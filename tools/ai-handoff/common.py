#!/usr/bin/env python3
"""Shared helpers for Orbit External Architect handoff bundles."""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
ATTACHMENT_INBOX = PROJECT_ROOT / ".ai-handoff" / "attachments"

SOURCE_ROOTS = (
    ("App", Path("app")),
    ("Backend", Path("backend")),
    ("AI", Path("ai")),
)

EXCLUDED_DIR_NAMES = {
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    ".superdesign/tmp",
    ".venv",
    "build",
    "DerivedData",
    "dist",
    "node_modules",
    "output",
    "screenshots",
    "xcuserdata",
}

EXCLUDED_FILE_NAMES = {
    ".DS_Store",
}

EXCLUDED_FILE_PATTERNS = (
    "*.apk",
    "*.aab",
    "*.ipa",
    "*.class",
    "*.jar",
    "*.log",
    "*.mp4",
    "*.mov",
    "*.m4v",
    "*.xcuserstate",
    "*.zip",
)

EXCLUDED_RELATIVE_PREFIXES = (
    ".ai-handoff/attachments",
    ".ai-handoff/requests",
    ".ai-handoff/work-orders",
    ".ai-handoff/executions",
    ".ai-handoff/reviews",
)

SECRET_FILE_NAMES = {
    ".env",
    "local.properties",
}

SECRET_FILE_PATTERNS = (
    ".env.*",
    "*.jks",
    "*.keystore",
    "*.p12",
    "*.pem",
)

SECRET_PATTERNS = (
    re.compile(r"\b(?:OPENAI_API_KEY|API[_-]?KEY|SECRET|TOKEN|PASSWORD)\s*[:=]\s*['\"]?[^'\"\s]{12,}"),
    re.compile(r"\b(?:apiKey|secret|token|password)\s*[:=]\s*['\"][^'\"\s]{12,}['\"]"),
    re.compile(r"\bsk-[A-Za-z0-9_-]{20,}"),
    re.compile("BEGIN " + "RSA PRIVATE KEY", re.IGNORECASE),
    re.compile("BEGIN " + "OPENSSH PRIVATE KEY", re.IGNORECASE),
    re.compile("BEGIN " + "PRIVATE KEY", re.IGNORECASE),
)

ATTACHMENT_EXTENSIONS = {
    ".csv",
    ".docx",
    ".html",
    ".json",
    ".md",
    ".markdown",
    ".pdf",
    ".rtf",
    ".txt",
    ".yaml",
    ".yml",
}

TEXT_ATTACHMENT_EXTENSIONS = {
    ".csv",
    ".html",
    ".json",
    ".md",
    ".markdown",
    ".rtf",
    ".txt",
    ".yaml",
    ".yml",
}

MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024


@dataclass(frozen=True)
class ProjectFile:
    source: Path
    relative: Path


@dataclass(frozen=True)
class SecretFinding:
    relative: Path
    reason: str
    kind: str


@dataclass(frozen=True)
class AttachmentInfo:
    source: Path
    bundle_path: Path
    size_bytes: int
    sha256: str


@dataclass(frozen=True)
class SourceRootInfo:
    label: str
    relative: Path
    exists: bool
    included_files: int


def task_id(value: str) -> str:
    value = value.strip()
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]*", value):
        raise argparse.ArgumentTypeError(
            "task must start with an alphanumeric character and contain only letters, digits, dot, underscore, or hyphen",
        )
    return value


def run_git(args: list[str]) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=PROJECT_ROOT,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    return result.stdout.strip()


def git_state() -> str:
    branch = run_git(["branch", "--show-current"]) or "(detached)"
    head = run_git(["rev-parse", "HEAD"]) or "(unknown)"
    status = run_git(["status", "--short"]) or "(clean)"
    return "\n".join(
        [
            "# Git State",
            "",
            f"Branch: {branch}",
            f"HEAD: {head}",
            "",
            "## Status",
            "",
            "```text",
            status,
            "```",
            "",
        ],
    )


def git_diff() -> str:
    diff = run_git(["diff", "--binary"])
    staged = run_git(["diff", "--cached", "--binary"])
    parts: list[str] = []
    if diff:
        parts.append(diff)
    if staged:
        parts.append(staged)
    return "\n\n".join(parts)


def is_secret_path(relative: Path) -> bool:
    name = relative.name
    if name in SECRET_FILE_NAMES:
        return True
    return any(fnmatch.fnmatch(name, pattern) for pattern in SECRET_FILE_PATTERNS)


def is_excluded_dir(relative: Path) -> bool:
    parts = relative.parts
    if not parts:
        return False
    if parts[-1] in EXCLUDED_DIR_NAMES:
        return True
    as_posix = relative.as_posix()
    if any(as_posix == item or as_posix.startswith(f"{item}/") for item in EXCLUDED_RELATIVE_PREFIXES):
        return True
    return any(as_posix == item or as_posix.endswith(f"/{item}") for item in EXCLUDED_DIR_NAMES)


def is_excluded_file(relative: Path) -> bool:
    as_posix = relative.as_posix()
    if any(as_posix == item or as_posix.startswith(f"{item}/") for item in EXCLUDED_RELATIVE_PREFIXES):
        return True
    name = relative.name
    if name in EXCLUDED_FILE_NAMES:
        return True
    if any(fnmatch.fnmatch(name, pattern) for pattern in EXCLUDED_FILE_PATTERNS):
        return True
    return is_secret_path(relative)


def iter_project_files() -> list[ProjectFile]:
    files: list[ProjectFile] = []
    for root, dirs, filenames in os.walk(PROJECT_ROOT):
        root_path = Path(root)
        root_relative = root_path.relative_to(PROJECT_ROOT)
        dirs[:] = [
            item
            for item in dirs
            if not is_excluded_dir((root_relative / item) if root_relative.parts else Path(item))
        ]
        for filename in filenames:
            source = root_path / filename
            relative = source.relative_to(PROJECT_ROOT)
            if is_excluded_file(relative):
                continue
            files.append(ProjectFile(source=source, relative=relative))
    return sorted(files, key=lambda item: item.relative.as_posix())


def iter_source_root_dirs() -> list[Path]:
    dirs: list[Path] = []
    for _, relative_root in SOURCE_ROOTS:
        source_root = PROJECT_ROOT / relative_root
        if not source_root.is_dir():
            continue
        for root, dirnames, _ in os.walk(source_root):
            root_path = Path(root)
            root_relative = root_path.relative_to(PROJECT_ROOT)
            if is_excluded_dir(root_relative):
                continue
            dirs.append(root_relative)
            dirnames[:] = [
                item
                for item in dirnames
                if not is_excluded_dir(root_relative / item)
            ]
    return sorted(set(dirs), key=lambda item: item.as_posix())


def scan_for_secrets() -> list[SecretFinding]:
    findings: list[SecretFinding] = []
    for root, dirs, filenames in os.walk(PROJECT_ROOT):
        root_path = Path(root)
        root_relative = root_path.relative_to(PROJECT_ROOT)
        dirs[:] = [
            item
            for item in dirs
            if not is_excluded_dir((root_relative / item) if root_relative.parts else Path(item))
        ]
        for filename in filenames:
            source = root_path / filename
            relative = source.relative_to(PROJECT_ROOT)
            if is_secret_path(relative):
                findings.append(SecretFinding(relative=relative, reason="sensitive file name", kind="excluded_path"))
                continue
            if is_excluded_file(relative):
                continue
            try:
                sample = source.read_text(encoding="utf-8", errors="ignore")
            except OSError as exc:
                findings.append(SecretFinding(relative=relative, reason=f"could not read for secret scan ({exc})", kind="read_error"))
                continue
            for pattern in SECRET_PATTERNS:
                if pattern.search(sample):
                    findings.append(
                        SecretFinding(
                            relative=relative,
                            reason=f"matched secret pattern {pattern.pattern}",
                            kind="content",
                        ),
                    )
                    break
    return findings


def abort_on_secrets(*, allow_sensitive_path_exclusion: bool = False) -> None:
    findings = scan_for_secrets()
    if not findings:
        return
    blocking = [
        finding
        for finding in findings
        if not (allow_sensitive_path_exclusion and finding.kind == "excluded_path")
    ]
    allowed = [
        finding
        for finding in findings
        if allow_sensitive_path_exclusion and finding.kind == "excluded_path"
    ]
    for finding in allowed:
        print(
            f"WARNING: excluded sensitive path from bundle: {finding.relative} ({finding.reason})",
            file=sys.stderr,
        )
    if not blocking:
        return
    print("ABORT: possible secrets found. No bundle was created.", file=sys.stderr)
    for finding in blocking:
        print(f"- {finding.relative}: {finding.reason}", file=sys.stderr)
    sys.exit(2)


def secret_match(text: str) -> str | None:
    for pattern in SECRET_PATTERNS:
        if pattern.search(text):
            return pattern.pattern
    return None


def sanitize_attachment_name(path: Path, *, base_dir: Path | None = None) -> str:
    if base_dir is not None:
        try:
            raw_name = path.relative_to(base_dir).as_posix()
        except ValueError:
            raw_name = path.name
    else:
        raw_name = path.name
    name = re.sub(r"[^A-Za-z0-9._-]+", "_", raw_name.strip())
    name = name.strip("._")
    if not name:
        name = "attachment"
    return name


def unique_attachment_name(source: Path, used_names: set[str], *, base_dir: Path | None = None) -> str:
    name = sanitize_attachment_name(source, base_dir=base_dir)
    candidate = name
    stem = Path(name).stem or "attachment"
    suffix = Path(name).suffix
    counter = 2
    while candidate in used_names:
        candidate = f"{stem}-{counter}{suffix}"
        counter += 1
    used_names.add(candidate)
    return candidate


def validate_attachment(path: Path) -> None:
    if not path.exists():
        raise SystemExit(f"Attachment not found: {path}")
    if not path.is_file():
        raise SystemExit(f"Attachment is not a file: {path}")
    if is_secret_path(Path(path.name)):
        raise SystemExit(f"Attachment has a sensitive file name and was not bundled: {path}")
    if path.suffix.lower() not in ATTACHMENT_EXTENSIONS:
        raise SystemExit(f"Attachment type is not allowed: {path}")

    size_bytes = path.stat().st_size
    if size_bytes > MAX_ATTACHMENT_BYTES:
        raise SystemExit(f"Attachment is too large ({size_bytes} bytes): {path}")

    suffix = path.suffix.lower()
    if suffix in TEXT_ATTACHMENT_EXTENSIONS:
        text = path.read_text(encoding="utf-8", errors="ignore")
        match = secret_match(text)
        if match:
            raise SystemExit(f"Attachment matched secret pattern {match}: {path}")
    else:
        sample = path.read_bytes()
        match = secret_match(sample.decode("utf-8", errors="ignore"))
        if match:
            raise SystemExit(f"Attachment matched secret pattern {match}: {path}")
        if suffix == ".docx":
            try:
                with zipfile.ZipFile(path) as archive:
                    doc_text = "\n".join(
                        archive.read(name).decode("utf-8", errors="ignore")
                        for name in archive.namelist()
                        if name.startswith("word/") and name.endswith(".xml")
                    )
            except zipfile.BadZipFile as exc:
                raise SystemExit(f"Attachment is not a valid docx file: {path}") from exc
            match = secret_match(doc_text)
            if match:
                raise SystemExit(f"Attachment matched secret pattern {match}: {path}")


def attachment_inputs(explicit_paths: list[Path]) -> list[tuple[Path, Path | None]]:
    inputs: list[tuple[Path, Path | None]] = []
    if ATTACHMENT_INBOX.exists():
        if not ATTACHMENT_INBOX.is_dir():
            raise SystemExit(f"Attachment inbox is not a directory: {ATTACHMENT_INBOX}")
        for path in sorted(ATTACHMENT_INBOX.rglob("*")):
            if path.is_file():
                inputs.append((path, ATTACHMENT_INBOX))
    for raw_path in explicit_paths:
        inputs.append((raw_path, None))
    return inputs


def copy_attachments(inputs: list[tuple[Path, Path | None]], destination: Path) -> list[AttachmentInfo]:
    infos: list[AttachmentInfo] = []
    used_names: set[str] = set()
    for raw_path, base_dir in inputs:
        source = raw_path.expanduser().resolve()
        validate_attachment(source)
        resolved_base_dir = base_dir.expanduser().resolve() if base_dir is not None else None
        name = unique_attachment_name(source, used_names, base_dir=resolved_base_dir)
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        infos.append(
            AttachmentInfo(
                source=source,
                bundle_path=Path("attachments") / name,
                size_bytes=source.stat().st_size,
                sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
            ),
        )
    return infos


def validate_attachments(inputs: list[tuple[Path, Path | None]]) -> None:
    for raw_path, _ in inputs:
        validate_attachment(raw_path.expanduser().resolve())


def attachment_block(attachments: list[AttachmentInfo]) -> str:
    if not attachments:
        return "None."
    lines: list[str] = []
    for attachment in attachments:
        lines.extend(
            [
                f"- Bundle path: `{attachment.bundle_path.as_posix()}`",
                f"  Source path: `{attachment.source}`",
                f"  Size: {attachment.size_bytes} bytes",
                f"  SHA-256: `{attachment.sha256}`",
            ],
        )
    return "\n".join(lines)


def reset_dir(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)


def copy_project_snapshot(destination: Path) -> list[ProjectFile]:
    files = iter_project_files()
    for relative_dir in iter_source_root_dirs():
        (destination / relative_dir).mkdir(parents=True, exist_ok=True)
    for item in files:
        target = destination / item.relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(item.source, target)
    return files


def is_relative_to(relative: Path, parent: Path) -> bool:
    try:
        relative.relative_to(parent)
    except ValueError:
        return False
    return True


def source_root_coverage(files: list[ProjectFile]) -> list[SourceRootInfo]:
    coverage: list[SourceRootInfo] = []
    for label, relative_root in SOURCE_ROOTS:
        included_files = sum(1 for item in files if is_relative_to(item.relative, relative_root))
        coverage.append(
            SourceRootInfo(
                label=label,
                relative=relative_root,
                exists=(PROJECT_ROOT / relative_root).exists(),
                included_files=included_files,
            ),
        )
    return coverage


def source_coverage_text(files: list[ProjectFile]) -> str:
    lines = [
        "# Source Coverage",
        "",
        "The handoff bundle must preserve NexusFlow's three implementation roots: app, backend, and ai.",
        "",
        "| Area | Root | Status | Included files |",
        "| --- | --- | --- | --- |",
    ]
    for info in source_root_coverage(files):
        if not info.exists:
            status = "missing"
        elif info.included_files == 0:
            status = "present but no included files"
        else:
            status = "included"
        lines.append(
            f"| {info.label} | `{info.relative.as_posix()}/` | {status} | {info.included_files} |",
        )
    lines.extend(
        [
            "",
            "Shared contracts, repository rules, build files, scripts, docs, and skills are included by the whole-repository snapshot when they pass the safety filters.",
            "Sensitive files, generated outputs, caches, IDE files, binaries, large media, logs, and prior handoff artifacts remain excluded.",
            "",
        ],
    )
    return "\n".join(lines)


def tree_text(files: list[ProjectFile]) -> str:
    lines = ["# Included Project Files", ""]
    lines.extend(item.relative.as_posix() for item in files)
    lines.append("")
    return "\n".join(lines)


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def read_template(name: str) -> str:
    path = PROJECT_ROOT / ".ai-handoff" / "templates" / name
    return path.read_text(encoding="utf-8")


def render_template(template: str, values: dict[str, str]) -> str:
    result = template
    for key, value in values.items():
        result = result.replace(f"{{{{{key}}}}}", value)
    return result


def list_block(values: list[str], default: str) -> str:
    cleaned = [value.strip() for value in values if value.strip()]
    if not cleaned:
        return default
    return "\n".join(f"- {value}" for value in cleaned)


def zip_dir(source_dir: Path, zip_path: Path) -> None:
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source_dir.rglob("*")):
            if path.is_dir():
                archive.write(path, f"{path.relative_to(source_dir).as_posix()}/")
                continue
            if path.is_file():
                archive.write(path, path.relative_to(source_dir))


def print_manifest(files: list[ProjectFile]) -> None:
    print("Source coverage:")
    for info in source_root_coverage(files):
        if not info.exists:
            status = "missing"
        elif info.included_files == 0:
            status = "present but no included files"
        else:
            status = "included"
        print(f"- {info.label}: {status} ({info.included_files} files under {info.relative.as_posix()}/)")
    print()
    print("Included project files:")
    for item in files:
        print(f"- {item.relative.as_posix()}")

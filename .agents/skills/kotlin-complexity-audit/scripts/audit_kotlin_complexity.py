#!/usr/bin/env python3
"""Run a pinned Detekt and Compose complexity audit and produce class-level reports.

The script intentionally separates deterministic findings from semantic review:
Detekt and source-text Compose heuristics report measurable signals; the
generated Markdown/JSON gives an agent the evidence needed to decide whether a
class or Composable deserves semantic inspection.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


DEFAULT_DETEKT_VERSION = "1.23.8"
DEFAULT_CYCLOMATIC_THRESHOLD = 15
DEFAULT_COGNITIVE_THRESHOLD = 15
DEFAULT_OUTPUT_DIR = ".codex/complexity-audit"
COMPOSE_METHOD_LINE_THRESHOLD = 120
COMPOSE_PARAMETER_THRESHOLD = 8
COMPOSE_CALLBACK_THRESHOLD = 5
COMPOSE_BRANCH_THRESHOLD = 8
COMPOSE_EFFECT_THRESHOLD = 2
COMPOSE_STATE_THRESHOLD = 4
COMPOSE_SIGNAL_SCORE = 2
COMPOSE_STRONG_SIGNAL_SCORE = 6

TEST_SOURCE_SETS = {
    "androidTest",
    "commonTest",
    "iosTest",
    "jsTest",
    "jvmTest",
    "macosTest",
    "nativeTest",
    "test",
    "tvosTest",
    "wasmTest",
}
EXCLUDED_PARTS = {".git", ".gradle", ".idea", "build", "generated", "Pods", "DerivedData"}

MAJOR_RULES = {
    "CognitiveComplexMethod",
    "CyclomaticComplexMethod",
    "LargeClass",
    "LongMethod",
    "TooManyFunctions",
    "ComplexCondition",
}
SECONDARY_RULES = {
    "LongParameterList",
    "NestedBlockDepth",
    "ReturnCount",
    "ThrowsCount",
    "ThrowingExceptionsWithoutMessageOrCause",
    "TooGenericExceptionCaught",
}
AUDIT_RULES = MAJOR_RULES | SECONDARY_RULES
RULE_WEIGHTS = {
    "CognitiveComplexMethod": 5,
    "CyclomaticComplexMethod": 5,
    "LargeClass": 4,
    "LongMethod": 4,
    "TooManyFunctions": 4,
    "ComplexCondition": 2,
    "ReturnCount": 1,
    "TooGenericExceptionCaught": 1,
    "LongParameterList": 1,
    "NestedBlockDepth": 1,
    "ThrowsCount": 1,
    "ThrowingExceptionsWithoutMessageOrCause": 1,
}

CLASS_PATTERN = re.compile(
    r"^\s*(?:(?:public|private|protected|internal|abstract|open|final|sealed|data|enum|"
    r"annotation|value|expect|actual|inner|companion|fun)\s+)*"
    r"(class|interface|object)\s+([A-Za-z_]\w*)"
)
FUNCTION_PATTERN = re.compile(
    r"^\s*(?:(?:public|private|protected|internal|abstract|open|final|suspend|inline|"
    r"infix|operator|tailrec|override|expect|actual|external)\s+)*fun\s+([A-Za-z_]\w*)"
)
COMPOSABLE_INLINE_FUNCTION_PATTERN = re.compile(
    r"^\s*@Composable\b.*\bfun\s+([A-Za-z_]\w*)"
)
COMPOSE_EFFECT_NAMES = (
    "LaunchedEffect",
    "DisposableEffect",
    "SideEffect",
    "produceState",
    "snapshotFlow",
    "pointerInput",
)
COMPOSE_STATE_CALL_PATTERN = re.compile(
    r"\b(?:remember|rememberSaveable|rememberUpdatedState|rememberCoroutineScope|"
    r"mutableStateOf|mutableStateListOf|mutableStateMapOf|derivedStateOf|"
    r"collectAsState|collectAsStateWithLifecycle)\s*\("
)
COMPOSE_BRANCH_PATTERN = re.compile(r"\b(?:if|when|for|while|catch)\b|\?\:")
COMPOSE_EFFECT_PATTERN = re.compile(
    r"\b(?:LaunchedEffect|DisposableEffect|SideEffect|produceState|snapshotFlow|pointerInput)\b"
)
COMPOSE_LAYOUT_PATTERN = re.compile(
    r"\b(?:LazyColumn|LazyRow|LazyVerticalGrid|LazyHorizontalGrid|Canvas|SubcomposeLayout)\b"
)
COMPOSE_GESTURE_PATTERN = re.compile(
    r"\b(?:pointerInput|detectTapGestures|detectDragGestures|clickable|draggable|scrollable)\b"
)
COMPOSE_NAVIGATION_PATTERN = re.compile(r"\b(?:NavController|navigate|popBackStack)\b")
COMPOSE_DATA_PATTERN = re.compile(
    r"\.(?:map|filter|flatMap|groupBy|indexOfFirst|take|drop|sortedBy|distinctUntilChanged)\s*\("
)
METRIC_PATTERNS = (
    (re.compile(r"complexity:\s*(\d+).*?threshold.*?'(\d+)'", re.IGNORECASE), "complexity"),
    (re.compile(r"too long \((\d+)\).*?maximum length is (\d+)", re.IGNORECASE), "length"),
    (re.compile(r"with '(\d+)' functions detected.*?'(\d+)'", re.IGNORECASE), "count"),
    (re.compile(r"has (\d+) return statements.*?limit of (\d+)", re.IGNORECASE), "count"),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Audit Kotlin class and method complexity with Detekt."
    )
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="Kotlin project root.")
    parser.add_argument(
        "--include-tests",
        action="store_true",
        help="Include commonTest/androidTest and other test source sets.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="Write complexity-audit.md and complexity-audit.json here.",
    )
    parser.add_argument(
        "--detekt-jar",
        type=Path,
        help="Use a local Detekt CLI jar instead of discovering/downloading one.",
    )
    parser.add_argument(
        "--detekt-version",
        default=DEFAULT_DETEKT_VERSION,
        help=f"Pinned Detekt version for automatic download (default: {DEFAULT_DETEKT_VERSION}).",
    )
    parser.add_argument(
        "--no-download",
        action="store_true",
        help="Fail instead of downloading Detekt when no local executable/jar is found.",
    )
    parser.add_argument(
        "--show-clean",
        action="store_true",
        help="Include classes with no Detekt findings in the Markdown report.",
    )
    return parser.parse_args()


def find_kotlin_files(root: Path, include_tests: bool) -> list[Path]:
    files: list[Path] = []
    for path in sorted(root.rglob("*.kt")):
        relative_parts = path.relative_to(root).parts
        if any(part in EXCLUDED_PARTS for part in relative_parts):
            continue
        if not include_tests and any(part in TEST_SOURCE_SETS for part in relative_parts):
            continue
        files.append(path)
    return files


def strip_kotlin_literals(lines: list[str]) -> list[str]:
    """Remove comments and string/character contents while preserving line shape."""
    sanitized: list[str] = []
    block_comment = False
    string_literal = False
    triple_string = False
    char_literal = False

    for line in lines:
        output: list[str] = []
        index = 0
        while index < len(line):
            if block_comment:
                end = line.find("*/", index)
                if end < 0:
                    output.extend(" " for _ in line[index:])
                    break
                output.extend(" " for _ in line[index : end + 2])
                index = end + 2
                block_comment = False
                continue
            if triple_string:
                end = line.find('"""', index)
                if end < 0:
                    output.extend(" " for _ in line[index:])
                    break
                output.extend(" " for _ in line[index : end + 3])
                index = end + 3
                triple_string = False
                continue
            if string_literal or char_literal:
                closing = '"' if string_literal else "'"
                if line[index] == "\\":
                    output.extend((" ", " "))
                    index += 2
                    continue
                if line[index] == closing:
                    output.append(" ")
                    index += 1
                    string_literal = False
                    char_literal = False
                    continue
                output.append(" ")
                index += 1
                continue
            if line.startswith("//", index):
                output.extend(" " for _ in line[index:])
                break
            if line.startswith("/*", index):
                output.extend((" ", " "))
                index += 2
                block_comment = True
                continue
            if line.startswith('"""', index):
                output.extend((" ", " ", " "))
                index += 3
                triple_string = True
                continue
            if line[index] == '"':
                output.append(" ")
                index += 1
                string_literal = True
                continue
            if line[index] == "'":
                output.append(" ")
                index += 1
                char_literal = True
                continue
            output.append(line[index])
            index += 1
        sanitized.append("".join(output))
    return sanitized


def matching_brace_line(lines: list[str], start_line: int, start_column: int) -> int:
    depth = 0
    for line_number in range(start_line, len(lines) + 1):
        line = lines[line_number - 1]
        start = start_column if line_number == start_line else 0
        for character in line[start:]:
            if character == "{":
                depth += 1
            elif character == "}":
                depth -= 1
                if depth == 0:
                    return line_number
    return len(lines)


def matching_delimiter(text: str, start: int, opening: str, closing: str) -> int:
    depth = 0
    for index in range(start, len(text)):
        character = text[index]
        if character == opening:
            depth += 1
        elif character == closing:
            depth -= 1
            if depth == 0:
                return index
    return len(text) - 1


def split_top_level(text: str, delimiter: str = ",") -> list[str]:
    parts: list[str] = []
    start = 0
    stack: list[str] = []
    pairs = {"(": ")", "[": "]", "{": "}"}
    for index, character in enumerate(text):
        if character in pairs:
            stack.append(pairs[character])
        elif stack and character == stack[-1]:
            stack.pop()
        elif character == delimiter and not stack:
            parts.append(text[start:index].strip())
            start = index + 1
    parts.append(text[start:].strip())
    return [part for part in parts if part]


def function_body(
    sanitized_lines: list[str], start_line: int, end_line: int | None = None
) -> tuple[str, int, int] | None:
    last_line = end_line or len(sanitized_lines)
    opening_brace: tuple[int, int] | None = None
    for line_number in range(start_line, min(last_line, len(sanitized_lines)) + 1):
        column = sanitized_lines[line_number - 1].find("{")
        if column >= 0:
            opening_brace = (line_number, column)
            break
    if not opening_brace:
        return None
    body_end = matching_brace_line(sanitized_lines, *opening_brace)
    body = "\n".join(sanitized_lines[start_line - 1 : body_end])
    return body, opening_brace[0], body_end


def function_parameters(
    sanitized_lines: list[str], function_name: str, start_line: int, end_line: int
) -> tuple[int, int]:
    source = "\n".join(sanitized_lines[start_line - 1 : end_line])
    function_match = re.search(rf"\bfun\s+{re.escape(function_name)}\b", source)
    if not function_match:
        return 0, 0
    opening = source.find("(", function_match.end())
    if opening < 0:
        return 0, 0
    closing = matching_delimiter(source, opening, "(", ")")
    parameters = source[opening + 1 : closing]
    return len(split_top_level(parameters)), parameters.count("->")


def effect_key_warnings(effect_name: str, arguments: str, body: str) -> list[dict[str, object]]:
    warnings: list[dict[str, object]] = []
    for key in split_top_level(arguments):
        if key in {"Unit", "true", "false"}:
            continue
        identifiers = re.findall(r"\b[A-Za-z_]\w*\b", key)
        if not identifiers:
            continue
        primary_identifier = identifiers[0]
        if re.search(rf"\b{re.escape(primary_identifier)}\b", body):
            continue
        warnings.append(
            {
                "rule": "ComposeEffectKeyNotRead",
                "kind": "semantic warning",
                "score": 1,
                "message": (
                    f"{effect_name} key `{key}` is not directly read in its body; "
                    "review whether it is an intentional invalidation trigger."
                ),
                "key": key,
            }
        )
    return warnings


def analyze_composable_function(
    function: dict[str, object], sanitized_lines: list[str]
) -> dict[str, object]:
    start_line = int(function["line"])
    end_line = int(function.get("end_line", start_line))
    body_result = function_body(sanitized_lines, start_line, end_line)
    body = body_result[0] if body_result else ""
    parameter_count, callback_count = function_parameters(
        sanitized_lines, str(function["name"]), start_line, end_line
    )
    effect_calls = COMPOSE_EFFECT_PATTERN.findall(body)
    state_calls = COMPOSE_STATE_CALL_PATTERN.findall(body)
    branches = len(COMPOSE_BRANCH_PATTERN.findall(body))
    categories: list[str] = []
    if state_calls:
        categories.append("state")
    if effect_calls:
        categories.append("effects")
    if COMPOSE_LAYOUT_PATTERN.search(body):
        categories.append("layout")
    if COMPOSE_GESTURE_PATTERN.search(body):
        categories.append("gestures")
    if COMPOSE_DATA_PATTERN.search(body):
        categories.append("data preparation")
    if COMPOSE_NAVIGATION_PATTERN.search(body):
        categories.append("navigation")

    findings: list[dict[str, object]] = []
    score = 0
    line_count = max(0, end_line - start_line + 1)

    def add_finding(rule: str, message: str, weight: int, **extra: object) -> None:
        nonlocal score
        score += weight
        findings.append(
            {
                "rule": rule,
                "kind": "semantic warning",
                "score": weight,
                "message": message,
                **extra,
            }
        )

    if line_count > COMPOSE_METHOD_LINE_THRESHOLD:
        add_finding(
            "ComposeLongMethod",
            f"Composable function is {line_count} lines; review UI state and side-effect boundaries.",
            3,
            value=line_count,
            threshold=COMPOSE_METHOD_LINE_THRESHOLD,
        )
    if parameter_count > COMPOSE_PARAMETER_THRESHOLD:
        add_finding(
            "ComposeLongParameterList",
            f"Composable function has {parameter_count} parameters; review state/event grouping without creating a catch-all state object.",
            2,
            value=parameter_count,
            threshold=COMPOSE_PARAMETER_THRESHOLD,
        )
    if callback_count > COMPOSE_CALLBACK_THRESHOLD:
        add_finding(
            "ComposeCallbackSurface",
            f"Composable function exposes {callback_count} callback parameters; review event ownership and API cohesion.",
            2,
            value=callback_count,
            threshold=COMPOSE_CALLBACK_THRESHOLD,
        )
    if branches > COMPOSE_BRANCH_THRESHOLD:
        add_finding(
            "ComposeBranchComplexity",
            f"Composable function contains {branches} control-flow branches; review UI state modeling and nesting.",
            2,
            value=branches,
            threshold=COMPOSE_BRANCH_THRESHOLD,
        )
    if len(effect_calls) > COMPOSE_EFFECT_THRESHOLD:
        add_finding(
            "ComposeMultipleEffects",
            f"Composable function contains {len(effect_calls)} effect or input handlers; review lifecycle and side-effect ownership.",
            2,
            value=len(effect_calls),
            threshold=COMPOSE_EFFECT_THRESHOLD,
        )
    if len(state_calls) > COMPOSE_STATE_THRESHOLD:
        add_finding(
            "ComposeStateSurface",
            f"Composable function contains {len(state_calls)} state-related calls; review state ownership and derived state boundaries.",
            1,
            value=len(state_calls),
            threshold=COMPOSE_STATE_THRESHOLD,
        )
    if len(categories) >= 3:
        add_finding(
            "ComposeMixedResponsibilities",
            "Composable function mixes " + ", ".join(categories) + "; review extraction by responsibility and lifecycle.",
            3,
            categories=categories,
        )

    if body:
        for effect_name in COMPOSE_EFFECT_NAMES:
            if effect_name == "snapshotFlow":
                # snapshotFlow takes an observed expression, not restart keys.
                continue
            for match in re.finditer(rf"\b{re.escape(effect_name)}\b", body):
                opening = body.find("(", match.end())
                if opening < 0:
                    continue
                closing = matching_delimiter(body, opening, "(", ")")
                effect_body_start = body.find("{", closing + 1)
                if effect_body_start < 0:
                    continue
                effect_body_end = matching_delimiter(body, effect_body_start, "{", "}")
                arguments = body[opening + 1 : closing]
                effect_body = body[effect_body_start + 1 : effect_body_end]
                key_warnings = effect_key_warnings(effect_name, arguments, effect_body)
                for warning in key_warnings:
                    score += int(warning["score"])
                    findings.append(warning)

    return {
        "function": function["name"],
        "line": start_line,
        "end_line": end_line,
        "line_count": line_count,
        "parameter_count": parameter_count,
        "callback_count": callback_count,
        "branch_count": branches,
        "effect_count": len(effect_calls),
        "state_call_count": len(state_calls),
        "responsibilities": categories,
        "score": score,
        "findings": findings,
    }


def compose_analysis(
    files: list[Path], root: Path
) -> tuple[list[dict[str, object]], list[dict[str, object]]]:
    methods: list[dict[str, object]] = []
    findings: list[dict[str, object]] = []
    for path in files:
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        sanitized_lines = strip_kotlin_literals(lines)
        classes, functions = declarations(path)
        relative = str(path.relative_to(root))
        for function in functions:
            if not function.get("composable"):
                continue
            method = analyze_composable_function(function, sanitized_lines)
            method["file"] = relative
            method["class"] = (
                containing_class(classes, int(function["line"])) or {"name": "<top-level>"}
            )["name"]
            methods.append(method)
            for finding in method["findings"]:
                findings.append(
                    {
                        **finding,
                        "file": relative,
                        "line": function["line"],
                        "method": function["name"],
                    }
                )
    return methods, findings


def declarations(path: Path) -> tuple[list[dict[str, object]], list[dict[str, object]]]:
    classes: list[dict[str, object]] = []
    functions: list[dict[str, object]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()

    sanitized_lines = strip_kotlin_literals(lines)
    for line_number, line in enumerate(lines, start=1):
        class_match = CLASS_PATTERN.match(line)
        if class_match:
            classes.append(
                {
                    "name": class_match.group(2),
                    "kind": class_match.group(1),
                    "line": line_number,
                }
            )
        function_match = FUNCTION_PATTERN.match(line)
        inline_composable_match = COMPOSABLE_INLINE_FUNCTION_PATTERN.match(line)
        if not function_match and inline_composable_match:
            function_name = inline_composable_match.group(1)
        else:
            function_name = function_match.group(1) if function_match else None
        if function_name:
            composable = bool(inline_composable_match)
            if not composable:
                for previous_line in lines[max(0, line_number - 5) : line_number - 1]:
                    if re.search(r"@Composable\b", previous_line):
                        composable = True
                        break
            functions.append(
                {
                    "name": function_name,
                    "line": line_number,
                    "composable": composable,
                }
            )

    for class_item in classes:
        start_line = int(class_item["line"])
        opening_brace: tuple[int, int] | None = None
        for line_number in range(start_line, len(sanitized_lines) + 1):
            if line_number > start_line and (
                CLASS_PATTERN.match(lines[line_number - 1])
                or FUNCTION_PATTERN.match(lines[line_number - 1])
            ):
                break
            column = sanitized_lines[line_number - 1].find("{")
            if column >= 0:
                opening_brace = (line_number, column)
                break
        class_item["end_line"] = (
            matching_brace_line(sanitized_lines, *opening_brace)
            if opening_brace
            else start_line
        )
    for function_item in functions:
        start_line = int(function_item["line"])
        next_declaration = min(
            (
                int(other["line"])
                for other in [*classes, *functions]
                if int(other["line"]) > start_line
            ),
            default=len(sanitized_lines) + 1,
        )
        body_result = function_body(sanitized_lines, start_line, next_declaration - 1)
        function_item["end_line"] = body_result[2] if body_result else start_line
    return classes, functions


def nearest_declaration(
    items: list[dict[str, object]], line: int
) -> dict[str, object] | None:
    candidates = [item for item in items if int(item["line"]) <= line]
    return max(candidates, key=lambda item: int(item["line"])) if candidates else None


def containing_class(
    classes: list[dict[str, object]], line: int
) -> dict[str, object] | None:
    candidates = [
        item
        for item in classes
        if int(item["line"]) <= line <= int(item.get("end_line", item["line"]))
    ]
    return min(
        candidates,
        key=lambda item: int(item.get("end_line", item["line"])) - int(item["line"]),
    ) if candidates else None


def parse_metric(message: str) -> tuple[int | None, int | None, str | None]:
    for pattern, metric_name in METRIC_PATTERNS:
        match = pattern.search(message)
        if match:
            return int(match.group(1)), int(match.group(2)), metric_name
    return None, None, None


def parse_detekt_report(report_path: Path, root: Path) -> list[dict[str, object]]:
    findings: list[dict[str, object]] = []
    tree = ET.parse(report_path)
    for file_node in tree.getroot().findall("file"):
        raw_path = file_node.attrib.get("name", "")
        file_path = Path(raw_path)
        try:
            relative_path = str(file_path.relative_to(root))
        except ValueError:
            relative_path = raw_path
        for error in file_node.findall("error"):
            source = error.attrib.get("source", "")
            rule = source.rsplit(".", maxsplit=1)[-1]
            if rule not in AUDIT_RULES:
                continue
            message = error.attrib.get("message", "")
            value, threshold, metric_name = parse_metric(message)
            findings.append(
                {
                    "file": relative_path,
                    "line": int(error.attrib.get("line", "0")),
                    "column": int(error.attrib.get("column", "0")),
                    "severity": error.attrib.get("severity", "warning"),
                    "rule": rule,
                    "message": message,
                    "value": value,
                    "threshold": threshold,
                    "metric": metric_name,
                }
            )
    return findings


def resolve_detekt(args: argparse.Namespace) -> list[str]:
    if args.detekt_jar:
        if not args.detekt_jar.is_file():
            raise FileNotFoundError(f"Detekt jar not found: {args.detekt_jar}")
        return ["java", "-jar", str(args.detekt_jar)]

    executable = shutil.which("detekt")
    if executable:
        return [executable]

    if args.no_download:
        raise RuntimeError(
            "Detekt was not found. Install it, pass --detekt-jar, or remove --no-download."
        )

    java = shutil.which("java")
    if not java:
        raise RuntimeError("Java is required to run Detekt, but no java executable was found.")

    cache_root = Path(tempfile.gettempdir()) / "codex-kotlin-class-complexity" / args.detekt_version
    cache_root.mkdir(parents=True, exist_ok=True)
    jar_path = cache_root / f"detekt-cli-{args.detekt_version}-all.jar"
    if not jar_path.exists():
        url = (
            "https://github.com/detekt/detekt/releases/download/"
            f"v{args.detekt_version}/detekt-cli-{args.detekt_version}-all.jar"
        )
        print(f"Downloading Detekt {args.detekt_version}...", file=sys.stderr)
        try:
            urllib.request.urlretrieve(url, jar_path)
        except Exception as error:
            jar_path.unlink(missing_ok=True)
            raise RuntimeError(f"Unable to download Detekt from {url}: {error}") from error
    return [java, "-jar", str(jar_path)]


def write_config(path: Path) -> None:
    path.write_text(
        """complexity:
  active: true
  CognitiveComplexMethod:
    active: true
    threshold: 15
""",
        encoding="utf-8",
    )


def run_detekt(
    detekt_command: list[str], root: Path, report_dir: Path, include_tests: bool
) -> tuple[Path, str]:
    xml_report = report_dir / "detekt.xml"
    text_report = report_dir / "detekt.txt"
    config_path = report_dir / "detekt-complexity.yml"
    write_config(config_path)
    exclude_patterns = [
        "**/.gradle/**",
        "**/.git/**",
        "**/build/**",
        "**/generated/**",
        "**/Pods/**",
        "**/DerivedData/**",
    ]
    if not include_tests:
        exclude_patterns.extend(f"**/{source_set}/**" for source_set in sorted(TEST_SOURCE_SETS))

    command = [
        *detekt_command,
        "--all-rules",
        "--build-upon-default-config",
        "--config",
        str(config_path),
        "--language-version",
        "2.2",
        "--input",
        str(root),
        "--includes",
        "**/*.kt",
        "--excludes",
        ",".join(exclude_patterns),
        "--report",
        f"xml:{xml_report}",
        "--report",
        f"txt:{text_report}",
        "--max-issues",
        "999999",
    ]
    completed = subprocess.run(command, capture_output=True, text=True, check=False)
    if not xml_report.exists():
        details = (completed.stdout + "\n" + completed.stderr).strip()
        raise RuntimeError(f"Detekt did not produce a report.\n{details}")
    return xml_report, completed.stdout + completed.stderr


def build_class_records(
    files: list[Path],
    findings: list[dict[str, object]],
    compose_findings: list[dict[str, object]],
    root: Path,
) -> list[dict[str, object]]:
    declarations_by_file: dict[str, tuple[list[dict[str, object]], list[dict[str, object]]]] = {}
    records: dict[tuple[str, str, int], dict[str, object]] = {}
    for path in files:
        relative = str(path.relative_to(root))
        classes, functions = declarations(path)
        declarations_by_file[relative] = (classes, functions)
        for class_item in classes:
            key = (relative, str(class_item["name"]), int(class_item["line"]))
            records[key] = {
                "class": class_item["name"],
                "kind": class_item["kind"],
                "file": relative,
                "line": class_item["line"],
                "status": "none",
                "signal_strength": "none",
                "score": 0,
                "detekt_score": 0,
                "findings": [],
                "compose_findings": [],
                "compose_score": 0,
            }

    for finding in findings:
        relative = str(finding["file"])
        line = int(finding["line"])
        classes, functions = declarations_by_file.get(relative, ([], []))
        class_item = containing_class(classes, line)
        function_item = nearest_declaration(functions, line)
        class_name = str(class_item["name"]) if class_item else "<top-level>"
        class_line = int(class_item["line"]) if class_item else 0
        key = (relative, class_name, class_line)
        if key not in records:
            records[key] = {
                "class": class_name,
                "kind": "unknown",
                "file": relative,
                "line": class_line or line,
                "status": "none",
                "signal_strength": "none",
                "score": 0,
                "detekt_score": 0,
                "findings": [],
                "compose_findings": [],
                "compose_score": 0,
            }
        issue = dict(finding)
        issue["method"] = function_item["name"] if function_item else None
        records[key]["findings"].append(issue)

    for finding in compose_findings:
        relative = str(finding["file"])
        line = int(finding["line"])
        classes, functions = declarations_by_file.get(relative, ([], []))
        class_item = containing_class(classes, line)
        class_name = str(class_item["name"]) if class_item else "<top-level>"
        class_line = int(class_item["line"]) if class_item else 0
        key = (relative, class_name, class_line)
        if key not in records:
            records[key] = {
                "class": class_name,
                "kind": "unknown",
                "file": relative,
                "line": class_line or line,
                "status": "none",
                "signal_strength": "none",
                "score": 0,
                "detekt_score": 0,
                "findings": [],
                "compose_findings": [],
                "compose_score": 0,
            }
        records[key]["compose_findings"].append(dict(finding))
        records[key]["compose_score"] += int(finding.get("score", 0))

    for record in records.values():
        issues = record["findings"]
        major = [issue for issue in issues if issue["rule"] in MAJOR_RULES]
        if major or int(record["compose_score"]) >= COMPOSE_STRONG_SIGNAL_SCORE:
            record["status"] = "strong-signal"
        elif issues or int(record["compose_score"]) >= COMPOSE_SIGNAL_SCORE:
            record["status"] = "signal"
        record["detekt_score"] = sum(RULE_WEIGHTS.get(issue["rule"], 1) for issue in issues)
        record["score"] = int(record["detekt_score"]) + int(record["compose_score"])
        rule_names = {str(issue["rule"]) for issue in issues}
        if any(rule in rule_names for rule in ("CognitiveComplexMethod", "CyclomaticComplexMethod")):
            record["signal_strength"] = "high"
        elif len(major) >= 2 or any(rule in rule_names for rule in ("LargeClass", "LongMethod")):
            record["signal_strength"] = "medium"
        elif issues or record["compose_score"]:
            record["signal_strength"] = "low"
    return sorted(
        records.values(),
        key=lambda record: (-int(record["score"]), str(record["file"]), int(record["line"])),
    )


def markdown_report(
    root: Path,
    files: list[Path],
    records: list[dict[str, object]],
    findings: list[dict[str, object]],
    compose_methods: list[dict[str, object]],
    compose_findings: list[dict[str, object]],
    detekt_version: str,
    include_tests: bool,
    show_clean: bool,
) -> str:
    strong_signals = [record for record in records if record["status"] == "strong-signal"]
    signals = [record for record in records if record["status"] == "signal"]
    clean = [record for record in records if record["status"] == "none"]
    generated_at = dt.datetime.now().astimezone().isoformat(timespec="seconds")
    lines = [
        "# Kotlin Class Complexity Audit",
        "",
        f"- Root: `{root}`",
        f"- Generated: `{generated_at}`",
        f"- Detekt: `{detekt_version}`",
        f"- Tests included: `{include_tests}`",
        "",
        "## Summary",
        "",
        f"- Kotlin files scanned: **{len(files)}**",
        f"- Classes found: **{len(records)}**",
        f"- Strong static signals: **{len(strong_signals)}**",
        f"- Other static signals: **{len(signals)}**",
        f"- Classes with no configured findings: **{len(clean)}**",
        f"- Total findings: **{len(findings) + len(compose_findings)}**",
        f"- Composable methods analyzed: **{len(compose_methods)}**",
        f"- Compose semantic warnings: **{len(compose_findings)}**",
        "",
        "This report contains static smoke signals only. It does not decide ownership, refactor priority, acceptable complexity, or human traceability. A strong signal is not a semantic refactor verdict.",
        "",
        "## Strong static signals",
        "",
        "| Signal strength | Class | File | Findings | Evidence |",
        "| --- | --- | --- | --- | --- |",
    ]
    if strong_signals:
        for record in strong_signals:
            all_issues = [*record["findings"], *record["compose_findings"]]
            issue_names = ", ".join(sorted({str(issue["rule"]) for issue in all_issues}))
            evidence = "; ".join(str(issue["message"]) for issue in all_issues[:3])
            lines.append(
                f"| {record['signal_strength']} | `{record['class']}` | `{record['file']}:{record['line']}` | "
                f"{issue_names} | {evidence} |"
            )
    else:
        lines.append("| - | - | - | - | No strong static signals. |")

    lines += ["", "## Other static signals", "", "| Signal strength | Class | File | Findings |", "| --- | --- | --- | --- |"]
    if signals:
        for record in signals:
            all_issues = [*record["findings"], *record["compose_findings"]]
            issue_names = ", ".join(sorted({str(issue["rule"]) for issue in all_issues}))
            lines.append(
                f"| {record['signal_strength']} | `{record['class']}` | `{record['file']}:{record['line']}` | {issue_names} |"
            )
    else:
        lines.append("| - | - | - | No other static signals. |")

    lines += [
        "",
        "## Compose method review",
        "",
        "| Score | Function | File | Responsibilities | Findings |",
        "| --- | --- | --- | --- | --- |",
    ]
    compose_methods_with_findings = [method for method in compose_methods if int(method["score"]) > 0]
    if compose_methods_with_findings:
        for method in sorted(compose_methods_with_findings, key=lambda item: -int(item["score"])):
            method_findings = "; ".join(str(item["message"]) for item in method["findings"][:3])
            responsibilities = ", ".join(str(item) for item in method["responsibilities"]) or "-"
            lines.append(
                f"| {method['score']} | `{method['function']}` | `{method['file']}:{method['line']}` | "
                f"{responsibilities} | {method_findings} |"
            )
    else:
        lines.append("| - | - | - | - | No Compose semantic warnings. |")

    if show_clean:
        lines += ["", "## Classes with no configured findings", ""]
        lines.extend(f"- `{record['class']}` — `{record['file']}:{record['line']}`" for record in clean)
    return "\n".join(lines) + "\n"


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    if not root.is_dir():
        print(f"Project root does not exist: {root}", file=sys.stderr)
        return 2

    files = find_kotlin_files(root, args.include_tests)
    if not files:
        print(f"No Kotlin source files found under {root}", file=sys.stderr)
        return 2

    output_dir = args.output_dir.resolve() if args.output_dir else Path(tempfile.mkdtemp(prefix="kotlin-complexity-"))
    output_dir.mkdir(parents=True, exist_ok=True)
    try:
        detekt_command = resolve_detekt(args)
        xml_report, _ = run_detekt(detekt_command, root, output_dir, args.include_tests)
        findings = parse_detekt_report(xml_report, root)
        compose_methods, compose_findings = compose_analysis(files, root)
        records = build_class_records(files, findings, compose_findings, root)
    except (FileNotFoundError, RuntimeError, ET.ParseError) as error:
        print(f"Complexity audit failed: {error}", file=sys.stderr)
        return 2

    payload = {
        "tool": {
            "name": "detekt",
            "version": args.detekt_version,
            "cyclomatic_threshold": DEFAULT_CYCLOMATIC_THRESHOLD,
            "cognitive_threshold": DEFAULT_COGNITIVE_THRESHOLD,
        },
        "root": str(root),
        "include_tests": args.include_tests,
        "files_scanned": len(files),
        "classes_scanned": len(records),
        "findings": findings,
        "compose": {
            "methods": compose_methods,
            "findings": compose_findings,
            "thresholds": {
                "method_line_count": COMPOSE_METHOD_LINE_THRESHOLD,
                "parameter_count": COMPOSE_PARAMETER_THRESHOLD,
                "callback_count": COMPOSE_CALLBACK_THRESHOLD,
                "branch_count": COMPOSE_BRANCH_THRESHOLD,
                "effect_count": COMPOSE_EFFECT_THRESHOLD,
                "state_call_count": COMPOSE_STATE_THRESHOLD,
                "signal_score": COMPOSE_SIGNAL_SCORE,
                "strong_signal_score": COMPOSE_STRONG_SIGNAL_SCORE,
            },
        },
        "classes": records,
    }
    json_path = output_dir / "complexity-audit.json"
    markdown_path = output_dir / "complexity-audit.md"
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown_path.write_text(
        markdown_report(
            root,
            files,
            records,
            findings,
            compose_methods,
            compose_findings,
            args.detekt_version,
            args.include_tests,
            args.show_clean,
        ),
        encoding="utf-8",
    )

    strong_signal_count = sum(record["status"] == "strong-signal" for record in records)
    signal_count = sum(record["status"] == "signal" for record in records)
    print(f"Scanned {len(files)} Kotlin files and {len(records)} classes.")
    print(f"Strong static signals: {strong_signal_count}; other static signals: {signal_count}.")
    print(f"Composable methods analyzed: {len(compose_methods)}; semantic warnings: {len(compose_findings)}.")
    print(f"Markdown report: {markdown_path}")
    print(f"JSON report: {json_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

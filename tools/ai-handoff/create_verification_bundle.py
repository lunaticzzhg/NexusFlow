#!/usr/bin/env python3
"""Create a NexusFlow External Architect Verification bundle."""

from __future__ import annotations

import argparse
from pathlib import Path

from common import (
    PROJECT_ROOT,
    abort_on_secrets,
    copy_project_snapshot,
    git_diff,
    git_state,
    print_manifest,
    read_template,
    reset_dir,
    source_coverage_text,
    task_id,
    tree_text,
    write_text,
    zip_dir,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task", required=True, type=task_id)
    parser.add_argument("--work-order", required=True, type=Path)
    parser.add_argument("--execution", type=Path)
    parser.add_argument("--test-results", type=Path)
    parser.add_argument(
        "--allow-sensitive-path-exclusion",
        action="store_true",
        help="continue when sensitive file names are found, as long as those files are excluded from the bundle",
    )
    return parser.parse_args()


def read_required(path: Path, label: str) -> str:
    resolved = path if path.is_absolute() else PROJECT_ROOT / path
    if not resolved.exists():
        raise SystemExit(f"{label} not found: {resolved}")
    return resolved.read_text(encoding="utf-8")


def main() -> None:
    args = parse_args()
    abort_on_secrets(allow_sensitive_path_exclusion=args.allow_sensitive_path_exclusion)

    execution_dir = PROJECT_ROOT / ".ai-handoff" / "executions" / args.task
    bundle_dir = execution_dir / "verify-bundle"
    project_dir = bundle_dir / "project"
    reset_dir(bundle_dir)

    files = copy_project_snapshot(project_dir)
    write_text(bundle_dir / "START_HERE.md", read_template("verify_START_HERE.md"))
    write_text(bundle_dir / "WORK_ORDER.md", read_required(args.work_order, "Work Order"))

    if args.execution:
        execution_text = read_required(args.execution, "Execution report")
    else:
        execution_text = read_template("execution_TEMPLATE.md")
    write_text(bundle_dir / "EXECUTION.md", execution_text)

    diff = git_diff() or "No git diff captured.\n"
    write_text(bundle_dir / "DIFF.patch", diff)
    write_text(bundle_dir / "GIT_STATE.md", git_state())

    if args.test_results:
        test_results = read_required(args.test_results, "Test results")
    else:
        test_results = "No external test results file was provided.\n"
    write_text(bundle_dir / "TEST_RESULTS.md", test_results)

    deviation_path = execution_dir / "DEVIATION.md"
    if deviation_path.exists():
        write_text(bundle_dir / "DEVIATION.md", read_required(deviation_path, "Deviation report"))

    write_text(bundle_dir / "SOURCE_COVERAGE.md", source_coverage_text(files))
    write_text(bundle_dir / "TREE.txt", tree_text(files))

    zip_path = execution_dir / f"orbit-verify-{args.task}.zip"
    zip_dir(bundle_dir, zip_path)
    print_manifest(files)
    print(f"Generated: {zip_path.relative_to(PROJECT_ROOT)}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Create a portable NexusFlow AI handoff bundle."""

from __future__ import annotations

import argparse
from pathlib import Path

from common import (
    PROJECT_ROOT,
    abort_on_secrets,
    attachment_block,
    attachment_inputs,
    copy_attachments,
    copy_project_snapshot,
    git_diff,
    git_state,
    list_block,
    print_manifest,
    read_template,
    render_template,
    reset_dir,
    source_coverage_text,
    task_id,
    tree_text,
    validate_attachments,
    write_text,
    zip_dir,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task", required=True, type=task_id)
    parser.add_argument("--goal", required=True)
    parser.add_argument("--user-concern", default="Not provided.")
    parser.add_argument(
        "--receiver-role",
        default="No fixed role. Perform the requested task using the bundled context.",
    )
    parser.add_argument(
        "--requested-action",
        default="Complete the task described by Goal using the bundled NexusFlow repository context.",
    )
    parser.add_argument(
        "--expected-deliverable",
        default=(
            "Return the result required by the Goal and Requested Action.\n"
            "Do not assume a Work Order, code change, or review verdict unless explicitly requested."
        ),
    )
    parser.add_argument("--constraint", action="append", default=[])
    parser.add_argument("--question", action="append", default=[])
    parser.add_argument("--note", action="append", default=[])
    parser.add_argument(
        "--attachment",
        action="append",
        type=Path,
        default=[],
        help="external requirement or context document to copy into bundle/attachments; may be provided multiple times",
    )
    parser.add_argument(
        "--allow-sensitive-path-exclusion",
        action="store_true",
        help="continue when sensitive file names are found, as long as those files are excluded from the bundle",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    abort_on_secrets(allow_sensitive_path_exclusion=args.allow_sensitive_path_exclusion)
    attachments_to_bundle = attachment_inputs(args.attachment)
    validate_attachments(attachments_to_bundle)

    request_dir = PROJECT_ROOT / ".ai-handoff" / "requests" / args.task
    bundle_dir = request_dir / "bundle"
    project_dir = bundle_dir / "project"
    reset_dir(bundle_dir)

    files = copy_project_snapshot(project_dir)
    attachments = copy_attachments(attachments_to_bundle, bundle_dir / "attachments")
    write_text(bundle_dir / "START_HERE.md", read_template("handoff_START_HERE.md"))
    write_text(
        bundle_dir / "REQUEST.md",
        render_template(
            read_template("request_TEMPLATE.md"),
            {
                "TASK_ID": args.task,
                "GOAL": args.goal.strip(),
                "RECEIVER_ROLE": args.receiver_role.strip(),
                "REQUESTED_ACTION": args.requested_action.strip(),
                "EXPECTED_DELIVERABLE": args.expected_deliverable.strip(),
                "USER_CONCERN": args.user_concern.strip(),
                "EXTERNAL_ATTACHMENTS": attachment_block(attachments),
                "KNOWN_CONSTRAINTS": list_block(
                    args.constraint,
                    "- Follow repository rules and architecture authorities for the scope being analyzed or modified.\n"
                    "- Preserve unrelated user changes.\n"
                    "- Preserve existing behavior and contracts unless the Goal explicitly requests a change.\n"
                    "- Do not expand into unrelated features.",
                ),
                "QUESTIONS": list_block(args.question, "None."),
                "NOTES": list_block(args.note, "None."),
            },
        ),
    )
    write_text(bundle_dir / "GIT_STATE.md", git_state())
    diff = git_diff()
    if diff:
        write_text(bundle_dir / "DIFF.patch", diff)
    write_text(bundle_dir / "SOURCE_COVERAGE.md", source_coverage_text(files))
    write_text(bundle_dir / "TREE.txt", tree_text(files))

    zip_path = request_dir / f"nexusflow-handoff-{args.task}.zip"
    zip_dir(bundle_dir, zip_path)
    print_manifest(files)
    print(f"Generated: {zip_path.relative_to(PROJECT_ROOT)}")


if __name__ == "__main__":
    main()

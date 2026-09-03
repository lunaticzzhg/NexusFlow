---
name: orbit-work-order-executor
description: "Execute a self-contained NexusFlow Work Order without redesigning target ownership or self-approving Human Traceability. Use when the user provides a Work Order or Correction Work Order for implementation."
---

# Orbit Work Order Executor

## Mission

Implement the self-contained NexusFlow Work Order exactly within the real NexusFlow repository. Codex is the implementation executor: it edits code, adds tests, runs verification, records deviations, and produces a Verification bundle. Codex does not replace the Work Order's architecture decisions with its own.

## Required Sources

Before implementation, read:

- `AGENTS.md`;
- `.agents/skills/INDEX.md`;
- architecture authorities matching the Work Order scope:
  - App/KMP: `docs/architecture/orbit-frontend-architecture.md`;
  - Backend: `docs/architecture/nexusflow-backend-architecture.md`;
  - AI/planning: `docs/architecture/nexusflow-ai-architecture.md`;
  - shared contracts: `contracts/` plus relevant producer/consumer source;
- the entire Work Order or Correction Work Order;
- directly touched source, tests, and caller/callee files.

If the Work Order touches Backend persistence, JDBC, Flyway migrations, transactions, FK / UNIQUE / CHECK integrity rules, idempotency, optimistic concurrency, PostgreSQL-specific SQL, or durable multi-write behavior, also read `.agents/skills/nexusflow-feature-development/references/backend-persistence.md` before editing.

Also inspect current git state and identify unrelated user changes before editing.

## Preflight Checklist

Do not edit until these are clear:

```text
[ ] Work Order is fully read
[ ] Current Git base is compatible with the Work Order
[ ] Existing user changes are identified
[ ] Behavior Freeze is understood
[ ] Target ownership is understood
[ ] Non-goals are understood
[ ] Stop Conditions are understood
```

If the Work Order depends on a base commit or snapshot and HEAD has materially changed, check whether the changed files affect the target flow. If they do, stop and ask for a refreshed PLAN/Work Order rather than blindly applying the old design.

## Execution Rules

Implement slices in Work Order order:

```text
Slice 1 -> code -> narrow tests
Slice 2 -> code -> narrow tests
...
Final -> scope-derived verification -> Verification bundle
```

Verification is selected from the actual touched areas and the Work Order. App ktlint is mandatory only for App/KMP Kotlin or Gradle Kotlin DSL changes where the App authority requires it. Backend-only, Contracts-only, AI-only, and docs/skills-only Work Orders must not inherit App ktlint as a universal final step.

For Work Orders that require final Backend PostgreSQL evidence, run the relevant Backend tests with `NEXUSFLOW_REQUIRE_POSTGRES_TESTS=true`. Ordinary local runs may clearly skip PostgreSQL integration tests when Docker is unavailable, but required Work Order evidence must fail rather than silently skip when PostgreSQL cannot run.

When App/KMP Kotlin or Gradle Kotlin DSL changes require the App ktlint task, run:

```bash
./gradlew :app:composeApp:ktlintCheck
```

If that App `ktlintCheck` fails because of hand-written style issues, run:

```bash
./gradlew :app:composeApp:ktlintFormat
```

Review formatter changes, then run `ktlintCheck` again.

## Prohibited

Do not:

- skip target changes because Codex thinks the existing design is already reasonable;
- change the Work Order's selected owner to another owner;
- add a Manager, Factory, Registry, Strategy, wrapper, option, or compatibility seam that the Work Order did not require;
- preserve old APIs "for future needs" unless the Work Order or compatibility contract requires it;
- replace required knowledge convergence with delegation-only wrappers;
- refactor unrelated features;
- reduce LOC as a goal by itself;
- change Behavior Freeze to what seems more reasonable;
- declare Human Traceability PASS.

Tests passing, ktlint passing, or a tidy diff are execution facts, not independent verification approval.

## Deviation Protocol

If repository facts conflict with the Work Order design, stop the affected slice and create:

```text
.ai-handoff/executions/<task-id>/DEVIATION.md
```

Use this structure:

```markdown
# Deviation Report

## Work Order

...

## Slice

...

## Expected By Work Order

...

## Actual Repository Fact

File:
Line / symbol:
Actual behavior:

## Why This Blocks The Slice

...

## What Was Not Changed

...

## Decision Needed

...
```

Report only the decision needed. Do not choose a new target ownership on behalf of the Architect.

## Completion

When implementation is complete, create or update:

```text
.ai-handoff/executions/<task-id>/EXECUTION.md
```

Then generate:

```bash
python3 tools/ai-handoff/create_verification_bundle.py \
  --task <task-id> \
  --work-order <path-to-work-order> \
  --execution .ai-handoff/executions/<task-id>/EXECUTION.md
```

If the only safety findings are excluded sensitive file names such as `local.properties`, use `--allow-sensitive-path-exclusion` only after the user has explicitly approved continuing without those files. Content-level secret matches still abort.

The Verification bundle must include the Work Order, execution report, diff, git state, source coverage for App/Backend/AI roots, test results if provided, and the complete latest effective NexusFlow project snapshot.

Final handoff should report what was implemented, which commands ran, any gaps, and the generated bundle path. Do not claim independent verification PASS.

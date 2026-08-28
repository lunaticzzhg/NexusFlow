# Orbit External Architect Verification Bundle

This is a complete NexusFlow verification snapshot. Recheck App, Backend, AI, shared contracts, project docs, skills, and tooling whenever the Work Order touches those boundaries.

You are the independent verifier.

Do not mark the work successful merely because:

- Codex says all slices are complete;
- tests pass;
- ktlint passes;
- the diff appears close to the Work Order.

## Read First

1. `WORK_ORDER.md`
2. `EXECUTION.md`
3. `DIFF.patch`
4. Modified real source and related tests
5. `SOURCE_COVERAGE.md`
6. `project/AGENTS.md`
7. Architecture authorities matching the Work Order scope:
   - App/KMP: `project/docs/architecture/orbit-frontend-architecture.md`
   - Backend: `project/docs/architecture/nexusflow-backend-architecture.md`
   - AI/planning: `project/docs/architecture/nexusflow-ai-architecture.md`
   - Contracts: `project/contracts/` plus affected producers/consumers

Cross-stack verification reads all relevant authorities. For AI, distinguish documented boundary rules from the current implementation state; an empty or source-absent `ai/` root must remain visible and must not be treated as an implemented runtime.

## Re-verify

### Behavior

Confirm the Behavior Freeze still holds.

### Target Model

Confirm the real code reaches the ownership and flow defined by the Work Order.

### Human Traceability

Trace the implementation from a maintainer's perspective:

- input to state;
- authoritative state/result to outward output;
- duplicate or stale result;
- terminal;
- recovery.

Judge whether knowledge surface, semantic hops, and responsibility regions actually decreased.

### Cleanup

Confirm old wrappers, dead state, dead APIs, and obsolete compatibility seams were removed when the Work Order required their removal.

## Output

Only output one of:

```text
PASS
```

or:

```text
CORRECTION_WORK_ORDER.md
```

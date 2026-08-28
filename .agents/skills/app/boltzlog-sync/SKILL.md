---
name: boltzlog-sync
description: Compare every maintained NexusFlow KMP App implementation against the local Boltzlog reference, treating Boltzlog's mature architecture, lifecycle, module boundaries, platform ports, and test depth as the default implementation standard while preserving NexusFlow product/backend contracts. Use when asked to periodically sync Boltzlog design/implementation, inspect it comprehensively, identify alignment gaps, or selectively port a Boltzlog capability.
---

# Boltzlog Sync

Use Boltzlog as the default App implementation standard: its mature architecture, lifecycle ownership, module boundaries, shared/platform split, state handling, failure behavior, and relevant test depth should be adopted unless NexusFlow has concrete contrary evidence. NexusFlow product facts and backend contracts remain authoritative over Boltzlog product semantics and wire contracts. Default to a read-only assessment. Do not modify NexusFlow or copy code until the user selects a proposal or explicitly requests implementation.

## Source and authority

- Read `AGENTS.md` and `.agents/skills/INDEX.md` first.
- Treat NexusFlow's product facts, backend contracts, architecture documents, and existing mature implementations as authoritative.
- Treat a Boltzlog implementation difference as an alignment gap by default. Classify it as an intentional divergence only when a NexusFlow product/backend contract, platform limitation, or demonstrably smaller existing equivalent proves that it must differ. Record that evidence; do not use project age, file count, or a generic ROI claim as a divergence reason.
- Use the reference-project location and candidate map in [references/reference-map.md](references/reference-map.md).
- Build the review index from **every maintained NexusFlow App source and test** first, then map every entry to its Boltzlog counterpart or explicitly record the absence of one. Cover `commonMain`, Android/iOS platform ports, app entry/composition, feature code, and relevant tests; do not begin from Boltzlog filenames alone.
- For each mapped implementation, inspect the complete NexusFlow source, complete Boltzlog source, direct callers, and relevant tests. Never assess either project by filename, package shape, or a representative sample alone.
- Read the receiving NexusFlow implementation before proposing a port. For compatible feature-free foundations, prefer the complete Boltzlog implementation and tests over a new NexusFlow-specific rewrite.
- For a feature-free foundation module whose dependency closure and contracts are compatible, prefer a direct source port with only mechanical adaptation (package/import names, target runtime/configuration interfaces, and platform bindings). Port its relevant tests with it. Do not rewrite a proven core mechanism merely to make it look native to NexusFlow.

## Synchronization workflow

1. Compare project norms and accumulated experience first: read both projects' `AGENTS.md`, skill indexes/workflows, architecture documents, product/backend contracts, verification conventions, and mature tests. Establish the narrow set of NexusFlow facts that may require intentional divergence before reviewing source modules.
2. Build a complete NexusFlow App inventory second. Enumerate each maintained implementation and test in app entry/composition, `core`, `feature`, `commonMain`, Android, and iOS. For each row, name the owning behavior, current callers, current tests, and its expected Boltzlog comparison area.
3. Compare all maintained Boltzlog foundation modules third: `core/`, app entry/composition, shared runtime/configuration, platform ports, and their tests. Match every NexusFlow inventory row to an equivalent Boltzlog mechanism, a Boltzlog absence, or an explicitly product-specific non-equivalence. Ignore generated output, build artifacts, branding, copy, and identifiers.
4. Compare business modules fourth: inspect every maintained Boltzlog feature's `presentation`, `domain`, `data`, and `di` patterns, including representative callers and tests. For each current NexusFlow feature implementation, assess the closest mature Boltzlog pattern for layering, state ownership, lifecycle, errors, DI, and test coverage; never port business models or workflows wholesale.
5. For every mapped row, record one parity verdict: **aligned**, **direct-port candidate**, **adapted-port candidate**, **intentional divergence**, **defer until named consumer**, or **no reference equivalent**. An intentional divergence must cite the NexusFlow authority that requires it; a defer must name the exact product/contract trigger that will reopen it.
6. Use Git history and working-tree status only as supplementary evidence for a candidate's maturity or recent evolution; do not use it to restrict this full comparison.
7. Compare each candidate against NexusFlow's existing code and its authoritative backend/product rules. Read the matching Orbit skill before evaluating or implementing a candidate in its domain.
8. Classify every reviewed candidate:
   - **Adopt**: an app-wide mechanism with an invariant NexusFlow currently lacks.
   - **Adapt**: a useful pattern whose protocol, model, lifecycle, or UI behavior must be rewritten for Orbit.
   - **Defer**: useful only after a concrete NexusFlow consumer exists.
   - **Reject**: conflicts with Orbit facts, duplicates existing capability, or adds complexity without current ROI.
9. Rank Adopt/Adapt items by dependency order, correctness, and user-visible risk. Propose the smallest vertical slice. Do not create a framework, Gradle module, Koin scope, event bus, or generic manager unless the Boltzlog mechanism itself is required by a current NexusFlow behavior or its missing lifecycle invariant has been demonstrated.
10. Stop after the report and approval gate unless implementation is explicitly authorized. Do not begin a migration from a positive classification alone; wait for the user to approve one or more named candidates, or the table as a whole. For selected product behavior work, route through `nexusflow-feature-development` so App / Contracts / Backend / AI scope is explicit; do not invent separate backend or AI feature workflows. Run the verification required by the touched scope.

## Foundation port decision

For every selected foundation candidate, state one of the following before implementation:

- **Direct port**: copy the complete relevant implementation and tests, then perform only mechanical adaptation. List the dependency closure and each mechanical edit.
- **Adapted port**: start from the reference implementation, but name the specific incompatible contract, runtime dependency, platform binding, or safety rule that requires a semantic change.
- **No port**: explain why a direct port would import feature/business semantics or unsupported infrastructure.

Treat `core/` code as portable by default; treat feature code as pattern-level reference by default. Never copy API envelopes, authentication semantics, domain models, user-facing text, credentials, or feature workflows even when they reside beside core code.

## Required report

Begin the response with this grouped candidate table. It is the approval gate, not an implementation plan:

| Comparison area | Candidate and Boltzlog evidence | Classification | NexusFlow landing point | Why it is worth syncing now | Required adaptation / defer trigger |
| --- | --- | --- | --- | --- | --- |

Group rows in this fixed order:

1. **Project norms and accumulated experience**
2. **Foundation modules**
3. **Business modules**

List only Adopt and Adapt candidates in this first table. State the candidates by stable, reviewable names so the user can approve them individually. Do not include implementation steps, code changes, or a verification run before approval.

After approval, return a concise decision table with:

| Candidate and Boltzlog evidence | Classification | NexusFlow landing point | Required adaptation | Why now / defer trigger | Smallest verification |
| --- | --- | --- | --- | --- | --- |

Also state:

- a complete parity ledger for every maintained NexusFlow App implementation: current file(s), Boltzlog counterpart or absence, callers/tests checked, parity verdict, and exact evidence for every divergence or defer;
- confirmation that this was a full current-state comparison, plus the checked revisions/status as supplementary evidence when useful;
- evidence that the review followed the required order: norms/experience, NexusFlow inventory, foundation modules, then business modules;
- authoritative NexusFlow contracts/rules checked;
- a recommended implementation order and explicit non-goals;
- unchanged, deferred, and rejected areas when that prevents duplicate work; do not hide an unaligned current implementation merely because it is not proposed for immediate migration.

Keep Defer and Reject candidates out of the initial approval table. Summarize them after the table only when they prevent duplicate work or clarify why a seemingly obvious capability is absent. A user approval may be broad (all listed items) or narrow (specific named rows); treat all unapproved candidates as out of scope.

Link exact local files for both projects. Keep business concepts in `feature/<name>`; put only feature-free, app-owned mechanisms in the appropriate `core/` package. Preserve the shared `commonMain` authority for behavior and keep platform code to atomic system interop.

## Guardrails

- Do not copy Boltzlog response envelopes, auth semantics, API paths, domain models, user-facing text, analytics, identifiers, or credentials.
- Do not introduce caching, pagination, runtime coordination, SSE, push, media, deep links, or platform ports until an Orbit product path needs them.
- For user/tenant/session-scoped state, preserve Orbit's REST authority, server validation, and context invalidation requirements; a client pattern is only an implementation candidate.
- Preserve cancellation by rethrowing `CancellationException`; do not turn it into a user error.
- Treat a Boltzlog difference as a required alignment investigation. Keep a NexusFlow difference only with the explicit authority or platform evidence required above.

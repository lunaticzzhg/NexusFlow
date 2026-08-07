# Planning contract and mapping

Read this before adding a prompt field, changing a proposal shape, or connecting `ai/` to `contracts`.

## Ownership

`ai/src/main/.../planner/PlanningModels.kt` owns planning-core input/output and policy feedback. `contracts/.../planning/PlanningContracts.kt` owns stable cross-process/HTTP/event data. They overlap by design but are not interchangeable:

| Core | Wire contract | Reason |
| --- | --- | --- |
| `PlanningContext` | no public equivalent | Context is private, redacted working input |
| `ai.planner.PlanProposal` | `contracts.planning.PlanProposal` | Core can evolve without coupling to API/event serialization |
| `RequestedAction` | `ActionRequest` | Both declarative; backend creates execution state and idempotency key |
| `PlanningResult.Rejected` | backend error/event state | Rejection is an internal decision, not an HTTP response |

Keep mapping in an adapter owned by the caller (today `backend`; later the planning-service edge). `ai/` must not import persistence, Ktor, or external-provider DTOs.

```kotlin
fun toContract(
    taskId: String,
    proposal: com.nexusflow.ai.planner.PlanProposal,
    metadata: ModelRunMetadata,
    now: Instant,
): com.nexusflow.contracts.planning.PlanProposal =
    com.nexusflow.contracts.planning.PlanProposal(
        taskId = taskId,
        title = proposal.title,
        summary = proposal.rationale.joinToString(" "),
        generatedAt = now,
        options = proposal.options.mapIndexed { index, option ->
            // Map only source-backed items supplied by the orchestrator.
            // Do not invent ActionRequest idempotency or execution status here.
            error("Map validated option $index with its source snapshots")
        },
        modelRun = metadata,
    )
```

The snippet intentionally fails until the adapter can provide required `PlanItem` timestamps and source references. Do not fill required wire fields with made-up values just to satisfy a type checker.

## Input and output rules

- Context must carry task ID, locale/time zone, normalized hard constraints, opted-in preference suggestions, and source snapshots sufficient for every factual recommendation.
- Apply explicit size/count limits before provider invocation. Existing policy limits request length, opportunity count, and option count.
- Use stable source IDs. Every recommendation that claims a real event must map to a source snapshot, not raw provider prose.
- Add new contract fields as optional/defaulted first. Never rename/re-purpose an existing serialized field; bump `schemaVersion` for semantic incompatibility.
- `amountMinor + ISO-4217 currency`, UTC instants plus IANA timezone, and source retrieval time are required at the wire boundary. Do not use floating point money or locale-formatted dates.

## Contract change checklist

1. Classify it as core-only, adapter-only, or stable wire change.
2. Update validation and deterministic provider together.
3. Add/adjust core and contract tests, including older payload compatibility where serialized.
4. State why app rendering, backend persistence, and replay consumers remain compatible.

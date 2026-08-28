# Boltzlog Reference Map

Reference root:

`/Users/lunatic/AndroidStudioProjects/uav/uav-backend-system/app/frontend/code/apps/boltzlog`

Use this map only after comparing project norms and accumulated experience and building the complete NexusFlow App inventory. Cover every row before inspecting individual business features. Each NexusFlow implementation must be mapped to a row, a more specific Boltzlog counterpart, or an explicit no-equivalent/product-contract explanation; do not substitute a representative sample for the inventory.

| Concern | Boltzlog starting points | Evaluate for NexusFlow when |
| --- | --- | --- |
| Feature boundaries and MVI | `composeApp/src/commonMain/kotlin/com/entropix/boltzlog/feature/*/{presentation,domain,data,di}`; `docs/architecture/boltzlog-frontend-architecture.md`; `.agents/skills/boltzlog-feature-development/SKILL.md` | adding or restructuring an Orbit feature, ViewModel, repository, or feature DI |
| Network errors and session replay | `core/network/NetworkCall.kt`, `NetworkHttpClient.kt`, `NetworkSessionController.kt` | Orbit backend contract, Problem JSON, auth or idempotent requests change |
| Logging and diagnostics | `core/observability/` | a feature gains external I/O, background execution, failure recovery, or production diagnostics |
| Loading, refresh, pagination, cache | `core/design/feedback/ListLoadPhase.kt`, `core/presentation/state/KeyedStateStore.kt`; `references/list-data-lifecycle.md` | a list, scoped cache, refresh, pagination, or multiple concurrent data keys exists |
| Context runtimes | `app/context/`; `core/realtime/`; `core/push/`; `docs/architecture/context-runtime.md` | user/tenant/session changes own connections, workers, subscriptions, or executors |
| Navigation and app shell | `core/navigation/`, `App.kt` | a new feature graph or cross-feature navigation is needed |
| Storage, file/media, permissions, system UI | `core/storage/`, `core/filesystem/`, `core/media/`, `core/permissions/`, `core/systemui/` | an Orbit user flow requires that concrete platform capability |

## Mandatory parity ledger

For every sync, create the following read-only ledger before selecting candidates. Keep it in the report, not as a permanent generated file.

| NexusFlow implementation | Behavior and direct callers | Boltzlog counterpart and tests checked | Verdict | Evidence / reopen trigger |
| --- | --- | --- | --- | --- |

- Include all maintained files in `app/composeApp/src/commonMain`, `androidMain`, `iosMain`, and their relevant tests. Group adjacent files only when they implement one inseparable mechanism and list every grouped path.
- Check app entry/startup, module registration, runtime configuration, network, observability, secure storage, time, design feedback, navigation, every current feature's data/domain/presentation/DI, and each platform bridge explicitly.
- Use **aligned** only after reading both complete implementations plus callers/tests. Use **intentional divergence** only with a linked NexusFlow product/backend/architecture authority. Use **defer** only with a named consumer or contract trigger. Use **no reference equivalent** only after checking the mapped Boltzlog area.
- A Boltzlog implementation that conflicts with NexusFlow backend/product facts remains out of scope, but its architectural pattern and tests still require comparison.

Never import a Boltzlog business feature wholesale. Treat its source as an example of layering, state ownership, error handling, and tests; map all models and behavior to NexusFlow's own product and server contracts.

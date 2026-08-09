# Boltzlog Reference Map

Reference root:

`/Users/lunatic/AndroidStudioProjects/uav/uav-backend-system/app/frontend/code/apps/boltzlog`

Use this map only after comparing project norms and accumulated experience. Then cover every row before inspecting individual business features. A row without a current NexusFlow consumer still receives an explicit Defer or Reject decision rather than being skipped.

| Concern | Boltzlog starting points | Evaluate for NexusFlow when |
| --- | --- | --- |
| Feature boundaries and MVI | `composeApp/src/commonMain/kotlin/com/entropix/boltzlog/feature/*/{presentation,domain,data,di}`; `.agents/skills/boltzlog-feature-development/references/architecture.md` | adding or restructuring an Orbit feature, ViewModel, repository, or feature DI |
| Network errors and session replay | `core/network/NetworkCall.kt`, `NetworkHttpClient.kt`, `NetworkSessionController.kt` | Orbit backend contract, Problem JSON, auth or idempotent requests change |
| Logging and diagnostics | `core/observability/` | a feature gains external I/O, background execution, failure recovery, or production diagnostics |
| Loading, refresh, pagination, cache | `core/design/feedback/ListLoadPhase.kt`, `core/presentation/state/KeyedStateStore.kt`; `references/list-data-lifecycle.md` | a list, scoped cache, refresh, pagination, or multiple concurrent data keys exists |
| Context runtimes | `app/context/`; `core/realtime/`; `core/push/`; `docs/architecture/context-runtime.md` | user/tenant/session changes own connections, workers, subscriptions, or executors |
| Navigation and app shell | `core/navigation/`, `App.kt` | a new feature graph or cross-feature navigation is needed |
| Storage, file/media, permissions, system UI | `core/storage/`, `core/filesystem/`, `core/media/`, `core/permissions/`, `core/systemui/` | an Orbit user flow requires that concrete platform capability |

Never import a Boltzlog business feature wholesale. Treat its source as an example of layering, state ownership, error handling, and tests; map all models and behavior to NexusFlow's own product and server contracts.

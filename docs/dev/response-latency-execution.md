# Response Latency Execution Record

Date: 2026-09-19
Executor: GPT-5.5 subagents (no context fork); parent agent code review
Base commit: `35e5659fbcfb283cb620f755ff6c8e6866fed24b`
Contract: `docs/dev/response-latency-implementation-plan.md`

## Implemented slices

- S0/S7 observability: `ConversationTurnWorkflow` now logs `turn_started`, branch choice (`turn_answer`, `turn_research`, `turn_planning`), `first_answer_delta`, answer generation/finish, durable result store/consume, and terminal run state with response run id, attempt, conversation id, task id/revision, and trace context. These logs expose branch and durable lifecycle boundaries. The direct-answer first-delta event is present, but provider-first-delta monotonic duration and Research first-delta timing are not implemented; S0/S7 timing instrumentation is partial. No live provider latency or real UI rendering measurement was run in this environment.
- S1/S2 AI transport and one-pass turn: added `ConversationTurnCapability`, `ConversationTurnRequest`, `ConversationTurnResult`, `StreamingTurnModelProvider`, `StructuredConversationTurn`, and provider wiring. Responses and Chat streaming now reject incomplete EOF, mixed text/tool output, multiple tool identities, refusal deltas, response failed/error events, non-output Responses deltas in text mode, and inconsistent Responses/Chat tool identity fragments. Responses/Chat request bodies set bounded single-operation semantics (`parallel_tool_calls=false` where supported; local identity rejection remains authoritative) and keep strict provider assumptions explicit.
- S3 durable request context: response runs persist `time_zone_id` via `V013__response_run_request_context.sql`; reference time is the durable run `createdAt`, not a duplicated column. Duplicate client ids with same text but different timezone now conflict. Worker/context loader passes persisted timezone and `run.createdAt` into `ConversationTurnRequest` and answer requests.
- S4 streaming hub/SSE: replaced global shared flow with per-run synchronized state and bounded subscriber channels. Attempt transitions preserve or close subscribers safely, replay is attempt-aware, stale attempt publishes are rejected under lock, slow subscribers are disconnected on overflow without breaking durable work, route `Last-Event-ID` rejects future attempt/seq cursors and falls back to authoritative snapshot/replay.
- S5 production path cleanup: `ConversationTurnWorkflow` now uses a single production turn path for Turn stage: direct answer, research+answer, or planning proposal. Old `ConversationUnderstandingStep`, `ConversationDecisionStep`, `ConversationAnswerService.answer`, and backend production `ConversationDecisionCapability` DI were removed. `ConversationAnswerService` keeps only `answerKnownNeeds` with backend validator guardrails before research and answer synthesis. `StructuredConversationTurn` reuses the existing Understanding JSON schema for planning; semantic validation remains local mapping logic rather than a new validator abstraction to avoid expanding architecture in this pass.
- S6 worker/read tools/lifecycle: worker `start()` supports configurable parallel loops with per-loop lease identity, loop exception survival, target retry/terminal failure mapping, cancellation, and heartbeat/claim exception survival. Read tool execution now uses a bounded semaphore and thread-safe activity observer state.
- S7 App consumer compatibility: Backend SSE cursor/replay semantics were fixed without App production changes. Existing App `ResponseRunStreamControllerTest` covers delta accumulation, duplicate/stale/gap/new attempt reset/terminal snapshot/reconnect behavior.

## Key files changed

- Contracts/AI: `contracts/backend-ai/src/main/kotlin/com/nexusflow/contracts/backendai/conversation/ConversationTurnContracts.kt`, `contracts/backend-ai/src/main/kotlin/com/nexusflow/contracts/backendai/common/AiProtocolContracts.kt`, `ai/src/main/kotlin/com/nexusflow/ai/conversation/StructuredConversationTurn.kt`, `ai/src/main/kotlin/com/nexusflow/ai/provider/StreamingTurnModelProvider.kt`, provider implementations, `OpenAiCompatibleStructuredTransport.kt`.
- Backend workflow/runtime: `ConversationTurnWorkflow.kt`, `ConversationTurnProcessor.kt`, `ConversationTurnContext*.kt`, `ConversationTurnPayloadMapper.kt`, `ResponseRunWorker.kt`, `ResponseRunRealtimeHub.kt`, `ResponseRunService.kt`, `ConversationRoutes.kt`, `ConversationAnswerService.kt`, `ConversationAnswerStep.kt`, `ReadToolExecutor.kt`, `ResponseRunReadToolActivityObserver.kt`.
- Persistence: `V013__response_run_request_context.sql`, `ResponseRun.kt`, `JdbcResponseRunRepository.kt`, `JdbcConversationTurnStartCommitter.kt`, `JdbcConversationRepository.kt`, `JdbcConversationAnswerCommitter.kt`, `JdbcTaskRepository.kt`, `PlanningResultCommitter.kt`.
- Removed production old-path files: `ConversationUnderstandingStep.kt`, `ConversationDecisionStep.kt`.
- Tests: AI transport/turn tests, backend realtime hub/worker/result bus/routes/service/read tool tests, backend-ai serialization tests, App stream controller existing tests.

## Verification run

- `./gradlew :backend:compileKotlin :backend:compileTestKotlin --no-daemon` — passed during cleanup.
- `./gradlew :backend:test --tests 'com.nexusflow.backend.feature.conversation.infrastructure.JdbcResponseRunResultBusTest' --tests 'com.nexusflow.backend.feature.conversation.application.ConversationServiceTest.duplicate create with same client id and text but different timezone conflicts' --tests 'com.nexusflow.backend.feature.task.application.readtool.ReadToolFoundationTest.executor allows two overlapping calls and queues the third when limit is two' --tests 'com.nexusflow.backend.feature.conversation.application.ResponseRunRealtimeHubTest' --no-daemon` — passed.
- `./gradlew :ai:test :contracts:backend-ai:test --no-daemon` — passed.
- `NEXUSFLOW_REQUIRE_POSTGRES_TESTS=true ./gradlew :backend:test --no-daemon` — passed; the latest complete report before final review contained 270 tests with no failures or skips.
- `./gradlew :app:composeApp:testDebugUnitTest --tests 'com.nexusflow.app.feature.task.presentation.detail.ResponseRunStreamControllerTest' --no-daemon` — passed.
- `./gradlew :app:composeApp:ktlintCheck --no-daemon` — passed.

Additional targeted evidence added and run includes real local HTTP chunked SSE gated flush tests for both `stream()` and `streamTurn()`: the first text delta is observed before `response.completed` is released by the local socket server.

## Compatibility and migration notes

- `V013__response_run_request_context.sql` adds `response_runs.time_zone_id TEXT NOT NULL DEFAULT 'UTC'` plus a length check. Existing rows become UTC. New writers set the real timezone from create/send commands.
- Rolling migration requirement: apply the migration before deploying new code. Old binaries can continue writing due the database default, but they would write UTC for new rows; do not rely on old binaries for timezone-preserving response runs after rollout. Direct rollback to old binaries is behaviorally lossy for timezone even though inserts remain schema-compatible.
- `referenceTime` intentionally comes from durable `response_runs.created_at`; no second reference_time column was introduced.
- SSE `Last-Event-ID` future attempt/seq cursors are treated as invalid relative to snapshot and recover from snapshot/replay.

## Known gaps / not fabricated

- No live provider call, deployed backend, or real device UI rendering/performance evidence was run. The proof for early deltas is local HTTP chunked SSE, transport-level and App controller tests.
- Planning proposal validation in `StructuredConversationTurn` reuses the existing Understanding schema and preserves key semantic checks, but the validation code is not fully extracted into one shared validator abstraction. Both Understanding and Turn are real callers today. Shared semantic validation extraction remains a maintenance gap, deferred at the final scope freeze; a future change to planning proposal semantics must update and verify both callers.
- App production code was not changed; existing controller tests validated consumer behavior. No iOS simulator test was run.

## Parent review and scope freeze

The user requested faster completion after prolonged implementation/review. Production edits were frozen at a compilable checkpoint; no validator extraction or new timing instrumentation was left half-applied. This record does not claim all S0–S7 acceptance items are complete.

Reviewed and corrected: provider response buffering; operation identity/refusal/truncation; Research streaming; attempt-aware SSE ordering and slow-client disconnect; worker loop survival and bounded concurrency; planning clarification atomic commit and stale rejection; durable timezone; and history selection by canonical turn index in both direct and Research paths. Ordinary answers use one turn capability call; Research uses one proposal call plus one answer call. Provider repair/retry and the existing planning stages are outside this ordinary-path count.

The local HTTP streaming tests, Backend flow tests and App controller tests verify separate segments. They are not a single integrated provider-to-device rendering test or a live latency benchmark. Research history now uses the same turn-index rule, but has no new dedicated second-answer history regression test.

Remaining maintenance/measurement work: shared planning proposal validation, complete monotonic first-delta instrumentation, live-provider latency/quality evaluation and Android/iOS rendering verification. These are not presented as verified speedups or independent architecture approval. No commit or deployment was performed.

### Final parent verification

`NEXUSFLOW_REQUIRE_POSTGRES_TESTS=true ./gradlew :ai:test :contracts:backend-ai:test :contracts:app-backend:jvmTest :backend:test` — BUILD SUCCESSFUL in 45s. Backend compiled and tests re-executed after the final history fix; AI/contracts were up-to-date against their previously passing inputs. App controller evidence is from the earlier targeted run, not re-executed in this command. `git diff --check` passed.

- `ai` / `test`: tests=87, failures=0, errors=0, skipped=0
- `backend` / `test`: tests=270, failures=0, errors=0, skipped=0
- `contracts/backend-ai` / `test`: tests=15, failures=0, errors=0, skipped=0
- `contracts/app-backend` / `jvmTest`: tests=10, failures=0, errors=0, skipped=0
- `app/composeApp` / `testDebugUnitTest`: tests=8, failures=0, errors=0, skipped=0

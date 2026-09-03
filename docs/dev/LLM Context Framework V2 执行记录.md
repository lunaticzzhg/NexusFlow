# LLM Context Framework V2 执行记录

执行时间：2026-08-29

Work Order：`WORK_ORDER_LLM_CONTEXT_FRAMEWORK_V2.md`

范围：`:ai` / Backend / PostgreSQL / architecture docs / tests。

## Slice Completion

| Slice | Status | Evidence |
| --- | --- | --- |
| Slice 0 Preflight | Done | 确认 provider request、Understanding、Planning、ExplicitPreferenceRepository、无生产 MCP runtime 等基线事实。 |
| Slice 1 Structured request/payload contract | Done | `StructuredModelRequest.userPayload` 改为 `JsonObject`；AI capability typed payload 后再编码；provider adapter 在 transport boundary 序列化。 |
| Slice 2 Context foundation | Done | 新增 Backend `ModelContextKey`、definition、lifecycle、priority、trust、catalog、resolver、assembler foundation。 |
| Slice 3 Explicit preference source | Done | 使用既有 `ExplicitPreferenceRepository` 解析 selected preference keys；不新增 preference store，不创建额外 Requirement 存储。 |
| Slice 4 Understanding selection + persistence | Done | Understanding payload 支持 available definitions、optional Context、selected keys；新增 V005 task key persistence；new key invalidates planning freshness。 |
| Slice 5 Planning integration | Done | Planning 前 resolve selected Task Context 并注入 `optionalContext`；Opportunity ID references 保留 typed core path。 |
| Slice 6 External/MCP distillation seam | Done | 新增 typed external source marker/projector/filter seam 和 fake-source tests；未新增真实 MCP runtime。 |
| Slice 7 Budget + observability | Done | 增加 optional Context structural/serialized-char budget、安全 diagnostics、provider-reported token usage propagation。 |
| Slice 8 Docs + full verification | Done | 更新 architecture authority，完整验证通过，verification bundle 已生成。 |

## Main Changes

- AI authority: `docs/architecture/nexusflow-ai-architecture.md`
  - StructuredModelRequest / capability contract。
  - Core Context vs Optional Context。
  - Context Catalog / Resolver / Assembler。
  - Context Selection and lifecycle。
  - External/MCP distillation boundary。
  - Context budget, observability and token-saving rules。
- Backend authority: `docs/architecture/nexusflow-backend-architecture.md`
  - Backend-owned model Context construction。
  - Task selected-key persistence boundary。
  - External source typed projection and fail-closed rule。
  - Safe diagnostics / no raw prompt or value logging。
- Execution evidence:
  - `docs/dev/LLM Context Framework V2 执行记录.md`
  - `.ai-handoff/executions/llm-context-framework-v2/EXECUTION.md`

## Non-goals Preserved

This execution did not introduce Context cache, response cache, vector DB, RAG, embeddings, inferred long-term memory, general Agent Runtime, multi-agent, provider fallback/router, MCP side-effect runtime, generic tool router, universal prompt DSL, full transcript injection, full profile injection, raw MCP/API payload injection, or provider adapter Context-key branches.

## Deviations

No Work Order deviation was recorded for Slice 8.

Pre-existing unrelated staged issue: `docs/dev/Orbit M1 — First Planning Loop 阶段方案.md` contains trailing whitespace in the staged diff. Slice 8 did not modify that staged file. This may make `git diff --cached --check` fail, but it is outside this Work Order's touched paths.

## Verification

- `./gradlew :ai:test :backend:test` — passed.
- `NEXUSFLOW_REQUIRE_POSTGRES_TESTS=true ./gradlew :backend:test --rerun-tasks` — passed.
- `git diff --check -- ai/src backend/src docs/architecture docs/dev` — passed.
- `git diff --cached --check` — failed only on pre-existing unrelated staged trailing whitespace in `docs/dev/Orbit M1 — First Planning Loop 阶段方案.md`; Slice 8 did not modify that staged file.

## Bundle

- `.ai-handoff/executions/llm-context-framework-v2/orbit-verify-llm-context-framework-v2.zip`

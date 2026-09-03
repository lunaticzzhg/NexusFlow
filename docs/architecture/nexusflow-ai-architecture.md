# NexusFlow AI 架构主规范

当前实现状态：`:ai` Gradle module 已包含 structured model provider boundary、用户消息理解、第一版计划生成/解释 capability，以及 V2 LLM Context Framework 的 AI-facing payload contract。它不是 agent runtime、tool router、RAG、memory、provider fallback router 或 side-effect executor；任何文档或计划都不能被解读为当前存在这些 runtime。

本规范是 NexusFlow AI/planning 边界的长期 authority。它冻结的是 Backend/AI 信任边界、ownership 和 Kotlin-first 默认方向；内部 Planner architecture 在真实源码出现前保持 `UNPROVEN`。

状态分类：

```text
Boundary / ownership rules: PROVEN
Current structured Understanding / Planning capabilities: PRESENT
LLM Context Framework V2 boundary: PRESENT
Agent runtime / RAG / memory / tool router: UNPROVEN until real source exists
```

## 0. 范围、权威与当前状态

### 适用范围

本规范适用于当前和未来的 `ai/` planning module、Planner API、provider adapter、guardrails、PlanProposal、RequestedAction、reason/risk tag、evaluation 和 Backend 集成边界。

### 事实来源

- `settings.gradle.kts`：当前 include `:app:composeApp`、`:contracts`、`:backend`、`:ai`。
- `ai/src/main/kotlin/com/nexusflow/ai/understanding/`：user-message understanding capability。
- `ai/src/main/kotlin/com/nexusflow/ai/planner/`：first-version planning proposal and explanation capabilities。
- `ai/src/main/kotlin/com/nexusflow/ai/provider/`：provider-neutral structured request and provider adapters。
- `backend/src/main/kotlin/com/nexusflow/backend/core/aicontext/`：Backend-owned model context catalog, resolver, assembler, budget and external projection boundary。
- `docs/v0.1/requirements.md` 与 `docs/v0.1/app-module-technical-plan.md`：Backend authoritative state、read-only planning context、Kotlin Planner、structured proposal、Backend validation/approval/persistence/execution。

这些需求、技术计划和当前源码是边界证据，不是实现许可。不得据此补出 RAG、memory、agent loop、tool router、vector store、model registry、retry framework、provider fallback router 或独立 AI service。

## 1. Kotlin/JVM-first Default

第一版 Planner implementation 默认使用 Kotlin/JVM 和现有 Gradle/Ktor ecosystem。独立 Python runtime、单独 AI service 或新的 deployment boundary 只有在出现具体证据时才重新评估，例如：

- 无法替代的 Python-only runtime/library dependency；
- 独立部署、扩缩容或资源隔离需求；
- 明确安全隔离边界；
- materially different CPU/GPU/memory/runtime requirement；
- 当前 JVM process 无法承载的 provider SDK 或 streaming lifecycle。

“AI 生态常用 Python”不是独立服务的充分理由。本规则是默认和重新评估条件，不是现在实现 AI 的指令。

## 2. Main Planning Boundary

AI planning 的唯一长期边界模型是：

```text
Backend authoritative state
-> immutable/read-only PlanningContext
-> Planner
-> model/provider adapter
-> typed candidate
-> deterministic validation/guardrails
-> PlanProposal / RequestedAction / reasons / risk tags
-> Backend
-> permission / approval / idempotency / persistence / side effect
```

Backend 将 durable truth 投影为只读 planning context。AI 解释、生成候选、给出理由和风险标签。Backend 校验权限、审批、幂等、状态合法性、持久化和副作用执行。

## 3. Ownership

AI 只拥有 proposal / reasoning，不拥有 authoritative business state。

AI 不得：

- 写数据库；
- 调用有副作用的 tool/plugin；
- 持有 calendar、notification、provider credential 或用户 secret；
- 批准 action；
- 生成 authoritative action idempotency key；
- 推进 task authoritative state；
- 把 model output 当作 permission、approval 或 state-machine legality 的事实。

`RequestedAction` 是 proposal，不是 executable Backend command。只有 Backend 在权限、审批版本、幂等、policy、schema 和当前 task state 校验通过后，才能创建 durable command 或执行副作用。

## 4. PlanningContext

`PlanningContext` 由 Backend 构造，是 typed、immutable、最小必要的 planning facts snapshot。它只能包含当前 planning 需要的 intent、requirements、真实 Opportunity snapshot、只读插件结果摘要、busy-time projection、task identity/revision 等事实。

AI 不应独立访问 Backend business repository、用户 credential、secret storage 或任意 plugin 来重建 authority。若未来引入 async planning，context 必须包含足够的 identity/revision，使 Backend 可以判断 Planner result 是否仍适用于当前 task、approval 或 user/tenant scope。

设计 PlanningContext 时至少回答：

```text
Backend authoritative facts 来自哪里？
哪些字段是 planning 必需，哪些敏感字段被排除？
context identity / revision 如何让 Backend 拒绝过期 result？
AI 是否只读？
```

Memory/storage ownership and model Context are separate concepts. Backend owns authoritative data and memory. A model request receives only a bounded Context snapshot constructed for the current capability.

### 4.1 Core Context 与 Optional Context

Capability core context 是强类型、能力专属、 correctness-relevant 的输入。例如 Understanding 的 Task.intent / current requirements，Planning 的 Task.intent / current requirements / Opportunity snapshots。Core context 不因为 token budget 被静默裁剪；如果 correctness-critical 输入过大，必须由上游明确失败或降级处理。

Optional Context 是扩展 seam，使用 model-facing `ModelContextBlock` envelope：

```text
key
trust
content
```

`content` 可以是 `JsonObject`，但只能来自 source-owned typed DTO 或 domain data 的 deterministic projection/distillation，不能是 raw MCP/API JSON、raw HTML、raw string、repository entity 或 `toString()`。Supplemental reasoning facts 进入 optional Context；会参与 deterministic feasibility/validation 的事实必须走 typed core/domain path。

### 4.2 Context Catalog、Resolver 与 Assembler

Context Catalog / Resolver / Assembler 由 Backend 拥有。Catalog 注册 code-defined semantic keys、definition、lifecycle、priority、capability allowance，并拒绝 duplicate / unknown / disallowed keys。Resolver 在 verified actor/task scope 内读取自己的 authoritative source，返回 filtered/distilled `ResolvedModelContextBlock` 和 Backend-only provenance。Assembler 负责 dedupe、empty omission、per-block bound、global optional budget、AI-facing block mapping 和 safe diagnostics。

Provider adapters 不得查询 profile repository、calendar repository、task repository、location service、MCP tool 或外部 API。Provider 层只接收已经构造好的 `StructuredModelRequest` 并执行协议映射。

### 4.3 Context Selection 与 Lifecycle

Understanding 是当前 Context selection owner。Payload 可以包含：

```text
availableContextDefinitions[] = bounded key + description + selectionHint
optionalContext[] = already selected and resolved blocks
```

Model 只能从本次 request 明确提供的 definitions 中选择 bounded keys。Backend 必须本地验证 unknown、unoffered、duplicate、over-limit 和 no-definition selections；selection 不是权限授予，也不是 Requirement acceptance。

Context lifecycle 描述 model-context selection 的有效期，不等于 source storage owner：

```text
Request       = one inference snapshot
Execution     = one ask/execution process, reserved until real runtime exists
Task         = later turns for the same task
```

当前只持久化 Task-lifecycle selected keys，且只持久化 key；后续 model input 重新从 authoritative source resolve 当前值。新增 Task Context selection 会改变 future Planning input，因此 Backend 使用现有 version/freshness 语义使旧 plan 不再 current。

### 4.4 StructuredModelRequest 与 Capability Contract

AI capabilities follow a stable four-part request:

```text
systemPrompt  = capability behavior and payload interpretation rules
userPayload   = JsonObject built from typed capability DTO
outputSchema  = expected structured output contract
metadata      = local-only request identity, prompt version, capability, attempt and safe diagnostics
```

Capability code owns typed Kotlin payloads and semantic output validation. It serializes typed DTOs to `JsonObject` before creating `StructuredModelRequest`. Provider adapters serialize that JSON once at the transport boundary and must not learn Context key semantics.

`systemPrompt` may describe field semantics and trust precedence, but must not duplicate runtime values already present in `userPayload`. Opaque Backend IDs such as task/request/trace IDs stay in metadata unless the model has a specific semantic need.

### 4.5 External / MCP Distillation Boundary

Raw MCP/external API output is never model context. Source-specific typed decoding, allowlist projection, normalization, relevance filtering, bounds, and trust/provenance handling are mandatory before model exposure.

The legal future path is:

```text
MCP/API transport
-> tool/source-specific DTO
-> source-specific projector/filter/distiller
-> typed distilled Context payload or typed Backend domain model
-> Context Resolver / domain service
-> Context Assembler
-> model
```

Malformed, scope-mismatched, unsafe or unparseable external source data fails closed. External free text remains data, never instruction, and must be stripped/bounded before exposure.

### 4.6 Context Budget 与 Observability

Context is budgeted structurally and by serialized size, not by provider-specific tokenizers in the first version. Optional blocks and definitions have finite counts and serialized-char limits; lower-priority optional blocks can be omitted deterministically. Core context is not silently trimmed by optional context budget.

Each AI invocation may carry safe diagnostics such as capability, prompt version, available definition count, selected/resolved/included/omitted context counts, serialized optional/definition/full payload chars, and provider-reported token usage. Diagnostics are local-only metadata/audit evidence. They must not become model input and must not include full prompts, raw payloads, resolved preference values, provider response text, external free text, credentials, tokens or secrets.

### 4.7 Token-saving Rules

The default model-visible payload follows these rules:

- never send a full task message history by default;
- never send a full profile/preferences snapshot by default;
- first turn sends Context definitions, not all Context values;
- later turns send selected resolved values, not the whole definition catalog;
- opaque Backend IDs stay in metadata unless semantically required;
- raw MCP/API payloads never enter model input;
- external/domain payloads are projected to the smallest fields needed for current reasoning;
- candidate/item lists are bounded before model injection;
- do not add an extra LLM call merely to summarize structured external data;
- Context blocks are assembled per capability; one giant universal prompt is forbidden.

## 5. Typed Kotlin Boundary

Application-facing Planner inputs/outputs 使用 typed Kotlin model，例如 `data class`、`sealed interface`、enum/value type。Raw model text、provider JSON、provider DTO、token accounting、finish reason 和 provider exception 留在 provider adapter boundary。

不得让 `String`、`JsonObject` 或 `Map<String, Any>` 成为系统 planning domain model。可以在 adapter 内解析 raw output，但 adapter 必须产出 typed candidate，再由 deterministic guardrails 检查。

`JsonObject` 只允许作为 provider-bound payload envelope 或 optional Context block 的 already-distilled heterogeneous content；它不能替代 capability core DTO、Backend domain model、source DTO 或 deterministic guardrail input。

`PlanProposal`、`RequestedAction`、reason 和 risk tag 的类型边界必须表达业务语义，而不是 provider 机制。

## 6. Deterministic Guardrails

MUST requirements 必须由 deterministic code 执行，不能交给 prompt instruction。

包括但不限于：

- permission / tenant / user scope；
- schema validation；
- approval requirement；
- idempotency；
- task state-machine legality；
- budget、time conflict、unavailable window 等硬约束；
- security/privacy policy；
- external/plugin text untrusted input handling。

外部网页、插件数据、用户文本、provider response 和 tool output 都是不可信输入。它们不能改变 system policy、approval policy、credential handling 或 Backend authority。

## 7. Decision vs Inference Boundary

模型适合：

- 解释自然语言请求；
- 生成候选计划；
- 排序建议；
- 总结理由；
- 标注风险或不确定性；
- 在 deterministic policy 前后提供受限建议。

Kotlin deterministic code 拥有：

- hard accept/reject；
- permission；
- approval；
- idempotency；
- schema legality；
- state-machine transition；
- side-effect command creation；
- security/privacy enforcement。

当 model output 与 deterministic rule 冲突时，rule 获胜，AI result 被拒绝或降级为不可执行 proposal。

## 8. Coroutine、Lifecycle 与 Retry

AI runtime 必须遵循结构化并发和 cancellation propagation。Timeout、retry、provider unavailable、invalid structured output 和 policy rejection 是不同 outcome category，不能都揉成“模型失败”。

一旦 runtime 存在，必须明确：

```text
谁拥有 Planner operation lifecycle？
timeout / retry / recovery owner 是谁？
provider call cancellation 是否传播？
invalid output 如何 terminal？
stale planning result 如何被 Backend 拒绝？
```

不要提前建立 RetryManager、FallbackRouter、ModelRegistry、agent loop 或 provider routing framework。只有真实可达 failure、第二 provider/runtime variation 或 durable planning workflow 出现时，才引入对应 owner。

## 9. Provider Isolation

Provider SDK、model-specific DTO、raw output、exception、token accounting、streaming chunk 和 finish reason 留在 adapter boundary。Planner application boundary 只看 typed candidate 和 typed failure。

第二 provider abstraction 只在真实第二 provider、独立模型 variation、deployment constraint 或产品要求出现后引入。不要为了“以后可能换模型”创建 registry/factory。

## 10. Security、Privacy 与 Observability

AI context、prompt、provider request、provider response 和日志默认最小化敏感信息。

禁止：

- credential、access/refresh token、private key、Authorization header 进入 prompt、model context 或日志；
- 完整私密日历内容、完整用户偏好、完整聊天原文默认落日志；
- provider raw response 默认作为可检索业务记录；
- 外部/plugin 文本改变系统、审批或安全策略。

允许记录稳定低敏 identity 和 category，例如 planning request id、task id、tenant id、context version、provider category、outcome、guardrail rejection reason、latency bucket。完整 prompt/response logging 需要单独的安全评估和显式开关。

## 11. Evaluation Strategy

AI quality verification 与 deterministic correctness 分开：

- deterministic Kotlin unit tests：guardrails、normalization、MUST requirement enforcement、policy rejection；
- schema/contract tests：typed input/output boundary 和 serialization；
- Backend integration tests：Backend 是否拒绝 stale/unauthorized/invalid proposal；
- AI eval cases：semantic quality、reason correctness、risk tags、invariant preservation；
- observability checks：不记录 secret 或完整敏感内容。

当多个 plan 都合法时，避免 exact-string tests。更重要的是 invariant 稳定：不得越过预算、冲突时间、禁用时段、审批策略、权限和副作用边界。

当当前分支存在真实 `:ai` module 时，AI deterministic/provider-adapter verification 使用该 module 的实际 Gradle task，例如 `./gradlew :ai:test`。如果某个分支只有预留 `ai/` root，不得 invent AI verification command。

## 12. Explicit Non-goals

当前规范不批准实现：

- RAG；
- memory；
- multi-agent；
- agent loop；
- vector DB；
- prompt registry/framework；
- provider/model routing；
- caching framework；
- separate deployment/runtime；
- tool router；
- AI-owned business repository；
- AI-owned side-effect execution。

这些能力只有在真实产品需求、source owner、lifecycle、security 和 verification evidence 存在后重新评估。

## 13. Human Traceability、Simplicity 与 ROI

AI 相关 review 同样按：

```text
Architecture -> Coordination -> Local Reasoning -> Human Debug Simulation
```

必须能回答：

- Backend authoritative state 在哪里；
- PlanningContext 由谁构造、包含什么、排除什么；
- Planner 只拥有 proposal/reasoning 的边界在哪里；
- deterministic guardrails 在哪里执行；
- Backend 如何拒绝 unauthorized、invalid、duplicate 或 stale proposal；
- side effect 在哪里经过 approval/idempotency/persistence；
- model/provider raw data 在哪里被隔离。

任何关于内部 Planner class shape、RAG、memory、provider registry、fallback router 的结论，在当前 snapshot 都必须标为 `UNPROVEN`。不要用想象中的类名把 M0 understanding adapter 扩展成完整 Planner runtime。

## 14. Re-evaluation Triggers

出现以下事实时重新评估本规范的内部架构部分：

- 第一份真实 Planner source 存在；
- `:ai` Gradle module 或等价 runtime boundary 被正式加入；
- unavoidable Python-only/runtime dependency 出现；
- independent deployment/scaling/resource isolation 变成真实要求；
- 第二个真实 model/provider variation 出现；
- retrieval/memory 成为产品 requirement；
- durable planning workflow、retry/recovery、lease 或 process-crash recovery 出现；
- security review 要求隔离 prompt/data/model execution。

重新评估前，AI 仍只能作为 proposal/reasoning 边界被讨论，Backend 保持 permissions、approval、idempotency、persistence、credentials 和 side effects 的 authority。

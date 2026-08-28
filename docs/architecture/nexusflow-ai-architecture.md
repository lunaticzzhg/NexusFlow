# NexusFlow AI 架构主规范

当前实现状态：本快照中没有已实现的 AI runtime source，且 `settings.gradle.kts` 没有 `:ai` Gradle module。`ai/` 只是为未来 planning module 预留的目录；任何文档或计划都不能被解读为当前存在 working Planner。

本规范是 NexusFlow AI/planning 边界的长期 authority。它冻结的是 Backend/AI 信任边界、ownership 和 Kotlin-first 默认方向；内部 Planner architecture 在真实源码出现前保持 `UNPROVEN`。

状态分类：

```text
Boundary / ownership rules: PROVEN
Current runtime implementation: ABSENT
Internal Planner architecture: UNPROVEN until real source exists
```

## 0. 范围、权威与当前状态

### 适用范围

本规范适用于未来 `ai/` planning module、Planner API、provider adapter、guardrails、PlanProposal、RequestedAction、reason/risk tag、evaluation 和 Backend 集成边界。

### 事实来源

- `README.md`：`ai/` reserved；no AI runtime is implemented yet。
- `settings.gradle.kts`：当前只 include `:app:composeApp`、`:contracts`、`:backend`。
- `docs/v0.1/requirements.md` 与 `docs/v0.1/app-module-technical-plan.md`：Backend authoritative state、read-only planning context、Kotlin Planner、structured proposal、Backend validation/approval/persistence/execution。

这些需求和技术计划是边界证据，不是实现许可。不得据此补出 Planner、RAG、memory、agent loop、tool router、vector store、model registry、retry framework 或 AI service。

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

`PlanningContext` 由 Backend 构造，是 typed、immutable、最小必要的 planning facts snapshot。它只能包含当前 planning 需要的偏好、时间范围、约束、只读插件结果摘要、busy-time projection、task identity/version 等事实。

AI 不应独立访问 Backend business repository、用户 credential、secret storage 或任意 plugin 来重建 authority。若未来引入 async planning，context 必须包含足够的 identity/version，使 Backend 可以判断 Planner result 是否仍适用于当前 task、approval 或 user/tenant scope。

设计 PlanningContext 时至少回答：

```text
Backend authoritative facts 来自哪里？
哪些字段是 planning 必需，哪些敏感字段被排除？
context identity / version 如何让 Backend 拒绝 stale result？
AI 是否只读？
```

## 5. Typed Kotlin Boundary

Application-facing Planner inputs/outputs 使用 typed Kotlin model，例如 `data class`、`sealed interface`、enum/value type。Raw model text、provider JSON、provider DTO、token accounting、finish reason 和 provider exception 留在 provider adapter boundary。

不得让 `String`、`JsonObject` 或 `Map<String, Any>` 成为系统 planning domain model。可以在 adapter 内解析 raw output，但 adapter 必须产出 typed candidate，再由 deterministic guardrails 检查。

`PlanProposal`、`RequestedAction`、reason 和 risk tag 的类型边界必须表达业务语义，而不是 provider 机制。

## 6. Deterministic Guardrails

Hard constraints 必须由 deterministic code 执行，不能交给 prompt instruction。

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

未来 AI runtime 必须遵循结构化并发和 cancellation propagation。Timeout、retry、provider unavailable、invalid structured output 和 policy rejection 是不同 outcome category，不能都揉成“模型失败”。

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

- deterministic Kotlin unit tests：guardrails、normalization、hard constraints、policy rejection；
- schema/contract tests：typed input/output boundary 和 serialization；
- Backend integration tests：Backend 是否拒绝 stale/unauthorized/invalid proposal；
- AI eval cases：semantic quality、reason correctness、risk tags、invariant preservation；
- observability checks：不记录 secret 或完整敏感内容。

当多个 plan 都合法时，避免 exact-string tests。更重要的是 invariant 稳定：不得越过预算、冲突时间、禁用时段、审批策略、权限和副作用边界。

当前没有 `:ai` module，因此不得 invent AI verification command。AI 检查只能是 docs/source-boundary inspection，直到真实 Gradle module 出现。

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

任何关于内部 Planner class shape、RAG、memory、provider registry、fallback router 的结论，在当前 snapshot 都必须标为 `UNPROVEN`。不要用想象中的类名填补 runtime absent 的事实。

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

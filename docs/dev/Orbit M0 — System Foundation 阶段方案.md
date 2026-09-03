# Orbit M0 — System Foundation 阶段回看

本文按当前核心领域模型重写历史 M0 记录。旧实现草案中的阶段状态、独立目标对象、手动生成入口和条件命名已经废弃；当前代码以 `Task / Requirement / Opportunity / Plan` 为唯一核心模型。

## 当前结论

- Task 表示用户正在解决的一件事，用户意图保存在 `Task.intent`。
- Requirement 表示本次事情的明确要求，强度只有 `MUST` 和 `PREFER`。
- Opportunity 表示来自可信来源的候选事实快照，Plan 只能引用已验证的 Opportunity。
- Plan 表示可选择方案，当前有效性由 `Task.revision` 与 `Plan.revision` 对齐表达。
- 用户主操作是继续发消息、调整要求、选择方案。
- Backend 拥有认证、权限、持久状态、审计、要求写入、机会快照、方案选择和计划触发策略。
- AI 只输出结构化理解结果或 PlanDraft；不能制造事实、不能持久化事实、不能决定权限或副作用。

## M0 Baseline

M0 的系统基础现在包括：

- App 到 Backend 的认证链路。
- `KResponse` 与认证 contract。
- Task 创建、消息追加、要求投影、方案读取与选择的基础 API。
- PostgreSQL/Flyway auth 与 identity baseline。
- Task 相关 schema 的破坏式 baseline：`tasks`、`task_messages`、`task_requirements`、`opportunity_snapshots`、`plans`、`plan_opportunities`、`plan_requirement_evaluations`、`task_context_selections`。

## 非目标

- 不保留旧 wire、旧 DB 或旧文档模型。
- 不提供手动生成或换一批方案的公开操作。
- 不把长期偏好直接写成当前事情的隐藏要求。
- 不让 AI 直接改变持久事实或解释未验证方案。

## 验证

M0 相关变更至少运行：

- `./gradlew :contracts:jvmTest`
- `./gradlew :backend:test`
- `./gradlew :ai:test`
- `./gradlew :app:composeApp:ktlintCheck`
- `./gradlew :app:composeApp:testDebugUnitTest`

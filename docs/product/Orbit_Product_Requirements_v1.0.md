# Orbit Product Requirements v1.0

Orbit 是 Conversation-first, Planning-centered：用户先自然聊天、提问或表达想法；只有当用户表达安排、推荐、规划或选择一套方案的诉求时，Orbit 才形成 Task，并把这件事变成可选择、可执行的方案。

## 产品语言

- Conversation：用户与 Orbit 的交互容器，包含 Messages；可以只是问答，也可以承载一次 Planning。
- Message：Conversation 中的一轮用户或助手消息。
- Task：一次已激活的 Planning 事项；同一个 Conversation 在当前目标状态下最多关联一个 Task。
- 事情：用户正在解决或安排的一件事，是 Task 面向用户的表达。
- 要求：本次事情里会影响方案的明确偏好或限制。
- 方案：用户可以选择的一组安排。
- 机会：来自可信来源的候选事实快照，用户不需要直接管理。

App 对用户主要展示“对话 / 事情 / 要求 / 方案”，不展示内部实现术语。Conversation 可以没有 Task；Task 不应由普通闲聊或单纯问答创建。

## Core Conversation Flow

1. 用户发送自然语言 Message。
2. Backend 将 Message 追加到 Conversation。
3. Backend 调用 AI understanding，判断本轮是 Conversation 还是 Planning，并允许选择当前 turn 需要的 bounded Context。
4. 如果是普通 Conversation，Orbit 直接回答，或在需要实时、地域、外部或最新事实时发起 Backend 管控的 read-only external fact research。
5. 外部事实必须经过 source owner 的 typed decoding、projection、filtering、provenance/bounds 处理后，才能进入 grounded answer。
6. Conversation answer 保存为 assistant Message；如果没有 Planning 诉求，不创建 Task。

## Planning Activation Flow

1. 用户表达安排、推荐、规划或选择方案的诉求。
2. Backend 在同一个 Conversation 下创建或继续一个 Task。
3. Task 拥有 goal、requirements、opportunities、plans 与 revision。
4. Backend 保存 requirements / constraint deltas，并在 Planning input 变化时推进 Task revision。
5. Planning research 使用同一套 Backend bounded read-only research layer 获取需要的外部事实。
6. Backend 将可信结果保存为 Opportunity snapshots。
7. AI planner 只返回 PlanDraft 与 Opportunity IDs。
8. Backend deterministic validator 校验要求、来源、有效期和 revision 后保存 Plan。
9. App 在同一个 Conversation 里展示方案，用户选择一个仍然有效的 Plan。

## Requirements

Requirement 必须属于一个 Task。强度只有：

- `MUST`：必须满足，否则 Plan 不可用。
- `PREFER`：用于排序或解释，但不让方案失效。

Requirement 可以来自用户明确消息或系统整理。长期偏好可以作为模型上下文，但不会自动成为当前事情的隐藏要求。

## Plans

Plan 必须满足：

- 只引用已保存的 Opportunity snapshot。
- 带有 `revision`，且只在 `Plan.revision == Task.revision` 时可被选择。
- 带有 `validUntil`，过期后不可选择。
- 对 requirements 给出 deterministic evaluation。
- narrative 只解释 validated Plan，不新增事实。

## Public API Direction

目标公开入口以 Conversation 为普通交互容器：

- create a conversation
- read a conversation
- append a message to a conversation
- list tasks
- read a task
- update or delete a task requirement
- select a task plan

迁移期可以保留旧 Task-first 路由作为兼容入口，但它不再代表产品目标。普通聊天、直接问答和 external fact research 不应污染 Task 列表；Task 只在 Planning 激活后出现。App 不提供单独的生成方案操作；是否计划由 Backend 自动决定。

## Success Criteria

- 用户能自然聊天、提问和继续上下文，而不需要先创建事情。
- 用户问普通问题时能得到 Direct Answer。
- 用户问依赖实时或外部事实的问题时，Orbit 能通过有 provenance 的只读 research 给出 Grounded Answer，或在缺少必要输入时澄清。
- 用户表达安排、推荐、规划或选择方案时，同一个 Conversation 关联 Task。
- 用户能看到当前要求和可选方案。
- 用户能继续发消息来调整方向。
- 用户能选择仍然有效的方案。
- 维护者能从 Conversation messages、Task revision、requirements、opportunity snapshots、plans 与 audit events 追踪问题。

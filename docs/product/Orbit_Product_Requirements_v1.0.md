# Orbit Product Requirements v1.0

Orbit 帮用户把想安排的一件事变成可选择、可执行的方案。

## 产品语言

- 事情：用户正在解决或安排的一件事。
- 要求：本次事情里会影响方案的明确偏好或限制。
- 方案：用户可以选择的一组安排。
- 机会：来自可信来源的候选事实快照，用户不需要直接管理。

App 对用户展示“事情 / 要求 / 方案”，不展示内部实现术语。

## 核心流程

1. 用户描述想安排的事情。
2. Orbit 建立 Task，并把描述保存为第一条消息。
3. Backend 调用 AI understanding，从消息中提取 intent 与 requirement changes。
4. Backend 保存要求并推进 Task revision。
5. Backend 根据 PlanningReadinessPolicy 判断是否可以计划。
6. Backend 从可信 provider 获取 Opportunity snapshots。
7. AI planner 只返回 PlanDraft 与 Opportunity IDs。
8. Backend deterministic validator 校验要求、来源、有效期和 revision 后保存 Plan。
9. App 展示方案，用户选择一个。

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

## Public API

当前公开 Task API：

- `POST /v1/tasks`
- `GET /v1/tasks`
- `GET /v1/tasks/{taskId}`
- `POST /v1/tasks/{taskId}/messages`
- `PUT /v1/tasks/{taskId}/requirements/{requirementId}`
- `DELETE /v1/tasks/{taskId}/requirements/{requirementId}`
- `POST /v1/tasks/{taskId}/plans/{planId}/select`

App 不提供单独的生成方案操作；是否计划由 Backend 自动决定。

## Success Criteria

- 用户能从一段自然语言开始一件事。
- 用户能看到当前要求和可选方案。
- 用户能继续发消息来调整方向。
- 用户能选择仍然有效的方案。
- 维护者能从 Task revision、requirements、opportunity snapshots、plans 与 audit events 追踪问题。

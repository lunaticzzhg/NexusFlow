# Orbit M1 — First Planning Loop 阶段方案

本文按当前破坏式核心领域模型重写。M1 的目标是让用户通过自然语言推进一件事，Backend 在信息足够时自动形成可选择方案。

## 核心模型

```text
Task
├── intent
├── revision
├── Requirements
└── Plans for the current revision

Opportunity snapshots
└── validated facts and source refs used by Plans
```

## 用户体验

- 首页显示进行中的事情。
- 详情页显示消息、要求、方案。
- 用户通过发送消息补充或修改要求。
- 用户只需要选择一个方案；不需要理解系统何时计划。
- App 不展示内部技术术语。

## Backend 行为

- 创建 Task 时写入第一条 user message，并运行 understanding。
- 发送消息后运行 understanding；如果 intent 或 requirements 变化，Task revision 前进。
- 修改或删除 requirement 后，Task revision 前进并清空已选方案。
- `PlanningReadinessPolicy` 根据当前 Task snapshot 决定是否 planning。
- Planning 使用当前 Task intent、requirements、Opportunity snapshots 生成 PlanDraft，再由 Kotlin validator materialize Plan。
- 选择方案使用 `POST /v1/tasks/{taskId}/plans/{planId}/select`。
- 选择时校验 plan 属于 task、`Plan.revision == Task.revision`，且 `validUntil` 仍有效。

## 数据模型

Task 相关 schema 破坏式重建为：

- `tasks`
- `task_messages`
- `task_requirements`
- `opportunity_snapshots`
- `plans`
- `plan_opportunities`
- `plan_requirement_evaluations`
- `task_context_selections`
- `task_audit_events`

不提供 dual-read、dual-write、backfill 或兼容 view。

## AI 边界

- Understanding 只输出 `intentPatch` 与 `requirementChanges`。
- Planner 只输出 `PlanDraft`，其中只能引用 Opportunity IDs。
- Plan narrative 只能解释已经通过 deterministic validation 的 Plan。
- 外部文本、插件文本、模型输出都不是权限、审批、事实或副作用 authority。

## 验证

- contracts serialization 覆盖 Task intent、Requirement、Plan revision 和 Opportunity refs。
- backend domain tests 覆盖 controlled Opportunity snapshot 与 Plan validator。
- AI payload tests 覆盖 requirement changes 与 PlanDraft refs。
- App tests 覆盖创建、发消息、选方案，不覆盖已删除的公开 planning 操作。

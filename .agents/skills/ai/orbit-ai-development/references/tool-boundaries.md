# 工具与执行边界

新增请求动作、MCP/工具集成、审批 UI/API 或动作策略变更时读取本文。

## 能力链路

```text
read-only source snapshot -> PlanningContext -> PlanProposal/RequestedAction
-> backend validation -> approval snapshot -> external action + idempotency key -> audited result
```

AI 停在 `RequestedAction` / 契约 `ActionRequest`。它没有工具 client、OAuth token、数据库句柄、写接口、动作 Idempotency key 或标记动作完成的权限。后端只在鉴权与审批后创建稳定动作记录和键。

## 动作规则

- 默认每个外部写操作 `requiresApproval = true`；只读链接跳转只能经后端策略豁免。
- 动作参数是不可信提案数据。后端/工具网关使用逐动作 schema/allowlist，不能把任意 `Map<String, String>` 转交 Provider。
- 含写入的提案必须标记外部写风险并要求审批；核心策略强制该下限。
- 用户编辑会创建新的审批快照/版本；模型不会因提出动作就获得权限。
- 重新用于规划的工具输出仍是不可信输入。

新增动作类型必须端到端审查：契约 enum/schema、核心提案校验、后端策略、审批展示、执行器幂等、审计字段以及拒绝/重复执行测试。运行时所有权见 [docs/v0.1/app-module-technical-plan.md](../../../../../docs/v0.1/app-module-technical-plan.md)。

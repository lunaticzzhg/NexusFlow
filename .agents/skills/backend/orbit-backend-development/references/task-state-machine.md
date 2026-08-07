# 任务、审批与动作状态

变更状态或事件前，与 `contracts/task/TaskContracts.kt`、`TaskTransitionPolicy.kt`、`backend/domain/TaskAggregate.kt` 一起阅读。领域层是合法迁移的唯一权威。

## 不变量

1. `TaskLifecycle.requireTransition` 校验每一次迁移。
2. 已校验且含一个或多个 `ActionRequest` 的提案迁移到 `AWAITING_APPROVAL`；纯建议提案才可完成。使用 `TaskTransitionPolicy.afterValidation`，绝不能以 `ActionRequest.requiresApproval` 或生成文本绕过。
3. 审批保存提议动作快照和预期任务版本。批准/编辑/拒绝命令必须原子检查 owner、tenant、过期、状态、版本、schema 和风险策略。
4. 只有已批准且已持久化的动作可进入执行。每个动作都有服务端生成的稳定幂等键和终态结果/审计事件。
5. 取消、重试、部分失败和迟到 Worker 完成均是显式迁移；重复事件不能让终态任务复活。

`contracts/TaskContracts.kt` 是当前可执行迁移表。简要产品文档对取消的描述比该表更宽：它称活动任务可取消，而当前表不允许 `EXECUTING → CANCELLED`。不得在接口或 Worker 中自行解决。执行中取消需要先取得覆盖在途外部副作用与补偿的明确产品决策，再原子更新契约、聚合、恢复测试、API 文档和产品状态机文档。

```kotlin
fun TaskAggregate.approve(approval: Approval, expectedVersion: Long, at: Instant): TaskAggregate {
    check(version == expectedVersion) { "Task version conflict" }
    check(status == TaskStatus.AWAITING_APPROVAL) { "Task is not awaiting approval" }
    check(approval.isPendingAt(at)) { "Approval is no longer valid" }
    return transitionTo(TaskStatus.EXECUTING, at)
}
```

## 按任务类型的验收矩阵

| 变更类型 | 必需不变量/测试 |
| --- | --- |
| 纯建议规划 | `VALIDATING → COMPLETED`；重复 Worker 投递不产生额外迁移。 |
| 含写操作的计划 | `VALIDATING → AWAITING_APPROVAL`；决定前不得调用连接器。 |
| 批准/编辑/拒绝 | actor/版本/过期/schema 检查；拒绝/取消不产生外部动作。 |
| 外部动作 | 动作幂等；重复投递只产生一次连接器写入；部分结果可见且可重试。 |
| 重试/回收 | 重试预算/退避；过期租约可安全恢复；忽略终态任务。 |
| 新状态/事件 | 生命周期、聚合、持久化、API/事件兼容、时间线和恢复测试均更新。 |

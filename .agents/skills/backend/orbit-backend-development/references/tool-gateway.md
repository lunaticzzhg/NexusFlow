# 工具网关与外部副作用

规划/候选查询是只读的。创建日历、提醒、发送通知、创建工单、支付、删除或任意已连接账户变更均为写操作，必须由服务端批准并可审计。

## 能力边界

```kotlin
interface CalendarTool {
    fun search(input: CalendarSearch): List<CalendarSlot> // read capability
    fun createApproved(input: ApprovedCalendarWrite, idempotencyKey: String): ExternalActionResult
}

data class ApprovedCalendarWrite(
    val taskId: String,
    val approvalId: String,
    val actorTenantId: String,
    val actionSnapshot: CalendarWrite,
)
```

Worker 只能在原子审批决定后构造 `ApprovedCalendarWrite`。工具不得接收任意模型文本、原始用户 token，也没有标记任务完成的权限。

## 规则

- 在网关校验连接器专属 schema、账户/tenant 绑定、允许目的地、风险等级和动作新鲜度。计划和授权都会过期，执行前立刻再次校验。
- 使用服务端生成的稳定动作幂等键作为连接器请求键；推进任务状态前持久化外部引用、尝试、结果和脱敏错误。
- 连接器凭据按 tenant 隔离并遵循最小权限；轮换/撤销授权并掩码 secret。连接器输入/输出均不可信：限制大小、从检索文本移除 prompt injection 指令，并在受控保留策略下存储原始工件。
- 未知连接器结果视为 `UNKNOWN`/需要对账，不能安全重试。重复写入前按幂等键/引用查询连接器。

## 验收

证明模型提案不能调用写方法；未批准/过期/编辑后过期的动作被阻止；重复事件只有一次外部写入；发送后超时可对账；tenant A 永不使用 tenant B 凭据；脱敏审计/时间线覆盖每种结果。

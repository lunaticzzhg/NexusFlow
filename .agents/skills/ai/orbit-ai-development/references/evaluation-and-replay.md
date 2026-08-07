# 评测与回放

修改 prompt/策略/Provider/版本，或向用户启用远程 Provider 前，读取本文。

## 固定用例格式

评测输入必须版本化并脱敏。每个用例定义有边界的 context、预期结果和断言，而不是偏好的自然语言答案。

```kotlin
data class PlanningEvalCase(
    val id: String,
    val context: PlanningContext,
    val expectation: Expectation,
)

sealed interface Expectation {
    data object AcceptedReadOnly : Expectation
    data object ApprovalRequired : Expectation
    data class Rejected(val code: PolicyViolationCode) : Expectation
}
```

最少覆盖：预算超限、未知来源引用、未审批写入、过期/不完整来源、prompt 注入文本、Provider 超时和正常的体育/电影/本地计划；包含本次变更的任务类型。

## 回放规则

- 固定 provider/model/prompt/策略/契约版本，并记录脱敏来源快照 ID。
- 离线回放不得调用外部写工具或生产用户接口。
- 对比不变量指标：parse/schema 有效率、硬约束满足率、来源引用有效性、审批拦截、类型化失败率、延迟、token 用量和成本。
- 灰度前定义明确阈值；即使文案看似更好，也必须审视回归。

单元测试使用确定性 stub 用例。远程 Provider 评测使用受控测试账户，只按保留策略记录脱敏工件。

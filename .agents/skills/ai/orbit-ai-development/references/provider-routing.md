# Provider 路由、降级与预算

涉及远程模型 SDK、模型选择、流式响应、重试或任意成本/延迟变更时读取本文。

## 边界

保持规划核心的 Provider 契约与模型无关且可测试：

```kotlin
fun interface ModelProvider {
    fun propose(context: PlanningContext): PlanProposal
}

data class ProviderPolicy(
    val primary: String,
    val fallbacks: List<String>,
    val maxAttempts: Int,
    val timeoutMillis: Long,
    val maxInputTokens: Int,
    val maxOutputTokens: Int,
    val maxCostMinor: Long,
)
```

SDK/HTTP 实现属于 Provider 适配器或未来 `planning-service`，不属于 `Planner` 或策略。所有确定性单元/集成路径保留 `DeterministicStubModelProvider`。

## 路由算法

1. 远程调用前拒绝超过大小/token/墙钟时间预算的 context。
2. 按任务类型、灰度 cohort、能力、延迟与成本策略选择 provider/model，不由自由模型输出决定。
3. 每次尝试有 deadline，且只对临时失败做有边界重试。
4. 用 `PlanningPolicy` 校验结构化输出；仅在仍处于任务预算内时允许一次修复。
5. 仅因超时、传输、容量或结构化输出无效使用兼容降级；绝不跨越隐私/数据驻留/能力约束降级。
6. 记录结果，预算耗尽即停止；返回类型化原因让后端重试/追问，不得静默继续。

在适配器/服务层使用熔断器与并发舱壁。降级必须可观测，它不是普通成功。

## 禁止

- 无限重试校验失败。
- 未计入第一次成本就发起第二次模型调用。
- 在适配器外返回 Provider 专属对象。
- 在 trace/eval 元数据中隐藏当前模型或 prompt 版本。

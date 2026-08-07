# 可观测性与安全

以项目[可观测性规范](../../../../../docs/architecture/observability.md)作为质量门禁。每次命令/Worker/工具操作都必须可追踪，且不暴露敏感内容。

## 最小遥测要求

| 信号 | 必需属性 / 指标 |
| --- | --- |
| HTTP 命令 | route、状态/错误码、延迟、trace/correlation ID；不含请求体/token。 |
| 任务迁移 | task ID（或安全哈希 ID）、租户安全范围、前后状态、版本、事件 ID、causation。 |
| Worker | 事件年龄、领取/回收、阶段延迟、重试次数/预算、结果、DLQ 原因。 |
| AI/工具 | provider/connector、操作、模型/prompt 版本、schema 结果、token/成本/预算、外部动作状态。 |
| 安全 | 鉴权失败类别、限流决定、被拒绝的 scope/tenant（不泄露 secret/资源）。 |

## 防护措施

- 入队前限制输入大小/schema；按 actor/tenant 限流，并以任务预算/超时/熔断器保护昂贵模型/工具路径。
- 记录结构化错误类别和安全 ID，不记录原始 prompt、PII、access token、OAuth 授权、连接器 payload 或模型输出。只有明确保留与访问策略时才存储加密/原始工件。
- 可用时传播 W3C trace context，并在持久化事件中保留 correlation/causation ID。对 Outbox 年龄、重试/DLQ 增长、租约过期、授权失败、幂等冲突、错误率和预算耗尽告警。
- 为每个新增检索源/工具建模威胁：内容只是数据，绝非权限或可执行指令。校验所有工具参数，使用 allowlist 而非只靠 prompt 防护。

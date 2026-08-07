# AI 可观测性与成本

涉及模型遥测、日志、配额或事故分析时读取本文，同时阅读 [docs/architecture/observability.md](../../../../../docs/architecture/observability.md)。

## 每次调用

关联 `requestId`、`traceId`、`taskId`、租户安全的 actor 引用和尝试次数，并记录：

| 分组 | 字段 |
| --- | --- |
| 路由 | provider、model、prompt/策略/契约版本、降级/熔断状态 |
| 预算 | 输入/输出 token、成本 minor+currency、超时、剩余任务预算 |
| 质量 | 结构化 parse、策略违规、来源数量、提案/修复/拒绝结果 |
| 耗时 | 排队、Provider、校验、总耗时毫秒数 |

绝不在 span attribute 或普通日志中写入原始 prompt、原始模型响应、access token、OAuth 凭据或未脱敏用户/画像文本。若批准受保护回放存储，单独存放脱敏 payload，并配置保留期、访问控制和删除路径。

## 成本归属

在任务、tenant 与 Provider 三级执行上限。每次尝试调用均计费，包括 parse 失败和降级调用。对成本异常、拒绝/修复/降级率升高、Provider 延迟和安全策略拒绝告警。成本面板必须区分成功、安全降级和失败。

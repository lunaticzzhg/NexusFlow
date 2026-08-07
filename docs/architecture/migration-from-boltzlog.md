# Boltzlog Skills Migration Note

Orbit 复用了 Boltzlog 的完整 KMP 开发与 review 工作流：Skills Index、轻量 MVI、Feature 分层、Koin 生命周期、Compose 边界、列表生命周期、状态机、可观测性和验证门禁。

有意差异仅限产品与协议边界：

- 工程定位更新为 NexusFlow 的 `app/composeApp` 与 `com.nexusflow.orbit`。
- 身份上下文改为 `session → user → tenant → task/conversation`。
- 原有特定业务示例改为任务、审批、SSE、通知、深链和系统日历授权。
- 登录模型改为 Keycloak / OIDC Authorization Code + PKCE。
- 网络模型改为 REST Problem JSON、`202 Accepted` 异步任务、SSE 增量与 `Idempotency-Key`；服务端 REST 快照保持权威。

这些替换不改变通用的 KMP 分层、生命周期、状态所有权、最小抽象和证据化 review 原则。

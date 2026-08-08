# 认证与授权

当前认证的唯一事实来源是 [认证规范](../../../../../docs/architecture/authentication.md)。本专题规定所有后端 feature 在该基线上接入身份与授权的共同边界。

Android Credential Manager 取得 Google ID token；仅认证交换端点验证该第三方 token。后端随后签发并验证 NexusFlow 自有 access token，业务路由仅接受其 Bearer token，并据此构造 `ActorContext`。业务路由不得接受 Google token、客户端提交的 user/tenant/role，或开发身份 header 作为身份事实。

## 规则

- `ActorContext` 从已验证的 NexusFlow access token 派生，包含后端执行授权和数据范围所需的稳定 ID；生产配置缺失或 token 无效时必须失败关闭。
- 应用层 port 接收 `ActorContext` 或明确的 actor 范围。Repository 的每个用户可见查询/变更在 SQL 中施加 tenant 与 owner/权限约束；不得用未限定的 `findById` 作为用户访问捷径。
- 新 feature 需要角色或 scope 时，在该 feature 的 API 契约、领域授权策略、JWT claim 演进和负向测试中一并定义；不得由 route 临时字符串判断替代。
- 审计事件、持久化命令或未来后台执行都绑定安全的 actor 与 request/correlation ID；服务身份只能使用显式最小权限，不能冒用终端用户 token。
- 不记录 Authorization header、Google ID token、NexusFlow access/refresh token、provider subject、邮箱或连接器 secret。密钥通过运行时 secret 注入，不存在代码或容器镜像中。
- `TestActorResolver` 只属于显式 Test profile；生产默认 profile 不得回退到测试身份。

## 验收

至少测试无 token、伪造或过期 NexusFlow token、错误 audience/issuer、跨 tenant 或 owner 的访问，以及新 feature 实际引入的 scope/角色不足情况。认证端点另按认证规范覆盖 Google token 验证、会话轮换和注销。

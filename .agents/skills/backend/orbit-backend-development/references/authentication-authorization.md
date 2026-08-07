# 认证与授权

OIDC Authorization Code + PKCE 是 App 流程。后端验证 access JWT（issuer、签名/JWKS、过期时间、audience 与必要 claims），并创建精简的服务端 `ActorContext`；绝不信任 App 自带的身份。

```kotlin
data class ActorContext(
    val tenantId: String,
    val userId: String,
    val scopes: Set<String> = emptySet(),
    val subject: String = userId,
)

fun TaskRepository.findVisible(actor: ActorContext, taskId: String): TaskAggregate? =
    findByTenantAndOwner(actor.tenantId, actor.userId, taskId)
```

## 规则

- API 用户调用需要任务 read/write scope。审批与取消默认使用 `orbit.tasks.write` 加当前任务 owner/tenant，除非契约新增更窄 scope；服务身份使用显式最小权限 scope，绝不用用户 token 替代。
- 向应用层 port 传递 `ActorContext`，让 SQL 中的租户条件不可绕过。`findById(taskId)` 适配器只能供内部 Worker 所有权使用，不能作为用户查询捷径。
- 每次变更绑定事件 `tenantId`、审计 actor、correlation/causation ID。Worker 服务身份只能推进它已租赁/领取的任务。
- 不记录 Authorization header、refresh token、OAuth authorization code 或连接器 secret。生产环境将 secret 放在 Postgres 外部；连接器授权需加密和轮换。
- 本地 `DevelopmentActorResolver` 仅允许在 `ORBIT_RUNTIME_PROFILE=local` 下使用。JWT 鉴权缺失或错误配置时，生产必须失败关闭。

## 验收

测试无 token、无效 audience、缺少 scope、同租户其他用户、跨租户用户、过期审批以及服务身份尝试用户专属决定。GET 与 SSE 均不得跨所选策略边界泄露任务存在性。

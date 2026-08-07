# API 契约

所有公开 Ktor 路由或契约变更都读取本文。权威来源是 `contracts/src/main/kotlin/com/nexusflow/contracts/api/ApiContracts.kt`；DTO 会被适配器外部使用时，不得在 `backend` 重复定义。

## 路由规则

- 解析/校验传输输入，解析 `ActorContext`，调用一个应用层 use case，并将预期失败映射为 `ApiErrorResponse`。路由不得直接改变 `TaskStatus` 或调用 AI/工具 client。
- 会排队执行长任务的命令返回 `202 Accepted`；精确的幂等重放返回原结果（种子工程为 `200 OK`）。返回规范 ID、版本、状态和恢复 URL，不返回推测性的模型文本。
- 每个外部命令都使用 `Idempotency-Key`。绑定 `(tenant_id, owner_user_id, key)` 与请求指纹；相同 key 不同规范化请求必须为 `409 IDEMPOTENCY_CONFLICT`。
- 改变既有聚合的命令必须在已校验 body 中携带 `expectedVersion`。在命令事务中比较；版本过期为 `409 TASK_STATE_CONFLICT`。不得从客户端缓存推断，也不得把缺失版本当作“最新”。
- 保持版本化信封和可追加字段。不得原地重命名/删除序列化字段或枚举值，应增加 API/事件版本和迁移路径。

```kotlin
post("/v1/tasks") {
    val actor = actorResolver.resolve(call)
    requireScope(actor, "orbit.tasks.write")
    val request = call.receive<CreateTaskRequest>()
    val key = call.request.headers["Idempotency-Key"]
        ?: return@post call.problem(HttpStatusCode.BadRequest, VALIDATION_FAILED, "Idempotency-Key is required")
    val result = taskService.createTask(actor, request, key, call.traceId())
    call.respond(if (result.replayed) HttpStatusCode.OK else HttpStatusCode.Accepted, result.toResponse())
}
```

## 失败映射

| 条件 | HTTP / 错误码 | 说明 |
| --- | --- | --- |
| token 缺失/无效 | 401 `UNAUTHENTICATED` | 不泄露租户/资源事实。 |
| 有效身份缺少 scope 或资源所有权 | 403 `FORBIDDEN` 或不可区分的 404 | 选择并记录策略；列表接口必须静默按范围过滤。 |
| DTO/schema/输入无效 | 422 `VALIDATION_FAILED` | 仅含结构性字段详情。 |
| 幂等请求指纹不同 | 409 `IDEMPOTENCY_CONFLICT` | 绝不创建第二个任务。 |
| 版本/状态过期 | 409 `TASK_STATE_CONFLICT` | 安全时返回当前版本/状态。 |
| 依赖不可用 | 503 `DEPENDENCY_UNAVAILABLE` | 不得静默变为已完成/失败。 |

所有错误使用 `ApiErrorResponse(code, message, traceId, details)`。在边缘生成/传播请求关联 ID，并以 `X-Request-Id` 返回。新增过滤器或异常层前先阅读既有 `Routes.kt`。

## 契约测试

变更接口需证明成功路径、畸形输入、缺少 scope、租户/owner 隔离、重放和适用的冲突。任何移动端改动前先更新面向消费者兼容的 JSON/schema 检查。

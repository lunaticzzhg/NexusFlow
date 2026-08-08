# API 契约

所有公开 Ktor 路由或契约变更都读取本文。权威 HTTP DTO 位于 `contracts/src/commonMain/kotlin/com/nexusflow/contracts/api/ApiContracts.kt`；当 DTO 被 App 或其他适配器消费时，不得在 `backend` 重复定义。当前错误信封、Kotlinx JSON、请求 ID 和框架级失败映射由 `core/http` 实现。

## 路由规则

- 路由解析和结构校验传输输入，解析 `ActorContext`（若端点需要身份），调用一个应用层 use case，并将预期业务失败映射为 `ApiErrorResponse`。路由不得直接持久化业务状态或调用第三方客户端。
- 需要异步推进的命令在同一 feature 已具备持久化状态和恢复语义时才可返回 `202 Accepted`；同步完成的命令返回与其结果匹配的成功状态。不得为尚不存在的 Worker 伪造异步接口。
- 仅对会产生重复副作用的外部命令使用 `Idempotency-Key`。键必须绑定 actor 范围与规范化请求指纹；同 key 不同请求返回 `409 IDEMPOTENCY_CONFLICT`。
- 仅对存在并发编辑风险的已持久化聚合引入 `expectedVersion`。在命令事务中比较；版本过期映射为契约定义的 `409 CONFLICT`，不得把缺失版本当作“最新”。
- 保持版本化路径、可追加字段和消费者兼容性。不得原地重命名或删除已发布的序列化字段/枚举值；需要破坏性演进时，新增版本与迁移路径。

## 失败映射

| 条件 | HTTP / 错误码 | 说明 |
| --- | --- | --- |
| token 缺失或无效 | 401 `UNAUTHENTICATED` | 不泄露租户或资源事实。 |
| 有效身份缺少权限或资源所有权 | 403 `FORBIDDEN` 或不可区分的 404 | 选择并记录策略；列表接口按范围过滤。 |
| DTO / 输入结构无效 | 422 `VALIDATION_FAILED` | 只返回安全的结构性信息。 |
| 幂等键与请求指纹不匹配 | 409 `IDEMPOTENCY_CONFLICT` | 不创建第二次副作用。 |
| 并发或状态冲突 | 409 `CONFLICT` | 只在安全时返回当前版本/状态。 |
| 依赖不可用 | 503 `DEPENDENCY_UNAVAILABLE` | 不得伪装为业务成功或终态失败。 |

所有错误使用 `ApiErrorResponse(code, message, traceId, details)`。HTTP 平台生成或校验 `X-Request-Id`，并将其回写响应；feature 通过 `ApplicationCall.traceId()` 复用该 ID，不得自行生成第二套关联 ID。`StatusPages` 负责畸形请求与未处理异常，feature route 只映射其可预期的业务失败。

## 契约测试

变更接口需证明成功路径、畸形输入、身份/权限范围和该端点实际适用的幂等或冲突路径。涉及 App 消费时，先更新共享 DTO 与序列化测试，再接入两端适配器。

# Orbit 网络契约

本规范定义 KMP 客户端与 Orbit 后端的唯一网络边界。服务端 OpenAPI / versioned contracts 是最终事实来源；客户端不得从页面文案或 mock 推断协议。

- `core/network` 只拥有共享 `HttpClient`、认证 header、HTTP/Problem JSON 失败归一化与通用请求上下文；`core/error` 拥有上层可处理的 `AppException`；feature 拥有自己的 API、请求/响应 DTO 和 repository。
- `RuntimeConfig.apiBaseUrl` 是必填的非敏感构建配置；feature 不得创建第二个 client、全局 `get`/`post` 门面或单消费者 API 的 Koin binding。
- 外部协议和持久化 DTO 均使用 `@Serializable`；外部字段使用显式 `@SerialName`。DTO 不得越过 Repository；UI 不得直接调用 API。
- 共享 client 对同源非 2xx 响应统一解析 `application/problem+json` 或等价 Problem JSON，并映射为稳定 `AppException`；保留协程取消，不捕获编程错误。网络、超时和反序列化失败同样只在此边界归一化。
- API 返回类型表达实际 HTTP 语义：读取或同步写入可返回 DTO；异步任务创建必须处理 `202 Accepted` 与任务引用；不能把 `202` 当成方案已生成或动作已执行。
- 任务创建、审批决定和外部动作请求必须显式传递 `Idempotency-Key`。该键由调用发起方稳定保存到单次操作完成或明确失败，重试复用同一键；不得在 interceptor 每次请求时随机生成。
- REST 任务详情是权威快照，SSE 仅是可丢失的增量提示。SSE 事件带 `eventId` / cursor、`taskId`、类型和服务端版本；断线、前后台恢复、乱序或版本缺口时，重新拉取快照而非本地猜测状态。
- 认证 token、URL query、请求 body、原始响应正文和 Problem `detail` 不得进入日志。一次 API 失败只在共享执行器记录一次安全事件，字段仅包含路径模板、HTTP status、稳定 problem type 和安全 error type。
- feature API 使用明确路径常量，动态段保留模板，不记录实际 ID、host 或 query。请求 DTO 和响应 DTO 的新增字段必须明确缺失时的默认行为与兼容性测试。

默认分层：`Api -> RemoteDataSource (Result<DTO>) -> Repository (Result<Domain>) -> UseCase -> ViewModel`。HTTP 状态、Problem JSON、Ktor 异常、缓存格式和数据来源不得越过其所属边界。

实现或 review 时至少验证：成功、401/403、404/409、429、5xx、网络不可用、取消、Problem JSON 解析失败；异步任务还要验证重复键、`202`、SSE 断线恢复和过期版本。

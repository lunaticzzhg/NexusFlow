# App/KMP 网络契约

本参考仅适用于 NexusFlow App/KMP 的 HTTP、DTO、Repository 与客户端网络失败映射。跨 App / Backend / AI 的共享 wire schema 决策见 `contracts.md`；Backend authoritative behavior 以 Backend architecture/source 为准。

## 归属与模型

- `core/network` 只拥有共享 `HttpClient`、Ktorfit、`KResponse<T>`、HTTP/业务 envelope 失败归一化与通用请求上下文；`core/error` 拥有上层可处理的 `AppException`；feature 拥有自己的业务 API、请求/响应 DTO 和 repository。
- `RuntimeConfig.apiBaseUrl` 是必填的非敏感构建配置；feature 不得创建第二个 client、Ktorfit、全局 `get`/`post` 门面或单消费者 API 的 Koin binding。
- 一个业务边界一个 annotated API；请求 DTO、响应 DTO 按类别集中，而不是为每个 endpoint 或 DTO 过度拆文件。
- 外部协议和持久化 DTO 均使用 `@Serializable`，每个字段显式 `@SerialName`。稳定且跨 feature 的响应体为 `KResponse<T>`，归 `core/network`。
- Mock 与真实远端实现同一 feature 的 data source 接口，均返回 `Result<DTO>`；Mock 不得绕过 Repository 直接构造 domain 以掩盖 mapper 或协议问题。

## 直返响应与 HTTP 状态

- Ktorfit 的 `suspend` API 直接返回 `KResponse<T>`，不得再包裹 `Response<KResponse<T>>`，也不得在 feature 中恢复 `ResponseConverterFactory`。
- 共享 client 对**同源** API 的非 2xx 响应统一抛出 `HttpFailureException`。`core/network` 的 `ApiCallExecutor` 是 HTTP 状态、网络/超时、反序列化、`KResponse.code` 与空 `data` 的唯一归一化点，并返回 `Result<DTO>`；它保留协程取消，且不捕获编程错误。
- `RemoteDataSource` 是调用 `ApiCallExecutor` 的唯一 feature 边界。Repository 不得重复实现通用请求、业务 envelope 校验或宽泛 `try/catch`。
- 对象存储预签名 URL、第三方 OAuth 等跨域请求不注入 `X-Client-Instance-Id`，也不经过同源 HTTP 状态拦截。调用方按该外部协议自行处理。

## 结果与 Repository 边界

- API 固定返回 `KResponse<DTO>`；RemoteDataSource 固定返回 `Result<DTO>`；Repository 固定返回 `Result<Domain>`。`KResponse`、HTTP 状态和 Ktor 异常不得越过 RemoteDataSource，DTO、缓存格式与数据来源不得越过 Repository。
- `Result.failure` 统一使用 `AppException`：通用类别表达上层动作（重新认证、重试、普通拒绝等），不是 feature 的结果类型。`AppException` 的 feature `Business` 子类仅在有明确且不同的上层动作时定义；不得将业务码注册表或 feature 语义放进 core。
- HTTP status 不代表业务成功。`executeApi` 当前兼容业务成功码 `0` 和 `200`；其余业务码即为 `AppException.Rejected`，即使 HTTP 为 2xx。后端完成迁移且旧客户端不再需要兼容后，才能收敛为只接受 `200`。
- 除明确的无载荷接口（例如登出）外，成功业务码同时要求非空、可映射的 `data`；否则返回 `AppException.InvalidResponse`，不能构造半成品 domain 对象。
- DTO 的结构校验统一通过 `core/error` 的 `Result<T>.mapValidatedPayload` 完成：校验失败返回 `InvalidResponse`，原有失败保持不变；不要在每个 Repository 重复相同的 `fold` 或 `try/catch`。除用于识别无效外部字段的 `IllegalArgumentException` 外，mapper 的其他编程错误必须继续抛出。
- Repository 协调远端、缓存与 DTO 到 domain 的映射。仅当后端合同明确且会导致不同上层动作时，它才把通用 `Rejected` 转为 feature 的 `AppException.Business` 子类；例如 refresh 拒绝转为 `Unauthorized`。异常不得穿透到 Composable。
- 缓存未命中、过期和损坏 JSON 是 LocalDataSource 的正常 `DTO?` miss，不是 `Result.failure`。`Result` 仅用于一次性 suspend 调用边界，禁止放入 `Flow`、`UiState`、缓存或持久化模型。
- 不使用宽泛 `runCatching` 包住 suspend 网络调用；`CancellationException` 必须原样抛出。
- 修改路径、字段、业务码或响应含义前，先核对真实后端契约和旧客户端兼容性；优先新增可选字段或独立 endpoint，不猜测 wire 格式。

### Domain 字段最小化

Repository 暴露的 Domain 模型与 command 只包含当前产品行为、UI 或领域决策实际消费的语义；不得因 DTO 存在某字段而镜像进入 Domain。

- 固定请求常量、认证/语言/时区等请求上下文、传输状态码、服务端诊断字段，以及仅用于 DTO 校验、去重或排序的字段，留在 data 层。
- Repository mapper 必须在构造精简 Domain 前完成协议校验、排序和失败归一化。
- 当字段会驱动 UI、导航、权限、状态迁移或后续 Repository 调用时，才允许进入 Domain；新增时必须指出其消费点。
- 例外：Domain 需要保留稳定 ID、关联 ID 或时间字段，以维持当前行为、排序、幂等或下一次领域调用时，可以保留，并在 mapper 或测试中证明用途。
- 新增或消费接口字段时，按“DTO 字段 → Domain 是否需要 → 消费点 → 缺失/为空时降级”核对；没有消费点的字段不进入 Domain。
- 稳定 ID 只交给拥有该 ID 语义的 capability 或 feature 使用；不得因同为 `String` 将内容、媒体、任务、会话、外部资源等不同 owner 的 ID 混用。
- API、DTO 或 Repository 改动的 review 至少核对：每个新增 Domain 字段是否有消费点；每个省略的 DTO 字段是否不影响当前行为；DTO 不得泄漏到 presentation。

## 请求上下文

- `X-Client-Instance-Id` 由 `core/network` 自动加到同源 API 请求；业务 API 不声明、不传递该 Header。
- 显式 Header 必须保留；跨域、OAuth 与预签名 URL 不得泄露该 ID。它是非敏感随机 UUID，不是硬件或广告标识。
- 受保护 API 默认使用 `ApiAuthenticationMode.Automatic`：共享 client 在安全会话可用时注入 Bearer；UI、日志、普通存储和请求 query 中不得持有 token。需要调用方完整拥有 Header 与认证失效生命周期的连接，显式使用 `Explicit` 并自行提供 Bearer。

## 401 与可观测性

- `Automatic` 的同源受保护 API 读取安全会话、自动注入 Bearer；401 时合并刷新一次并至多重放原请求一次。重放仍为 401 时，仅当安全存储中的当前 access token 仍等于重放 token 才清除会话；已切换或缺失的会话不得被旧请求影响。SSE 等 `Explicit` 请求不读取会话、不注入或覆盖 Bearer、不刷新也不重放。
- SSE 必须显式提供 Bearer，并将首次 401/403 分类为重新认证、其余 4xx 分类为拒绝、5xx/网络故障分类为可重试；不能交给通用重连或自动刷新吞掉。
- 通用网络遥测只记录 method、HTTP 状态类别、耗时和稳定请求/业务标识；不得记录 token、验证码、邮箱、URL query 或响应正文。
- 一方 API 失败仅由 `ApiCallExecutor` 输出一次 `API` / `api_request_failed` 日志。日志 fields 只包含 `api_path`、`code` 与 `message`，不包含 `operation` 或 HTTP method；`level`、`event` 与异常 `error_type` 由共享 logger 或固定事件名提供。`code` 优先来自结构化 `KResponse` envelope 或同源非 2xx 错误体的 `code`，缺失时使用 HTTP status；都缺失时省略。不得为了字段齐全输出空值。
- `message` 优先输出结构化 `KResponse` envelope 或同源非 2xx 错误体中的原始 `message` / `msg`；缺失时使用客户端稳定 fallback：无效响应为 `Invalid response`，超时为 `Request timeout`，I/O 不可用为 `Network unavailable`，HTTP 失败按状态使用 `Unauthorized`、`Forbidden`、`Not found`、`Conflict`、`Rate limited`、`Client error` 或 `Server error`。不得记录原始响应正文、请求 header、URL query、token 或异常 message。转换、序列化、超时和 I/O 异常只允许通过 logger 的安全 `error_type` 输出异常类型。
- `api_request_failed` 不输出 `http_status`、`backend_code`、`has_backend_message`、`failure_category`、`failure_reason` 或 `backend_message`；需要排查时使用 `code`、`message` 和可选 `error_type`。
- 每个 feature 的 annotated `XxxApi` 在本文件内定义大写 `const val` 路由常量。Ktorfit 注解与 RemoteDataSource 传给 `ApiCallExecutor` 的值必须引用同一常量，防止接口和日志路径漂移。动态段保留 `{sourceId}` 等路径模板，不得记录实际 ID、host 或 query。

## 新接口接入与验收

1. 在 feature `data` 定义 annotated API 和 `@Serializable` DTO，API 直返 `KResponse<T>`；UI 不得直接调用 API。
2. 在 feature RemoteDataSource 通过 `ApiCallExecutor` 调用 API，并复用 `XxxApi` 的路由常量，返回 `Result<DTO>`；Repository 协调缓存并映射 DTO 为 domain，返回 `Result<Domain>`。
3. 在 ViewModel 将 `AppException` 转为可见 UiState/UiEffect，不把网络协议或异常传给 Composable。
4. 至少覆盖：HTTP 2xx 的业务成功与业务失败、同源非 2xx、断网/超时、无效响应、协程取消；涉及时另覆盖 401/SSE、跨域上传、幂等写入和需要特化的业务异常。
5. App/KMP API、DTO 或 Repository 改动按 `verification.md` 选择验证，通常运行相关 App 测试/编译，并在修改 App Kotlin/Gradle 时运行 `./gradlew :app:composeApp:ktlintCheck`；涉及共享 KMP 网络边界时，还要验证 Android 编译与受影响 iOS target。

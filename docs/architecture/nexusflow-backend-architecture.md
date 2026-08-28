# NexusFlow Backend 架构主规范

本规范是 NexusFlow Ktor Backend 的长期架构 authority，适用于 `backend/` 中的 HTTP 路由、application service、domain model、ports、infrastructure、persistence、外部 provider、认证授权、事务、后台工作流、错误边界和验证治理。

真实源码和测试优先于本文措辞。本文沉淀的是当前 Backend 已证明的 Kotlin/Ktor/JVM 方向和后续切片的判断规则，不要求为了文档对称创建 speculative layers。

核心原则：

> 后端拥有跨信任边界后的 durable truth、权限、事务不变量和副作用执行。架构对象按 authoritative source、writable owner、lifecycle、context、terminal/recovery 和 Human Traceability 建立，不按目录层数或模式名称建立。

## 0. 范围、权威与非目标

### 适用范围

本规范只约束 NexusFlow Ktor Backend。App/KMP 规则由 [Orbit 前端架构主规范](orbit-frontend-architecture.md) 维护；AI/planning 边界由 [NexusFlow AI 架构主规范](nexusflow-ai-architecture.md) 维护；共享 wire schema 由 `:contracts` 承担。

### 事实优先级

1. 当前 Backend 源码、测试和迁移事实；
2. 已冻结的 shared contract / product requirement；
3. 本规范；
4. Skill 工作流和一次性实现说明。

本文不能覆盖真实代码行为。若文档与现有源码冲突，先以源码为事实并通过 Work Order 或 governance change 修正文档。

### 非目标

- 不建立传统分层模板要求。
- 不要求每个 endpoint 创建 Handler、UseCase、Manager、Coordinator 或接口。
- 不为未来任务系统、Outbox、Worker、AI planning 预建占位实现。
- 不把 Backend domain / persistence model 移入 `:contracts`。
- 不为了减少行数引入 delegation-only wrapper。
- 不用 App 的 Compose、ViewModel、Koin 或 Runtime 术语机械套 Backend。

## 1. 主模型与依赖方向

Backend 默认依赖方向：

```text
external request/event
-> Ktor Route / Adapter
-> Application Service
-> Domain model / rules / ports
-> Infrastructure
-> DB / external provider / tool
```

简单流程可以保持：

```text
Route -> Service -> Repository
```

额外的 Handler、UseCase、Manager、Coordinator、Worker 或 Executor 只有在承载真实语义时才成立，例如跨多个 operation 的 durable lifecycle、明确的 recovery owner、独立事务不变量、外部副作用调度、并发租约或多个调用方复用同一业务动作。只包装一次 service/repository 调用的中间层应删除或避免新增。

当前 auth 实现证明的 baseline 是：

```text
AuthRoutes
-> AuthService
-> AuthPrincipal / StoredSession / IdentitySessionRepository port
-> JdbcIdentitySessionRepository / GoogleJwtIdentityVerifier / JwtAccessTokenCodec
-> PostgreSQL / Google identity provider
```

这些是当前已落地模式示例，不表示 Task、Approval、Outbox、Worker 或 AI 代码已经存在。

## 2. Ownership 模型

### 核心规则

- Backend persistence/domain state 是跨信任边界后的 authoritative durable business truth。
- 一个 mutable business fact 只有一个 writable owner。
- Route 拥有协议适配，不拥有业务 sequencing。
- Application Service 通常拥有 request-to-terminal 的业务 flow、decision、authorization application 和 failure terminal。
- Repository / infrastructure 拥有持久化机制、外部 SDK 协议和 atomic write mechanics，不拥有产品 policy。
- Worker / Coordinator / Executor 只在真实 durable 或 async lifecycle 出现时创建。

### 术语

| 概念 | Backend 责任 | 不负责 |
| --- | --- | --- |
| `Route / Adapter` | HTTP method/path、request decode、response encode、已知错误到 HTTP 映射 | 业务状态机、权限策略、事务不变量 |
| `Application Service` | command/query 的业务决策、owner/tenant/scope 校验、terminal、调用 ports | JDBC 细节、外部 provider DTO、HTTP status 细节 |
| `Domain model` | 业务身份、状态、不变量和可测试规则 | Ktor、数据库 entity、raw JSON |
| `Port` | application/domain 需要的能力合同 | provider/driver 细节 |
| `Repository / Infrastructure` | persistence mechanics、transaction execution、external provider access | 产品 policy、审批策略、模型推理 |
| `Worker / Coordinator` | durable workflow 的 lease、retry、recover、terminal 推进 | 为短请求制造层次 |

命名必须表达业务意图。`Manager`、`Helper`、`Runtime`、`Handler` 等泛名不能替代 ownership 说明。

## 3. 身份与授权上下文

外部请求中的 user、tenant、scope 和 owner identity 都是不可信输入。HTTP adapter 必须从已验证 business access token 或等价可信机制创建 `ActorContext`，再把它传入 application boundary。

当前 `ActorContext` 的注释已经冻结了重要边界：它是传入 use case 的 verified identity；HTTP adapter 必须从 validated business access token 创建它；user identity 不能来自 request body。

后续 Backend feature 必须回答：

```text
ActorContext 在哪里创建？
owner / tenant / scope 在哪个 application boundary 校验？
request body 中哪些 identity 只是对象引用，不能作为权限事实？
失败时返回什么 application/domain failure，再由 Route 映射到 HTTP？
```

`BearerActorResolver` 证明了当前模式：读取 Bearer token、验证 token、检查 session active，再产出 `ActorContext`。Feature service 不应绕过该边界信任 body 字段。

## 4. 事务与不变量边界

事务范围来自业务不变量，不来自“每个 repository 方法一个 transaction”的形式规则。

当前 `AuthService.refresh()` 是已证明示例：

```text
read session by refresh token hash
-> reject revoked/expired session and revoke family
-> create replacement session
-> atomically rotate current session to replacement
-> failed rotation revokes family
-> return new issued session
```

`JdbcIdentitySessionRepository.rotateSession()` 执行同一 transaction 内的旧 session revoke 和 replacement insert，因此 rotation 的 atomicity 属于 repository mechanics；“发现 reuse/invalid 后撤销整族”属于 application decision。

后续 durable flow 如果出现 `state + event + outbox`、审批版本推进、幂等记录和外部 effect command，必须在同一 transaction 中提交所有维持 invariant 所需的 durable facts。不要提前创建 Outbox/Worker 抽象；当产品切片真正需要可恢复副作用时，再按 invariant 选择 owner 和事务边界。

## 5. Durable Workflow 与 Ephemeral Coroutine

Coroutine、process worker、timer 或 in-memory queue 不是 authoritative durable task state。它们可以推进工作，但不能成为业务事实的唯一载体。

当 Backend 出现 durable workflow 时，设计前必须说明：

```text
durable state 写在哪里？
谁 start / retry / recover / lease / terminal？
operation identity 是什么？
process crash 后如何恢复？
duplicate / stale / late result 如何拒绝？
外部 effect 的幂等边界在哪里？
```

短 HTTP 请求不需要 Worker/Coordinator。只有后台继续运行、跨进程恢复、有限 retry、租约、外部副作用补偿或 terminal ownership 真实出现时，才建立对应 owner。

## 6. Kotlin 并发与阻塞 IO

Backend Kotlin 代码遵循结构化并发：

- cancellation 必须传播；
- 不使用 `GlobalScope` 或 ownerless background job；
- broad `runCatching` / `catch` 不得吞掉 cancellation；
- 长生命周期 scope 必须由明确 lifecycle owner 创建和关闭；
- 已知阻塞 JDBC、file 或 blocking SDK work 不得阻塞需要 non-blocking 的 execution context；
- execution context 已经拥有边界时，不为形式机械增加 dispatcher wrapper。

`suspend` 不保证代码自动离开调用线程。是否切 dispatcher 由具体 driver/SDK 和 owner 的执行模型决定，而不是由类名决定。

## 7. Kotlin-first Modeling

Backend 默认使用 idiomatic Kotlin/JVM 表达业务：

- typed `data class`、`sealed interface`、enum/value type 表达稳定业务状态；
- `Clock` 或等价可注入时间源用于 time-dependent decision；
- explicit ports 隔离 external provider、persistence 或 tool；
- `kotlinx.serialization` 用于 wire boundary where relevant；
- domain/application 名称表达业务意图，不表达机制。

避免让 `Map<String, Any>`、untyped JSON、raw provider DTO、generic Manager/Helper/Runtime container 成为业务模型。Provider DTO、database row 和 HTTP schema 可以存在，但不能替代 domain concept。

## 8. Time、ID、Idempotency 与 Optimistic Concurrency

时间依赖的业务 decision 必须可测试。当前 auth service 和 token verifier 使用 `Clock` 是已证明模式。

Backend 拥有 request、operation、entity、approval version、idempotency key 和 expected version 的 authority：

- client 可以提供 request idempotency key，但 Backend 持久化、验证和定义 replay semantics；
- AI 不能生成 authoritative action idempotency key；
- App 不能本地推进 authoritative task state；
- expected version / optimistic concurrency 由 Backend 校验并返回结构化 conflict。

每个 identity 都要有 owner：谁生成、谁验证、谁持久化、谁可以安全重放。

## 9. HTTP 与错误边界

Route 负责协议输入输出和已知 application/domain failure 到 HTTP 的映射。HTTP status、Problem JSON、protocol exception 和 request decode failure 不进入 domain state。

Application Service 应返回或抛出语义清楚的 application/domain failure。不要在每一层复制 try/catch/error normalization。Transport 错误、provider 错误和 domain rejection 应在边界处逐层转义，避免把 raw exception message 泄漏给用户或日志。

当前 `AuthRoutes` 展示了最小 Route 责任：接收 request DTO，调用 `AuthService`，把 invalid Google identity、invalid session 和 invalid request 映射为 HTTP error。

## 10. External Effects、Retry 与 Recovery

外部副作用必须有一个 recovery owner。Retry 只能服务真实可达且有价值恢复的 failure，不为理论完整性增加框架。

设计 effect flow 时至少说明：

```text
谁决定 effect 应该执行？
谁执行 effect？
effect command 是否 durable？
成功 / 失败 / duplicate / retry / terminal 写在哪里？
debug boundary 如何区分 decision 失败和 execution 失败？
```

未来 Calendar、Notification 或 plugin 写操作必须由 Backend 在审批、权限、幂等和 audit 后执行。AI 的 `RequestedAction` 只能是 proposal。

## 11. Observability 与 Secrets

Backend 日志和 trace 要能定位 flow，但不能泄漏敏感内容。

允许记录低基数、稳定、非敏感字段，例如 request id、task id、tenant id、operation id、stage、outcome category、safe error category。禁止记录 access token、refresh token、credential、private key、Authorization header、raw request/response body、完整插件 payload、完整模型 prompt/response、预签名 URL 或 secret config value。

Secret 来自 runtime/environment 或 secret manager；不得写入 repository、Docker image、frontend config 或日志。`BackendRuntimeConfig` 当前体现了 database、JWT、Google audience 等配置边界：数据库和 JWT secret 是 runtime 注入；Google audience / client id 属于非秘密配置，但仍应避免散落在 feature 代码中。

## 12. Module 与 Package 边界

- `backend/core` 放真正跨 feature 的基础能力，例如 identity、HTTP serialization/error、config、persistence lifecycle、health。
- `backend/feature/<name>` 拥有自己的 API/application/domain/infrastructure 行为。
- `:contracts` 只放实际跨边界共享的 wire schema。
- Backend domain / persistence model 留在 Backend 内；App presentation/domain model 留在 App 内；AI provider/internal model 留在 AI 内。

新增 shared contract 必须说明 producer、consumer、兼容策略和验证；不得因为字段相似就把内部模型提升进 `:contracts`。

## 13. Human Traceability、Simplicity 与 ROI

Backend review 与复杂设计按同一顺序：

```text
Architecture -> Coordination -> Local Reasoning -> Human Debug Simulation
```

必须能回答：

- HTTP request 在哪里停止作为 protocol，在哪里成为 application intent；
- Flow Owner / State Owner / Decision Owner / Effect Owner 分别是谁；
- durable truth 在哪里写入；
- transaction scope 为什么覆盖这些写入；
- identity / permission 如何进入 use case；
- coroutine lifetime 和 durable state 如何区分；
- Worker / Coordinator 是否由真实 durable/async lifecycle 证明。

没有任何 layer、pattern、test 数量、LOC 下降或文件拆分本身可以构成 Human Traceability PASS。优先删除 obsolete concept、重复 owner、无意义 wrapper 和 speculative extension；只有真实第二调用方、真实 lifecycle、真实 failure/recovery 或明确 trust boundary 证明收益时才新增抽象。

### 重新评估触发条件

- 新增任务状态机、审批、幂等执行或 outbox-like durable effect；
- 新增真实外部写工具或插件；
- 新增后台 worker、lease、retry、recover、process-crash 恢复；
- 新增跨 producer/consumer 的 shared contract；
- 引入第二种 persistence/provider/runtime variation；
- 现有 auth baseline 被真实源码演进替代。

## 14. 当前源码示例

以下只是当前已证明的例子：

- `backend/.../feature/auth/api/AuthRoutes.kt`：Ktor Route / protocol adapter；
- `backend/.../feature/auth/application/AuthService.kt`：application-flow decisions；
- `backend/.../feature/auth/domain/AuthPorts.kt`：domain ports and typed identity/session contracts；
- `backend/.../feature/auth/infrastructure/JdbcIdentitySessionRepository.kt`：transaction-backed persistence mechanics；
- `backend/.../core/identity/ActorContext.kt`：trusted identity boundary；
- `backend/.../bootstrap/BackendBootstrap.kt`：composition/bootstrap and route registration；
- `backend/src/test/.../AuthServiceTest.kt`：refresh rotation and session-family revoke behavior；
- `backend/src/test/.../DependencyLifecycleTest.kt`：application resource lifecycle evidence。

本文不声称 Task、Approval、Outbox、Worker、AI planning 或 plugin execution 已实现。

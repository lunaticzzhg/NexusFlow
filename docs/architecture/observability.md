# Feature 可观测性规范

## 触发与目标

当 feature 具有异步操作、外部 I/O、重试、状态迁移或用户可见失败时，必须提供结构化诊断。目标是让联调和问题定位能够回答：哪一类操作在何阶段失败、失败归类为何、是否重试或完成；同时为后续产品埋点保留稳定语义。

本规范只约束 feature 级诊断。它不取代 `core/network` 的通用网络遥测，也不要求接入 Analytics、Crash SDK、全局 EventBus 或分布式 tracing。

## 所有权与边界

每个 feature 拥有自己的操作上下文和诊断事件，不共享万能业务事件类型：

```text
业务流程
  ↓
<Feature>OperationContext + <Feature>DiagnosticEvent
  ↓
<Feature>DiagnosticReporter
  ├── <Feature>LogMapper → AppLogger
  └── <Feature>AnalyticsMapper → Analytics SDK（仅在真实接入后）
```

- `OperationContext` 表达一次操作的稳定关联信息。
- `DiagnosticEvent` 表达发生了什么；它是 feature 的强类型业务语义，不依赖日志或埋点 SDK。
- `DiagnosticReporter` 是业务代码唯一的诊断出口。
- Mapper 决定如何输出到日志、埋点或其他外部系统。

不得引入全局 EventBus、`BaseEvent`、万能 `TelemetryManager`、`ThreadLocal`/MDC，或让 feature 任意订阅其他 feature 的诊断事件。当前只有一个真实输出端时，Reporter 应同步委托给单个 mapper；不要提前引入 `SharedFlow`、队列或 consumer 注册表。

## Context 与字段分级

Context 只含操作关联所需的稳定字段。例如任务规划可使用 `taskId`、`traceId`、`attempt`。不要用 Context 保存原始用户内容或平台对象。

| 字段类别 | 日志 | 埋点 | 说明 |
| --- | --- | --- | --- |
| 操作关联 ID（如 `task_id`） | 可用 | 禁止 | 只用于联调和单次问题关联。 |
| 枚举维度（如 `stage`、`reason`、`category`、`result`） | 可用 | 可用 | 必须低基数且稳定。 |
| 次数、时长、大小分桶 | 可用 | 可用 | 埋点优先分桶，避免高基数。 |
| 原始对话、精确位置、OAuth 凭证、Problem detail | 禁止 | 禁止 | 用户隐私与安全实现细节。 |
| 原始 userId、spaceId、token、Header、预签名 URL | 禁止 | 禁止 | 身份、鉴权或敏感基础设施数据。 |
| 原始异常 message、请求/响应 body | 禁止 | 禁止 | 可能携带隐私或不稳定内容。 |
| 后端结构化 envelope message | 仅 `api_request_failed` 可用 | 禁止 | API 联调诊断字段，必须来自 `message` / `msg`，不得使用 raw body。 |
| 安全异常类型名、HTTP 状态、失败分类 | 可用 | 按 allowlist | 必须不包含原始 message。 |

埋点 mapper 必须拥有自己的字段 allowlist，不能直接复用日志的完整字段集合。
网络边界的转换、序列化、超时和 I/O 异常可以通过 `cause` 交给共享 logger 输出安全 `error_type`；不得额外记录异常 message、响应正文、请求 header 或 URL query。`API` / `api_request_failed` 是 Problem JSON 的唯一日志例外：仅记录安全 `problem_type` 与客户端固定 fallback，不记录 `detail`。
结构化日志字段 value 为 `null` 或空字符串时必须省略字段；不得为了字段齐全用 `.orEmpty()` 输出 `key=` 空壳字段。空白字符串（例如 `" "`）不自动清理，`false`、`0`、枚举值等非 null 值不是空值，必须按原语义保留。

## 事件与命名

- 事件使用 feature 前缀和稳定结果语义，例如 `task_plan_generation_failed`、`approval_execution_finished`。
- `stage`、`reason`、`category`、`outcome` 必须使用 feature 拥有的枚举或 sealed 类型；不得在业务路径散落任意字符串。
- 新增字段优先采用可选的、加性语义；不得改变既有事件名或字段含义。
- 一次操作的开始、成功、失败、取消和延后清理等可观测状态应明确区分。取消必须继续传播，不能被改写为失败。
- 聚合事件只能输出计数、分桶或稳定类别，不能通过集合、动态 key 或原始内容泄漏标识符。

## 日志等级与现场语义

日志等级只使用 `DEBUG`、`INFO`、`ERROR`。不得新增或恢复 `WARN`：失败、不可用、拒绝、异常、持久化失败、外部 I/O 失败、登录/会话恢复失败统一输出 `ERROR`；正常开始、完成、跳过、重试计划、非失败聚合摘要使用 `INFO` 或 `DEBUG`。协程取消必须继续传播，不能作为失败日志输出。

业务状态机中的日志等级必须在具体分支现场可见。认证、会话、任务/审批状态机、重试协调、持久化恢复、deep link 分发等代码，读者应能在当前分支直接看到 `logger.info(...)` 或 `logger.error(...)`，而不是跳转到 helper 才知道等级。

禁止用以下形式隐藏业务分支的成败等级：

```kotlin
logger.log(level = result.toLogLevel(), tag = tag, event = event, fields = fields)
logger.logSessionEvent(failed = true, tag = tag, event = event, fields = fields)
logger.log(level = if (failed) LogLevel.ERROR else LogLevel.INFO, tag = tag, event = event)
```

推荐在业务分支现场直接表达等级：

```kotlin
when (result) {
    SessionResult.StorageUnavailable ->
        logger.error(tag = authLogTag, event = "auth_session_restore", fields = fields)
    SessionResult.Restored ->
        logger.info(tag = authLogTag, event = "auth_session_restore", fields = fields)
}
```

允许的封装必须保持等级语义清楚：

- `logFields { ... }`、字段构建和枚举到字段值的转换可以封装，因为它们不决定日志等级。
- Feature 拥有的 `DiagnosticEvent -> LogMapper` 可以封装事件到日志的映射；这是明确观测边界，不应把业务流程改成到处直接依赖 SDK。
- `core/network`、基础设施 telemetry、同类 catch 内固定输出 `ERROR` 的 `logUnavailable()` 可以封装；函数名必须表达固定失败事件，内部不得根据参数在 `INFO` 与 `ERROR` 间切换。

Review 时必须至少搜索：

```bash
rg -n "logger\\.log\\(|LogLevel\\.|toLogLevel|failed\\s*=\\s*(true|false)|log[A-Za-z0-9_]*Event" app/composeApp/src
rg -n "LogLevel\\.WARN|logger\\.warn|fun warn\\(|\\.warn\\(|\\bWARN\\b" app/composeApp/src .agents
```

若命中业务状态机、会话生命周期、认证流程或重试协调代码，默认要求把等级写回具体分支。若命中明确的 mapper 或基础设施边界，确认它没有泄漏敏感字段、没有吞掉取消、且失败路径固定输出 `ERROR`。

## 失败隔离与演进

诊断是旁路能力：日志或后续埋点上报失败不得改变业务结果、状态迁移、重试或取消语义。外部 Analytics SDK 的异常由 Analytics mapper 自行隔离；不要在业务流程中捕获后继续执行来“保护埋点”。

初始实现只需要 `LogMapper`。以下任一事实出现时，才新增 `AnalyticsMapper`：

- 已选定并接入实际 Analytics SDK 或服务端埋点协议；
- 同一事件需要同时输出到日志和第二个稳定消费者；
- 产品确实需要按事件维度聚合指标。

新增 Analytics mapper 不得要求修改业务调用点；业务层仍只调用 `reporter.report(event)`。

## 验证

每个采用本规范的 feature 至少验证：

1. 成功、失败和取消路径发出正确的强类型事件；取消仍被抛出。
2. Log mapper 的事件名、必需字段、失败分类和异常类型符合契约。
3. 日志中不出现禁止字段；Analytics mapper 接入后另测 allowlist。
4. Mapper 或 SDK 的故障不改变业务行为。
5. DI 能解析 Reporter 与当前 mapper。

新 feature 在对应 feature 的决策卡中填写字段、事件与验证记录，并遵循本规范的最小字段与脱敏要求。任务/审批模块是当前参考方向：

- `app/composeApp/src/commonMain/kotlin/com/nexusflow/orbit/feature/tasks/observability/`
- `app/composeApp/src/commonMain/kotlin/com/nexusflow/orbit/feature/tasks/data/DefaultTaskRepository.kt`
- `app/composeApp/src/commonMain/kotlin/com/nexusflow/orbit/feature/approval/data/ApprovalExecutor.kt`

例外必须记录真实限制、替代方案、用户/安全影响和重新评估条件；“可能以后会有更多消费者”不是引入全局总线的充分理由。

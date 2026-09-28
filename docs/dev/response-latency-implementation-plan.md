# 用户提问回复提速落地方案

日期：2026-09-19

源码基线：`35e5659`，编写前工作区无未提交修改。

状态：设计与实施计划，尚未实现；未声明独立架构验收或 Human Traceability PASS。

设计来源：本任务中用户确认的“单次流式推理直接回答或提出操作、Backend 固定分支执行、全链路流式、有界并发”架构。用户明确要求直接给出设计，无需独立架构角色；本文件按该明确指示编写，不伪称外部 Work Order。

## 1. 目标、成功标准与非目标

### 1.1 用户目标

让用户更早看到有用正文、更快拿到完整答案；多个用户同时提问时，不因某一条慢请求阻塞其他会话。

主要验收标准：

1. 普通知识问答在正常成功路径中只调用模型一次，且第一段正文在模型完成前到达 App。
2. 一轮外部研究后回答在正常成功路径中最多两次模型调用；只允许一个受限查询批次。
3. 不同会话在配置上限内并行，同一会话仍按业务顺序执行。
4. 数据库最终状态、客户端展示和取消/重试语义一致；不能用错误路由、忽略实时事实或提交半截正文换速度。
5. 相同模型、题集、输入长度和负载下，记录改造前后首字延迟与完成延迟的 p50/p95。没有基线前不承诺固定秒数或百分比。

本文“调用次数”指正常成功路径。网络重试、模型修复、用户重试必须单独统计，不能藏在预算之外。Planning 后续生成流程有自己的调用预算，不算作两次问答预算。

### 1.2 行为冻结

- Backend 继续拥有权限、持久化、任务 revision、幂等、终态与副作用 authority。
- AI 输出是文本候选或操作提议；不能直接执行任意工具、写 DB 或推进任务状态。
- 保留 HTTP 快速持久化受理、ResponseRun、结果提交事务与 App snapshot/SSE 模式。
- 保留正常业务空结果、数据源不可用、模型输出非法、内部不变量错误之间的区别。
- 无合法候选时不调用 PlanComposer；计划合法性仍由 Backend 校验。

### 1.3 非目标

本轮不引入通用 Agent Runtime、任意工具循环、动态工作流引擎、Provider Registry、第二套任务队列、RAG、通用缓存、查询依赖图、每 token 持久化、模型选择路由、规划解释异步补写。

已存结果自动重放、研究步骤检查点、跨实例实时总线作为后续独立需求。本轮保留明确的限制，不以新增模式/开关提前实现。

## 2. 项目规范与事实来源

本文件是一次性任务计划，不复制或替代长期 authority：

- [项目全局规则](../../AGENTS.md)
- [Skill 路由](../../.agents/skills/INDEX.md)
- [Backend authority](../architecture/nexusflow-backend-architecture.md)
- [AI authority](../architecture/nexusflow-ai-architecture.md)
- [App authority](../architecture/orbit-frontend-architecture.md)
- [Feature workflow](../../.agents/skills/nexusflow-feature-development/SKILL.md)
- [持久化验证清单](../../.agents/skills/nexusflow-feature-development/references/backend-persistence.md)
- [Contract 验证清单](../../.agents/skills/nexusflow-feature-development/references/contracts.md)

实施前重新核对 HEAD、用户改动、实际源码与调用方。本文中“新增文件”是目标位置，不表示现有实现。

### 2.1 已确认的基线

| 事实 | 当前实现位置 | 对实施的含义 |
| --- | --- | --- |
| 普通问答先 Understanding，再 Decision，最后 Answer | `ConversationTurnWorkflow`、`ConversationUnderstandingStep`、`ConversationAnswerService` | 消除前两次独立等待，不能只改命名 |
| provider stream 先 `client.post`，再读 `bodyAsChannel` | `OpenAiCompatibleStructuredTransport.stream` | 先修正为响应作用域内增量读取 |
| Worker 等待一条 run 完成后才领取下一条 | `ResponseRunWorker.start/runOnce` | 在现有 lifecycle owner 中扩展固定数量循环 |
| 查询通过 `calls.map` 顺序执行 | `ReadToolExecutor.execute` | 独立查询做有界并发 |
| 后端已发布 Delta，App 已累计并展示 partialText | `ResponseRunRealtimeHub`、`ResponseRunStreamController`、`TaskDetailSections` | 复用现有展示协议，修复恢复边界 |
| Hub 新 attempt 重置 seq，订阅只比较 seq | `ResponseRunRealtimeHub.events/publish` | 游标必须包含 attempt |
| 请求时区仅验证，processor 默认 UTC | `ConversationService`、`ConversationTurnProcessor`、`TaskDependencies` | 本轮 context 必须持久化并恢复 |
| 规划理解结果含澄清字段，但提交后固定排队规划 | `ConversationTurnPayloadMapper`、`JdbcTaskRepository.consumePlanningUnderstandingAtomically` | 补澄清终态，不能在新链路继续丢失 |
| 结果落库后消费；新 claim 增加 attempt | `ResponseRunWorker`、`JdbcResponseRunRepository` | 不把当前恢复描述为已存结果自动重放 |

Ktor 版本基线为 `3.2.3`。普通 HttpStatement 执行会保存响应体；应使用 `preparePost(...).execute { response -> ... }` 的响应作用域执行流式读取。依据：[Ktor 3.2.3 HttpStatement 源码](https://github.com/ktorio/ktor/blob/3.2.3/ktor-client/ktor-client-core/common/src/io/ktor/client/statement/HttpStatement.kt)。必须以 NexusFlow 的真实延迟分片测试验证，不仅依赖文档或静态判断。

## 3. 倒推后的目标架构

```text
App submit
  → ConversationService 授权与校验
  → 消息 + ResponseRun 原子受理
  → ResponseRunWorker 有界领取
  → ConversationTurnWorkflow 构造本轮只读 context
  → ConversationTurnCapability 首次流式推理
      ├─ Answer：正文 delta → SSE → App；正常结束 → 最终结果
      ├─ Research：完整提议 → Backend guardrails → 并发只读查询
      │             → Answer capability（不提供操作能力）→ 正文 delta → 最终结果
      └─ Planning：完整提议 → Backend 校验
                    → 澄清消息终态 / 更新任务并进入既有 Planning stage
  → result store
  → committer 原子提交最终业务事实 + consumedAt + run terminal
  → durable completion event / snapshot
```

### 3.1 Scope Matrix

| 区域 | Change | 说明 |
| --- | --- | --- |
| Backend | YES | turn 分支、context、Worker/查询并发、终态及 SSE 修复 |
| AI | YES | 真流式 transport、正文/操作解码、新 turn capability、prompt/eval |
| contracts:backend-ai | YES | typed turn 输入/结果及增量回调边界；复用现有 proposal |
| contracts:app-backend | 默认 NO | 复用 Delta/Snapshot/Failed/Completed 与 attempt/seq；若真实行为无法表达，再报告最小变更 |
| App | 有缺口才改 | 核对真正增量显示、重试替换、断流恢复、失败临时内容；不新增业务 authority |
| observability | 默认 NO | 优先使用已有 StructuredLogger 和 trace 模型，不新增 metrics 框架 |

### 3.2 Existing Implementation Decision

| 职责 | 结论 | 原因与验证 |
| --- | --- | --- |
| HTTP 受理与结果事务 | 复用既有 service/committers | 保留现有幂等、FK、revision 保护；真实 PostgreSQL 回归 |
| 最终证据回答 | 扩展/复用现有 StreamingConversationAnswerer | 第二次调用不需要工具循环；验证引用、缺失证据及正常结束 |
| Research 去重与证据归集 | 复用 ConversationResearchCoordinator | 已表达 need→call→evidence；并发不改变映射身份 |
| 查询执行 | 扩展 ReadToolExecutor | 同一职责的执行策略变化，无需新增调度 owner |
| Worker | 扩展 ResponseRunWorker | 已拥有 scope、lease、取消和 shutdown |
| 首次推理 | 在 AI conversation 能力内新增 ConversationTurnCapability 实现 | 现有文本流/结构化决策均不能独立表达“正文或操作” |
| provider 协议 | 扩展现有 compatible transport/adapters | 原始 tool delta 是 provider 机制，不泄漏到 Backend |
| 实时状态与 UI | 扩展既有 Hub/StreamController | 修复 cursor、缺口恢复、临时正文生命周期，无第二 StateHolder |

### 3.3 Ownership

| Owner | Writable fact / decision | 生命周期 |
| --- | --- | --- |
| ConversationService + start committer | verified actor 下的受理与消息/run 原子创建 | HTTP 请求 |
| ResponseRunStore | claim、lease、attempt、retry、cancel、deadline 条件推进 | durable run |
| ResponseRunWorker | 当前实例执行循环及已领取 attempt 的资源 | Backend start/close |
| ConversationTurnWorkflow | 一次已领取 operation 的固定业务分支 | 当前 attempt |
| AI adapter/capability | provider 分片累积、协议分支、typed proposal | 单次模型调用 |
| ResearchCoordinator / ReadToolExecutor | 查询映射 / 网络执行 | 当前研究批次 |
| 既有 committers | 最终消息/计划、result consumed、终态联合提交 | DB transaction |
| RealtimeHub | 当前 run/attempt 的临时文本和有限 replay | 活跃实时状态 |
| App ResponseRunStreamController | 当前连接、cursor、临时展示与恢复 | 当前页面/会话绑定 |

committers 对 run terminal 的写入是明确的联合事务入口，不能理解为允许其他对象随意写 ResponseRun。生产流程保持 store/committer 的已授权写边界。

## 4. Protocol、context 与状态合同

### 4.1 首次推理合同

建议新 Backend/AI contract 放在 `contracts/backend-ai/src/main/kotlin/com/nexusflow/contracts/backendai/conversation/ConversationTurnContracts.kt`。

- Request：当前消息、受限近期消息、本轮 referenceTime/timeZoneId、可选 active planning、有限工具定义、必要 optional context 与诊断 metadata。
- Incremental output：仅正文 delta。内部 operation 参数分片不能流给 App。
- Terminal result：互斥 `Answer / Research / Planning`；失败通过现有 typed capability error 分类表达。
- Answer 的最终完整正文必须与本次有效 delta 的拼接语义一致；仅允许明确、测试覆盖的格式归一化。
- Research 复用信息需求与工具提议的 typed model，不使用任意 `Map<String, Any>` 表达规划域。
- Planning 复用目标/约束变化/选中 context 的业务类型。澄清与允许规划必须有明确互斥语义，不继续新增 nullable/boolean 组合。
- provider 请求 ID、usage、finish category 留在适当 metadata/adapter 边界，不进入业务决策。

Producer：AI capability；Consumer：Backend ConversationTurnWorkflow。Request producer 反向为 Backend。Backend/AI 在同一部署单元同步升级；不增加长期双路径开关。若存在其他调用方，按真实 caller 兼容，不机械删除旧接口。

### 4.2 Provider 流式协议

已有 OpenAI Responses 与 Chat compatible 模式在同一 transport 下；必须分别实现各自真实的正文、操作参数及终止事件映射。实施前核对配置中的 provider/model 对目标协议的支持，不把“兼容接口”当作全部事件都一致。

本次新调用的局部协议状态：`Undecided → Text | Operation → Finished/Failed`。它只属于单次 provider 调用，不建成数据库状态机。

1. 忽略无业务内容的 role/usage/心跳事件，首个有效正文或操作片段确定分支。
2. Text 分支立即回调正文；之后出现操作则协议失败，不执行操作、不提交半截正文。
3. Operation 分支仅累积一个操作；参数长度、操作数量及正文输出量受现有预算或最小新增边界限制。
4. 参数收齐并正常结束后，解码为 Research/Planning；再由 Backend 校验白名单、数量、字段与身份。
5. 未知操作、多操作、正文混合、空正文、JSON 截断、token limit、异常 EOF、拒绝均不能被当作完整成功。
6. 第二次 Answer 调用不暴露操作能力；不接受第三次查询循环。
7. 三个 provider adapter 按其实际配置模式提供协议测试。某 provider/model 不支持所需协议时，不静默回退到三次调用；记录兼容缺口并停止该 provider 的切换。

首次正文已经展示后再发生协议错误，无法抹除用户曾看见的内容；App 必须将它保持为失败/被替换的临时内容，不能混入历史 assistant message。这是低延迟展示的显式取舍。

### 4.3 请求 context 持久化

建议在 `response_runs` 增加本轮 `time_zone_id`，通过 create/append command 一起提交；request-local fact 不放到全局 processor 配置。

- 新请求：输入时区校验后写入，Worker 只从当前 run 读取。
- 幂等重放：复用原 run context；同一 client id 若携带不同语义输入，需按 existing/conflict 合同处理，不覆盖已有时区。
- referenceTime：语言中的“今天/明天”使用该用户消息受理时间，避免排队或重试跨午夜改变原始指代。外部资料 observedAt/有效期仍使用实际查询时间，不冻结资料新鲜度。
- 旧 run 缺少原始时区，不能恢复猜测。迁移明确回填 UTC 以延续旧语义；仅新请求保证保存真实时区。
- 使用新 Flyway migration；当前最大编号为 V012，实施时重新检查后选择下一个编号，不修改已发布 migration。
- 先新增兼容列/回填，再让新应用写入；最终约束与旧版本写入行为需一起验证。保留原 FK、UNIQUE、CHECK 与事务不变量。
- 优先采用停止接收、排空/停止旧 worker 后更新单部署单元的发布方式；若实际必须滚动升级，须先确认旧新 worker 的 claim/result 兼容，不能无依据混跑。

### 4.4 结果与失败合同

- 最终正文或规划结果复用现有 result store/committer，优先保持已有持久化 payload 类型可读。
- 若新内部模型影响存储格式，补显式版本/兼容解码方案并验证旧记录；不得仅改 serial name。
- 初次与第二次模型调用的失败都区分 retryable dependency、invalid AI result、refusal/unauthorized、internal invariant；只对可恢复类别有限重试。
- 中断后自动重试属于新 attempt，不能在已有正文后偷偷拼接另一份回答。
- 不引入额外修复模型调用来补坏掉的操作 JSON。协议错误进入明确失败；用户重试或既定有限策略单独计数。
- 当前已存未消费结果恢复可能重算，本文不承诺 exactly-once inference；最终业务提交必须保持幂等。

## 5. 切片依赖与执行计划

```text
S0 基线与合同核对
  → S1 真实流式读取
  → S2 运行与展示可靠性
  → S3 本轮 context 与澄清终态
  → S4 Turn 协议与 provider 能力
  → S5 固定分支接线并删除重复调用
  → S6 有界并发
  → S7 全链路验收与交付
```

采用顺序切片，避免同时改变调用次数、并发和流式协议而无法定位性能或正确性回归。每个切片先通过最窄有效验证，再进入下一片；S1 可独立交付，不等待全部改造。

### S0 — 固定基线与测试入口

产出：执行记录中的 base commit、scope、provider/model/协议模式、现有测试任务、基线测量表。禁止记录 key、完整 prompt、用户正文或完整 provider 输出。

动作：

- 重读当前链路与直接 caller/callee；核对旧 capability 是否被 task 非会话入口使用。
- 复用 StructuredLogger 的 operationId/runId/attempt/trace，补不足的首字/结束时间点，不引入 metrics 平台。
- 区分 `queue_wait`、`provider_first_delta`、`backend_first_delta`、`app_first_render`、research 与完整完成耗时。
- Backend 内耗时使用单调时钟；App 内端到端耗时使用本地单调时钟。未经同步校准不能直接相减客户端和服务器墙钟。
- 基线题集至少覆盖普通知识、上下文追问、实时问题、查询失败、规划创建/修改/澄清。

完成条件：有可复现测量方式；没有真实 provider 访问条件时明确缺失，不编造线上数字，不阻塞本地可验证切片。

### S1 — 修复真实流式 transport

主要文件：

- `ai/src/main/kotlin/com/nexusflow/ai/provider/compatible/OpenAiCompatibleStructuredTransport.kt`
- `ai/src/main/kotlin/com/nexusflow/ai/provider/StreamingTextModelProvider.kt`
- `ai/src/test/kotlin/com/nexusflow/ai/provider/compatible/OpenAiCompatibleStreamingTransportTest.kt`

动作：

- 在 `preparePost(...).execute {}` 作用域完成 status 校验、逐事件读取、结果构造与清理。
- 正文片段到达后立即回调；不调用完整 body/string 读取。
- 修正 accumulator 的正常完成判断：不得默认 Complete 后把任意 EOF 视作成功。
- 保留 cancellation、连接关闭、timeout 与 typed error 映射；usage-only 分片不算正文。

验证：使用真实本地 HTTP server 分两段 flush，第一段发出后由测试闸门阻止终止事件；断言在放开闸门前已经收到 delta。再验证分片跨行、末尾 usage、拒绝、截断、提前 EOF、取消后连接释放。

只返回整段 SSE 字符串的 MockEngine 测试可以验证解析，不能单独证明网络增量到达。

### S2 — Worker 与 SSE 恢复可靠性

主要文件：`ResponseRunWorker.kt`、`ResponseRunRealtimeHub.kt`、`ResponseRunService.kt`、`ConversationRoutes.kt`；对应 Backend tests；App `ResponseRunStreamController.kt` 及测试。

动作：

- 先保持一个执行循环，修复 claim/heartbeat/标记失败的基础设施异常可逃逸并永久杀死循环的问题。
- 区分 shutdown cancellation 与 dependency failure；恢复不创建第二个无 owner scope。
- start/close 幂等合同明确；不能以非 null 的已完成 Job 误判仍在运行。
- Hub 订阅游标加入 attempt；新 attempt 的 seq 从 1 开始仍可投递，旧 attempt 被拒绝。
- Hub 可变 state 的更新、序号分配和 snapshot 有一致并发保护；不能只靠 ConcurrentHashMap 包住内部可变对象。
- 慢订阅使用有界缓冲；溢出结束该订阅并触发现有重连/snapshot 恢复，不允许无限 Channel 或静默丢正文。
- App 验证 Failed/Cancelled/新 attempt 对 partialText 的处理；消息落库后用 durable 消息收敛。
- Hub 只在进程内提供实时增量。跨实例请求归属未保证时，不承诺实时；snapshot 仍从 durable state 恢复最终事实。

验证：一次 claim 失败后自动继续；heartbeat 抛异常后 attempt 可恢复；close 取消；同一 collector 高序号 attempt 1→低序号 attempt 2→Completed；慢连接溢出后 snapshot 收敛；取消后旧结果不写库。

### S3 — 请求 context 与规划澄清

主要文件：

- conversation domain `ConversationRepository.kt`，application `ConversationService.kt`、`ConversationTurnContextLoader.kt`、`ConversationTurnPayloadMapper.kt`
- conversation infrastructure `JdbcConversationTurnStartCommitter.kt`
- responserun domain `ResponseRun.kt`，infrastructure `JdbcResponseRunRepository.kt`
- task infrastructure `JdbcTaskRepository.kt`
- `backend/src/main/resources/db/migration/` 新 migration

动作：按第 4.3 节持久化和恢复时区/时间语义；移除生产提问路径对 processor 全局 UTC 默认的依赖。修复现有 PlanningUnderstanding 澄清结果消费，尽量复用现有持久化 payload，给新 turn 接线提供正确提交终态。

澄清事务：锁定并验证 run/result/task → 接受允许的需求更新 → 插入一次澄清 assistant → 标记 message understood/result consumed/run terminal → commit。若 task revision 不匹配，走明确 stale/rejection，不能先部分更新再生成问题。只有允许规划时才把同一 run 排队为 Planning。

验证：两种时区经真实 HTTP/service→DB→Worker 传递；UTC/上海跨日；旧行迁移；重复消息不覆盖 context；澄清问题恰好落库一次；clarification 不触发下一次规划；stale revision 无部分写入。

### S4 — Turn contract 与 provider 操作流

新增目标：`ConversationTurnContracts.kt`；AI conversation 包内新增 turn capability 实现，具体类名按职责选择，不增空包装层。

主要扩展：`OpenAiCompatibleStructuredTransport`、OpenAI/Qwen/DeepSeek provider adapters、`AiTaskCapabilities.kt`、AI conversation prompt/schema/tests。

动作：

- 实现第 4.1/4.2 节 typed 输入、正文回调和互斥 terminal result。
- 复用已有模型失败 taxonomy；新增协议分类仅限不能表达的真实语义。
- prompt 分为正文/Research/Planning 的明确规则；不把 plan compose/explain 的完整 prompt 复制到首轮。
- prompt 和 schema 带明确版本；必要 context 不可因 token 裁剪变成错误业务输入。
- 第二次 Answer 请求不提供工具；通过请求构造和结果校验共同限制循环。

验证：三个 provider 的实际模式分别覆盖文本、完整操作、参数分片、多个操作、未知操作、混合输出、截断、空输出、usage、拒绝；contract serialization；输入预算和无 credential 泄漏。

完成条件：新 capability 可独立测试；此片可暂不切生产装配，但不能发布一个未支持当前配置 provider 的默认路径。

### S5 — 主链路切换与旧路径清理

主要文件：`ConversationTurnWorkflow.kt`、`ConversationTurnProcessor.kt`、`ConversationUnderstandingStep.kt`、`ConversationTurnPayloadMapper.kt`、answer 包、`TaskDependencies.kt`、`AiTaskCapabilities.kt`。

动作：

- 普通提问只调用新 turn capability：Answer 直接映射现有 ConversationAnswer result；Research 经过 Backend validator/research 后调用一次 Answer；Planning 映射既有理解结果提交边界。
- first-turn Answer/普通澄清不再调用额外模型分类其语义。需要更新任务状态的澄清通过 Planning typed 提议提交。
- Research 保留信息需求校验、去重、证据身份与部分失败；不因单源不可用抛弃成功证据。
- 删除 ConversationAnswerService 内冗余 Decision 调用，把剩余 research/answer 职责留在最小现有 owner。
- 删除无真实调用方的旧 Understanding/Decision、无效构造参数和测试；如其他业务入口仍使用则保留并明确 caller，不为减少文件数强删。
- 更新日志，把“模型首轮开始/首字/分支/研究/回答/提交”连成 runId+attempt 可追踪时间线。

验证：计数型 fake provider 证明 Answer=1、Research=2；Planning 不经过问答 Answer；澄清终态；失败不提交半截正文；旧历史/result 可读取；无旧分支被隐式调用；真实模型 eval 覆盖应该查询却直接回答等语义风险。

### S6 — 有界并发

主要文件：`ResponseRunWorker.kt`、`ResponseRunWorkerConfig`、`BackendRuntimeConfig.kt`、`ResponseRunDependencyMappings.kt`、`JdbcResponseRunRepository.kt`、`ReadToolExecutor.kt` 及直接 observer/source caller。

动作：

- Worker 自有 scope 中启动固定数量循环，每个循环只领取一条，执行后再领取；明确执行循环 lease identity。
- 仅新增真实需要的并发配置，复用已有单轮查询数预算。初始并发值在本地测试/真实限流条件下确定，不承诺任意环境最优默认。
- 同会话顺序继续由 DB claim 条件、锁与状态决定；不得以 JVM mutex 作为跨进程最终 authority。
- 审核 claim 的 earlier-run 条件、lease reclaim、Planning stage 再排队、取消与超时在并发下的行为。
- ReadToolExecutor 使用结构化并发执行独立调用，结果按 call 身份归集，返回顺序稳定；预期 source unavailable 与内部错误保持区别。
- 验证共享 HttpClient、Hub/observer 和 source cache 的实际并发安全性，必要修复限定在本切片调用链。

验证：跨会话两个闸门证明同时执行；同会话后一条不能越过前一条；同一 run 不能重复 claim；claim/commit/reclaim 真实 PostgreSQL 测试；查询并发上限；去重一次执行、多 need fan-out；取消所有子查询；部分失败保留有效 evidence。

上线初始运行在一个 Backend 实例内部扩并发。增加实例前需另行验证 SSE 归属、总 provider 并发上限和跨实例恢复，不把本切片等同于水平扩容方案。

### S7 — 端到端验收与发布

组合验证真实本地 provider HTTP 分片、Backend Worker/JDBC/SSE、App 消费端：在 provider 仍被闸门阻塞时，App state 已有正文；最终提交后临时正文被 durable message 收敛。

在可用的平台做实际 UI 检查，覆盖 Android/iOS 的网络 engine 与展示。只有 controller 测试时，标明它证明 state 增量而不是屏幕已绘制。

发布顺序：新 migration → 更新同一 Backend/AI 部署单元 → 验证旧 App 的 snapshot/SSE 消费 → 必要时发布 App 修复。不要让旧新 worker 在未证明兼容的情况下共同领取新协议 run。

回滚：保留 additive migration；先停止接收/处理，识别正在执行及未消费结果，再回退到可读取现有数据的应用版本。不得通过删除新列、清空 result 或修改历史 migration 快速回滚。旧版如不能消费新结果，停止该回滚路径并形成偏差说明。

## 6. 验证命令与证据要求

当前真实模块为 `:contracts:backend-ai`、`:contracts:app-backend`，不是可以统一套用的 `:contracts:jvmTest`。

最窄 AI/contract 验证：

```bash
./gradlew :ai:test :contracts:backend-ai:test
```

Backend 最终 PostgreSQL 证据：

```bash
NEXUSFLOW_REQUIRE_POSTGRES_TESTS=true ./gradlew :backend:test
```

App/Backend contract 若实际修改：

```bash
./gradlew :contracts:app-backend:jvmTest
```

App 源码/Gradle 修改时运行：

```bash
./gradlew :app:composeApp:ktlintCheck
./gradlew :app:composeApp:tasks --all
```

从实际任务表选取承载 `commonTest` 的 Android/iOS 可用目标测试及必要编译，不虚构 desktop/jvm target。CI/目标平台不可用须记录缺失项及补验证条件。App ktlint 不作为 Backend/AI-only 切片的统一要求。

新增本地 HTTP 分片测试优先复用现有测试设施，确需 server 测试依赖时只在对应 module test scope 增加；不建立通用性能平台。

### 6.1 必须保留的反例

| 场景 | 必须观察到的结果 |
| --- | --- |
| provider 输出首段后暂停 | App 在 provider 结束前看到临时正文 |
| 输出部分正文后 EOF/超时 | 无成功 assistant 提交，run 正确失败/重试 |
| 正文后又提出操作 | 操作不执行，协议失败可见 |
| 未知工具/多操作/参数截断 | Backend 不执行外部查询或任务变更 |
| 外部资料为空或失败 | 回答承认证据缺失，不伪装实时事实 |
| 取消时 provider/tool 正在运行 | 取消传播，迟到结果不能提交 |
| DB 短暂不可用 | Worker 恢复后继续消费，不永久停摆 |
| attempt 1 序号大于 attempt 2 | 同连接仍收到新 attempt 的首字和终态 |
| 断线、慢连接、序号缺口 | snapshot 恢复，不拼接缺失/旧正文 |
| 相同 client id 重复请求 | 不重复创建业务消息/run，不覆盖原 context |
| task revision 已变化 | 不写入旧计划 |
| Planning 需要澄清 | 问题恰好落库一次，不继续规划 |
| UTC/上海跨日且有排队 | 时间指代和本轮时区保持一致 |

### 6.2 性能记录模板

| 阶段/场景 | 基线 p50/p95 | 改造后 p50/p95 | 模型调用数 | 输入/输出 token | 并发/错误率 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| 普通问答首字 | 待测 | 待测 | 3→1 目标 | 待测 | 待测 | 未验证 |
| 普通问答完整完成 | 待测 | 待测 | 同上 | 待测 | 待测 | 未验证 |
| Research 首字/完成 | 待测 | 待测 | 3→2 目标 | 待测 | 待测 | 未验证 |
| 多会话排队 | 待测 | 待测 | 不适用 | 不适用 | 待测 | 未验证 |
| Planning 首个有效结果 | 待测 | 待测 | 按分支记录 | 待测 | 待测 | 未验证 |

实际执行记录必须附样本量、模型版本、prompt 版本、输入长度、缓存条件、并发量、限流/超时比例。样本不足时不要把 p95 当稳定结论。

## 7. Human Takeover 与排障路径

| 用户现象 | 第一检查点 | 第二检查点 | 责任边界 |
| --- | --- | --- | --- |
| 已发送但一直等待 | durable run 是否 Queued/Processing，queue_wait | worker claim/heartbeat/loop 是否仍活跃 | 受理或执行生命周期 |
| 模型在生成，App 无字 | provider_first_delta 是否出现 | backend_first_delta、SSE 与 App cursor | adapter 缓冲或实时投递 |
| 已生成但没有最终消息 | result 是否 stored/consumed | committer 拒绝类别、run terminal | 事务提交 |
| 重试后拼接旧正文 | 当前 run attempt 与 App cursor | Hub replay/snapshot 与 controller 替换 | 实时状态身份 |
| 回复错日期 | durable timeZone/referenceTime | turn request 与查询参数 | context 映射 |
| 有澄清提议却不提问 | Planning typed result | 澄清事务和 assistant message | planning committer |
| 状态永不结束 | deadline/lease/attempt | worker 存活与 outstanding provider/tool | Worker/store |
| 重复/旧结果被使用 | client id、attempt、task revision | store/committer 条件检查 | 幂等与 stale rejection |

可以记录稳定 identity、阶段、耗时、计数与分类；不得记录 credential、完整 prompt/response、完整用户消息或外部 payload。

## 8. Stop Conditions、扩展条件与交付物

### 8.1 停止受影响切片的条件

- 当前 provider/model 无法表达正文或受限操作，或兼容模式与预期不同。
- 实际有必须保留的旧 capability caller，删除会改变不在范围内的行为。
- 新旧 durable result 无法兼容，或要求未设计的滚动部署。
- DB claim 并发验证发现当前顺序 invariant 不成立，需要改变 ownership/事务模型。
- App 既有协议无法表达必要的失败/恢复语义，需要公开 wire 变更。
- 必须引入跨实例总线、任意工具循环或其他本文非目标才能满足实际部署要求。

此时记录源码、冲突、影响和最小决策点，停止对应切片；其他独立验证可继续。不自行扩大架构或静默降级到旧的三次调用方案。

### 8.2 重新评估条件

- 已存结果重算成为显著成本：单独设计 result replay/claim 合同。
- Planning explain 成为显著等待：评估基础计划先交付及 enrichment 生命周期。
- 真实第二轮查询需求出现：重新定义调用预算与控制流，不偷偷增加循环。
- 多实例成为部署要求：设计实时归属/总线及全局限流。
- 已有 provider 协议差异超出现有 adapter：以真实变化原因决定是否需要新抽象。

### 8.3 实施交付清单

- [ ] S0—S7 每片执行记录、实际改动文件与必要偏差。
- [ ] 真流式延迟分片证据、App 增量到达证据。
- [ ] 正常路径一次/两次模型调用计数证据。
- [ ] PostgreSQL migration、并发、幂等、stale、取消和澄清测试。
- [ ] provider 协议与 AI eval 结果及版本。
- [ ] before/after 性能表，未测项目明确标出。
- [ ] 旧路径删除或保留 caller 的说明。
- [ ] 公开 contract、持久化兼容、部署及回滚检查结果。
- [ ] 实际 Flow/Ownership/五种 Debug Simulation；不以测试通过替代 Human Traceability 结论。

本文编写阶段只新增该 Markdown，未执行实现、产品测试或性能测量。文档验证限于 diff、引用路径和内容一致性检查。

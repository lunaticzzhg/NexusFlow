# Orbit M0 — System Foundation 阶段方案

## 1. M0 的核心目标

M0 不是做出一个功能丰富的 Orbit，而是建立后续 M1–M5 不需要反复推翻的工程语义地基。

最终目标可以压缩成一句话：

> 建立一条真实的 `App → Backend → AI → Backend Domain → App` 纵向链路，并固定 Task / Constraint / Plan 等核心概念的 ownership、状态和边界。

M0 完成后必须可以证明：

```text
用户在 App 输入一个生活目标
→ Backend 创建 authoritative Task
→ 用户消息持久化
→ AI 只做结构化理解
→ Backend 校验 AI 结果
→ Backend 更新 Constraint / TaskState
→ App 从 Backend 读取并展示最新 Task
```

同时：

```text
AI ≠ 状态机
App ≠ Domain truth
Chat Transcript ≠ Task State
```

---

# 2. 当前代码基线

M0 不应从零搭建。

| Area | 当前已有 | M0 主要缺口 |
| --- | --- | --- |
| App | KMP、Koin、Ktorfit、统一 HttpClient、Auth、Token、ApiCallExecutor、Loading/Error、Logging、Task Mock UI | Task 仍是 MockRepository；`status` 还是 String；没有真实 Task API / Constraint / Task Detail |
| Contracts | `KResponse`、Auth wire contract、serialization test | 没有 Task / Message / Constraint / Plan wire contract |
| Backend | Ktor、PostgreSQL、Flyway、Auth、ActorContext、Bearer 校验、HTTP error、CallId、日志 | 没有 Task Domain、Task persistence、Conversation、Constraint、Task API、AI integration |
| AI | `ai/` 空目录 | 没有 `:ai` module，没有 runtime，没有 structured understanding |
| Governance | AGENTS、App/Backend/AI authorities、feature development skill 已较完整 | 不需要重新建设一套开发规范 |

因此 M0 的工程策略应该是：

> **保留现有 App / Backend 基础设施，替换 Mock Task 主链路，并首次建立 Kotlin AI module。**

不重做网络层、不重做认证、不重做 DI、不拆微服务。

---

# 3. M0 Scope Matrix

| Area | Change? | M0 内容 |
| --- | --- | --- |
| App | YES | Task Remote API、DTO mapping、typed TaskState、最小 Task Detail、真实 Backend 接入 |
| Contracts | YES | 当前真实链路需要的 Task / Message / Constraint / Plan wire schema |
| Backend | YES | Task Domain、Persistence、Conversation、Constraint、Task API、状态机、AI orchestration |
| AI | YES | 新建 Kotlin/JVM `:ai` module，实现 `UnderstandUserMessage` |
| Opportunity Source | NO | 仅保留未来能力边界 |
| Calendar / Reminder | NO | 不实现真实调用 |
| Ranking / Memory | NO | 不进入 M0 |
| Approval / Execution | NO implementation | 固定语义和 ownership，不提前实现运行逻辑 |

---

# 4. 一个重要边界：Domain Language ≠ 所有模型都塞进 `:contracts`

Product Blueprint 要求 M0 固定这些语义：

```text
Task
TaskState
Constraint
ConstraintSource
ConstraintStrength
Opportunity
PlanningRun
Plan
ProposedAction
Approval
Execution
Preference
Inference
BehaviorSignal
Feedback
```

但不应该因此一次性在 `:contracts` 创建十几套未来 DTO。

应分两层。

## Canonical Domain Language

M0 固定所有上述概念的：

```text
含义
ownership
相互关系
authoritative source
```

例如：

```text
TaskState       → Backend owns
Plan            → Backend owns validated result
AI Plan         → proposal only
Approval        → Backend owns
Execution       → external result + Backend owns
Preference      → Backend owns
```

这些产品语义后续不得由 App / Backend / AI 各自重新定义。

## Executable Wire Contract

`:contracts` 只加入 M0 真实 producer / consumer 会使用的协议：

```text
Task
TaskState
TaskSummary
TaskDetail

ConversationMessage

Constraint
ConstraintSource
ConstraintStrength

PlanningRun identity
Plan
```

而：

```text
Approval
Execution
Preference
Inference
BehaviorSignal
Feedback
```

如果 M0 没有真实 API consumer，就不要提前创建完整 wire DTO。

这样既满足 Product 要求的“统一语言”，又符合当前 repository 的 Contracts 规则：

> Shared Contract 只服务真实跨边界协议，不能成为未来模型仓库。

---

# 5. Task Domain 基线

Task 是 M0 最重要的 Domain。

Backend 内建立真正的 Task model：

```text
Task
├── id
├── tenantId
├── ownerUserId
├── origin
├── originRef?
├── title
├── currentGoal
├── state
├── version
├── createdAt
└── updatedAt
```

其中：

```text
TaskState
├── Draft
├── CollectingConstraints
├── Planning
├── WaitingForApproval
├── Executing
├── NeedsAttention
├── Completed
└── Cancelled
```

M0 就实现明确的 transition rule，而不是到 M1/M2 再让各 feature 自己判断。

主路径：

```text
Draft
→ CollectingConstraints
→ Planning
→ WaitingForApproval
→ Executing
→ Completed
```

允许的主要分支：

```text
Planning
→ CollectingConstraints

WaitingForApproval
→ Planning

Executing
→ NeedsAttention

NeedsAttention
→ Executing

Active State
→ Cancelled
```

`Completed` 和 `Cancelled` 是 terminal。

不要实现 Generic StateMachine framework。

一个简单、typed、可 unit test 的 Task transition rule 足够。

---

# 6. Constraint 基线

Constraint 需要从第一天避免“所有东西都是 String”。

统一语义：

```text
Constraint
├── id
├── taskId
├── kind
├── value
├── strength
├── source
├── evidenceRef?
├── confirmedAt
└── createdAt
```

固定：

```text
ConstraintStrength
├── Hard
└── Soft
```

以及：

```text
ConstraintSource
├── UserExplicit
├── AcceptedSuggestion
├── OpportunityContext
└── SystemDerived
```

M0 建议首先支持后续 M1 最常用的小集合：

```text
TimeWindow
BudgetLimit
CommuteLimit
Location
ActivityDomain
Topic
ExperiencePreference
```

Constraint value 使用 typed payload，而不是：

```text
Map<String, Any>
```

例如时间、金额、分钟数具有各自明确结构。

AI 提取出的内容也不能自动全部成为 Confirmed Constraint。

正确链路：

```text
AI extracted candidate
→ Backend deterministic validation
→ 确认它确实来自当前用户表达
→ Backend 创建 Constraint
```

例如：

> “周六晚上想看利物浦，预算 300。”

可以产生：

```text
TimeWindow(UserExplicit, Hard/Soft)
Topic("Liverpool", UserExplicit)
BudgetLimit(300, UserExplicit)
```

而长期 Preference 或 AI 推断不能偷偷进入本次 Task。

---

# 7. Conversation 设计

MVP 已明确：

```text
Task 1 : 1 Conversation
```

M0 应坚持这个简单模型。

建议创建最小：

```text
Conversation
├── id
├── taskId
└── createdAt

Message
├── id
├── conversationId
├── role
├── content
├── clientMessageId?
├── aiRequestId?
└── createdAt
```

Conversation 不拥有 Task State。

Message 也不承担 Domain truth。

即：

```text
Message
→ interaction evidence

Constraint / TaskState
→ structured authoritative state
```

Backend 绝不能通过重新读取聊天记录，让 LLM 猜当前 Task 到哪个阶段。

---

# 8. M0 API 设计

不增加：

```text
POST /agent/run
```

这种万能接口。

使用明确产品语义。

建议 M0 API：

```text
POST /v1/tasks
GET  /v1/tasks
GET  /v1/tasks/{taskId}

POST /v1/tasks/{taskId}/messages
```

如果 M0 需要单独验证 Constraint editing，可以再提供：

```text
PUT    /v1/tasks/{taskId}/constraints/{constraintId}
DELETE /v1/tasks/{taskId}/constraints/{constraintId}
```

但不要为了“REST 完整”提前实现所有 CRUD。

核心 Command / Query 应保持：

```text
CreateTask
GetTask
ListTasks
SendTaskMessage
```

Task API 必须通过现有：

```text
Bearer token
→ ActorResolver
→ ActorContext
```

获得：

```text
tenantId
userId
scopes
```

绝对不能接受：

```text
request.userId
request.tenantId
```

作为权限事实。

---

# 9. Command identity 与简单幂等

M0 不建设通用 Idempotency Framework。

但两个最常见的重复问题值得直接解决：

## CreateTask

App 生成一个：

```text
clientRequestId
```

Backend 在 actor scope 下保证唯一。

如果：

```text
请求已经成功
→ response 丢失
→ App retry
```

Backend 返回同一个 Task，而不是再建一个。

## SendTaskMessage

请求携带：

```text
clientMessageId
```

Backend 对：

```text
taskId + clientMessageId
```

唯一。

这同时解决一个非常重要的 AI failure case：

```text
Message 已经成功持久化
→ AI timeout
→ App retry
```

retry 不会再插入一条重复消息。

Backend 可以针对同一个 Message 重新执行 understanding。

这属于具体业务幂等，不需要 Generic RetryManager。

---

# 10. Backend 方案

保持当前 Backend authority 已证明的结构：

```text
Route
→ Application Service
→ Domain / Repository Port
→ JDBC Infrastructure
```

建议增加：

```text
backend/feature/task/
├── api/
├── application/
├── domain/
└── infrastructure/
```

不要因为 M0 建立：

```text
Handler
Manager
Coordinator
Worker
Executor
Factory
Registry
```

当前 HTTP 同步链路不需要这些。

## Backend Flow Owner

M0 可以由一个明确的：

```text
TaskService
```

拥有 request-to-terminal orchestration，并暴露语义明确的方法，例如：

```text
createTask()
getTask()
listTasks()
sendMessage()
```

不是 Generic `execute()`。

## Repository

M0 不必机械拆：

```text
TaskRepository
ConversationRepository
ConstraintRepository
AuditRepository
```

如果它们始终围绕一个 Task transaction 一起修改，优先一个 Task persistence port 提供业务级 atomic operation。

例如：

```text
createTaskWithConversation()
appendUserMessage()
applyUnderstandingResult()
```

真正独立生命周期出现后再拆。

---

# 11. SendTaskMessage 的关键流程

这是整个 M0 最重要的 Flow。

```text
App
│
│ POST message
↓
TaskRoute
│
│ ActorResolver
↓
TaskService
│
├─ verify Task belongs to actor
│
├─ persist User Message
│
├─ capture task version
│
├─ create aiRequestId
│
↓
AI UnderstandUserMessage
│
↓
typed UnderstandingResult
│
↓
TaskService
│
├─ deterministic validation
├─ reject unsupported / invalid facts
├─ apply confirmed Constraints
├─ decide TaskState
├─ persist assistant message if applicable
├─ persist audit event
└─ bump Task version
      ↓
TaskDetailResponse
      ↓
App
```

AI provider IO 不能包在数据库 transaction 中。

正确事务形态：

```text
TX 1
persist user message
commit

AI call

TX 2
verify expected Task version
apply constraints
update TaskState
persist assistant message
write audit
commit
```

如果 AI 返回后 Task version 已改变：

```text
不要应用 stale result
```

而应拒绝本次旧结果。

这给 M1 的异步 Planning 也提前建立正确的 identity/version 思维。

---

# 12. AI M0 方案

当前仓库 AI runtime 是 ABSENT。

因此 M0 应正式增加：

```text
:ai
```

Gradle module。

第一版：

```text
Kotlin/JVM
```

并由 Backend 直接依赖：

```text
Backend process
    ↓
:ai library
    ↓
single LLM provider
```

不要创建：

```text
独立 Python 服务
AI microservice
Agent Runtime
multi-agent
tool router
provider registry
fallback router
```

目前没有证据证明需要。

## 唯一首发能力

```text
UnderstandUserMessage
```

输入应是 typed、immutable、最小上下文，例如：

```text
UnderstandingContext
├── taskId
├── taskVersion
├── currentGoal
├── confirmedConstraints
├── currentMessage
├── referenceTime
└── timezone
```

不要发送：

```text
全部用户历史
全部 Preference
全部聊天
全部 Task
```

## 输出

至少：

```text
UnderstandingResult
├── userIntent
├── extractedConstraints[]
├── proposedConstraintChanges[]
├── missingInformation[]
├── clarificationNeeded
├── clarificationReason?
└── assistantMessageDraft
```

AI output 是：

```text
proposal
```

不是 Backend Domain object。

Backend 必须再验证。

AI output 中禁止：

```text
taskState = Planning
approved = true
permission = granted
execution = success
```

这些都不是 AI authority。

---

# 13. AI Structured Output Failure

M0 不建设复杂 retry。

只允许一个非常明确、高 ROI 的策略：

```text
provider result
→ parse
→ schema validation

invalid
→ 最多一次 structured repair retry

still invalid
→ typed InvalidStructuredOutput failure
```

不要：

```text
Provider A
→ Provider B
→ fallback model
→ free text parser
→ heuristic extraction
→ silently fabricate result
```

失败必须真实暴露。

App 重试同一个 `clientMessageId` 时，Backend 可以重新执行该 Message 的 understanding，而不会制造重复消息。

---

# 14. Provider isolation

AI module 内部保持：

```text
UnderstandUserMessage
        ↓
Provider Adapter
        ↓
LLM
```

Provider adapter 可以知道：

```text
provider SDK
model name
raw JSON
finish reason
token usage
provider exception
```

但这些不能泄漏给 Backend Domain。

Backend / AI public boundary只看：

```text
typed result
typed failure
```

M0 只支持一个真实 provider。

不要为了以后可能更换模型建立 Registry / Factory。

具体选 OpenAI、Gemini 或其它 provider 属于后续 M0 Work Order 的 deployment/config 决定，不改变本方案的 ownership。

---

# 15. App M0 方案

现有 Task feature 已有：

```text
TaskHome
TaskCreate
MockTaskRepository
```

应直接沿着当前 feature 演进，而不是另起一个新 feature。

建议：

```text
feature/task/
├── data/
│   ├── TaskApi
│   ├── TaskRemoteDataSource
│   ├── DefaultTaskRepository
│   └── TaskMappers
├── domain/
│   ├── TaskModels
│   └── TaskRepository
└── presentation/
    ├── home/
    ├── create/
    └── detail/
```

复用现有：

```text
Ktorfit
ApiCallExecutor
Auth Header / Session refresh
AppException
AppLogger
Koin
AppErrorState
AppLoading
```

不要再创建第二套 HTTP client 或 error framework。

---

# 16. App Domain 修正

当前：

```kotlin
TaskSummary.status: String
```

应该在 M0 删除。

替换为 typed：

```text
TaskState
```

App 可以把：

```text
WaitingForApproval
```

映射成：

```text
等待你确认
```

但 Domain 不反向依赖 UI 文案。

同样：

```text
Contract DTO
→ App Domain
→ UiState
```

保持三者职责分离。

`:contracts` DTO 不直接成为 Compose `UiState`。

---

# 17. App fixture 的处理

当前 `MockTaskRepository` 可以保留作为：

```text
test fixture / debug fixture capability
```

但不能继续作为 Task feature 默认 production data source。

正式主链路必须变为：

```text
TaskRepository
→ DefaultTaskRepository
→ TaskRemoteDataSource
→ TaskApi
→ Backend
```

RoadMap 要求的“开发模式 fixture Task”可以保留，但最好作为独立：

```text
TaskFixtureProvider
```

或 debug entry。

不要增加：

```text
if (fixtureMode) ...
```

散落在 Repository / ViewModel / Screen 中。

---

# 18. M0 App UI 范围

M0 不做完整 Chat UI。

只需让真实 E2E 可观察。

现有 TaskCreate 页面可以继续作为首个用户入口：

```text
输入：
“周六晚上想看利物浦，预算 300”

Submit
→ CreateTask
→ SendTaskMessage
→ Open Task Detail
```

Task Detail 在 M0 只需要展示：

```text
Task title / goal
TaskState
confirmed Constraints
最近一条用户输入
assistantMessageDraft / clarification
debug identity（debug build only）
```

这就足够验证：

```text
自然语言
→ structured state
```

完整 Chat transcript、Plan comparison UI 留到 M1。

---

# 19. App Action 规范同步

当前 Task feature 使用：

```text
TaskHomeIntent
TaskCreateIntent
dispatch()
```

而当前 App authority 已统一使用：

```text
Action
```

M0 会真实修改 Task feature，因此建议趁本次 touched scope 将其统一成：

```text
TaskHomeAction
TaskCreateAction
TaskDetailAction
```

不需要再创建：

```text
Action → Intent → dispatch → handler
```

这种额外控制路径。

---

# 20. Backend persistence

建议 M0 增加第二个 migration，例如：

```text
V002__task_foundation.sql
```

核心表：

```text
tasks
conversations
task_messages
task_constraints
task_audit_events
```

## tasks

至少：

```text
id
tenant_id
owner_user_id
creation_request_id
origin
origin_ref
title
current_goal
state
version
created_at
updated_at
```

## conversations

```text
id
task_id UNIQUE
created_at
```

M0 保持 Task 1:1 Conversation。

## task_messages

```text
id
conversation_id
client_message_id
role
content
ai_request_id
created_at
```

并保证：

```text
conversation_id + client_message_id
```

的唯一性。

## task_constraints

```text
id
task_id
kind
value_json
strength
source
evidence_ref
confirmed_at
created_at
updated_at
```

`value_json` 可以属于 persistence representation，但进入 Domain 后必须变成 typed Constraint Value。

不要让 JSONB 直接一路传到 App/AI。

## task_audit_events

只记录安全的业务事件：

```text
id
task_id
event_type
request_id
ai_request_id?
metadata
occurred_at
```

不是 Event Sourcing。

Task 当前状态仍来自 `tasks` 等正常业务表。

---

# 21. Planning / Plan 在 M0 做到什么程度

M0 不做真实 Planning。

但必须固定：

```text
PlanningRun
Plan
```

的正式结构。

并至少准备一个 fixture Plan，通过正式 Contract 被序列化、Backend/App mapping 和展示。

这是为了确保 M1 不会重新发明：

```text
Plan = 一段 Markdown
```

Plan 从 M0 开始就是结构化对象。

至少应有：

```text
Plan
├── id
├── taskId
├── planningRunId
├── version
├── direction
├── title
├── summary
├── timeline
├── estimatedCost
├── commute
├── opportunityRefs
├── satisfiedConstraintIds
├── tradeoffs
├── reasons
├── sourceRefs
├── validUntil
└── createdAt
```

M0 不需要正式持久化 PlanningRun / Plan。

RoadMap 的数据资产也明确：

```text
M0:
Task
Message
Constraint

M1:
PlanningRun
Plan Exposure
Plan Selection
```

因此 M0 只做：

```text
正式 model
+ wire contract
+ fixture contract test
+ debug/display proof
```

不要提前建设 planning persistence。

---

# 22. Observability

M0 要建立一条以后可以真正排障的 trace。

现有 Backend 已有：

```text
X-Request-Id / CallId
```

继续复用。

Send Message Flow 至少关联：

```text
requestId
taskId
taskVersion
messageId
aiRequestId
AI capability
model
modelVersion
promptVersion
validationOutcome
```

日志不要记录：

```text
完整用户 message
完整 prompt
完整 AI response
Authorization
token
```

建议事件：

```text
task_created
task_message_persisted
ai_understanding_started
ai_understanding_validated
ai_understanding_failed
task_constraints_updated
task_state_changed
```

每个事件有明确 stage + outcome。

不要为这些事件新建 TelemetryManager / EventBus。

直接沿用现有 logger 即可。

---

# 23. AI observability

一次 AI 请求至少能回答：

```text
哪个 task？
哪个 aiRequestId？
哪个 capability？
哪个 model/version？
哪个 prompt version？
parse 是否成功？
schema 是否通过？
有没有 repair？
耗时如何？
最终 outcome 是什么？
```

structured result 可以用于测试和内部 tracing，但 production 日志默认不要 dump 原始敏感内容。

---

# 24. Test 基线

M0 的测试应该按 truth boundary 建立。

## Contracts

验证：

```text
TaskState serialization
Constraint serialization
TaskDetail serialization
Plan fixture serialization
backward compatible optional field behavior
```

使用：

```text
./gradlew :contracts:jvmTest
```

## Backend Domain

至少：

```text
合法 Task transition
非法 Task transition
terminal state 不可继续
Constraint validation
tenant / owner isolation
stale Task version rejection
duplicate clientMessageId
```

## Backend Application

`TaskServiceTest` 覆盖：

```text
CreateTask
SendMessage success
AI asks clarification
AI result → Constraint
AI failure
AI invalid structured output
stale AI result
duplicate retry
```

## Backend API

Ktor integration test：

```text
auth Actor
→ create task
→ send message
→ get task
```

并检查 HTTP failure mapping。

## AI

`:ai` 建立后：

```text
valid structured output
malformed output
one repair succeeds
repair still invalid
unsupported constraint rejected
cancellation propagation
provider failure mapping
```

模型语义测试不要做 exact sentence matching。

## App

至少覆盖：

```text
Contract → Domain mapping
Create Task success/failure
Send initial message success/failure
TaskDetail state projection
typed TaskState → UI mapping
```

并继续运行：

```text
./gradlew :app:composeApp:ktlintCheck
```

---

# 25. M0 Vertical Slices

建议按依赖推进，不按 App / Backend / AI 三个团队分别开发。

## Slice 1 — Domain Truth

建立：

```text
Task
TaskState
Constraint
ConstraintSource
ConstraintStrength
Conversation / Message semantics
Plan semantic baseline
```

以及 Task transition unit tests。

此时不接 UI、不接模型。

---

## Slice 2 — Task Persistence + API

完成：

```text
migration
TaskRepository
TaskService
TaskRoutes
ActorContext authorization
CreateTask
ListTask
GetTask
```

证明：

```text
Backend 已经拥有真实 Task truth。
```

---

## Slice 3 — App Real Task API

替换：

```text
MockTaskRepository
```

默认主链路。

完成：

```text
TaskApi
RemoteDataSource
DefaultTaskRepository
Contract mapping
TaskHome
TaskCreate
TaskDetail baseline
```

此时：

```text
App
→ real Backend
→ DB
```

已经成立。

---

## Slice 4 — AI Module

正式加入：

```text
:ai
```

实现：

```text
UnderstandUserMessage
typed input/output
single provider adapter
schema validation
single repair
AI contract tests
```

先使用 fake provider 完成 deterministic tests，再做真实 provider smoke。

---

## Slice 5 — Full Message Understanding Flow

Backend 接入：

```text
Message Persist
→ AI
→ Validate
→ Constraints
→ TaskState
→ Audit
```

完成 `SendTaskMessage`。

这是 M0 最重要的 vertical slice。

---

## Slice 6 — App E2E + Fixture Plan Contract

App 完成：

```text
输入生活目标
→ Create Task
→ Send Message
→ Task Detail
→ 显示 structured constraints
```

同时证明 fixture Plan 可以：

```text
formal contract
→ App mapping
→ debug UI/card
```

但不做真实 Planning。

---

# 26. Human Traceability

M0 完成后，一个没有参与开发的人应能按下面路径排障：

```text
TaskCreateViewModel
→ TaskRepository
→ TaskApi
→ TaskRoutes
→ TaskService
→ Task persistence
→ UnderstandUserMessage
→ structured validation
→ TaskService.applyUnderstanding
→ Task persistence
→ TaskDetailResponse
→ TaskDetailViewModel
```

关键 ownership：

```text
Flow Owner
= Backend TaskService

Task State Owner
= Backend Task Domain + persistence

Constraint writable owner
= Backend

AI reasoning owner
= :ai UnderstandUserMessage

AI provider IO owner
= AI provider adapter

App state owner
= corresponding ViewModel

Cross-boundary schema owner
= :contracts
```

Debug Boundary：

```text
1. App request emitted?
2. Backend request / Actor resolved?
3. Message persisted?
4. AI request started / returned?
5. structured validation passed?
6. Task transaction committed?
7. TaskDetail response correct?
8. App projection correct?
```

这样用户说：

> “我明明说了预算 300，但 Task 没显示。”

工程师可以快速判断问题在：

```text
App
Message persistence
AI extraction
Backend validation
Constraint persistence
Response mapping
UI projection
```

中的哪一段，而不需要读完整聊天记录猜。

---

# 27. M0 明确不做

M0 结束前禁止顺手扩张到：

```text
真实 Opportunity Source
Personal Ranking
真实 Planning
Calendar write
Reminder execution
Approval runtime
Execution runtime
Inferred Preference
Long-term Memory
vector DB
RAG
multi-agent
Agent Loop
Python AI service
microservices
workflow engine
provider registry
model fallback router
complex retry framework
event sourcing
outbox/worker
ML platform
```

尤其不要因为以后 M2 会有 Calendar，就在 M0 先建：

```text
ActionExecutor
Worker
Saga
RetryManager
```

M0 没有真实 side effect lifecycle，不需要。

---

# 28. M0 Acceptance

M0 完成时必须同时满足以下事实：

```text
App / Backend / AI 对 Task / Constraint / Plan 使用统一语义
```

```text
TaskState 只由 Backend 修改
```

```text
App 已不再依赖 MockTaskRepository 作为正式 Task 数据源
```

```text
一条真实用户消息能通过 AI structured understanding 改变 Backend Task
```

```text
AI output 可以确定性 parse + validate
```

```text
AI invalid output 不会偷偷变成 Domain truth
```

```text
Task / Message / Constraint 已真实持久化
```

```text
fixture Plan 通过正式 Plan contract 展示
```

```text
requestId + taskId + aiRequestId 可以串起整条链路
```

```text
domain tests + contracts tests + backend API integration + AI contract tests + App tests 已存在
```

以及：

```text
不存在 POST /agent/run
不存在 AI 直接 set TaskState
不存在 App 从文案推断 TaskState
不存在为了未来能力建立复杂框架
```

---

# 29. M0 → M1 Gate

只有下面四项成立后进入 M1：

```text
Domain contract stable
Task authoritative
AI structured output stable
App real Task API proven
```

这里所谓 stable 不是“不允许再改字段”。

而是：

> M1 开始实现真正 Constraint → Plan 时，不需要再讨论 Task 是什么、TaskState 谁拥有、Constraint 怎么确认、AI 是否可以改状态、Plan 是不是聊天文本。

这才是 M0 真正的价值。

---

# 30. 最终建议的 M0 系统形态

```text
┌──────────────────── App ────────────────────┐
│                                             │
│ TaskCreate / TaskHome / TaskDetail          │
│                ↓                            │
│        DefaultTaskRepository                │
│                ↓                            │
│             TaskApi                         │
└────────────────┬────────────────────────────┘
                 │
                 │ :contracts
                 ↓
┌────────────── Backend ──────────────────────┐
│                                            │
│ TaskRoutes                                 │
│     ↓                                      │
│ TaskService  ← Flow / Decision Owner       │
│     ↓                     ↓                │
│ Task Domain              :ai               │
│     ↓                     ↓                │
│ JdbcTaskRepository   UnderstandUserMessage │
│     ↓                     ↓                │
│ PostgreSQL          Provider Adapter        │
│                           ↓                │
│                          LLM               │
└────────────────────────────────────────────┘
```

最重要的是这里没有出现：

```text
Agent Runtime
Workflow Engine
Task Manager
AI Coordinator
State Synchronizer
Fallback Router
```

因为 M0 当前没有证据需要它们。

先把：

```text
Message
→ Structured Understanding
→ Constraint
→ Task State
```

这条主链路做正确。

这会是最符合当前 Product Requirements、System Blueprint、RoadMap，以及现有 NexusFlow App / Backend / AI architecture authority 的 M0。
# Orbit System Blueprint

**文档类型**：System / Domain Blueprint  
**版本**：v1.0  
**状态**：Baseline  
**上游依据**：`Orbit Product Requirements v1.0`  
**目标**：把 Product Requirements 中的产品语义翻译为统一的系统边界、领域模型、状态归属和 App / Backend / AI 协作方式，作为后续 RoadMap、System Design、API Contract、AI Design 与各阶段 Work Order 的架构基线。

---

# 0. 文档定位

`Product Requirements` 回答：

> Orbit 要解决什么问题，以及产品必须如何表现。

`System Blueprint` 回答：

> 为了长期正确地实现这些产品语义，系统应该由哪些领域组成、哪些状态由谁持有、App / Backend / AI 如何协作。

本文件**不是详细技术设计**，因此不在此阶段绑定：

- 数据库产品；
- ORM；
- HTTP / RPC 具体协议；
- 云厂商；
- LLM 供应商；
- Agent Framework；
- Vector Database；
- Event Bus；
- Cache；
- 具体部署拓扑。

这些应在真正需要时进入对应阶段的 System Design / Work Order。

原则：

> **先稳定产品语义和系统职责，再选择实现技术。**

---

# 1. 文档优先级

Orbit 后续项目文档的语义优先级：

```text
Product Requirements
        ↓
System Blueprint
        ↓
RoadMap
        ↓
Milestone System Design
        ↓
Work Order
        ↓
Implementation
```

如果实现与 Blueprint 冲突：

1. 先判断产品需求是否改变；
2. 如果产品没有改变，应修改实现；
3. 如果产品已经改变，应先更新 Product Requirements；
4. 再更新 Blueprint / RoadMap；
5. 不允许通过局部代码“事实上修改产品语义”。

---

# 2. 核心系统目标

Orbit 系统不是为了构建一个“会调用工具的 Chat Agent”。

系统要稳定支持以下闭环：

```text
External World
     ↓
Opportunity Discovery
     ↓
Opportunity Normalization
     ↓
Personal Ranking
     ↓
Opportunity Delivery
     ↓
Task
     ↓
Constraint Collection
     ↓
Planning
     ↓
Approval
     ↓
Execution
     ↓
Feedback
     ↓
Learning
     ↺
```

长期核心资产不是 Chat Transcript，而是：

```text
Opportunity Graph
        ×
Personal Taste Model
        ×
Task / Decision History
        ↓
Personal Opportunity Ranking
        ↓
Real-world Action
```

---

# 3. 总体架构

Orbit MVP 推荐采用逻辑上的三层架构：

```text
┌───────────────────────────────────────────────┐
│                    App                        │
│                                               │
│  Home / Chat / Task / Plan / Approval / Pref │
│  OS Permission / Calendar Bridge / Reminder  │
└──────────────────────┬────────────────────────┘
                       │
                       │ typed application contract
                       ↓
┌───────────────────────────────────────────────┐
│                  Backend                      │
│                                               │
│  Domain Truth                                 │
│  Task Lifecycle                               │
│  Opportunity                                  │
│  Ranking                                      │
│  Planning Orchestration                       │
│  Approval / Execution                         │
│  Profile / Feedback                           │
│  External Integrations                        │
└──────────────────────┬────────────────────────┘
                       │
                       │ constrained AI requests
                       ↓
┌───────────────────────────────────────────────┐
│                     AI                        │
│                                               │
│  Intent Understanding                         │
│  Constraint Extraction                        │
│  Clarification Decision                       │
│  Candidate Composition                        │
│  Explanation                                  │
│  Feedback Interpretation                      │
└───────────────────────────────────────────────┘
```

最核心的边界：

> **App 负责交互与展示。**  
> **Backend 负责领域事实与状态。**  
> **AI 负责理解、推理、组合与解释。**

AI 不拥有业务真相。

App 不拥有跨设备 / 跨请求的业务真相。

---

# 4. 系统设计原则

## ARCH-01：Backend Owns Domain Truth

以下状态必须以 Backend 为 authoritative source：

- Opportunity；
- Task；
- Task State；
- Constraints；
- Plans；
- Selected Plan；
- Proposed Actions；
- Approval；
- Execution；
- Preference；
- Inferred Preference；
- Behavior Signal；
- Feedback。

App 可以有本地缓存和 optimistic UI，但不能产生另一套业务语义。

---

## ARCH-02：AI Produces Proposals, Not Truth

AI 可以：

- 提取候选 Constraint；
- 建议追问；
- 生成候选 Plan 结构；
- 解释 Ranking；
- 解释方案；
- 解释反馈。

AI 不可以独立确认：

- Calendar 是否冲突；
- 价格是否真实；
- Opportunity 是否还有效；
- 位置距离是否真实；
- 权限是否已授权；
- Action 是否执行成功；
- Task 当前处于什么状态。

---

## ARCH-03：Structured State > Chat Transcript

Chat Transcript 是用户体验的一部分，但不能作为系统主要状态。

错误设计：

```text
读取历史聊天
→ LLM 猜用户当前确认了什么
→ LLM 猜 Task 到哪个阶段
```

正确方向：

```text
Task
├── Constraints
├── Planning State
├── Plans
├── Approval
└── Executions

Chat 只是这些状态变化的交互界面之一
```

---

## ARCH-04：Deterministic Verification for Real-world Facts

影响用户真实决策或外部动作的关键事实必须通过确定性逻辑 / 数据源验证。

例如：

```text
LLM：
“这个方案大概率没有时间冲突”
```

不能直接成为最终 Plan。

必须经过：

```text
Calendar availability check
→ verified
```

同理包括：

- budget；
- freshness；
- travel time；
- permission；
- availability。

---

## ARCH-05：Simple Main Flow First

每个模块优先处理：

1. 主流程；
2. 常见权限问题；
3. 常见网络问题；
4. 常见数据过期；
5. 常见部分失败。

不因为极低概率 case：

- 引入多层自动恢复；
- 无限重试；
- 多套 fallback；
- 复杂补偿状态；
- 隐藏真实失败。

所有容错设计都应考虑：

> **复杂度成本是否大于用户收益。**

---

## ARCH-06：Vertical Slice Development

RoadMap 不按：

```text
App
Backend
AI
```

拆阶段。

而按：

```text
用户可以完成的端到端行为
```

拆阶段。

例如：

> “用户可以提出周末需求并拿到可执行 Plan”

这一 Milestone 必须同时完成对应 App、Backend、AI。

---

# 5. 核心领域边界

建议逻辑上划分为 7 个核心 Domain。

```text
1. Opportunity
2. Task
3. Conversation / Constraint
4. Planning
5. Action / Execution
6. Profile / Learning
7. Delivery / Proactive
```

这些是**领域边界**，不是要求 MVP 创建 7 个微服务。

MVP 完全可以部署为一个 Backend application。

原则：

> **先模块化语义，不提前微服务化。**

---

# 6. Opportunity Domain

## 6.1 职责

Opportunity Domain 负责：

- 外部候选数据接入；
- Normalization；
- Deduplication；
- Freshness；
- Availability；
- Opportunity 生命周期；
- 向 Ranking 提供统一候选集合。

---

## 6.2 核心模型

```text
Opportunity
├── id
├── domain
├── title
├── summary
├── location
├── startsAt
├── endsAt
├── priceRange
├── source
├── sourceItemId
├── sourceUpdatedAt
├── observedAt
├── validUntil
├── availability
├── attributes
└── confidence
```

`Opportunity` 不应该包含某个用户的“喜欢程度”。

它是现实世界事实。

用户相关信息应该进入：

```text
OpportunityCandidate / RankingResult
```

而不是污染 Opportunity 本体。

---

## 6.3 Source Adapter

每一种外部来源应通过 adapter 进入统一语义：

```text
SportsSource
MovieSource
EventSource
     ↓
Source Record
     ↓
Normalizer
     ↓
Opportunity
```

UI 不直接消费 Source DTO。

Planning 也不直接消费 Source DTO。

---

## 6.4 Freshness

Opportunity 必须具有显式 freshness 生命周期。

推荐概念：

```text
Fresh
Stale
Expired
Unavailable
```

不要求一定按 Enum 实现，但系统必须能回答：

> “这个 Opportunity 当前是否仍然值得用于生成可执行方案？”

---

## 6.5 Opportunity 生命周期

```text
Observed
   ↓
Normalized
   ↓
Eligible
   ↓
Ranked
   ↓
Displayed
   ↓
Planned
   ↓
Expired / Completed
```

这里不建议建设一个重量级 Opportunity State Machine。

Opportunity 本身的 lifecycle 以：

- 时间；
- availability；
- source freshness；

驱动即可。

---

# 7. Personal Ranking Domain

Ranking 是 Orbit 最核心的差异化能力。

## 7.1 输入

```text
Opportunity
+
Current Time
+
User Explicit Preferences
+
Inferred Preferences
+
Behavior Signals
+
Calendar Availability
+
Location Context
+
Task Intent（如果存在）
+
Recent Exposure / Silence
```

---

## 7.2 输出

建议逻辑模型：

```text
RankedOpportunity
├── opportunityId
├── score
├── eligibility
├── reasons
│   ├── whyNow
│   └── whyYou
├── matchedPreferences
├── penalties
├── confidence
└── deliveryLevel
```

`deliveryLevel` 可表达：

```text
Hidden
Browse
HomeCandidate
Featured
NotificationEligible
```

具体枚举后续设计。

---

## 7.3 两阶段 Ranking

MVP 推荐避免“一次模型直接输出最终推荐”。

逻辑上采用：

```text
Phase 1 — Eligibility / Filtering
        ↓
Phase 2 — Personal Ranking
```

### Phase 1：硬过滤

确定性系统优先过滤：

- expired；
- unavailable；
- 明确时间不可能；
- 明确硬预算不符合；
- 用户明确屏蔽类别；
- 本周静默。

### Phase 2：软排序

综合：

- interest；
- time fit；
- commute；
- budget；
- novelty；
- freshness；
- historical acceptance；
- confidence。

---

## 7.4 AI 在 Ranking 中的位置

MVP 不应让 LLM 成为唯一 Ranking Engine。

更合理：

```text
Deterministic features
        +
Product-defined scoring
        +
Optional AI semantic feature
        ↓
Ranking
```

AI 可以帮助：

- semantic interest match；
- explain why now；
- explain why you。

但：

> 最终 eligibility 与重要 hard constraint 不交给自由文本 reasoning。

---

# 8. Task Domain

Task 是 Orbit 的中心聚合对象。

## 8.1 定义

Task 表示：

> Orbit 正在为用户推进的一件生活目标。

任何需要跨多个步骤持续推进的行为，都应该属于一个 Task。

---

## 8.2 Task 来源

```text
UserInitiated
OpportunityInitiated
Regenerated
SystemInitiated（未来）
```

---

## 8.3 Task Aggregate

逻辑上：

```text
Task
├── id
├── userId
├── origin
├── originRef
├── title
├── state
├── currentGoal
├── constraints
├── plans
├── selectedPlanId
├── approval
├── executions
├── createdAt
└── updatedAt
```

不意味着这些必须存一张表。

---

# 9. Task State Machine

PRD 已定义：

```text
Draft
CollectingConstraints
Planning
WaitingForApproval
Executing
NeedsAttention
Completed
Cancelled
```

Blueprint 将其解释为系统级状态。

---

## 9.1 正常主流程

```text
Draft
  ↓
CollectingConstraints
  ↓
Planning
  ↓
WaitingForApproval
  ↓
Executing
  ↓
Completed
```

---

## 9.2 常规分支

### 缺少必要信息

```text
Planning
  ↓
CollectingConstraints
```

### 数据过期，需要重新规划

```text
WaitingForApproval
  ↓
Planning
```

### 执行需要用户处理

```text
Executing
  ↓
NeedsAttention
```

### 用户处理后继续

```text
NeedsAttention
  ↓
Executing
```

### 用户放弃

```text
Any Active State
  ↓
Cancelled
```

---

## 9.3 状态修改规则

只有 Backend Domain Application Service 能提交 Task State Transition。

App：

```text
request action
```

而不是：

```text
set state = Completed
```

AI：

```text
propose next step
```

而不是：

```text
set state = Planning
```

---

## 9.4 State 与 UI 文案分离

例如：

```text
WaitingForApproval
```

App 可以显示：

- 等待你确认
- 需要你处理
- 方案待审批

但 Backend 不通过 UI 文案理解状态。

---

# 10. Conversation / Constraint Domain

Conversation 的真实目标是构建 Task Context。

## 10.1 三层信息

每个 Task 必须区分：

```text
A. Confirmed Constraints
B. Candidate Constraints
C. Profile Suggestions
```

---

## 10.2 Confirmed Constraint

用户已明确表达 / 确认，当前 Task 可直接使用。

```text
Constraint
├── type
├── value
├── strength
│   ├── Hard
│   └── Soft
├── source
├── evidenceRef
└── confirmedAt
```

---

## 10.3 Candidate Constraint

AI 从用户语言中提取，但系统尚不应该把它当成确定事实。

例如用户说：

> “不要太远。”

AI 可以提出：

```text
Candidate:
commutePreference = low
```

如果产品语义允许它作为当前 soft constraint，可以直接落地为明确标记的 `UserExpressedSoft`。

如果歧义足以显著改变结果，则追问。

---

## 10.4 Profile Suggestion

例如：

> 你通常选择 ¥300 左右的方案，要沿用吗？

它来自 Profile，不属于当前 Task，直到用户接受。

---

# 11. AI Conversation Pipeline

推荐逻辑：

```text
User Message
      ↓
Message Persisted
      ↓
AI Understanding Request
      ↓
Structured Understanding
      ↓
Backend Validation
      ↓
Update Task Constraints
      ↓
Decide:
   ├─ ask clarification
   └─ ready for planning
```

---

## 11.1 AI Structured Output

AI 不应直接返回一大段自由文本让 Backend 解析。

至少逻辑上输出：

```text
UnderstandingResult
├── userIntent
├── extractedConstraints[]
├── proposedConstraintChanges[]
├── missingInformation[]
├── clarificationNeeded
├── clarificationReason
└── assistantMessageDraft
```

最终 schema 在 AI Design 中确定。

---

## 11.2 Clarification Rule

是否追问不能简单等同于：

```text
字段不完整 → 追问
```

而应该判断：

> 缺失信息是否会显著改变候选方案或可执行性？

例如：

“周末想看电影。”

如果已经能根据：

- 用户位置；
- Calendar；
- Preference；

生成 3 个有区分度的候选方案，就无需先追问预算。

---

# 12. Planning Domain

Planning 把：

```text
Task Goal
+
Confirmed Constraints
+
Candidate Opportunities
+
Verified Context
```

转换成：

```text
Executable Candidate Plans
```

---

## 12.1 Planning Pipeline

```text
Task
 ↓
Planning Request
 ↓
Resolve Required Context
 ├─ Opportunity
 ├─ Calendar
 ├─ Commute
 ├─ Budget
 └─ Profile
 ↓
Candidate Generation
 ↓
AI Composition
 ↓
Deterministic Validation
 ↓
Plan Ranking
 ↓
Persist Plans
 ↓
Task → WaitingForApproval
```

---

## 12.2 为什么先生成再校验

AI 擅长组合：

> 比赛 + 晚餐 + 出发时间

但系统必须验证：

```text
时间是否真的成立？
通勤是否真的成立？
活动是否仍然有效？
预算是否超过 Hard Constraint？
```

因此：

```text
AI Plan Proposal
≠
Final Plan
```

必须经过 validation。

---

# 13. Plan Model

建议逻辑模型：

```text
Plan
├── id
├── taskId
├── version
├── direction
├── title
├── summary
├── timeline[]
├── estimatedCost
├── commute
├── opportunityRefs[]
├── satisfiedConstraints[]
├── tradeoffs[]
├── reasons[]
├── facts[]
├── validUntil
├── validationStatus
├── proposedActions[]
└── createdAt
```

---

## 13.1 Plan Version

重新生成不能覆盖历史选择语义。

推荐：

```text
Task
 ├─ PlanningRun #1
 │    ├─ Plan A
 │    ├─ Plan B
 │    └─ Plan C
 │
 └─ PlanningRun #2
      ├─ Plan A
      ├─ Plan B
      └─ Plan C
```

MVP 实现可以更简单，但至少必须能知道：

> 用户选择的是哪一次生成中的哪个 Plan。

这对 Learning 很重要。

---

## 13.2 Plan 不是 Chat Message

聊天可以展示 Plan 卡片。

但 Plan 的权威数据来自 Plan Model，而不是消息正文。

---

# 14. Approval Domain

Approval 是：

> 用户对 Proposed Actions 的明确授权记录。

---

## 14.1 Approval Flow

```text
Selected Plan
      ↓
Build Proposed Actions
      ↓
Verify current plan freshness
      ↓
Present Approval
      ↓
User edits / disables actions
      ↓
Approve
      ↓
Approval Snapshot
      ↓
Execution
```

---

## 14.2 Approval Snapshot

批准后必须冻结：

- 用户批准了哪些 Action；
- Action 参数；
- Plan version；
- approval timestamp。

执行不能偷偷使用批准后被 AI 改写的新参数。

---

## 14.3 Approval 的粒度

Approval 应按：

```text
Action
```

而不是：

```text
Task
```

例如：

```text
CalendarEvent = approved
Reminder = approved
```

之后才能准确表达部分成功。

---

# 15. Action / Execution Domain

## 15.1 ProposedAction

逻辑模型：

```text
ProposedAction
├── id
├── type
├── parameters
├── requiredPermission
├── validation
└── enabled
```

---

## 15.2 Execution

```text
Execution
├── id
├── actionId
├── status
├── startedAt
├── completedAt
├── result
├── failure
└── retryability
```

---

## 15.3 Execution Status

保持简单：

```text
Pending
Running
Succeeded
Failed
```

如果确实需要：

```text
Cancelled
```

再增加。

不要提前建设复杂 orchestration 状态树。

---

## 15.4 部分成功

Task execution result 从各 Action 聚合：

```text
Calendar → Failed
Reminder → Succeeded
```

Task 可进入：

```text
NeedsAttention
```

直到：

- 用户处理；
- 重试成功；
- 用户接受部分完成。

---

## 15.5 Retry 原则

自动重试只适用于：

- 明确 transient；
- 幂等；
- 不产生重复副作用。

例如网络 timeout 可以有有限重试。

以下情况不应该自动死循环：

- Permission denied；
- Invalid input；
- Plan expired；
- User action required。

---

# 16. App Architecture Responsibility

App 负责：

```text
Presentation
Interaction
Local OS integration
Permission UX
Local Calendar bridge（如采用端侧集成）
Notification UX
Optimistic interaction
Local cache
```

---

## 16.1 App 不负责

App 不应：

- 自己决定 Task state；
- 从 chat 文本重建 Constraints；
- 自己 Ranking Opportunity；
- 自己判断 Plan 是否有效；
- 自己推断 Execution 成功；
- 把 Preference 隐式塞进 Task；
- 维护 Backend 之外的第二套产品规则。

---

## 16.2 Screen → Domain Mapping

```text
Home
→ RankedOpportunity
→ Task Summary
→ Upcoming Plan / Execution

Chat
→ Task
→ Constraints
→ Conversation

Plan
→ PlanningRun
→ Plan

Approval Sheet
→ ProposedAction
→ Approval

Tasks
→ Task State

Preferences
→ Preference
→ Inference
→ Evidence
→ Permission
```

---

## 16.3 UI State

UI 可以有纯展示状态：

```text
Loading
Refreshing
Expanded
InputDraft
SelectedTab
SheetOpen
```

这些属于 App。

而：

```text
Planning
WaitingForApproval
Completed
```

属于 Domain。

---

# 17. Backend Architecture Responsibility

Backend 是 Orbit 的 application brain，但不是“LLM wrapper”。

职责：

```text
Domain persistence
State transition
Use-case orchestration
Permission truth synchronization
Opportunity normalization
Ranking
Planning orchestration
Validation
Approval
Execution
Profile / feedback
Audit / observability
```

---

## 17.1 Application Use Cases

推荐以明确 Use Case 表达系统行为，例如：

```text
CreateTask
SendTaskMessage
AcceptConstraintSuggestion
RemoveConstraint
GeneratePlans
SelectPlan
PrepareApproval
ApproveActions
ExecuteApprovedActions
RecordFeedback
DismissOpportunity
SilenceRecommendations
```

这比暴露：

```text
POST /agent/run
```

更符合 Orbit 产品语义。

具体 API 路径后续再设计。

---

# 18. AI Architecture Responsibility

AI 层应该提供明确、有限的能力。

建议划分：

```text
1. UnderstandUserMessage
2. DecideClarification
3. ComposePlans
4. ExplainOpportunity
5. ExplainPlan
6. InterpretFeedback
```

早期不需要为了这些能力建立很多“Agent”。

---

## 18.1 不推荐 MVP 一开始采用

```text
Planner Agent
Calendar Agent
Memory Agent
Ranking Agent
Critic Agent
Executor Agent
Supervisor Agent
```

除非真实复杂度证明需要。

默认优先：

```text
one orchestrated AI layer
+
typed tools / context
+
structured outputs
```

---

## 18.2 AI Context 应最小化

每次 AI 请求只提供当前 Use Case 必要的信息。

错误：

```text
整个用户历史
+
全部聊天
+
全部画像
+
全部机会
```

正确：

```text
Current Task
Relevant Constraints
Relevant Preferences
Candidate Opportunities
Verified Context
```

降低：

- token 成本；
- hallucination；
- context conflict；
- 隐式行为。

---

# 19. Profile / Learning Domain

## 19.1 分层模型

```text
ExplicitPreference

InferredPreference
  └─ Evidence[]

BehaviorSignal

Feedback
```

不能都存成同一种：

```text
Memory
```

否则产品语义会逐渐不可控。

---

## 19.2 Explicit Preference

权重最高。

必须来自：

- 用户明确设置；
- 用户明确确认某个 candidate。

---

## 19.3 Inferred Preference

必须包含：

```text
Inference
├── proposition
├── confidence
├── evidence[]
├── status
└── updatedAt
```

用户删除：

```text
status = Removed
```

或等价语义。

系统必须停止使用。

---

## 19.4 Behavior Signal

例如：

```text
SelectedPlan
DismissedOpportunity
OpenedOpportunity
RegeneratedPlan
ApprovedAction
CompletedActivity
```

Signal 是 Learning 原料，不直接等于 Preference。

---

## 19.5 Feedback

用户明确反馈可以比被动 Signal 权重高。

但仍需要区分：

```text
Task-local feedback
```

和：

```text
long-term preference
```

例如：

> “这次太远了。”

不一定等于：

> “以后永远不要超过 20 分钟。”

---

# 20. Learning Pipeline

MVP 推荐：

```text
User Action
     ↓
Record Behavior Signal
     ↓
Update ranking features
     ↓
Evidence aggregation
     ↓
Potential Inference
     ↓
If confidence sufficient:
show candidate to user when appropriate
     ↓
Explicit Preference
```

早期不必做在线 ML。

可以从：

```text
rules
+
weighted features
+
simple evidence aggregation
```

开始。

核心是先产生正确的数据语义。

---

# 21. Calendar / Permission Integration

Calendar 同时影响：

```text
Planning Context
```

和：

```text
Execution Target
```

必须区分读和写。

---

## 21.1 Calendar Read

用于：

- availability；
- conflict；
- free window。

没有读权限：

- 不阻塞所有 Planning；
- 但 Plan 需要明确降低 confidence / 要求用户确认时间。

---

## 21.2 Calendar Write

属于 ProposedAction。

必须：

```text
Approval
→ Execute
```

不能因为用户授予 Calendar permission 就默认获得行为授权。

---

## 21.3 OS Permission 与 Product Approval 区分

非常重要：

```text
OS Permission
≠
User Approval
```

Calendar 已授权：

> App 有技术能力写入。

用户批准某个 Action：

> 用户允许 Orbit 这一次写入。

两者必须同时成立。

---

# 22. Reminder / Notification

Reminder 与主动 Opportunity Notification 是两个领域概念。

### Reminder

用户已经选择一个计划后：

> 活动前 60 分钟提醒我。

属于 Action / Execution。

### Opportunity Notification

Orbit 主动打扰：

> 有一件事情值得你现在关注。

属于 Delivery / Proactive。

二者不能混在一个 notification rule 中。

---

# 23. Proactive Delivery Domain

输入：

```text
RankedOpportunity
+
User notification preference
+
Silence state
+
Recent notification history
+
Urgency
+
Confidence
```

输出：

```text
DoNotDeliver
HomeOnly
FeaturedHome
Notify
```

---

## 23.1 Notification Gate

主动 Notification 之前应有一个明确 Gate：

```text
Is relevant enough?
Is timely enough?
Is confidence high enough?
Has user been over-notified?
Is user silenced?
```

AI 可以辅助解释，不应该自由决定是否 push。

---

# 24. Freshness / Validation Architecture

系统至少需要三个不同时间概念：

```text
sourceUpdatedAt
observedAt
validUntil
```

含义：

### sourceUpdatedAt

外部来源声称最后更新时间。

### observedAt

Orbit 最近一次获取 / 验证时间。

### validUntil

Orbit 认为这份数据可用于用户行动决策的最晚时间。

---

## 24.1 Validation Points

至少在以下节点检查 freshness：

```text
Opportunity Display
Plan Generation
Approval
Execution
```

严格程度逐步上升。

例如：

Home 展示：

> 可以容忍轻度旧数据。

Execution：

> 必须使用足够新、足够确定的数据。

---

# 25. Data Ownership Matrix

| Data / State | App | Backend | AI | External Source |
|---|---|---|---|---|
| Opportunity事实 | Read | **Own normalized truth** | Read | Raw truth source |
| Ranking结果 | Read | **Own** | Assist | - |
| Task state | Read | **Own** | Suggest | - |
| Constraint | Edit via command | **Own** | Extract / suggest | - |
| Chat message | Create/read | **Persist** | Consume/generate | - |
| Plan | Read/select | **Own validated plan** | Propose/compose | factual inputs |
| Approval | Create | **Own** | No authority | - |
| Execution | Display | **Own** | No authority | Action target |
| Calendar truth | Present/cache | Normalize/use | Read context only | OS / Calendar |
| Preference | Edit | **Own** | Interpret | - |
| Inference | Display/remove | **Own** | Assist generation | - |
| Behavior Signal | Emit interaction | **Own** | Optional analysis | - |
| Permission | Request/display | synchronize relevant truth | Read | OS |

---

# 26. Command / Query Boundary

Orbit 不要求完整 CQRS。

但产品语义上建议区分：

### Query

```text
GetHome
GetTask
GetPlans
GetPreferences
```

### Command

```text
CreateTask
SendMessage
RemoveConstraint
GeneratePlans
SelectPlan
ApproveActions
RecordFeedback
```

这样 App 不会通过：

```text
PATCH arbitrary object
```

直接破坏领域规则。

---

# 27. Idempotency / Side Effect Safety

对外部副作用：

- Calendar create；
- Reminder create；

必须支持逻辑上的幂等保护。

例如：

```text
Approve button double tap
network retry
app reconnect
```

都不能创建两次同一 Calendar Event。

具体 idempotency key 方案后续设计。

---

# 28. Concurrency / Versioning

MVP 不需要复杂 distributed concurrency。

但必须避免：

```text
旧 Plan
+
新 Approval
```

错误组合。

最低要求：

- Plan 有 version / identity；
- Approval 引用具体 Plan；
- Execution 引用具体 Approval；
- Plan 失效后旧 Approval 不可直接执行。

---

# 29. Event / Audit Trail

Orbit 是 Agent 产品，用户信任要求系统能回答：

> Orbit 做了什么？

因此关键行为应该保留可审计事件语义。

例如：

```text
TaskCreated
ConstraintConfirmed
PlansGenerated
PlanSelected
ApprovalGranted
ActionSucceeded
ActionFailed
FeedbackRecorded
InferenceRemoved
```

不要求第一阶段就部署 Event Sourcing。

可以：

> 正常数据库状态 + 关键 audit/event log。

---

# 30. Observability

至少需要关联：

```text
user/session
taskId
planningRunId
planId
approvalId
executionId
AI request id
external source request id
```

目标是当用户说：

> “为什么这个任务显示完成，但日历没写进去？”

工程侧能快速追踪整个链路。

---

## 30.1 AI Observability

记录：

- AI capability；
- model/version；
- prompt/template version；
- structured result；
- validation result；
- latency；
- token / cost（适用时）；
- parse failure。

不需要默认记录不必要的完整敏感上下文。

---

# 31. Analytics / Product Events

产品事件必须围绕核心 Funnel，而不是页面 PV。

建议统一：

```text
OpportunityImpression
OpportunityOpened
OpportunityDismissed
PlanningStarted
ConstraintConfirmed
PlansGenerated
PlanSelected
ApprovalViewed
ApprovalGranted
ActionExecuted
TaskCompleted
FeedbackSubmitted
InferenceRemoved
```

后续可直接计算 PRD 指标：

```text
Opportunity → Planning
Planning → Selection
Selection → Approval
Approval → Execution
Opportunity → Real-world Action
```

---

# 32. Testing Strategy

测试按系统真相层次划分。

## 32.1 Domain Unit Test

重点：

- Task transition；
- Constraint rule；
- Ranking filter；
- Plan validation；
- Approval rule；
- Execution aggregation；
- Memory promotion rule。

---

## 32.2 AI Contract Test

不要测试：

> 模型必须生成完全一致的句子。

而测试：

- schema valid；
- constraint extraction 基本正确；
- unsupported facts 不被接受；
- required fields；
- clarification decision；
- candidate diversity；
- validator 能拦截错误 plan。

---

## 32.3 Integration Test

重点：

```text
Backend + AI
Backend + Opportunity Source
Backend + Calendar
Backend + Reminder
```

---

## 32.4 End-to-End Vertical Slice

RoadMap 每个 Milestone 必须至少有一个完整 E2E。

例如：

```text
用户说周末想看比赛
→ Constraint
→ Plan
→ Approval
→ Calendar
→ Result
```

---

# 33. Failure Design

Orbit 不使用“无限 fallback 架构”。

统一原则：

```text
Detect
→ Surface correct state
→ Retry when high-ROI
→ Ask user when necessary
```

---

## 33.1 AI Failure

例如 structured output invalid：

```text
有限重试 / repair
→ 仍失败
→ Task 保持可恢复状态
→ 明确失败
```

不要 silently fabricate result。

---

## 33.2 External Source Failure

```text
已缓存且仍有效
→ 可继续

数据已 stale
→ 明确不可验证

关键数据缺失
→ 不进入可执行 Plan
```

---

## 33.3 Execution Failure

不通过重新问 LLM：

> “你觉得成功了吗？”

真实 connector / OS result 才是 Execution truth。

---

# 34. Security / Privacy Baseline

MVP 至少坚持：

- 最小权限；
- permission 与 approval 分离；
- 用户可删除重要 inference；
- AI context 最小化；
- 外部 Action 必须有明确 authorization；
- 不把不必要的用户数据发送给第三方 source；
- audit 中避免保存无必要敏感数据。

详细安全规范后续另行设计。

---

# 35. MVP 推荐部署形态

Blueprint 不绑定技术栈，但**逻辑上推荐 MVP 保持简单**：

```text
Mobile App
    ↓
Single Backend Application
    ├── Opportunity module
    ├── Task module
    ├── Planning module
    ├── Action module
    ├── Profile module
    └── AI adapter
         ↓
       LLM
```

外部：

```text
Opportunity Sources
Calendar
Notification / Reminder
```

当前没有充分理由把：

- Ranking；
- Memory；
- Planning；
- Execution；

拆成独立微服务。

---

# 36. 推荐代码边界

无论具体技术栈如何，都建议保持：

```text
domain/
application/
infrastructure/
api/
```

或者等价分层。

核心原则不是目录名字，而是：

```text
Domain
不能依赖具体 LLM SDK
不能依赖具体 HTTP framework
不能依赖具体 database DTO
```

例如：

```text
Task
Plan
Constraint
Approval
Execution
```

应该是领域概念，而不是某个 API response。

---

# 37. AI Adapter Boundary

AI Provider 必须被隔离在 adapter 后面。

例如逻辑能力：

```text
AiGateway
├── understandTaskMessage()
├── composePlans()
├── explainOpportunity()
└── interpretFeedback()
```

业务层依赖：

```text
capability
```

而不是：

```text
OpenAI/Anthropic/Gemini SDK API shape
```

但 MVP 不要求为了“可替换模型”做复杂 abstraction。

只需要避免 LLM SDK 泄漏进所有领域模块。

---

# 38. External Source Boundary

同样：

```text
OpportunitySource
CalendarGateway
ReminderGateway
```

作为能力边界。

领域不应该知道：

```text
某供应商 JSON 字段名
某平台错误码
```

Infrastructure 负责转换成 Orbit 语义。

---

# 39. Home Read Model

Home 是跨 Domain 聚合页面，因此 Backend 可以提供专门 Read Model：

```text
Home
├── needsAttentionTasks[]
├── featuredOpportunity
├── upcomingPlans[]
└── discoveredOpportunities[]
```

它不是新的 Domain Entity。

这是重要区别：

> **读模型可以为 UI 组合，领域对象不要为了 UI 组合而互相污染。**

---

# 40. Task Detail Read Model

类似：

```text
TaskDetail
├── task
├── constraints
├── currentProgress
├── latestPlans
├── approval
├── executions
└── timeline
```

App 不应该自己从多个 API 拼接出“当前 Task 到哪一步”并推断语义。

---

# 41. Chat 与 Task 的关系

一个 Task 可以拥有一个 Conversation。

MVP 推荐：

```text
Task 1 : 1 Conversation
```

避免早期支持：

- 一个 Conversation 操作很多并行 Task；
- 一个 Task 横跨很多独立 Conversation；
- 多 Agent Thread。

未来有真实需要再扩展。

这能让：

```text
Chat Context
Task Context
```

保持一致。

---

# 42. Opportunity → Task 关系

用户从 Opportunity 点击：

> 围绕它聊聊

应：

```text
Create Task
origin = Opportunity
originRef = opportunityId
```

然后把相关信息作为：

```text
OpportunityContext
```

进入 Task。

不是把 Opportunity 文案复制进 Chat 后丢失结构关系。

---

# 43. Task → Opportunity 关系

用户直接说：

> “周末想看电影。”

这时 Task 先存在。

Planning 根据 Task Intent 去检索 Opportunity。

所以两条路径都成立：

```text
Opportunity → Task
```

以及：

```text
Task → Opportunity Search
```

这是系统必须支持的双向关系。

---

# 44. Ranking 与 Planning 的关系

它们不是一回事。

### Ranking

回答：

> 哪些 Opportunity 值得用户关注？

### Planning

回答：

> 基于用户当前目标，怎样把一个或多个 Opportunity 组合成可执行方案？

因此：

```text
Ranking
≠
Planning
```

但 Planning 可以复用 Ranking features。

---

# 45. Ranking 与 Memory 的关系

Memory 不直接驱动 UI。

正确：

```text
Preference / Signal
      ↓
Ranking Features
      ↓
Ranked Opportunity
      ↓
UI
```

错误：

```text
Preference
      ↓
App 自己加标签 / 过滤
```

---

# 46. 推荐解释 Architecture

`Why now / Why you` 应基于真实 Ranking evidence。

例如内部结构：

```text
ReasonEvidence
├── type = CalendarFit
├── fact = "Saturday evening free"
└── weight = high
```

AI 根据 evidence 生成：

> “周六晚上你的日历是空的。”

AI 不应该自由创造推荐理由。

---

# 47. Plan Explainability

Plan explanation 同样基于验证后事实：

```text
Verified Facts
+
Tradeoffs
+
Constraint Satisfaction
        ↓
AI wording
```

这样避免：

> AI 文案与 Plan 真实结构不一致。

---

# 48. MVP Memory 不建议采用“无限记忆”

不建议：

```text
自动把所有聊天 embedding
→ vector DB
→ 每次全部 retrieval
```

作为 Memory 主设计。

原因：

- 来源边界不清；
- 用户控制困难；
- Task-local 与 Long-term 混淆；
- 难以解释；
- 难以测试。

MVP 优先：

```text
Explicit structured preferences
+
Evidence-based inference
+
Behavior events
```

自由文本记忆以后有明确收益再增加。

---

# 49. MVP Ranking 不建议先做 ML Platform

第一版 Personal Ranking 可以：

```text
Eligibility Rules
+
Weighted Features
+
Simple personalization
+
AI semantic assist
```

重点是：

- features 正确；
- feedback 数据正确；
- metric 正确。

有真实数据后再决定：

- learning-to-rank；
- embeddings；
- recommender model；
- bandit。

---

# 50. MVP Planning 不建议做 General-purpose Agent

Planning 应限制在 Orbit 的生活计划 domain：

```text
read task
read opportunity
read calendar
calculate commute
compose plans
validate
```

不需要让模型拥有任意浏览器和任意工具执行能力。

更窄：

> 更可靠、更便宜、更容易测试。

---

# 51. 核心 E2E Sequence

## 51.1 用户主动规划

```text
App
│
│ Send Message
↓
Backend
│ Create/Update Task
│
├──→ AI: Understand
│      ↓
│   Structured Result
│
│ Validate / Confirm Constraints
│
├── if missing → App clarification
│
└── if ready
       ↓
   Opportunity Search
       ↓
   Verified Context
       ↓
   AI Compose Plans
       ↓
   Plan Validator
       ↓
   Persist Plans
       ↓
App Plan A/B/C
```

---

## 51.2 Opportunity 主动入口

```text
External Source
      ↓
Opportunity
      ↓
Personal Ranking
      ↓
Home Featured
      ↓
User Opens
      ↓
Why now / Why you
      ↓
Create Task(origin=Opportunity)
      ↓
Conversation / Planning
```

---

## 51.3 执行

```text
User selects Plan
      ↓
Backend verifies validity
      ↓
Proposed Actions
      ↓
App Approval UI
      ↓
User approves
      ↓
Approval Snapshot
      ↓
Execution
   ├─ Calendar
   └─ Reminder
      ↓
Action-level results
      ↓
Task state
      ↓
App Result
```

---

## 51.4 学习

```text
Opportunity opened
Plan selected
Action approved
Feedback submitted
      ↓
Behavior Signal
      ↓
Profile Learning
      ↓
Ranking Features
      ↓
Next Opportunity Ranking
```

---

# 52. RoadMap Implications

基于该 Blueprint，RoadMap 应按以下能力顺序建设。

## M0 — System Foundation

建立：

- domain language；
- Task state；
- contracts；
- backend baseline；
- AI structured output；
- test fixtures；
- observability；
- development rules。

---

## M1 — First Planning Loop

完成：

```text
Chat
→ Constraint
→ Opportunity fixture
→ Plan A/B/C
```

验证 Conversation + Planning。

---

## M2 — Action Loop

完成：

```text
Plan
→ Approval
→ Calendar / Reminder
→ Result
```

Orbit 第一次拥有真实 Agent execution。

---

## M3 — Opportunity Engine

完成：

```text
External Data
→ Opportunity
→ Ranking
→ Home
→ Why now / Why you
→ Task
```

开始建立真正产品差异。

---

## M4 — Learning Loop

完成：

```text
Behavior
→ Feedback
→ Inference
→ Ranking improvement
```

闭合数据飞轮。

---

## M5 — Proactive & Reliability

完成：

- proactive delivery；
- notification gate；
- freshness refresh；
- common retry；
- analytics；
- source expansion；
- reliability hardening。

最终 RoadMap 仍应单独成文，本节只定义架构依赖顺序。

---

# 53. M0 必须先固定的 Contract

进入大量功能开发前，至少应稳定以下语义：

```text
Task
TaskState
Constraint
ConstraintSource
ConstraintStrength
Opportunity
Plan
PlanningRun
ProposedAction
Approval
Execution
Preference
Inference
BehaviorSignal
Feedback
```

这里的“稳定”不是字段永远不能变。

而是：

> 所有端使用同一套概念，不自行创建重复模型。

---

# 54. Explicit Non-goals for Architecture

当前阶段不做：

- Microservices；
- Distributed Saga；
- Event Sourcing；
- General Agent Runtime；
- Arbitrary Tool Marketplace；
- Multi-agent collaboration；
- Generic workflow engine；
- ML platform；
- feature store；
- vector-memory-first architecture；
- complex retry engine；
- universal external data abstraction。

只有真实需求出现后再引入。

---

# 55. 架构评审 Checklist

后续每个 Work Order 都应检查：

### Domain

- 是否复用了统一领域对象？
- 是否让 UI 文案变成业务状态？
- 是否让 AI 成为了业务真相？

### App

- 是否只承担应该属于 App 的状态？
- 是否出现 App 自己重建 Task 状态？

### Backend

- 是否有明确 Use Case？
- 是否保持 Domain authoritative？

### AI

- structured output 是否明确？
- 是否给了过多上下文？
- 是否把关键事实交给 LLM 猜？

### Execution

- side effect 是否先 approval？
- action result 是否真实可验证？
- 是否可防重复执行？

### Learning

- behavior signal 是否被错误当作 preference？
- 用户是否可理解 / 撤销重要 inference？

### Complexity

- 是否为低概率 case 引入了高复杂度？
- 是否存在“为了未来可能需要”而提前抽象？

---

# 56. Blueprint 的最终系统判断

Orbit 最合理的系统形态不是：

```text
Chat UI
   ↓
LLM Agent
   ↓
Tools
```

而应该是：

```text
                 ┌──────────────┐
External World → │ Opportunity  │
                 └──────┬───────┘
                        ↓
                 Personal Ranking
                        ↓
                      Task
                 ┌──────┴──────┐
                 ↓             ↓
           Conversation     Planning
                 ↓             ↓
            Constraints →   Plan
                               ↓
                           Approval
                               ↓
                           Execution
                               ↓
                            Feedback
                               ↓
                            Learning
                               │
                               └────→ Ranking
```

AI 横向参与：

```text
Understanding
Reasoning
Composition
Explanation
```

但**不成为中心数据库、不成为状态机、不成为事实来源、不成为执行结果判定者**。

---

# 57. 当前架构结论

Orbit 的技术难点不是“把 LLM 接进 App”。

真正需要长期建设的是：

1. **统一 Opportunity 语义；**
2. **Personal Ranking；**
3. **稳定 Task lifecycle；**
4. **结构化 Constraint / Plan；**
5. **Approval + Execution 的可靠闭环；**
6. **Evidence-based Learning。**

因此后续所有工程决策都应该优先回答：

> 这是否让 `Opportunity → Personal Ranking → Plan → Action → Feedback` 的闭环更正确、更简单、更可学习？

如果没有，应谨慎增加复杂度。

---

# 58. 下一步

基于：

- `Orbit Product Requirements v1.0`
- `Orbit System Blueprint v1.0`

下一份正式文档应为：

# `Orbit RoadMap v1.0`

RoadMap 应：

- 按垂直用户能力拆 Milestone；
- 每个 Milestone 同时覆盖 App / Backend / AI；
- 明确进入条件与退出标准；
- 明确核心 E2E；
- 明确产生的数据资产；
- 明确不做什么；
- 为后续逐阶段 Work Order 提供稳定边界。


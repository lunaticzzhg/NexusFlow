# Orbit RoadMap

**文档类型**：Product / Engineering RoadMap  
**版本**：v1.0  
**状态**：Baseline  
**上游依据**：
- `Orbit Product Requirements v1.0`
- `Orbit System Blueprint v1.0`

**目标**：把 Orbit 从产品定义逐步落地为可验证、可演进的端到端 Personal Life Agent。每个 Milestone 必须形成真实用户能力，而不是只建设某一端或某一个技术模块。

---

# 0. RoadMap 原则

Orbit RoadMap 不按：

```text
App
Backend
AI
```

横向拆阶段。

也不按：

```text
Memory
Agent Framework
Recommendation Engine
```

这种纯技术模块拆阶段。

统一按：

> **用户在这一阶段新增能够完成什么真实目标**

来定义 Milestone。

每个 Milestone 都必须同时回答：

1. 用户新获得什么能力？
2. App / Backend / AI 各自需要实现什么？
3. 核心 Domain Model 增加什么？
4. 核心 E2E 如何走通？
5. 会产生什么可用于未来 Personal Ranking / Learning 的数据？
6. 什么明确不做？
7. 如何判断这一阶段真的完成？

---

# 1. 总体 RoadMap

```text
M0 — System Foundation
统一领域语义、状态、Contract、AI Structured Output 和工程基线

        ↓

M1 — First Planning Loop
用户可以提出周末需求，并拿到真正可比较的 Plan A / B / C

        ↓

M2 — Action Loop
用户可以批准 Plan，并把它真正写入 Calendar / Reminder

        ↓

M3 — Opportunity Engine
Orbit 可以主动发现、排序和解释值得用户关注的生活机会

        ↓

M4 — Learning Loop
Orbit 开始从真实选择和反馈中形成 Personal Taste Model

        ↓

M5 — Proactive & Reliability
Orbit 可以低频主动提醒，并具备可上线所需的 freshness、analytics 与 reliability
```

---

# 2. 阶段依赖关系

RoadMap 的核心依赖不是技术模块依赖，而是产品闭环依赖。

```text
M0
Domain Truth / Contract
        ↓
M1
Constraint → Plan
        ↓
M2
Plan → Action
        ↓
M3
Opportunity → Ranking → Task
        ↓
M4
Decision → Learning → Ranking
        ↓
M5
Proactive Delivery + Production Reliability
```

如果前一阶段没有稳定，不应通过在下一阶段增加 fallback 来掩盖问题。

---

# 3. M0 — System Foundation

## 3.1 目标

建立 Orbit 的统一系统语言和最小工程地基，使后续每个 Milestone 都能在同一套 Domain Model、状态机、Contract 和测试规则上开发。

M0 不追求丰富用户功能。

M0 的成功标准是：

> **后续 Codex 在实现 App / Backend / AI 时，不需要重新发明 Orbit 的核心语义。**

---

## 3.2 用户价值

M0 的直接用户价值有限，但必须保证：

- App 可以连接真实 Backend；
- Backend 可以创建和读取 Task；
- AI 可以稳定返回结构化结果；
- App / Backend / AI 对同一状态有一致理解。

---

## 3.3 核心领域对象

M0 必须正式建立统一语义：

```text
Task
TaskState
Conversation
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
InferredPreference
BehaviorSignal
Feedback
```

M0 不要求每个对象都完整投入使用，但不能让各层自己创造重复模型。

---

## 3.4 Task State 基线

至少支持：

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

状态转换由 Backend Domain 层持有。

App 不能通过 UI 文案推断状态。

AI 不能直接修改 Task State。

---

## 3.5 App

M0 App 主要完成：

- API client 基线；
- Domain DTO / Model mapping；
- 统一 loading / error 基础结构；
- Task / Plan 等后续模块的 package/module 边界；
- 当前原型 UI 与正式 Domain Model 的映射基线；
- 调试入口或开发模式下可加载 fixture Task；
- OS permission / Calendar integration 先保留 adapter 边界，不在 M0 完整实现。

### App 不做

- 完整 Home；
- 完整 Chat；
- 真正 Planning；
- Calendar 写入；
- Opportunity Ranking。

---

## 3.6 Backend

M0 Backend 完成：

- application skeleton；
- domain / application / infrastructure / api 分层或等价结构；
- Task persistence；
- Task state transition；
- Conversation / Message 最小持久化；
- Constraint model；
- Plan model；
- API error model；
- id / version / timestamps；
- audit / trace 基线；
- fixture data capability；
- AI adapter boundary；
- Opportunity source boundary；
- Calendar / Reminder gateway boundary。

推荐 Use Case 基线：

```text
CreateTask
GetTask
SendTaskMessage
UpdateConstraints
GeneratePlans（可先 fixture）
SelectPlan（可先 fixture）
```

---

## 3.7 AI

M0 AI 只需要证明：

> 自然语言可以稳定进入 Orbit 的结构化系统。

首先实现：

```text
UnderstandUserMessage
```

结构化输出至少包含：

```text
userIntent
extractedConstraints[]
missingInformation[]
clarificationNeeded
assistantMessageDraft
```

要求：

- schema-first；
- structured output；
- parse / validation；
- model / prompt version 可追踪；
- AI provider 通过 adapter 隔离。

M0 不实现复杂 Agent loop。

---

## 3.8 数据资产

M0 就必须开始保证后续数据语义正确：

- Task created；
- message sent；
- constraint confirmed；
- planning requested；
- plan generated；
- plan selected。

这些可以先进入 audit / analytics baseline。

---

## 3.9 核心 E2E

```text
App
→ Create Task
→ Send Message
→ Backend persists
→ AI structured understanding
→ Backend validates
→ Task constraints updated
→ App displays updated task
```

Planning 可以返回 fixture Plan，但必须已经通过正式 Plan contract。

---

## 3.10 验收标准

M0 完成必须满足：

- App / Backend / AI 使用统一 Task / Constraint / Plan 语义；
- Backend 是 authoritative Task state；
- AI structured output 可被确定性验证；
- 一条用户消息可以端到端改变 Task；
- fixture Plan 可以通过正式 contract 展示；
- domain unit test、AI contract test、API integration test 基线存在；
- trace 可以关联 `taskId + AI request id`；
- 不存在 `POST /agent/run` 式万能接口承担所有语义。

---

## 3.11 Non-goals

M0 明确不做：

- Opportunity real source；
- Calendar write；
- Personal Ranking；
- Inferred Preference；
- proactive notification；
- multi-agent；
- vector memory；
- microservices；
-复杂 retry framework。

---

# 4. M1 — First Planning Loop

## 4.1 目标

第一次证明 Orbit 的核心 Planning Value：

> **用户用自然语言表达一个周末需求，Orbit 能用很少的追问收敛条件，并生成 3 个真正可执行且有差异的候选方案。**

推荐 MVP 主场景：

> “这周末想看利物浦比赛。”

或：

> “这周末想出去看个电影，不想太远。”

---

## 4.2 用户价值

用户第一次可以完成：

```text
模糊需求
→ 必要条件确认
→ Plan A / B / C
```

不再只是聊天。

---

## 4.3 App

### Chat

正式实现：

- 创建新 Task；
- 当前 Task Chat；
- User / Assistant messages；
- 本次条件区；
- 删除 Constraint；
- 清空本次条件；
- 接受 Profile Suggestion；
- AI clarification；
- Ready to Plan 状态；
- Generate Plans。

### Plan

实现：

- Plan A / B / C；
- direction；
- timeline；
- budget；
- commute；
- reasons；
- tradeoffs；
- source / freshness；
- validUntil；
- Plan selection；
- regenerate direction；
- 返回 Chat 修改条件。

### UI 原则

不能只渲染 AI Markdown。

Plan 必须使用结构化 ViewModel。

---

## 4.4 Backend

正式实现：

### Conversation / Constraint

- Current Task constraints；
- constraint source；
- hard / soft；
- accepted suggestion；
- remove constraint；
- constraint history / audit；
- readiness decision。

### Planning

实现：

```text
Resolve Planning Context
→ Candidate Opportunities
→ AI Compose Plans
→ Deterministic Validation
→ Persist PlanningRun
→ Persist Plans
```

M1 外部 Opportunity 可以继续使用：

- fixture；
- controlled dataset；
- manually curated test source。

目的不是做 Discovery，而是先验证 Planning。

### Plan Validation

至少验证：

- required fields；
- time shape；
- duplicate plans；
- hard constraint violation；
- validUntil；
- referenced Opportunity existence。

如果已有可用 Calendar read，可以接入；否则以 controlled availability fixture 替代，避免 M1 范围失控。

---

## 4.5 AI

M1 增加：

```text
DecideClarification
ComposePlans
ExplainPlan
```

AI 必须做到：

### Clarification

只在缺失信息显著影响：

- feasibility；
- ranking；
- action；

时追问。

### Plan composition

必须产生真正不同的方向：

```text
Best Match
More Relaxed
New Experience
```

不能只改标题和措辞。

### Plan explanation

基于 Backend 提供的 verified facts，而不是自由发明事实。

---

## 4.6 Personal Preference

M1 只做 **Explicit Preference + Profile Suggestion**。

例如：

```text
Explicit:
喜欢英超
喜欢科幻电影

Suggestion:
常用预算 ¥300
低通勤偏好
```

长期 Preference 只能作为 suggestion 进入当前 Task。

未经接受，不变成 hard constraint。

---

## 4.7 Behavior Signal

开始记录：

```text
TaskStarted
ConstraintAccepted
ConstraintRemoved
PlansGenerated
PlanSelected
PlanRegenerated
```

M1 不需要真正训练 Ranking。

但必须保证未来可以回答：

> 用户看到什么 Plan，最终选了哪个？

---

## 4.8 核心 E2E

```text
用户：
“周末想看利物浦比赛”

→ Task Created
→ AI extracts:
   本周末
   利物浦
→ 如有必要追问：
   在家 / 外出？
→ 用户确认
→ Candidate opportunities
→ AI compose
→ Validator
→ Plan A / B / C
→ App 展示
→ 用户选择 Plan B
```

---

## 4.9 验收标准

- 用户无需填写长表单即可形成 Task；
- AI 平均只追问真正必要的问题；
- 本次 Constraint 和长期 Preference 明确分离；
- 3 个 Plan 有真实差异；
- Plan 是结构化 Domain Entity；
- AI 产生的 Plan 必须经过 Backend validation；
- Plan 具有 freshness / validity；
- 用户可以修改条件后重新生成；
- 用户 Plan Selection 被结构化记录。

---

## 4.10 产品验证指标

关注：

- Chat → Plan Generation Rate；
- Average Clarification Turns；
- Plan Selection Rate；
- Regeneration Rate；
- Task abandonment before plans。

M1 重点验证：

> **Planning 是不是比自己搜索和安排明显省事。**

---

## 4.11 Non-goals

M1 不做：

- real proactive opportunity；
- notification；
- inferred preference；
-自动 Calendar action；
-复杂 external source aggregation；
- ML ranking。

---

# 5. M2 — Action Loop

## 5.1 目标

第一次完成：

> **Plan → Approval → Real-world Action**

使 Orbit 从 Planner 变成真正具有受控执行能力的 Agent。

---

## 5.2 用户价值

用户选择 Plan 后，不需要自己重新：

- 建日历；
- 创建提醒；
- 复制时间；
- 记活动时间。

Orbit 可以在明确审批后执行。

---

## 5.3 App

### Approval UI

正式实现：

- Selected Plan summary；
- Proposed Actions；
- action toggle；
- editable parameters；
- Calendar permission state；
- Reminder permission/state；
- approve；
- reject；
- defer。

### Execution UI

正式实现：

- Executing；
- action-level status；
- partial success；
- failure reason；
- user action required；
- retry where applicable；
- final result。

### Task Center

这一阶段 Task Center 开始真正有意义：

```text
Needs Attention
In Progress
Completed
```

Task Detail 展示：

- 已确认条件；
- Plan；
- Approval；
- Execution timeline。

---

## 5.4 Backend

正式实现：

```text
SelectPlan
PrepareApproval
ApproveActions
ExecuteApprovedActions
GetExecutionResult
RetryExecution
```

### Approval Snapshot

必须记录：

- planId；
- planVersion；
- approved actions；
- approved parameters；
- approvedAt。

### Execution

按 action 粒度：

```text
Pending
Running
Succeeded
Failed
```

### Idempotency

必须避免：

- double tap；
- network retry；
- reconnect；

造成重复 Calendar event / Reminder。

---

## 5.5 Calendar / Reminder

M2 重点支持：

```text
CreateCalendarEvent
CreateReminder
```

可选：

```text
UpdateCalendarEvent
RemoveReminder
```

如果 ROI 足够再加入。

必须区分：

```text
OS Permission
≠
Product Approval
```

---

## 5.6 AI

M2 AI 增量很小。

AI 可以：

- 将 Plan 转为用户友好的 action summary；
- 对失败原因生成简洁解释（基于真实 failure code）。

AI 不参与：

- permission truth；
- action success truth；
- retry decision 的关键确定性规则。

---

## 5.7 Behavior Signal

新增：

```text
PlanSelected
ApprovalViewed
ActionEnabled
ActionDisabled
ApprovalGranted
ApprovalRejected
ExecutionSucceeded
ExecutionFailed
TaskCompleted
```

这些是后续 User Decision Model 非常重要的数据。

---

## 5.8 核心 E2E

```text
Plan B selected
→ verify Plan still valid
→ Prepare Approval
→ Calendar event ON
→ Reminder ON
→ user approves
→ Approval Snapshot
→ Calendar create
→ Reminder create
→ Results
→ Task Completed
```

部分失败：

```text
Calendar failed
Reminder succeeded
→ NeedsAttention / partial result
```

---

## 5.9 验收标准

- 所有外部副作用先经过 approval；
- approval 引用具体 Plan version；
- duplicate action 可防止；
- Calendar / Reminder 结果来自真实系统；
- partial success 可准确展示；
- permission failure 可恢复；
- Plan expired 时不能直接执行；
- Task Detail 能解释当前发生了什么；
- Execution 成功/失败均产生结构化事件。

---

## 5.10 产品验证指标

- Plan → Approval Rate；
- Approval → Successful Execution Rate；
- Partial Failure Rate；
- Action retry success；
- Task Completion Rate。

M2 核心验证：

> **用户愿不愿意把真实日程的一部分交给 Orbit 执行。**

---

## 5.11 Non-goals

M2 不做：

- ticket purchase；
- payment；
- restaurant booking；
- arbitrary web actions；
- general computer-use agent；
- complex workflow compensation。

---

# 6. M3 — Opportunity Engine

## 6.1 目标

第一次建立 Orbit 真正的产品差异：

> **用户不需要先知道自己要做什么，Orbit 可以主动发现值得关注的生活机会。**

从这一阶段开始：

```text
Opportunity
→ Personal Ranking
→ Home
→ Task
```

成为真实产品能力。

---

## 6.2 MVP Domain Scope

M3 不追求全品类。

推荐首期：

```text
Sports
Movies
```

如果数据源条件允许，可增加一个：

```text
Live Events / Exhibitions
```

但首要目标是质量而非覆盖量。

---

## 6.3 Opportunity Ingestion

正式实现：

```text
Source Adapter
→ Source Record
→ Normalizer
→ Opportunity
→ Freshness
→ Eligibility
```

每个 Opportunity 至少具备：

- source；
- updatedAt；
- observedAt；
- validUntil；
- location；
- time；
- availability；
- price / priceRange（如适用）。

---

## 6.4 App

### Home

正式实现 Agent Dashboard：

1. 需要你处理；
2. 为你准备的一件事；
3. 本周安排；
4. 发现的新机会。

Home 不做无限 Feed。

### Opportunity Detail

展示：

- title；
- time；
- location；
- source；
- freshness；
- why now；
- why you；
- action CTA。

Feedback：

- 感兴趣；
- 不感兴趣；
- 少推荐此类；
- 本周静默。

### Opportunity → Task

点击：

> 围绕它聊聊

必须创建：

```text
Task(origin = Opportunity)
```

保留结构化关系。

---

## 6.5 Backend

正式实现 Opportunity Domain：

- source adapters；
- normalization；
- dedupe；
- freshness；
- availability；
- candidate query。

正式实现 Personal Ranking v1。

推荐：

```text
Eligibility Rules
+
Weighted Features
+
Simple personalization
+
AI semantic assist
```

而不是直接上 ML platform。

---

## 6.6 Ranking v1 Features

至少考虑：

```text
Interest Match
Time Fit
Calendar Fit
Distance Fit
Budget Fit
Freshness
Novelty
Historical Acceptance
Recent Exposure
Silence
Confidence
```

明确区分：

```text
Hard Filter
vs
Soft Ranking
```

---

## 6.7 Delivery Level

Ranking 至少需要产品语义：

```text
Hidden
Browse
HomeCandidate
Featured
NotificationEligible
```

M3 可以暂时不真的发 Notification。

但 Ranking 要为 M5 proactive 做准备。

---

## 6.8 AI

M3 AI 增加：

```text
ExplainOpportunity
SemanticInterestMatch（可选）
```

Why now / Why you 必须基于 structured ranking evidence。

例如：

```text
CalendarFit = Saturday evening free
InterestMatch = explicit Premier League preference
Freshness = schedule updated 2h ago
```

AI 只负责自然语言表达。

---

## 6.9 Preference 使用

从 M3 开始，Explicit Preference 正式进入 Ranking。

Inferred Preference 仍可先有限使用或保持实验状态。

---

## 6.10 核心 E2E

```text
Sports Source
→ Opportunity
→ Freshness
→ Eligibility
→ Personal Ranking
→ Home featured
→ user opens
→ Why now / Why you
→ “围绕它聊聊”
→ Create Task(origin=Opportunity)
→ Constraint
→ Plan
→ Approval
→ Execution
```

这是 Orbit 第一次完整贯通最核心价值链。

---

## 6.11 验收标准

- 至少一个真实 Opportunity source；
- Opportunity normalization 与 UI 解耦；
- freshness 可见且可用于 validation；
- stale / expired 不进入可执行路径；
- Home 能稳定返回 featured Opportunity；
- why now / why you 有真实 evidence；
- Opportunity → Task 保留结构关系；
- dismissal / silence 能影响未来 delivery；
- 无高质量机会时允许“不推荐”。

---

## 6.12 产品验证指标

最重要：

- Opportunity Open Rate；
- Opportunity → Planning Rate；
- Opportunity Dismiss Rate；
- Featured Opportunity Acceptance；
- Opportunity → Real-world Action Rate。

M3 核心验证：

> **Orbit 找到的东西是否真的值得用户花时间。**

---

## 6.13 Non-goals

M3 不做：

- 覆盖所有活动源；
- 全城市搜索；
-复杂推荐 ML；
-社交推荐；
-广告；
-UGC；
-无限 Feed。

---

# 7. M4 — Learning Loop

## 7.1 目标

让 Orbit 从：

> “基于用户设置做个性化”

升级为：

> **“根据用户真实选择逐渐形成 Personal Taste Model。”**

这是长期竞争力开始形成的阶段。

---

## 7.2 Personal Taste Model v1

必须明确区分：

```text
Explicit Preference

Inferred Preference

Behavior Signal

Task Feedback
```

---

## 7.3 Evidence Model

Inferred Preference 必须有 evidence。

例如：

```text
Inference:
Low Commute Preference

Evidence:
- selected Plan with 12min commute
- selected Plan with 18min commute
- rejected Plan with 42min commute
- feedback: “下次少通勤”

Confidence:
Medium
```

系统不能只保存：

```text
low_commute = true
```

---

## 7.4 App

Preferences 页面正式实现：

### Explicit Preference

- 查看；
- 编辑；
- 删除。

### Inferred Preference

展示：

- 推断内容；
- evidence summary；
- confidence；
- 当前用途。

允许：

- 删除 inference；
- 确认为长期 Preference。

### Feedback

执行完成后：

- 很满意；
- 下次少通勤；
- 不喜欢这种安排；
- 可根据场景增加有限结构化反馈。

---

## 7.5 Backend

实现：

```text
RecordBehaviorSignal
RecordFeedback
AggregateEvidence
UpdateInference
RemoveInference
ConfirmInferenceAsPreference
```

Learning v1 不要求在线 ML。

推荐：

```text
Rules
+
Weighted Evidence
+
Decay where needed
```

---

## 7.6 Signal Taxonomy

至少包括：

```text
OpportunityImpression
OpportunityOpen
OpportunityDismiss
PlanningStart
PlanSelected
PlanRegenerated
ApprovalGranted
ActionSucceeded
TaskCompleted
FeedbackSubmitted
```

不是所有 signal 权重相同。

例如：

```text
PlanSelected
>
OpportunityOpened
```

而：

```text
TaskCompleted + PositiveFeedback
```

权重更高。

---

## 7.7 Preference Promotion

典型路径：

```text
Behavior Signals
→ Evidence
→ Inferred Preference
→ confidence grows
→ prompt user when useful
→ Explicit Preference
```

不能自动：

```text
一次反馈
→ 永久长期偏好
```

---

## 7.8 Ranking v2

M4 Ranking 开始使用：

- Inferred Preference；
- historical acceptance；
- Plan selection pattern；
- commute sensitivity；
- budget sensitivity；
- novelty preference；
- repeat-category fatigue。

不用一开始全做。

优先挑有明确数据支持的 2–4 个维度。

---

## 7.9 Planning Personalization

M4 不只让 Opportunity Ranking 个性化。

Plan Ranking 也可以开始使用：

```text
Personal Taste Model
```

例如同一 Opportunity：

```text
用户 A:
偏向低通勤 → Plan A 排前

用户 B:
更喜欢新体验 → Plan C 排前
```

---

## 7.10 核心 E2E

```text
多次 Opportunity / Plan interactions
→ Behavior Signals
→ Evidence
→ Low commute inference
→ Ranking 使用该 inference
→ 下一次更低通勤 Opportunity 排名上升
→ Preferences 显示推断
→ 用户确认 / 删除
```

---

## 7.11 验收标准

- signal、feedback、preference、inference 明确分层；
- inference 有 evidence 和 confidence；
- 用户可以删除 inference；
- 删除后 Ranking 不再使用；
- 用户可以确认 inference 为 explicit preference；
- 至少一个 ranking feature 由历史行为改善；
- 一次行为不会永久修改长期 Preference；
- Task-local Constraint 不会错误写入长期画像。

---

## 7.12 产品验证指标

关注：

- Returning User Opportunity Acceptance；
- Acceptance improvement by tenure；
- Inference confirmation rate；
- Inference deletion rate；
- Personalized ranking lift；
- Positive feedback rate；
- Regeneration rate trend。

M4 核心验证：

> **Orbit 是否真的越用越懂用户。**

---

## 7.13 Non-goals

M4 暂不做：

- 通用“记住所有聊天”；
- vector-memory-first；
- autonomous preference writing；
- deep psychographic profiling；
- heavy ML infrastructure；
-用户不可见的黑箱画像。

---

# 8. M5 — Proactive & Reliability

## 8.1 目标

让 Orbit 从“可用的内部 MVP”走向：

> **真正可以长期运行在用户生活中的低打扰 Personal Life Agent。**

M5 重点不是堆新功能，而是：

- 主动性；
- reliability；
- freshness；
- observability；
- analytics；
- 用户控制；
- 上线质量。

---

## 8.2 Proactive Delivery

正式实现：

```text
RankedOpportunity
→ Delivery Gate
→ Home Featured / Notification
```

Notification Gate 至少考虑：

```text
Relevance
Urgency
Confidence
Recent notification count
Silence
User preference
Time of day
```

---

## 8.3 App

新增：

- proactive notification deep link；
- notification settings；
- this-week silence；
- per-category reduction；
- freshness states；
- stale Plan UX；
- retry / permission recovery；
- better task needs-attention UX。

---

## 8.4 Backend

新增：

- periodic opportunity refresh；
- freshness refresh；
- notification eligibility；
- delivery history；
- silence state；
- notification budget；
- common transient retry；
- execution recovery；
- lifecycle cleanup；
- analytics aggregation。

---

## 8.5 AI

重点是 reliability：

- structured output failure handling；
- prompt versioning；
- evaluation dataset；
- hallucination guardrails；
- explanation consistency；
- latency / cost tuning。

不因为进入 M5 就扩大 AI autonomy。

---

## 8.6 Opportunity Source Expansion

只有在现有 domain 数据质量和 ranking 已证明有效后，才逐步增加：

- Live Events；
- Exhibitions；
- Local Experiences；
- Restaurants as supporting context。

每新增 source 都必须满足：

- freshness；
- provenance；
- normalization；
- product value。

---

## 8.7 Reliability 范围

重点覆盖：

### Network

- transient failures；
- timeout；
- reconnect。

### Permission

- Calendar denied；
- Notification disabled；
- Location unavailable。

### Freshness

- Opportunity stale；
- Plan expired；
- source unavailable。

### Execution

- partial failure；
- retryable failure；
- idempotency。

不为极低概率场景建设复杂 fallback network。

---

## 8.8 Observability

完整关联：

```text
taskId
planningRunId
planId
approvalId
executionId
AI request
external source request
delivery event
```

关键目标：

> 一次用户投诉可以追踪完整链路。

---

## 8.9 Product Analytics

形成完整 Funnel：

```text
Opportunity Impression
→ Open
→ Planning
→ Plans Generated
→ Plan Selected
→ Approval
→ Execution
→ Feedback
```

并可按：

- user tenure；
- domain；
- opportunity source；
- ranking version；
- plan direction；

分析。

---

## 8.10 核心 E2E

```text
Orbit background refresh
→ high-confidence Opportunity
→ notification gate
→ user receives one useful notification
→ opens
→ plans
→ approves
→ executes
→ feedback
→ learning
→ next ranking improves
```

这标志着核心飞轮正式成立。

---

## 8.11 验收标准

- proactive delivery 有明确 gate；
- 默认不会高频打扰；
- silence 立即生效；
- stale / expired path 正确；
- execution 常见失败可恢复；
- analytics 能计算 North Star funnel；
- AI / source / execution 全链路可 trace；
- source expansion 不破坏统一 Opportunity model；
- retry 有上限且仅覆盖高 ROI 场景。

---

## 8.12 产品验证指标

M5 重点：

### North Star

```text
Opportunity → Real-world Action Rate
```

### 主动推荐质量

- Notification Open Rate；
- Notification → Planning；
- Notification mute / silence rate；
- Opportunity acceptance。

### Reliability

- execution success；
- stale-plan rate；
- source freshness SLA；
- crash-free / request success；
- AI structured output success。

---

# 9. M0–M5 用户能力变化

| Milestone | 用户真正新增的能力 |
|---|---|
| M0 | Orbit 已具备统一系统骨架，用户消息可以进入真实 Task |
| M1 | 用户可以通过聊天得到可执行的周末方案 |
| M2 | 用户可以批准方案，并真正写入日历 / 提醒 |
| M3 | Orbit 可以主动发现并推荐值得做的事情 |
| M4 | Orbit 可以从真实选择中逐渐形成个人偏好模型 |
| M5 | Orbit 可以低频主动工作，并达到可长期使用的可靠性 |

---

# 10. App / Backend / AI 演进总览

## App

```text
M0
Domain/API foundation

M1
Chat + Constraint + Plan

M2
Approval + Execution + Task Center

M3
Home + Opportunity Detail

M4
Preferences + Evidence + Feedback

M5
Notification + Reliability UX
```

---

## Backend

```text
M0
Domain Truth + Task + Contract

M1
Conversation + Planning

M2
Approval + Execution

M3
Opportunity + Ranking

M4
Learning + Personal Taste Model

M5
Proactive Delivery + Reliability
```

---

## AI

```text
M0
UnderstandUserMessage

M1
Clarification + ComposePlans

M2
Action explanation only

M3
ExplainOpportunity + semantic assist

M4
Feedback interpretation + inference assist

M5
Evaluation / reliability / optimization
```

AI 的职责不会随着 RoadMap 推进无限增长。

---

# 11. Personal Taste Model 演进

为了避免过早建设 Memory Platform：

```text
M0
只定义语义

M1
Explicit Preference
+ Task-local Constraint

M2
记录真实 Plan / Approval / Execution decision

M3
Explicit Preference 进入 Ranking

M4
Behavior Signal
→ Evidence
→ Inferred Preference
→ Ranking improvement

M5+
再根据真实数据决定：
decay
contextual preference
embedding
learning-to-rank
```

因此：

> **Long-term memory 很重要，但应该随着真实决策数据增长，而不是先建设一个大而全 Memory 系统。**

---

# 12. 数据资产演进

## M0

```text
Task
Message
Constraint
```

## M1

```text
PlanningRun
Plan Exposure
Plan Selection
Regeneration
```

## M2

```text
Approval
Action Choice
Execution
Completion
```

## M3

```text
Opportunity Impression
Opportunity Open
Dismissal
Ranking Result
```

## M4

```text
Behavior Signal
Feedback
Inference
Preference Confirmation
```

## M5

```text
Delivery
Notification
Freshness
Reliability
```

最后形成：

```text
Opportunity
×
User Decision History
×
Context
```

这才是未来 Personal Ranking 的数据基础。

---

# 13. Milestone Gate

每个 Milestone 完成后才能正式进入下一阶段。

---

## M0 → M1 Gate

必须：

- Domain contract 稳定；
- Task authoritative；
- AI structured output 稳定；
- App 可走真实 Task API。

---

## M1 → M2 Gate

必须：

- Constraint 收敛可靠；
- Plan A/B/C 有决策价值；
- Plan 是结构化可验证对象；
- 用户可以稳定选出一个 Plan。

如果 Plan 本身质量不够，不进入 Execution。

---

## M2 → M3 Gate

必须：

- Approval 清晰；
- Calendar / Reminder execution 可靠；
- partial failure 正确；
- Task state 真实。

只有真实 action loop 成立后，主动 Opportunity 才有完整落点。

---

## M3 → M4 Gate

必须：

- Opportunity quality 达到基本水平；
- Ranking 有真实使用数据；
- Opportunity → Plan → Action 已形成真实决策记录。

没有决策数据时，不应建设复杂 Learning。

---

## M4 → M5 Gate

必须：

- inference 能被解释；
- personalized ranking 有基本 lift；
- 用户可以控制画像；
- Learning 不会污染 Task-local state。

---

# 14. RoadMap 中明确不提前做的事情

以下能力只有出现明确产品证据后才考虑：

```text
Microservices
Event Sourcing
Distributed Workflow Engine
Multi-agent system
Vector-memory-first
Generic browser agent
ML feature store
Learning-to-rank platform
Autonomous purchasing
Payment
Ticket booking
Social graph
UGC community
Full travel agent
Work assistant
Finance assistant
```

---

# 15. RoadMap 产品风险

## Risk 1 — Opportunity 数据不够好

可能导致：

> Orbit 变成一个 AI 包装的普通活动列表。

策略：

- M3 限制 domain；
- 质量优先于覆盖；
- source freshness 强约束；
- 不够好时允许不推荐。

---

## Risk 2 — Planning 只是 AI 文案

策略：

- M1 就要求 structured Plan；
- deterministic validation；
- 真实 tradeoff；
- Plan Selection 指标验证价值。

---

## Risk 3 — 主动推荐骚扰

策略：

- M3 先 Home featured；
- M5 才正式 push；
- notification gate；
- silence / reduce recommendation；
- 每天少而准。

---

## Risk 4 — Memory 做成黑箱

策略：

- Preference / Inference / Signal 分层；
- evidence；
- confidence；
- user removal；
- 不做“所有聊天都进长期记忆”。

---

## Risk 5 — AI 成为状态机

策略：

- Backend authoritative；
- structured outputs；
- Domain validation；
- AI 只提供 proposal。

---

## Risk 6 — 过度兜底导致系统复杂

策略：

- 主流程优先；
- common failure only；
- finite retry；
- failure transparency；
- 每个 fallback 做 ROI 判断。

---

# 16. MVP 完成定义

如果 M0–M5 完成，Orbit MVP 应可以稳定实现：

```text
1. 用户设置少量兴趣
2. Orbit 从真实 source 获得 Opportunity
3. Personal Ranking 找到最值得推荐的一件事
4. Home / Notification 低频呈现
5. 用户看到 Why now / Why you
6. 用户围绕它建立 Task
7. Chat 用少量追问收敛 Constraint
8. 生成 3 个结构化 Plan
9. 用户选择
10. Approval
11. Calendar / Reminder
12. Action-level Result
13. Feedback
14. Behavior Signal
15. Inferred Preference
16. 下一次 Ranking 改善
```

这才代表：

```text
Opportunity
→ Personal Ranking
→ Plan
→ Action
→ Feedback
→ Learning
```

核心飞轮成立。

---

# 17. MVP North Star

RoadMap 的最终验证不以：

- 聊天次数；
- 消息数量；
- DAU；
- AI token usage；

作为第一目标。

候选 North Star：

# **Opportunity → Real-world Action Rate**

辅助核心指标：

```text
Opportunity Open Rate
Opportunity → Planning Rate
Plan Selection Rate
Plan → Approval Rate
Execution Success Rate
Positive Feedback Rate
Returning User Acceptance Lift
```

---

# 18. 后续 Work Order 组织方式

RoadMap 本身不替代 Work Order。

每个 Milestone 开始前应单独创建：

```text
M0 Work Order
M1 Work Order
M2 Work Order
...
```

Work Order 必须包含：

- 当前代码基线；
- 本阶段目标；
- App tasks；
- Backend tasks；
- AI tasks；
- Domain changes；
- API contracts；
- migration；
- tests；
- telemetry；
- acceptance；
- explicit non-goals；
- required docs updates。

每个 Work Order 必须能够直接指导 Codex 开发。

---

# 19. 推荐实际开发节奏

建议严格执行：

```text
M0 Work Order
→ implementation
→ review
→ fix
→ milestone acceptance

M1 Work Order
→ implementation
→ review
→ product validation
→ milestone acceptance

M2 ...
```

不要一次性把 M0–M5 全部转换为开发任务让 Codex 同时实施。

RoadMap 是方向。

Work Order 是当前阶段的执行合同。

---

# 20. 最终判断

Orbit RoadMap 的重点不是把所有 Agent 能力做齐。

正确顺序应该是：

```text
先让用户能得到好方案
        ↓
再让方案真正发生
        ↓
再让 Orbit 主动找到好机会
        ↓
再从真实选择中学习
        ↓
最后才扩大主动性和覆盖面
```

原因是：

> **没有高质量 Plan，Execution 没价值。**  
> **没有 Execution，主动 Opportunity 没有闭环。**  
> **没有真实 Opportunity / Decision 数据，长期 Learning 没有可靠基础。**

因此 M0–M5 的建设顺序，本质上是在逐步降低 Orbit 最大的产品风险：

```text
M0：系统会不会做乱
M1：方案有没有价值
M2：用户敢不敢让它执行
M3：它能不能发现真正值得做的事
M4：它是否越用越懂人
M5：它能不能长期、低打扰、可靠地运行
```

当这些问题依次被验证，Orbit 才真正从一个 AI 产品原型成长为有长期竞争力的 Personal Life Agent。

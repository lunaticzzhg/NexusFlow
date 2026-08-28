# Orbit Product Requirements

**文档类型**：Product Requirements / MVP Product Spec  
**版本**：v1.0  
**状态**：Baseline  
**目标**：作为 Orbit 后续 RoadMap、App、Backend、AI、测试与 Work Order 的产品真源（Product Source of Truth）

---

## 0. 文档原则

本文件定义 Orbit **要解决什么问题、产品如何工作、哪些行为是必须的、哪些暂时不做**。

本文件不提前绑定具体技术实现。除非某项技术约束直接影响产品正确性，否则数据库表结构、API 形态、模型供应商、Agent 框架、缓存方案等应在后续系统设计与各阶段 Work Order 中确定。

后续实现如果与本文件冲突，应优先确认产品需求是否发生变化，而不是在代码中自行引入新的产品语义。

---

# 1. 产品定义

## 1.1 一句话定义

**Orbit 是一个主动发现生活机会，并把值得做的事情推进到真实发生的 Personal Life Agent。**

Orbit 不以“回答问题”为最终目标，而以：

> **找到此刻值得用户投入时间的事情，并在用户控制下完成从发现、规划到执行的闭环。**

为核心价值。

---

## 1.2 MVP 产品定位

MVP 不做“全能个人助理”，而聚焦：

# **帮用户过好周末**

首期重点场景：

- Sports
- Movies
- Live Events
- Exhibitions
- Local Experiences
- Restaurants（作为活动前后配套能力，而不是独立餐饮推荐产品）

未来可以扩展到旅行、学习、健身、家庭活动等，但不属于 MVP 成功的必要条件。

---

## 1.3 Orbit 不是什么

Orbit **不是**：

- 一个换皮 ChatGPT；
- 一个纯聊天机器人；
- 一个活动信息流；
- 一个只根据兴趣推荐内容的推荐系统；
- 一个传统日历 / To-do 工具；
- 一个未经审批就替用户执行高影响动作的全自动 Agent；
- 一个一开始覆盖工作、购物、财务、旅行等所有生活领域的超级 App。

如果某项功能只提升“AI 看起来更聪明”，但不能提升 **Opportunity → Plan → Real-world Action** 的转化，应降低优先级。

---

# 2. 核心产品假设

Orbit MVP 要验证的不是“大模型能不能规划”，而是以下三个产品假设。

## H1：Opportunity Quality

Orbit 能找到比用户自己随手搜索 / 刷信息流更值得关注的生活机会。

判断标准不是“推荐很多”，而是：

> 推荐少，但用户愿意打开、规划和实际参加。

---

## H2：Planning Convenience

从“我可能想做这个”到“这件事已经安排好”，Orbit 明显比用户自己：

- 搜索；
- 查时间；
- 看日历；
- 比距离；
- 算预算；
- 建提醒；

更省事。

---

## H3：Personal Learning

连续使用后，Orbit 能从用户明确偏好、实际选择和反馈中逐渐提高 Opportunity Ranking 与 Plan Ranking 的质量。

如果使用三个月后推荐质量没有明显改善，则 Memory / Learning 没有形成产品价值。

---

# 3. 核心价值闭环

Orbit 的核心闭环固定为：

```text
Opportunity Discovery
        ↓
Personal Ranking
        ↓
Opportunity Delivery
        ↓
Conversation / Constraint Collection
        ↓
Planning
        ↓
Plan Selection
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

所有核心功能都应能映射到这条链路中的某个阶段。

不在闭环中、又不能显著提升闭环质量的功能，原则上不进入 MVP。

---

# 4. 用户与核心场景

## 4.1 MVP 目标用户

优先面向以下用户：

- 城市生活用户；
- 有一定可支配时间与消费预算；
- 经常出现“周末不知道做什么”；
- 有明确或半明确兴趣；
- 愿意尝试演出、电影、体育、展览、本地体验；
- 已经使用系统日历 / 通知；
- 不愿意花大量时间自己做活动搜索与组合规划。

MVP 不要求用户是高频活动达人。

相反，Orbit 更希望解决：

> “我想把生活过得丰富一点，但没有精力一直搜。”

---

## 4.2 核心 Jobs To Be Done

### JTBD-01：主动发现

> 当城市里出现适合我的活动时，我希望有人替我发现，而不是让我自己持续搜索。

### JTBD-02：判断值不值得去

> 当我看到一个活动时，我希望快速知道它为什么适合我、为什么现在值得关注、是否和我的时间 / 距离 / 预算冲突。

### JTBD-03：快速形成方案

> 当我有兴趣时，我不希望再自己查一堆信息，而是希望直接得到几个真正不同、可以执行的方案。

### JTBD-04：安全执行

> 当我选定方案后，希望系统可以替我创建日历、提醒等动作，但执行内容必须透明且由我控制。

### JTBD-05：越用越懂我

> 我希望系统从我的真实选择中学习，但不希望一次点击就被永久“贴标签”。

---

# 5. MVP 用户旅程

## 5.1 首次使用

```text
安装 / 首次打开
    ↓
轻量兴趣初始化
    ↓
必要权限说明
    ↓
进入 Home
    ↓
Orbit 开始构建首批 Opportunity
```

Onboarding 原则：

- 不使用长问卷；
- 初始兴趣 3–8 项即可；
- 用户允许跳过；
- 后续通过真实选择学习；
- 首次使用不能要求用户先维护完整“个人档案”。

---

## 5.2 主动 Opportunity 路径

```text
Orbit 发现机会
    ↓
Opportunity Ranking
    ↓
进入 Home / Notification
    ↓
Opportunity Detail
    ↓
解释：
为什么是现在
为什么是你
    ↓
用户选择：
围绕它聊聊 / 感兴趣 / 不感兴趣 / 暂时静默
    ↓
进入 Task / Conversation
```

---

## 5.3 主动聊天路径

```text
用户打开 Chat
    ↓
表达模糊需求
“周末想看场比赛”
    ↓
Orbit 提取本次条件
    ↓
仅追问必要信息
    ↓
条件足够
    ↓
生成 Plan A / B / C
```

---

## 5.4 Plan → Action 路径

```text
Plan A / B / C
    ↓
用户选择
    ↓
Approval Sheet
    ↓
展示 Proposed Actions
    ↓
用户修改 / 开关 / 批准
    ↓
Execution
    ↓
Action-level Result
```

---

## 5.5 学习路径

```text
Opportunity 行为
Plan 选择
Action 执行
活动后反馈
    ↓
Behavior Signal / Feedback
    ↓
影响未来 Ranking
    ↓
证据稳定
    ↓
Preference Candidate
    ↓
必要时请求用户确认
    ↓
Long-term Preference
```

---

# 6. 信息架构

MVP 保留四个一级入口。

## 6.1 Home

Home 是 **Agent Dashboard**，不是内容 Feed。

建议固定包含：

1. **需要你处理**
   - 等待审批；
   - 需要补充信息；
   - 执行失败需要处理。

2. **为你准备的一件事**
   - Orbit 当前最值得主动推荐的 Opportunity；
   - 强调低频、高质量。

3. **本周安排**
   - 已确认、即将发生的 Plan / Actions。

4. **发现的新机会**
   - 次一级 Opportunity；
   - 可由用户主动浏览。

Home 不追求无限滚动。

---

## 6.2 Chat

Chat 是：

> **Task Constraint Builder + Planning Interface**

而不是独立聊天产品。

Chat 必须能区分：

- 当前 Task；
- 本次已确认条件；
- Orbit 提出的长期偏好建议；
- 未确认信息；
- 当前是否已经具备生成方案的条件。

---

## 6.3 Tasks

Task Center 管理事情的生命周期：

- 需要你处理；
- 进行中；
- 已完成。

Task Detail 至少允许用户理解：

- 这个 Task 为什么存在；
- 当前已经确认什么；
- 系统正在做什么；
- 当前卡在哪里；
- 下一步是谁行动；
- 最终执行了什么。

---

## 6.4 Preferences

Preferences 管理：

- Explicit Preferences；
- Inferred Preferences；
- Profile Evidence；
- 权限；
- 用户控制项。

用户必须可以查看和撤销重要推断。

---

# 7. 核心领域对象

以下对象属于产品语义，不代表最终数据库表一一对应。

---

## 7.1 Opportunity

Opportunity 表示一个：

> **可能值得某个用户在某个时间采取行动的现实世界机会。**

示例：

- 一场利物浦比赛；
- 一场 IMAX 排片；
- 一个周末展览；
- 一个限时城市活动。

核心字段语义：

```text
Opportunity
- id
- domain
- title
- description
- location
- start/end time
- price / price range
- source
- sourceUpdatedAt
- validUntil
- availability
- attributes
- confidence
```

Opportunity 必须有来源和新鲜度概念。

---

## 7.2 Task

Task 是 Orbit 的核心一等实体。

Task 表示：

> **Orbit 正在替用户推进的一件现实目标。**

来源可以是：

- 用户主动聊天；
- Opportunity；
- 旧 Task 重开；
- 未来系统触发。

核心状态语义至少包括：

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

具体技术状态机可在 System Design 中定义，但不能依赖 UI 文案隐式推断状态。

---

## 7.3 Constraint

Constraint 是本次任务中已经确认、会影响方案生成或排序的条件。

示例：

- 本周六晚上；
- 想看利物浦；
- 预算不超过 ¥300；
- 通勤 30 分钟以内；
- 想出去而不是在家。

Constraint 必须区分来源：

```text
UserExplicit
AcceptedSuggestion
OpportunityContext
SystemDerived
```

未经用户确认的长期推断默认不应等同于硬约束。

---

## 7.4 Plan

Plan 是可执行的候选方案，而不是自然语言回答。

核心语义：

```text
Plan
- title
- direction
- summary
- timeline
- estimatedCost
- commute
- opportunityRefs
- constraintsSatisfied
- tradeoffs
- reasons
- sourceRefs
- validUntil
- proposedActions
```

Plan 必须能支持：

- A/B/C 对比；
- 失效；
- 重新校验；
- 审批；
- 执行。

---

## 7.5 ProposedAction

ProposedAction 是：

> **如果用户批准，Orbit 准备对现实系统执行的动作。**

MVP 示例：

- CreateCalendarEvent
- CreateReminder
- UpdateCalendarEvent
- RemoveReminder

未来可能包括：

- Reservation
- TicketPurchase
- RideBooking

但购买 / 支付不属于 MVP。

---

## 7.6 Execution

Execution 是 ProposedAction 的真实执行结果。

必须做到 Action-level Result：

```text
CalendarEvent: success
Reminder: success
Reservation: failed
```

不能只显示“计划执行成功”。

---

## 7.7 Preference

Preference 表示用户明确认可的、跨任务长期有效的偏好。

例如：

- 喜欢英超；
- 喜欢科幻电影；
- 常用单次预算约 ¥300。

Preference 不应因为一次行为自动产生。

---

## 7.8 Profile Evidence / Behavior Signal

表示系统观察到但尚未升级为长期偏好的证据。

示例：

> 最近 30 天 4 次最终选择均为 25 分钟通勤以内。

它可以影响 Ranking，但必须与 Explicit Preference 区分。

---

## 7.9 Feedback

Feedback 表示用户对：

- Opportunity；
- Plan；
- Execution；
- 实际体验；

提供的反馈。

例如：

- 很满意；
- 下次少通勤；
- 不喜欢这种安排；
- 本周不想收到此类推荐。

---

# 8. Opportunity Discovery

## PRD-DISC-001

Orbit 必须支持从外部数据源持续获得候选 Opportunity。

MVP 不要求“覆盖所有城市生活数据”，但要求支持至少几个高质量垂直领域，并可扩展。

---

## PRD-DISC-002

外部数据进入 Orbit 后必须统一为可比较的 Opportunity 语义，而不是每个数据源直接驱动 UI。

---

## PRD-DISC-003

Opportunity 必须保留：

- source；
- updatedAt；
- validUntil 或等价 freshness 信息；
- 数据可信度 / 完整性信息（如适用）。

---

## PRD-DISC-004

数据不足、过期或不确定时，Orbit 不得通过自然语言制造虚假确定性。

---

# 9. Personal Ranking

Personal Ranking 是 Orbit 的核心产品能力之一。

## 9.1 Ranking 目标

Ranking 不回答：

> 用户“喜欢”什么？

而回答：

> **此刻什么最值得这个用户投入真实时间？**

因此至少综合：

```text
Interest Match
Time Fit
Calendar Fit
Distance Fit
Budget Fit
Freshness
Availability
Novelty
User Intent
Historical Acceptance
Confidence
```

---

## 9.2 软 / 硬条件

必须区分：

### Hard Constraint

不满足就不能进入可执行 Plan。

例如：

- 明确时间冲突；
- 明确超过用户本次硬预算；
- 活动已过期；
- 不可到达。

### Soft Preference

影响排序但允许违反。

例如：

- 通常喜欢低通勤；
- 通常更喜欢科幻；
- 偶尔接受新体验。

---

## PRD-RANK-001

一次行为不得直接升级为长期 Hard Constraint。

---

## PRD-RANK-002

Ranking 必须允许解释至少两个问题：

1. 为什么是现在？
2. 为什么推荐给你？

---

## PRD-RANK-003

主动推送阈值必须高于 Home 普通候选展示阈值。

换言之：

> “值得展示”不等于“值得打扰”。

---

# 10. 主动推荐策略

Orbit 的主动性必须受到严格约束。

## PRD-PRO-001：少而准

默认最多主动突出：

> **一件当前最高质量 Opportunity**

不将 Home 变成内容 Feed。

---

## PRD-PRO-002：通知不是默认出口

只有 Opportunity 达到足够高的：

- relevance；
- urgency；
- confidence；

才允许主动 Notification。

---

## PRD-PRO-003：用户可降低主动性

至少支持：

- 不感兴趣；
- 少推荐此类；
- 本周静默；
- 关闭主动推荐。

---

## PRD-PRO-004：静默不等于删除兴趣

用户说“这周不想收到”，不能自动解释为“用户不再喜欢该领域”。

---

# 11. Conversation / Constraint Collection

## PRD-CONV-001

每次新 Task 拥有独立的 Current Constraints。

长期 Preference 不能无条件写入当前 Task。

---

## PRD-CONV-002

Orbit 可以把长期偏好作为 suggestion：

> “你通常预算 ¥300，要沿用吗？”

用户采纳后才进入本次已确认条件。

---

## PRD-CONV-003：最少必要追问

AI 仅在缺失信息会显著改变：

- 可行性；
- Plan 排序；
- Action；

时追问。

---

## PRD-CONV-004

当信息已经足够生成可用方案时，应停止追问并进入 Planning。

---

## PRD-CONV-005

用户必须可以：

- 查看当前条件；
- 删除条件；
- 修改条件；
- 清空本次条件。

---

# 12. Planning

## PRD-PLAN-001

默认生成最多 3 个候选 Plan。

候选方案必须存在明确差异，不得只是措辞不同。

MVP 建议方向：

- 最匹配；
- 更轻松；
- 新体验。

---

## PRD-PLAN-002

每个 Plan 至少说明：

- 做什么；
- 什么时候；
- 预算；
- 通勤；
- 为什么适合；
- 关键 trade-off；
- 数据来源 / 时效；
- 后续可执行 Actions。

---

## PRD-PLAN-003

Planning 必须基于结构化事实完成可行性校验。

LLM 不应独自决定：

- 时间是否冲突；
- 是否超过硬预算；
- 是否过期；
- 是否具备执行权限。

---

## PRD-PLAN-004

Plan 必须具有 `validUntil` 或等价失效机制。

---

## PRD-PLAN-005

用户可以：

- 选择 Plan；
- 收藏；
- 换方向重新生成；
- 返回 Chat 修改条件。

---

# 13. Approval

## PRD-APPROVAL-001

对外部系统产生副作用的动作，必须在用户批准后执行。

MVP 包括：

- Calendar write；
- Reminder creation / modification。

---

## PRD-APPROVAL-002

Approval UI 必须展示：

- 将执行哪些动作；
- 每个动作的关键参数；
- 哪些动作已开启；
- 用户可修改的字段。

---

## PRD-APPROVAL-003

用户必须可以：

- 批准；
- 关闭单个 Action；
- 修改可编辑参数；
- 拒绝；
- 稍后处理。

---

## PRD-APPROVAL-004

在 Approval 与实际 Execution 之间，如果关键数据已经过期，Orbit 必须重新校验。

---

# 14. Execution

## PRD-EXEC-001

Execution 必须按 Action 粒度记录状态。

---

## PRD-EXEC-002

部分成功是合法状态。

例如：

```text
Calendar：失败（未授权）
Reminder：成功
```

UI 不得把这种情况显示成完全成功或完全失败。

---

## PRD-EXEC-003

失败必须尽量告诉用户：

- 哪个 Action 失败；
- 原因；
- 是否可重试；
- 是否需要用户处理。

---

## PRD-EXEC-004

不得为了“看起来可靠”而无限增加兜底、重试和隐藏失败。

MVP 优先保证：

- 主流程；
- 常见网络错误；
- 常见权限错误；
- 常见数据过期；

正确、透明、可恢复。

---

# 15. Memory / Learning

Memory 的目标是：

> **提升未来 Ranking 与 Planning，而不是尽可能多地保存用户信息。**

---

## 15.1 四类信息必须区分

### A. Explicit Preference

用户明确确认：

> “我喜欢科幻电影。”

### B. Inferred Preference

系统根据重复行为形成推断：

> “用户似乎偏好低通勤。”

### C. Behavior Signal

单次或低置信度行为：

> “这一次选择了 12 分钟通勤方案。”

### D. Task Feedback

针对某次任务：

> “下次少通勤。”

---

## PRD-MEM-001

Behavior Signal / 单次 Feedback 默认只影响未来 Ranking，不直接修改长期 Preference。

---

## PRD-MEM-002

Inferred Preference 必须有：

- evidence；
- confidence；
- provenance；
- 可撤销能力。

---

## PRD-MEM-003

重要长期推断达到足够证据后，可以请求用户确认：

> “你最近经常选择 20 分钟以内的活动，要把低通勤作为默认偏好吗？”

---

## PRD-MEM-004

用户删除某项推断后，该推断不得继续作为默认 Ranking 依据。

---

## PRD-MEM-005

Memory 不得成为当前 Task 的隐藏 Hard Constraint。

---

# 16. 权限与用户控制

MVP 重点权限：

- Calendar；
- Notification；
- Location（可选 / 渐进式）。

原则：

- 按需请求；
- 解释用途；
- 未授权时功能降级，而不是阻断全部产品；
- 用户能看到当前授权状态。

例如 Calendar 未授权时：

> Plan 仍可生成，但 Calendar Action 无法执行。

---

# 17. Freshness / Trust

Orbit 处理的是真实世界动态数据，因此 Freshness 属于产品核心能力，而不是后台实现细节。

## PRD-TRUST-001

用户能看到与行动决策相关的数据新鲜度。

例如：

- 2 小时前更新；
- 有效至周六 18:00。

---

## PRD-TRUST-002

重要数据过期后必须重新校验。

---

## PRD-TRUST-003

外部事实来源与 AI 推理应在系统内部可区分。

LLM 负责解释，不负责“创造”票价、场次、营业状态等事实。

---

# 18. Failure / Empty States

MVP 必须设计以下常规边界场景。

## 18.1 没有高质量 Opportunity

Orbit 应允许：

> “这周没有特别值得主动打扰你的事情。”

而不是为了活跃度强行推荐。

---

## 18.2 External Data Failure

- 保留已知信息；
- 明确当前无法校验；
- 不生成虚假的确定性 Plan。

---

## 18.3 Calendar 未授权

- 不阻塞 Planning；
- Approval 中明确 Calendar Action 不可用；
- 引导用户授权。

---

## 18.4 Plan 过期

Plan 标记失效并允许：

- 重新校验；
- 重新生成。

---

## 18.5 Execution 部分失败

显示 Action-level Result，并提供针对失败 Action 的下一步。

---

# 19. MVP Scope

## 19.1 MUST

MVP 必须具备：

- Lightweight onboarding；
- Explicit preference；
- Opportunity ingestion；
- Opportunity freshness；
- Personal ranking；
- Home Agent Dashboard；
- Opportunity Detail；
- Why now / Why you；
- Chat constraint collection；
- Long-term preference suggestions；
- Plan A/B/C；
- Plan feasibility validation；
- Approval；
- Calendar integration；
- Reminder；
- Task lifecycle；
- Action-level execution result；
- Basic feedback；
- Behavior Signal；
- Inferred preference evidence；
- User-controlled deletion / silence；
- Basic analytics。

---

## 19.2 SHOULD

条件允许时：

- Notification；
- 多种 Opportunity source；
- Location-based commute；
- Plan 收藏；
- Plan regeneration directions；
- Feedback after activity；
- Preference candidate confirmation。

---

## 19.3 NOT IN MVP

明确不做：

- 自动买票；
- 自动支付；
- 银行 / 财务；
- 工作 Agent；
- 邮件工作流；
- 自动购物；
- 长途复杂旅行规划；
- 酒店 / 航班交易闭环；
- 无边界 Browser Agent；
- 多 Agent 为了架构而多 Agent；
- 完整社交网络；
- UGC 内容社区；
- 无限活动 Feed；
- 广告推荐系统。

---

# 20. AI 产品边界

AI 是 Orbit 的 reasoning interface，不是所有业务逻辑的 owner。

## AI 适合负责

- 用户意图理解；
- Constraint extraction；
- 判断是否需要追问；
- Query planning；
- 候选 Plan composition；
- Plan explanation；
- Why now / Why you 的自然语言表达；
- Feedback interpretation。

## AI 不应单独负责

- Calendar conflict truth；
- Price truth；
- Travel time truth；
- Opportunity freshness；
- Permission truth；
- Action execution truth；
- Task state truth；
- 最终数据持久化语义。

原则：

> **AI 可以提出判断，但影响真实执行的关键事实必须能够被确定性系统验证。**

---

# 21. App 产品原则

## APP-01：状态优先于聊天文本

UI 应优先表达：

- Task state；
- Constraint；
- Plan；
- Action；
- Execution result；

而不是把所有信息埋在聊天历史里。

---

## APP-02：复杂度只在需要时出现

默认界面保持轻量。

详细来源、证据、权限、失败原因在对应场景按需展开。

---

## APP-03：Agent 行为透明

用户随时能理解：

- Orbit 为什么推荐；
- Orbit 正在做什么；
- Orbit 准备执行什么；
- Orbit 已经执行什么。

---

## APP-04：主流程简单

产品实现与交互优先保证：

> 主流程 + 常规高价值边界情况。

避免为了低概率极端场景堆积大量兜底逻辑，使主流程难以理解和维护。

---

# 22. 成功指标

MVP 不以 DAU 作为第一核心指标。

## 22.1 North-star Candidate

### Opportunity → Real-world Action Rate

```text
产生 Opportunity
    ↓
用户实际批准并形成现实安排
```

这是 Orbit 是否真正创造生活价值的直接指标。

---

## 22.2 Funnel

至少跟踪：

```text
Opportunity Impression
→ Opportunity Open
→ Start Planning
→ Plan Generated
→ Plan Selected
→ Approval
→ Execution Success
→ Activity Feedback
```

---

## 22.3 核心指标

### Discovery

- Opportunity Open Rate
- Opportunity Dismiss Rate
- Silence Rate

### Planning

- Opportunity → Planning Rate
- Chat → Plan Generation Rate
- Average Necessary Clarification Turns
- Plan Selection Rate

### Execution

- Plan → Approval Rate
- Execution Success Rate
- Partial Failure Rate

### Quality

- Positive Feedback Rate
- Regeneration Rate
- “Not Interested” Rate

### Learning

- Returning User Opportunity Acceptance Rate
- Acceptance Rate improvement over usage tenure
- Explicit Preference confirmation rate
- Inferred Preference deletion rate

---

# 23. 产品质量门槛

MVP 上线前至少需要验证：

## Q1

用户是否能在首次使用 5 分钟内理解：

> Orbit 能替我做什么？

---

## Q2

首批 Opportunity 是否能明显避免“泛推荐”。

---

## Q3

从打开 Opportunity 到得到 Plan，用户是否无需进行长对话。

---

## Q4

三个 Plan 是否真的具有决策价值，而不是文案差异。

---

## Q5

Approval 是否让用户清楚知道系统将执行什么。

---

## Q6

任何 Action 失败时，用户是否仍能知道实际发生了什么。

---

## Q7

一次反馈是否不会悄悄改变长期画像。

---

# 24. MVP 验收场景

后续 M0/M1/M2 Work Order 应逐步覆盖以下端到端场景。

## Scenario A：主动发现赛事

```text
用户已确认喜欢英超
→ Orbit 获取周六比赛
→ Calendar 周六晚空闲
→ Opportunity 排名达到展示阈值
→ Home 展示
→ Why now / Why you
→ 用户围绕它聊聊
→ Opportunity context 进入 Task
→ 补充 1~2 个必要条件
→ 生成 3 个 Plan
→ 选择 Plan
→ Approval
→ Calendar + Reminder
→ Execution Result
→ Feedback
```

---

## Scenario B：用户主动提出周末需求

```text
用户：
“这周末想出去玩，不想太远”

→ 提取：
本周末
外出
低通勤倾向

→ Orbit 仅追问真正必要信息
→ 查询 Opportunity
→ 生成方案
→ 用户完成安排
```

---

## Scenario C：长期偏好只是建议

```text
长期常用预算 ¥300
→ 新 Task
→ Orbit 显示“采用常用预算 ¥300”
→ 用户未采用
→ 本次 Task 不得自动拥有 ¥300 硬预算
```

---

## Scenario D：推断偏好

```text
连续多次选择低通勤 Plan
→ 创建低通勤 Inference
→ 只影响 Ranking
→ Preference 页面显示证据
→ 用户可以删除
```

---

## Scenario E：部分执行失败

```text
用户批准 Calendar + Reminder
→ Calendar 未授权
→ Reminder 成功
→ Result：
Calendar failed
Reminder success
→ 用户可以处理 Calendar 权限
```

---

# 25. 产品决策原则

当后续存在多个方案时，按以下顺序做决策：

1. **是否提升真实生活行动率？**
2. **是否提升 Opportunity / Plan 质量？**
3. **是否减少用户做规划的成本？**
4. **是否增加信任与控制感？**
5. **是否帮助 Learning Flywheel？**
6. 最后才考虑“AI 能力是否看起来更酷”。

---

# 26. Orbit 的长期竞争力方向

Orbit 不应把护城河建立在：

- Prompt；
- 单个模型；
- Chat UI；
- Calendar tool call；
- 通用 Agent framework。

真正应逐步积累的是：

```text
Personal Taste Model
        ×
Opportunity Graph
        ×
Decision / Feedback History
        ↓
Personal Opportunity Ranking
        ↓
Planning
        ↓
Real-world Actions
        ↓
More Decision Data
        ↺
```

目标是：

> 用户使用时间越长，Orbit 越能判断“什么值得这个人现在去做”。

这是后续架构与 RoadMap 应共同服务的核心资产。

---

# 27. RoadMap 约束

后续 RoadMap 的每个 Milestone 都必须回答：

1. 它补齐核心闭环中的哪一段？
2. 用户能新增完成什么真实场景？
3. 它产生什么可用于未来 Ranking / Learning 的数据？
4. App / Backend / AI 是否形成完整端到端能力？
5. 是否存在为了未来假设而过度设计？

任何 Milestone 不应只完成：

> “Backend 有了，但 App 还不能用”

或：

> “AI 能生成了，但没有真实领域状态”

而应尽量形成可以验收的垂直切片。

---

# 28. Product Source of Truth

从本版本开始，以下概念应作为项目统一语言：

- Opportunity
- Personal Ranking
- Task
- Constraint
- Plan
- ProposedAction
- Approval
- Execution
- Preference
- Inferred Preference
- Behavior Signal
- Feedback

后续 App、Backend、AI、测试、设计文档和 Work Order 应优先复用这些术语，不应在各层自行创造语义重复但名称不同的对象。

如果产品方向发生变化，应先修改 Product Requirements，再更新 RoadMap 与对应 System Design。

---

# 29. 当前结论

Orbit MVP 的目标不是证明：

> AI 可以成为一个很聪明的生活聊天助手。

而是证明：

> **Orbit 可以持续找到值得用户做的事情，并低摩擦地把其中一部分变成真实生活安排。**

MVP 成败最终取决于：

1. Opportunity 够不够好；
2. Personal Ranking 够不够准；
3. Planning 够不够省事；
4. Execution 够不够可靠；
5. Feedback 是否真的让下一次更好。

只要这五件事形成闭环，后续再扩展更多生活领域才有意义。

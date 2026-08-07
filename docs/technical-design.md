# Orbit 技术方案（模块化交付）

> 关联产品需求：[requirements.md](requirements.md)。
>
> 基线架构：`app` 为 KMP 客户端，`backend` 为 Ktor 权威任务系统，`ai` 为 Kotlin/JVM 规划模块。AI 只能提出结构化计划；所有状态变化、审批与外部副作用由后端控制。

## 0. 模块总览与交付顺序

| 编号 | 模块 | 用户价值 | MVP 优先级 |
| --- | --- | --- | --- |
| M1 | 偏好档案 | 让计划真正个性化，而非泛推荐 | P0 |
| M2 | 创建与恢复计划任务 | 用户用一句话开始一项可持续任务 | P0 |
| M3 | 候选计划生成与校验 | 获得可行、有亮点的 Plan A/B/C | P0 |
| M4 | 可编辑审批与执行 | 用户保有对日历/提醒写操作的最终控制权 | P0 |
| M5 | 任务时间线与断线恢复 | 可追溯、可恢复而非一次性聊天 | P0 |
| M6 | 领域插件 | 可扩展到赛事、电影、活动等数据与动作 | P0（Fake） |
| M7 | 主动发现与周末简报 | 不主动刷动态，也能获得机会 | P1 |
| M8 | 金融信息提醒 | 只读、可溯源的信息提醒 | P2 |

M1–M6 完成后即构成首个本地闭环。M7 仅在该闭环稳定后实现；M8 不进入首版开发范围。

---

## M1. 偏好档案模块

### 目标

将用户的兴趣和现实限制转换为后续所有计划可使用的结构化约束。用户无需重复说明“喜欢什么”和“哪些时间绝不能安排”。

### 主流程

```text
App：首次引导/偏好页编辑
  → App：本地校验并保存草稿
  → Backend：保存新的 Preference Profile（version + 1）
  → Backend：返回规范化档案
  → App：更新本地缓存与“最后生效时间”

后续创建任务时
  → Backend：复制当前档案为不可变 preferenceSnapshot
  → AI：仅接收该快照，不读取可变的当前偏好
```

### App 能力

- 三步引导：兴趣与优先级、现实约束、可选的权限说明。
- 偏好编辑页：标签多选、优先级排序、预算输入、通勤范围、不可用时间段、惊喜频率。
- 变更先写 SQLDelight `preference_draft`，成功后覆盖 `preference_cache`。
- 编辑时展示“仅影响新生成的计划”；待审批任务不被静默修改。

### 后端能力

- `PreferenceService`：校验、版本化保存和返回当前档案。
- PostgreSQL `preferences` 表：`user_id`、`version`、`payload(JSONB)`、`updated_at`。
- 规范化规则：预算必须大于零、通勤范围为 5–120 分钟、兴趣域至少一个、不可用时间段格式合法。
- 创建任务时由 `TaskService` 写入档案快照，避免任务中途用户改偏好改变已有计划语义。

### AI 能力

- 无需模型调用。
- 定义 `PlanningPreferenceSnapshot` 数据结构，并区分：
  - **硬约束**：预算上限、不可用时间、避开项、通勤上限；不可被模型覆盖。
  - **软偏好**：球队、电影类型、活动类型、惊喜频率；仅用于排序与组合。

### 接口

| 方法 | 路径 | 请求/响应重点 |
| --- | --- | --- |
| `GET` | `/v1/preferences/me` | 当前档案及 `version` |
| `PUT` | `/v1/preferences/me` | 完整档案、`expectedVersion`；返回新版本 |

### 常规边界 Case

| 情况 | 处理 |
| --- | --- |
| 无网络时编辑 | 保存本地草稿，不假装已同步；恢复网络后提示用户提交 |
| 两台设备同时编辑 | 后端返回 `PREFERENCE_VERSION_CONFLICT`；App 展示服务端版本与本地变更，用户选择覆盖或重新编辑 |
| 用户未选择兴趣 | 引导页阻止完成；设置页保存失败并指出字段 |
| 用户关闭惊喜 | Planner 不生成 `surprise=true` 的候选项 |
| 用户改偏好后打开旧任务 | 旧任务仍显示创建时的快照，不重新计算 |

### 验收

- 用户可完成最小偏好设置并在首页看到已保存状态。
- 不合法预算、通勤或空兴趣无法提交。
- 更新偏好后，新任务使用新版本；既有任务的快照不变。
- 版本冲突、离线草稿均有可理解的 UI 提示。

---

## M2. 创建与恢复计划任务模块

### 目标

把自然语言需求变成可持久化、可恢复的任务，而不是一个等待模型响应的 HTTP 请求。

### 主流程

```text
App：输入“周末安排一下，想看利物浦，预算 300”
  → App：生成 clientRequestId + Idempotency-Key
  → Backend：CreateTask 保存 CREATED 任务、偏好快照、时间范围
  → Backend：立即返回 taskId
  → App：跳转任务详情并显示状态步骤
  → Backend Worker：领取任务，PLANNING → GATHERING_CONTEXT → VALIDATING
  → App：轮询/订阅任务事件，直到候选计划或失败结果出现
```

### App 能力

- 创建页包含自由文本、时间范围和快捷条件 Chips。
- 发送后按钮防连点；超时重试复用同一个幂等键。
- 任务详情页将 `CREATED/PLANNING/GATHERING_CONTEXT/VALIDATING` 映射为可见进度文案。
- 首版通过前台轮询获取状态；接口设计保留事件流，后续替换为 WebSocket/SSE。
- SQLDelight 缓存任务摘要和详情，冷启动先显示缓存后同步。

### 后端能力

- `CreateTaskUseCase` 在一个事务中：
  1. 校验用户、时间范围与请求长度；
  2. 读取当前偏好；
  3. 插入 `tasks(status=CREATED)` 与 `task_events(TASK_CREATED)`；
  4. 以 `(user_id, idempotency_key)` 去重；
  5. 投递任务给同 JVM Coroutine Worker。
- `TaskWorker` 可恢复处理非终态任务；进程启动时扫描 `CREATED/RETRYING`。
- 每次状态变化同事务写入不可变 `task_events`；`tasks.version` 用乐观锁防并发覆盖。

### AI 能力

- `IntentExtractor`：从文本提取计划时间范围、兴趣、预算、同行、明确禁止项等。
- 输出仅为 `ExtractedIntent` JSON，不产生任何外部动作。
- 提取结果只可**收紧**硬约束，不能扩大预算、占用禁止时段或清除避开项。
- 模型不可用时使用规则解析器处理时间范围、预算等常见表达，仍继续后续流程。

### 接口

| 方法 | 路径 | 请求/响应重点 |
| --- | --- | --- |
| `POST` | `/v1/tasks` | `requestText`、`timeRange`、`clientRequestId`；返回 `taskId`、`status` |
| `GET` | `/v1/tasks/{taskId}` | 任务、候选项、审批、时间线摘要 |
| `GET` | `/v1/tasks/{taskId}/events?after=` | 增量状态事件；MVP 可用轮询 |
| `POST` | `/v1/tasks/{taskId}/retry` | 仅失败且可重试的任务 |

### 常规边界 Case

| 情况 | 处理 |
| --- | --- |
| 用户连续点击生成 | 同一幂等键返回同一任务；不同键视为用户明确创建新任务 |
| App 在任务运行时被杀死 | 任务留在后端；下次打开按 `updated_at` 同步未完成任务 |
| 文本为空 | App 本地拦截；后端仍校验并返回 `VALIDATION_ERROR` |
| 时间范围已过 | 返回可编辑错误，建议选择下一个周末 |
| Worker 中途崩溃 | 任务记录最后事件；重启后从安全检查点恢复或标为可重试 |
| 用户取消任务 | 终态前可取消；已进入执行则取消未开始动作，已完成动作不静默回滚 |

### 验收

- 创建任务后 1 秒内拿到 `taskId`，不等待完整规划。
- 重复网络提交只产生一个任务。
- 强杀/重启客户端后能重新查看进行中与待审批任务。
- 后端重启后可恢复未终态任务，且状态线完整。

---

## M3. 候选计划生成与可行性校验模块

### 目标

将“喜欢什么”与“实际上能不能去”结合，产出 2–3 个可比较的候选方案，而不是一段泛泛的推荐文字。

### 主流程

```text
Backend Worker：读取 Task + preferenceSnapshot + ExtractedIntent
  → Backend：调用只读 Sports / Movies / Calendar 插件
  → AI：根据受限上下文生成结构化 PlanProposal
  → Backend：规则引擎校验、剔除不合格项、重新排序
  → Backend：保存 PlanOption + source/freshUntil + 时间线事件
  → Backend：任务转为 AWAITING_APPROVAL 或 COMPLETED(NO_FEASIBLE_PLAN)
  → App：展示 Plan A/B/C 与推荐依据
```

### App 能力

- 任务详情显示步骤化加载：理解偏好、查询赛事/电影、检查时间与预算。
- 计划比较页支持 Plan A/B/C 切换；每张卡展示：亮点、时间轴、预算、通勤、推荐理由、来源、待执行动作。
- 计划详情把理由分为“符合你的偏好”“来自活动数据”“新体验建议”，不把模型推断伪装成事实。
- `重新生成` 必须让用户选择调整方向（更省钱/更轻松/更多新鲜感/赛事优先），并创建新任务，不篡改旧任务。
- 无合格方案时展示冲突原因与可点击的调整项。

### 后端能力

- `PlanningOrchestrator`：依序收集只读插件结果、调用 AI、再运行确定性校验。
- `FeasibilityValidator`：校验时间重叠、不可用时段、预算、通勤、避开项、数据时效、最小休息间隔。
- `PlanRanker`：用透明权重排序；保存得分分解供解释，但不必在 UI 展示裸分。
- 持久化 `plan_options(rank, payload, sources, fresh_until)`；每个数据来源保存获取时间。
- 若只剩一项合格计划，返回一项并清楚说明；若零项，写 `NO_FEASIBLE_PLAN` 与失败原因。

### AI 能力

- `Planner` 接收受限 `PlanningContext`，只生成 JSON 格式的 `PlanProposal`：

```text
PlanProposal
- extractedConstraints
- candidateItems[]: title, time window, domain, estimated budget, rationale
- uncertaintyNotes[]
- requestedReadCapabilities[]
```

- AI 不调用插件，不读取数据库，不决定排名，不生成可执行 API 参数。
- `ProviderAdapter` 支持 Stub 和真实模型；输出必须经 kotlinx.serialization + JSON Schema 校验。
- 当数据不足或不确定时，输出 `uncertaintyNotes`；禁止编造活动、电影或赛程来源。
- 可解释性模板从结构化字段生成，减少“事后编理由”。

### 接口

| 方法 | 路径 | 请求/响应重点 |
| --- | --- | --- |
| `GET` | `/v1/tasks/{taskId}` | `planOptions[]`、`validationSummary`、`resultType` |
| `POST` | `/v1/tasks` | `regenerationHint` 可选：用于新任务的排序偏好 |
| `GET` | `/v1/tasks/{taskId}/sources` | 可选；返回候选项引用的来源与时效 |

### 常规边界 Case

| 情况 | 处理 |
| --- | --- |
| 无日历权限/无忙碌时间 | 允许生成，但标记“未检查已有日程”；审批前再次提示 |
| 插件无数据/超时 | 使用剩余插件继续；若关键数据缺失，降级为少量候选或无可行方案 |
| 所有候选超预算 | 返回 `NO_FEASIBLE_PLAN` 和“提高预算/缩短通勤/更换时间”建议 |
| 数据已过期 | 不将该候选作为默认可执行方案；标记过期并要求重新刷新 |
| 模型输出非 JSON/缺字段 | 记录 AI Trace，有限重试；仍失败则使用规则化模板或任务失败 |
| 模型建议违反硬约束 | 后端无条件剔除，记录 `PLAN_REJECTED_BY_RULE` 事件 |
| 惊喜候选 | 必须满足硬约束、显式标记并不排在最高匹配方案前（除非用户选择“更多新鲜感”） |

### 验收

- 标准样例可返回 2–3 个不冲突且预算合规的候选项。
- 候选项均显示时间、预算、至少一个理由和数据来源。
- 任一违反硬约束的 AI 候选无法进入审批。
- 无可行方案时不编造活动，并明确给出至少一个可调整条件。
- Stub 模型和真实模型均通过同一 `PlanProposal` 校验路径。

---

## M4. 可编辑审批与执行模块

### 目标

用户批准具体的副作用，而不是“相信 Agent 会做好”。同一审批即使被重复提交，也只能执行一次。

### 主流程

```text
App：选择 Plan A，点击“安排这个计划”
  → Backend：根据计划生成 Approval(v1) + 待执行 Action 草稿
  → App：打开审批抽屉，允许编辑标题/时间/提醒
  → App：POST 决策（approvalId + version + editablePayload）
  → Backend：事务校验版本、权限与参数，写 APPROVED/REJECTED
  → Backend：APPROVED 时创建 Actions，Task → EXECUTING
  → Plugin Gateway：按持久化幂等键执行 Calendar / Notification
  → Backend：写 externalRef、Action 结果、时间线和最终 Task 结果
  → App：显示成功、部分成功或失败，并刷新时间线
```

### App 能力

- 审批抽屉固定显示动作列表和副作用说明。
- 支持编辑日历标题、开始/结束时间、提醒时间；每项动作可单独关闭。
- 只有至少保留一个动作才可点击“批准并安排”。
- 批准后禁用重复点击，保持当前审批版本；返回版本冲突时刷新详情并要求用户重新确认。
- 支持拒绝原因、稍后处理和重试失败动作。
- 任务完成页区分：全部成功、部分成功、失败；显示外部引用/本地结果。

### 后端能力

- `ApprovalService.prepare`：从 `PlanOption` 派生明确 `Approval` 与 `ProposedAction`，不执行操作。
- `ApprovalService.decide`：单事务校验 `userId`、`task.status`、`approval.version`、动作 Schema、权限和计划数据时效。
- `ActionService`：每个动作持有唯一 `idempotency_key`；执行前先查询既有成功结果；执行结果单独落库。
- 执行顺序：先 Calendar，再 Notification；Calendar 失败时默认不执行依赖它的提醒，除非提醒被明确配置为独立动作。
- 对每个副作用记录 `external_ref`、请求摘要、结果摘要和时间；不能只以任务级成功/失败掩盖部分失败。

### AI 能力

- 不参与批准决策，也不接收“批准”作为执行指令。
- 提供 `ActionExplanation` 的固定文案模板，例如“创建日历事件会修改你的设备/账户日程”。
- 若用户编辑文本包含新约束（如改为另一天），AI 可在**新任务**中重新规划；MVP 不在审批提交中让 AI 重写计划。

### 接口

| 方法 | 路径 | 请求/响应重点 |
| --- | --- | --- |
| `POST` | `/v1/tasks/{taskId}/approvals` | 选择计划后创建/获取待审批对象 |
| `POST` | `/v1/tasks/{taskId}/approvals/{approvalId}/decision` | `decision`、`version`、`editablePayload`、`Idempotency-Key` |
| `POST` | `/v1/tasks/{taskId}/actions/{actionId}/retry` | 仅允许重试可重试的失败动作 |
| `GET` | `/v1/tasks/{taskId}` | 返回 approvals、actions 及最终结果 |

### 常规边界 Case

| 情况 | 处理 |
| --- | --- |
| 连续点击批准/网络重试 | 相同幂等键返回同一个 Action 结果；绝不重复创建事件 |
| 两设备同时批准 | 首个有效审批成功；后一个收到 `APPROVAL_ALREADY_DONE` 或最终结果 |
| 用户编辑后与日历冲突 | 后端重新跑硬规则，返回字段级冲突信息，不执行 |
| 审批期间活动数据过期 | 拒绝执行并提示刷新计划；不使用过期活动写日历 |
| Calendar 成功、Notification 失败 | 任务为 `COMPLETED_WITH_WARNINGS` 展示态；可只重试通知 |
| 用户拒绝 | `Approval=REJECTED`，任务进入终态但保留计划和拒绝原因 |
| 系统日历权限撤销 | Calendar Action 失败为 `ACTION_NOT_ALLOWED`，保留可重试状态 |

### 验收

- 未批准前 Fake Calendar/Fake Notification 不产生任何记录。
- 批准后每个启用动作只执行一次，重复请求不会重复创建。
- 用户修改时间会被重新校验，冲突时得到字段级错误。
- 可在 UI 中分别观察 Calendar 成功、Notification 失败的部分成功结果。
- 审批事件、参数版本、幂等键和执行结果均出现在时间线/审计中。

---

## M5. 任务时间线、通知与断线恢复模块

### 目标

把 Agent 变成可追溯的长期任务系统：用户随时能知道它做了什么、停在哪里，以及恢复后会发生什么。

### 主流程

```text
Backend：任一状态变化/插件调用/审批/动作结果
  → 事务写入 TaskEvent
  → EventPublisher 推送或标记增量游标
  → App 前台轮询/订阅拉取 after=cursor 的新事件
  → App 写 SQLDelight，更新任务详情与时间线
  → Android：到达 AWAITING_APPROVAL 时显示通知
  → 用户点通知，以 taskId 深链回到审批页
```

### App 能力

- 时间线按发生顺序展示可读摘要，不展示内部 Prompt、密钥或完整第三方原文。
- 每次同步保存 `sync_cursor`；网络恢复后按游标增量拉取。
- WorkManager 负责未完成任务的后台同步和通知后状态刷新。
- 通知点开后校验最新状态：已处理则显示完成结果，不显示过期审批卡。
- 缓存优先渲染，顶部显示“正在同步”或“离线数据”的非阻断状态。

### 后端能力

- `TaskEvent` 为 append-only：`type`、`summary`、`metadata`、`occurredAt`、`sequence`。
- 所有状态转移和动作落库时同事务追加事件，避免状态成功但时间线缺失。
- `TaskEventQueryService` 按 user/task 验权并提供 cursor 分页。
- `RecoveryWorker`：启动和定时扫描非终态任务、超时插件调用、待重试动作；每次恢复也写事件。
- Notification 仅对 `AWAITING_APPROVAL` 的新版本发送，去重键为 `(taskId, approvalVersion, channel)`。

### AI 能力

- 无需参与状态同步。
- 仅为用户可见的计划理由提供结构化摘要；不能自行编写“已执行”之类的运行事实。

### 接口

| 方法 | 路径 | 请求/响应重点 |
| --- | --- | --- |
| `GET` | `/v1/tasks?status=` | 首页任务摘要、待审批数量 |
| `GET` | `/v1/tasks/{taskId}/events?after=&limit=` | 增量时间线 |
| `POST` | `/v1/devices/push-tokens` | Android Push Token 注册（MVP 可先用本地通知） |

### 常规边界 Case

| 情况 | 处理 |
| --- | --- |
| 事件重复下发 | App 以 `eventId` 去重；后端游标保证稳定顺序 |
| 用户离线数天 | 冷启动分页同步；不因大量事件阻塞首页 |
| 推送到达时任务已处理 | 深链获取最新任务，显示最终状态 |
| Worker 重复恢复 | 数据库状态版本和动作幂等键保证无重复执行 |
| 事件元数据含敏感内容 | 存储前脱敏；用户 API 只返回白名单字段 |

### 验收

- 主流程中的每次关键转换均在时间线可见且顺序正确。
- App 重启后能恢复最近任务、待审批项和时间线。
- 相同状态变化不产生重复通知。
- 后端重启后恢复任务不会重复 Calendar/Notification Action。

---

## M6. 领域插件模块

### 目标

把赛事、电影、日历和通知作为可替换能力接入；新增领域时不重写 Planner、任务状态机或审批 UI。

### 主流程

```text
Backend：根据任务所需 capability 查询 Plugin Registry
  → Plugin Gateway：校验 capability、参数 Schema、权限和限流
  → Read Plugin：返回标准化 Opportunity + Source
  → AI：使用只读 Opportunity 生成候选计划

审批后
  → Backend：根据 PreparedAction 找到 Write Plugin
  → Plugin Gateway：携带 idempotencyKey 执行
  → Backend：持久化外部引用与结果
```

### App 能力

- 不感知插件实现，只显示标准化域名、来源、更新时间和权限提示。
- 计划卡使用统一 `PlanItem` 模型，避免为 sports/movies 各写一套审批页。
- 偏好页按插件能力显示开关，但不能将关闭开关误解为删除历史计划。

### 后端能力

- `PluginRegistry`：注册 capability、Schema、是否只读、审批策略、数据时效策略。
- `PluginGateway`：统一超时、重试、限流、审计和结果脱敏。
- `Opportunity` 统一字段：`id`、`domain`、`title`、`timeRange`、`location`、`estimatedCost`、`source`、`freshUntil`。
- `PreparedAction` 统一字段：`type`、`payload`、`approvalRequired`、`idempotencyKey`。
- MVP 仅实现固定 Fixture 的 Fake 插件，保证本地 Demo 可重复；真实接入在 P1 后替换 Adapter。

### AI 能力

- 只看到经过网关标准化的 `Opportunity`，不直接处理原始网页/第三方响应。
- 通过 capability 描述知道“可查询什么”，但无权限调用 executor。
- 对不存在的 capability 只能输出“不足以完成”，不得自造工具名。

### 接口

插件在 MVP 为后端内部接口，不暴露给客户端。客户端通过任务接口读取来源和结果。开发调试可额外提供：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/v1/debug/plugins` | 仅开发环境：查看已注册插件与 capability |
| `POST` | `/v1/debug/plugins/{id}/fixtures/reset` | 仅开发环境：重置 Fake 数据 |

### 常规边界 Case

| 情况 | 处理 |
| --- | --- |
| 未注册 capability | 规划失败为可理解的 `PLUGIN_UNAVAILABLE`，不尝试任意请求 |
| 插件超时 | 记录 PluginCall；遵循按插件配置的有限重试与降级策略 |
| 返回字段不合 Schema | 隔离该次结果，标记插件故障，不交给 AI |
| 真实来源被注入指令 | 原始文本视为不可信数据，不能改变系统策略或调用范围 |
| 写插件不支持幂等 | 不可接入审批执行链；必须在 Adapter 层实现外部引用映射/去重 |

### 验收

- FakeSports 和 FakeMovies 可被 Planner 使用，FakeCalendar/FakeNotification 仅在批准后执行。
- 每次插件调用都有审计记录：输入摘要、耗时、状态、来源与结果摘要。
- 新增一个 Fake 插件不需要修改 Task State Machine 或审批 UI。
- 不合法插件输出无法到达 AI 或动作执行层。

---

## M7. 主动发现与周末简报模块（P1）

### 目标

在用户不主动搜索时，发现可能值得安排的机会；仍将决定和执行权交给用户。

### 主流程

```text
Backend Scheduler：每周指定时间为符合条件用户创建 Discovery Task
  → Backend：查询只读活动插件 + 用户偏好
  → AI：生成最多 3 个机会摘要，不创建 Action
  → Backend：规则过滤、保存 Discovery Result
  → App：首页“发现的新机会”展示预填充任务卡
  → 用户点击“生成计划”后才进入 M2/M3/M4
```

### App 能力

- 首页展示有限数量（最多 3 个）的机会卡，包含来源/时间/为什么适合你。
- 用户可收藏、忽略或点击“围绕这个安排”；忽略会形成轻量负反馈。
- 不存在“自动加入日历”按钮。

### 后端能力

- `DiscoveryScheduler` 按用户时区调度并限频（默认每周一次）。
- 只对允许主动发现、偏好完整且插件数据可用的用户运行。
- 发现结果与任务分开存储，直到用户点击才创建 `Task`。

### AI 能力

- `DiscoveryRanker` 只输出机会摘要、原因和不确定性，不输出写操作。
- 使用用户最近接受/忽略反馈调整排序，但受频率与惊喜限制约束。

### 接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/v1/discoveries` | 首页机会卡 |
| `POST` | `/v1/discoveries/{id}/dismiss` | 忽略并记录反馈 |
| `POST` | `/v1/discoveries/{id}/create-task` | 以机会为上下文创建 M2 任务 |

### 常规边界 Case

| 情况 | 处理 |
| --- | --- |
| 用户关闭主动发现 | 不运行 Scheduler，历史卡隐藏 |
| 本周无高质量机会 | 不推送、不凑数 |
| 机会已过期 | 点击后要求刷新，不创建任务 |
| 同一机会重复出现 | 以 source/event ID 去重，并尊重用户忽略记录 |

### 验收

- 每周最多生成一次，每次最多展示 3 张卡。
- 主动发现不创建日历/提醒，也不直接进入审批。
- 用户从机会卡进入后，仍完整走 M2 → M3 → M4。

---

## M8. 金融信息提醒模块（P2，仅只读）

### 目标与边界

提供政策、公告、打新日历等**可溯源的信息提醒**；不提供买卖建议、不接券商账户、不自动申购或交易。

该模块仅在核心娱乐日程闭环稳定后评估。若上线，应独立完成合规与数据源审查。

### 主流程

```text
Backend：读取受信任金融信息源
  → Backend：提取事实、来源、发布时间与适用市场
  → AI：生成中性摘要与“需要用户核对”的提醒理由
  → App：展示只读信息卡
  → 用户批准后：仅创建本地提醒
```

### 验收红线

- UI、Prompt、通知和日志中均不得出现个股买卖/收益承诺/交易指令。
- 每条信息必须显示来源、发布时间、市场范围和“仅供信息参考”。
- 所有提醒创建仍走 M4 审批，但不存在券商、交易或自动申购 Action。

---

## 跨模块测试矩阵

| 类型 | 覆盖重点 |
| --- | --- |
| 单元测试 | 状态迁移、偏好校验、硬约束、幂等键、审批版本、插件 Schema |
| 集成测试 | CreateTask → Planner → Validator → Approval → Fake Action 全链路 |
| App 测试 | ViewModel 状态、离线 Outbox、深链、审批编辑与错误展示 |
| 契约测试 | App DTO、Backend API、AI PlanProposal、Plugin 输入输出 Schema |
| 故障注入 | 模型超时、插件超时、重复请求、进程重启、网络中断、过期审批 |
| 验收 Demo | 固定 Fixture 下可重复演示：生成周末计划 → 批准 → 日历/提醒结果 → 时间线 |

## 首个开发切片

优先交付 **M1 的最小档案 + M2 + M6 Fake 读取插件 + M3 规则化 Planner**：

```text
KMP 创建任务
  → Ktor 保存任务
  → FakeSports/FakeMovies 提供固定机会
  → Stub Planner + Validator 生成 Plan A/B
  → App 展示任务和候选项
```

该切片不写日历、不发通知；M4 在此基础上增加审批与副作用执行。这样可以先验证任务、计划和 UI 体验，再引入最容易出错的外部写操作。

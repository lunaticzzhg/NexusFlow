# Orbit v0.1 需求任务与进度表

## 1. 目的与依据

本文把 [需求基线](requirements.md) 和 [App 功能模块技术方案](app-module-technical-plan.md) 拆成可交付、可独立验证的任务，用于安排实现顺序和更新进度。

- 任务按技术方案的 F0–F5 阶段排序；每项只交付一个可验证行为。
- “已验证”仅表示对应代码与自动化测试已经存在并通过；不代表整个用户模块完成。
- “进行中”表示已有可复用骨架，但尚未满足该任务验收；“未开始”表示没有对应实现。
- 当前验证记录：`./gradlew :contracts:jvmTest :backend:test` 与 `./gradlew :app:composeApp:ktlintCheck :app:composeApp:allTests :app:composeApp:assembleDebug :app:composeApp:iosSimulatorArm64Test` 已通过。Docker 当前不可用，尚未实跑 PostgreSQL/Flyway；本环境无 Android 真机，未验证 Credential Manager 实际交互。

## 2. 当前需求进度总览

| 模块 | 当前进度 | 已有证据 | 主要缺口 |
| --- | --- | --- | --- |
| A0 应用基础壳 | 进行中 | KMP 启动入口、主题、`RuntimeConfig`、根导航与三 Tab 占位页已存在。 | 全局反馈、Debug 入口。 |
| A1 会话与启动恢复 | 已完成 | Google Session Gate、Android Credential Manager、平台安全存储、NexusFlow Access/Refresh 业务 Token、PostgreSQL 会话与 Bearer 鉴权已落地。 | Docker PostgreSQL/Flyway 与 Android 真机 Credential Manager 仍待环境验证。 |
| A2 首次引导 | 未开始 | — | 偏好 API、引导 UI、草稿和保存。 |
| A3 偏好与设置 | 未开始 | — | 版本化偏好、冲突处理、设置页。 |
| A4 首页 | 未开始 | — | 首页摘要、待审批入口、创建入口。 |
| A5 创建任务 | 未开始 | — | App 表单、偏好快照、时间范围、任务 API、PostgreSQL 持久化与 Outbox。 |
| A6 任务中心 | 未开始 | — | 列表 API、缓存、刷新与分组 UI。 |
| A7 任务详情/订阅 | 未开始 | — | App 详情、详情 API、SSE/events、持久化恢复。 |
| A8 方案比较/重生成 | 未开始 | — | App Plan A/B/C、结构化提案、确定性完整校验、重新生成命令。 |
| A9 审批 | 未开始 | 契约有待审批响应占位。 | Approval 聚合、API、编辑 UI、版本校验。 |
| A10 执行结果/重试 | 未开始 | — | 外部动作、结果模型、部分失败和重试。 |
| A11 时间线 | 未开始 | — | 持久化事件、Outbox、事件接口、时间线 UI。 |
| A12 缓存与恢复 | 未开始 | — | 本地存储、scope 隔离、前后台同步。 |
| A13 权限 | 未开始 | — | SystemUiGateway、日历/通知能力端口。 |
| A14 通知与深链 | 未开始 | — | 设备注册、通知、任务深链协调。 |

## 3. 交付任务

### F0：可运行的 App 壳

| ID | 任务 | 依赖 | 状态 | 独立验收 |
| --- | --- | --- | --- | --- |
| F0-01 | 建立根 `NavHost` 与 `AppShell`，包含首页、任务、偏好三个占位入口。 | 无 | 已验证 | Android Debug APK 可启动后可在三个入口间导航；路由只携带 ID。 |
| F0-02 | 搭建认证基础设施并建立 Google Session Gate 与 `AppContextSnapshot`：PostgreSQL/Flyway 身份会话表、Google 验证、NexusFlow Access/Refresh Token、Bearer 鉴权、Android Credential Manager、安全存储与登出。 | F0-01 | 已完成 | 无会话进入 Google 原生登录；有效 Google ID Token 建立业务会话；刷新/登出正确轮换或撤销；身份变化销毁旧 Shell。已通过 `./gradlew :contracts:jvmTest :backend:test`、`./gradlew :app:composeApp:ktlintCheck :app:composeApp:allTests :app:composeApp:assembleDebug :app:composeApp:iosSimulatorArm64Test`。未覆盖：Docker 当前不可用，尚未实跑 PostgreSQL/Flyway；本环境无 Android 真机，未验证 Credential Manager 实际交互。 |
| F0-03 | 建立全局 Toast、Loading/Error 基础接入与中英文资源约束。 | F0-01 | 未开始 | 根部仅一个 Toast Host；空内容失败显示 ErrorState；新增文案有英文和中文资源。 |

### F1：固定数据的任务体验

| ID | 任务 | 依赖 | 状态 | 独立验收 |
| --- | --- | --- | --- | --- |
| F1-01 | 为正式 Mock 建立 `TaskRepository`、成功/空/失败 Fixture 与 ViewModel Contract。 | F0-01 | 未开始 | UI 不直接读取静态列表；Repository 可返回成功、空和失败 Result。 |
| F1-02 | 实现首页与创建任务页面的固定数据流程。 | F1-01 | 未开始 | 输入有效请求后出现提交态，并打开固定任务详情；空输入不可提交。 |
| F1-03 | 实现任务详情的阶段化加载与基础错误状态。 | F1-01 | 未开始 | 可渲染规划中、待审批、完成、失败四类状态；失败有重试入口。 |
| F1-04 | 实现 Plan A/B/C 比较卡与来源、预算、时间、动作展示。 | F1-03 | 未开始 | 用户可切换方案并一屏识别主要取舍；来源与理由不伪装为模型事实。 |

### F2：任务后端与规划状态接入

| ID | 任务 | 依赖 | 状态 | 独立验收 |
| --- | --- | --- | --- | --- |
| F2-01 | 固化任务状态迁移、结构化提案和幂等创建契约。 | 无 | 未开始 | 生命周期/策略契约、结构化提案验证与创建任务幂等测试通过。 |
| F2-02 | 建立 PostgreSQL + Flyway 任务 Repository 与原子 Task/Event/Outbox 写入。 | F2-01、F0-02 | 未开始 | 重复 key 只产生一个任务；任务状态与事件/Outbox 同事务提交，并以已验证 Bearer actor 作为所有者范围。 |
| F2-03 | 实现持久化 Worker 领取、阶段 checkpoint、有限重试与崩溃恢复。 | F2-02 | 未开始 | 重复事件不重复推进；过期 lease 可恢复；耗尽重试得到可解释失败。 |
| F2-04 | 实现 App 侧 Task API、RemoteDataSource、Repository 与 `task-create`/`task-detail` 接入。 | F1-02、F2-01 | 未开始 | App 正确处理 202、幂等重放、422/409/5xx 与网络中断。 |
| F2-05 | 提供 REST events 或 SSE，接入任务详情刷新和时间线读取。 | F2-02、F2-04 | 未开始 | SSE 仅作刷新提示；断线、前后台或版本缺口后重新拉取详情快照。 |

### F3：偏好、约束与任务恢复基础

| ID | 任务 | 依赖 | 状态 | 独立验收 |
| --- | --- | --- | --- | --- |
| F3-01 | 实现版本化 Preference Profile API、校验和创建任务快照。 | F2-02 | 未开始 | 非法兴趣/预算/通勤被拒绝；新任务保存快照，旧任务不随偏好变化。 |
| F3-02 | 实现 onboarding 与 preferences UI，包括版本冲突与本地草稿。 | F0-02、F3-01 | 未开始 | 未授予日历权限仍可完成；版本冲突不静默覆盖。 |
| F3-03 | 补齐 PlanningContext、Fake sports/movies/calendar 数据和硬约束校验。 | F2-03、F3-01 | 未开始 | 固定 Fixture 下候选不违反时间、预算、通勤、避开项和来源时效；无可行项给调整原因。 |
| F3-04 | 实现任务列表、首屏缓存和 scope 隔离。 | F2-04、F3-01 | 未开始 | 冷启动先显示当前用户缓存，远端成功后覆盖；切换用户/租户不串读。 |

### F4：审批与可控执行闭环

| ID | 任务 | 依赖 | 状态 | 独立验收 |
| --- | --- | --- | --- | --- |
| F4-01 | 实现 Approval、ApprovalAction 与动作编辑快照的持久化/API。 | F2-02、F3-03 | 未开始 | 后端原子校验 owner、tenant、状态、版本、过期和 Schema；未批准不创建动作。 |
| F4-02 | 实现 Calendar/Notification Fake 工具网关、稳定动作幂等键与动作级审计。 | F4-01 | 未开始 | 重复批准或事件重放只执行一次外部写入；工具不接收模型文本/用户 Token。 |
| F4-03 | 实现审批抽屉：编辑标题/时间/提醒，批准、拒绝、稍后处理。 | F1-04、F4-01 | 未开始 | 编辑后重新校验；至少启用一个动作才可批准；409 后刷新并重新确认。 |
| F4-04 | 实现执行结果、部分成功和失败动作重试。 | F4-02、F4-03 | 未开始 | 日历成功、提醒失败时分别展示；重试只重放失败动作并复用原幂等键。 |

### F5：恢复、权限、通知与深链

| ID | 任务 | 依赖 | 状态 | 独立验收 |
| --- | --- | --- | --- | --- |
| F5-01 | 完成未终态任务同步、离线浏览和前后台恢复。 | F2-05、F3-04 | 未开始 | 应用重启可恢复待审批任务；离线时不执行审批写入且有明确提示。 |
| F5-02 | 接入日历/通知权限的 System UI 链路与结构化能力结果。 | F0-03 | 未开始 | 授权、拒绝、取消、不可用均使 Feature 退出 pending；无日历权限提示未校验冲突。 |
| F5-03 | 实现设备通知注册和待审批通知策略。 | F4-01、F5-02 | 未开始 | 任务进入待审批时最多产生一条通知；通知权限拒绝不影响任务。 |
| F5-04 | 实现 `taskId` 深链解码、会话 gate 和最终快照导航。 | F2-04、F5-03 | 未开始 | 点击已处理任务通知时显示最终结果，不展示过期审批卡。 |

### P1：不纳入 v0.1 MVP 验收的任务

| ID | 任务 | 依赖 | 状态 | 独立验收 |
| --- | --- | --- | --- | --- |
| P1-01 | 持久化聊天会话与本次条件。 | F2-02、F3-01 | 未开始 | 新会话不带入固定条件；用户可单项删除/清空条件后重新生成。 |
| P1-02 | 主动机会与负反馈。 | F3-03、F3-04 | 未开始 | 无高质量机会显示空态；负反馈即时隐藏机会并影响后续排序。 |
| P1-03 | 结果反馈与画像洞察。 | F4-04、F3-01 | 未开始 | 单次反馈仅影响排序；画像建议必须有证据且可删除。 |

## 4. 当前优先级与下一步

当前仅 A0 应用基础壳与 A1 会话基础设施具备实现证据。下一步必须从需求基线中选择一个新的产品模块，并为它建立独立的最小薄切片；不得恢复或扩展已移除的 Task/AI 种子代码。

建议优先在 A2 首次引导、A3 偏好与设置、A4 首页中选择一个模块，确认其权威状态、必要的后端基础设施和独立验收后再开始实现。若选择任务链路，则应从 F2-01 开始重新设计和实现，不能以任何旧内存 API、规划器或契约作为已完成前提。Apple 登录、Keycloak、浏览器 OIDC/PKCE 和其他 Provider 不属于本期任务。每项完成后更新本文的状态、证据、验证命令和未覆盖风险。

## 5. 更新规则

- 仅在代码、测试或固定 Fixture 演示能证明独立验收后，更新任务状态。
- 接口、状态机或权限语义与 `contracts/` 不一致时，先冻结该任务并解决契约冲突；不得由 App 或 Worker 自行选择语义。
- 新增版本时复制任务结构到新版本目录，不回写 v0.1 的历史进度。

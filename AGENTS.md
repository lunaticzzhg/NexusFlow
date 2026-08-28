# Orbit KMP Agent Guide

适用于 `NexusFlow`。

## 1. 入口

- 开始 Orbit 需求开发、复杂 review 或重构前，先读 `.agents/skills/INDEX.md`。
- `docs/architecture/orbit-frontend-architecture.md` 是 Orbit 前端架构、状态、生命周期和治理规则的唯一长期事实来源。Skill 负责工作流与专题补充，不得复制、绕开或弱化主规范。
- 新页面、功能、API 接入、bug fix、状态流、序列化、依赖注入、Compose UI 或平台能力实现，使用 `orbit-feature-development`。
- 非轻量 feature、复杂 bug、结构性重构、Human Traceability 目标或 owner/lifecycle 不清的问题，先使用 `orbit-architect-handoff` 生成 External Architect PLAN Bundle；拿到自包含 Work Order 后，再使用 `orbit-work-order-executor` 执行。
- 审视 module、feature、复杂业务 flow，或判断 AI 代码是否方便人类理解、追踪与排障，使用 `orbit-human-traceability-review`。
- 已明确问题集中在单个 owner 内，需要行为保持的局部重构时，使用 `kotlin-local-reasoning-refactor`。
- 仅需要扫描 LargeClass、TooManyFunctions、LongMethod、CognitiveComplexity 等静态热点时，使用 `kotlin-complexity-audit`。静态指标只能作为审视信号，不得直接作为重构结论。
- 定期比对 Boltzlog、同步其 App 规范或实现，使用 `app/boltzlog-sync`。
- 后端协议、权限和持久状态是事实来源；客户端只负责交互、本地状态与友好的失败体验。

## 2. 常驻原则

- 共享业务与 UI 默认写在 `app/composeApp/src/commonMain`；仅平台能力与平台入口写入相应 source set。
- 不因预期复用而过早拆 Gradle 模块；先在 feature package 内保持依赖方向清晰。
- 每个有意义的改动都运行最窄有效验证；涉及共享代码或依赖装配时扩大到 KMP 相关测试。
- Compose 页面、ViewModel 状态所有权、`UiState` 边界、上下文运行态、复杂异步模块、状态机、Loading 反馈、认证/网络/Deep Link、协程调度、平台能力、可观测性、UI 资源和验证治理统一遵循 [Orbit 前端架构主规范](docs/architecture/orbit-frontend-architecture.md)。
- 每次修改 Kotlin 或 Gradle Kotlin DSL 后，交付前必须运行 `./gradlew :app:composeApp:ktlintCheck`；它是代码格式与基础静态风格的默认收尾检查，不得省略。
- 若 Ktlint 失败，先运行 `./gradlew :app:composeApp:ktlintFormat`，审查自动修改后再次执行 `ktlintCheck`；禁止为绕过手写代码问题随意放宽规则或扩大 baseline。仅第三方生成代码可保留最小、可说明的 baseline 例外。
- 应用根入口、全应用基础设施和共享设计系统的 Kotlin 源码标识使用 `App*`；Android Application 根入口固定命名为 `App`。不得以产品品牌 `Orbit*` 命名 Kotlin 类型、函数或源码文件，也不得为 feature 内业务类型添加 `App*`。`Orbit` 仅用于用户可见品牌文案、构建/工程产物，以及为兼容既有安装或外部平台而必须保持稳定的外部标识；包名使用当前 `com.nexusflow.app`。

### 2.1 命名

- 命名应简明、准确、顾名思义。生产 Kotlin 的内部类型、函数、变量和常量默认不超过四个英文词。
- 优先删除 package、文件、类型和参数已表达的重复信息；不得为满足字数限制使用生硬缩写。
- 只有为消除真实歧义，或受平台 override、第三方 API/协议、序列化字段约束时，才可保留更长名称。
- 方法和类型优先表达调用意图与业务效果，而不是 flag、generation、counter、offset 等实现机制。
- 评审时若不能明确指出名称中每个词提供的额外语义，应删减或重命名。

## 2.2 External Architecture Workflow

对于非轻量 feature、复杂 bug 和结构性重构，Codex 是 implementation executor，不是最终 architecture 或 Human Traceability authority。

需要结构设计时：

1. 使用 `orbit-architect-handoff` 生成完整项目 PLAN Bundle；
2. 等待 External Architect 返回自包含 Work Order；
3. 使用 `orbit-work-order-executor` 严格按 Work Order 执行；
4. Work Order 与真实源码发生设计层冲突时，停止受影响 Slice 并报告 Deviation，不得自行重新设计；
5. 实现完成后生成包含完整最新项目、Work Order、diff 和测试结果的 Verification Bundle；
6. External Architect 返回 PASS 后，结构性任务才算完成。

不得把 tests passing、LOC 下降、拆文件、helper extraction、private wrapper 或设计模式本身视为 Human Traceability PASS。

## 3. Human Traceability Gate

非轻量 Kotlin feature、fix、review 或 refactor，必须以“未参与代码生成的人能否快速理解链路并排查问题”为最终复杂度验收标准。纯文案、样式微调、无业务语义的机械接线可按轻量路径处理，但不得借此绕过状态、生命周期或 owner 风险。

- 复杂代码统一按 `Architecture -> Coordination -> Local Reasoning -> Human Debug Simulation` 顺序审视；上一层未证明清楚时，不得用下一层的整理手段掩盖问题。
- Architecture 必须回答：这个能力属于哪里，authoritative source 在哪里，依赖方向与生命周期是否合理。
- Coordination 必须回答：Entry 在哪里，Flow Owner / State Owner / Lifecycle Owner 分别是谁，关键 Decision 谁做，Effect 谁执行，success / failure / cancel / recovery / duplicate / late-result 在哪里闭环。
- Local Reasoning 必须回答：一个 owner 内部依赖哪些核心概念和状态，状态是否有 canonical representation，transition 是否明确，理解关键行为需要多少 semantic hops。
- 每个 mutable business fact 只能有一个 writable owner；维护状态 invariant 的行为默认与该状态属于同一 owner。
- 复杂 flow 必须存在明确的 debug boundaries，使维护者能够从用户现象逐步二分到责任 owner，而不是检查所有参与对象。
- delegation-only wrapper、机械拆文件、机械 Extract Method、仅减少 LOC、仅字段 private 化，不算 human traceability 改善。只有当读者需要掌握的事实、semantic hops、状态组合、责任区域、write entries、callback/back-edge 或 lifecycle/recovery/terminal 分散度实际减少时，才算重构收益。
- 不得因为代码有测试、类名清楚、字段已封装、没有依赖环或业务本身复杂，就直接判定复杂度可接受。关键 flow 的可追踪性必须被具体证明；无法证明时标记为 `UNPROVEN`。
- 同一语义状态应尽量只有一种 canonical representation；如果多个 boolean、nullable field、generation、mode 或 options 组合才能表达生命周期阶段，应先审视是否存在隐含状态机或可派生状态。
- 不为未来需求增加 behavior boolean、可选模式、接口、Factory、Registry 或扩展点；真实第二调用方或真实变化原因出现后再泛化。
- 新增 lifecycle/recovery/terminal/cancel/late-result 路径时，必须说明谁 start、retry/recover、进入终态、拒绝迟到结果以及清理资源。
- 不得以 minimal diff 为理由保留本次改动造成的明显恶化结构；在测试保护下允许先做小规模行为等价整理，但不得顺手扩大成无边界存量重构。

### 3.1 Human Debug Simulation

非轻量开发、复杂 bug 修复和行为保持重构完成后，必须至少模拟以下五类故障：

1. input 已收到，但 state 未变化；
2. state 已变化，但 UI/output 未变化；
3. duplicate 或 stale result 被重复/错误处理；
4. flow 永远没有 terminal；
5. recovery 已完成，但最终结果仍然错误。

每个场景至少回答：

- 第一检查点是什么；
- 第二检查点是什么；
- 可以观察什么事实证明流程已经经过该节点；
- 哪个 owner 对该阶段负责。

如果无法快速缩小责任区域，应回到对应 Architecture / Coordination / Local Reasoning 层修正，而不是继续增加日志、helper 或防御性 flag 掩盖问题。

具体的 Flow Reconstruction、Traceability Gate、Knowledge Surface、Semantic Hop、Canonical State、Debug Boundary 和行为保持重构流程，由对应 Skill 定义；`AGENTS.md` 不重复维护操作细节。

## 4. 既有实现优先门槛

新增或修改任何代码前，必须先搜索项目内同职责实现；不得从名称、局部写法或预想架构直接开始编码。搜索范围至少包括当前 feature、`core`、同仓成熟 feature、已有资源与测试；比较职责、状态/生命周期、调用方、失败语义与依赖边界，而不是只比名称。

- 语义匹配时直接复用。
- 语义大体匹配且可向后兼容时，在既有实现内扩展。
- 只有现有实现无法表达当前独立语义、owner 或变化原因时，才在最小 owner 内新增；必须记录不能复用的具体差异与验证方式。

方案必须列出参考实现、复用/扩展/新增的结论及原因、以及对应验证。没有这项证据不得编码；纯格式或拼写修正无需单独形成决策卡，但仍须先阅读被修改位置及相邻实现。正式 feature 使用 Mock 数据不免除此门槛。

涉及列表时，完整的 Mock 与成熟实现对照、状态契约和例外规则以 `orbit-feature-development/references/list-data-lifecycle.md` 为唯一工作流参考。新增 Koin 初始化参数、平台专用 Module、Service Locator、全局桥接或抽象层前，必须完成上述对照。默认优先级为：既有实现模式、官方能力的最小接入方式、新抽象。平台 SDK 的互操作细节必须封装在平台 `actual` 或平台入口挂载中，不得反向改变 `commonMain` 的 DI 拓扑或 Koin 启动签名；只有已有模式无法满足且方案明确论证时才可例外。

## 5. 设计质量与 ROI

目标是在当前约束下选择正确、清晰且可维护的最小方案；“更完备”不自动等于“更好”。开发与 review 的步骤、触发条件和交付物由对应 Skill 定义，前端架构、状态、网络和平台规则以 [Orbit 前端架构主规范](docs/architecture/orbit-frontend-architecture.md) 为主，必要时叠加 Skill references。

- 方案与 review 优先保障业务逻辑正确性和业务架构层次，再按真实风险扩展安全、性能和测试检查。
- 不因形式问题、预期复用或理论极端场景引入框架、Factory、注册表、平台分叉或额外测试基建；只有真实缺陷、重复协调或明确约束证明收益时才收敛。
- ViewModel、Controller、StateHolder 和业务动作链路必须符合 [Orbit 前端架构主规范](docs/architecture/orbit-frontend-architecture.md) 的 ViewModel 与状态所有权规范；不得用通用 dispatch、Intent、helper 或宽泛 callback 保留无业务职责的绕行链路。
- 在增加并发、取消、生命周期或防御性状态前，先还原真实交互入口、UI 禁用/Loading 条件、导航与上下文切换路径。产品流程已使某路径不可达时，不为它增加保护；“无需处理”必须记录交互或测试证据及重新评估条件。仅当路径真实可达、产品要求支持、已发生故障或平台限制证明风险时，才增加复杂度。
- 一个 review 结论不得因为静态指标、测试数量、类名、封装层次或设计模式而自动升级为“可接受复杂度”；必须回到真实业务 flow 和 human debug path 验证。
- 重构优先删除错误或过期的概念、状态、callback、兼容 seam 和无效抽象，再考虑引入新的类型或结构。
- 每个有意义的设计或 review 结论都说明非目标、验证证据和触发重新评估的条件。

## 6. 用户文案与本地化

- `commonMain`、Android 和 iOS 中所有用户可见文案及无障碍描述，必须来自 Compose Resources，并在 Composable 中通过 `stringResource(Res.string.xxx)` 解析。
- 禁止在 `Text`、`contentDescription`、按钮、Snackbar、Dialog、空态、错误态、加载态等 UI API 中直接写自然语言字面值。
- 带变量的文案必须使用资源占位符；禁止通过字符串拼接或插值构造完整句子。
- ViewModel、Repository 和 Domain 仅传递结构化状态、错误类别、数量和时间等数据，不传递已经翻译的用户文案。
- 例外仅限非用户可见的稳定标识：日志 tag、事件名、路由、资源键、业务 ID、Mock 数据和测试夹具。
- 新增或修改页面的 review 必须检查新增的用户文案字面值，且默认英文与中文资源必须同步补齐。

## 7. 上下文身份边界

- 先判断一次切换是否会改变数据归属、权限、导航合法性或后台任务所有权；只有会使既有 UI 状态失效时，才建立上下文身份边界。
- 在能完整覆盖失效状态的最低 Compose 边界，以稳定的 context ID 使用 `key(...)` 重建；账户/登录会话变化重建已登录 App Shell 与 NavHost，确保旧导航栈、页面 ViewModel 和 `viewModelScope` 一并销毁。
- Tab、日期、筛选和排序等不改变状态合法性的交互，保持同一 ViewModel，通过业务动作更新；不得借此重建导航树。
- Repository 对账户、家庭或租户范围的数据，必须在请求开始时捕获 scope；缓存读写与迟到响应只能作用于该 scope。
- Koin `scope` 仅适用于有明确 owner、创建点、关闭点且确有多个共享消费者的可关闭资源；不得用它替代 Compose/Navigation 的页面生命周期。
- 新增上下文身份边界必须在方案与 review 中说明：稳定身份 ID、销毁/重建范围、迟到请求和缓存处理，以及验证方式。

## 8. 跨端与平台能力路由

- 共享业务在 `commonMain` 保持单一权威实现；平台 source set 只实现不可共享的系统互操作。
- `core`、feature 与平台端口的归属和提取边界统一遵循 [Orbit 前端架构主规范](docs/architecture/orbit-frontend-architecture.md)。
- 文件、媒体、权限、系统 UI、推送、SSE 与语音能力遵循 [Orbit 前端架构主规范](docs/architecture/orbit-frontend-architecture.md)。
- 平台 SDK 的细节不得反向污染共享业务模型；需要平台能力时，通过最小、稳定、可测试的 port 或 `expect/actual` 边界接入。

## 9. 规范沉淀与治理

- 规范沉淀遵循 [Orbit 前端架构主规范](docs/architecture/orbit-frontend-architecture.md) 的 Review、验证与规范治理章节：先判断是否值得沉淀，再补齐可执行规则、验证方式与必要路由。
- 具有异步、外部 I/O、重试或用户可见失败的 feature，遵循 [Orbit 前端架构主规范](docs/architecture/orbit-frontend-architecture.md) 的可观测性章节；新 feature 从其模板开始，而不是新增全局埋点框架。
- `AGENTS.md` 只保留全局适用的强制原则、最高级 Gate 与 Skill 路由；Skill 定义工作流和触发条件；前端架构规则统一维护在 [Orbit 前端架构主规范](docs/architecture/orbit-frontend-architecture.md)；网络合同、Koin 生命周期、列表生命周期、UI review 和验证等专题材料维护在对应 Skill references。
- 同一规则不得复制到多个长期维护位置。若 Skill 与主架构规范冲突，以主架构规范为准，并修正 Skill。
- Skill 的质量必须用真实项目 flow 做回归验证，不能只检查输出模板是否完整。对 Human Traceability Review，优先使用 Chat reply、typewriter、Vlog late-result、cancel/late-event、history restore 等真实 debug 场景验证其判断能力。

# NexusFlow Agent Guide

适用于整个 `NexusFlow` repository。本文是全局 constitution，只保留跨 App、Contracts、Backend、AI 都成立的强制原则、最高级 Gate、authority routing、skill routing 和 scope-derived verification。具体执行环境的长期架构正文由对应 authority 文档维护。

## 1. Architecture Authorities

长期事实按范围路由：

| 范围 | Authority |
| --- | --- |
| App / KMP interaction、Compose、ViewModel、`UiState`、平台能力、本地化与 App 验证 | [Orbit 前端架构主规范](docs/architecture/orbit-frontend-architecture.md) |
| Backend / Ktor durable truth、认证授权、事务、外部 IO、持久化与 Backend 验证 | [NexusFlow Backend 架构主规范](docs/architecture/nexusflow-backend-architecture.md) |
| AI / planning proposal boundary、Kotlin-first Planner 默认、guardrails 与 eval 边界 | [NexusFlow AI 架构主规范](docs/architecture/nexusflow-ai-architecture.md) |

Skill 负责工作流与专题步骤，不复制、绕开或弱化 authority 文档。若 Skill 与 authority 冲突，以 authority 为准并修正 Skill。真实源码、测试、contracts 和迁移事实优先于 prose。

## 2. Skill Routing

- 开始需求开发、复杂 review、结构性重构或执行 Work Order 前，先读 `.agents/skills/INDEX.md`。
- 新页面、功能、API 接入、bug fix、状态流、序列化、依赖注入、Compose UI、Backend 行为、Contracts 变更、AI/planning 边界或其它用户可见产品行为修改，使用 `nexusflow-feature-development`，并按 touched scope 读取对应 architecture authority。
- 非轻量 feature、复杂 bug、结构性重构、Human Traceability 目标或 owner/lifecycle 不清的问题，先使用 `orbit-architect-handoff` 生成 External Architect PLAN Bundle；拿到自包含 Work Order 后，再使用 `orbit-work-order-executor` 执行。
- 执行既有 Work Order 或 Correction Work Order，使用 `orbit-work-order-executor`，严格按 Work Order slices 执行。
- 审视 module、feature、复杂业务 flow，或判断 AI 代码是否方便人类理解、追踪与排障，使用 `orbit-human-traceability-review`。
- 已明确问题集中在单个 Kotlin owner 内，需要行为保持的局部重构时，使用 `kotlin-local-reasoning-refactor`，并读取目标范围对应 authority。
- 仅需要扫描 LargeClass、TooManyFunctions、LongMethod、CognitiveComplexity 等静态热点时，使用 `kotlin-complexity-audit`。静态指标只能作为审视信号，不得直接作为重构结论。
- 定期比对 Boltzlog、同步其 App 规范或实现，使用 `app/boltzlog-sync`。

## 3. Global Mental Model

NexusFlow 使用一套 reasoning model，落到三个执行环境：

```text
authoritative source
-> writable owner
-> lifecycle / context identity
-> Flow / State / Decision / Effect ownership
-> canonical state
-> async / recovery / terminal ownership
-> Human Traceability
-> Simplicity / ROI
```

不同环境的概念映射只用于推理，不要求创建对称类：

| App reasoning | Backend reasoning | AI reasoning |
| --- | --- | --- |
| Action / intent | Command / query / request | Planner request |
| UiState / domain projection | Domain state / durable snapshot | PlanningContext / PlannerResult |
| ViewModel / feature Flow Owner | Application Service / operation owner | Planner operation owner |
| Controller only when lifecycle complexity is real | Coordinator/Worker only when durable/async workflow requires it | Coordinator only if real multi-step lifecycle later requires it |
| Repository | Repository / domain port | No business Repository by default |
| UiEffect / platform request | external effect / durable command | RequestedAction proposal |

后端协议、权限和持久状态是事实来源；客户端负责交互、本地状态和友好的失败体验；AI 只负责 proposal/reasoning，不能成为权限、审批、幂等、持久化或副作用 authority。

## 4. Existing Implementation First

新增或修改任何有意义的代码或治理规则前，必须先搜索项目内同职责实现。搜索范围按 touched scope 决定，至少覆盖相关 feature、`core`、shared contracts、成熟实现、已有资源与测试；比较职责、状态/生命周期、调用方、失败语义和依赖边界，而不是只比名称。

- 语义匹配时直接复用。
- 语义大体匹配且可向后兼容时，在既有实现内扩展。
- 只有现有实现无法表达当前独立语义、owner 或变化原因时，才在最小 owner 内新增。

方案必须列出参考实现、复用/扩展/新增的结论及原因、以及对应验证。纯格式或拼写修正无需单独形成决策卡，但仍须先阅读被修改位置及相邻实现。正式 feature 使用 Mock 数据不免除此门槛。

## 5. Human Traceability Gate

非轻量 feature、复杂 bug、复杂 review 或行为保持重构，必须以“未参与代码生成的人能否快速理解链路并排查问题”为最终复杂度验收标准。

复杂 flow 统一按以下顺序审视：

```text
Architecture -> Coordination -> Local Reasoning -> Human Debug Simulation
```

- Architecture 必须回答：能力属于哪里，authoritative source 在哪里，依赖方向与生命周期是否合理。
- Coordination 必须回答：Entry 在哪里，Flow Owner / State Owner / Lifecycle Owner 分别是谁，关键 Decision 谁做，Effect 谁执行，success / failure / cancel / recovery / duplicate / late-result 在哪里闭环。
- Local Reasoning 必须回答：一个 owner 内部依赖哪些核心概念和状态，状态是否有 canonical representation，transition 是否明确，理解关键行为需要多少 semantic hops。
- Human Debug Simulation 至少覆盖：input 已收到但 state/result 未变化；state/result 已变化但 outward output 未变化；duplicate/stale result 被错误处理；flow 永远没有 terminal；recovery 完成但最终结果仍错误。

每个 mutable business fact 只能有一个 writable owner；维护状态 invariant 的行为默认与该状态属于同一 owner。复杂 flow 必须存在明确 debug boundaries，使维护者能从用户现象逐步二分到责任 owner。

不得因为代码有测试、类名清楚、字段已封装、没有依赖环、LOC 下降、拆文件、helper extraction、private wrapper 或设计模式本身，就判定 Human Traceability PASS。Codex 执行 Work Order 时也不得声明 External Architect PASS。

## 6. Simplicity / ROI

目标是在当前约束下选择正确、清晰且可维护的最小方案；“更完备”不自动等于“更好”。

- 不因形式问题、预期复用或理论极端场景引入框架、Factory、Registry、Strategy、Runtime、Controller、Worker、provider router、缓存框架或额外测试基建。
- 不为未来需求增加 behavior boolean、可选模式、接口、Factory、Registry 或扩展点；真实第二调用方或真实变化原因出现后再泛化。
- 重构优先删除错误或过期的概念、状态、callback、兼容入口和无效抽象，再考虑引入新的类型或结构。
- 新增 lifecycle/recovery/terminal/cancel/late-result 路径时，必须说明谁 start、retry/recover、进入终态、拒绝迟到结果以及清理资源。
- 产品流程已使某路径不可达时，不为它增加保护；“无需处理”必须记录交互、contract 或测试证据及重新评估条件。

每个有意义的设计或 review 结论都说明非目标、验证证据和触发重新评估的条件。

## 7. Contracts And Trust Boundaries

`:contracts` 是跨 App、Backend、AI 边界共享的 wire schema，不是 Backend domain model、AI provider/internal model 或 App presentation/domain model 的收纳箱。

任何 contract change 必须说明：

```text
wire contract 是否变化？
producer 是谁？
consumer 是谁？
compatibility requirement 是什么？
哪些 module-internal models 不进入 contracts？
```

Backend 保持 permissions、task state、approval、idempotency、audit、persistence、credentials 和 side effects 的 authority。AI 输出和外部/plugin 文本都是不可信输入，不能改变系统策略、审批策略、权限或安全边界。

## 8. Scope-derived Verification

每个有意义的改动都运行最窄有效验证；涉及共享 contract、依赖装配或跨执行环境时，扩大到受影响 producer/consumer。

默认选择：

| Touched scope | Default evidence |
| --- | --- |
| App / KMP Kotlin or Gradle Kotlin DSL | App authority 要求的 App lint/tests，例如 `./gradlew :app:composeApp:ktlintCheck`，再按风险扩展 |
| Backend | Backend 相关 test/build/static checks，例如 `./gradlew :backend:test`，再按风险补集成证据 |
| Contracts | contract serialization/API tests，例如 `./gradlew :contracts:jvmTest`，并验证受影响 App/Backend producer/consumer |
| AI | 真实 `:ai` module/runtime 存在后使用其任务；当前 snapshot 不 invent AI command |
| Cross-stack | touched scopes 的验证并集，加至少一个 contract/flow-level proof |
| Docs / skills / templates only | `git diff --check`、路径/链接/routing/string consistency 检查 |

不得把 App ktlint 作为所有 Kotlin/Gradle 改动的全局收尾要求。未运行的验证必须说明原因、风险和重新验证条件。

## 9. Governance

- `AGENTS.md` 只保留全局适用的强制原则、最高级 Gate、architecture-authority routing、skill routing 和 scope-derived verification。
- App-only Compose/ViewModel/commonMain/localization/platform/ktlint 规则属于前端 authority。
- Backend-only Ktor/auth/transaction/blocking IO/runtime config 规则属于 Backend authority。
- AI-only Planner/provider/guardrails/eval/runtime-absent 规则属于 AI authority。
- 同一长期规则不得复制到多个维护位置。
- 规范沉淀必须有触发证据、适用范围、非目标、职责边界、反例和验证方式。
- Skill 的质量必须用当前 repository flows 回归验证；不得依赖已删除或不存在的源码场景。

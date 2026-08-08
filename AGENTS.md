# Orbit Engineering Guide

适用于 NexusFlow；Orbit 是其用户可见产品名。本文件保留全局强制原则与路由：客户端规则主要适用于 `app/composeApp`，后端与 AI 规则由对应 Skill 维护。

## 1. 入口

- 开始 Orbit KMP 需求开发、修复或 review 前，先读 [.agents/skills/INDEX.md](.agents/skills/INDEX.md)。
- 新页面、功能、API 接入、状态流、序列化、依赖注入、Compose UI 或平台能力实现，使用 `app/orbit-feature-development`。
- Ktor API、任务状态、认证授权、数据库/Outbox、Worker、事件或工具网关实现，使用 `backend/orbit-backend-development`。
- 规划、模型 Provider、结构化输出、提示注入防护、评测、回放或模型成本实现，使用 `ai/orbit-ai-development`。
- 后端协议、产品事实、权限与服务端任务状态是事实来源；客户端只负责交互、本地状态与友好的失败体验。

## 2. 常驻原则

- 共享业务与 UI 默认写在 `app/composeApp/src/commonMain`；仅平台能力与平台入口写入相应 source set。
- 不因预期复用而过早拆 Gradle 模块；先在 feature package 内保持依赖方向清晰。
- 每个有意义的改动都运行最窄有效验证；涉及共享代码或依赖装配时扩大到 KMP 相关测试。
- 每次修改 Kotlin 或 Gradle Kotlin DSL 后，交付前运行 `./gradlew :app:composeApp:ktlintCheck`。若失败，先运行 `./gradlew :app:composeApp:ktlintFormat`，审查自动修改后再次检查；不得为绕过手写代码问题随意放宽规则或扩大 baseline。
- 应用根入口、全应用基础设施和共享设计系统的 Kotlin 源码标识使用 `App*`；Android Application 根入口固定命名为 `App`。不得以产品品牌 `Orbit*` 命名 Kotlin 类型、函数或源码文件，也不得为 feature 内业务类型添加 `App*`。

## 3. 既有实现优先门槛

新增或修改基础能力、平台能力、Koin 装配、跨端接口，或会进入正式交付的 ViewModel / 数据加载 / 列表 UI 前，必须先搜索至少一个同职责既有实现，并在方案开头列出参考文件、拟复用的模式，以及不复用的具体原因。

正式 feature 使用 Mock 数据不免除此门槛。涉及列表时，Mock 与成熟实现对照、状态契约和例外规则以 [列表数据生命周期](.agents/skills/app/orbit-feature-development/references/list-data-lifecycle.md) 为唯一事实来源。未完成对照，不得新增 Koin 初始化参数、平台专用 Module、Service Locator、全局桥接或抽象层。

## 4. 设计质量与 ROI

目标是在当前约束下选择正确、清晰且可维护的最小方案；“更完备”不自动等于“更好”。

- 方案与 review 优先保障业务逻辑正确性和业务架构层次，再按真实风险扩展安全、性能和测试检查。
- 不因形式问题、预期复用或理论极端场景引入框架、Factory、注册表、平台分叉或额外测试基建；只有真实缺陷、重复协调或明确约束证明收益时才收敛。
- 在增加并发、取消、生命周期或防御性状态前，先还原真实交互入口、UI 禁用/Loading 条件、导航与身份切换路径；“无需处理”必须记录证据及重新评估条件。
- 每个有意义的设计或 review 结论都说明非目标、验证证据和触发重新评估的条件。
- 已确认需求依赖但尚未存在的基础设施，必须先将其作为该需求的最小实现切片搭建，再开发业务代码；完整规则见 [需求依赖的基础设施](docs/architecture/standards-governance.md#需求依赖的基础设施)。

## 5. 用户文案与本地化

- `commonMain`、Android 和 iOS 中所有用户可见文案及无障碍描述，必须来自 Compose Resources，并在 Composable 中通过 `stringResource(Res.string.xxx)` 解析。
- 禁止在 UI API 中直接写自然语言字面值；带变量的文案使用资源占位符，不用字符串拼接或插值构造完整句子。
- ViewModel、Repository 和 Domain 只传递结构化状态、错误类别、数量和时间等数据，不传递已翻译文案。
- 新增或修改页面的 review 必须检查用户可见字面值，默认英文与中文资源同步补齐。

## 6. 身份与运行态边界

- 先判断一次切换是否会改变数据归属、权限、导航合法性或后台任务所有权；只有会使既有 UI 状态失效时，才建立上下文身份边界。
- 在能完整覆盖失效状态的最低 Compose 边界，以稳定 context ID 使用 `key(...)` 重建；用户、租户或登录会话变化必须销毁旧 App Shell、NavHost、页面 ViewModel 与 `viewModelScope`。
- Tab、日期、筛选和排序等不改变状态合法性的交互，保持同一 ViewModel，通过 Intent 更新；不得借此重建导航树。
- Repository 对 user / tenant 范围的数据，必须在请求开始时捕获 scope；缓存读写与迟到响应只能作用于该 scope。
- Koin `scope` 仅适用于有明确 owner、创建点、关闭点且确有多个共享消费者的可关闭资源；不得替代 Compose/Navigation 页面生命周期。

## 7. 业务与平台能力边界

- 同一业务事实、状态机、输入校验、缓存策略、重试/取消/幂等语义，以及用户可见行为，只能在 `commonMain` 有一套权威实现。
- `core` 与“是否需要平台实现”是独立维度：跨 feature、无业务语义且由应用统一拥有的能力归 `core`；平台无关实现留在 `commonMain`，系统 API 实现才分别放 Android/iOS。
- `androidMain` 与 `iosMain` 仅实现不可共享的系统能力，如通知、系统日历授权、深链、后台执行、原生 SDK 与平台回调。共享层决定“做什么、何时做、是否允许做、失败后怎么办”；平台层只完成原子能力并返回共享的结构化结果。
- 不得将任务编排、审批、SSE 处理、重试策略或用户可见文案分叉到平台 source set。只有已证实的平台限制导致用户可见行为必须不同，才允许分叉，并记录依据、影响和验证。

## 8. Orbit 特有契约

- REST 权威快照与服务端任务状态是唯一事实来源；SSE 只传递增量事件，断线后必须以 REST 恢复。
- 客户端不能伪造审批、外部写入或任务完成；审批决定、`Idempotency-Key`、任务版本与执行权都由服务端校验。
- 长期偏好和本次聊天条件必须分开；AI 推断只能作为建议，用户接受后才可持久化为偏好。

## 9. 规范沉淀与治理

- 规范沉淀遵循 [规范治理](docs/architecture/standards-governance.md)：先判断是否值得沉淀，再选择唯一事实来源、补齐可执行规则与验证方式，最后更新必要路由。
- 具有异步、外部 I/O、重试或用户可见失败的 feature，遵循 [Feature 可观测性规范](docs/architecture/observability.md)。
- `AGENTS.md` 保留全局强制原则与路由；Skill 定义工作流和触发条件；专题契约的完整细则只在 `docs/architecture/` 或 Skill `references/` 的唯一事实来源中维护。

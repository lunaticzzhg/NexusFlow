---
name: orbit-feature-development
description: Implement and review Orbit KMP feature work with lightweight MVI, Jetpack Compose Multiplatform, kotlinx.serialization, and Koin. Use for a new or changed screen, feature flow, API/DTO, ViewModel state/effect, Compose component, dependency registration, platform capability, or related defect in NexusFlow's app/composeApp.
---

# Orbit Feature Development

采用一套可维护但不堆叠仪式的默认做法：轻量 MVI、Kotlin 原生序列化、构造函数注入，以及以状态提升为中心的 Compose UI。

## 工作流

1. **需求定级与路由。** 先按 [Skills Index](../../INDEX.md) 判断是轻量、合同、并发/生命周期、平台能力或跨 feature 改动。读取 [架构参考](references/architecture.md) 和命中的专题 reference；协议、后端事实或权限不明确时先查现有实现或澄清，不按客户端猜测实现。
2. **形成实施决策卡。** 对共享基础能力、跨 feature、API/认证/会话、并发/重试/缓存、生命周期、平台能力、新依赖或兼容性风险，先完成证据化方案比较再编码。比较顺序为：后端/产品契约 → 当前 Orbit 实现与测试 → 同仓成熟应用 → 官方资料 → 最小自定义方案；前一层足以定案时停止扩大调研。不得机械照搬同仓实现，必须记录有意差异及其验证。

   每项工作只填写命中的最小档；复杂跨边界改动在相应档不足以说明取舍时，才补充最后一档。所有档都列出可独立验证的薄切片。

   ```markdown
   ### 轻量改动
   - 目标与非目标：
   - 沿用的既有模式、文件与验证：

   ### 合同改动（API、DTO、认证或兼容性）
   - 权威合同、调用者与缺失新字段时的默认行为：
   - 请求/响应兼容性、失败映射与验证：

   ### 并发、生命周期或平台能力改动
   - owner 与权威状态：
   - 状态转换、取消/重试、并发或平台限制：
   - 真实交互约束、可达路径与重新评估条件：
   - common/platform 或运行态边界、验证：

   ### 复杂跨边界改动（仅在上述记录不足时）
   - 候选方案、拒绝方案、回滚与残余风险：

   ### 薄切片（所有档必填）
   1. 行为 / 文件 / 验证：
   ```

3. **反驳方案。** 主动检查：能否删除一个抽象、DI 定义、配置层或文件？先按 `AGENTS.md` 还原交互流程，确认并发或生命周期路径真实可达后才增加保护。流程复杂度是否应先通过显式状态机收敛，而非增加变量和控制分支？是否把 feature 语义错放进 core？是否存在 domain → data、UI → repository 或跨端业务分叉？只有复杂跨边界改动才记录被拒绝方案；若保留新增项，写明当前的真实消费者或故障依据。
4. **确认停点。** 未得到用户对方案的明确确认前，不修改任何项目文件，也不执行有副作用的工程动作；仅可完成只读核查和方案修订。需求记录与提交仍须在用户验收后分别取得明确授权。确认后按最小薄切片实现，每个切片只覆盖一个可验证行为。
5. **实现、核心结论与验收。** 对每个已确认的开发任务，只要可使用子 agent，就必须采用主从模式；不得由主 agent 直接绕过该模式完成实现或修复。主 agent 冻结方案、拆分可独立验证的薄切片、委派子 agent、完成常规代码/测试审查并作最终架构裁决；子 agent 只按确认切片实现、修复和验证，不作最终架构裁决或范围扩张。每个切片先补最小有效测试，再实现并运行对应验证。

   每个切片完成后，读取并填写 [核心原则结论门禁](../../../../docs/architecture/review-evidence-gate.md) 中实际命中的结论卡。默认不重复执行完整 `code-review-and-refactoring`；只有用户明确要求 review，或命中该门禁的升级条件时，主 agent 才读取并执行 [`code-review-and-refactoring/SKILL.md`](../code-review-and-refactoring/SKILL.md) 的完整独立审查。完整审查发现的 P0/P1/P2 问题必须由子 agent 修复；修复后重复“常规审查 → 完整 review”，直至没有需要在本次范围修复的问题，或向用户明确报告并获得接受残余风险的决定。主 agent 在子 agent 完成、所需审查和必要修正闭环前保持任务进行中，不得提前交付。

   子 agent 完成切片、被中断或不再有待办时，主 agent 必须立即停止/释放它；只有主 agent 基于审查发现明确发起新的修复切片时，才能再次调度。最终交付前确认没有仍在运行的子 agent。若当前环境无法使用子 agent，先向用户说明该硬门槛无法满足并请求明确豁免，不得静默降级。

   修正完成后，向用户报告核心结论卡、完整 review（如触发）、验证证据与未覆盖风险，等待用户验收。交付按本次命中的需求、决策卡和专题检查项逐条输出：每项给出结论、代码/测试/命令证据与未验证原因；发现问题则给出影响和处理结论；不适用项说明原因。不得以“架构合理”“已检查”“测试通过”等笼统结论替代逐项结果。验收后，只有获得用户对“更新需求记录”和“提交”的分别明确授权，才执行相应动作。

## 专题路由

- 认证、登录、Token、会话恢复、刷新或认证 Header：读取 [认证架构规范](../../../../docs/architecture/authentication.md)。
- API、DTO、Ktor、HTTP 响应、Problem JSON、序列化或 `Idempotency-Key`：读取 [网络契约](references/network-contract.md)。
- 列表首屏、刷新、cursor 分页、缓存恢复或用户/租户范围的本地数据：读取 [列表数据生命周期](references/list-data-lifecycle.md) 与 [Loading、空态、异常与 Toast 规范](../../../../docs/architecture/loading-feedback.md)。这同样适用于将作为正式 feature 起点的 Mock 列表；在决策卡中记录成熟参考实现和选定状态契约。
- 用户、租户、会话切换，或需要关闭并重建内存 worker、SSE 连接、订阅、执行器：读取 [Context Runtime 规范](../../../../docs/architecture/context-runtime.md)。
- 命中多阶段、取消/重试、资源清理、迟到结果、后台 worker 或上下文失效中的任意两项：读取 [复杂流程状态机规范](../../../../docs/architecture/state-machines.md)，并在决策卡中附状态集合、迁移表、owner/清理责任和验证用例。
- Koin、ViewModel、Compose host、composition root 或平台入口：读取 [Koin 生命周期](references/koin-lifetimes.md)。
- Compose UI，多个稳定 UI 子域，跨 destination 共享 ViewModel，或跨 feature 用户出口：读取 [Compose UI 参考](references/compose-ui.md) 与 [Compose 参数与边界规范](../../../../docs/architecture/compose-parameter-boundaries.md)。
- UI Review、页面改造、Figma/静态设计稿布局审视或视觉一致性检查：读取 [Compose UI Review](references/ui-review.md)。
- 测试与构建范围：读取 [验证参考](references/verification.md)。
- 通知、实时 SSE、系统日历授权或审批深链：读取 [平台能力专题](../../../../docs/architecture/platform-capabilities.md)。

## 默认边界

- `commonMain` 放共享 UI、业务规则、接口、DTO、仓库和 DI 定义；平台 source set 只放平台能力实现与平台入口。
- 用 `expect`/`actual` 表达真实平台能力，不能用它分叉业务规则。
- 先在 `feature/<name>` 内分 `presentation`、`domain`、`data`、`di`；一般共享业务代码、工具类或 Gradle module 只在出现实际的第二处复用后提取。与 feature 无关且由应用统一拥有的真实基础能力，即使首个消费者出现时也归入 `core`；判定见 [架构参考](references/architecture.md)。
- `UiState` 用于可渲染、可恢复的状态；`UiEffect` 只用于导航、Toast、打开外部能力等一次性事件。
- 异步失败必须转化为明确的 UI 状态或 effect，不能让异常穿透到 Composable。
- 方案只引入当前需求所需的最小状态、接口、依赖与文件；没有第二个真实消费者时不建立通用抽象。

## 参考

- [architecture.md](references/architecture.md)：稳定的分层、MVI 与跨端总纲。
- [authentication-session.md](references/authentication-session.md)：认证和会话边界。
- [network-contract.md](references/network-contract.md)：API、DTO 与网络边界。
- [list-data-lifecycle.md](references/list-data-lifecycle.md)：列表刷新、分页、缓存与数据归属边界。
- [loading-feedback.md](../../../../docs/architecture/loading-feedback.md)：读取型状态、缓存失败降级、Effect、Toast 与重试。
- [context-runtime.md](../../../../docs/architecture/context-runtime.md)：上下文身份、运行态交接、DI 与 UI 边界。
- [state-machines.md](../../../../docs/architecture/state-machines.md)：复杂异步流程的状态、迁移、操作身份、清理与验证。
- [koin-lifetimes.md](references/koin-lifetimes.md)：DI、ViewModel 与 Compose 生命周期。
- [compose-ui.md](references/compose-ui.md)：状态提升、无状态组件与重组约束。
- [compose-parameter-boundaries.md](../../../../docs/architecture/compose-parameter-boundaries.md)：Compose 参数、共享 ViewModel、导航与跨 feature 输出边界。
- [ui-review.md](references/ui-review.md)：UI Review 的冻结标准、布局审计、状态反馈与渲染验收。
- [verification.md](references/verification.md)：单元测试与 KMP 验证。
- [review-evidence-gate.md](../../../../docs/architecture/review-evidence-gate.md)：开发切片与完整 review 共用的核心结论门禁和升级条件。

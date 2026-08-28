# Orbit 前端架构主规范

本规范是 Orbit KMP 前端开发的唯一前端架构正文，适用于 Compose 页面、ViewModel、`UiState`、上下文运行态、复杂异步、状态机、Loading 反馈、认证/网络/Deep Link、协程调度、平台能力、可观测性、UI 资源和验证治理。旧 `docs/architecture/*.md` 专题文件仅保留为历史链接兼容入口，不再维护独立规则。

核心原则：

> 架构对象按 ownership 和 lifecycle 建立，不按代码量、目录或设计模式建立。一个业务事实只有一个 writable owner。简单功能保持简单，复杂度真实出现时才建立边界。

## 0. 规范定位与使用原则

### 适用范围

本规范用于长期约束 Orbit 前端的业务状态、UI 数据流、异步生命周期、Context 隔离、平台能力和验证方式。它不是类命名规范，也不是要求每个 feature 套完整架构模板。

### 原则

- 先找 authoritative source，再找 writable owner，最后才决定类和目录。
- 简单页面允许 `Route -> ViewModel -> Repository -> UiState`。
- 只有真实 owner、生命周期、Context、恢复、并发或资源清理问题出现时，才引入新边界。
- 抽象必须转移真实责任；不为未来猜测创建接口、Factory、Runtime、Controller 或 Reconciler。
- 现有代码不因规范更新批量重构，只在触及代码、出现真实 defect、owner 不清或 correctness 风险时迁移。

### 非目标

- 不引入传统 Clean Architecture 全套模板。
- 不要求每个操作创建 UseCase。
- 不要求每个 feature 创建 Runtime。
- 不要求所有页面拆成多个 Feature。
- 不要求 Repository、Controller、StateHolder 都拥有接口。
- 不建立 `BaseViewModel`、`BaseUiState`、`BaseListState` 或全局 EventBus。
- 不为了目录整齐移动没有 owner 变化的代码。

### 主模型

```text
External World
Navigation / Platform / Link
        ↓
      Route
        │ Action
        ↓
    ViewModel ── UiEffect ──→ Route / Navigation / Platform
        │
   projection
        ↓
     UiState
        ↓
     Screen
  ┌─────┼─────┐
  ↓     ↓     ↓
Feature Feature Feature
  UI    UI    UI

ViewModel
   │ orchestration only
   ↓
StateHolder / Controller / Store
                 │
                 ↓
              Executor
          ┌──────┼──────┐
          ↓      ↓      ↓
     Repository Remote Storage
          │
          ↓
     Durable State

Context
   ↓
Runtime Host / Registry
   ↓
Runtime
 ┌─┼─┐
 ↓ ↓ ↓
Store Controller Session
```

## 1. 术语与 Ownership 模型

### 适用范围

本章统一定义本规范中的核心概念。后续章节只说明何时使用、如何组合、哪些反例和如何验证，不重新定义术语。

### 核心术语

架构对象按“它拥有什么”定义，不按文件名、代码量或设计模式定义。

| 概念 | 核心问题 | 典型生命周期 | 拥有可写状态 | 执行 IO |
| --- | --- | --- | --- | --- |
| `UiState` | UI 现在渲染什么 | 页面 | 否，属于投影结果 | 否 |
| `UiEffect` | UI 执行哪一个一次性行为 | 瞬时 | 否 | 否 |
| `StateHolder` | 谁拥有一个稳定同步状态域 | 页面或 feature | 是 | 原则上否 |
| `Store` | 谁拥有跨页面或长期业务状态 | 长于页面 | 是 | 可通过 Repository |
| `Controller` | 谁决定流程何时 `start/cancel/retry/recover` | Feature / Runtime | 是 | 可协调 IO |
| `Executor` | 一个任务如何从当前 phase 推进到下一 phase | 单任务 | 是 | 是 |
| `Reducer` | Event 如何纯粹改变 State | 与 owner 一致 | 否 | 否 |
| `Reconciler` | local state 如何与 authoritative state 收敛 | Feature / Runtime | 可返回结果 | 是 |
| `Runtime` | 固定 Context 下谁拥有内存资源和生命周期 | Context | 是 | 是 |
| `RuntimeHost` | 谁切换 active Runtime | App / account scope | 是 | 可执行 teardown |
| `RuntimeRegistry` | 谁管理多个同时存活的 Runtime | App | 是 | 可执行 teardown |
| `Repository` | durable domain state 如何存取 | 长期 | 否 | 是 |
| `RemoteDataSource` | 后端协议如何访问 | 请求 | 否 | 是 |
| `Storage` | 文件、媒体、blob 如何访问 | 请求 / 资源 | 否 | 是 |
| `UseCase / Operation` | 一个完整业务动作如何编排 | 调用期间 | 原则上否 | 可协调 |

### 命名原则

一个类只有在真正拥有对应责任时才使用上述名称：

- 没有状态 ownership 的类不要命名为 `StateHolder`。
- 没有 `start/cancel/retry/recover` 生命周期的类不要命名为 `Controller`。
- 只包装一个 Repository 调用的类不要命名为 `UseCase`。
- 没有固定 Context 生命周期和 teardown 责任的对象不要命名为 `Runtime`。
- 只是搬运代码、减少行数或隐藏依赖的对象，不应借用上述架构名词。

### Action 统一

本规范统一使用 `Action` 表达进入 ViewModel / feature presentation 边界的事件。历史代码中的 `Intent` 与 `Action` 若语义一致，应逐步统一；不得在同一 feature 中人为区分两个没有实际 ownership 差异的事件体系。

## 2. 架构决策树

### 适用范围

本章用于从需求或代码坏味道出发选择最小架构边界。不要从“需要几个类”开始设计。

### 决策步骤

1. **这是 UI-only 状态吗？**
   展开、hover、scroll position、animation target、纯视觉选择、临时焦点等留在 Composable。
2. **这是页面业务状态吗？**
   页面销毁后没有继续存在的业务意义，并且只属于当前页面，放在 ViewModel 或 StateHolder。
3. **它是否形成独立稳定状态域？**
   有自己的 state、多个输入事件、状态总是一起变化、可独立测试、UI 子区域有稳定语义、会继续扩展，满足任意两项时考虑 `StateHolder`。
4. **页面销毁后它还需要存在吗？**
   conversation、upload task、background sync、download、account session 等进入 `Store` 或 `Runtime` owner。
5. **它是否拥有流程生命周期？**
   出现 `start`、`cancel`、`retry`、`recover`、Job、callback、timer、connection，考虑 `Controller`。
6. **单个任务是否存在多阶段推进并可从中间恢复？**
   例如 `init -> uploading -> completing -> submitted`，考虑 `Executor`。
7. **是否存在两个需要收敛的事实来源？**
   例如 local checkpoint + remote authoritative state，考虑 `Reconciler`。
8. **Context 改变会导致状态失效吗？**
   account、family、workspace、conversation identity 变化会使内存资源失效时，放入 context-bound `Runtime`。
9. **动作是否只是完整业务编排，但不拥有长期状态？**
   使用 `UseCase / Operation`；否则保持当前结构，不新增抽象。

### 判断顺序

```text
authoritative source
→ writable owner
→ lifecycle
→ context validity
→ UI projection / Effect
→ workflow lifecycle
→ recovery
→ resource close/delete
→ class / file shape
```

## 3. Compose Screen 实现规范

### 适用范围

适用于新增或修改 `Route`、`Screen`、页面 Content、稳定 UI 子域和 Compose 交互流。

简单静态组件、纯视觉微调和一次性叶子组件不需要 Feature 化。只要页面包含业务状态、异步状态、导航、平台能力或多个稳定子区域，就按本节设计。

### 原则

- `Screen` 只负责渲染和组合，不解释 domain runtime。
- `Route` 只连接外部世界，不承接业务状态机。
- `State` 向下、`Action` 向上、`Effect` 旁路。
- 高频状态局部化，子 Feature 只观察自己的 state。
- UI-only 状态留在 Composable，业务或可恢复状态进入 ViewModel。
- 导航、Picker、权限、Toast 等平台副作用停在 Route 或 UI adapter。

### 常见问题

- `Screen` 同时做布局、业务判断、导航、平台调用和 Flow 收集。
- `Route` 从 ViewModel 搬走了业务状态机，变成新的 God Route。
- 子组件收到整个 ViewModel、Repository、NavController 或巨型 `UiState`。
- 一次性导航、Toast、Picker 请求被做成 `Boolean` state，再由 UI reset。
- 高频输入状态改变时，整个页面和低频列表一起重组。

### 标准方案

采用单向数据流：

```text
Navigation / Overlay
        ↓
      Route
        ↓
    ViewModel
        ↓
     UiState
        ↓
     Screen
        ↓
   Feature UI
```

`State` 向下传，`Action` 向上传，`Effect` 旁路执行。`Route` 连接 ViewModel、导航、平台能力和一次性 Effect；`Screen` 只做布局和 UI-only 局部状态；稳定子区域用 `FeatureUiState + FeatureAction + Feature()` 表达。Action 是本规范的统一事件词，历史 `Intent` 若语义相同应逐步收敛。

### 核心概念

- `Route`：负责收集 ViewModel state、消费 effect、连接导航和平台能力。它不负责业务状态机、retry loop、history merge 或复杂异步生命周期。
- `Screen`：负责页面布局、Feature 组合和纯视觉局部状态。它不依赖 ViewModel、Repository、SDK、NavController 或 Activity。
- `Feature UI`：一个稳定 UI 子领域，拥有自己的 `FeatureUiState` 和 `FeatureAction`。只有当子区域有独立状态、多个操作、稳定业务语义并会持续扩展时才引入。
- `UiState`：可直接渲染的 Presentation State，不是 Domain Runtime。Screen 不应二次解释 Repository state、Reducer state 或内部 keyed store。
- `Action`：表达用户或 UI 发生了什么，例如 `Send`、`Retry`、`OpenMedia`，不表达实现细节。
- `Effect`：一次性 UI/平台行为，例如导航、Toast、Picker、权限、Share Sheet。

### 概念关系

```text
Domain / Feature State
        ↓
    ViewModel
        ↓
   UiState Mapper
        ↓
      UiState
        ↓
      Screen
  ┌─────┼─────┐
  ↓     ↓     ↓
Feature Feature Feature

ViewModel / Controller
        └── Effect → Route → Navigation / Platform
```

父层只组合子 Feature，不重新实现子 Feature 的业务规则。能否发送、能否 retry、reply mode 等业务派生放在 ViewModel 或 Mapper；阴影、滚动按钮、动画目标等纯视觉派生留在 Composable。

### 触发条件

- `Screen` 开始判断业务规则：放回 ViewModel 或 UiState Mapper。
- 某个子区域出现独立 state 和多个操作：考虑 `FeatureUiState + FeatureAction`。
- 一次性行为需要消费后 reset：改为 `Effect`。
- draft、输入光标、播放进度等高频状态影响整页：缩小状态观察范围。
- 子层需要导航或平台能力：通过语义化 action/effect 回到 Route。

### 注意事项与反例

不要为普通 `Button`、Header、Divider、Avatar 创建 Action/State。不要把参数穿透包装成无语义的 Context 对象。不要用 `remember` 复制业务 state；`remember` 只用于局部 UI state、昂贵 UI 计算或 Compose 对象。

列表必须使用稳定业务 key；异构列表按需提供 `contentType`。UiState 集合应尽量稳定，避免无关状态变化时整批重建 item。

### Orbit 示例

家庭 Ask 页面中，`AskRoute` 收集 `AskViewModel.state` 并消费 `AskEffect.OpenMediaPicker`；`AskScreen` 组合 transcript、composer 和 action bar；composer 拥有 `ComposerUiState` 与 `ComposerAction`。发送按钮是否可用由 `ComposerUiState.canSend` 给出，而不是 Screen 读取 draft、reply phase 和权限后自行计算。

## 4. ViewModel 与状态所有权规范

### 适用范围

适用于页面 ViewModel、页面级 `UiState`、子领域状态、长期会话状态和页面内业务协调。

简单页面允许 `ViewModel -> Repository`。当页面出现多个稳定状态域、长于页面的状态、复杂异步生命周期或跨子领域协调时，按本节拆分 owner。

### 原则

- ViewModel 是页面协调者，不是业务系统本身。
- 一份业务状态只有一个明确 owner 和一个可写 source of truth。
- 业务动作链路必须符合用户意图和状态 owner 的直觉：从 UI action 到最终状态变化，应能顺着命名直接读出来。
- 按状态所有权、业务生命周期和稳定子领域拆分，不按行数或函数数量拆分。
- 长于页面生命周期、可被多个页面共享或后台继续变化的状态不归页面 ViewModel。
- 有 `start/cancel/retry/recover` 的流程不应长期停留为普通 ViewModel 函数。
- ViewModel 暴露 presentation state 和 effect，不向 Screen 泄漏 Repository、SDK、Job 或内部 keyed store。

### 常见问题

- ViewModel 同时拥有 draft、timeline、selected item、Job、retry、cache、SDK callback 和导航事件。
- 为了减少行数拆出 Helper，但状态仍由 ViewModel 拥有。
- 一函数一个 UseCase，制造很多没有业务边界的类。
- ViewModel 构造函数注入大量 Repository、Service、SDK、Manager。
- UI、ViewModel 和 Repository 同时维护同一业务事实。
- 为了统一入口增加无业务含义的中转层，例如 `Action -> Intent -> dispatch -> helper -> controller`，实际只是在转发。
- Controller 通过宽泛 callback 修改其它 owner 的状态，使调用点看不出谁拥有或改变了业务事实。

### 标准方案

ViewModel 是页面协调者，不是业务系统本身。它主要保留：

```text
combine / map
direct action handlers
cross-domain orchestration
screen lifecycle entry
```

复杂度按状态所有权、业务生命周期和稳定子领域拆分，而不是按函数数量拆分。

用户动作的处理链路必须直观：

```text
UI action / platform callback
-> ViewModel named handler
-> owning StateHolder / Controller
-> Repository / Effect
```

每一层都应表达真实业务动作或真实 owner 切换。若某层只把参数重新包装、拆包或无条件转发，应优先删除。`dispatch`、`Intent`、`UseCase`、`Controller`、helper 都不是默认必需层；只有当它们承载稳定外部合同、跨调用方复用、异步生命周期、权限/上下文校验或真实状态 owner 时才保留。

状态 mutation 必须由 owner 执行。跨 owner 协调可以由 ViewModel 调用多个命名操作完成，但不得把宽泛的 `updateState` / `updatePage` callback 传给非 owner，让它间接改不属于自己的状态。

### 中层协作审视

当一个 feature 内同时出现多个 `StateHolder`、`Controller`、`Runtime`、异步资源或恢复路径时，先审视业务流协作，再决定是否做单类可读性重构。审视时必须区分：

- **Flow Owner**：谁负责把一个业务意图从入口推进到 terminal。
- **State Owner**：谁唯一写入某个业务事实或内存状态域。
- **Lifecycle Owner**：谁创建、启动、取消、恢复、关闭资源，并拒绝迟到结果。

简单顺序协作可以留在最近公共上层，例如 ViewModel 调用 2-3 个 owner 的命名操作。若流程拥有 operation identity、`start/cancel/retry/recover`、timeout、background lifecycle、Context 失效或 late result 规则，应有明确 Flow Owner / Controller / Runtime。

以下信号默认进入模块级 coordination review：新增或保留 `lateinit` controller；peer Controller 互相持有或通过 callback 控制；非 owner 经宽泛 callback 修改其它 owner 状态；多个 workflow 同时获得同一个 `StateHolder` 的宽泛写入口；start、cancel、recover、finish、cleanup 分散在多个 peer 对象；旧 callback、no-op hook 或 forwarding controller 已失去业务含义。

### 核心概念

- `ViewModel`：页面级协调者。向 UI 暴露稳定 `UiState`，接收 Action，协调多个子能力。
- `UiState`：页面可稳定渲染的状态快照，是底层业务状态到 UI 的投影。
- `Action`：统一表达外部或内部事件，避免异步回调任意修改状态；历史 `Intent` 只作为同义迁移名保留。
- `UiEffect`：一次性 UI 副作用，不进入持续 state。
- `StateHolder`：一个完整子领域的状态 owner，例如 composer、selection、filter、playback local state。
- `Store` / `SessionStore`：生命周期长于页面的业务状态 owner，例如 conversation session、upload task、download task。
- `Controller`：拥有 `start/cancel/retry/recover` 的异步流程 owner，例如 streaming reply、upload、sync、playback。
- `Reducer`：纯状态转换，表达 `State + Event -> State`，不访问 Repository、Coroutine、Android API 或文件 IO。
- `UseCase` / `Operation`：一个完整业务动作，例如发送消息、刷新 feed、导入媒体；不用于包装简单 setter。

### 概念关系

```text
                  ScreenViewModel
              projection / orchestration
                         ↓
          ┌──────────────┼──────────────┐
          ↓              ↓              ↓
     StateHolder     Controller      StateHolder
          │              │              │
          └──────────────┼──────────────┘
                         ↓
                    Domain Store
                         ↓
                      UseCase
                         ↓
              Repository / Service
```

Reducer 可以位于 StateHolder、Controller 或 Store 内部。ViewModel 可以负责调用顺序，但不实现上传、协议解析、retry 算法、history merge 或底层数据合并。

### 触发条件

- 一组状态总是一起变化、有自己的输入事件、能独立测试：考虑 `StateHolder`。
- 状态销毁页面后仍有意义、多个页面共享或后台继续变化：考虑 `Store` / Runtime。
- 出现 `Job`、`start`、`cancel`、`retry`、`recover`、callback：考虑 `Controller`。
- 大量 `when(state)`、phase 和 `copy(...)` 且无 IO：考虑 `Reducer`。
- 一个动作跨数据源或多步骤完成：考虑 `UseCase`。
- 一个用户动作需要跳过多层中性转发才能看懂最终修改：合并或删除中转层。
- 一个 Controller 需要修改其它 owner 的状态：把该修改移回 owner，或让 ViewModel 显式协调两个 owner 的命名操作。

### 注意事项与反例

不要用多个 ViewModel 互相通信模拟子领域；生命周期一致且高度共享状态时，优先普通 StateHolder 或 Controller。不要把 Repository state、ViewModel copy、UI copy 做成多份 source of truth。不要为了行数拆类，拆分必须转移真实 owner。

ViewModel 构造函数应依赖高层能力，而不是几十个底层基础设施对象。简单页面不需要完整套用所有概念。

不要为了“统一事件模型”保留第二套永久控制路径。兼容迁移入口必须临时、隔离并有删除条件；当生产调用方已全部迁到更直观的 action 或命名方法后，应删除旧 `Intent` / `dispatch` 入口，并同步让测试走真实入口。

### Orbit 示例

家庭聊天页面中，ViewModel 组合 `ConversationStateHolder`、`ComposerStateHolder` 和 `ReplyController`。输入框文字和附件归 Composer；会话消息归 Conversation；流式回复的 start、cancel、retry、recover 归 ReplyController。ViewModel 在用户点击发送时协调三者：读取 composer snapshot，调用发送操作，清空草稿，更新会话，再启动回复流程。

聊天页面的语音转写和图片选择属于 composer 输入，不应绕成通用 chat intent；会话选择属于 conversation owner；回复恢复属于 reply/history owner。会话列表置顶归 conversation list owner，发送流程只通知该 owner 执行命名操作，而不是拿页面级 callback 直接改列表。

## 5. UiState 边界与订阅规范

### 适用范围

适用于所有页面级可变状态、按 key 的页面数据、加载/失败/空态、一次性反馈和跨 feature 共享运行态。

### 原则

- 每项影响渲染的业务状态必须有唯一写入 owner 和可观察出口。
- 页面级 ViewModel 默认只向 UI 暴露一个只读 `StateFlow<XxxUiState>`，按需再暴露一个只读 `UiEffect` 流。
- `UiState` 是稳定渲染快照；`UiEffect` 只表达一次性行为。
- 内部 keyed store、request token、cursor、Job、去重集合和 generation 是协调细节，不直接给 Screen 读取。
- 共享状态只有在确有独立 owner、生命周期和多个消费者时，才允许页面直接收集其只读流。

### 常见问题

- UI 只订阅 selected key，却直接读取 `store.value[key]`，导致后续 key 内容变化不重组。
- ViewModel、UI 和 Repository 分别可写地保存同一业务事实。
- 为了“单一 state flow”把独立共享运行态复制进页面 `UiState`，制造跨 feature 耦合。
- Toast、导航或 Picker 请求放进 `UiState`，消费后再 reset。

### 标准方案

页面私有业务状态先在 ViewModel 内组合并投影：

```text
selection + keyed states + request phase
        ↓
ViewModel projection
        ↓
XxxUiState
        ↓
Screen
```

按 key 的状态必须先转成可观察整表或当前 key 投影，再进入页面 `UiState`。页面只能收集该 `UiState`，不能读取内部快照后假定它会自动更新。

### Single UiState 不等于 Single Observation Point

页面级单一 `UiState` 约束的是页面业务状态的统一 presentation 出口、source of truth 不被 UI 绕过，以及 Screen 不直接读取内部 store 快照。它不要求整个页面只能在一个 Composable 中观察完整 `UiState`。

当稳定子 Feature 存在高频状态时，可以通过 ViewModel selector、`map` / `distinctUntilChanged` 子投影、Feature-level presentation state 或 Compose 层稳定输入的局部派生缩小观察范围。前提是：不产生第二个可写业务 owner，不允许 Feature 绕过页面 projection 读取内部 mutable store，子投影来源于同一 authoritative owner，并且不为了减少重组复制业务 state。

```text
Domain owners
     ↓
ViewModel projection
     ↓
ScreenUiState
     ├── ComposerUiState   → Composer
     ├── TranscriptUiState → Transcript
     └── ToolbarUiState    → Toolbar
```

一个 source of truth 不等于一个 `collectAsState`。

### 触发条件

- 页面存在 `selectedId`、日期、tab、conversation id 等 key：检查当前 key 的内容是否被订阅。
- Composable 读取 `.value`、`storeState(key)`、`viewModel.xxx(key)`：检查是否绕过 `UiState` 投影。
- 某个状态同时被多个 owner 写入：先确定唯一 source of truth。
- 一个共享流被页面直接收集：记录共享 owner、生命周期和不并入页面 `UiState` 的理由。

### 注意事项与反例

不要在 `remember` 中镜像业务 `UiState`。不要让 Screen 根据 domain runtime 再查 selected item、计算按钮状态或拼接错误状态。详情、tab、上下文切换后的 ViewModel 作用域必须与状态合法性一致；离开页面或切 key 后的请求不能写入不再合法的页面状态。

### Orbit 示例

日历页选择日期后，`selectedDate` 和 `records.states` 必须在 ViewModel 中 `combine`，投影成当前日期的 `records`。Screen 不能只订阅 `selectedDate` 后调用 `recordsState(selectedDate)` 读取快照。

## 6. Compose 参数、导航与系统 UI 边界

### 适用范围

适用于 Composable 参数传递、导航出口、`CompositionLocal`、前台原生系统 UI 能力，以及 Route 与平台 Host 的协作。

### 原则

- 环境型 UI 依赖才使用 `CompositionLocal`；业务依赖由 ViewModel 或非 UI 协调器构造注入。
- `NavController` 只属于导航 Host/Route；Screen 和叶子组件只上抛语义化动作。
- 系统 UI 能力走 `Feature Action -> ViewModel -> UiEffect -> Route -> SystemUiGateway -> Platform Host -> Result Action`。
- 原生系统 UI 有 request identity；成功、失败、取消、Host detach 和晚到 callback 都必须清理同一 pending 状态。
- Common feature 不持有 Activity、Context、UIViewController、原生 SDK callback、URI 句柄或平台错误详情。

### 常见问题

- 为减少参数传递创建全局 Navigator、Service Locator、callback registry 或无语义 Args 包装。
- Screen 接收 `NavController`、Repository、ViewModel 或平台对象。
- Route 执行系统 UI 后吞掉取消或异常，导致 ViewModel 一直 pending。
- 原生 callback 晚到后覆盖新的系统 UI 请求。

### 标准方案

普通 Compose 参数按顺序判断：

```text
环境型 UI 依赖 → CompositionLocal
用户意图       → 语义化 callback / Action
展示输入       → 显式参数
业务能力       → ViewModel / coordinator 构造注入
```

系统 UI 固定链路：

```text
用户动作 → Feature Action → ViewModel → UiEffect
                                  ↓
Feature Route → SystemUiGateway → Activity / Window Host → 原生系统 UI
      ↑                                                          ↓
      └────────────── Result Action ← typed SystemUiResult ──────┘
```

`SystemUiGateway` 是 Activity/window 级单活跃协调器，不排队；并发请求明确失败。Platform Host 是唯一调用原生 API 的位置。

### 触发条件

- 参数跨两层以上传递：先判断它属于环境、用户意图、展示输入还是业务能力。
- 三个及以上同类导航 callback：考虑收敛成强类型 destination 或 sealed event。
- 新增权限、相册、相机、分享、第三方登录等前台系统 UI：必须走 System UI 链路。
- 需要保存 URI、Activity、presenter、delegate、launcher：检查 owner 是否在 Platform Host，且是否会及时清理。

### 注意事项与反例

不要把系统 UI 通道扩展成导航、跨 feature 消息或后台队列。Route 被取消时必须向 ViewModel 回传同 requestId 的 `Cancelled`，再重新抛出取消。Host detach 时取消在途任务并清除 Activity/presenter 引用。平台不支持能力时返回 `Failed(Unavailable)`，不得伪成功或让 feature 自行判断平台。

### Orbit 示例

登录页点击 Google 登录时，ViewModel 只发 `RequestExternalCredential(requestId)` effect。Route 调用 `SystemUiGateway`，Android/iOS Host 获取原生凭据后返回 typed result，Route 将结果映射回 `ExternalCredentialResolved(requestId, result)` intent。只有 `AuthenticationCoordinator` 和 `SessionController` 能激活会话。

## 7. Context Runtime 切换规范

### 适用范围

适用于账号、家庭、工作区、会话等身份变化会影响数据归属、权限、导航合法性、后台任务或内存资源的场景。

普通 tab、日期、筛选、排序不改变状态合法性，不应借此重建 Runtime 或导航树。

### 原则

- Context 是 identity，不是 mutable global variable。
- Context 变化意味着换 Runtime，不是 reset 原 Runtime。
- Singleton 管 Runtime，不直接充当 Context Runtime。
- Repository、Remote、Executor 在构造或启动时 capture Context，不在长期任务中动态读取 current context。
- 先 detach，再 close，再 create/restore/attach，避免旧 projection 或 observer 污染新 context。
- Cancellation 控制资源，identity/generation guard 保证 correctness。
- Switch 和 Logout 分开；普通切换不等于删除 durable state。

### 常见问题

- Singleton 直接持有当前账号下的 Map、Job、queue、retry state 和业务命令。
- Repository、Remote、Executor 在长期任务中动态读取 current account 或 current family。
- 账号/家庭切换只清 StateFlow，没有真正停止旧 observer、Job、retry timer 或 socket。
- 旧异步结果晚到后写入新家庭的 UI 或缓存。
- Koin Scope 被误认为会自动完成业务资源 teardown。

### 标准方案

Context 必须显式建模。Context 变化不是修改旧 Runtime，而是停用或销毁旧 Runtime，再创建或选择新 Runtime。

```text
Singleton
只负责：Context 观察 / Runtime 定位 / Runtime 切换

Runtime
负责：固定 immutable Context 下的状态、Job、缓存、订阅、Controller
```

切换顺序遵守：

```text
context changed
→ detach old projection
→ close old runtime
→ close old scope if any
→ create new context-bound runtime
→ restore persisted state
→ recover
→ attach projection
```

Cancellation 控制资源；generation 或 identity guard 保证 correctness。

### 核心概念

- `Context`：账号、家庭、工作区、会话等身份 fact，创建后视为 immutable identity。
- `ContextRuntime`：某个固定 Context 下真正的内存 owner，拥有 state、Job、Channel、queue、cache、observer、context-bound Repository/Remote。
- `RuntimeHost`：单 active Runtime 的 singleton host。负责 observe、detach、close、create、restore、attach，不代理所有业务方法。
- `RuntimeRegistry`：多 active Context 需要后台继续运行时使用，以 Context 为 key 管理多个 Runtime。
- `RuntimeProvider`：Scope 外消费者用来 `Context -> Runtime` 定位，不复制 Runtime 的所有命令。
- `Capability`：Runtime API 很大时拆出的窄接口，让消费者只依赖所需能力。

### 概念关系

```text
                 Context
                   │
                   ↓
          Runtime Host / Registry
                   │
                   ↓
             Runtime Provider
                   │
            Context → Runtime
                   │
                   ↓
          Context-bound Runtime
          ┌────────┼─────────┐
          ↓        ↓         ↓
      StateHolder Controller Repository
                              │
                              ↓
                        Context Remote
```

Scope 内消费者优先直接依赖 Runtime 或 Capability；Scope 外应用服务、Push、Worker、Deep Link 才通过 Provider 获取 Runtime。

### 触发条件

- Context 下存在需要统一 teardown、restore 或 isolation 的资源，例如长期 Job、socket/SSE、observer、scheduler、queue、cache、context-bound repository 或可恢复任务：考虑 Runtime。
- 只有 Repository query 需要 Context 参数，且没有 context-bound 内存资源：显式传递 Context identity，不创建 Runtime。
- 任务、缓存、连接、订阅或后台 worker 归属于账号/家庭/会话：必须显式 Context。
- 长期任务读取 `currentAccount()` / `currentFamily()`：改为构造时 capture Context。
- 切换后旧 Context 不继续后台运行：使用 Single Runtime Host。
- 切换后旧 Context 仍需后台运行：使用 Runtime Registry。
- Singleton 方法转发越来越多：改为 Provider + Runtime + Capability。
- Scope 外消费者需要 Context -> Runtime 定位：使用 `RuntimeProvider`；普通 feature 内不得为了隐藏依赖动态获取 Runtime。
- Runtime API 只有在出现第二个真实消费者只需要其中一部分能力时，才拆 `Capability`。

### 注意事项与反例

Switch 和 Logout 必须区分。普通 switch 关闭内存资源但保留 durable local state；logout/remove account 才删除账号数据、文件、token 和远端会话。

Repository query 必须包含 context identity；不能依赖“切换时清内存”保证隔离。`scope.close()` 不替代 `runtime.close()`；前者关闭对象容器，后者负责业务资源 teardown。Koin scope、factory、singleton 只表达对象构造生命周期，不能替代 durable state ownership、operation identity、session stop 或 resource cleanup。


### Session 生命周期分类

任何 session 创建时必须声明自己的 lifecycle class：

- **Page-scoped Session**：只服务当前可见页面交互，例如 Photo Picker request、voice input、临时 preview、前台授权流程。页面离开或 Host detach 时关闭。
- **Feature-scoped Session**：属于一个业务 feature，但不一定绑定单个 Composable，例如当前 conversation realtime stream 或编辑 session。页面暂时离开是否关闭，由 feature owner 根据产品语义决定。
- **Runtime-scoped Session**：属于固定 Context Runtime，例如 background upload、account sync、family realtime observer、context-bound queue。页面离开不得自动关闭。

UI lifecycle 只能直接控制 page-scoped resource。对于 feature-scoped 或 runtime-scoped resource，UI 只能发送语义化 action，最终生命周期由真正 owner 决定。

### Orbit 示例

家庭切换时，当前家庭的实时 Ask 订阅、上传队列和会话缓存必须先 detach，再 close。旧家庭的 SSE 事件即使晚到，也必须因 identity/generation 不匹配而丢弃，不能追加到新家庭 transcript。普通本地持久任务保留 family id，切回旧家庭时从 DB restore/recover。

## 8. 复杂异步模块规范

### 适用范围

适用于长生命周期、多阶段、可取消/重试/恢复、依赖网络/文件/数据库/系统资源、受 Context 影响或需要进程重启恢复的模块。

简单查询、一次 API 请求、普通表单提交不适用。不得为了形式机械创建 Runtime、Controller、Executor、接口或 Factory。

### 原则

- 先设计 ownership，再设计类。
- Durable state 和 ephemeral state 分离；Job 可以死，业务任务不能跟着死。
- Controller 决定谁运行，Executor 决定怎么运行。
- Authoritative source 必须明确，本地 projection/cache 不能替代最终真相。
- Recovery 是一等设计能力，不是失败后的补丁。
- 一个类尽量只回答一个核心问题；拆分必须转移真实 owner，而不是移动代码。
- 同一份 mutable scheduler state 只能有一个写 owner。
- 每个资源必须有明确 close/delete owner。

### 常见问题

- 一个 Manager 同时拥有状态、流程、资源、上下文、IO、调度、展示和 telemetry。
- Job 被当作业务状态，Job 死了业务任务也丢了。
- Scheduler 和单任务执行逻辑混在一起。
- DB、Runtime map、Controller progress、Executor phase、UI state 都可写地保存同一事实。
- 错误 enum 无限膨胀，把 phase 和 failure reason 混在一起。
- Recovery 是失败后的补丁，而不是一等设计能力。

### 标准方案

先按 ownership 拆，不先按目录拆：

```text
长期内存状态        → StateHolder
多步骤流程生命周期  → Controller
单任务状态机执行    → Executor
纯状态转换          → Reducer
本地/远端收敛       → Reconciler
持久化              → Repository
网络访问            → RemoteDataSource
本地资源            → Storage
上下文切换          → RuntimeHost
固定 Context 资源集 → Runtime
```

Durable state 与 ephemeral state 必须分离。Job 可以死，业务任务不能跟着死。

### 核心概念

- `Runtime`：固定 Context 下的资源和能力集合。
- `StateHolder`：长期内存状态 owner，同步、快速、无 IO。
- `Controller`：调度 owner，回答谁现在运行、并发多少、谁等待、怎么取消和 retry。
- `Executor`：单任务状态机 owner，回答一个任务如何从当前 phase 推进到下一 phase。
- `Reducer`：纯状态转换。
- `Reconciler`：本地状态和 authoritative state 的收敛逻辑。
- `Repository`：durable state 存取。
- `RemoteDataSource`：服务端 API 调用。
- `Storage`：本地文件、媒体、blob 等资源 IO。

### 概念关系

```text
                     Context
                       │
                       ↓
                Runtime Host
                       │
                       ↓
                    Runtime
          ┌────────────┼────────────┐
          ↓            ↓            ↓
     StateHolder   Controller    Projection
                       │
                       ↓
                    Executor
                ┌──────┼──────┐
                ↓      ↓      ↓
           Repository Remote Storage
                │
                ↓
          Durable State
```

Controller 决定谁运行；Executor 决定怎么运行。Remote 不决定业务 phase；Repository 不调度任务；UI 只消费投影后的状态。

### 触发条件

- 多阶段、长期 Job/worker、cancel/retry、临时资源、late result、Context invalidation、process recovery、多 coroutine 写同一状态等风险信号出现两个或以上：显式检查 ownership、operation identity、recovery 和 cleanup。检查后若现有一个 owner 已能清晰承担责任，可以保持简单；只有真实 ownership 分离后才拆 Controller / Executor / Reconciler。
- 有 phase A -> B -> C，失败后需要从中间恢复：考虑 `Executor`。
- 存在 local 和 remote authoritative 两份状态：考虑 `Reconciler`。
- 多个 coroutine 修改同一 scheduler state：串行化 ownership，使用 command loop、Mutex 或单写 owner。
- 每个资源找不到 close/delete owner：先画 ownership 图再编码。

### 注意事项与反例

不要让 ViewModel 持有或等待 Session/worker；不要让 Session 回调 Manager 或写 UI；不要让 Worker 读取全局当前 Context；不要在锁内执行长 I/O。

错误要逐层语义化：transport error 转 typed technical error，Executor 转 workflow failure，Repository 持久化失败，UI Mapper 转 presentation error。状态 phase 和 failure reason 分开建模，避免无限增加顶层失败状态。

设计复杂模块前至少回答：

```text
什么是 durable state？
什么是 ephemeral state？
authoritative source 是谁？
进程被杀后如何恢复？
Context 切换后如何恢复？
哪些操作必须幂等？
哪些资源必须 close？
```

### Orbit 示例

媒体上传模块中，`UploadTask`、part checkpoint 和 remote session id 是 durable state，归 Repository/DB；pending queue、running Job、part concurrency 是 ephemeral state，归 Controller。`UploadTaskExecutor` 负责从 init、uploading、completing 推进到 submitted；服务器 multipart parts 是 authoritative source，恢复时由 Reconciler 合并本地 checkpoint 与远端状态。家庭切换时 Runtime close 停止旧上传资源，但 durable task 保留 family id，切回后 recover。

## 9. 状态机、进度与恢复规范

### 适用范围

适用于多阶段流程、重复操作、取消/重试、迟到结果、上下文失效、提交点、资源清理或可恢复状态。

### 原则

- 复杂流程必须有显式状态集合、事件集合和迁移表。
- Job、mutex、request token、cursor 和平台句柄是协调细节，不是业务阶段。
- 未列出的事件默认不改变状态；终态不得接受进度、成功或失败回调。
- 操作身份、context identity 或 generation 是拒绝旧事件的正确性边界。
- 进度必须表达真实含义，不能把 `currentItem` 和 `completedCount` 混用。
- 提交点决定取消后删除还是保留事实；清理由资源 owner 执行。

### 常见问题

- 用多个 Boolean、`Job?` 和可空进度值共同推导当前阶段。
- 用户取消后 UI 仍停留在 Running，只是调用了 `Job.cancel()`。
- 最后一项刚开始就显示 100%，或终态后仍接收进度。
- 重复触发产生第二个 owner，旧回调覆盖新操作状态。

### 标准方案

命中复杂流程时，编码前写出最小状态设计：

```text
状态集合和终态
事件集合
迁移表
owner、operation identity、context identity
副作用、提交点、清理责任
用户可见反馈与验证用例
```

状态优先使用穷尽模型。每个状态只保存该阶段可渲染、可恢复或决定迁移的事实。状态转换可以提取为语义函数或 Reducer，但它只维护迁移，不发网络请求、不持有 Job、不决定导航。

### 触发条件

- 三个以上用户可见阶段，或阶段间文案、可用操作、完成条件不同。
- 用户可取消、重试、跳过、确认或重复触发。
- 存在临时资源、持久化提交点或必须完成的清理。
- 账号、家庭、会话或导航生命周期会使工作失效。
- 迟到结果、并发请求或旧 callback 可能回写当前可见状态。

### 注意事项与反例

简单 `Idle/Loading/Content/Error` 不需要状态机框架。只有业务需要独立 operation identity、取消语义或资源生命周期时，才建立 operation/session 对象。测试优先断言状态和可观察副作用，不断言私有 Job 排列。

### Orbit 示例

批量导入媒体时，`Running` 接受 item 完成事件并增加 `completedCount`；用户取消进入 `Cancelling`，不再启动新 item；当前 item 到安全点后进入 `Cancelled` 并清理未提交临时文件。提交点之后的已导入记录保留，不因取消删除。

## 10. Loading、空态、错误与 Toast 规范

### 适用范围

适用于读取型请求、列表首屏、刷新、分页、详情读取、短暂反馈、错误态和基础 Loading 组件选型。

### 原则

- Loading、Error、Empty 是稳定 UI 状态；Toast 是一次性 effect。
- 当前内容非空时失败应保留内容并发 Toast；当前内容为空时失败显示错误态。
- `LoadState` 只描述请求生命周期，不携带数据、用户文案、原始 `Throwable` 或 Toast。
- 有本地首屏缓存、显式刷新或真实分页时，列表一次只允许一个互斥操作阶段。
- 启用下拉刷新时，所有可刷新的可见状态都必须由可滚动容器承载；成功加载后的空结果也是可刷新的内容快照。
- Toast Host 只在 App 根创建一次；Route 消费 effect 后调用共享 Toast 能力。

### 常见问题

- 用 Toast 表示首屏等待或空内容读取失败。
- 内容非空时刷新失败替换整页错误态，丢掉可保留内容。
- 初始加载、刷新、分页由多个 Boolean 表达，出现非法并行组合。
- `AppPullToRefresh` 内的内容态使用 `LazyColumn` / `LazyVerticalGrid`，但空态退成不可滚动的居中 `Box`，导致空态无法下拉。
- Composable 观察失败 state 后直接弹 Toast，重组导致重复提示。

### 标准方案

Feature 内用户可见的远端数据加载默认接入 `LoadCoordinator` 或 `KeyedLoadCoordinator`，包括简单详情读取。仅对无刷新、无 retry 入口、无内容保留语义的启动 gate / bootstrap，允许局部使用“内容 + `LoadState`”。接入 coordinator 后，通过 `LoadStates + content/cache/cursor` 派生完整列表阶段：

```text
InitialLoading / InitialFailure
Idle
Refreshing / RefreshFailure
LoadingMore / LoadMoreFailure
```

`LoadCoordinator` 服务单 owner 的 refresh / prepend / append 请求生命周期，并保持 owner 内互斥 single-flight。`KeyedLoadCoordinator` 服务多个独立 data key，只保证同 key single-flight 和 replace 同 key；不同 key 可以并发，公共层不提供全局并发上限。需要串行的业务场景必须在业务 owner 内显式维护 latest pending、queue 或取消策略，不允许依赖 keyed coordinator 静默限流。

使用 `AppPullToRefresh` 时，手势能力来自嵌套滚动链路；只要 `enabled = true`，当前渲染分支必须保持可滚动。列表页优先把 loading、empty、error footer 和 content 都放入同一个 `LazyColumn` / `LazyVerticalGrid` 的 item；非列表空态可使用 `Modifier.verticalScroll(rememberScrollState())` 承载。不要在成功空结果、空缓存刷新失败等仍允许刷新的状态下直接渲染不可滚动的 `Box(fillMaxSize())`。

反馈选型：

```text
首次无内容等待       → 全屏 Loading 或 Skeleton
无内容读取失败       → AppErrorState
内容非空刷新失败     → 保留内容 + Toast Effect
成功加载后的空结果   → Feature-local empty state
分页失败             → Toast Effect + pagination footer retry
提交中按钮           → Button loading content
```

### 触发条件

- 列表有缓存、刷新或分页：检查是否存在唯一阶段，而不是多个 loading/error 字段。
- 页面支持下拉刷新：检查成功空结果和空缓存刷新失败是否仍能触发下拉刷新，并有对应状态测试或明确的渲染验证。
- 失败路径会保留内容：必须走 Effect 触发 Toast。
- 失败路径没有可展示内容：必须进入错误态，不能只弹 Toast。
- 页面重进时已存在可展示内容：默认复用内存快照，除非产品明确要求进入即刷新。

### 注意事项与反例

不要建立 `BaseUiState`、`BaseListViewModel`、通用列表框架或全局列表状态容器。Feature 只复用状态语义和反馈规则，仍拥有数据、cursor、去重、缓存和请求调度。ViewModel 传递结构化错误类别；用户文案在 Composable 边界用资源解析。

### Orbit 示例

家庭任务列表已有本地缓存时，进入页面立即展示缓存并静默校验远端首屏。校验失败保留缓存并发一次 Toast；缓存为空且远端失败时显示 `AppErrorState` 和 Retry。只有 `Idle` 后的用户下拉才进入 `Refreshing`。

## 11. 认证、网络与 Deep Link 边界

### 适用范围

适用于认证会话、登录方式、Token、HTTP client/Ktorfit、API/DTO、业务 envelope、认证 Header、SSE、预签名上传和入站链接。

### 原则

- 后端协议、权限和持久状态是事实来源；客户端不发明合同。
- `feature/auth` 拥有客户端认证流程；`SessionController` 是 session store 的唯一读写者和 `AuthState` publisher。
- Tokens 不进入 UI state、日志、导航参数或普通 preferences。
- `core/network` 统一拥有 `HttpClient`、Ktorfit、请求上下文、业务 envelope 失败归一化和认证 Header。
- Feature RemoteDataSource 返回 `Result<DTO>`；Repository 返回 `Result<Domain>`。
- Deep link 分三层：平台入口接收 raw URI，common decoder 转 typed intent，feature use case 应用业务规则并返回导航目标或安全 fallback。

### 常见问题

- UI、Repository、HTTP transport 或平台 SDK bridge 自己持久化、清除 session 或导航。
- 为单一 consumer API 注册 Koin binding、第二个 client/Ktorfit、全局 API service 或 forwarding DataSource。
- Deep link 在 Activity/AppDelegate 里解析业务 path、检查权限或直接导航。
- 记录 raw URI、token、Header、预签名 URL、邀请码或对象 ID。

### 标准方案

认证请求边界：

```text
AuthenticationCoordinator
        ↓
AuthRepository(domain interface)
        ↓
DefaultAuthRepository
        ↓
feature AuthApi + shared Ktorfit/HttpClient
```

会话恢复由 `SessionController` 串行化。非过期 access token 可本地恢复；过期或格式异常使用 refresh token；refresh 被拒绝才清 session。共享 HTTP 边界只做一次 refresh 和一次原请求重放；旧请求不能清掉新 session。

Deep link：

```text
Platform ingress → Common decoding → Feature use case → Route navigation
```

Feature use case 拥有登录/onboarding gate、家庭和对象权限、一次性消费、页面选择和安全 fallback。

### 触发条件

- 新登录方式：需要 UI entry、typed System UI effect/result、Repository 映射、SessionController 激活和测试。
- 新 API/DTO：检查权威合同、兼容性、失败映射、字段消费点和缺失字段默认行为。
- 新 Deep link：合同确认前不得注册 production decoder 或做产品导航。
- 请求/响应包含 token、link、code、URI 或对象 ID：检查日志和导航参数是否泄漏。

### 注意事项与反例

认证只接受约定成功码；其他业务码即使 HTTP 成功也是认证失败。`X-Client-Instance-Id` 是非敏感随机 UUID，不是硬件或广告标识。`RuntimeConfig.apiBaseUrl` 为空是构建配置错误，不是认证运行态。Deep link 原始值不得被业务层外的代码记录或解析。

### Orbit 示例

邀请链接进入应用后，平台层只把 URI 交给 `AppLinkSource`。Common decoder 只验证链接合同并生成 `InvitationDeepLinkIntent`；邀请 feature use case 检查登录、家庭权限、邀请状态和是否已消费，最后返回加入家庭页、错误页或安全 fallback。

## 12. 协程调度边界

### 适用范围

适用于平台能力、数据缓存、Repository、网络拦截器、媒体/文件处理、上传/重试任务和长期运行协调器。

### 原则

- `suspend` 不代表同步代码自动离开调用方线程。
- Dispatcher 由真正拥有同步重工作的能力决定，而不是根据类名或 API 类型猜测。
- 已经提供真正 suspend / non-blocking 行为的 API 保持调用上下文。
- UI state、Compose side effect 和 ViewModel 状态更新保持在 `Main`。
- 文件、Keychain/Keystore、ContentResolver、同步 native SDK 归拥有能力内部切到 `IO`。
- CPU 密集计算、媒体处理、大型序列化、批量集合处理归拥有能力内部切到 `Default`。
- 长期 scheduler、upload、realtime coordinator 拥有自己的结构化 scope。

### 常见问题

- 从 `viewModelScope.launch` 调用同步文件或 Keychain 操作，误以为 suspend 会自动后台执行。
- ViewModel 普遍给 Repository 和平台能力套 `withContext(IO)`。
- 整个 Ktor 请求为了 DTO 映射被外层 `withContext(Default)` 包住。
- 同一主导工作里嵌套大量细粒度相同 dispatcher hop。

### 标准方案

拥有同步重工作的编排方法在入口选择主导上下文，只在硬性平台边界短暂切换：

```text
UI / ViewModel                → Main
真正 non-blocking API          → 继承调用上下文
同步文件/Keychain/原生 SDK   → 能力实现内部 IO
CPU 密集/大对象编解码        → 能力实现内部 Default
上传/SSE/重试协调器          → 自有 scope + Default
```

### Coroutine ownership 规则

只有明确 lifecycle owner 可以创建长期 `CoroutineScope`。App 级 observer 和 startup runtime 借用 `AppRuntimeScope`；固定 Context runtime、连接/session、上传/重试 coordinator 可以创建自己的 child scope，但 child job 必须挂在 owner 的 parent job 下。

创建 child supervisor scope 时必须显式传 parent job，例如 `SupervisorJob(parentScope.coroutineContext[Job])`，再用 `parentScope.coroutineContext.minusKey(Job)` 组合 child job 和 `CoroutineName`。禁止使用 `parentScope.coroutineContext + SupervisorJob()` 表示 child scope，因为新的 `Job` 会替换旧 job，导致 cancellation parent-child 关系断裂。

借来的 scope 只能 launch 工作，不能由下游 cancel。Runtime / session 的 `close()` 是 teardown barrier：返回后自己拥有的 coroutine、channel、observer、socket 或 native resource 必须已经停止或完成必要释放。Cancellation 只负责资源停止；context identity、generation guard 和 durable ownership key 仍负责 correctness，不能被删除。

### 触发条件

- 可能访问磁盘、Keychain、内容提供者或同步原生 SDK。
- 输入来自外部且大小、条目数或耗时没有小上限。
- 包含媒体处理、密码学哈希、序列化/反序列化或批量集合计算。
- 已有卡顿、ANR、掉帧、主线程 I/O 告警，或平台文档说明耗时不可预测。

### 注意事项与反例

不得仅因为“这是网络”或“这是数据库”就机械包 `withContext(IO)`。如果具体 driver 或 SDK 文档明确存在阻塞行为，以实现事实为准。新增可注入 dispatcher 仅在必须测试调度/取消语义，或存在跨实现并发策略时引入。不得为替代少量直接 `Dispatchers` 创建全局 dispatcher 框架。取消必须保持传播，UI state 只在 Main 更新。

### Orbit 示例

媒体选择返回后，Route 只接收 typed result。读取图片 bytes、复制临时文件和解析元数据由媒体能力内部在 `IO` 执行；必要的系统 picker 启动在 `Main.immediate`，返回后继续回到 `IO` 完成准备和清理。

## 13. 平台能力边界

### 适用范围

适用于推送、实时 SSE、媒体选择、实时语音转文字，以及未来新增的跨端平台能力。

### 原则

- 基础层只提供通用能力和结构化结果；业务层拥有 endpoint、业务模型、用户可见行为和恢复策略。
- 外部 payload、SSE data、PickedImage、语音结果都是不可信输入，必须在业务层校验。
- 平台能力不得自动导航、持久化业务消息、记录 token/payload 明文或解释业务语义。
- Feature 是具体 session 的 owner，页面离开、会话结束或认证失效时必须关闭。

### 常见问题

- Push 层解析业务 payload 并直接导航。
- SSE 基础层定义业务 DTO、刷新 token、轮询兜底或记录事件正文。
- 媒体选择暴露 URI/path 或持久化平台句柄。
- 语音 session 录制、上传或记录音频/文本，或自动发送业务消息。

### 标准方案

- Push：`PushTransport` 和 `PushRuntime` 只统一冷启动、点击和前台原始 payload；应用级路由器单消费者订阅。
- SSE：`RealtimeSseSessionFactory` 按业务会话创建 session；每个 session 独占连接、原始事件流和重连任务；feature 持有并 `stop()`。
- 媒体选择：系统 Photo Picker / PHPicker；业务只得到短生命周期 `PickedImage`，按业务上限 `readBytes(maxBytes)` 并 `close()`。
- 语音输入：`VoiceInputSessionFactory` 按交互创建 session；全应用同一时刻只有一个系统识别会话。

### 触发条件

- 新平台能力要进入 common feature：先定义跨端结构化请求/结果和 owner。
- 平台结果包含原始 payload、URI、token、音频、文本或 native error：检查是否被记录或泄漏。
- 基础层想持久化、导航、轮询兜底或解释业务字段：退回 feature owner。

### 注意事项与反例

没有平台配置或 entitlement 时必须保持 `Unavailable`/`Degraded`，不得伪造可用状态。未来新增第二个真实实现后，再通过类型化配置和显式注册选择，不由业务判断平台或传输方式。

### Orbit 示例

Ask 实时回复使用 SSE 时，基础 session 只产出 `id/type/data` 和连接状态。Chat feature 校验 ask id、解析业务 delta、去重、必要时用 HTTP snapshot 恢复；SSE 层不生成 chat item，也不决定页面跳转。

## 14. 可观测性规范

### 适用范围

适用于具有异步操作、外部 I/O、重试、状态迁移、用户可见失败或联调定位需求的 feature。

### 原则

- 诊断是旁路能力，失败不得改变业务状态、重试、取消或迁移。
- 默认直接注入 `AppLogger`，在业务分支现场记录当前日志后端支持的清晰等级。
- 字段必须稳定、低基数、非敏感；禁止原始用户内容、路径、URI、token、Header、预签名 URL、raw body 和异常 message。
- 取消继续传播，不能被记录成失败。
- 只有出现第二个真实稳定消费者时，才建立 feature 专属 `DiagnosticEvent` 和 sink。

### 常见问题

- 为单一日志输出建立 Reporter、Event、Mapper、TelemetryManager 或全局 EventBus。
- 用 helper 隐藏日志等级，review 看不到分支是成功还是失败。
- 输出空字段、原始 exception message、请求/响应 body 或 URL query。
- Analytics SDK 故障影响业务流程。

### 标准方案

```text
业务流程
  ↓
logger.info/error(tag, event, fields)
```

事件名使用 feature 前缀和稳定结果语义，例如 `upload_transfer_finished`。`stage`、`reason`、`category`、`outcome` 使用 feature 拥有的枚举或 sealed 类型。日志字段值为 `null` 或空字符串时省略。

### 触发条件

- 新增重试、失败、取消、恢复、外部 I/O 或后台协调器。
- 需要联调判断哪一阶段失败、是否重试、是否完成。
- 事件需要同时输出到日志和第二个稳定消费者。

### 注意事项与反例

默认使用项目当前支持的日志等级，不为单个 feature 私自扩展日志体系。`DEBUG` 用于开发诊断和非关键内部状态；`INFO` 用于正常开始、结束、跳过、恢复和 retry plan；`ERROR` 用于用户请求失败、持久化失败、不可恢复外部 IO、协议失败或业务操作未完成。Capability 的 `Unavailable` 是否为 `ERROR` 取决于它是否违反当前产品预期：平台天然不支持且产品允许降级时不是 `ERROR`；当前流程承诺能力可用但初始化失败时是 `ERROR`。埋点 mapper 必须有自己的 allowlist，不能复用日志完整字段集合。

### Orbit 示例

上传失败时，Executor 在失败分支记录 `upload_transfer_part_failed`，字段只包含 `task_id`、`stage`、`category`、`attempt` 和安全异常类型，不记录本地文件路径、URL、Header、响应 body 或异常 message。

## 15. UI 资源、视觉 Token、本地化与时间

### 适用范围

适用于用户可见文案、无障碍描述、颜色/字体/形状/间距、图标资产、时间时区和基础 UI 资源。

### 原则

- 用户可见文案和无障碍描述必须来自 Compose Resources。
- ViewModel、Repository 和 Domain 只传结构化状态、错误类别、数量和时间，不传已翻译文案。
- 颜色、字体、形状和间距从 `MaterialTheme`、status colors 与 `orbitSpacing` 获取。
- 新 Token 必须表达跨页面稳定语义，不为单页面命名。
- 图标优先复用项目中语义一致的现有资源，其次使用 Lucide 官方资源，再使用设计提供资产。
- 业务和页面不得直接调用 `Clock`、`TimeZone` 或原生格式化器；通过 `AppTimeProvider` 和 `SystemDateTimeFormatter`。

### 常见问题

- `Text`、Snackbar、Dialog、空态、错误态、按钮中直接写自然语言。
- ViewModel 传递已经翻译的 message。
- Feature 代码散落十六进制颜色、任意间距或临时圆角。
- 手写 Canvas 图标、字符串图标名或全局图标注册表。
- 格式化时间时依赖隐式系统时区。

### 标准方案

UI 文案在 Composable 边界用 `stringResource(Res.string.xxx)` 解析；带变量文案使用资源占位符。图标放在 `composeResources/drawable/`，通过 `Res.drawable` 和 `painterResource` 加载，颜色由调用方 tint 提供。展示时间显式传入 `Instant` 和 `TimeZone`。

### 触发条件

- 新增或修改任何用户可见文案：同步补齐默认英文和简体中文资源。
- 新增图标：记录来源、语义和资源归属；不要为了少量图标引入完整库依赖。
- 新增视觉 token：证明存在跨页面稳定语义或布局规则。
- 时间影响业务或展示：检查是否使用同一 `AppTimeProvider.timeZone`。

### 注意事项与反例

非用户可见的日志 tag、事件名、路由、资源键、业务 ID、Mock 数据和测试夹具可以保留稳定字面值。业务规则不能依赖自然语言文案分支。图标画布不能替代最小触控目标。

### Orbit 示例

任务列表的“刷新失败”由 ViewModel 发出结构化错误 effect；Route 根据错误类别选择资源文案并调用 Toast。ViewModel 不传 `"Refresh failed"`，也不拼接中文句子。

## 16. 模块依赖与编译期边界

### 适用范围

适用于 package/module 依赖方向、common/platform 边界、DI composition root 和可自动化检查的架构规则。

### 原则

- Runtime ownership 和编译期依赖方向都必须清楚。
- DI container 负责构造对象，不负责定义业务 owner。
- `commonMain` 定义跨端接口、domain、presentation 和可共享规则；平台 source set 只实现不可共享的系统互操作。
- concrete implementation 的装配集中在 composition root / module registration 边界。
- 优先自动化静态检查能可靠判断、误报率低、违反后风险高的规则。

### 依赖方向

```text
Compose UI
    ↓
Presentation / Feature
    ↓
Domain capability
    ↓
Repository abstraction
    ↓
Data / Remote / Storage

App / Composition Root
    ↓
组装 concrete implementation
```

KMP 边界：

```text
commonMain
    ↓ defines
跨端接口 / domain / presentation

androidMain / iosMain
    ↓ implements
平台能力
```

### Forbidden Dependency

| 来源 | 禁止直接依赖 |
| --- | --- |
| `Screen` / Feature UI | ViewModel、Repository、SDK、NavController、Activity、UIViewController |
| ViewModel | Activity、UIViewController、原生 SDK callback、文件句柄 |
| Domain | Compose、Android、UIKit、Ktor DTO、数据库 Entity |
| Repository interface | Compose、Navigation、ViewModel |
| RemoteDataSource | UI state、导航、业务 workflow state machine |
| Storage | UI state、导航 |
| commonMain feature | Android `Context`、Activity、UIKit controller |
| platform implementation | 修改 feature business state |

允许边界：

```text
UI → semantic Action
Route → navigation / platform gateway
ViewModel → high-level capability
Repository → Remote / Local
Platform Host → native API
```

### 自动化验证建议

优先逐步加入 architecture test、Detekt 或 custom lint，覆盖高价值低误报规则：

- `Screen` 参数出现 ViewModel 或 NavController。
- UI package 直接依赖 repository / datasource / platform package。
- `commonMain` 出现 Android `Context` 或 UIKit controller。
- ViewModel 持有 Activity、UIViewController 或原生 SDK callback。
- Feature 中直接使用 `Clock.System.now()`。
- 用户可见字符串硬编码。
- 日志字段出现 token、Authorization、raw URI、header 或预签名 URL。

不要一开始自动化所有规范；先阻止静态检查能可靠判断且违反后风险高的规则。

### Orbit 示例

媒体选择能力在 commonMain 定义 `SystemUiRequest.PickImages` 和纯 Kotlin result；Android/iOS Host 实现原生 picker；feature ViewModel 只发 effect 和接收 action，不依赖 Activity、PHPicker、URI 或平台 permission controller。

## 17. Review、验证与规范治理

### 适用范围

适用于有意义开发切片、完整代码审查、规范沉淀和长期规则调整。

### 原则

- 不得只声称“已检查”；每个命中规则必须给出结论和证据。
- 结论只能是 `通过`、`不通过`、`不适用` 或 `未验证`。
- 测试通过不自动代表状态、owner、兼容性或用户可见降级正确。
- 规范是可执行长期决策，不是一次讨论、实现备忘或个人偏好。
- 新规则只在高频、已发生缺陷、平台限制、安全要求或跨端契约需要防复发时沉淀。
- 非轻量 Kotlin 开发、复杂 bug、复杂模块 review 和存量重构以 Human Traceability 为验收目标：人类第一次接手时，应能沿 `Architecture -> Coordination -> Local Reasoning -> Human Debug Simulation` 缩小责任范围。
- 复杂度治理不以 LOC、函数长度、文件数量或参数数量为完成标准；只有减少需要同时掌握的事实、semantic hops、责任区域、write entries、illegal state 组合或排障区域时，才算改善。

### 常见问题

- 交付时只写“架构合理”“已检查”“测试通过”。
- 实际命中状态机、上下文失效或用户降级，却没有 owner 和验证结论。
- 为低概率猜测或一次性取舍新增长期规范。
- 同一规则在 `AGENTS.md`、Skill 和 docs 中复制多份，后续冲突。

### Review 分级

- **Level 0：纯局部修改**，例如文案、spacing、icon、无业务行为的 Compose 调整、测试 fixture。只需要正常测试、preview 或 build 证据。
- **Level 1：普通 feature 修改**，命中页面业务 state、请求、error、navigation 或 effect。交付说明 state owner、`UiState` / Effect 边界、失败行为和验证证据。
- **Level 2：复杂边界修改**，命中 concurrency、retry/cancel、background、Context、Runtime、durable state、process recovery、API/auth/permission contract、cross-feature ownership、peer controller、callback mesh、shared mutable hub 或 lifecycle split。必须说明状态与 owner、operation/context identity、late result、recovery、resource cleanup、debug boundary、用户可见降级和验证证据。

只有命中相应风险的切片，才要求对应架构结论。`不适用` 不需要为了填模板而大量列出。

### Recovery / Fallback ROI

主流程和常见真实可达边界优先于理论完整性。新增 retry、fallback、recovery、late-result 或 duplicate-defense 逻辑前，必须证明真实产品路径、导航路径、平台行为或后端合同能够触发该失败；不特殊处理会造成实质用户影响、正确性问题、隐私风险或数据完整性风险；普通 refresh、生命周期重建、导航退出、context 切换或下一次用户动作无法低成本自然收敛；新增 mutable state、callback/back-edge、owner、跨层参数和 semantic hops 的成本值得承担。Synthetic test 的回调组合不是生产可达证据。

Terminal error 必须显式进入终态、关闭、降级或由用户可见错误承载，不得藏进 retry、empty-state、refetch 或 loading loop。一个 failure 只能有一个 recovery owner；基础层可以分类失败，但不能同时让多个层独立 clear、retry、refresh 或重建同一业务状态。

当一个边界需要跨中性 UI 层 plumbing、ViewModel 生命周期或全局 de-dup 状态、新 coordinator / registry / event bus、或只服务单个边缘场景的 generation/counter/state machine 时，停止并重新评估 reachability、impact、natural convergence 和维护成本。Security、privacy、data-loss 或平台 guarantee 可以例外，但必须有书面 requirement 和证据。

### Ownership Review Card

对于命中架构风险的修改，至少回答：

```text
State
- authoritative source 是谁？
- writable owner 是谁？
- UI 看到的是哪一个 projection？

Flow
- 用户动作到状态变化的链路是什么？
- Flow Owner 是谁？
- State Owner 是谁？
- Lifecycle Owner 是谁？
- 每一层是否有真实业务职责？
- 是否存在只包装/拆包/转发的中转层？
- 是否有非 owner 通过宽泛 callback 间接修改其它 owner 状态？
- 主链路能否用 5-7 个 semantic nodes 说明？

Lifecycle
- owner 生命周期是什么？
- page leave 后是否仍合法？
- Context 切换后是否仍合法？

Async
- 谁 start / cancel / retry / recover？
- operation identity 是什么？
- late result 如何拒绝？

Recovery ROI
- 该路径是否能由真实产品 flow / contract 触发？
- 不特殊处理会发生什么？
- 普通 lifecycle / refresh / navigation 是否已经自然收敛？
- recovery 新增了哪些 state、callback、owner 或 semantic hops？

Resource
- 谁 close？
- 谁 delete？
- process death 后如何处理？

User
- loading / empty / failure 如何表现？
- 是否有安全 fallback？

Verification
- 哪些行为已验证？
- 哪些仍是 未验证？

Debug
- input 已收到但 state 没变化时，从哪个 checkpoint 开始？
- state 已变化但 UI/output 没变化时，从哪个 projection/render boundary 二分？
- 重复事件由哪个 operation/event identity 和 owner 处理？
- flow 永不 terminal 时，terminal owner 与 pending resource 在哪里？
- recovery 后结果仍错误时，谁决定 recovery、谁执行 effect、旧结果如何拒绝？
```

### Simplicity / ROI Gate

对于任何有意义的新 abstraction、state、recovery mechanism、wrapper、executor、controller、reconciler、registry、cache 或 queue，设计和 review 必须先记录：

```text
Simplicity / ROI
- Can an incorrect/duplicate/obsolete concept be deleted instead?
- Can the main flow be constrained so the race is unreachable?
- Can the existing authoritative source/snapshot converge the state?
- Can an existing owner absorb the responsibility?
- If new complexity is still added, what real reachable path proves the simpler options insufficient?
```

“更完整”不自动等于更好；bug fix 默认不应增加长期概念。若确实新增长期复杂度，Work Order 或 review 必须说明必要性、真实可达路径，以及被拒绝的更简单替代方案。

`Wrapper`、`Executor`、`Controller`、`Reconciler` 只有拥有真实责任，并且减少 semantic hops、非法状态组合或排障区域时才通过。`minimal diff` 不是目标；当任务已经触及过期抽象时，优先删除 obsolete abstraction，而不是为了保留兼容 seam 把它藏到另一层后面。

### 标准方案

开发切片和 review 至少对命中项给出证据：

```text
状态机与重复操作
数据归属与上下文失效
业务动作链路与 owner 直觉
合同字段可达性与兼容性
失败、取消与迟到结果
Flow/State/Lifecycle Owner
debug boundary 与五类排障模拟
用户可见降级
验证证据
```

规范沉淀遵守：

```text
触发证据和目标
适用范围与非目标
职责与边界
明确动作和反例
验证与例外
```

完整 Review 只在用户要求或风险升级条件命中时进入；平时按薄切片完成最小结论卡和验证。

### 触发条件

- 改变用户可见状态迁移、失败恢复或重复操作语义。
- 改变 API、DTO、认证、权限、隐私或兼容性。
- 引入并发、取消、缓存、上下文、后台 runtime 或资源生命周期。
- 改变跨 feature 边界、数据 owner、抽象方式或 common/platform 边界。
- 引入或保留通用 dispatch、Intent、helper、callback 或 coordinator 等中转层。
- 开发自检无法证明状态、资源 owner 或失败行为。

### 注意事项与反例

`未验证` 不是失败，但必须说明缺失的是哪一类证据，例如未运行 iOS build、未验证 process death、未验证真实后端 contract、未验证平台 capability 或未执行 migration。不得用“理论上没问题”替代 `未验证`。不为未命中专题填写表格。例外必须记录真实限制、替代方案、用户/安全影响和重新评估条件。“可能以后复用”“代码更短”“感觉更完整”不是新增规范或抽象的充分理由。

业务动作链路不直观不是风格偏好，而是 review 问题。若必须反复跳转才能判断用户动作最终修改哪个 owner、某层没有真实业务职责、或 owner 通过宽泛 callback 被其它对象间接修改，结论应为 `不通过`，并给出最小删除中转层或回收 owner mutation 的方案。

### Orbit 示例

实现家庭任务分页时，交付需要说明 `LoadCoordinator/LoadStates` 如何接入、cursor 的 owner、刷新失败如何降级、旧请求如何被拒绝、运行了哪些测试或为什么未验证，而不是只写“分页已完成”。

审视聊天页面时，交付需要列出 `ConversationAction`、`TranscriptAction`、`ComposerAction` 到对应 owner 的链路。若图片选择、语音转写、发送、重试或会话选择先被包装成另一套 intent 再拆开，或发送 controller 通过页面级 callback 修改会话列表，应判为链路不直观并简化。

## 18. 使用顺序

开发或 review 时按下列顺序判断：

1. 先确认后端合同、durable state 与 authoritative source。
2. 确认客户端哪一个对象可以写该业务事实。
3. 判断状态生命周期：Composable / Page / Feature / Runtime / Durable。
4. 如果 Context 改变会使状态失效，先建立 identity 边界。
5. Compose 页面建立 `Route -> ViewModel -> UiState -> Screen` 单向流。
6. 一次性行为进入 `UiEffect`，不进入持续状态。
7. 出现独立同步状态域时引入 `StateHolder`。
8. 出现 `start/cancel/retry/recover` 时引入 `Controller`。
9. 单任务多阶段可恢复时考虑 `Executor`。
10. local / remote 需要收敛时考虑 `Reconciler`。
11. 页面销毁后仍存在的业务状态进入 `Store` / `Runtime`。
12. 明确 resource close/delete owner。
13. 最后检查 module dependency、dispatcher、logging、resource、localization。
14. 根据风险等级给出对应验证证据。

最终交付按 Review 分级提供证据：Level 0 给正常验证；Level 1 给 state/effect/失败行为；Level 2 给 ownership、identity、recovery、cleanup 和降级证据。未验证项必须明确标记。

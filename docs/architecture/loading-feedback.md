# Loading、空态、异常与 Toast 规范

本规范是 Orbit 对 Loading、页面空态、异常和短暂反馈的唯一事实来源。它解决跨 feature 的一致选型，不定义后端错误、业务重试、缓存策略或持久任务状态。

基础表现位于 `core/design/feedback/`；Feature 的 ViewModel 拥有失败分类、重试、可渲染状态和一次性 effect；Route 在最低共同 UI 边界消费 effect 并映射为 Toast。`App` 根是唯一的 Toast state owner，通过 `LocalAppToast` 向全部 Route 提供共享 UI 能力。不得引入 DI 单例、业务消息总线或让基础组件依赖 Feature。

## 读取状态模型

读取型请求统一使用 `core/design/feedback/LoadState`：

```kotlin
sealed interface LoadState {
    data object Idle : LoadState
    data object Loading : LoadState
    data object Success : LoadState
    data class Fail(val error: AppException) : LoadState
}
```

`LoadState` 只描述请求生命周期；内容、分页事实和页面业务状态仍归 Feature 的 `UiState`。它不携带数据、用户文案、原始 `Throwable` 或 Toast。网络、Repository 或 ViewModel 边界必须先将未知错误收敛为 `AppException`。

带本地首屏缓存、显式刷新或分页的列表使用 `core/design/feedback/ListLoadPhase`。它把互斥的列表操作表达为单一状态机，避免初始校验、刷新和分页出现非法并行组合：

```kotlin
sealed interface ListLoadPhase {
    data object InitialLoading : ListLoadPhase
    data class InitialFailure(val error: AppException) : ListLoadPhase
    data object Idle : ListLoadPhase
    data object Refreshing : ListLoadPhase
    data class RefreshFailure(val error: AppException) : ListLoadPhase
    data object LoadingMore : ListLoadPhase
    data class LoadMoreFailure(val error: AppException) : ListLoadPhase
}
```

`InitialLoading`、`Refreshing` 和 `LoadingMore` 成功后都回到 `Idle`。首次失败后的 Retry 回到 `InitialLoading`；刷新和分页失败的原地 Retry 则分别回到 `Refreshing` 和 `LoadingMore`。

不建立 `BaseUiState`、`BaseListViewModel`、泛型 `LoadState`、通用列表状态容器或通用列表框架。Feature 只复用状态语义和反馈规则，仍自行拥有数据、cursor、去重、缓存和请求调度。

### 推荐状态结构

无缓存、无分页的列表或独立内容区块使用简单 `LoadState`：

```kotlin
data class XxxListUiState(
    val items: List<XxxItem> = emptyList(),
    val loadState: LoadState = LoadState.Idle,
)
```

有本地首屏缓存、显式刷新或真实分页能力的列表使用 `ListLoadPhase`：

```kotlin
data class XxxListUiState(
    val items: List<XxxItem> = emptyList(),
    val phase: ListLoadPhase = ListLoadPhase.InitialLoading,
    val hasMore: Boolean = false,
)
```

`ListLoadPhase` 的每个阶段只表达一个真实操作：

- `InitialLoading` / `InitialFailure`：页面首次进入时的本地首屏缓存读取和远端首屏校验；首次失败后的 `AppErrorState` Retry 仍回到 `InitialLoading`。
- `Refreshing` / `RefreshFailure`：仅由用户显式下拉刷新触发。
- `LoadingMore` / `LoadMoreFailure`：仅表达 cursor/页码的下一页追加请求。
- `Idle`：首屏、刷新或分页已成功完成，允许下一次操作。

有非空本地缓存时，首次流程应立刻写入 `items`，并保持 `phase = InitialLoading` 静默校验远端首屏：页面展示缓存、不展示下拉刷新 spinner、也不允许分页。远端成功后以首屏结果替换 `items`、更新私有 cursor 与 `hasMore`，并将 `phase` 置为 `Idle`；远端失败时保留缓存、置为 `InitialFailure(error)` 并发出一次 Toast Effect。缓存为空时，远端失败直接显示 `AppErrorState`。

`nextCursor` 等只供请求编排使用的数据优先留在 ViewModel 私有状态；`hasMore` 是 UI 需要的分页事实。刷新成功替换列表，加载更多成功按稳定 ID 去重后追加。`ListLoadPhase` 只允许一个当前操作，不存在初始校验、刷新和分页同时进行的组合。

详情页用 `detail: XxxDetail? + loadState: LoadState`；只有支持原地显式刷新时才使用列表状态机。Gate/前置查询页只有一个首次读取操作时，也只使用 `LoadState`。多区块页面为每个独立数据源各自维护“内容 + LoadState”或其必要的 `ListLoadPhase`，不得用一个全页状态覆盖已成功的其他区块。

`LoadState` 不适用于 SSE 流、任务编排、审批执行等具有进度、取消、重连或队列语义的流程，也不强制用于登录、创建、保存、删除等写操作。它们继续保留各自的状态机；写操作可仅在按钮提交期间使用 `Loading` 语义。

## 反馈选型

| 当前状态 | 必须使用 | 规则 |
| --- | --- | --- |
| 首次加载尚无结果 | `AppFullScreenLoading` 或与内容结构对应的 `AppSkeletonBlock` | 不用 Toast 表示等待。 |
| 内容非空时刷新失败 | `AppToast.Error` | 保留当前列表或详情；不追加行内错误。 |
| 内容为空时读取失败 | `AppErrorState` | Feature 决定是否提供 Retry。 |
| 成功加载后的空结果 | Feature-local empty state | 空态是有效内容；下次刷新失败时，因为当前内容为空，显示 `AppErrorState`。 |
| 列表分页失败 | `AppToast.Error` + Feature-local pagination footer | 保留列表，发出一次 Toast，并保留原地 Retry。 |
| 单个机会来源不可用 | `AppSourcePlaceholder` | 只反馈该来源，不替换整页。 |
| 用户主动下拉刷新 | `AppPullToRefresh` | 只表示这一次显式刷新。 |
| 提交、登录、创建等按钮操作 | `AppActionLoadingContent` | 保留按钮文案，禁用和重复提交由 Feature 管理。 |
| 短时且不可安全继续操作的前台任务 | `AppBlockingLoading` | 调用方必须拥有结束、失败或取消路径。 |

“有可展示内容”只指当前可渲染内容非空，例如 `items.isNotEmpty()` 或 `detail != null`。空列表或空详情不属于可保留内容；其首次读取或显式刷新失败必须显示 `AppErrorState`，不使用 Toast。不得引入 `initialized`、`hasLoadedOnce`、`RefreshSource`、独立的 `error` 字段或平行 loading 布尔字段来改变此判断。

## 状态与 effect 边界

`UiState` 是屏幕稳定、可恢复的渲染快照，例如内容、`LoadState` 和 `hasMore`。`UiEffect` 只用于一次性事件，例如内容保留后的刷新失败、成功提示和可撤销操作。

```text
请求失败
  ├─ 当前内容非空 → 保留内容 + Failure phase/state + UiEffect → AppToast
  └─ 当前内容为空 → Failure phase/state → AppErrorState
```

不要在 Composable 中观察失败状态后直接弹 Toast，重组会使提示重复。ViewModel 只在当前内容非空时发出携带 `AppException` 的失败 Effect；初始缓存校验、显式刷新和分页可分别使用 `InitialLoadFailedWithCache(error)`、`RefreshFailed(error)`、`LoadMoreFailed(error)` 这类 Effect。Route 消费该 Effect 后通过 `LocalAppToast.current.show()` 展示 Toast。

### 列表初始缓存校验、刷新与分页

`ListLoadPhase` 对一个列表数据源一次只表达一个操作。首次读取（包括失败后的初始 Retry）只使用 `InitialLoading` / `InitialFailure`：

| 当前内容 | `phase` | 呈现 |
| --- | --- | --- |
| 空 | `InitialLoading` | 首屏 Loading |
| 空 | `InitialFailure(error)` | `AppErrorState` |
| 空 | `Idle` | Feature-local 空态 |
| 非空缓存 | `InitialLoading` | 静默展示缓存；不显示下拉 spinner，不允许分页 |
| 非空缓存 | `InitialFailure(error)` | 保留缓存，发出一次初始校验失败 Toast Effect |
| 非空 | `Idle` | 正常列表；允许后续显式刷新和满足条件的分页 |

用户显式刷新从 `Idle` 进入 `Refreshing`：成功回到 `Idle`；失败转为 `RefreshFailure(error)`。`Refreshing` 时保留内容并显示下拉 spinner；`RefreshFailure` 时保留非空内容并发出一次刷新失败 Toast Effect，无内容时显示 `AppErrorState`。刷新或失败后的原地 Retry 必须回到 `Refreshing`，不得复用初始阶段。

### 重进已缓存的按 Key 列表或详情

适用于同一 ViewModel 生命周期内、按稳定 DataKey 保存可展示内容的列表或详情。它不规定跨会话缓存时效，也不要求所有页面在重进时刷新；产品明确要求“进入即刷新”时，必须在 feature 决策卡中说明原因并覆盖相应测试。

页面重进由语义化的 `Open` Intent 处理。默认优先复用当前内存快照，只有没有可展示内容时才恢复加载：

| 当前状态 | 是否请求 | 行为 |
| --- | --- | --- |
| `Uninitialized` | 是 | 执行首次加载；可先注入本地首屏缓存。 |
| `InitialFailure` 且无内容 | 是 | 重试首次加载。 |
| `Idle`，包括空结果 | 否 | 复用当前内存快照；有内容时由用户显式下拉刷新更新，空结果同样不因重进而自动请求。 |
| `RefreshFailure` 且有内容 | 否 | 保留内容；由下拉刷新或错误态的原地 Retry 恢复。 |
| `RefreshFailure` 且无内容 | 是 | 恢复该刷新请求；再次失败时保持 `AppErrorState`。 |
| `InitialLoading`、`Refreshing`、`LoadingMore` | 否 | 保持单飞，不得因重进产生并发请求。 |
| `LoadMoreFailure` | 否 | 保留内容和底部分页 Retry，不得改为首屏刷新。 |

至少覆盖：已有内容重进不请求、无内容重进加载或重试、空态刷新失败显示可重试错误态，以及加载或分页中重进不产生并发请求。

分页从 `Idle` 且 `hasMore = true` 进入 `LoadingMore`：成功回到 `Idle`，失败转为 `LoadMoreFailure(error)`。分页失败保留列表、发出一次加载更多失败 Toast Effect，并保留底部重试入口；`hasMore = false` 时不再请求或显示加载更多。`InitialLoading`、`InitialFailure`、`Refreshing`、`RefreshFailure` 和 `LoadingMore` 时不得开始分页，避免使用未经当前远端首屏确认的 cursor。

Route 消费 effect 后通过 `LocalAppToast.current.show()` 触发 Toast。Route 不得创建 `rememberAppToastHostState()`、不得放置 `AppToastHost`，也不要把 `AppToastHostState` 传入 ViewModel 或让 ViewModel 传递已翻译文案。

## 全局 Toast 宿主与生命周期

`App` 根创建唯一的 `rememberAppToastHostState()`，并通过 `CompositionLocalProvider(LocalAppToast provides state)` 向认证、onboarding 和 `AppShell` 的全部 Route 提供。Toast 是短暂反馈；按当前产品选择，它可以在用户或租户上下文切换后短暂保留，不要求在切换时清空。

`AppToastHost` 只能由 `App` 根放置一次：

- 根 Host 使用 IME 与系统导航安全区 inset，并预留 `bottomNavigationHeight` 和底部间距，确保不会覆盖 Main Tabs 的底部导航。
- 认证、onboarding、`AppShell`、feature nested navigation 和普通页面均不得创建独立 Host/state，也不得层层传递 Toast state。

同一时刻应用只渲染一个 Host；跨业务的新 Toast 仍替换旧 Toast。

## 共享组件契约

### `AppToast`

`AppToast.Success`、`AppToast.Error` 和 `AppToast.Action` 是视觉与短暂交互机制；message 和 action label 由 Route 通过 Compose Resources 解析后传入。`Action` 仅允许一个可选操作，点击时先关闭 Toast 再执行 callback。

`AppToastHostState.show()` 始终替换当前 Toast，不排队；`dismiss()` 立即关闭。每次展示有独立 ID，因此旧的自动关闭任务不能关闭替换后的新消息。

| 类型 | 图标与时长 | 限制 |
| --- | --- | --- |
| Success | 成功图标，3 秒 | 无 action。 |
| Error | 错误图标，4 秒 | 不放 Retry；恢复入口属于错误态或原地 UI。 |
| Action | 成功图标，5 秒 | 只有一个简短 action。 |

Toast 使用主题反色 surface、12dp 圆角、16dp 水平/12dp 垂直内边距，并由 Host 以 `pageHorizontal`（当前 20dp）保持页边距。其内容以 polite live region 宣告，不抢占焦点或阻断底层内容。

### `AppErrorState`

`AppErrorState(title, description, actionLabel, onAction)` 只用于内容区没有任何可保留结果时的读取失败。它填充调用方提供的内容区，不遮挡 App Shell、Tab、返回按钮或导航栏。图标置于带 `outline` 边框的 56dp 圆形 `surface`，并以主题 `error` 着色；可用 action 使用 Material 主按钮并保持最小 44dp 触控目标。Feature 传入 `null` action 表示不可重试。

标题、说明和操作都必须由 Compose Resources 在 Composable 边界解析；ViewModel 只传递结构化错误类别。

## Loading 组件契约

| 场景 | 必须使用 | 规则 |
| --- | --- | --- |
| 全屏等待 | `AppFullScreenLoading` | 仅限启动、门禁等没有可保留内容的短暂等待。 |
| 内容仍未知 | `AppSkeletonBlock` | Feature 组合与最终内容结构接近的骨架。 |
| 列表继续加载 | `AppPaginationLoading` | 只在有下一页且请求中显示。 |
| 机会来源 | `AppSourcePlaceholder` | 保持调用方卡片尺寸，区分 Loading 与 Unavailable。 |

不新增 `AppEmptyState`、`AppInlineError`、`AppInlineLoadingBar` 或全局确定进度卡。不同 Feature 的空态 CTA、分页恢复和进度语义仍归 Feature-local UI；确定进度仅在确有本地、可量化进度时就近实现。

## 无障碍与视觉 token

- 所有用户可见文案和无障碍描述来自 Compose Resources；基础组件不硬编码自然语言。
- 颜色、字体、形状和间距从 `MaterialTheme` 与 Orbit 设计 token 获取，不在 Feature 代码散落十六进制值或任意尺寸。
- 页面级错误通过 polite live region 宣告；装饰性图标不重复播报。Toast 不抢占焦点。
- 页面 spinner 为 `40.dp`，下拉刷新为 `20.dp`，分页与按钮 spinner 为 `18.dp`，阻塞层 spinner 为 `30.dp`，机会来源占位图标为 `24.dp`。

## 验收与例外

- [ ] 当前内容非空的失败产生 Toast；当前内容为空的失败显示 `AppErrorState`。
- [ ] 有非空本地首屏缓存时立即展示缓存；首屏远端校验期间不显示下拉刷新 spinner 且不允许分页；校验失败保留缓存并仅发出一次 Toast Effect。
- [ ] 首屏失败后的 ErrorState Retry 回到 `InitialLoading`；只有 `phase == Idle` 后的用户下拉才进入 `Refreshing`。
- [ ] 分页失败保留列表、产生一次 Toast，并保留局部重试入口；机会来源失败保持局部恢复入口。
- [ ] Toast 覆盖而不排队；替换后旧超时不会关闭新 Toast。
- [ ] 新增/修改文案同时提供默认英文和简体中文 Compose Resources。
- [ ] 读取列表至少验证：首屏 Loading、空内容失败 ErrorState、空结果空态、非空内容刷新失败的 Toast Effect；分页额外验证追加、失败重试和无更多数据。
- [ ] 修改 `core/design/feedback/` 后运行对应 `commonTest` 和 `./gradlew :app:composeApp:ktlintCheck`；视觉变更在 Android/iOS 各做一次烟测。

新反馈样式必须先证明上述组件无法表达真实场景，并在本文件补充触发条件、状态语义、无障碍策略和验证方式。单页面视觉差异、预期复用或“可能需要”不构成新增基础组件的依据。

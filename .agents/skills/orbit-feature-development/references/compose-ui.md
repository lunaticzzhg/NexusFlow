# Compose UI 参考

## 状态所有权

- 将业务、可恢复和可由调用方控制的状态放在 ViewModel，并提升到最低共同拥有者；不要机械提升到 app 根部。
- Screen 负责收集 `UiState`、派发 Intent、消费 `UiEffect` 与导航；Content 只根据参数渲染；小组件只接收最少的值和回调。
- 组件默认无状态。仅当状态纯视觉、短暂、调用方无需控制或恢复、且无需独立测试时，才允许组件内部 `remember`。
- 不在 composition 中发请求或写业务状态；页面首次加载通过键稳定的 `LaunchedEffect` 派发一次 `Load` Intent，其他请求通过明确的用户 Intent 发起。

## 参数与边界

遵循 [`docs/architecture/orbit-frontend-architecture.md`](../../../../docs/architecture/orbit-frontend-architecture.md) 中的 Compose 参数、导航与系统 UI 边界。简要规则：环境型 UI 依赖使用根部 `CompositionLocal`；导航由 Host/Route 持有并以语义化 callback 上抛；`UiState`、`Modifier` 和上层拥有的可恢复状态保持显式；业务依赖由构造函数注入。不要为消除参数穿透而引入全局 Navigator、Service Locator 或无语义的参数包装对象。

当 Route/Screen 拆出独立 Content 区块时，Intent 的派发边界停在 Route/Screen：Content 与叶子组件接收最小的展示状态和语义化回调，例如 `onRetry`、`onDateSelected`、`onOpenDetail`，而不是整个 feature 的 `onIntent`。只有组件本身就是完整 Screen 的交互出口、且需要表达完整 Intent 集合时，才传递 `onIntent`。这保留单向数据流，同时避免渲染组件耦合 ViewModel 的命令词汇。

## Compose 文件组织

- `XxxRoute.kt`、`XxxScreen.kt` 或具备状态收集职责的 `XxxPage.kt` 保留状态收集、Intent 派发、effect 消费、导航回调和页面级布局编排。拆出的 Content/叶子组件只接收最小的显式 UI 数据、`Modifier` 和语义化回调，不得接收 ViewModel、Repository、NavController 或整个 `UiState` 作为便利参数。
- ViewModel 只能在 Route、Screen 或上述状态型 Page 创建/接收。若同一状态必须跨 sibling Page 或嵌套 destination 存活，在最低共同导航边界创建并显式传给这些状态边界；不得因为视觉上同属一个 Tab 而共享 ViewModel。
- 按独立视觉语义拆分文件，例如 `XxxHeader.kt`、`XxxCalendarContent.kt`、`XxxTimeline.kt`、`XxxAlbumGrid.kt`。不要使用 `Utils`、`Parts`、`Components` 等泛化文件名；单 feature 的组件继续留在该 feature 的 `presentation/`，只有第二个真实消费者出现时才提取到 `core`。
- 当 `presentation` 内出现稳定 UI 子域，且它满足下列三项中的至少两项时，使用语义化子包：拥有独立的 ViewModel/Contract、拥有多个同域 Page/Screen/Content 文件、拥有独立状态机或导航入口。根 `presentation` 只保留跨子域 Route、导航、共享组件和主题/图标；不得因单个文件、临时视觉拆分、行数或预期复用创建子包。
- 容器必须有唯一职责：每个 `Box`、`Row`、`Column`、`Surface` 都应明确提供排列、叠放/对齐、共享修饰、布局作用域、手势/语义边界或状态/动画边界之一；没有职责不得创建。先让子节点直接归属父布局，只有至少两个兄弟确实共享修饰或需要新的布局语义时才新增容器。
- `Box` 仅用于叠放、对齐、绘制/背景层或点击遮罩；`Column`/`Row` 仅用于相应方向排列、共同修饰或提供 `weight` 作用域；`Surface` 仅在需要颜色、形状、边框、阴影或 tonal elevation 等 Material 容器语义时使用。单子节点且无共同修饰的 `Row`/`Column` 不得创建。
- 尺寸约束由最近的责任节点声明一次。若带 `weight` 或填充约束的子节点已经决定父布局尺寸，父级不得重复写 `fillMaxSize`；只有父级自身拥有全屏背景、覆盖层、全局点击/语义边界等独立尺寸职责时，才声明全尺寸。评审时由内向外执行删除测试：移除后约束、视觉、交互和语义都不变，即删除该容器或修饰。
- 一次性、少于约 30 行、且仅服务一个父 Composable 的私有辅助函数可留在同一文件。不要把单个小控件、加载态、空态或错误态机械拆成多个文件；同一内容区的紧密状态分支应保留在一个 Content 内。
- 当文件难以定位内容、一次修改反复跨越独立视觉区块，或页面级职责已经混杂时，修改前评估能否整体迁出一个内聚区块。行数和 Composable 数量只能作为复杂度信号，不能单独触发拆分；新增功能不得继续堆叠到职责已混杂的页面文件，除非记录当前边界不可拆的具体原因。
- 拆分按薄切片进行：下次修改大文件的某个区块时，优先把该区块及其私有辅助函数整体迁出，而不是发起无关的全量重构。每个切片完成后必须能独立编译并保留原有行为。
- 业务状态、Intent 和错误路径继续由 ViewModel 测试覆盖。拆出的 Content 仅在包含复杂渲染分支、关键点击或无障碍语义时补 Compose UI 测试；不要因文件移动机械增加测试。

## 重组约束

- 不复制 `UiState` 到 `remember`；不要用 `remember` 掩盖错误的状态所有权。
- `LaunchedEffect` 使用稳定且必要的 key；一次性 effect 必须被消费一次。长生命周期 effect（例如收集 Flow）只以决定其生命周期的 ViewModel、业务 ID 等作为 key；需要在 effect 内使用但不应触发重启的 callback、标题或环境值，使用 `rememberUpdatedState` 读取最新值。
- 列表提供稳定 key；昂贵计算、排序、过滤和 I/O 移到 ViewModel/use case。
- 需要跨分段、tab 或返回栈切换保留的滚动/选择状态，由最低共同 Compose 宿主持有；列表数据、刷新和分页状态仍归 ViewModel，具体规则见 [列表数据生命周期](list-data-lifecycle.md)。
- 仅在确有可测收益时使用 `remember`、`derivedStateOf`、`@Stable` 或 `@Immutable`；不要把它们当作默认性能装饰。
- 参数保持小、清晰和稳定；不要把 ViewModel、Repository、Koin 或平台对象传入可复用组件。

## 页面完整性

- 每个异步屏幕明确呈现 loading、content、empty、error 与提交中状态。
- 考虑 window inset、键盘、长文本、小屏和横向尺寸；不要把固定设计稿尺寸当布局事实。
- 为可交互元素提供语义、合适触达面积和必要的 test tag；为关键内容与错误状态补 Compose UI 测试。

## 设计 Token

- 颜色、字体与形状从 `MaterialTheme` 获取；警示色使用 `MaterialTheme.orbitStatusColors`；共享布局尺寸从 `MaterialTheme.orbitSpacing` 获取。
- 页面和业务组件不得直接写颜色、间距、圆角等视觉规格；仅在组件内部存在 Material 无法表达且不跨页面复用的视觉细节时例外。
- 新 Token 必须表达跨页面的稳定语义或布局规则；不要为单一页面或单一组件命名 Token。

## 图标资产

- 图标来源按此顺序决策：项目中图形、笔画和语义均一致的现有资源 → [Lucide 官方图标库](https://lucide.dev/icons/) → 设计团队提供的资产。不得仅因资源属于另一 feature 就复用；也不得仅因图形近似而改变原有语义。
- 需要 Lucide 图标时，先按动作或目的地选择官方图标，再下载其官方 24 × 24 SVG；禁止凭名称猜测、手写 Canvas 路径、重新推导 path，或把 SVG path 嵌入页面/App Shell Kotlin 文件。
- 运行时图标统一转换为 Android VectorDrawable XML，放在 `app/composeApp/src/commonMain/composeResources/drawable/`，并通过生成的 `Res.drawable` 与 `Icon(painterResource(...))` 加载。每个从 Lucide 引入的 XML 顶部必须以注释记录 Lucide 图标名和官方 URL，便于升级、核对和替换。
- SVG 转换必须保留原始 path、fill/stroke、opacity、stroke width、line cap 与 line join；标准 Lucide 图标使用 24dp 画布、24 × 24 viewport、2dp round stroke、round cap、round join。转换工具无法无损支持时，不得退回 Canvas，须先确定可接受的资源格式方案。
- 图标颜色由调用方通过 `MaterialTheme` tint 提供，VectorDrawable 不得硬编码产品色；图标画布不能替代最小 44dp 触控目标。
- 资源命名使用 `ic_<owner>_<semantic>.xml`，例如 `ic_memory_calendar_x.xml`。只有三个及以上 feature 实际使用同一图形和语义时，才迁移为无 feature 前缀的全局资源；不要从单一 feature 过早提取。
- Kotlin 组件间传递 `DrawableResource`，禁止 `"calendar-x"` 一类字符串图标名、`when(name)` 分发器和自建静态图标渲染器。Lucide 无合适语义时才可为具体需求增加自定义 vector，并遵循同样的命名、来源记录与几何规范。
- 不为少量图标新增完整图标库依赖、通用注册表或图标框架；仅添加已有产品消费者的资源。资源变更必须通过目标平台编译；关键交互图标还需人工核对至少一个真实界面状态。Review 必查来源、资源归属、语义命名、向量参数、类型化传递与全局提取门槛。

## 本地化

- 用户可见文案必须来自 Compose Resources；UI 负责解析资源，业务层只传状态、错误类别和数据，不能传已翻译文案。非 UI 的语言判断依赖 `AppLocaleResolver`，不能直接读取平台 Locale。
- 参数化文案使用资源占位符，不能在代码中拼接句子；业务规则不能依赖自然语言文案分支。
- 新增语言时补齐资源覆盖并明确未知语言回退；当前根部跟随系统资源环境。手动应用语言切换延后到 Key-value 与平台 Locale 应用能力就绪后实现，不能在 composition 中修改全局平台配置。

## 时间与时区

- 业务与页面不得直接调用 `Clock`、`TimeZone` 或原生格式化器；运行期时间通过 `AppTimeProvider` 获取，并使用其启动时快照的 `timeZone`。
- 展示时间通过 `SystemDateTimeFormatter` 格式化，并显式传入同一 `Instant` 与 `TimeZone`；不要依赖格式化器的隐式时区。

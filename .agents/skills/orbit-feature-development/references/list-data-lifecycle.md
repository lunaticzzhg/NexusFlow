# 列表数据生命周期

适用于有首屏加载、刷新、游标分页和可选本地恢复的任意 feature，不绑定具体业务类型或接口字段。

## 开发前清单

在设计或修改数据加载逻辑前，逐项明确；答案不明确时先补充产品约束或查看既有实现，不以客户端猜测代替事实。

- `ContextIdentity` 是什么？例如账户、会话、家庭、工作区；它变化时哪些导航、ViewModel、内存状态与后台任务必须失效？
- `DataKey` 是什么？它必须包含所有会改变结果集的业务参数，例如日期、内容 ID、筛选和排序；cursor 不属于 DataKey。
- 当前是单个 DataKey，还是多个 DataKey 会并发存在、各自刷新或继续分页？只有后一种才需要按 key 隔离内存状态。
- 数据是服务端事实、可恢复的业务快照，还是可全局复用的二进制媒体？分别确定内存、业务缓存与媒体缓存策略。
- 业务快照的归属 scope 是什么？无有效 scope 时必须不读不写；是否要求切回某个 scope 后恢复其本地快照？
- 同一 DataKey 的缓存注入、首屏刷新、下拉刷新与分页如何决定新旧关系？失败时页面保留什么、用户如何重试？

## 分层与数据事实

- `RemoteDataSource` 返回 `Result<wire DTO>`；`LocalDataSource` 只持久化原始 DTO，并以 `DTO?` 表达缓存 miss；Repository 是 DTO 到 domain 的唯一映射与数据源协调边界，并返回 `Result<domain>`。网络失败归一化规则见网络契约。
- 当 wire contract 已冻结时，Mock remote source 必须实现与真实 remote source 相同的接口和 DTO 边界；替换真实 HTTP 实现只能改 DI binding，不改 Repository、ViewModel 或 UI。wire contract 尚未冻结时，正式 Mock 至少保持 Repository 的 `Result<domain>` 边界与完整生命周期覆盖，不得为 Mock 臆造 DTO 或网络层。
- Repository interface 只暴露 domain 模型和标准 `Result`，presentation 不依赖 DTO、缓存格式或 cursor 细节。

### 正式 Mock 列表不是静态预览

`Mock` 只描述数据来源，不降低正式 feature 的数据加载、状态和验证契约。只要页面会作为后续真实接口实现的起点，就属于正式 Mock 列表；即使当前没有 HTTP、缓存或 cursor，也必须按本节规则建模。

- 开发前必须选择至少一个同职责、已测试的成熟列表实现作为参考，并在决策卡中列出参考文件、沿用的 Contract / ViewModel / Screen / 测试模式，以及每项有意差异。没有参考或没有明确差异理由，不得开始实现。
- 正式 Mock 的 Repository 必须保留未来真实数据源所需的结构化 `Result<domain>` 边界；Mock 以成功、空结果和可控失败覆盖该边界。不得让 Mock 绕过 Repository、直接向 ViewModel / UI 提供固定 `List`，也不得以“后端未 ready”为由把接口设计成永远成功。
- 正式 Mock 列表至少拥有可渲染的首次加载、成功、空结果、首次失败和 Retry 状态。若产品已明确提供下拉刷新，则同时实现刷新中和刷新失败；若尚无真实 cursor / 缓存合同，不伪造分页或持久缓存，但必须在决策卡写明“不适用”、原因和后端合同冻结后的重新评估条件。
- Mock fixture 必须覆盖当前 UI 的正常、空和失败分支；测试至少证明默认加载、空结果、失败后重试，以及当前产品支持的刷新或视图切换。Mock 的用户可见文案仍遵循 Compose Resources；fixture 内容可使用稳定测试数据。

纯设计预览、一次性静态 Composable 或用户明确要求“不进入正式 feature”的演示页面可以例外。例外必须在决策卡记录页面用途、没有 ViewModel / Repository 的原因，以及一旦进入正式 feature 时重新按本节实现的触发条件；不得把例外页面直接演化为生产 feature。

## 缓存范围与归属

- 默认只持久化“可恢复的最小列表快照”；不默认持久化详情、后续页或纯派生 UI 状态。
- 缓存必须绑定稳定的数据归属 scope，例如账号、家庭或租户。无有效 scope 时不读写缓存。
- scoped 缓存以请求开始时捕获的 scope 作为读写 key，不能用请求结束时的当前身份改写归属。若请求由纯 context-keyed ViewModel 发起、协程会随该 ViewModel 销毁并传播取消、Repository 没有后台 scope/worker/subscription/非协作 callback，页面生命周期已负责使旧调用失效，Repository 无需重复比较当前 scope；否则 Repository 必须在写入前重新校验 scope。
- 可恢复缓存的版本边界由 feature 私有 cache key/schema version 唯一表达。不兼容 DTO 或 Domain 字段/语义变化必须升级该版本；不做就地迁移或基于 mapper 失败的猜测性清理。LocalDataSource 的存储或 JSON 编解码失败直接视为 cache miss；Repository 不为假设性的 mapper 失败增加 try/catch、删除或刷新。恢复策略必须有证据和测试。
- 缓存容量语义必须写明。例如“设备单槽、带 owner 标记”表示不会跨 owner 读取，但新 owner 的写入会覆盖旧槽；当产品要求账户切换后恢复各自首屏时，使用多 owner 容器并只替换当前 owner 的 entry。媒体二进制缓存按资源身份全局复用，不随账户切换清理。
- 读、写和“校验后删除”必须形成原子事务，避免一个请求的清理删除另一个请求刚写入的有效值；单进程单实例缓存可用同一把 `Mutex`，已有存储原子能力时优先复用。

## 首屏、刷新与分页

- 对用户可见的列表加载阶段使用明确、可渲染的状态：未请求、首次加载、首次失败、内容稳定、刷新中、刷新失败、加载更多、加载更多失败。可用实现不必共享同一个 sealed 类型，但不得依赖一组 Boolean、可空列表和嵌套条件推导当前阶段。
- 首次加载、主动刷新与加载更多是不同的用户意图和失败语义。ViewModel 可以复用小的成功写入或错误映射辅助函数，但入口和状态迁移必须具名；不得用 `isInitial`、`isRefresh` 等 Boolean 参数在同一业务方法中切换路径。
- `UiState` 或按 Key 的可观察状态保存 UI 需要直接渲染的快照、加载阶段和 `hasMore`。cursor、请求 token/generation、去重集合和“最新请求”序号属于 ViewModel 的协调细节；它们只有确实影响 UI 渲染时才进入可观察状态。
- 首屏请求进行中时禁止分页，即使已经显示缓存；缓存只改善可见内容，不改变首屏请求的所有权。
- 下拉刷新成功时完整替换首屏，不能按 ID 跳过同 ID 项的内容更新。
- 产品支持下拉刷新时，成功空结果与空缓存也属于可刷新的内容快照；对应 UI 分支必须保持可滚动承载，例如列表 item 或 `verticalScroll` 空态，不能退成不可滚动的全屏 `Box`。
- 当同一 DataKey 明确允许并发或抢占刷新时，每次首屏替换递增 generation/version；任何较早的后续页请求完成后不得回写当前列表。默认单飞不因重复刷新额外引入版本。
- 只在后续页追加处按服务端稳定实体 ID 去重，保留第一次出现的顺序；仍采用服务端返回的 `nextCursor` 与 `hasMore`，避免因重叠页卡住分页。
- Loading、refreshing、paging 与 error 是独立且可渲染的状态。失败保留现有内容并允许明确重试；不要用全屏 loading 覆盖已有缓存或列表。

### 分页合同门槛

- 只有后端合同同时定义稳定的下一页定位信息（例如 `nextCursor`）和是否仍有下一页的事实（例如 `hasMore`）时，feature 才能暴露加载更多状态、入口或重试。不得根据当前条数、空数组或客户端猜测合成分页事实。
- 缺少该合同而产品仍需限制读取量时，使用一次明确的固定快照请求；不保存 cursor、`hasMore`、伪分页 Intent 或不可达的加载更多 UI，并在调用处保留 TODO，写明所需的后端分页字段和重新评估条件。
- 仅当产品明确接受“固定快照可能截断旧数据”时才可采用此临时策略；否则先补齐后端合同。验证必须证明 UI 不会显示无法履行的加载更多动作，并记录固定上限的用户影响。

### 同 Key 刷新默认单飞

- 对同一 `ContextIdentity × DataKey`，首屏、下拉刷新与重试默认单飞：当该 Key 已处于首屏/刷新 loading 时，后续入口直接返回，不再发起第二个远端请求。
- 所有能触发同一列表刷新的 UI 入口必须收敛到 ViewModel 的一个私有刷新入口；ViewModel 是请求调度 owner，负责 loading gate 与可见状态。仅在例外的抢占或并发场景才引入请求 token/generation；Repository 不得为补偿重复调用维护 feature 刷新版本表。
- Repository 仍须在请求开始时捕获 scope，并以该 scope 作为缓存 key。纯 context-keyed ViewModel、协作取消且无 Repository 后台执行所有权时，ViewModel 销毁与取消负责旧调用失效；存在后台 scope/worker/subscription/非协作 callback 或页面外 caller 时，Repository 才须在写入前重校验当前 scope。取消不能被任意后台路径当作正确性的唯一前提。
- 例外仅在产品明确要求“同 Key 强制刷新覆盖正在进行的请求”、或底层请求能被安全取消且新旧请求有可验证的替换语义时成立；此时必须记录触发入口、取消/覆盖规则和回归测试。

## 身份与 UI 边界

- UI 不传递 owner ID，也不直接读写缓存。会话能力以只读、可观察的当前归属事实提供给 Repository。
- 认证/会话恢复是账号缓存生效的前提。不要为 QA 或未登录壳退回匿名共享缓存；使用正常会话路径或明确的 mock session。
- 不为这些规则提前引入 Paging 框架、全局缓存管理器、事件总线或通用状态机。只有实际出现第二个同构消费者或现有边界无法表达时再提取。

## 按 DataKey 隔离异步状态

- 一份可独立展示、刷新或追加的数据由 `ContextIdentity × DataKey` 唯一标识；DataKey 必须包含会改变结果集的业务参数，例如日期、相册 ID、筛选或排序，不包含 cursor 等同一列表的推进状态。
- 不同 DataKey 的 loading、错误、缓存快照和请求版本必须独立。切换当前展示的 Key 只改变观察目标，不得以正确性为由取消、覆盖或清空其他 Key 的请求和状态。
- 当同一 DataKey 明确允许并发或抢占时，新请求必须使旧结果失效；请求令牌或 generation 必须在清空后仍不可与旧令牌碰撞。默认单飞无需为重复刷新新增 token/generation，但可保留既有 `KeyedStateStore` token 作为缓存注入、分页或可迟到结果的防御。取消仅可作为资源优化，不能是防止旧结果写回的唯一手段。
- 本地缓存注入必须是原子的“仅在该 Key 尚未被加载或替换时写入”操作。慢到的缓存不能覆盖已经开始的远端刷新或用户操作。
- 基础状态容器只保存按 Key 的内存状态与旧响应拦截；请求、缓存读写、游标、去重、账户身份和 UI 语义仍归 feature 的 ViewModel 与 Repository。
- `KeyedStateStore` 只在多个 key 能真实并存、并各自保留独立可见状态（例如内容、loading、错误、草稿或进行中操作）时使用。单详情或单一数据集使用 ViewModel 的局部状态；不得为了统一形式引入 keyed store。
- keyed store 必须以一个只读状态流输出其按 key 快照，页面 `UiState` 从该输出派生，不再维护可写的第二份 key→state 镜像。它可用 generation/epoch 拒绝同 key 的迟到响应，但不拥有请求、Job、cursor、缓存或业务状态迁移。
- 验证至少覆盖：切换当前观察 key 不清空其他 key；旧 key 或旧 generation 的结果不能覆盖新状态；store 不因取消恰好成功才保持正确。若 key 不再可并存，应删除 store 而非保留空泛基础设施。

## 最小职责模板

保持调用链显式，不用全局管理器或事件总线隐藏归属：

```text
UI
  观察当前 ContextIdentity × DataKey 的渲染状态，发送用户事件
ViewModel
  生成/持有请求版本，编排缓存注入、刷新、分页与可见失败状态
KeyedStateStore（仅多个 DataKey 独立并存时）
  保存按 key 的内存状态，拒绝同 key 的旧请求回写
Repository
  在请求开始时捕获归属 scope，映射 DTO ↔ Domain，协调远端与本地缓存
LocalDataSource
  以 feature 私有格式、在单一原子临界区内读写 scoped 原始 DTO
```

- 单详情或单一列表不为统一形式引入 `KeyedStateStore`；使用 ViewModel 内局部 request token/generation 即可。
- 分页的所有权（cursor、去重、首屏替换、`hasMore`）保留在 feature 的 ViewModel 状态模型中；其中 `hasMore` 通常是可渲染状态，cursor 与去重集合通常是内部协调状态。不因单个场景建立 `KeyedPagingStore`。
- ContextIdentity 的内存失效使用最低覆盖范围的 Compose `key(contextId)` 重建导航与 ViewModel；不以 Koin `scope` 代替页面生命周期。
- 媒体二进制缓存按稳定资源身份（资源 ID + 版本或不可变 URL）全局复用；业务列表、权限和可见性仍由 scoped 业务快照决定。

## Review 与最小验证

每个涉及列表、缓存、刷新或上下文切换的改动，review 必须明确回答：

- 两个不同 ContextIdentity 是否会串读、串写、复用旧内存状态或保留失效导航？
- 两个不同 DataKey 同时加载时，是否互不取消、覆盖或清空？
- 同一 DataKey 连续刷新后，较早结果和旧分页结果能否被拒绝？
- 缓存是否只影响对应 scope 与 key；损坏或删除一个 scope 的数据是否会影响其他 scope？
- 是否新增了没有第二个真实消费者的通用缓存、分页、DI scope 或事件总线？
- 该请求的执行所有权是什么：是否由 context-keyed ViewModel 独占并协作取消，还是存在 Repository 后台 scope、worker、订阅、非协作 callback 或页面外 caller，因而需要 Repository scope 重校验？
- 缓存字段或语义是否发生不兼容变化，并已升级 feature 私有 cache key/schema version？
- 缓存恢复是否有对应的兼容性证据与测试，而非依赖 mapper 失败后的自愈？
- 支持下拉刷新时，成功空结果、空缓存和空结果刷新失败是否仍在可滚动容器内，并且 `canRefresh` / `isRefreshing` 语义与空快照一致？

最少覆盖下列与本次改动相关的行为，而非测试第三方库：

- 有缓存且首屏远端未完成时，不会发起下一页。
- 成功空结果是可刷新快照；用户触发刷新后进入刷新中，失败时保留空快照并给出刷新失败反馈。
- 刷新会淘汰旧 generation 的分页响应。
- 重叠 cursor 不产生重复实体；首屏刷新仍可更新同 ID 内容。
- owner 切换后，迟到响应不会写入新 owner；无 owner 时不读写。
- 损坏 JSON 视为 cache miss，但不删除原值；后续有效写入可覆盖该 key。DTO 映射失败不触发猜测性清理；缓存清理不会删除其他 owner 的有效写入。
- 多 owner 本地恢复被产品要求时，A/B 分别写入、读取、删除后仍各自独立；媒体资源缓存无需按 owner 重复下载。

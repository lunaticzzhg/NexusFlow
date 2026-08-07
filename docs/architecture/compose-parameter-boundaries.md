# Compose 参数与边界规范

本规范定义 Orbit Compose 页面之间如何传递依赖、状态与用户动作。目标是保留单向数据流和可测试性，同时避免中间层仅为转交参数而存在。

## 决策顺序

遇到一个要跨 Composable 传递的值时，按下列顺序判断：

1. 它是否是整个 Compose 子树共享、随 UI 环境变化的能力？如主题、间距、系统 UI、当前时区、日期格式化器。是则使用 `CompositionLocal`。
2. 它是否是一次用户意图，需要由更高层处理？如返回、打开详情、请求原生系统界面。是则通过显式、语义化 callback 上抛。
3. 它是否是当前组件的展示输入、布局约束或由调用者拥有的可恢复状态？如 `UiState`、详情数据、`Modifier`、`LazyListState`。是则保持显式参数。
4. 它是否是业务能力或持久化事实？如 Repository、UseCase、Clock。是则由 ViewModel 或非 UI 协调器通过构造函数注入；不得由 Composable 查找。

不能仅因参数经过两层就创建全局对象、Context 包装器、通用 Navigator 或 Service Locator。

## 环境型 UI 依赖

应用根部在 `CompositionLocalProvider` 中提供环境型依赖；只有 Compose UI 可以读取它们。当前包括：

- `LocalSystemUiGateway`
- `LocalAppTimeProvider`
- `LocalSystemDateTimeFormatter`

ViewModel、domain、data 与普通 Kotlin helper 禁止读取 `CompositionLocal`。业务逻辑需要当前时间时，依旧构造函数注入 `AppTimeProvider`。

```kotlin
CompositionLocalProvider(
    LocalAppTimeProvider provides timeProvider,
    LocalSystemDateTimeFormatter provides formatter,
) {
    AppShell()
}
```

预览和 Compose 测试必须在需要时提供固定的替身，不能依赖宿主或 Koin 的隐式全局状态。

## Route、Screen 与导航

- 只有导航 Host/Route 持有 `NavController` 并调用 `navigate`、`popBackStack`。
- `Screen`、Content 和叶子组件不接收 `NavController`，也不直接导航。
- 子层通过语义化 callback 上抛用户意图；相同目标的多个 callback 合并为一个强类型目标或 sealed event。
- 跨 feature 出口只上抛源 feature 自己拥有的语义数据；目标 feature 的 Route、导航 DTO、ViewModel 或页面启动参数只能在功能整合 Host/Route 适配，不得下传到源 feature 的 Screen、Content 或叶子组件。
- 单一、明确的动作保留单个 callback，例如 `onBack()`、`onOpenTask(taskId)`。

```kotlin
// Host owns framework navigation.
InspirationListRoute(
    listState = listState,
    onOpenDetail = { destination -> navController.navigate(destination) },
)

// Screen emits a feature semantic intent, not a NavController command.
onOpenDetail(VlogDetailRoute(item.id))
```

不要把导航 callback 改为 `CompositionLocal` 或全局 Navigator；这会隐藏依赖、降低复用性并使预览与测试变得困难。

## 显式状态与布局参数

以下参数即使跨一两层传递也应保持显式：

- `UiState`、展示数据和错误状态；
- `onIntent` 等 ViewModel 交互出口；
- `Modifier`；
- 由上层拥有、需要跨切换保留的 `LazyListState`；
- 叶子组件局部的 `onClick`。

状态必须由最低共同拥有者持有。以灵感列表为例，`DailySections` 持有 `LazyListState`，所以在日常/灵感分段切换时滚动位置不会丢失；它不是应被隐藏的参数。

## 开发与评审清单

- [ ] 环境型 UI 依赖只在根部提供，且没有穿透 Route/Screen。
- [ ] ViewModel、domain、data 没有读取 `CompositionLocal`。
- [ ] 没有向 Screen/组件传递 `NavController`、Repository 或 ViewModel。
- [ ] 三个及以上同类导航 callback 已收敛为强类型 destination 或 sealed event。
- [ ] `UiState`、`Modifier`、可恢复状态与局部点击事件仍然显式，且状态拥有者明确。
- [ ] 中间层连续两次原样转发参数时，已按本规范确认它属于环境、导航、状态/布局还是业务依赖。

## 非目标

- 不把 `CompositionLocal` 变成任意依赖的 Service Locator。
- 不为参数数量创建无业务语义的 Context/Args 包装对象。
- 不建立全局 Navigator、事件总线或跨 feature callback 注册表。

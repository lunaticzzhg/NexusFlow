# Kotlin / Compose Local Code Shape

代码形态是 Local Reasoning 的最后一层，不覆盖 ownership/state/transition 问题。

## Kotlin

- 方法名优先表达业务意图，不表达 counter/flag/implementation mechanism。
- private helper 只有形成概念、抽象层、side-effect boundary、contract 或真实复用时才提取。
- 同一抽象层的方法放在一起；公共入口和主要 transition 靠前，底层 helper 靠后。
- 避免 `data/state/item/handle/process/manage/runtime/helper` 作为缺乏上下文的泛名。
- `Manager/Coordinator/Runtime` 必须有一句稳定职责；不能成为无法归类逻辑的收纳箱。
- 没有真实第二个 caller 时，不为未来复用创建 options/context/actions wrapper。
- 重复 exhaustive `when` 若来自模型缺失，优先改善模型而不是新增 helper。

## Compose

- Route/screen wiring 与可复用视觉块分开；按完整视觉/交互单元提取，不按行数切片。
- state 持有在最低合法边界；叶子 Composable 不隐藏 DI/navigation/repository/platform call。
- 公共可复用 Composable 通常接受 `modifier: Modifier = Modifier`；固定 leaf/host 可例外。
- callback 使用用户动作语义命名，例如 `onRetry`, `onSelectRecommendation`，不要用 `onAction` 统一吞掉领域语义。
- Composable 很长但只是同层级视觉 section，不自动视为高 reasoning complexity。
- Composable 很短但同时持有多份 state/effect/navigation/async ownership，仍可能是高复杂度。

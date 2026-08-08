# 跨端系统 UI 接入规范

本规范约束所有需要前台原生界面的能力：Google Credential Manager 登录、运行时权限、系统日历授权、通知设置、深链，以及未来同类能力。

目标是让 shared feature 保持平台无关，并使取消、异常、窗口销毁和迟到回调都有确定结果。它不是通用事件总线，也不承载导航或业务状态。

## 一条固定链路

```text
用户动作 → Feature Intent → ViewModel → UiEffect
                                  ↓
Feature Route → SystemUiGateway → Activity / Window Host → 原生系统 UI
      ↑                                                          ↓
      └────────────── Result Intent ← typed SystemUiResult ──────┘
```

- **ViewModel**：拥有 feature 的 `UiState`；只发语义化 `UiEffect`，只接收 `Intent`。不得持有或查找 `Activity`、`Context`、`UIViewController`、原生 SDK callback 或权限控制器。
- **Route**：只把该 feature 的 Effect 映射为 `SystemUiRequest`，并把 Result 映射回对应 Intent；不得写业务规则、会话、导航或持久化。
- **SystemUiGateway**：每个 Activity/window 一个实例，协调单个前台系统任务。并发请求立即以 `Failed(Unavailable)` 返回，绝不排队。
- **Platform UI Host**：唯一可以调用原生 API、持有当前 Activity/window presenter、注册 Activity Result 或原生 delegate 的位置。Android 由 `MainActivity` 驱动；iOS 由窗口级 `IosPlatformUiHost` 驱动。
- **Feature/domain**：只接收纯 Kotlin 的结果数据，绝不暴露平台对象、URI 句柄或原始 SDK 错误。

## 新能力接入模板

### 1. 定义最小的请求与结果

在 `core/systemui/SystemUiGateway.kt` 增加一个强类型 `SystemUiRequest` 子类和对应 `SystemUiResult` 子类。两者必须携带相同的 `SystemUiRequestId`。

```kotlin
data class SelectDocument(
    override val id: SystemUiRequestId,
    val mimeTypes: Set<String>,
) : SystemUiRequest

data class DocumentSelected(
    override val id: SystemUiRequestId,
    val document: SelectedDocument,
) : SystemUiResult
```

请求只表达原生能力所需的最小参数；结果只包含跨端可用的数据。业务筛选、任务编排、导航和文案归 feature，不进入 `core/systemui`。

### 2. 在 feature Contract 中成对定义 Effect 与 Intent

```kotlin
sealed interface ComposerEffect {
    data class RequestDocument(val requestId: SystemUiRequestId) : ComposerEffect
}

sealed interface ComposerIntent {
    data class DocumentResolved(
        val requestId: SystemUiRequestId,
        val result: DocumentResult,
    ) : ComposerIntent
}
```

ViewModel 发出 Effect 时保存当前 `requestId`；只接受同一 ID 的结果。取消、失败和成功都必须清理 feature 的 submitting/pending 状态。

### 3. 由 Route 执行网关调用

```kotlin
collectEffects { effect ->
    when (effect) {
        is ComposerEffect.RequestDocument -> {
            val result = systemUiGateway.execute(
                SystemUiRequest.SelectDocument(effect.requestId, allowedMimeTypes),
            )
            viewModel.dispatch(
                ComposerIntent.DocumentResolved(effect.requestId, result.toDocumentResult()),
            )
        }
    }
}
```

Route 被取消时必须把相同 ID 的 `Cancelled` 结果回传给 ViewModel，然后重新抛出 `CancellationException`。Result 映射应穷尽 `Success`、`Cancelled`、`Failed(Unavailable)` 和 `Failed(Unknown)`；不能吞掉结果。

### 4. 在两个平台 Host 中实现同一请求

- Android：在 `AndroidPlatformUiHost.execute()` 增加分支；原生 launcher/SDK 回调只产生同 ID 的 typed `SystemUiResult`。`MainActivity` 的请求收集器在 active 校验后调用 `SystemUiTaskSource.complete()`，Host 不注入 task source。
- iOS：在 `IosPlatformUiHost.execute()` 或 `IosSystemUiCoordinator.executeWithPresenter()` 增加分支；delegate/SDK continuation 是 Host 的实例状态，不能是全局状态。
- 若某平台不支持该能力，显式回传 `Failed(Unavailable)`。不得制造空实现、伪成功或 feature 内的平台判断。

## 强制生命周期规则

| 场景 | 必须行为 |
| --- | --- |
| Route/调用方取消 | Route 先将同 ID 的 `Cancelled` 回传 Feature；Gateway 随后发布该 `requestId` 的取消，平台 Host 只取消匹配的原生任务。Feature 必须退出 pending。 |
| Activity/window detach | Host 取消在途原生任务，Gateway 取消 active request，随后清除 presenter/Activity 引用。 |
| 原生任务抛异常 | Host 回传同 ID 的 `Failed(Unknown)`；collect 协程不得因此结束。 |
| Host 已忙或能力不可用 | 明确回传 `Failed(Unavailable)`；不得静默忽略请求。 |
| 晚到 callback | 通过 `requestId` 和 active 校验忽略；不得覆盖新任务结果。 |
| 成功、失败或取消 | 平台 pending delegate/job 与 feature pending 状态都必须清理。 |

`SystemUiGateway` 是窗口级单活跃协调器，不可注册为进程单例，不可保存 Activity、presenter 或业务状态。

## 平台实现边界

| 层 | Android | iOS |
| --- | --- | --- |
| composition root | `MainActivity` 取得 Activity-scoped `AndroidSystemUiViewModel` 并收集请求 | `AppDelegate` 创建窗口级 gateway 与 `IosPlatformUiHost` |
| 原生宿主 | `AndroidPlatformUiHost`，实例绑定当前 Activity | `IosPlatformUiHost`，实例绑定当前 window presenter |
| 可持有的运行时状态 | Gateway 当前任务（瞬态）、feature state | Gateway 当前任务（瞬态）、feature state |
| 禁止保存 | Activity、Context、全局 launcher | 全局 UIViewController、全局 continuation/delegate |

Gateway 的当前任务是窗口级瞬态状态，不保存、不恢复、不重放；只有 feature state 能按 feature 自己的状态恢复规则保存。平台 SDK 的 URL callback、Activity Result、delegate 都由 Host 消费；回调完成后必须清理它们持有的 closure/delegate。Feature 代码不得为了“方便”绕过 Host 直接调用 SDK。

## 已有能力的参考

- Google 登录与业务 Session 边界：[认证集成规范](authentication.md)。
- 权限：`core/calendar/CalendarPermissionRoute.kt`。
- Android 平台执行：`core/platform/AndroidPlatformUiHost.kt`。
- iOS 平台执行：`core/systemui/IosSystemUiCoordinator.kt` 和 `iosApp/Orbit/IosPlatformUiHost.swift`。

优先扩展这套 gateway/host，而不是新建 feature 专属的 Activity 注入、Notification、全局单例或 callback registry。

## 验收清单

新增系统 UI 能力必须同时满足：

- [ ] ViewModel、domain、repository 不依赖平台 UI 类型或原生 SDK。
- [ ] Request、Result、Effect、Intent 以同一个 `requestId` 串联。
- [ ] 已支持的平台有明确的成功、取消和异常结果；未支持的平台明确返回 `Failed(Unavailable)`，不得伪造成功。
- [ ] Host detach、Route 取消、异常和迟到回调不会留下 active/pending 状态。
- [ ] 为 gateway 关联/取消行为补充测试；为 feature 的 Effect→Intent 映射补充测试。
- [ ] 运行 `./gradlew :app:composeApp:allTests`、`./scripts/check.sh android`、`./scripts/check.sh ios`；涉及原生授权或第三方 SDK 时补真机烟测。

## 非目标

- 不把系统 UI 通道扩展为导航、跨 feature 消息或后台工作队列。
- 不为尚未存在的第二种 Host、第二个窗口模型或第三方 SDK 建立注册框架。
- 不在 common feature 中推断平台、权限策略或原生错误细节。

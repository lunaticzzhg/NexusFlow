# 架构参考

## Feature 形状

```text
feature/<feature>/
  presentation/  Intent, UiState, UiEffect, ViewModel, Screen
  domain/        model, use case, repository interface
  data/          DTO, mapper, repository implementation
  di/            featureModule
```

开始时保持在 `composeApp` 内。一般共享业务代码、工具类或 Gradle module 仅当已有第二个真实消费者时才提取；真实基础能力的归属见下文。

## 业务与平台能力边界

同一业务在 Android 与 iOS 必须只有一套权威行为。默认将业务逻辑放入 `commonMain`；平台 source set 只实现无法共享的原子系统能力。

| 归属 | 内容 |
| --- | --- |
| `commonMain` | 业务状态和迁移、输入校验、产品限制、命名和缓存策略、请求编排、重试/取消/幂等、失败分类、资源生命周期、业务模型与用户可见行为。 |
| `androidMain` / `iosMain` | 系统日历、系统 UI、权限、通知、原生 SDK、后台执行和平台 callback 的最小互操作。 |

共享层决定“做什么、何时做、失败后怎么办”；平台层只决定“如何通过当前系统完成一个原子能力”。调用了平台 API 不代表外围流程应放到平台层。

### Core 归属与平台实现

`core` 与“是否需要平台实现”是两个独立维度。只要能力的职责、接口、模型和错误语义不依赖任何 feature，且应由应用统一拥有，就归 `core`：无论它当前有几个消费者、是否依赖平台 API。

- 平台无关的基础能力（例如时间、ID、通用结果类型、事件 cursor）在 `core` 的 `commonMain` 实现。
- 需要系统 API 的基础能力（例如系统日历、权限、系统 UI、通知）在 `core` 的 `commonMain` 定义契约，在 Android/iOS source set 实现。
- 基础能力按功能归类，例如 `core/network`、`core/navigation`；接口、参数、结果和错误类型不得出现 `Plan`、`Task`、`Approval`、`Tenant` 或其他 feature 业务概念。
- 日历访问、通知等真实基础能力不因首个消费者是某个 feature 而归该 feature 所有。第二个消费者门槛只用于一般共享业务代码、工具类或 Gradle module 的提取，不能阻止基础能力进入 `core`。

平台端口应小、强类型、表达能力而非业务工作流。例如日历端口只处理授权、查询和创建事件；通知端口只处理权限与投递请求。任务 feature 决定调用顺序、审批门槛、状态迁移、缓存保留与失败语义。

不要让 Android/iOS 分别实现包含审批校验、任务状态迁移、SSE 重连策略、缓存清理或重试策略的完整工作流。此类编排由 `commonMain` 的 feature service/use case 统一拥有；平台实现仅完成通知、深链、日历授权等原子系统调用。

平台能力返回共享的结构化结果，不能泄漏 `Activity`、`Context`、`UIViewController`、URI/绝对路径、原始 SDK 异常或用户文案。ViewModel/共享编排负责将结果转为 feature 状态或 effect。

只有存在经证实的平台限制、且用户可见行为必须不同，才允许业务分叉。方案与 review 必须记录限制依据、两端行为、状态与数据影响、失败恢复差异和验证方式；“当前两端实现不同”不是分叉理由。

跨端功能 review 必查：

- 业务规则、状态迁移、缓存/命名/清理、重试与失败策略是否只在共享层实现一次；
- 平台实现是否仅包含系统互操作和最小转换；
- 端口是否表达能力而非 feature 工作流；
- 共享业务中具有状态、决策、失败恢复或跨端收敛意义的逻辑是否由 `commonTest` 覆盖；纯 DTO、接线与无分支转换是否采用了足够的最小验证；平台层是否只补必要的平台能力验证。

## 复用与提取边界

- 业务语义默认留在 `feature/<feature>` 内，不为假设中的未来需求提前抽象。
- 基础能力必须与具体业务解耦：接口、命名、参数和错误类型不得包含某个 feature 的业务概念。
- 一般共享业务代码、工具类或 Gradle module 仅当出现第二个真实消费者，或当前重复已造成明确维护风险时才提取；真实基础能力按“业务无关且应由应用统一拥有”判断，不能因暂时只有一个消费者而留在 feature。
- 需求实现和代码 review 必须逐项审视新增的组件、方法与小型能力：若其职责是应用统一机制，且接口、命名、参数、错误和资源不携带 feature 语义，就应直接归入语义对应的 `core/` 目录，即使当前只有一个消费者；不得为了就近调用或减少文件改动放在业务模块。仍含业务规则、业务模型、文案、权限或工作流语义的实现留在 feature，不因“可能复用”提前提取。
- 同一 app 内优先提取到共享 package；仅在跨 feature 的依赖边界或构建需求明确时，才拆为 Gradle module。
- 共享能力只处理通用机制，例如网络、存储、平台能力、通用 UI 组件或通用状态处理；业务规则、业务模型和业务文案仍归 feature 所有。
- 共享层不得反向依赖 feature、Screen、ViewModel 或业务 DTO；feature 通过接口和构造函数依赖它。
- 优先复用已有能力；新增共享抽象前，先确认现有实现无法通过小范围扩展满足需求。
- 为具有业务分支、状态或失败语义的共享能力添加独立测试；纯接线由受影响 target 的依赖解析和编译证明。feature 测试继续覆盖业务组合与用户可见行为。

## 轻量 MVI

```text
UI event → Intent → ViewModel → UiState
                              └→ UiEffect
```

- Intent 表达用户动作或外部输入；不要将它变成一套通用事件总线。
- ViewModel 是 feature 的状态写入者。采用不可变状态并只暴露只读流。
- UiState 是屏幕完整渲染所需的稳定快照；不要把临时状态镜像在 `remember` 中。
- UiEffect 是一次性事件，消费一次，不用于持久页面状态。
- 不建立 Reducer；当分支变复杂时，提取私有 ViewModel 方法或领域用例，而不是引入额外状态层。

### Presentation Contract Placement

- 有 ViewModel 的 feature 将对外可见的 `UiState`、`Intent`、`UiEffect` 与页面 `Step` 放在 `XxxContract.kt`；`XxxViewModel.kt` 只保留 ViewModel 本体及其私有辅助实现。
- 不将每个契约类型拆成单独文件，也不为此引入通用 Contract 基类、框架或注册机制。
- domain model、DTO、repository model 不属于 presentation contract，继续留在各自分层。
- 此规范适用于新增或正在修改的 feature；不为格式统一批量迁移既有代码。

## 数据与序列化

- 外部请求和响应 DTO 使用 `@Serializable`；对未知字段保持容忍，新增字段优先可选并给安全默认值。
- data 层完成 `DTO ↔ Domain` 映射；UI、UiState 和 Intent 不依赖 DTO。
- Repository interface 面向 domain，implementation 面向网络/存储；错误转换为可供 presentation 决策的结果。API、DTO 与 repository 的具体边界见 [网络契约](network-contract.md)。
- 保持 API 变更可加性：旧客户端遗漏新字段时行为不变，旧响应含义不得重用或改写。
- `HttpClient` 仅由 feature 的 data 层具名 API/data source 通过构造函数使用；domain、ViewModel、Composable 与 UI 不得发起 HTTP 请求。每个 feature 的 DTO 与 endpoint 归其 data 层，不创建全局 API service 或通用请求包装器。
- 非敏感偏好只通过 `KeyValueStore` 使用：feature 的 data source 先取得自己拥有的 namespace，再定义私有的 typed key；不得使用裸 key、跨 feature 读写或清除其他 namespace。`KeyValueStore` 仅存语言、主题、onboarding 等可丢失的小型偏好，绝不存 Token、会话、密码或私密内容。
- 凭据、Token、刷新 Token 等小型秘密只通过 `SecureStore` 使用：feature 同样先取得私有 namespace，再定义私有 `SecureKey`。`SecureStore` 仅支持字符串且没有 `Flow`、缓存或自动重试；秘密不得进入 Composable、UiState、日志或普通存储。会话快照、启动恢复、清理顺序和认证 Header 归独立会话能力所有，不由 `SecureStore` 推断。
- 进程前后台状态和构建运行事实通过 `AppLifecycle`、`AppRuntimeInfoProvider` 读取；它们只提供平台事实，不承担重连、刷新、导航、权限或会话副作用，也不采集设备唯一标识、广告 ID 或设备指纹字段。
- 需要前台系统界面的操作（OIDC 登录、权限、日历授权、通知设置）统一遵循 [跨端系统 UI 接入规范](../../../../../docs/architecture/system-ui.md)；它们不允许绕过该链路。
- 通知、实时 SSE、日历授权与审批深链的能力契约、生命周期和平台限制，统一见 [平台能力专题](../../../../../docs/architecture/platform-capabilities.md)。本总则只约束它们不得反向携带 feature 业务语义。

## 依赖注入与跨端

- Koin module 只在 composition root 负责组装；feature 通过构造函数接收依赖。
- `commonMain` 放接口、共享实现、module 与唯一的 `initKoin`；平台 source set 只放实际实现或 factory。
- DI、应用级依赖、ViewModel 与 Compose 的生命周期边界见 [Koin 生命周期](koin-lifetimes.md)。
- 真实平台能力（文件、通知、系统设置等）用小接口或 `expect`/`actual` 隔离；业务流程保持 common。
- 平台能力返回可处理的成功/失败结果（例如 `Result<Unit>` 或 feature 定义的结果类型）；ViewModel 将失败转为友好的 `UiState` 或 `UiEffect`，而非忽略或抛给 UI。

### Koin 装配边界

- 只在 Android `Application` 与 iOS `AppDelegate` 启动一次；只有 composition root 可以查询 Koin。
- module 与初始化入口统一定义在 `commonMain`；平台启动入口先注入运行环境，再调用 common `initKoin` 注册全部共享 module。
- 平台差异通过 `expect`/`actual` factory 解决，不创建 Android/iOS 专用 Module 或初始化入口；仅在能力没有共享消费者时才允许平台专用装配。
- 新增或改变具有业务分支、生命周期或装配风险的 module 必须有装配测试；纯依赖接线可由受影响 target 编译验证。feature 测试不依赖全局 Koin 状态。

### 上下文运行态边界

- 涉及用户、租户、任务/会话切换，或需要销毁并重建内存 worker、连接、订阅、执行器时，遵循 [Context Runtime 规范](../../../../../docs/architecture/context-runtime.md)。
- `AppContextSnapshot` 是当前身份的唯一可观察事实；`ContextRuntimeCoordinator` 是 Runtime 生命周期的唯一调度者；Runtime 不得自行监听上下文。
- Runtime 的 `close()` 必须可等待并真正停止其资源；先由内到外关闭、再由外到内启动。Compose 页面状态仍使用最低身份边界的 `key(...)`，不得用 Koin scope 代替页面生命周期。

### 既有结构与代码风格

- 实现前先搜索同职责既有能力，复用其目录、命名、可见性、DI、错误处理与测试写法。
- 没有真实复用或构建边界时，不新增 module、顶层目录或平行架构；既有模式有问题先提出统一方案，不引入不一致命名或抽象。
- common 的 `expect`/接口与平台 `actual` 实现同名，采用 `Xxx.kt`、`Xxx.android.kt`、`Xxx.ios.kt`；基础能力的 Koin、测试与注释布局对照相邻既有能力保持一致。
- 对职责漂移保持坏代码嗅觉：当应用入口、composition root 或任一类反复接收彼此无关的平台逻辑时，先评估是否已有一个清晰、内聚的边界可收拢这些真实消费者。不要仅因文件变长而拆分；原生回调本就属于入口时保留在入口。若已有真实消费者，优先提取一个小型、强类型宿主，让入口只负责挂载；不得为此建立万能分发器、注册表或框架。

### 运行配置边界

- `RuntimeConfig` 及其 Koin module 属于 `commonMain`，是 App 唯一的类型化运行配置入口；平台 source set 只负责构造值，业务代码通过构造函数消费。
- 新字段必须已有真实消费者，并保持不可变、类型化、非敏感；禁止万能 Map。
- 密钥、Token、业务策略和业务状态不得进入运行配置。

### 导航边界

- 共享 Compose UI 的导航统一使用官方 Compose Multiplatform Navigation；在 `commonMain` 定义 `@Serializable` route，并由 App 根的 `NavHost` 聚合 graph。
- `NavController` 只由 App/Route/Screen 层持有和调用；禁止注入 ViewModel、Koin、domain 或 data。
- ViewModel 只发语义化 `UiEffect`，例如打开某个 ID；Route/Screen 层消费 effect 并执行导航。
- route 只携带最小标识，不传 DTO、完整对象、应用状态或 ViewModel。业务 route 与 graph 归所属 feature，App 导航根只负责聚合。
- 改用 Navigation 3、原生导航或第三方库前，必须以官方资料证明其跨端能力、生命周期、升级与迁移收益足以覆盖统一官方方案；不能仅因新颖而替换。

### 方案质量门槛

- 每个方案先审视已有实现、官方能力与最小自定义方案，并记录为何所选方案在当前约束下更简单、清晰且可维护。
- 新依赖、版本、跨端支持和平台 API 必须以官方文档核验；不以第三方示例或 Skill 的时效性作为唯一依据。
- 方案必须明确所有权、状态边界、失败行为、兼容性与验收证据；实现与 review 均检查这些项。
- 只为当前需求引入最小状态、接口、依赖和文件。没有第二个真实消费者时，不创建通用框架、注册表或预期复用的抽象。

### 可观测性边界

- 业务代码通过构造函数依赖 `AppLogger`；每条日志显式提供受校验的 `LogTag`，它表示稳定的业务或技术场景，不得由用户输入动态生成或携带敏感信息。`event` 表示稳定动作，`fields` 只补充有限类型的非敏感上下文。
- 共享 logger 统一负责等级、字段安全、格式与异常类型摘要；平台 sink 仅输出已处理文本，平台日志 API 不得出现在业务或 UI 代码中。
- 仅在已有真实消费者时扩展日志字段或能力；远程日志、崩溃采集和业务分析保持为独立能力。

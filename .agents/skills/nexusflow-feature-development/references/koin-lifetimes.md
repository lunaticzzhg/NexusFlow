# App/KMP Koin 与生命周期

本参考只约束 App/KMP 的 Koin、ViewModel、Compose host 与客户端 lifecycle。Backend dependency composition 和 AI runtime ownership 由各自 architecture authority 或真实 source 决定。

## 解析边界

- composition root 在进入可重组 Compose 内容前解析应用级依赖，并以显式参数向下传递；不得创建只为转交依赖的包对象。
- 平台入口只绑定原生宿主必需能力与应用级状态；共享业务、module 与初始化定义仍在 `commonMain`。
- Composable、domain、data 与普通业务代码禁止 `get()`、`inject()`、`KoinComponent`；构造函数注入是默认方式。

## 页面状态

- 页面级、需要跨重组存活或启动异步工作的状态持有者必须是 lifecycle `ViewModel`，以 Koin `viewModel` 注册。
- Route 或 Gate 可在默认参数用 `koinViewModel()` 获取该 ViewModel；这是唯一允许的 Compose Koin 解析例外，测试可显式传入实例。
- ViewModel 使用 `viewModelScope`，不得自建脱离生命周期的 coroutine scope。
- 应用级控制器保持显式依赖；不要在 Composable 中以普通 Koin 查询隐藏它。

### Job 与外部资源的关闭边界

- `viewModelScope` 启动的 Job 由 ViewModel 销毁自动取消；`onCleared()` 不得重复维护 cancel/close Job，除非该 Job 不在 `viewModelScope` 且 ViewModel 是其明确 owner。
- 连接、订阅、平台会话或其他自行持有 scope、句柄或后台任务的对象，是独立资源 owner。ViewModel 在离开时只调用其明确的 `stop()` / `close()`，资源自身负责幂等、取消内部工作、释放句柄和最终停止；调用方不得复制第二套清理流程。
- 调用方必须等待资源停止完成时，资源应显式提供 `suspend close()`、`awaitClosed()` 或等价合同；不得由 ViewModel 从内部 Job 拼接等待逻辑。无需等待的页面离开可使用非 suspend 的停止请求，但仍须保证迟到事件无法写入已失效状态。
- 验证至少覆盖 ViewModel 销毁后的 Job 取消、重复 stop/close 安全，以及旧资源事件不会更新当前 UI。资源的真实关闭时机和页面是否需要等待必须由交互或平台事实证明。

## 生命周期选型

| 方式 | 适用条件 | 典型示例 | 不适用时 |
| --- | --- | --- | --- |
| `single` | 整个应用只有一个共享、无页面所有权的实例 | 共享 `HttpClient`、Ktorfit、应用级会话控制器 | 不因“注入方便”或希望少建对象而使用 |
| `viewModel` | 页面级状态需跨重组存活，或要启动受页面生命周期管理的异步工作 | 登录页、列表页 ViewModel | 不承载应用级基础能力或纯无状态 mapper |
| 显式 Factory | 调用者确实需要一次性新对象，且其创建参数、所有权与关闭时机由调用者明确管理 | 一次交互的 session、带路由参数的协调器 | 不因“可能同时有多个实例”而注册 Koin factory |
| `scope` | 有真实且可关闭的宿主生命周期，多个对象必须在该生命周期内共享 | 明确拥有关闭点的独立流程或宿主 | 没有明确 owner/close 时不建立 scope |

先选最小能表达真实所有权的方式。不得仅为对象数量使用 factory 或 scope；若不能指出实例 owner、结束时机和第二个共享消费者，就保持构造函数组合或现有生命周期。

当一个依赖只有一个实现且创建过程没有独立业务语义时，直接注入该依赖；需要每次获得新实例时，用 Koin 的 `factory` 生命周期表达即可。不得再建立 feature `XxxFactory`、Manager 或注册表仅用于转发创建。只有存在两个真实实现、多个独立创建策略，或创建本身需要被调用方管理的参数/关闭语义时，才增加该抽象。review 必须指出当前的变化点；删掉抽象后调用方没有重复协调逻辑时，该抽象不应存在。

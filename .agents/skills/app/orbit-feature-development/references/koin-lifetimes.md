# Koin 与生命周期

## 解析边界

- composition root 在进入可重组 Compose 内容前解析应用级依赖，并以显式参数向下传递；不得创建只为转交依赖的包对象。
- 平台入口只绑定原生宿主必需能力与应用级状态；共享业务、module 与初始化定义仍在 `commonMain`。
- Composable、domain、data 与普通业务代码禁止 `get()`、`inject()`、`KoinComponent`；构造函数注入是默认方式。

## 页面状态

- 页面级、需要跨重组存活或启动异步工作的状态持有者必须是 lifecycle `ViewModel`，以 Koin `viewModel` 注册。
- Route 或 Gate 可在默认参数用 `koinViewModel()` 获取该 ViewModel；这是唯一允许的 Compose Koin 解析例外，测试可显式传入实例。
- ViewModel 使用 `viewModelScope`，不得自建脱离生命周期的 coroutine scope。
- 应用级控制器保持显式依赖；不要在 Composable 中以普通 Koin 查询隐藏它。

## 生命周期选型

| 方式 | 适用条件 | 典型示例 | 不适用时 |
| --- | --- | --- | --- |
| `single` | 整个应用只有一个共享、无页面所有权的实例 | 共享 `HttpClient`、应用级会话控制器 | 不因“注入方便”或希望少建对象而使用 |
| `viewModel` | 页面级状态需跨重组存活，或要启动受页面生命周期管理的异步工作 | 登录页、列表页 ViewModel | 不承载应用级基础能力或纯无状态 mapper |
| 显式 Factory | 调用者确实需要一次性新对象，且其创建参数、所有权与关闭时机由调用者明确管理 | 一次交互的 session、带路由参数的协调器 | 不因“可能同时有多个实例”而注册 Koin factory |
| `scope` | 有真实且可关闭的宿主生命周期，多个对象必须在该生命周期内共享 | 明确拥有关闭点的独立流程或宿主 | 没有明确 owner/close 时不建立 scope |

先选最小能表达真实所有权的方式。不得仅为对象数量使用 factory 或 scope；若不能指出实例 owner、结束时机和第二个共享消费者，就保持构造函数组合或现有生命周期。

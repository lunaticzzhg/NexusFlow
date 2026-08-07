# 复杂异步模块的分层与所有权

## 触发与目标

本规范用于有异步、多步骤、资源生命周期或上下文失效风险的模块。目标是让状态、Job、资源、清理和观测各有唯一 owner，且读者打开每一层实现时能直接看出核心方法与生命周期。

命中下列任意两项时，设计与 review 必须使用本规范：

- 多阶段流程或用户可见阶段；
- Job、worker、队列、订阅或外部 I/O；
- 取消、重试、重复触发或迟到结果；
- 临时资源、提交点、清理或恢复；
- user、tenant、task/conversation、session 或导航切换；
- 一个类同时协调状态、资源和多个异步回调。

简单查询、一次 API 请求、普通表单提交不适用。不得为了满足本规范机械创建 Runtime、Session、接口或 Factory。

本规范补充而不替代 [状态机](state-machines.md)、[Context Runtime](context-runtime.md) 与 [可观测性](observability.md)：它定义类角色、依赖方向与实现可读性；状态、上下文切换与诊断的具体规则仍以各自专题为准。

## 角色与依赖方向

复杂模块通常包含以下职责角色：

```text
Presentation
  -> Public feature service
    -> Runtime owner
      -> Operation owner / Session
        -> Atomic worker
```

这些是职责，不是固定类数。

| 角色 | 拥有内容 | 不拥有内容 |
| --- | --- | --- |
| Public feature service | 公开命令、UI 状态与一次性事件、runtime 安装/卸载 | 单次流程的 Job、资源路径、逐项处理细节 |
| Runtime owner | 固定上下文资源、observer、executor、当前操作及关闭顺序 | UI 文案、全局可变上下文 |
| Operation owner | 当前操作 identity、启动/取消/等待、旧结果隔离 | 跨上下文资源；只有一个前台操作时可并入 Runtime |
| Session | 一次多步骤流程的状态机、提交点、清理与内部 outcome | Scope、全局生命周期、UI 反馈 |
| Atomic worker | 一项文件、网络、数据库或平台原子动作 | 流程状态机、重试编排、上下文切换 |

规则：

- 上层只依赖下一层的窄合同；下层实现、Job、Mutex、操作 ID 和内部 outcome 不得向上泄漏。
- 没有真实替换实现、独立测试替身或跨边界需求时，层内优先使用 `internal` 具体类，不为每层创建接口。
- 下层不回调上层修改状态；下层发布内部 state/event，由父层投影为自己的状态或事件。
- 一个事实只能有一个写入 owner。禁止 UI、Runtime 和 Session 分别维护“当前导入”“是否取消”或“当前进度”。
- Worker 启动时捕获其上下文，不得读取全局可变 user/tenant/session 以决定旧操作的结果。

## 分层决策

| 事实 | 最小设计 |
| --- | --- |
| 一次无取消、无资源清理的请求 | 直接 use case/repository 调用，不建立 Session |
| 有固定 user/tenant/task 资源 | Runtime owner |
| 有取消、提交点、清理或多步骤流程 | Session |
| 有重复开始、当前操作、取消和迟到结果隔离 | Operation owner；单操作模块可并入 Runtime |
| 有多种平台/网络/文件原子实现 | Atomic worker 接口 |

禁止把“可能以后需要”作为新增层、接口、Factory 或注册表的依据。

## 类内部可读性

复杂类按以下顺序组织：

```text
1. 职责注释、依赖与唯一状态
2. 公开核心方法
3. 主流程私有方法
4. 状态迁移、资源关闭与清理
5. 小型内部数据类、mapper、常量
```

要求：

- 一个公开方法表达一个用户或生命周期动作，如 `start`、`beginImport`、`cancel`、`close`；避免 `handle`、`process`、`execute` 等泛词。
- 主流程应从方法顺序读出业务动作；锁、Job、observer 和诊断收进 `installRuntime`、`closeAndJoin`、`recordFinished` 等具名私有方法。
- 同一可观察状态只有一个写入口；状态更新 helper 只有在表达该边界时保留。
- 长 I/O 不得包裹在生命周期或状态 gate 内；短锁只用于安装/摘除当前 owner 或捕获安全快照。
- `close()` 必须给出关闭顺序并实际等待资源停止；取消只是请求，不能替代旧结果隔离或清理完成。
- 一个协作对象中有必须共同满足的 Job、session、observer、started 标志时，应聚合为小型私有对象，而不是散落为多个字段。

推荐骨架：

```text
Manager: start/close -> commands -> replace/detach/install/project
Runtime: start/close -> commands -> start/finish operation -> observe resources -> publish view/event
Session: run/cancel -> begin -> process items -> commit -> finish -> cleanup
Worker: one atomic action -> validation/preparation -> commit -> uncommitted cleanup
```

## 禁止项

- ViewModel 持有或等待 Session/worker；
- Session 回调 Manager 或直接写 UI；
- Runtime 产生用户文案；
- Worker 读取全局当前上下文；
- 多个类反复比较同一 `activeSession` 或自行过滤旧结果；
- 用多个 Boolean、可空 Job 与回调顺序共同推导可渲染状态；
- 锁内执行文件、网络、缩略图或其他长 I/O；
- 为“分层整齐”机械增加接口、Factory、基类、事件总线或通用状态机框架。

## 观测边界

观测遵循 `FeatureOperationContext + FeatureDiagnosticEvent + FeatureDiagnosticReporter`，不复用 UI event。

| 事实 | 唯一诊断 owner |
| --- | --- |
| Runtime 安装、关闭 | Public feature service |
| 操作开始、结束、取消 | Runtime/operation owner |
| 单项成功、失败、跳过 | Session |
| 原子 I/O 失败 | Worker |

Reporter、日志或分析 SDK 的失败不得改变业务状态、取消和清理。

## 验证与例外

命中本规范的模块至少覆盖：正常完成、重复触发、立即取消、处理中取消、提交点后取消、上下文失效、迟到结果与资源清理中实际可达的场景。测试优先断言状态和可观察副作用，不断言私有 Job 排列。

review 必须回答：

```text
- 每个状态、资源、Job、identity 和清理由谁拥有？
- 是否存在跨层对象泄漏或重复状态？
- 主流程能否从方法顺序直接读出？
- 关闭、取消和上下文切换是否有确定顺序？
- 旧 context 的 observer、结果或事件能否写入当前 UI？
```

例外必须说明真实交互、平台或性能依据、用户影响、替代方案与重新评估条件；“代码更短”或“未来可能复用”不是例外理由。

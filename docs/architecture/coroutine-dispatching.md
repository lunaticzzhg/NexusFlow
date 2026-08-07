# 协程调度边界

## 目标与触发证据

本规范防止从 UI 协程发起的调用链在主线程执行阻塞 I/O 或明显 CPU 密集工作。它源于已审视到的跨端能力差异：安全存储、系统日历访问和本地缓存编解码容易随调用方继承 `Main`。

适用于 Orbit 的平台能力、数据缓存、Repository、网络拦截器和长期运行的任务协调器。它不定义业务状态、网络协议、重试策略或 UI 生命周期；这些仍由各自专题规范负责。

## 基本事实

`suspend` 只表示调用可以挂起，不表示函数内部的同步代码自动离开调用方线程。尤其是 `viewModelScope.launch` 默认从主线程开始执行；在第一个实际挂起点之前，以及每次恢复后，其同步代码仍可能运行在主线程。

能力实现拥有其内部调度选择。调用方不应依赖未声明的后台切换，也不应因为调用的是 `suspend` 函数而重复或猜测性地切换调度器。

## 调度归属

| 工作类型 | 所有者 | 规则 |
| --- | --- | --- |
| UI 状态、Compose 副作用、ViewModel 状态更新 | Presentation | 保持 `Main`。 |
| Ktor、Room、DataStore 等库提供的正常挂起 I/O | Data | 保持调用上下文；不得仅因“网络/数据库”而额外包裹 `Dispatchers.IO`。 |
| 文件读写、Keychain/Keystore、`ContentResolver`、原生同步 SDK | Platform 或 Data | 在能力实现内部切至 `Dispatchers.IO`；目标平台没有可用的 `IO` 语义时使用 `Dispatchers.Default`。 |
| 视频/图片处理、摘要或哈希、JSON 大对象编解码、批量映射、排序与去重 | Platform 或 Data | 在能力实现内部切至 `Dispatchers.Default`。 |
| 任务队列、SSE 生命周期、重试和后台协调器 | Runtime 或 Data | 自有 `CoroutineScope` 使用 `Dispatchers.Default`，并保留既有的取消与生命周期所有权。 |
| 网络请求完成后的 UI 状态更新 | Presentation | 回到调用方的 `Main` 后更新状态。 |

## 实施规则

1. 切换必须位于同步重工作所属的能力实现中。例如 Keychain 操作在 secure store 内部切换，缓存 JSON 在 cache 内部切换，日历查询在平台 calendar adapter 内部切换。
2. ViewModel 只协调意图、可见状态和一次性副作用；不得为 Repository、缓存、文件或平台能力普遍添加 `withContext(Dispatchers.IO/Default)`。
3. 对 Ktor、Room、DataStore 的正常挂起调用不做额外切换。请求前后若存在同步 I/O、序列化、集合处理或原生 API 调用，只将该部分切至适当调度器。
4. 正常挂起 I/O 与 DTO 映射不得为了映射而以外层 `withContext` 包住整个请求。小而有界的映射继承调用方；只有批量映射、外部输入规模不受控或 CPU 重映射时，才在最小的 DTO→domain 边界切至 `Dispatchers.Default`。
5. `Main` 上允许小而有界的纯计算，例如字段校验、单个 DTO 映射和短文本处理；不得把外部输入规模不受控的处理视为“小工作”。
6. 新增可配置或可注入的 dispatcher 仅在必须测试调度/取消语义，或确有跨实现的并发策略时引入。不得仅为替代少量直接 `Dispatchers` 使用而创建全局 dispatcher 框架。
7. 对拥有同步重工作且主导类型明确的方法，优先在方法入口切至其总体调度上下文；仅在平台硬性边界短暂切换。例如本地任务快照导入以文件 I/O 为主时，方法整体运行在 `Dispatchers.IO`，只在读取 UI host、启动系统日历授权时切至 `Dispatchers.Main.immediate`；系统 UI 挂起返回后自动回到 `IO` 继续解析或清理。
8. 不得为同一主导工作中的每个细粒度 API 调用重复嵌套相同的 `withContext`。优先采用“入口总体上下文 + 少数边界切换”的结构，并以职责明确的私有函数表达边界两侧，例如 `prepareSnapshot`、`requestCalendarAccessOnMain`、`finalizeSnapshot`。
9. 上述入口切换不是所有 `suspend` 方法的默认套壳：UI 与 ViewModel 方法保持 `Main`；Ktor、Room、DataStore 的正常挂起调用保持调用上下文；严格小而有界的纯计算可直接继承调用方；私有辅助函数继承其编排方法的上下文，除非其自身跨越新的硬性平台边界。

## 强制切换的判定

满足任一项时，拥有该操作的实现必须明确切换调度器：

- 可能访问磁盘、Keychain/Keystore、内容提供者，或调用同步原生 SDK；
- 输入来自外部且大小、条目数或耗时没有严格的小上限；
- 包含日历查询、密码学哈希、序列化/反序列化或批量集合计算；
- 已有卡顿、ANR、掉帧、主线程 I/O 告警，或平台文档表明调用时间不可预测。

## Review 与验证

新增或修改平台能力、缓存、Repository、网络拦截器、任务/重试协调时，review 必须回答：

1. 调用是否可能来自 `Main`？
2. 其中是否存在同步 I/O 或 CPU 密集操作？
3. 若存在，哪个能力实现负责切换到哪个调度器？
4. 对 Ktor、Room 或 DataStore，为什么不需要或为何需要额外切换？
5. 取消是否保持传播，且 UI 状态是否只在 `Main` 更新？
6. 拥有同步重工作的编排方法是否有明确的主导工作类型，并从方法入口以总体上下文承载该工作，只在硬性平台边界切换？若没有入口切换，是否属于 UI、正常挂起 I/O、严格有界纯计算或继承上下文的私有辅助函数？
7. `withContext` 是否只覆盖同步重工作，是否错误包住 Ktor、Room、DataStore，或为小映射增加无收益的 dispatcher hop？

默认以行为测试验证。只有调度、取消或并发顺序本身是行为契约时，才注入测试 dispatcher，并使用 `kotlinx-coroutines-test` 覆盖该契约。

## 重新评估条件

当引入后台持久任务、全局并发配额、多个可替换执行器，或调度策略需要跨多个 feature 一致配置时，重新评估是否需要应用级 dispatcher 抽象。此前保持直接使用标准 `Dispatchers`。

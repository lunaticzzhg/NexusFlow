# 事件、Worker 与恢复

Worker 是持久化状态机执行器，不是 HTTP 请求的延续。它消费至少一次事件、领取一个任务、执行一个有边界的阶段、持久化 checkpoint/事件/Outbox 记录，然后释放或续租。

## Worker 循环

```kotlin
fun handle(event: TaskEvent) {
    if (processedEvents.contains(event.eventId)) return
    val claim = taskRepository.claim(event.taskId, workerId, now, leaseDuration) ?: return
    try {
        val next = stageRunner.run(claim) // bounded read/model/tool stage
        taskRepository.persistStage(claim.version, next, event.correlationId)
        processedEvents.record(event.eventId)
    } catch (retryable: RetryableFailure) {
        taskRepository.scheduleRetry(claim, retryable, backoff)
    } finally {
        taskRepository.releaseOrLetLeaseExpire(claim)
    }
}
```

## 要求

- 按 `taskId` 对任务事件分区/排序；仍须用 `eventId` 去重，因为会重投。
- 以版本 + `lease_until` 领取；回收任务只能处理已过期租约。副作用前立即检查取消/终态。
- 对失败分类：校验/策略失败为可解释终态；临时模型/工具/broker 失败可按预算指数退避重试；耗尽重试必须产生可观测失败或 DLQ，不能无限循环。
- 在所有新事件、trace、日志和连接器调用中携带 correlation 与 causation ID。每个对外有意义的阶段后写 checkpoint。
- 本地状态/Outbox 变更持久化前不得确认 broker 消息成功。恢复不能依赖内存完成标记。

## 初始部署与拆分部署

种子工程可进程内调用 `TaskPlanningWorker` 并使用确定性 Provider。现在就保持 port 边界。只有 API、orchestrator、discovery、tool-gateway 的独立吞吐/故障域、所有权和运行契约真实存在时才拆成进程；届时再以版本化事件/HTTP 契约替代直接调用。

# PostgreSQL、事务与 Outbox

PostgreSQL 是任务、审批、外部动作、不可变任务事件与 Outbox 行的权威。Redis 仅用于缓存/限流/短租约；Kafka/Redpanda 是投递通道，不是真相来源。变更 schema 前先读 `backend/src/main/resources/db/migration/V001__task_core.sql`。

## 命令事务

短事务只包含：与授权相关的聚合读取/比较、状态变更、领域时间线事件和 Outbox 插入。使用乐观版本更新（`WHERE id = ? AND version = ?`），零行受影响映射为冲突。模型或连接器 I/O 期间绝不持有数据库事务。

```kotlin
transaction {
    val current = tasks.lockOrReadScoped(actor, taskId)
    check(current.version == expectedVersion)
    val next = current.afterValidation(now)
    tasks.updateScoped(next, expectedVersion)
    taskEvents.append(next.toEvent(correlationId))
    outbox.append(next.toOutboxEvent())
}
```

## Schema 与迁移规则

- 环境已消费的 Flyway migration 只能追加。新建前向兼容的 `V###__...sql`，绝不修改已应用 migration。
- 用户范围表包含 `tenant_id`。唯一约束表达幂等（`tenant_id, owner_user_id, idempotency_key`）和外部动作去重；索引匹配 owner 列表、租约回收和任务时间线访问模式。
- 金额使用 `amount_minor` 加 ISO currency；瞬间以 UTC 存储，并携带 IANA timezone 表达展示/意图。
- 不可变时间线与可变当前状态分开持久化。事件 payload 必须有 schema/版本并脱敏。

## 发布者与消费者规则

发布者只在收到 broker 确认后将 Outbox 行标记为已发布；重复发布是预期行为。消费者在副作用前记录已处理事件 ID 或使用唯一自然副作用键。DB commit 不得依赖 broker 可用；把未发布年龄/尝试次数暴露为运维数据。

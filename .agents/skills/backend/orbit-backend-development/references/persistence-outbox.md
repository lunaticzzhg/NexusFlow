# PostgreSQL、事务与 Outbox

PostgreSQL 是当前身份、会话及未来持久化 feature 的权威。当前初始 schema 是 `backend/src/main/resources/db/migration/V001__identity_and_sessions.sql`。Redis、Redpanda 和 Outbox 不是当前运行时依赖；只有某个已确认 feature 的可靠交付确实需要缓存、消息或异步副作用时，才在该 feature 切片内引入。

## 事务边界

短事务只包含授权范围内的读取/比较、领域状态变更及与该状态不可分割的持久化记录。不得在数据库事务中调用模型、网络、消息 broker 或连接器。

需要可靠异步副作用时，在同一事务写入业务状态与 Outbox 行；发布与消费遵循至少一次语义，消费者必须在副作用前按稳定键去重。尚未具备这套持久化、发布、消费和恢复证据的 feature 不得注册异步路由或 Worker。

## Schema 与迁移规则

- 已被任一环境消费的 Flyway migration 只能追加。新建前向兼容的 `V###__...sql`，绝不修改已应用 migration。
- 新 feature 的 schema、Repository、授权范围和恢复语义在同一交付切片加入；不得为推测中的 feature 预建空表、占位 migration 或内存替代品。
- 用户范围表包含 `tenant_id`；唯一约束表达该 feature 的身份映射、幂等或外部副作用去重；索引匹配真实查询、回收或时间线访问路径。
- 时间以 UTC 存储；金额使用 `amount_minor` 与 ISO currency；仅在业务需求存在时存储 IANA timezone 以表达用户意图。
- 可变当前状态与不可变审计/时间线分开持久化。未来事件 payload 必须有版本并脱敏。

## 验证

迁移或 JDBC 适配器变更至少证明：干净 PostgreSQL 能从零应用全部 migration、配置缺失会失败关闭、范围 SQL 不跨 tenant/owner、以及适用的唯一约束和事务原子性。环境没有 PostgreSQL 时不得宣称这些集成验证已完成。

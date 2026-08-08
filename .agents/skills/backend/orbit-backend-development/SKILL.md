---
name: orbit-backend-development
description: 实现和审查 Orbit Kotlin/Ktor 后端代码。用于 `backend/`、`contracts/` 中的契约、Ktor 路由、Google 身份会话、PostgreSQL/Flyway、未来的状态机、Outbox、Worker、工具网关、可观测性、可靠性或后端测试。
---

# Orbit 后端开发

后端按可独立验证的 feature 纵向切片演进，而不是预建业务框架。当前可运行基线只有跨 feature 的 `core` 能力与 `feature/auth`；Task、AI、Outbox、Worker、审批和连接器尚未实现。命中这些未来能力时才读取对应专题并在同一切片内建立持久化、授权、恢复和验证边界。

## 必经流程

1. 读取 [Skills Index](../../INDEX.md)，再读取下列命中的最少 reference。新增模式前先读既有代码与测试；`contracts/`、数据库迁移和 `docs/architecture/` 中的已实现事实优先于本 Skill。
2. 所有非简单改动先写实施决策卡。只填写本切片实际命中的状态、授权、并发、恢复、外部副作用项；未命中时明确为非目标。
3. 一次实现一个 feature 的纵向命令或投影切片。路由只转换 HTTP；应用层/领域层负责决策；适配器执行 I/O。耗时、模型或外部工作不得延续 HTTP 请求，只有需求确实需要时才在该切片引入事件驱动运行时。
4. 先补能证明负向路径的最小测试，再执行对应检查。状态、事件、API 字段、迁移或权限改动没有兼容与恢复用例即不算完成。
5. 记录刻意未引入的内容及未来拆分触发条件。未拥有独立进程、持久化契约和数据所有权前，不得宣称服务边界已经存在。

### 契约冲突规则

对已实现、已发布的基线，`contracts/` 类型、生命周期测试和数据库迁移是可执行的权威。产品/架构文档描述目标方向，但不能静默改变线上语义。若 API 字段、状态迁移、授权范围或外部副作用结果不一致，必须暂停该切片：记录两处来源，取得明确的产品/领域决策，再同步更新可执行契约、测试和文档。不得让路由或后台运行时自行“选择”其中一种语义。

### 决策卡模板

```markdown
### 后端决策
- 目标 / 非目标：
- 已检查的既有模式与文件：
- 权威状态、租户/actor 与授权：
- 命令/API/事件契约及兼容性：
- 状态迁移、幂等键、版本/租约与恢复：
- 事务边界、Outbox/事件行为和外部副作用：
- 失败映射、可观测性、验证和拆分触发条件：
```

## 当前基线与依赖方向

当前工程是单个 `backend` JVM 模块和共享 `contracts` KMP 模块。目录所有权、Ktor DI、启动顺序与关闭规则以 [后端组合规范](../../../../docs/architecture/backend-composition.md) 为唯一事实来源；Google 身份验证与业务会话以 [认证规范](../../../../docs/architecture/authentication.md) 为唯一事实来源。

```text
core                         # HTTP、配置、健康检查、身份上下文、持久化基础设施
feature/<name>/api -> application -> domain
feature/<name>/infrastructure -> domain/application ports
contracts                    # 框架无关的共享 HTTP DTO
```

`domain` 不得依赖 Ktor、SQL、消息客户端、HTTP client、模型 SDK 或连接器凭据。`contracts` 不得依赖后端或框架。`core` 不得包含业务 feature 语义；feature 不得导入另一 feature 的 `api` 或 `infrastructure`。未来部署单元只有在独立吞吐、故障域、数据所有权和运行契约真实存在时才拆分，不是现在把 package 拆为 Gradle 模块的理由。

## Reference 路由

- 路由/Controller、请求/响应、Problem JSON、cursor/SSE 或 `Idempotency-Key`：[API 契约](references/api-contracts.md)。
- Google 身份验证、NexusFlow Bearer 会话、scope、服务调用、租户/owner 访问或审计 actor：[认证与授权](references/authentication-authorization.md)。
- Flyway、SQL、乐观锁、幂等、事务或 Outbox：[持久化与 Outbox](references/persistence-outbox.md)。
- **仅当当前 feature 引入异步消费者时**，Kafka/Redpanda、Worker、重试、租约、回放、DLQ 或恢复：[事件与 Worker](references/eventing-workers.md)。
- **仅当当前 feature 引入任务/审批状态时**，状态迁移、取消或外部动作：[任务状态机](references/task-state-machine.md)。
- **仅当当前 feature 引入第三方写操作时**，日历/提醒/通知/MCP 连接器：[工具网关](references/tool-gateway.md)。
- Trace、指标、日志、成本/安全/限流或事故排查：[可观测性与安全](references/observability-security.md)。
- 必要检查与验收矩阵：[验证](references/verification.md)。

## 不可突破的边界

- `ActorContext` 必须来自已验证的 NexusFlow 凭据。API body 绝不接收 tenant、owner、role 或权限作为身份事实；每次 Repository 查询/变更都必须带 actor 范围。
- 共享 HTTP DTO 只定义一次，位于 `contracts`；feature 不得在 backend 或 App 维护平行传输模型。
- 生产路由不得以 placeholder、内存状态或测试身份替代当前需求已经需要的持久化、授权或恢复语义。
- 普通日志/事件中不得写入 access token、refresh token、Authorization header、原始 prompt、敏感工具 payload 或用户数据；只保留安全的关联 ID、类别和脱敏元数据。

## 延伸参考

- [App 技术方案](../../../../docs/v0.1/app-module-technical-plan.md)是模块、接口与交付切片的产品级事实来源。
- [项目可观测性规范](../../../../docs/architecture/observability.md)和[审查证据门禁](../../../../docs/architecture/review-evidence-gate.md)与这些后端 reference 一并适用。

---
name: orbit-backend-development
description: 实现和审查 Orbit Kotlin/Ktor 后端代码。用于 `backend/`、`contracts/` 中的契约、Ktor 路由、OIDC 授权、任务/审批生命周期、PostgreSQL/Flyway、Outbox/事件 Worker、工具网关、可观测性、可靠性或后端测试。
---

# Orbit 后端开发

将 Orbit 视为持久化命令系统，而不是同步聊天接口。PostgreSQL 拥有任务、审批、动作和审计状态；API 负责鉴权和接收命令；Worker 推进持久化工作；AI 与连接器只能在服务端策略控制下提议或执行。

## 必经流程

1. 读取 [Skills Index](../../INDEX.md)，再读取下列命中的最少 reference。新增模式前先读既有代码与测试；`contracts/` 和 `docs/` 中的产品/API 事实优先于本 Skill。
2. 所有非简单改动先写实施决策卡：目标/非目标、权威状态/owner、受影响 API 或事件契约、授权、状态迁移、幂等/并发、失败/恢复、既有模式和最窄验证。
3. 一次实现一个纵向命令或投影切片。Controller 只转换 HTTP；应用层/领域层负责决策；适配器执行 I/O。命令事务必须短，耗时/模型/外部工作必须事件驱动。
4. 先补能证明命令负向路径的最小测试，再执行对应检查。状态、事件、API 字段、迁移或权限改动没有兼容与恢复用例即不算完成。
5. 记录刻意未引入的内容及未来拆分触发条件。未拥有独立进程、持久化契约和数据所有权前，不得宣称服务边界已经存在。

### 契约冲突规则

对已实现、已发布的基线，`contracts/` 类型、生命周期测试和数据库迁移是可执行的权威。产品/架构文档描述目标方向，但不能静默改变线上语义。若 API 字段、状态迁移、授权 scope 或外部副作用结果不一致，必须暂停该切片：记录两处来源，取得明确的产品/领域决策，再同步更新可执行契约、测试和文档。不得让路由或 Worker 自行“选择”其中一种语义。

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

## 当前基建与目标拓扑

当前种子工程是单个 `backend` JVM 模块：`api`、`application`、`domain`、`infrastructure`、`orchestrator`；`contracts` 是稳定共享契约，`ai` 只在本地规划时进程内调用。`InMemoryTaskRepository` 和 `DevelopmentActorResolver` 是本地/测试适配器，不是生产默认实现。

现在即保持如下目标依赖方向：

```text
api -> application -> domain <- infrastructure
                 -> contracts <- ai mapping boundary
orchestrator -> application/domain + ports; never controller
```

`domain` 不得依赖 Ktor、SQL、Kafka、HTTP client、模型 SDK 或连接器凭据。`contracts` 不得依赖后端/AI/框架。未来部署单元只有在独立吞吐、故障域、数据所有权和运行契约真实存在时才拆分，不是现在把每个 package 都拆成 Gradle 模块的理由。

## Reference 路由

- 路由/Controller、请求/响应、Problem JSON、cursor/SSE 或 `Idempotency-Key`：[API 契约](references/api-contracts.md)。
- JWT/OIDC、scope、服务调用、租户/owner 访问或审计 actor：[认证与授权](references/authentication-authorization.md)。
- 任务/审批/动作状态或用户取消：[任务状态机](references/task-state-machine.md)。
- Flyway、SQL、乐观锁、幂等、事务或 Outbox：[持久化与 Outbox](references/persistence-outbox.md)。
- Kafka/Redpanda、Worker、重试、租约、回放、DLQ 或恢复：[事件与 Worker](references/eventing-workers.md)。
- 日历/提醒/通知/MCP 连接器或任意第三方写入：[工具网关](references/tool-gateway.md)。
- Trace、指标、日志、成本/安全/限流或事故排查：[可观测性与安全](references/observability-security.md)。
- 必要检查与验收矩阵：[验证](references/verification.md)。

## 不可突破的边界

- `ActorContext` 必须来自已验证的凭据。API body 绝不接收 tenant、owner、role 或审批权限；每次 Repository 查询/变更都必须带 tenant 和 actor 范围。
- 计划只是建议。`TaskTransitionPolicy.afterValidation` 是 `VALIDATING` 的权威：任一 `ActionRequest` 必须进入 `AWAITING_APPROVAL`；客户端、Prompt 或 Worker 都不能绕过它进入 `EXECUTING`。
- 使用服务端生成且稳定的动作幂等键。请求幂等键只保护命令提交，不能保护连接器写入。
- 聚合变更、不可变时间线事件和 Outbox 行必须原子持久化。消费者是至少一次的：副作用前去重，且能容忍重复发布。
- 普通日志/事件中不得写入 access token、原始 prompt、敏感工具 payload 或用户数据；只保留引用/脱敏元数据并设定保留策略。

## 当前基线范围

本 Skill 规定面向生产的方向，但不假装种子工程已经具备 PostgreSQL 适配器、JWT 验证、broker 发布器、租约领取、审批接口、SSE 或真实连接器。每项能力都应在 port 后增加并补测试；只有生产适配器通过对应验收用例后才移除本地专用适配器。

## 延伸参考

- [App 技术方案](../../../../docs/v0.1/app-module-technical-plan.md)是模块、接口与交付切片的产品级事实来源。
- `contracts/task/TaskContracts.kt` 是任务生命周期的可执行事实来源；本 Skill 补充代码层实施约束。
- [项目可观测性规范](../../../../docs/architecture/observability.md)和[审查证据门禁](../../../../docs/architecture/review-evidence-gate.md)与这些后端 reference 一并适用。

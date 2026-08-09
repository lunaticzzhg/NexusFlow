# Orbit Skills Index

Orbit 需求开发或审视先读取本索引，再进入对应 Skill。后端协议与产品事实优先于客户端推断。一个任务可以命中多行；先进入“工作流”列指定的 Skill，再叠加“专题”列的 reference，并在交付中提供“最低输出”。

| 信号 | 工作流 | 专题 | 最低输出 |
| --- | --- | --- | --- |
| Ktor 路由、HTTP DTO、Problem JSON、JWT/OIDC、scope、租户隔离、幂等写入、任务/审批状态、PostgreSQL、Flyway、Outbox、Kafka/Redpanda、Worker、SSE 或工具网关 | `backend/orbit-backend-development/SKILL.md` | 按该 Skill 的专题路由 | 决策卡、权威状态/事务边界、失败与恢复语义、任务类型验收与验证记录。 |
| 规划上下文、`PlanProposal`、模型 Provider、Prompt、结构化输出、注入防护、模型预算、离线评测、回放或 AI Trace | `ai/orbit-ai-development/SKILL.md` | 按该 Skill 的专题路由 | 决策卡、输入输出契约、安全/降级策略、评测或回放证据与验证记录。 |
| 新页面、功能、认证/会话、API/DTO、ViewModel、状态流、Compose UI、Koin 装配、平台能力或相关缺陷修复 | `app/orbit-feature-development/SKILL.md` | 按该 Skill 的专题路由 | 按定级提供实施决策卡、薄切片、命中核心原则的结论卡和验证记录；仅在门禁升级条件或用户要求时进入完整 review。 |
| App 侧 API DTO、HTTP 响应、Problem JSON、序列化、请求 Header 或 `Idempotency-Key` | `app/orbit-feature-development/SKILL.md` | `references/network-contract.md` | 权威合同、兼容性、失败映射、验证。 |
| Job、并发、重试、缓存、用户/租户切换、SSE 连接、订阅或执行器 | 按任务使用 `app/orbit-feature-development/SKILL.md` 或 `app/code-review-and-refactoring/SKILL.md` | `../../docs/architecture/context-runtime.md`；涉及列表/缓存时叠加 `references/list-data-lifecycle.md` | owner、状态、取消/重试、并发或失效边界、验证。 |
| 多阶段流程、审批、取消/重试、资源清理、迟到 callback、后台 worker 或上下文失效 | `app/orbit-feature-development/SKILL.md` | `../../docs/architecture/state-machines.md`；同时命中 runtime、平台或网络时叠加对应专题 | 状态集合、迁移表、唯一 owner、操作身份、提交/清理责任与转换测试。 |
| Koin、ViewModel、Compose host、composition root 或平台入口 | `app/orbit-feature-development/SKILL.md` | `references/koin-lifetimes.md` | owner、生命周期、DI 边界与验证。 |
| 同步 I/O、线程/dispatcher、主线程卡顿、JSON 编解码或 CPU 密集处理 | 按任务使用 `app/orbit-feature-development/SKILL.md` 或 `app/code-review-and-refactoring/SKILL.md` | `../../docs/architecture/coroutine-dispatching.md` | 调用上下文、耗时操作归属、调度选择、取消语义与验证。 |
| 系统日历、通知、权限、系统 UI、SSE 或深链能力 | 按任务使用 `app/orbit-feature-development/SKILL.md` 或 `app/code-review-and-refactoring/SKILL.md` | `../../docs/architecture/platform-capabilities.md` | common/platform 边界、平台限制、失败处理、验证。 |
| 审查 diff、架构异味、职责漂移或重构决策 | `app/code-review-and-refactoring/SKILL.md` | 按信号叠加本表专题 | 结论卡、发现项、最小修复或不改的依据、验证与残余风险。 |
| 定期比对 Boltzlog、同步其优秀设计/实现，或评估其中变更是否适合 Orbit | `app/boltzlog-sync/SKILL.md` | 按候选能力进入对应 Orbit workflow | 基于增量或快照的候选表、适配边界、优先级、非目标与最小验证。 |

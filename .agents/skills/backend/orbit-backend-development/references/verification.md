# 后端验证

先执行能证明变更边界的最窄命令，再为共享契约、迁移或异步恢复扩大验证。

```bash
./gradlew :contracts:test :ai:test :backend:test
./gradlew :backend:ktlintCheck :contracts:ktlintCheck :ai:ktlintCheck
git diff --check
```

只运行已有 Gradle task；不得伪造 Testcontainers/broker 成功结论。引入 PostgreSQL、Redpanda、Redis 或 Keycloak 适配器时，使用这些依赖补确定性集成测试，并记录本地前置条件/命令。

## 交付检查表

| 变更 | 必需证据 |
| --- | --- |
| API/DTO | 请求/响应兼容、Problem JSON、鉴权/tenant/幂等测试。 |
| 领域状态/审批 | 生命周期和聚合测试、不可绕过测试、版本冲突/终态重复测试。 |
| 迁移/Repository | 前向迁移、范围 SQL、原子变更/事件/Outbox、唯一/索引行为。 |
| Worker/事件 | 重复投递、重试/退避、过期租约恢复、取消竞态、DLQ/耗尽行为。 |
| 工具写入 | 审批/版本/过期 gate、动作幂等/对账、凭据与审计脱敏。 |
| 安全/遥测 | trace/correlation 传播、安全日志审查、变更处的预算/限流和失败指标。 |

交付前报告变更的权威/边界、实际运行的测试与命令、未验证基础设施、已覆盖的失败/恢复用例、非目标和部署拆分条件。变更命中升级条件时使用[审查证据门禁](../../../../../docs/architecture/review-evidence-gate.md)。

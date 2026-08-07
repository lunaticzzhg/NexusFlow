---
name: orbit-ai-development
description: 实现或审查 Orbit Kotlin AI 规划代码，包括 PlanningContext 与 PlanProposal 映射、模型 Provider 集成、护栏、模型路由、评测、回放、Trace 与成本控制。用于 ai/、结构化规划契约或 AI 编排边界；不用于后端执行或 KMP UI。
---

# Orbit AI 开发

本 Skill 用于规划模型改动，不用于实现外部动作。Orbit AI 只产出有边界、结构化的**提案**；后端仍是权限、状态迁移、审批、幂等和工具执行的权威。

## 必经流程

1. 读取 [.agents/skills/INDEX.md](../../INDEX.md)，再识别下列相关 reference。新增抽象前先读现有实现和至少一个同职责测试。
2. 写简短决策卡：目标、当前/未来边界、输入/输出契约、非目标、参考实现和验证。
3. 保持 `ai/` 纯净：不包含 Ktor 路由、数据库/Redis/Kafka client、OAuth 凭据、工具 client、可变任务状态或外部写入。当前模块进程内运行；未来 AI 服务必须保持相同契约，不能把传输细节泄漏至规划核心。
4. 让模型产出 schema 合法的提案，再做确定性校验。所有用户、网页、RAG 和工具结果文本均视为不可信数据。
5. 保留确定性 Provider 供测试和本地 Demo。会改变决策的 prompt、策略或 Provider 改动要增加 eval/replay fixture。
6. 运行 [verification.md](references/verification.md) 中最窄验证。AI/后端边界改动还需运行 `./gradlew :contracts:test :ai:test :backend:test`。

## 选择 Reference

| 变更 | 阅读 |
| --- | --- |
| Context、提案字段、DTO 映射、schema 演进 | [规划契约](references/planning-contract.md) |
| Provider SDK、模型选择、超时/降级/预算 | [Provider 路由](references/provider-routing.md) |
| Prompt 注入、schema 校验、安全降级 | [安全策略](references/safety-policy.md) |
| 请求动作、审批与后端权威 | [工具边界](references/tool-boundaries.md) |
| 固定用例、回放、灰度质量门禁 | [评测与回放](references/evaluation-and-replay.md) |
| Trace、脱敏、token 与花费 | [可观测性与成本](references/observability-and-cost.md) |
| 命令与验收矩阵 | [验证](references/verification.md) |

## 不可突破的边界

- `PlanningContext` 是受大小限制、已脱敏的只读快照，不含凭据或可执行工具句柄。
- `PlanProposal` 是建议；请求动作是声明式数据，绝不是执行命令或幂等键。
- AI 内部模型与 `contracts` wire DTO 故意使用不同类型。在后端/AI 适配器边界显式映射；不得让 `ai/` 依赖传输或持久化便利。
- 不可用/无效/超预算模型结果变为结构化拒绝、追问或重试决定；绝不将未校验模型文本暴露为可执行计划。
- 长期偏好仍是建议；未经用户接受命令，模型推断不能写入画像。

## 当前基建与演进

当前 `ai/` 包含纯 Kotlin 规划核心：`Planner`、`ModelProvider`、`PlanningPolicy` 和 `DeterministicStubModelProvider`。`backend` 仅可在本地基建中进程内调用。 [docs/scalable-backend-ai-architecture.md](../../../../docs/scalable-backend-ai-architecture.md) 的目标拆分将 Provider 传输/路由移至 `ai-planning-service`；其公开输入/输出保持有边界且 schema 版本化。不要仅为预留拆分而引入网络跳转。

仓库级所有权、部署和任务恢复规则见 [docs/backend-ai-bootstrap-plan.md](../../../../docs/backend-ai-bootstrap-plan.md)；横切遥测要求见 [docs/architecture/observability.md](../../../../docs/architecture/observability.md)。

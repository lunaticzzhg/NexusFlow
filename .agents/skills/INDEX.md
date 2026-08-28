# Orbit Skills Index

本索引只负责工作流路由。Orbit 的长期架构事实统一以 `docs/architecture/orbit-frontend-architecture.md` 为准；Skill 只定义“怎么做”，不复制架构正文。

## 最高目标

对 AI 主导生成和修改的代码，工程质量的最终验收目标是 **Human Traceability**：一个没有参与生成过程的人，能够快速回答：

1. 输入从哪里进入；
2. 谁拥有这条业务 Flow；
3. 关键 Decision 在哪里发生；
4. authoritative state 写在哪里；
5. success / failure / cancel / recovery 在哪里 terminal；
6. 出现故障时，如何用少量 Debug Boundary 快速缩小责任范围。

所有非轻量开发、复杂 review 和存量重构都按同一顺序判断：

```text
Architecture
    -> Coordination
    -> Local Reasoning
    -> Human Debug Simulation
```

## 主工作流

| 任务信号 | 使用 Skill | 核心产物 |
| --- | --- | --- |
| 新功能、功能修改、缺陷修复、API/DTO、ViewModel、Compose、Koin、平台能力 | `orbit-feature-development/SKILL.md` | Traceability Design Card、薄切片、验证、Human Takeover Check |
| 非轻量 feature、复杂 bug、结构性重构、Human Traceability 目标、owner/lifecycle 不清 | `orbit-architect-handoff/SKILL.md` | External Architect PLAN Bundle |
| 执行外部 Architect 返回的 Work Order 或 Correction Work Order | `orbit-work-order-executor/SKILL.md` | Slice 实现、Deviation Report、Execution Report、Verification Bundle |
| 审视一个 feature/module/业务流程/复杂 diff；想知道代码链路是否容易理解、如何排障、哪里复杂 | `orbit-human-traceability-review/SKILL.md` | Flow Reconstruction、三层 Gate、Debug Simulation、PASS/FAIL/UNPROVEN、Findings/ROI |
| 已经收敛到一个明确 owner/class/function group，需要行为保持的存量重构 | `kotlin-local-reasoning-refactor/SKILL.md` | Before/After reasoning baseline、源码/测试修改、Debug Simulation、验证 |
| 只想扫描 Kotlin/Compose 静态复杂度热点 | `kotlin-complexity-audit/SKILL.md` | `complexity-audit.md/json`；只提供 static signals，不自动生成重构候选 |
| 定期比对 Boltzlog、同步其 App 规范或实现，或评估其中变更是否适合 Orbit | `app/boltzlog-sync/SKILL.md` | 基于快照或增量的候选表、适配边界、优先级、非目标与最小验证 |

## 路由原则

- **先 Flow，后 Class。** 跨多个 Controller/Runtime/StateHolder 的问题先走 Human Traceability Review，不要直接做单类重构。
- **复杂结构先 Handoff。** 非轻量 feature、复杂 bug、结构性重构或明确 Human Traceability 改善目标，先生成 External Architect PLAN Bundle，不由 Codex 自行设计再自行批准。
- **Work Order 是执行合同。** Codex 执行 Work Order 时只做实现、测试和偏差报告；设计层冲突必须停止受影响 Slice 并生成 Deviation，不得改写目标 ownership。
- **静态复杂度不是语义结论。** LargeClass、TooManyFunctions、CognitiveComplexity 只说明“值得看”，不说明“应该拆”。
- **无 Finding 不等于 PASS。** 关键 Flow 默认是 `UNPROVEN`，只有完成 Gate 和 Debug Simulation 后才能判 `PASS`。
- **有 tests 不等于易理解。** 测试是 correctness 证据，不是 Human Traceability 证明。
- **能最终推导出来不等于人容易理解。** AI 能同时读取大量文件和状态；review 必须以有限工作记忆的人类维护者为尺度。
- **wrapper / 拆文件 / 缩短方法本身不算改进。** 只有读者为了预测行为所需的事实、semantic hops 或责任区域减少，才算复杂度下降。

## 专题 References

Feature Development 按实际命中加载：

- API / DTO / Header / 失败映射：`orbit-feature-development/references/network-contract.md`
- 列表刷新 / 分页 / 缓存 / 数据归属：`orbit-feature-development/references/list-data-lifecycle.md`
- Koin / ViewModel / Compose host 生命周期：`orbit-feature-development/references/koin-lifetimes.md`
- Compose 结构与状态提升：`orbit-feature-development/references/compose-ui.md`
- UI/Figma/视觉 review：`orbit-feature-development/references/ui-review.md`
- 测试与 KMP 验证：`orbit-feature-development/references/verification.md`

Human Traceability Review 按需加载：

- 三层 Gate：`orbit-human-traceability-review/references/review-gates.md`
- Human Debug Simulation：`orbit-human-traceability-review/references/debug-simulation.md`
- Review Skill 回归基准：`orbit-human-traceability-review/references/orbit-benchmarks.md`

Local Reasoning Refactor 按需加载：

- Semantic Hop / Knowledge Surface / Canonical State 等：`kotlin-local-reasoning-refactor/references/reasoning-metrics.md`
- Kotlin / Compose 局部代码形态：`kotlin-local-reasoning-refactor/references/code-shape.md`

External Architect Handoff:

- PLAN Bundle：`orbit-architect-handoff/SKILL.md`
- Work Order 执行与 Verification Bundle：`orbit-work-order-executor/SKILL.md`

---
name: code-review-and-refactoring
description: Review Orbit changes for correctness, lifecycle and resource safety, architectural boundaries, code smells, and verification evidence; choose the smallest safe refactor when responsibilities drift or repeated coordination appears. Use when asked to review a diff, assess code quality, diagnose a class with mixed responsibilities, decide whether to extract logic or introduce configuration and registration, or prepare a change for handoff.
---

# Code Review And Refactoring

Find real defects first, then assess maintainability. Prefer the smallest change that preserves clear ownership and makes the next change easier to reason about.

## Review 总纲

业务模块 review 优先审两条主线，其他维度只有会实质影响其中之一时才深入：

1. **业务逻辑正确性**：主流程是否清晰、合理、正确；高频或高影响的常规边界、失败、取消、重试与重复操作是否有确定且可见的结果。
2. **业务架构层次**：是否遵循 MVI；状态、事件、数据和副作用的所有权是否清晰；是否高内聚、低耦合；业务与基础能力是否解耦；跨端业务规则是否在共享层保持单一实现；抽象和设计模式是否真实降低当前维护成本。

当流程命中[复杂流程状态机规范](../../../../docs/architecture/state-machines.md)的建模门槛时，review 必须检查状态集合、迁移表、唯一 owner、操作身份、提交/清理责任和转换测试。优先 `sealed` 状态或同等清晰的迁移模型；一组 Boolean、可空字段和嵌套条件共同推导当前流程状态，是需要优先收敛的复杂度信号。简单流程无需为了形式建立状态机。命中异步、多资源或跨层职责拆分时，同时遵循[复杂异步模块的分层与所有权](../../../../docs/architecture/complex-module-design.md)，审查角色边界、依赖方向与类内部主流程。

检查命名和目录是否降低而非增加理解成本：类、文件、接口和方法应准确简明，只表达职责，不重复已有上下文或描述实现步骤；目录应按稳定职责和依赖边界组织。当文件难以定位、职责明显分组或同目录出现多个子职责时，才建议拆子目录或迁移归属。不要将单纯文件数量或个人偏好视为重构依据，也不要用过深层级替代清晰命名。

ROI 是结论的裁决器：先主流程，再高频或高影响的常规边界，最后处理已经发生的架构复杂度。极端低概率 case 仅在有明确产品约束、已知平台限制、安全要求、可观测故障或低成本收益时才成为修复项；否则记录风险和重新评估信号。不要为架构洁癖引入额外层、接口、Factory、注册表或框架。

## Review workflow

1. Establish the scope, user-visible behavior, authoritative state, contract, and non-goals. Reconstruct the actual interaction entry points, UI disabled/Loading conditions, navigation, and context-switch paths; record evidence and a re-evaluation trigger before treating a concurrency or lifecycle path as unreachable. Read the target app's `AGENTS.md`, relevant architecture rules, and the complete diff before reaching conclusions.
2. Read [核心原则结论门禁](../../../../docs/architecture/review-evidence-gate.md), identify every matched principle, and keep its conclusion card as the minimum report. A complete review adds depth to this card; it does not replace it with generic prose.
3. Reconstruct the module's main success, failure, cancellation, and cleanup paths. For platform work, include lifecycle, foreground/background, recreation, callback threading, and native resource ownership.
4. Select the required overlay before reviewing implementation details:

   | Signal | Required reference | Required evidence |
   | --- | --- | --- |
   | API, DTO, HTTP, authentication header, Problem JSON, or `Idempotency-Key` | `orbit-feature-development/references/network-contract.md` | contract, compatibility, and failure mapping |
   | Job, queue, retry, worker, user/tenant switch, SSE connection, or executor | `../../../docs/architecture/context-runtime.md` | owner, lifecycle, cancellation/retry, and late-result behavior |
   | List, cache, refresh, pagination, or scoped local data | `orbit-feature-development/references/list-data-lifecycle.md` + `../../../docs/architecture/loading-feedback.md` | scope, key, invalidation, stale-write behavior, and `ListLoadPhase` conformance |
   | Calendar, notification, permission, system UI, SSE, or deep link | `../../../docs/architecture/platform-capabilities.md` | common/platform boundary, platform constraint, and failure behavior |

5. Audit every complex class that manages coroutine/Job state, queues, resource closing, a multi-stage state machine, or both persistence and external I/O. For each matched class, complete this card:

   | Class | Internal state/resources | Independent reasons to change | Shared invariant | Keep or smallest split |
   | --- | --- | --- | --- | --- |

   Do not use line count alone as a trigger or as a reason to extract.
6. Build a review baseline before judging differences: target-project rules and tested patterns first, then same-repository mature implementations, then primary official documentation when the behavior is external or version-sensitive.
7. Check boundaries: UI, feature, domain, data, platform, storage, network, and app-entry code must not absorb another layer's policy or state. Inventory each newly added component, helper, method, or small capability: if its interface, naming, parameters, errors, and resources are feature-free and it represents an app-wide mechanism, require placement in the matching `core/` directory even with one consumer; if it carries business rules, models, copy, authorization, or workflow semantics, keep it in the feature. Do not accept feature placement merely because it is locally convenient.
8. Identify code smells only after correctness review. Classify each difference as an intentional divergence, acceptable debt, defect, over-design, or missing capability; do not treat a difference from another app as a defect without an invariant.
9. Select the smallest remedy using the decision rules below. Do not treat a refactor as automatically required because a file is long.
10. Report the review point by point against every matched workflow/checklist item: include the core-principle conclusion card, then state concrete code/test/runtime evidence. For a finding, include severity, violated invariant, runtime consequence, smallest safe fix, verification evidence, and residual risk; for an inapplicable item, state why. Include the review card for every class matched in step 5, even when it is an acceptable tradeoff. Do not replace this with generic statements such as “architecture is sound” or “no issue found”, and do not edit unless the request authorizes implementation.

## 按风险展开的 review 维度

以下维度服务于上述两条主线，不与它们竞争优先级：

- **Correctness:** contract compatibility, invalid input, null/empty cases, failure mapping, retries, and observable behavior.
- **State and ownership:** concurrency, cancellation, lifecycle/recreation, temporary resources, connections, delegates, permissions, and cleanup handoff.
- **Architecture:** dependency direction, KMP source-set boundaries, composition-root responsibilities, and avoidance of business policy in platform adapters.
- **Security and privacy:** permissions, external input, files, secrets, logging, and data exposure.
- **Performance and resources:** main-thread work, memory bounds, unbounded queues/collections/retries, and I/O lifetime. Add early size, count, time, or storage limits when a normal product path, a concrete security requirement, a known platform constraint, or observed failures justify them; do not replace mature platform or library behavior solely to defend theoretical extremes.
- **Verification:** use high-ROI checks proportionate to risk; compilation alone is insufficient for changed lifecycle, state, or external-integration behavior.

## Code-smell decision rules

- Treat repeated edits to one central class for unrelated capabilities, a growing constructor of unrelated dependencies, copied coordination logic, and growing variant branches as responsibility-drift signals.
- Keep native callbacks that inherently belong to an app entry point there. Extract only the cohesive coordination or capability-specific work around them.
- Extract a small, strongly typed object when the collaborators are few and fixed. Keep ownership, ordering, errors, and lifecycle explicit.
- Use typed configuration plus explicit registration only when there are multiple real, same-shaped implementations and adding one should not require editing the coordinator. Configuration describes variation; it must not hold business logic.
- Do not introduce a generic dispatcher, string-key registry, reflection scan, implicit ordering, or framework merely because more implementations might appear later.
- Preserve a current direct composition when no independent extension point or duplicated coordination exists. State that choice explicitly as an acceptable tradeoff.
- Prefer the smallest change with a favorable ROI. A cleaner-looking abstraction is not a remedy unless it removes a current defect, repeated coordination, or a credible maintenance cost.
- Classify low-probability edge cases separately from normal-path defects. Record the trigger for revisiting them instead of adding speculative fallbacks, parsers, lifecycle frameworks, or test infrastructure.

### 方法精炼决策表

方法是否保留不以行数判断。目标是让调用点直接表达用户意图、状态约束和失败语义，同时保留真正的边界与不变量。

| 形态 | 默认处理 | 必须保留或允许例外的条件 |
| --- | --- | --- |
| 无参、无副作用、仅返回字段 | 改为只读属性 | 存在懒加载、同步、校验、计算成本或读取语义不是字段时保留方法。 |
| 仅转发到另一个方法 | 删除并内联 | 转发点承担权限、线程、事务、状态 gate、错误映射或跨层边界时保留。 |
| 调用方已完成相同前置判断的 wrapper | 删除 | 多个独立入口需要由该 wrapper 统一维护同一不变量时保留。 |
| 一个方法以 Boolean 切换业务流程 | 拆成具名方法 | Boolean 仅表达独立的低层技术选项、且不改变状态迁移或失败语义时允许保留。 |
| 实现相似但状态迁移或失败语义不同的短方法 | 保留具名分支 | 只有实现、状态迁移和失败语义都相同时才合并。 |
| 同一可观察状态的写入 helper | 保留唯一写入点 | 仅单处使用且不承载状态边界时才内联。 |

审查或重构前逐项确认：

- 删除或合并后，调用点能否直接看出用户意图、状态约束和失败语义？不能则不压缩。
- 该方法是否只重复了调用方已完成的判断或转发？是则删除，除非表中例外成立。
- 它是否是无参、无副作用的纯字段读取？是则优先属性。
- Boolean 是否在选择本应具名的业务路径？是则拆分，避免由调用方猜测分支语义。

## Findings and handoff

- Report only actionable findings as `P0`, `P1`, or `P2`; include file/line, violated invariant, runtime consequence, and a minimal remedy.
- Separate non-blocking design suggestions from defects. Do not elevate personal preference into a bug.
- For a refactor proposal, state the owner, boundaries, state transitions, failure behavior, migration/compatibility impact, test plan, and why the chosen design is simpler than the alternatives.
- Before handoff, summarize commands actually run, unverified paths, and residual risks.

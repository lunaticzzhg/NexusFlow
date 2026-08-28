---
name: orbit-human-traceability-review
description: "Reconstruct and evaluate Orbit business flows for human takeover and debugging. Use for feature/module/flow reviews, complex diffs, coordination analysis, maintainability reviews, or when AI-written code is correct but difficult for a human to trace. Review is flow-first and proof-driven; it does not modify production code by default."
---

# Orbit Human Traceability Review

## Mission

本 Skill 的目标不是“多找问题”，也不是判断代码是否符合某种 Clean Code 审美，而是证明：

> 一个没有参与代码生成过程的人，面对真实故障时，能快速重建链路、定位 Decision / State / Lifecycle Owner，并通过清晰的 Debug Boundary 缩小责任范围。

Review 必须 **Flow-first、Proof-driven**。

不要因为以下事实就判复杂度可接受：

- tests 很多；
- class 名称看起来职责窄；
- state 已 private；
- wrapper 封装了内部对象；
- 没有 dependency cycle；
- 最终可以把行为推导出来；
- 业务本身确实复杂。

这些只能作为辅助证据，不能证明 Human Traceability。

## Hard Boundaries

- 默认不修改 production code；用户明确要求边审边改时，先完成 Reconstruction 和 Gate，再进入对应开发/重构 Skill。
- 审视的基本单位是 **Business Flow / Debug Path**，不是 Detekt candidate 或单个 class。
- 静态复杂度只能作为 source-selection signal，不能替代语义审视。
- `PASS` 需要证据；无法证明时使用 `UNPROVEN`，不要用“看起来没问题”代替。
- 不使用宽松的 `Accepted Complexity`。只有所有相关 Gate 通过后，才允许写 `Proven Necessary Complexity`。
- 不为了显得严格而制造 finding。`FAIL` 和 `PASS` 都必须有真实源码/测试/调用链证据。

## Required Context

开始审视前读取：

- 项目 `AGENTS.md`；
- `.agents/skills/INDEX.md`；
- `docs/architecture/orbit-frontend-architecture.md`；
- 审视范围的 production source；
- 直接 caller / callee；
- 相关 tests；
- 如果用户指定真实故障，读取与现象直接相关的日志/协议/状态代码。

除非确实命中，不要一次性加载所有 references。

## Review Status

每个关键 Gate 使用：

- `PASS`：源码/测试/调用链证据足以证明；
- `FAIL`：存在明确 traceability/correctness/ownership 缺陷；
- `UNPROVEN`：当前证据不足，或链路过于分散，无法在有限上下文内证明。

`UNPROVEN` 不是失败，但禁止把它写成“复杂度可接受”。

---

# Workflow

## Phase 0 — Freeze Scope

先写清：

- Scope：feature/module/page/flow/diff；
- User-visible capability；
- 已知现象或 review 目的；
- 非目标；
- 读取的 source/tests；
- 是否做全 feature review，还是只追某条 Flow。

如果 scope 过大，优先按用户可观察 Flow 切，而不是按 package/class 数量切。

## Phase 1 — Reconstruction（禁止评价）

这一阶段只还原事实。禁止出现 `P1/P2`、重构建议、设计合理/不合理、可接受复杂度等评价。

### 1.1 Flow Inventory

枚举范围内真实可达的重要 Flow，例如：

- Send message；
- Receive streaming reply；
- Recover reply；
- Restore history；
- Generate / retry / dismiss vlog；
- Import / upload / retry transfer。

不要把 helper/function 当 Flow。Flow 必须由 user intent、外部事件、生命周期事件或 retry/recovery 开始，并到可观察 terminal 结束。

### 1.2 Critical Flow Map

对关键 Flow 用真实 symbol 画链路：

```text
Entry
-> Flow Owner / orchestrator
-> Decision
-> Effect / collaborator
-> State Owner
-> projection/rendering
```

保留真实 class/function owner。不要压缩成 `SSE -> Controller -> UI` 这种失去排障价值的图。

### 1.3 Ownership Matrix

至少区分：

- **Capability Owner**：能力属于哪个 feature/module；
- **Flow Owner**：谁负责把一次 business intent 驱动到 terminal；
- **State Owner**：谁是某个 mutable business fact 的唯一 writable owner；
- **Lifecycle Owner**：谁 create/start/cancel/recover/close/reject late result；
- **Decision Owner**：谁决定 ignore/retry/recover/replace/complete 等关键行为；
- **Effect Executor**：谁执行 network/database/Flow emit/coroutine/platform effect。

同一对象可以拥有多个角色，但必须明确写出。

### 1.4 State / Lifecycle Reconstruction

只列对 Flow 行为有决定意义的事实：

- authoritative state；
- operation/context identity；
- running/terminal state；
- duplicate rule；
- late-result rule；
- recovery source；
- cleanup responsibility。

不要先评价字段数量。

### 1.5 Flow Card

每条关键 Flow 至少写：

```markdown
Flow:
Entry:
Capability Owner:
Flow Owner:
State Owner:
Lifecycle Owner:
Decision Owner:
Effect Executor:
Main flow (5-7 semantic nodes):
Success terminal:
Failure terminal:
Cancel terminal:
Recovery:
Duplicate rule:
Late-result rule:
Debug boundaries:
Evidence:
```

如果 5-7 个 semantic node 无法表达主链路，不要强行压缩；记录真实节点数，作为后续 trace-cost 证据。

---

## Phase 2 — Traceability Evaluation

Phase 1 完成后才能评价。

按顺序执行：

```text
Architecture Gate
-> Coordination Gate
-> Local Reasoning Gate（只对关键 Owner）
```

详细清单见 `references/review-gates.md`。

### 2.1 Architecture Gate — Where does it belong?

判断：

- capability 是否放在正确边界；
- authoritative source 是否唯一；
- dependency direction 是否诚实；
- context identity 是否能解释数据/权限归属；
- lifecycle 是否放在拥有资源/operation 的边界；
- package/module 结构是否帮助人找到实现，而不是误导 ownership。

Architecture 没问题不代表整体 PASS，只表示继续下钻 Coordination。

### 2.2 Coordination Gate — Who owns the flow?

重点检查：

- Entry 是否可定位；
- Flow Owner 是否唯一且能负责到 terminal；
- State / Lifecycle / Decision Owner 是否明确；
- success/failure/cancel/recovery 是否闭环；
- duplicate/late-result 是否有 operation identity 和明确拒绝点；
- callback/Flow/controller 协作是否形成 dependency knot；
- 是否存在多个对象共同“碰巧形成”一个 decision；
- 是否有至少两个有用的 Debug Boundary 可以二分责任区域。

典型 smell：

- `lateinit` controller 用来解构造顺序；
- Controller A 控制 Controller B，B 又 callback 回 A；
- 多个 Controller 随意写同一个 StateHolder；
- lifecycle flags 分散在多个 owner；
- callback mesh；
- stateful data orchestrator 被 presentation 直接操控；
- restart/retry/reselect/dismiss 后旧异步结果仍能无条件写 state；
- no-op seam 仍留在调用图中。

### 2.3 Local Reasoning Gate — How does this owner work?

只对主 Flow Owner、关键 State Owner 或明显 hotspot 深入，不要把整个 feature 再拆成 class-by-class review。

检查：

- 一句话 responsibility；
- core concept count；
- authoritative vs derived state；
- canonical state representation；
- illegal state 是否需要读者记住；
- transition 是否可直接解释；
- decision 和 external effect 是否混在一起；
- result type 是否通过多个 nullable/boolean 字段制造组合空间；
- Knowledge Surface；
- Semantic Hop；
- delegation-only wrapper；
- dead state/API/callback/concept。

需要实际行为保持重构时，handoff 到 `kotlin-local-reasoning-refactor`。

---

## Phase 3 — Human Debug Simulation

这是最终验收，不是附录。

对关键 Flow 固定模拟：

1. Input 已收到，但 authoritative state 没变化；
2. State 已变化，但 UI/output 没变化；
3. 同一事件/operation 被重复处理；
4. Flow 永远没有 terminal；
5. Recovery 已执行，但最终结果仍错误。

每种场景至少给：

```text
First checkpoint
-> observable evidence
-> second checkpoint
-> responsible owner
-> next split
```

详细格式见 `references/debug-simulation.md`。

如果必须“每一层都看一下”才能排查，Coordination/Local Traceability 至少应为 `UNPROVEN`，通常是 `FAIL`。

---

## Phase 4 — Findings and ROI

只有完成 Reconstruction + Gate + Debug Simulation 后才输出 Findings。

每个 finding 必须包含：

```markdown
[P0/P1/P2/P3 或 T1/T2/T3] <title>
Affected Flow:
Layer: Architecture / Coordination / Local Reasoning / Debuggability
Broken invariant / traceability contract:
Source evidence:
Runtime or maintenance consequence:
Why hard to debug:
Smallest remedy:
Debug proof after fix:
Tests / verification:
```

Severity 优先按真实影响：

- `P0/P1`：correctness、数据归属、权限、丢失/覆盖、不可恢复 lifecycle；
- `P2`：用户可见行为、明确高维护风险；
- `P3`：低风险局部摩擦。

同时可以附 traceability severity：

- `T1`：常见故障无法快速确定责任 owner；
- `T2`：可以排查，但需要跨多个 owner/state model；
- `T3`：命名/dead seam/多余 hop 增加排障成本。

### ROI

优先：

1. correctness / operation identity / late-result；
2. terminal / cancel / recovery 闭环；
3. dependency knot / callback mesh / 写 owner 分散；
4. 高 Knowledge Surface / Semantic Hop 的核心 owner；
5. dead concept / stale seam / naming friction。

不要优先按 LOC 排序。

---

# Proof Rules

## Do not confuse reconstructable with readable

你的能力可以跨很多文件推理，不能用“我最终理解了”作为代码易读的证据。

必须分别回答：

```text
Behavior reconstructable: YES / NO
Human trace cost: LOW / MEDIUM / HIGH
```

并说明需要加载的 owners、concepts、state invariants 和 semantic hops。

## Delegation-only wrappers are neutral

例如：

```kotlin
fun consumeDelta(...) = decoder.consumeDelta(...)
```

只有当 caller 因此不再需要知道调用顺序或 invariant 时，才算 ownership/traceability 改善。

判断方法：

> 如果直接恢复成调用内部对象，caller 需要掌握的业务知识是否变化？

若 `NO`，Human Traceability benefit 记为 0。

## Proven Necessary Complexity

只有相关 Architecture / Coordination / Local / Debug Simulation Gate 都 `PASS` 后，才允许将剩余复杂度标记为 `Proven Necessary Complexity`。

说明剩余复杂度来自什么真实业务/协议 invariant，以及为什么无法在不丢失语义的前提下继续降低人的 trace cost。

---

# Recommended Output

```markdown
# <Scope> Human Traceability Review

## 0. Scope & Evidence

## 1. Flow Inventory

## 2. Reconstruction
### 2.x <Flow>
- Flow map
- Flow card

## 3. Ownership Matrix

## 4. Lifecycle / State Model

## 5. Architecture Gate
| Gate | Status | Evidence | Consequence |

## 6. Coordination Gate
| Gate | Status | Evidence | Consequence |

## 7. Local Reasoning Hotspots
| Owner | Knowledge Surface | Semantic Hops | Status | Why |

## 8. Human Debug Simulation

## 9. Findings

## 10. Proven Necessary Complexity
仅在已证明时填写；否则写“无 / 未证明”。

## 11. Layer Handoff
- Architecture follow-up:
- Coordination follow-up:
- Local refactor targets:
- Feature behavior changes:

## 12. ROI Order

## 13. Verification Gaps
```

# Multi-agent Recommendation

如果环境支持 subagent，复杂 feature 优先使用两个独立 pass：

- Agent A：只做 Reconstruction，不评价；
- Agent B：读取源码 + Reconstruction，做 Gate / Debug Simulation / Findings。

不要让一个 agent 同时“第一次理解 + 立刻证明自己理解得很好”。

# Self-check

修改本 Skill 后，使用 `references/orbit-benchmarks.md` 中固定场景做 fresh-context 回归。Review Skill 自己也需要测试。

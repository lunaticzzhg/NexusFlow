---
name: kotlin-local-reasoning-refactor
description: "Perform a behavior-preserving refactor on one clearly scoped Orbit Kotlin owner/class/function group to reduce human reasoning cost. Use after flow ownership is already clear, when state models, semantic hops, knowledge surface, result combinations, delegation wrappers, naming, or dead concepts make the implementation hard to understand or debug."
---

# Kotlin Local Reasoning Refactor

## Mission

目标不是让代码“更整齐”，而是：

> 让一个第一次接手该 owner 的人，为了预测行为和排查故障，需要知道更少的事实、跨更少的 semantic hops、维护更小的状态空间。

一个 refactor 只有在至少降低以下一项时才算成功：

- Knowledge Surface；
- Semantic Hop；
- mutable/write entry 数量；
- semantic state 的 representation 数量；
- illegal state / nullable-boolean 组合空间；
- decision 所跨的责任区域；
- dead concept / stale seam 对 mental model 的干扰。

单纯拆文件、缩短方法、增加 wrapper、把字段 private、增加 `Manager/Helper/Runtime` 不算收益。

## Hard Boundaries

- 一次只重构一个明确 owner 或紧密函数组。
- 如果问题跨多个 Flow Owner / Controller / Runtime，先用 `orbit-human-traceability-review`；不要用单类重构掩盖中层 ownership 问题。
- 默认行为保持。若必须改变产品语义、API contract、durable state、权限、恢复策略，转 `orbit-feature-development`。
- 不为了模式完整性强制 State Machine / Reducer / Pipeline / Strategy。
- 不保留 private/internal dead API “以后可能有用”。
- 不允许 delegation-only wrapper 被记作 ownership move。

## Required Context

读取：

- `AGENTS.md`；
- `.agents/skills/INDEX.md`；
- `docs/architecture/orbit-frontend-architecture.md`；
- 目标源码；
- 直接 caller/callee；
- 相关 tests；
- 如果该 owner 位于复杂 Flow 中，读取已有 Human Traceability Review 或先简短重建上下游边界。

按需读取：

- `references/reasoning-metrics.md`；
- `references/code-shape.md`。

---

# Workflow

## 1. Freeze Behavior

先写：

```text
Scope:
Behavior to preserve:
Inputs:
Observable outputs:
Failure/cancel/recovery behavior:
Authoritative state:
Relevant tests:
Non-goals:
```

没有这一步，不开始改结构。

## 2. Build Before Baseline

必须先建立原始 reasoning baseline：

```markdown
Responsibility:
Core concepts:
Authoritative state:
Derived state:
Mutable fields / containers:
Write entries:
Semantic states and representations:
Key transitions:
External effects:
Knowledge Surface:
Happy-path Semantic Hops:
Failure/recovery Semantic Hops:
Debug checkpoints:
Dead/stale concepts:
```

不要把 LOC / method count 当核心 baseline。

## 3. Identify the Real Source of Reasoning Cost

按优先级寻找：

### A. Dead concepts

先删：

- production 无调用的 private/internal API；
- 只被测试固定的实现细节；
- no-op callback / authorization seam；
- 不再影响行为的 flag/generation/cache；
- 重复 exhaustive mapping 可以通过 model 改善消掉的 helper。

### B. Non-canonical state

一个语义事实存在多种表示时先 normalize。

例如：

```text
cursor == null
或 cursor 指向末尾 offset == length
都代表 complete
```

优先改成单一 canonical representation。

### C. Hidden state combinations

多个 boolean/nullable 共同表达 phase，导致读者必须记合法组合时，考虑显式 model；只有真实状态集合稳定时才引入 enum/sealed state。

### D. Decision ownership

如果外层对象仍决定：

```text
何时调用 A
何时调用 B
何时更新 revision
何时 recovery
何时 terminal
```

把 A/B 封装成 wrapper 并不算 owner move。

真正的 owner move 要把 **decision / sequencing / invariant** 一起吸收。

### E. Decision / Effect mixing

当一个函数同时：

```text
判断 protocol/state
+ 修改 runtime
+ launch coroutine
+ network/db
+ emit Flow
+ log
```

优先寻找：

```text
Input -> Transition/Decision -> Effect -> Effect execution
```

这种可二分边界。

### F. Result combination complexity

多个 nullable/boolean 字段表示不同结果时，读者需要自己推断合法组合。

考虑用具名 result/effect/transition 表达合法空间；不要为“类型化”而制造更多无意义 wrapper。

### G. Concept mixing

如果一个 owner 同时要求理解多个独立模型，例如：

```text
content reconciliation
reveal progression
renderer invalidation
unicode stepping
```

优先做 semantic decomposition，让读者可以只加载当前问题所需概念。

### H. Naming / code shape

最后再处理命名、函数顺序、helper 边界、文件组织。命名优先表达 intent，不表达 mechanism。

## 4. Wrapper Test

对新增/已有 wrapper 问：

> 如果直接恢复为 caller 调内部对象，caller 需要知道的业务知识是否减少？

- `YES`：wrapper 可能形成了 semantic abstraction；
- `NO`：wrapper 是 neutral，不计入重构收益；必要时删除。

## 5. Implement in Small Behavior-preserving Slices

推荐顺序：

```text
dead concept cleanup
-> canonical state
-> transition/result model
-> decision ownership
-> decision/effect boundary
-> semantic decomposition
-> naming/code shape
```

每个切片：

1. 改最小源码；
2. 更新/删除只固定实现细节的测试；
3. 运行目标测试；
4. 确认没有保留旧 seam；
5. 记录 reasoning baseline 是否真的下降。

不要先创建一堆新类型再尝试把旧代码搬进去。

## 6. Build After Baseline

使用与 Before 相同的字段重新评估。

必须明确列出：

```text
Facts removed from Knowledge Surface:
Semantic Hops removed:
Write entries removed:
Illegal/canonical states simplified:
Dead concepts removed:
New concepts introduced:
Net reasoning benefit:
```

如果新 abstraction 增加的概念与删除的一样多，不能轻易宣称成功。

## 7. Human Debug Simulation

至少模拟与该 owner 相关的：

- input 到了但 state 没变；
- state 已变但 output/UI 没变；
- duplicate/late result；
- terminal 卡住；
- recovery 后结果仍错。

只覆盖适用项。

目标：重构后第一/第二检查点更明确，或者责任范围更小。

## 8. Verify

执行：

- 目标 unit tests；
- 受影响 feature tests；
- 必要 KMP compile/build；
- 如果删除 dead API，搜索全仓调用；
- 如果改变 state representation，补 transition/edge tests；
- 如果调整 async owner，补 stale/late/cancel/recovery tests。

---

# Refactor Decision Rules

## Do not extract methods just to shorten code

只有至少满足一个条件才抽：

- 代表可独立命名的业务/领域概念；
- 隐藏一个更低抽象层；
- 隔离外部 side effect；
- 有独立 contract/invariant；
- 有真实复用。

## Prefer intent over mechanism

问：

> 如果实现机制完全换掉，这个方法名仍然解释 caller 为什么调用它吗？

如果不能，名称通常过于 implementation-driven。

## Prefer named operations over boolean configuration

真实调用组合有限时：

```text
restoreLatestReply()
retryReply()
```

通常优于：

```text
recoverReply(..., replace=true, allow=false, requireActive=true)
```

不要为不存在的未来 caller 提前泛化。

## One semantic state, one canonical representation

只要读者必须记住“X 和 Y 两种表示都意味着 complete”，就优先 normalize。

---

# Output / Handoff

完成后报告：

```markdown
## Scope & Frozen Behavior

## Before Reasoning Baseline

## Refactor Slices

## After Reasoning Baseline

## Net Human Traceability Change
- Knowledge Surface:
- Semantic Hops:
- State representations:
- Write entries:
- Debug boundaries:

## Human Debug Simulation

## Verification

## Residual Complexity
仅列真实必要复杂度和重新评估条件。
```

如果 After 并没有明显降低人的 reasoning cost，应明确写“结构整理完成，但 Human Traceability 未显著改善”，不要把它包装成成功。

---
name: orbit-feature-development
description: "Implement Orbit KMP features and fixes with the project architecture, existing patterns, thin vertical slices, and Human Traceability as a design constraint. Use for screens, business flows, API/DTO, ViewModel/state, Compose, Koin, platform capability, lifecycle, async work, or related defects."
---

# Orbit Feature Development

## Mission

功能首先要正确，其次要让未来的人能够快速接管和排障。复杂功能在编码前就建立可追踪的 Flow/Owner/State/Lifecycle 模型，而不是写完后再靠“readability cleanup”补救。

当请求属于非轻量 feature、复杂 bug、结构性重构、明确 Human Traceability 改善目标，或 Codex 无法快速证明 flow/state/lifecycle owner 时，先切换到 `orbit-architect-handoff` 生成 External Architect PLAN Bundle。拿到自包含 Work Order 后，再用 `orbit-work-order-executor` 执行；不要由 Codex 自行设计再自行批准。

## Required Sources

每次开发先读取：

- `AGENTS.md`；
- `.agents/skills/INDEX.md`；
- `docs/architecture/orbit-frontend-architecture.md`；
- 当前 feature 的同职责实现、直接 caller/callee 和相关 tests。

协议/产品事实优先于客户端推断。

## Workflow

### 1. Search Existing Implementation First

任何新增代码或行为改动前：

- 搜当前 feature；
- 搜 `core`；
- 搜同仓成熟 feature；
- 搜已有 resources/tests。

比较：

```text
responsibility
state/lifecycle
callers
failure semantics
dependency boundary
```

不要按类名相似就照搬。

结论只能是：

- 直接复用；
- 向后兼容扩展；
- 在最小 owner 内新增。

### 2. Classify the Change

#### Lightweight

纯文案/样式/机械接线、无新业务语义和状态生命周期。

只需：目标、非目标、参考实现、目标验证。

#### Non-trivial

命中任一：

- 新/改 Business Flow；
- API/DTO/认证/权限；
- async/retry/cancel/recovery；
- Context/account/family/session；
- 多 owner/controller/runtime；
- 新 mutable state；
- Koin/lifecycle/platform capability；
- 影响历史恢复、late result、duplicate；
- 复杂 Compose state/effect。

必须填写 Traceability Design Card。

### 3. Traceability Design Card

```markdown
## Existing Implementation Decision
- Search scope / reference implementation:
- Decision: reuse / compatible extension / add in smallest owner
- Relevant differences:
- Verification:

## User Intent & Contract
- User intent:
- Observable success:
- Observable failure/empty/unavailable:
- Non-goals:

## Architecture
- Capability owner:
- Authoritative source:
- Dependency direction affected:
- Context identity (if any):

## Coordination
- Entry:
- Flow Owner:
- State Owner(s):
- Lifecycle Owner:
- Decision Owner(s):
- Effect Executor(s):
- Main flow (5-7 semantic nodes):
- Success terminal:
- Failure terminal:
- Cancel terminal:
- Recovery:
- Duplicate rule:
- Late-result rule:
- Debug boundaries:

## Local Reasoning
- New/changed core concepts:
- New mutable facts and write entries:
- New boolean/options/phase/generation/nullability:
- Canonical state representation:
- Known knowledge-surface risk:

## Thin Slices
1. Behavior / files / verification:
```

如果这些 owner 无法说清楚，不要开始堆实现；先使用 `orbit-human-traceability-review` 重建现有 Flow 或调整设计。

如果 owner 不清属于结构性设计问题，或者目标本身是降低 Human Traceability 成本，使用 `orbit-architect-handoff` 把完整项目上下文交给 External Architect，而不是继续由 Codex 决定 target ownership。

### 4. Implement Thin Vertical Slices

每个切片从真实行为贯通：

```text
contract/data boundary
-> flow/decision
-> authoritative state
-> presentation
-> verification
```

不要先完成整页 UI 再补业务，也不要先建一套抽象层再找 caller。

优先保持：

- 一个 mutable business fact 一个 writable owner；
- lifecycle 与 operation/resource owner 对齐；
- async result 带足够 identity 判断 stale/duplicate；
- success/failure/cancel/recovery 明确 terminal；
- decision 与 effect 有可排障边界；
- state 正确后，presentation/debug 不需要重新追 network。

### 5. Avoid AI-specific Accumulation Patterns

编码时禁止把这些当默认扩展方式：

- 每个 edge case 新增一个 boolean flag；
- 为未来需求增加 options 参数；
- 为减少 diff 在明显恶化的协调层或控制器上继续 patch；
- 为缩短方法提取无语义 3-5 行 helper；
- `Manager/Helper/Runtime` 收纳新增逻辑；
- 通过 wrapper 隐藏字段但仍由 caller 决定内部调用顺序；
- 保留已经 no-op 的 callback/API “以后也许有用”。

如果新需求会迫使同一 owner 再增加 lifecycle flag、mode、分支或跨对象控制，允许先做行为保持的局部重构，再实现 feature。

### 6. Slice Verification

每个切片完成后检查实际命中的：

- success；
- validation/empty/failure；
- retry/cancel；
- duplicate；
- late result；
- context change；
- recovery；
- UI authoritative-state mapping。

只为真实可达路径增加保护和测试。

### 7. Human Takeover Check

非轻量改动交付前必须重新根据 **实际实现** 回答：

```markdown
## Human Takeover Check

### Actual Flow
Entry -> ... -> terminal

### Ownership
- Flow Owner:
- State Owner:
- Lifecycle Owner:
- Key Decision Owner:

### Local Reasoning
- New concepts introduced:
- New mutable/write entries:
- Any non-canonical state:
- Any delegation-only wrapper:
- Dead/stale seams left behind:

### Debug Simulation
- Input received, state unchanged: first / second checkpoint
- State changed, UI/output unchanged: first / second checkpoint
- Duplicate/late result: identity / rejection owner
- Never terminal: running state / lifecycle owner
- Recovery incorrect: source / decision / effect / state checkpoint
```

如果实际链路与设计卡明显偏离、排障需要跨多个不明确 owner，先修结构再交付。

### 8. Review / Refactor Handoff

- 想审视整个 feature/flow：`orbit-human-traceability-review`；
- 想把复杂 feature、复杂 bug 或结构性重构交给外部架构判断：`orbit-architect-handoff`；
- 已有外部 Work Order 需要执行：`orbit-work-order-executor`；
- 已明确一个 owner 的 reasoning cost 高：`kotlin-local-reasoning-refactor`；
- 只想找静态热点：`kotlin-complexity-audit`。

## Topic Routing

- API / DTO / HTTP / Header / failure mapping：`references/network-contract.md`
- List refresh / pagination / cache / scope ownership：`references/list-data-lifecycle.md`
- Koin / ViewModel / Compose host lifetime：`references/koin-lifetimes.md`
- Compose state hoisting / reusable UI：`references/compose-ui.md`
- UI/Figma/visual review：`references/ui-review.md`
- Tests/KMP verification：`references/verification.md`

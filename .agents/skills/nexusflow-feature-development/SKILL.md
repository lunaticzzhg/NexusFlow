---
name: nexusflow-feature-development
description: "Implement NexusFlow product requirements and user-observable behavior changes with full-flow reasoning across App, Contracts, Backend, and AI while changing only the modules actually required."
---

# NexusFlow Feature Development

## Mission

功能首先要正确，其次要让未来的人能够快速接管和排障。本 skill 是 NexusFlow 的默认产品需求入口，用于新产品能力、用户可见行为变化、API 连接功能、owner 尚未证明的行为 bug，以及可能跨 App / Contracts / Backend / AI 的 feature 工作。

核心原则：

> Full-flow reasoning, minimum necessary changes.

每个产品需求都先跨 App、shared Contracts、Backend、AI 思考完整用户语义链路，但这不表示四个区域都要改。`NO CHANGE` 是有效设计结论，不是实现不完整。

当 reconnaissance 后发现需求需要新的或改变的 cross-owner architecture、durable workflow/state machine、permission/trust model、compatibility-sensitive wire architecture、runtime/process boundary、owner/lifecycle 归属，或复杂 recovery/duplicate/late-result 语义时，通过 `nexusflow-ai-handoff` 将完整项目上下文交给独立 Architect。Task Contract 必须明确要求结构判断，并将 Expected Deliverable 明确为自包含 Work Order。拿到自包含 Work Order 后，再用 `orbit-work-order-executor` 执行；不要由同一个实现 Agent 自行设计再自行批准。

## Required Sources

每次开发先读取：

- `AGENTS.md`；
- `.agents/skills/INDEX.md`；
- 与实际 touched scope 匹配的 architecture authority：
  - App/KMP：`docs/architecture/orbit-frontend-architecture.md`；
  - Backend：`docs/architecture/nexusflow-backend-architecture.md`；
  - AI/planning：`docs/architecture/nexusflow-ai-architecture.md`；
  - Contracts：读取 `references/contracts.md`，并核对真实 producer/consumer source/tests；
- 当前需求涉及区域的同职责实现、直接 caller/callee 和相关 tests。

Backend 协议、权限和持久业务事实优先于客户端推断。AI 可以提出计划和理由，但 Backend 仍拥有权限、审批、持久化、幂等、side effect 与可信状态。

当需求触及 Backend JDBC、migration、transaction、FK / UNIQUE / CHECK、idempotency、optimistic concurrency 或 durable multi-write 时，读取 `references/backend-persistence.md`，并按其中 checklist 给出持久化验证证据。

## Workflow

### 1. User Flow Discovery

在 class/file 设计前，先写清最小端到端语义：

```text
User intent
Observable success
Observable failure/unavailable state
Final authoritative fact
Side effects, if any
Approval/trust boundary, if any
```

尽量使用现有真实 symbol 重建链路。不要从 App 页面、Backend endpoint 或 AI planner 假设单点开始。

### 2. Scope Matrix

每个非轻量需求都记录：

```markdown
| Area | Current responsibility | Change? | Why / evidence |
| --- | --- | --- | --- |
| App | ... | YES/NO | ... |
| Contracts | ... | YES/NO | ... |
| Backend | ... | YES/NO | ... |
| AI | ... | YES/NO | ... |
```

例子：

```text
UI text/style only:
App CHANGE; Contracts/Backend/AI NO CHANGE

Backend auth safety fix:
Backend CHANGE; App/Contracts/AI NO CHANGE unless evidence says otherwise

Planning product feature:
App + Contracts + Backend + AI may all CHANGE
```

不要把 full-stack development 理解为每个 module 必须有文件 churn。

### 3. Existing Implementation Search By Touched Area

任何新增代码或行为改动前：

- 搜每个实际 touched module 的当前 capability；
- 搜该区域的 `core` / shared foundation；
- 搜同仓成熟 feature 或 backend flow；
- 搜已有 resources/tests；
- 读取该区域的 architecture authority。

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

### 4. Classify the Change

#### Lightweight

纯文案/样式/机械接线、无新业务语义和状态生命周期。

只需：目标、非目标、参考实现、目标验证。

#### Non-trivial

命中任一：

- 新/改 Business Flow；
- API/DTO/认证/权限；
- cross-boundary contract；
- async/retry/cancel/recovery；
- Context/account/family/session；
- 多 owner/controller/runtime；
- 新 mutable state；
- Koin/lifecycle/platform capability；
- 影响历史恢复、late result、duplicate；
- 复杂 Compose state/effect；
- AI planning/proposal/guardrail boundary。

必须填写 Traceability Design Card。

### 5. Full-stack Traceability Design Card

```markdown
## Existing Implementation Decision
- Search scope / references:
- Reuse / compatible extension / add in smallest owner:
- Why:

## User Intent & Observable Contract
- Intent:
- Success:
- Failure/unavailable:
- Non-goals:

## Scope Matrix
- App:
- Contracts:
- Backend:
- AI:

## Cross-boundary Contract
- Wire contract changed?:
- Producer:
- Consumer(s):
- Compatibility requirement:
- Module-internal models that must NOT enter contracts:

## Architecture & Ownership
- Authoritative source:
- App interaction/state owner (if touched):
- Backend Flow/State/Decision/Effect owners (if touched):
- AI proposal/reasoning owner (if touched):
- Context/tenant/task/planning identity:

## Coordination
- Entry:
- End-to-end main flow (5-9 semantic nodes):
- Success terminal:
- Failure terminal:
- Cancel/recovery if reachable:
- Duplicate/idempotency rule if reachable:
- Late/stale-result rule if reachable:
- Debug boundaries:

## Local Reasoning
- New/changed core concepts:
- New mutable facts and write entries:
- New boolean/options/phase/generation/nullability:
- Canonical state representation:
- Known knowledge-surface risk:

## Thin Slices
1. Behavior / modules / files / verification:
```

不可达的 lifecycle path 不强行填设计；标记 `N/A` 并写明证据。若 owner 无法说清楚，不要开始堆实现；先使用 `orbit-human-traceability-review` 重建现有 Flow 或调整设计。

如果 owner 不清属于结构性设计问题，或者目标本身是降低 Human Traceability 成本，使用 `nexusflow-ai-handoff` 把完整项目上下文交给独立 Architect。Requested Action 明确要求进行结构判断，Expected Deliverable 明确为自包含 Work Order，而不是继续由 Codex 决定 target ownership。

### 6. Dependency-driven Thin Vertical Slices

不要默认 App-first。按真实依赖选择顺序，例如 cross-stack feature 常见路径可能是：

```text
business invariant / ownership
-> wire contract when needed
-> Backend authority/flow
-> AI proposal boundary when needed
-> Backend validation/integration
-> App integration
-> end-to-end verification
```

但不要为了 App-only 需求创建空的 Contract/Backend/AI work。

每个切片从真实行为贯通：

```text
contract/data boundary if touched
-> flow/decision
-> authoritative state/result
-> outward output/presentation
-> verification
```

优先保持：

- 一个 mutable business fact 一个 writable owner；
- lifecycle 与 operation/resource owner 对齐；
- async result 带足够 identity 判断 stale/duplicate；
- success/failure/cancel/recovery 明确 terminal；
- decision 与 effect 有可排障边界；
- state 正确后，presentation/debug 不需要重新追 network。

### 7. Avoid Accumulation Patterns

编码时禁止把这些当默认扩展方式：

- 每个 edge case 新增一个 boolean flag；
- 为未来需求增加 options 参数；
- 为减少 diff 在明显恶化的协调层或控制器上继续 patch；
- 为缩短方法提取无语义 3-5 行 helper；
- `Manager/Helper/Runtime` 收纳新增逻辑；
- 通过 wrapper 隐藏字段但仍由 caller 决定内部调用顺序；
- 保留已经 no-op 的 callback/API “以后也许有用”。

如果新需求会迫使同一 owner 再增加 lifecycle flag、mode、分支或跨对象控制，允许先做行为保持的局部重构，再实现 feature。

### 8. Slice Verification

每个切片完成后检查实际命中的：

- success；
- validation/empty/failure；
- retry/cancel；
- duplicate；
- late result；
- context change；
- recovery；
- authoritative-state/result to outward-output mapping。

只为真实可达路径增加保护和测试。验证从实际 touched scope 推导，详见 `references/verification.md`。

### 9. Human Takeover Check

非轻量改动交付前必须重新根据 **实际实现** 回答跨 touched modules 的真实链路：

```markdown
## Human Takeover Check

### Actual Flow
Entry -> ... -> terminal

### Ownership
- Flow Owner:
- State Owner:
- Lifecycle Owner:
- Key Decision Owner:
- Effect Executor:

### Local Reasoning
- New concepts introduced:
- New mutable/write entries:
- Any non-canonical state:
- Any delegation-only wrapper:
- Dead/stale seams left behind:

### Debug Simulation
- Input received, state unchanged: first / second checkpoint
- Authoritative state/result changed, outward output unchanged: first / second checkpoint
- Duplicate/late result: identity / rejection owner
- Never terminal: running state / lifecycle owner
- Recovery incorrect: source / decision / effect / state checkpoint
```

如果实际链路与设计卡明显偏离、排障需要跨多个不明确 owner，先修结构再交付。

### 10. Review / Refactor Handoff

- 想审视整个 feature/flow：`orbit-human-traceability-review`；
- 想把当前 NexusFlow 任务交给另一个 AI：`nexusflow-ai-handoff`；
- 想把复杂 feature、复杂 bug 或结构性重构交给外部架构判断：通过 `nexusflow-ai-handoff` 指定 External Architect、architecture/ownership/lifecycle decisions 和自包含 Work Order；
- 已有外部 Work Order 需要执行：`orbit-work-order-executor`；
- 已明确一个 owner 的 reasoning cost 高：`kotlin-local-reasoning-refactor`；
- 只想找静态热点：`kotlin-complexity-audit`。

## Topic Routing

- Cross-boundary wire contracts：`references/contracts.md`
- App/KMP API / DTO / HTTP / Header / failure mapping：`references/network-contract.md`
- App/KMP list refresh / pagination / cache / scope ownership：`references/list-data-lifecycle.md`
- App/KMP Koin / ViewModel / Compose host lifetime：`references/koin-lifetimes.md`
- App/KMP Compose state hoisting / reusable UI：`references/compose-ui.md`
- App/KMP UI/Figma/visual review：`references/ui-review.md`
- Backend JDBC / migration / transaction / FK / UNIQUE / idempotency / optimistic concurrency / durable multi-write：`references/backend-persistence.md`
- Scope-aware verification：`references/verification.md`

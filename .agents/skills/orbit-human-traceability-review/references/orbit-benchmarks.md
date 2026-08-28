# NexusFlow Traceability Review Benchmarks

用于回归测试 Review Skill。每次重大修改 Skill 后，在 fresh context 中用当前源码跑这些场景；目的是测试 review 能否正确还原 Flow 和 Debug Boundary，而不是要求源码一定有对应 bug。

## Benchmark A — App Session Restore / Refresh

现象：应用启动或恢复时，本地 session 已存在，但认证 gate/output 未进入已登录状态，或 refresh 后状态不一致。

Review 应至少重建：

```text
AppStartup
-> AuthSessionController.restore
-> secure/local store and auth repository
-> published auth state
-> Auth gate/output
```

要求：识别 authoritative session state，区分本地恢复、refresh 决策和 published projection；一旦本地 state 已证明正确，不应重新从 network 开始排查 UI/output。

## Benchmark B — App Stale Google Sign-In Result

现象：用户发起 Google sign-in request A，随后取消、离开或重启并发起 request B；A 的系统 UI 回调迟到。

Review 应寻找：

```text
SystemUiGateway request identity
-> platform completion
-> result mapping
-> stale result rejection
-> auth/session state owner
```

要求：定位 operation identity、late-result owner、拒绝旧结果的 observable proof，以及旧结果不能激活新 session 的责任边界。

## Benchmark C — Backend Refresh Rotation / Reuse

现象：refresh token 被正常轮换，或旧 refresh token 被重复使用。

Review 应重建：

```text
Ktor Route
-> AuthService.refresh
-> repository lookup
-> rotation transaction
-> family revoke on invalid/reuse
-> response/error mapping
```

要求：分清 application service 的 business decision owner 与 repository/infrastructure 的 transaction execution owner；检查 terminal response/error 和 durable mutation 的 debug boundary。

## Benchmark D — Cross-Boundary Auth Contract

现象：shared auth request/response 发生字段或语义疑问，App 与 Backend 行为不一致。

Review 应区分：

```text
shared auth request/response serialization
-> Backend route mapping
-> Backend application/domain decision
-> response serialization
-> App data/domain consumption
-> App auth state/output
```

要求：把 wire contract 与 Backend domain/persistence model、App presentation/domain model 分开；只在当前 source 支持的 producer/consumer 上下文中下结论。

## Benchmark E — AI Source Absent

现象：用户询问当前 AI planner/runtime 谁拥有 state、lifecycle 或 provider retry。

Review 应返回：

- current runtime/internal ownership: `UNPROVEN` / absent；
- documented boundary fact: Backend authoritative state -> read-only planning context -> future Kotlin Planner proposal -> Backend validation/approval/persistence/effect；
- no invented Planner/Provider/Runtime classes；
- no claim that current `ai/` implements a runtime。

要求：AI 边界规则可以作为 proven architecture fact；当前 runtime/source 内部结构必须保持 `UNPROVEN`，不能凭产品文档补造实现。

## Review Regression Questions

每次 benchmark 结束检查：

1. 是否先 Reconstruction 再 Finding？
2. 是否准确使用真实 symbol，而不是抽象词替代链路？
3. 是否把 tests 当 correctness 证据，而非 traceability 证明？
4. 是否对未证明区域使用 `UNPROVEN`？
5. 是否因为 class 很大就机械建议拆分？
6. 是否因为 wrapper/private state 就过度评价 ownership 已改善？
7. 是否给出了能让人快速二分的 Debug Boundary？
8. 是否把 Local issue 错判成 Architecture issue？
9. 是否机械推荐 State Machine / Reducer / Manager 等模式？
10. 是否把 AI absent source 错判成已有 runtime？

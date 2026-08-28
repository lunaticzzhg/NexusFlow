# Orbit Traceability Review Benchmarks

用于回归测试 Review Skill。每次重大修改 Skill 后，在 fresh context 中用当前源码跑这些场景；目的是测试 review 能否正确还原 Flow 和 Debug Boundary，而不是要求源码一定有对应 bug。

## Benchmark A — Reply input arrived, UI missing

现象：SSE 已收到 assistant reply 内容，但 Chat UI 没显示。

Review 应至少能区分：

```text
realtime input
-> reply protocol/decision
-> domain update
-> conversation authoritative state
-> typewriter/presentation state
-> Compose rendering
```

必须给出 state 正确后停止追上游的二分路径。

## Benchmark B — Domain reply complete, typewriter stuck

现象：Conversation authoritative content 已完整，但逐字显示卡住。

Review 不应重新检查 SSE/network；应把范围缩到：

```text
Conversation State
-> ReplyTypewriter
-> Renderer
```

并检查 canonical reveal state / cursor / renderer invalidation。

## Benchmark C — Late Vlog result after restart

现象：operation A 开始生成；用户 restart/reselect 后 operation B 成为当前操作；A 的 poll 结果迟到。

Review 应寻找：

- operation/generation identity；
- late-result decision owner；
- writable state owner；
- restart/reselect/dismiss 后旧 identity 如何失效；
- stale result 的 observable rejection proof。

## Benchmark D — Cancel reply then late event arrives

Review 应重建：

- cancel entry；
- lifecycle/registration owner；
- terminal route；
- late SSE rejection/ignore point；
- state 是否可能被重新写回。

## Benchmark E — History restore differs from snapshot

Review 应区分：

```text
history load
-> reply restore/recovery
-> authoritative conversation state
-> presentation projection
```

并能指出 snapshot/revision/late update 的 decision owner。

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

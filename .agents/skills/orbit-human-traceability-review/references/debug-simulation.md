# Human Debug Simulation

目的：模拟一个没有参与生成过程的人如何从“现象”追到“责任 owner”。

不要回答“可能看看 A/B/C”。每个场景必须给最短的检查路径和可观察证据。

## Scenario 1 — Input received, state unchanged

回答：

```text
Input evidence:
First decision checkpoint:
Decision Owner:
Expected transition/effect:
State write checkpoint:
State Owner:
If missing, next split:
```

目标：能区分“输入没进入核心 flow / decision 丢弃 / effect 失败 / state write 缺失”。

## Scenario 2 — Authoritative state/result changed, outward output unchanged

```text
Authoritative state/result checkpoint:
Output projection/adapter checkpoint:
Local presentation/read-model/planner-result checkpoint:
Renderer/HTTP response/event/output checkpoint:
First owner to inspect:
```

Variant routing:

- App: state -> projection -> presentation -> renderer.
- Backend: durable/domain state -> mapper/read model -> HTTP response/event.
- AI: typed candidate -> deterministic validation/projection -> Planner result.

只检查当前 flow 命中的 variant。目标：authoritative fact 已正确时，不要重新从 network/provider/input 开始查。

## Scenario 3 — Duplicate processing

```text
Operation/event identity:
Duplicate decision owner:
Idempotency/rejection point:
Observable proof:
```

## Scenario 4 — Flow never terminal

```text
Running-state owner:
Expected terminal transitions:
Outstanding resource/job/subscription:
Who closes/cancels it:
First observable proof:
```

## Scenario 5 — Recovery completed, result still wrong

```text
Recovery decision owner:
Recovery authoritative source:
Effect executor:
State replacement/merge owner:
Late-result rejection after recovery:
First divergence checkpoint:
```

## PASS Standard

一个关键 Flow 的 Debug Simulation 通常应满足：

- 前两个检查点就能把责任区域缩小到 1-2 个 owner；
- 每个 checkpoint 有可观察 state/result/log/test evidence；
- 不需要先完整理解整个 feature；
- state 正确后，不再回头重查上游；
- late/duplicate/recovery 都有明确 identity/decision owner。

否则至少标 `UNPROVEN`；如果结构本身导致无法二分，标 `FAIL`。

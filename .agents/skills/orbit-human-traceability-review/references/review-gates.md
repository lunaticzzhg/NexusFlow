# Human Traceability Review Gates

本参考只提供审视清单。主流程以 `../SKILL.md` 为准。

## Architecture Gate — Where does it belong?

| Gate | PASS 需要证明什么 | 常见 FAIL / UNPROVEN 信号 |
| --- | --- | --- |
| Capability ownership | 能力归属 feature/core/app/backend service/contracts/planning boundary 清楚 | composition root 放在 core、feature 语义漂入 shared core、Backend domain 模型漂入 contracts、AI provider model 变成业务模型 |
| Authoritative source | 同一业务事实只有一个权威来源 | 多份镜像状态互相同步；AI proposal 被当作 Backend durable truth |
| Dependency direction | 依赖方向能解释业务所有权 | domain -> data、feature domain -> app implementation、跨 feature 任意 import、Route/adapter 承接 application policy |
| Context identity | 数据/权限/任务/规划归属有稳定 identity | request/result 无法判断属于哪个 account/family/session/actor/task/planning context |
| Lifecycle placement | create/start/stop/close 与资源 owner 对齐 | 页面销毁但后台 operation 仍无 owner；多个对象分别 cleanup |
| Package honesty | 目录/命名帮助找到 owner | `core` 实际聚合所有 feature；`Runtime`/`Manager` 成为 dumping ground |

Architecture PASS 只证明“放在哪里”基本合理，不代表 flow 或局部实现易追踪。

## Coordination Gate — Who owns the flow?

| Gate | PASS 需要证明什么 |
| --- | --- |
| Entry | 用户/外部/lifecycle/retry 的入口可定位 |
| Flow Owner | 有一个对象对该 intent 到 terminal 负责 |
| State Owner | mutable business fact 有唯一 writable owner |
| Lifecycle Owner | start/cancel/recover/close/late-result rejection 可定位 |
| Decision Owner | ignore/retry/recover/replace/complete 等关键决策有唯一定位点 |
| Effect Executor | network/db/coroutine/emit/platform/provider effect 的执行边界清楚 |
| Terminal | success/failure/cancel/recovery 的 terminal 明确 |
| Duplicate | duplicate rule 与 identity 明确 |
| Late result | stale operation 如何识别并拒绝明确 |
| Debug boundaries | 至少两个中间事实可快速二分问题 |
| Direction | 主要协作图近似单向；无 dependency knot / callback mesh |

### Coordination Smells

- `lateinit` controller 解决构造环；
- peer controller 互控；
- callback 参数只为绕过依赖方向；
- Flow Owner 只负责开始，不负责 terminal；
- 一个 StateHolder 被多个 controller 以宽 API 写入；
- operation identity 只存在调用栈，不随异步结果传播；
- retry/restart/reselect/dismiss 没有使旧结果失效；
- terminal 只停止 poll/job，却不更新用户可观察状态；
- data object 同时拥有 transport 和 presentation/application policy；
- Backend Route 同时拥有 protocol mapping、business decision 和 transaction policy；
- AI provider adapter 的 raw output 直接成为 authoritative Planner result；
- no-op callback 仍保留在核心调用图。

## Local Reasoning Gate — How does this owner work?

| Gate | PASS 需要证明什么 |
| --- | --- |
| Responsibility | 一句话可以解释对象为什么存在 |
| Concept model | 3-5 个核心概念足以解释主行为，或复杂度有必要证据 |
| Authoritative state | 哪些字段是真实状态、哪些可派生清楚 |
| Canonical state | 一个语义状态尽量只有一种标准表示 |
| Illegal state | 不要求读者记大量非法 boolean/nullable 组合 |
| Transition | 输入 -> Decision -> state/effect 可追踪 |
| Decision/effect | 业务判断和外部副作用有清晰边界 |
| Result model | 类型表达合法结果，不靠多个 nullable/boolean 猜组合 |
| Knowledge Surface | 安全修改所需事实数量可控 |
| Semantic Hop | 常见行为/故障不需跨太多语义边界 |
| Delegation | wrapper 真正吸收 decision/invariant，而不是只藏字段 |
| Dead concept | 无无效 API/callback/state 继续污染 mental model |

## Soft Traceability Signals

以下不是硬阈值，只用于解释人类认知成本：

- 核心 happy path 2-3 semantic hops：通常较好；
- >5：需要解释；
- >7：通常是高 trace cost；
- 为安全修改一个 owner 需要同时掌握 >7 个独立业务/协议事实：Knowledge Surface 偏高；
- 一个 semantic state 有多种 representation：优先 normalize；
- 2+ boolean 共同表达 lifecycle：审视是否应显式 phase/state；
- 3+ owner 可以写同一个业务状态域：优先审视 transition ownership。

# Local Reasoning Metrics

这些是 review/refactor 的证据语言，不是机械评分系统。

## Knowledge Surface

定义：安全修改某个 owner 前，工程师必须同时掌握的独立事实集合。

示例：

```text
SSE sequence rule
revision rule
snapshot semantics
delta semantics
decoder incremental contract
projection replacement semantics
terminal rule
recovery concurrency
late-result rule
```

如果必须同时掌握很多彼此独立的事实，说明 owner 的 knowledge surface 偏大。优先让概念有更清晰 owner 或边界，而不是只拆方法。

## Semantic Hop

定义：为了理解“下一步为什么发生”，读者必须切换到新的对象/概念/invariant 的次数。

不等于普通函数调用数量。简单纯 helper 可以不算；进入一个新的 state model、protocol decision、lifecycle rule 通常算一次。

软参考：

- 2-3：核心行为通常容易追踪；
- >5：需要解释；
- >7：通常是高 trace cost。

## Canonical State

一个 semantic fact 尽量只有一个标准表示。

坏例：

```text
Complete = cursor == null
或 cursor 在最后 block 且 offset == length
```

好例：

```text
cursor == null <=> fully revealed
```

## Illegal State Surface

检查：

- 多个 boolean/nullable 是否共同定义 phase；
- 哪些组合非法；
- 是否只有注释/调用顺序保证；
- 类型是否能减少需要人工记忆的组合。

不要把每个 boolean 都机械改 enum；只有稳定 phase/invariant 明确时建模。

## Write-entry Count

同一 business fact 可以从多少位置被修改。

目标不是绝对 1 个方法，而是：

- writable owner 唯一；
- transition 入口少且语义清楚；
- caller 不需要维护 owner 内部 invariant。

## Decision Region Count

一个关键行为需要跨几个责任区域共同形成。

例如“是否 recovery”如果由：

```text
outer orchestrator branch
+ ordering gate result
+ runtime flag
+ repository failure
```

共同决定，排障成本高。优先让 decision owner 可定位。

## Result-state Combination Count

类似：

```kotlin
data class Result(
  val updates: List<...>,
  val recoverReason: String?,
  val hasError: Boolean,
  val failure: Error?,
)
```

读者必须自己推断字段合法组合。考虑 sealed/named effect/transition，但只在它真正减少组合知识时采用。

## Debug Boundary

能通过一个可观察事实把责任区域一分为二的位置。

例如：

```text
Authoritative conversation state correct?
  NO -> upstream reply pipeline
  YES -> typewriter/rendering
```

好的局部重构应增加或强化这种 boundary。

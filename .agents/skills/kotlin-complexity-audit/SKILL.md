---
name: kotlin-complexity-audit
description: "Run deterministic Kotlin/Compose static complexity scanning for Orbit and produce smoke signals only. Use to find files/classes/functions worth semantic inspection. It does not decide refactor targets, priorities, ownership quality, or human traceability."
---

# Kotlin Complexity Audit

## Mission

静态扫描只回答：

> 哪些位置出现了值得进一步阅读的 size/control-flow/parameter/Compose signals？

它不回答：

- 这个 class 是否应该拆；
- 这个复杂度是否合理；
- 哪个热点应重构；
- architecture/coordination 是否有问题；
- 人是否容易排障。

## Boundaries

- 输出 `complexity-audit.md` 和 `complexity-audit.json`。
- 不生成 P1/P2/P3 semantic priority。
- 不自动调用 review/refactor workflow。
- 不修改 production code。
- 不把 LargeClass / TooManyFunctions / LongMethod 当成重构结论。

## Workflow

1. 冻结 source scope 和 test inclusion。
2. 运行：

```bash
python3 .agents/skills/kotlin-complexity-audit/scripts/audit_kotlin_complexity.py \
  --root <kotlin-source-root> \
  --output-dir <output-dir>
```

在 monorepo root 调用时可使用完整项目路径。

3. 阅读 Markdown/JSON；只把 high/medium static signals 作为 Human Traceability Review 或 Local Reasoning Refactor 的 source-selection 辅助。
4. 如果用户要求“哪些最值得先看”，结合业务 Flow/故障频率/owner 重要性给建议，不得仅按 audit score 排重构优先级。

## Handoff

- 模块/feature/flow 级问题 -> `orbit-human-traceability-review`；
- 已明确单 owner 且只需行为保持重构 -> `kotlin-local-reasoning-refactor`。

报告时明确写：

> Static signal is not a semantic refactor verdict.

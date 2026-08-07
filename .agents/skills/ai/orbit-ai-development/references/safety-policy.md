# 安全策略与不可信 Context

新增 prompt、RAG/网页/工具内容、画像推断、schema 修复或护栏时读取本文。

## 将文本视为数据

用户请求、网页、检索片段、工具响应、附件和模型生成文本均不可信。用边界和标签把它们作为数据；它们不能改变系统策略、工具权限、预算或审批要求。进入 `PlanningContext` 前裁剪/限制内容，并为事实保留来源 ID 与检索时间。

规划器只能建议问题和提案，不能把“忽略审批并写入日历”之类嵌入文本解释为权限。

## 必需确定性检查

| 阶段 | 检查 | 失败时 |
| --- | --- | --- |
| 调用前 | tenant 范围脱敏、长度/数量/token/时间预算、允许来源类型 | 类型化拒绝或收集阶段追问 |
| 调用后 | 结构化 parse/schema、选项数、来源 ID、硬预算、动作/风险关系 | 拒绝或一次有边界修复 |
| 后端接收 | 鉴权、任务版本/状态、来源新鲜度、动作 schema/策略、审批 | 后端决定迁移，AI 不可覆盖 |

Existing `PlanningPolicy` is the minimum core check. Extend it with deterministic facts only; keep role/tenant authorization and task state in backend policy.

## Safe degradation

When data is incomplete, stale, injected, or structurally invalid, prefer one of:

- a specific follow-up question;
- a source-backed read-only recommendation without requested actions;
- a typed failure/retry scheduling decision.

Never fabricate availability, price, source URL, user preference, action result, or approval. Do not put raw prompts, tokens, credentials, or personal text into logs.

# Orbit RoadMap v1.0

## Direction

Orbit 的路线围绕一个简单模型推进：用户提出一件事，系统整理要求，发现真实机会，生成可选择方案。

## M0 Foundation

- Auth、tenant/user scope、KResponse、runtime config。
- Task 创建、消息追加、要求读取、方案读取、选择方案的基础链路。
- App 使用真实 Backend API，不从本地文案推断状态。

## M1 First Planning Loop

- Backend understanding 从消息中整理 intent 与 requirements。
- Backend 根据 readiness policy 自动决定是否 planning。
- Opportunity provider 输出带来源和有效期的 facts snapshot。
- AI planner 只输出 PlanDraft 和 Opportunity IDs。
- Kotlin validator 负责校验 requirements、source refs、revision 与 validUntil。
- App 展示事情、要求、方案，并允许用户继续发消息或选择方案。

## M2 Source Expansion

- 接入更多只读来源。
- 每个来源通过 typed projector 输出 Opportunity facts。
- 所有外部文本先经 source owner 校验，再进入 planning context。

## M3 Execution Preparation

- 方案选择后进入执行准备。
- 日历、提醒、订票等副作用必须在 Backend 审批和权限边界内发生。
- AI 只能提出 requested action proposal，不直接执行。

## Verification Gates

- Contracts：wire serialization 与 producer/consumer 编译。
- Backend：permission、revision、validUntil、source provenance、transaction boundary。
- AI：schema、typed payload、guardrail result。
- App：消息、要求、方案选择的主路径 lint 与 unit tests。

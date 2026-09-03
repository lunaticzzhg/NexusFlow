# Orbit System Blueprint v1.0

## Core Model

```text
Task
├── intent
├── revision
├── Requirements
└── Plans

Opportunity
└── facts + source provenance
```

Task、Requirement、Opportunity、Plan 是当前核心领域概念。其它对象如果存在，只能是内部实现细节或投影，不能成为公开产品/API/domain 事实。

## Ownership

| Fact | Writable owner |
| --- | --- |
| Auth, tenant, user scope | Backend identity |
| Task intent and revision | Backend Task service |
| Task messages | Backend Task service |
| Requirements | Backend Task service |
| Opportunity snapshots | Backend planning source owner |
| Plans | Backend planning service after validation |
| Selected plan | Backend planning service |
| Plan narrative | AI explanation adapter, after validation only |

## Planning Flow

```text
User message
-> Backend append message
-> AI understanding proposal
-> Backend applies intent / requirement changes
-> Backend readiness policy
-> Opportunity discovery
-> AI PlanDraft proposal
-> Backend validation
-> Persist plans for current revision
-> User selects plan
```

Backend is the only authority for persistence, permissions, revision freshness, provenance, and side effects.

## AI Boundary

- Understanding receives bounded Task context and returns typed requirement changes.
- Planner receives requirements plus Opportunity snapshots and returns PlanDraft refs.
- Explanation receives validated Plans and facts and returns narrative only.
- AI output is never accepted as durable fact without deterministic validation.

## App Information Architecture

App surfaces:

- Home: ongoing things.
- Detail: messages, requirements, plans.
- Composer: send another message.
- Plan cards: select one current valid plan.

The user does not need to trigger planning manually or understand backend execution mechanics.

## Persistence Baseline

Task-related tables:

- `tasks`
- `task_messages`
- `task_requirements`
- `opportunity_snapshots`
- `plans`
- `plan_opportunities`
- `plan_requirement_evaluations`
- `task_context_selections`
- `task_audit_events`

Auth and identity migrations remain separate. Task schema changes are breaking and have no compatibility bridge.

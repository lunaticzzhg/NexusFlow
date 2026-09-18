# Orbit System Blueprint v1.0

## Core Model

```text
Conversation
└── Messages
    └── optional Task
        ├── goal
        ├── revision
        ├── Requirements
        ├── Opportunities
        └── Plans

Opportunity
└── facts + source provenance
```

Conversation 是普通交互容器，可以没有 Task。Task 是 Planning 激活后的事项容器，并继续拥有 goal、requirements、opportunities、plans 与 revision。当前目标状态下，一个 Conversation 最多关联一个当前 Task；不要提前设计同一段对话管理多个独立 Task。

## Ownership

| Fact | Writable owner |
| --- | --- |
| Auth, tenant, user scope | Backend identity |
| Conversation messages | Backend Conversation service |
| Conversation to Task association | Backend planning activation owner |
| Task goal and revision | Backend Task service |
| Requirements | Backend Task service |
| Read-only external research execution | Backend research/tool owner |
| Research evidence projection and provenance | Backend source owner |
| Opportunity snapshots | Backend planning source owner |
| Plans | Backend planning service after validation |
| Selected plan | Backend planning service |
| Conversation direct answer | AI answer capability, persisted by Backend |
| Grounded answer narrative | AI answer capability over Backend-provided evidence |
| Plan narrative | AI explanation adapter, after validation only |

Backend is the only authority for persistence, permissions, revision freshness, provenance, approval, credentials, and side effects.

## Conversation Flow

```text
User message
-> Backend append Conversation message
-> AI understanding proposal
-> Backend validates turn intent and context selection
-> Conversation branch
   -> Direct Answer
   -> or bounded read-only external fact research
   -> Grounded Answer from projected evidence
-> Persist assistant message
```

Conversation supports natural chat, direct questions, and external fact research. Research uses bounded read-only capabilities selected from definitions supplied by Backend for the current turn; it is not a generic agent runtime, arbitrary tool loop, or side-effect executor.

## Planning Flow

```text
User message with planning intent
-> Backend append Conversation message
-> AI understanding proposal
-> Backend creates or continues linked Task
-> Backend applies goal / requirement changes
-> Planning research proposes bounded read-only research calls
-> Backend validates and executes allowed research
-> Opportunity projection
-> AI PlanDraft proposal
-> Backend validation
-> Persist plans for current revision
-> User selects plan
```

## AI Boundary

- Understanding receives bounded Conversation / active Planning context and returns turn intent plus typed planning deltas.
- Conversation answer can answer directly or request bounded read-only research when external facts are required.
- Planning research can propose what read-only facts are needed for plan generation.
- Planner receives requirements plus Opportunity snapshots and returns PlanDraft refs.
- Explanation receives validated Plans and facts and returns narrative only.
- AI output is never accepted as durable fact without deterministic validation.

## App Information Architecture

App surfaces:

- Home: ongoing planning things and entry to conversations.
- Conversation detail: messages and composer.
- Planning section: requirements and plans when a Task exists.
- Composer: send another message.
- Plan cards: select one current valid plan.

The user does not need to choose a chat/planning mode, trigger planning manually, or understand backend execution mechanics.

## Persistence Baseline

Target persistence concepts:

- `conversations`
- `conversation_messages`
- `tasks`
- `task_requirements`
- `opportunity_snapshots`
- `plans`
- `plan_opportunities`
- `plan_requirement_evaluations`
- `task_context_selections`
- `task_audit_events`

Migration boundary: existing Task-first message storage may remain during compatibility work, but product and architecture target Conversation as the message owner. Auth and identity migrations remain separate. Task schema changes are breaking unless a dedicated compatibility bridge is designed and verified.

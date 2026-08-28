# Contracts Reference

This reference covers cross-boundary wire-contract workflow only. It is not a Backend architecture authority, an App modeling guide, or an AI runtime design.

## Scope

`:contracts` contains stable wire schema shared by actual producers and consumers in this repository. A model belongs there only when it is part of a real cross-boundary protocol and both sides need the same serialized shape.

Keep module-internal models local:

- Backend domain, persistence, transaction, and infrastructure models remain Backend-local.
- App presentation, UI state, domain projection, and cache models remain App-local.
- AI provider DTOs, raw model output, prompt/internal reasoning models, and guardrail intermediate models remain AI-local.

Similar fields are not enough reason to move a type into `:contracts`.

## Contract Decision

For every non-trivial feature card, answer:

```markdown
## Cross-boundary Contract
- Wire contract changed?:
- Producer:
- Consumer(s):
- Compatibility requirement:
- Module-internal models that must NOT enter contracts:
```

`NO CHANGE` is valid when the requirement is App-only, Backend-internal, AI-internal, or can use an existing wire shape without changing semantics.

## Evolution Rules

- Identify the authoritative producer and every current consumer before editing schema.
- Prefer backward-compatible optional evolution when existing clients must continue working.
- Use a separate endpoint, message, or versioned shape when optional evolution would make semantics ambiguous.
- Do not let Backend domain invariants, App rendering convenience, or provider-specific AI output leak into shared schema.
- Keep idempotency, permission, approval, persistence, and side-effect authority in Backend even when AI proposes a `RequestedAction`.
- Treat external/plugin/model text as untrusted input; it does not define a wire contract by itself.

## Verification

Contract changes require:

- serialization tests for the changed shared models;
- affected producer mapping verification;
- affected consumer mapping verification;
- compatibility evidence for omitted, optional, defaulted, renamed, or removed fields;
- cross-stack flow proof when the contract participates in a user-observable behavior.

Do not run unrelated App, Backend, or AI suites for ceremony. Use the touched-area checks in `verification.md`.

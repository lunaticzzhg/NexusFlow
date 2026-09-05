# NexusFlow Contracts

This directory is the single entry point for cross-module protocol models. The modules are colocated here, but each boundary keeps its own contract types.

## App to Backend

Module: `:contracts:app-backend`

Package root: `com.nexusflow.contracts.appbackend`

### Common

- `KResponse<T>`: standard JSON response envelope.
- `ApiVersion`: externally consumable HTTP API version prefix.

### Auth

- `GoogleExchangeRequest`
- `DevLoginRequest`
- `RefreshSessionRequest`
- `LogoutRequest`
- `AuthSessionResponse`

Backend owns identity, session rotation, token validation, and revocation. App sends credentials or refresh tokens through the protocol and receives session material only as a response.

### Task

- `CreateTaskRequest`
- `SendTaskMessageRequest`
- `UpdateRequirementRequest`
- `TaskSummaryResponse`
- `TaskDetailResponse`
- `TaskResponse`
- `TaskMessageResponse`
- `RequirementResponse`
- `RequirementValueResponse`
- `PlanningStatusResponse`

`TaskDetailResponse` is the current App-facing authoritative projection. It preserves the existing wire shape for this thin refactor slice: `task`, `requirements`, `messages`, `plans`, and `planning`.

### Plan

- `PlanResponse`
- `PlanTimelineItemResponse`
- `PlanEstimatedCostResponse`
- `RequirementEvaluationResponse`
- `PlanSourceRefResponse`
- `PlanDirection`

Backend maps validated domain Plans into App-facing plan responses. App does not receive Backend domain entities or AI provider output.

## Backend to AI

Module: `:contracts:backend-ai`

Package root: `com.nexusflow.contracts.backendai`

### Common

- `StructuredModelCapability`
- `ModelContextBlockPayload`
- `ModelContextTrustPayload`
- `SelectableContextDefinitionPayload`
- `StructuredModelRequestDiagnostics`
- `StructuredModelUsage`
- `AiCapabilityException`
- `CapabilityUnavailableException`
- `CapabilityUnauthorizedException`
- `CapabilityRateLimitedException`
- `CapabilityTimeoutException`
- `CapabilityRefusedException`
- `InvalidCapabilityResultException`

These are sanitized capability/context/audit contracts. Raw prompts, raw provider payloads, provider DTOs, credentials, and unfiltered external content stay out of contracts.

### Understanding

- `UserMessageUnderstanding`
- `UnderstandMessageRequest`
- `UnderstandMessageResult`
- `CurrentRequirement`
- `RequirementChangeProposal`
- `ClarificationProposal`
- `ContextSelectionProposal`
- `UnderstandingMetadata`

Backend sends the current Task state, committed user message, optional context blocks, and available context definitions. AI returns proposals only: intent patch, requirement changes, clarification, and context selection. Backend validates and applies any state change.

### Planning

- `PlanComposer`
- `CreatePlansRequest`
- `PlanningRequirement`
- `CandidateOpportunity`
- `CreatePlansResult`
- `PlanProposal`
- `PlanDirection`

Backend supplies requirements and verified opportunities. AI may return plan proposals that reference supplied opportunity IDs. Backend still validates, calculates, persists, and owns the authoritative Plan state.

### Explanation

- `PlanExplainer`
- `ExplainPlansRequest`
- `PlanForExplanation`
- `PlanExplanationFact`
- `ExplainPlansResult`
- `PlanNarrative`
- `PlanNarrativePoint`

Backend supplies already-validated Plans and allowed facts. AI may explain and compare those facts, but must not add new prices, times, places, availability, sources, or executable actions.

## Dependency Rules

- App depends on `:contracts:app-backend`.
- Backend depends on `:contracts:app-backend` and `:contracts:backend-ai`.
- AI depends on `:contracts:backend-ai`.
- App must not depend on Backend-AI contracts.
- AI must not depend on App-Backend contracts.
- Backend domain must not depend on contracts.
- Contracts must not import App, Backend, or AI implementation packages.
- Provider-specific payloads, schema builders, prompts, adapters, parsers, retry behavior, and transport exceptions remain in `:ai`.

## Change Checklist

1. Identify the boundary: App to Backend, or Backend to AI.
2. Add or edit contract types only in that boundary module.
3. Keep Backend domain, App presentation/domain models, AI provider payloads, and raw external data module-local.
4. Map Domain to App Contract, Domain to AI Contract, and AI Proposal to validated Domain update at Backend boundaries.
5. Update this README when a new protocol capability is introduced.
6. Add or update serialization/snapshot tests for changed contract shapes.

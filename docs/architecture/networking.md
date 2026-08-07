# Networking

The single source of truth is [网络契约](../../.agents/skills/app/orbit-feature-development/references/network-contract.md). API, DTO, HTTP errors, OIDC headers, SSE and asynchronous task creation must follow it.

Orbit uses one shared `HttpClient`, Problem JSON error mapping and typed feature DTOs. `202 Accepted` means a task was accepted, not completed; REST task detail is the snapshot authority and SSE is incremental. `Idempotency-Key` is required for task creation, approval decisions and external actions. Do not add a second client, a feature-specific envelope, a global request façade or a single-consumer Koin binding.

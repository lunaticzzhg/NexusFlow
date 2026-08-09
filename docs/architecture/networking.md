# Networking

The single source of truth is [网络契约](../../.agents/skills/app/orbit-feature-development/references/network-contract.md). API, DTO, HTTP errors, OIDC headers, SSE and asynchronous task creation must follow it.

Orbit uses one shared `HttpClient`, Ktorfit and typed feature DTOs. Every JSON API body uses `KResponse<T>(code, message, data)`; `code == 200` is the sole success condition and mirrors the HTTP status. The shared network boundary maps same-origin non-2xx, transport and conversion failures to `AppException`. `202 Accepted` means a task was accepted, not completed; REST task detail is the snapshot authority and SSE is incremental. `Idempotency-Key` is required for task creation, approval decisions and external actions. Do not add a second client, a feature-specific envelope, a global request façade or a single-consumer Koin binding.

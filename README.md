# NexusFlow / Orbit

An approval-first, personalized leisure-planning Agent. It discovers opportunities across plug-ins (sports, films, local events), builds feasible plans, and only performs external actions after user approval.

## Repository layout

- `app/` — Kotlin Multiplatform client.
- `backend/` — Ktor API, durable task workflow, approval and plug-in execution.
- `ai/` — Kotlin planner, prompt versions, guardrails, personalization and evaluation.
- `contracts/` — versioned schemas shared across all boundaries.

## Local development (foundation)

The local API uses a deliberately local-only identity adapter. Start it explicitly in the local runtime profile:

```bash
ORBIT_RUNTIME_PROFILE=local ./gradlew :backend:run
```

The health endpoint is available at `http://127.0.0.1:8080/health/live`.

The first command endpoint is `POST /v1/tasks`. It requires an `Idempotency-Key` header and returns `202`; its in-memory adapter is only for the current foundation milestone. The domain port already requires a single atomic task + Outbox write, and [V001__task_core.sql](backend/src/main/resources/db/migration/V001__task_core.sql) is the PostgreSQL target schema for the next milestone.

Start local infrastructure when Docker is available:

```bash
docker compose up -d
```

See [infra/README.md](infra/README.md) for Keycloak, Redpanda and OpenTelemetry local endpoints, and the [v0.1 App technical plan](docs/v0.1/app-module-technical-plan.md) for the implementation sequence.

The KMP module structure is Android-first and can be opened from the repository root in Android Studio.

# NexusFlow / Orbit

An approval-first, personalized leisure-planning Agent. It discovers opportunities across plug-ins (sports, films, local events), builds feasible plans, and only performs external actions after user approval.

## Repository layout

- `app/` — Kotlin Multiplatform client.
- `backend/` — Ktor backend foundation: core HTTP/persistence capabilities and authentication.
- `ai/` — Reserved for a future planning module; no AI runtime is implemented yet.
- `contracts/` — versioned schemas shared across all boundaries.

## Local development (foundation)

The local API uses a deliberately local-only identity adapter. Start it explicitly in the local runtime profile:

```bash
ORBIT_RUNTIME_PROFILE=local ./gradlew :backend:run
```

The health endpoint is available at `http://127.0.0.1:8080/health/live`.

The current backend exposes core health and authentication endpoints only. Task workflow, approval, Outbox/Worker and AI planning are not implemented; after a product module is selected, it will be developed as an independent, persistently backed feature slice rather than enabled from a placeholder API or schema.

Start local infrastructure when Docker is available:

```bash
docker compose up -d
```

See [infra/README.md](infra/README.md) for PostgreSQL, Redpanda and OpenTelemetry local endpoints, and the [v0.1 App technical plan](docs/v0.1/app-module-technical-plan.md) for the implementation sequence.

The KMP module structure is Android-first and can be opened from the repository root in Android Studio.

# Local infrastructure

Start the default local dependency, PostgreSQL, from the repository root:

```bash
cp .env.example .env
docker compose up -d
docker compose ps
```

PostgreSQL is exposed on `5432` and is the only dependency started by default. Its internal Compose address is `postgres:5432`.

Redis, Redpanda, and the OpenTelemetry Collector are available for the feature slices that actually use them, but are not started by default:

```bash
docker compose --profile cache up -d
docker compose --profile messaging up -d
docker compose --profile observability up -d
```

The optional profiles expose Redis on `6379`, Redpanda's Kafka listener on `19092`, and OTLP gRPC/HTTP on `4317`/`4318`. Their internal Compose addresses are `redis:6379`, `redpanda:9092`, and `otel-collector:4317`.

`.env.example` defaults are deliberately for services launched from the host via Gradle, so they use `localhost`. The matching `*_INTERNAL_*` values are for a future backend container on the Compose network. Authentication and backend runtime configuration are governed by [the NexusFlow Backend architecture guide](../docs/architecture/nexusflow-backend-architecture.md): database and NexusFlow JWT secrets are injected by the runtime, while Google audience and Android client IDs are non-secret configuration.

For local backend development, run the checked-in launcher from the repository root:

```bash
scripts/run_backend_local.sh
```

It starts and waits for PostgreSQL, loads the ignored root `.env`, then keeps the backend in the foreground. Request and startup logs remain visible in the terminal and are also written to the ignored `logs/backend-local.log`; inspect an existing run with `tail -f logs/backend-local.log`. The launcher fails rather than starting a second service if port `8080` is already in use.

If the development Mac needs an HTTP(S) proxy to reach Google, export an unauthenticated local `HTTPS_PROXY` (or `https_proxy`) such as `http://127.0.0.1:7890` before launching. The launcher translates it to JVM proxy settings only for that local process, so Google JWKS verification follows the same route as other local tooling. Proxy URLs with credentials are intentionally rejected to avoid exposing them in JVM process options. Use `scripts/run_backend_local.sh --no-proxy` on a network with direct Google access.

This delivery does not run Keycloak or another browser OIDC service. Android obtains a Google ID token from Credential Manager, and the backend verifies it before issuing a NexusFlow business session. Google provider credentials, NexusFlow signing keys and database passwords never belong in Docker images or repository configuration.

The `messaging` profile provides Redpanda as the Kafka-compatible local broker. Its Admin API is available at `http://localhost:19644`; its Pandaproxy listener is on `18082` for local inspection.

The `observability` profile provides an OpenTelemetry Collector that accepts OTLP and writes a compact debug export to its container logs. Its health endpoint is available at `http://localhost:13133/`. The collector image does not provide a portable in-container HTTP client, so Compose intentionally has no misleading binary-version healthcheck; use that endpoint in an environment-level readiness probe. Replace the `debug` exporter with an OTLP exporter to a tracing backend in shared or production environments.

All stateful services use named Docker volumes. To remove only this stack's local data deliberately, run `docker compose down -v` from the repository root.

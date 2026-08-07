# Local infrastructure

Start the local dependencies from the repository root:

```bash
cp .env.example .env
docker compose up -d
docker compose ps
```

The development stack exposes PostgreSQL on `5432`, Redis on `6379`, Redpanda's Kafka listener on `19092`, Keycloak on `8081`, and OTLP gRPC/HTTP on `4317`/`4318`. The internal Compose addresses are `postgres:5432`, `redis:6379`, `redpanda:9092`, `keycloak:8080`, and `otel-collector:4317`.

`.env.example` defaults are deliberately for services launched from the host via Gradle, so they use `localhost`. The matching `*_INTERNAL_*` values are for a future backend container on the Compose network. Keep `OIDC_ISSUER_URI` on the public `localhost:8081` value: it must match Keycloak's token issuer and the URI used by the KMP client.

Keycloak imports the `orbit-local` realm at first startup. Its `orbit-kmp` client is public and uses authorization code + PKCE; its access tokens include `orbit-api` in `aud` for backend validation. The bundled administrator values are deliberately development-only and configured in `.env`; never deploy them.

Redpanda is used as the Kafka-compatible local broker. Its Admin API is available at `http://localhost:19644`; its Pandaproxy listener is on `18082` for local inspection.

The OpenTelemetry Collector accepts OTLP and writes a compact debug export to its container logs. Its health endpoint is available at `http://localhost:13133/`. The collector image does not provide a portable in-container HTTP client, so Compose intentionally has no misleading binary-version healthcheck; use that endpoint in an environment-level readiness probe. Replace the `debug` exporter with an OTLP exporter to a tracing backend in shared or production environments.

All stateful services use named Docker volumes. To remove only this stack's local data deliberately, run `docker compose down -v` from the repository root.

# Backend composition convention

## Directory ownership

The backend is organized by ownership, then by feature layer:

```text
backend/
├── Application.kt                         # Three-step composition root
├── bootstrap/                             # Startup profiles and route assembly only
├── core/                                  # Cross-feature technical capabilities
│   ├── config/
│   ├── health/
│   ├── http/
│   ├── identity/
│   └── persistence/
└── feature/
    ├── auth/
    │   ├── api/                           # HTTP request/response adaptation
    │   ├── application/                   # Use cases and transaction policy
    │   ├── domain/                        # Feature facts and ports
    │   └── infrastructure/                # JWT, Google and JDBC adapters
```

`core` has no product-feature semantics. A capability belongs there only when it is application-wide and reusable without knowing a feature. Feature code must not import another feature's `api` or `infrastructure` package.

Within a feature, the allowed dependency direction is `api → application → domain`; `infrastructure` implements domain/application ports and is wired only from the feature's dependency function. A future `runtime` package is permitted only for a real non-HTTP entry point, such as a worker; it invokes the feature application layer and does not become a shared service layer.

## Composition

Ktor's built-in DI container owns application-scoped dependencies. Each feature owns exactly one registration function at its feature root:

- `core/http/`: Kotlinx JSON, CallId, request logging and framework-level error mapping.
- `core/health/`: liveness and PostgreSQL-backed readiness probes.
- `core/persistence/DatabaseDependencies.kt`: runtime configuration, connection pool and Flyway.
- `feature/auth/AuthDependencies.kt`: Google verifier, identity-session persistence, business-token service, authentication service and actor resolver.

`bootstrap/BackendBootstrap.kt` selects the runtime profile, invokes those functions in dependency order, runs Flyway before route installation, and resolves the small immutable `BackendRuntime` passed to routes. It owns no feature implementation. A new feature adds its own root registration function and one call in `BackendBootstrap`; `Application.module` remains unchanged.

```kotlin
fun Application.module(profile: BackendRuntimeProfile = BackendRuntimeProfile.fromEnvironment()) {
    configureHttpPlatform()
    val runtime = bootstrapBackend(profile)
    configureCoreRoutes(runtime.readinessProbe)
    configureFeatureRoutes(runtime)
}
```

`contracts` is the single wire-contract module shared by the App and backend. HTTP DTOs use `kotlinx.serialization`; neither feature owns a duplicate transport DTO or an independent JSON policy. This rule applies only to network contracts, not JDBC records or third-party SDK objects.

The HTTP platform generates or accepts a valid `X-Request-Id` through Ktor `CallId`, returns that ID on every response, and uses it for structured request logs. All JSON API responses use `KResponse`; `StatusPages` owns malformed-request and unexpected-error responses, while feature routes retain ownership of their explicit business-error mappings. The platform never logs authorization headers, tokens, request bodies, or raw exception messages.

## Dependency and lifecycle rules

- Providers are application-scoped and lazily initialized once per Ktor `Application`; request DTOs, `ApplicationCall`, `ActorContext`, transactions and JDBC connections are never application dependencies.
- Business classes use constructor injection only. Routes resolve required services during installation; route handlers, domain classes and application services must not use Ktor DI as a Service Locator.
- Any long-lived resource implementing `AutoCloseable`, including `HikariDataSource`, is registered with Ktor DI so it closes during application shutdown. Ktor closes registered resources in reverse declaration order.
- Failure to parse configuration, initialize a resource or migrate the database prevents the production process from accepting requests.
- Production uses `BearerActorResolver`; `TestActorResolver` is registered only for the explicit `Test` profile. The default profile is production and fails closed when required configuration is absent.
- `GET /health/live` is a core route and checks process liveness only. `GET /health/ready` is a core route and checks PostgreSQL availability; it returns `503` without database details when the dependency is unavailable.
- Production currently installs only core health and authentication routes. A future feature is registered only when its authoritative persistence, authorization and recovery semantics required by that feature are implemented in the same delivery slice; do not register a placeholder or in-memory production route.

## Test replacement

Tests register a fake dependency before calling `Application.module`. Ktor's test DI conflict policy keeps the test binding, allowing a test to replace a repository or external verifier without changing production composition.

```kotlin
application {
    dependencies {
        provide<ExternalIdentityVerifier> { verifier }
    }
    module(profile = BackendRuntimeProfile.Test)
}
```

## Runtime dependencies

`docker compose up -d` starts PostgreSQL only. Redis, Redpanda and the OpenTelemetry Collector are deliberately optional Compose profiles: `cache`, `messaging` and `observability`. A profile is local infrastructure availability, not evidence that the backend has integrated its client, producer, consumer or telemetry SDK. The corresponding runtime code and acceptance evidence must land in the same feature slice before that capability becomes a production dependency.

## Non-goals

Do not introduce a second backend DI container, runtime string-key service registry, classpath scanning, feature-specific global singleton, or a generic feature registry. If a capability needs request-local state, pass it explicitly from `ApplicationCall` or derive it through the authenticated request boundary.

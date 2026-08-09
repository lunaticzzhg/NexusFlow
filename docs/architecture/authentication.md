# Authentication integration convention

## Decision and scope

v0.1 uses **direct Google identity validation and a NexusFlow-owned business session**. Android starts Google sign-in with Credential Manager's system account-selection sheet; iOS starts the native Google Sign-In SDK from its UIKit host. Both send only the resulting Google ID token to the backend and receive NexusFlow access and refresh tokens in return.

```text
Android Credential Manager / iOS Google Sign-In SDK
  -> Google ID token
  -> POST /v1/auth/google/exchange
  -> verify Google identity
  -> NexusFlow access token + refresh token
```

Apple sign-in is explicitly deferred. There is no Apple client UI, verifier, configuration, browser fallback, or Apple secret in this delivery. Keycloak, browser OIDC + PKCE, WebView login, and provider refresh-token storage are also out of scope. They must not be introduced as a parallel runtime path.

The direct-provider decision is intentionally narrow: it supports the confirmed Google-first requirement and preserves the native Android sheet. Re-evaluate an identity broker only when a real requirement needs multiple active identity providers, enterprise SSO, password login, managed MFA, or account-linking administration.

## Infrastructure-first delivery order

Authentication is not a client-only feature. Before the Android login UI is connected, build the minimum durable infrastructure that owns identities and sessions:

1. Keep PostgreSQL as the authority and establish the identity/session schema as the initial Flyway migration (`V001`). Future feature schemas extend this ordered history additively; they do not create a second initialization mechanism.
2. Add configuration validation and secret injection for database access and NexusFlow signing keys. Production startup fails when a required production secret or Google audience is absent; it never falls back to a development identity.
3. Add backend Google verification, user/tenant/identity/session persistence, access-token signing, refresh rotation, logout, and Bearer-to-`ActorContext` resolution.
4. Add the App session store, recovery, refresh, logout, context invalidation, and only then Android Credential Manager exchange UI.

Mocks are permitted in focused tests only. They are not an alternative login or session implementation for an executable build.

## Ownership and boundaries

`feature/auth` owns the App authentication flow. `AuthSessionController` is the only reader and writer of `AuthSessionStore`, and the only publisher of `AuthState`. UI, repositories, HTTP transport, and platform SDK bridges must not persist a session, clear one, or navigate based on authentication themselves.

The request boundary is explicit: `AuthSessionController` depends on the domain `AuthRepository`; `DefaultAuthRepository` maps the Google exchange/refresh/logout HTTP contracts and failures to domain models; feature-local `AuthApi` owns endpoint paths and DTOs. It is constructed only for `DefaultAuthRepository`, not exposed as a general Koin binding. The current shared `HttpClient` provides JSON transport for these three auth endpoints only; authentication does not create a second client.

`AuthGate` is the root-level consumer: `Restoring` shows loading, `Unauthenticated` shows Google login, `Authenticated` renders the app shell, and `Unavailable` offers retry. Tokens, authorization codes, Google ID tokens, emails and provider profile fields never enter UI state, logs, analytics attributes, or navigation arguments.

Each platform bridge is an atomic adapter only: it opens the native Google account UI, returns a structured Google credential, cancellation or failure, and does not store tokens or navigate. iOS keeps UIKit presentation, Google SDK callbacks and URL handling in the Swift host; its Kotlin bridge owns only the active request continuation. The shared flow owns all session decisions.

## Identity and session model

The backend accepts no user, tenant, role or provider subject supplied as an asserted identity by the client. It verifies the Google ID token signature and required claims before resolving an external identity:

- Google verifier checks the token signature against Google JWKS plus `iss`, allowed `aud`, and `exp`; `sub` is the provider identity key.
- The stable external-identity key is `(provider, provider_subject)`. It is never an email address.
- The first verified Google identity creates its NexusFlow user, personal tenant, membership, external identity and session in one short transaction. A repeat login finds the same identity and user.
- This delivery writes only `GOOGLE` provider values. The generic identity key is retained so a future Apple slice can be additive without treating email as an account-linking key.

The initial Flyway identity/session schema owns `users`, `tenants`, `tenant_memberships`, `external_identities` and `auth_sessions`. `auth_sessions` stores only a hash of each refresh token, its expiry, revocation/rotation state and session-family relationship; it never stores the raw refresh token or Google credential.

NexusFlow issues its own tokens after verification:

- Access token: short-lived signed JWT for business APIs, carrying at least subject, tenant, session ID, issuer, audience, expiry and key ID.
- Refresh token: long-lived random opaque secret used only at the refresh endpoint. Each refresh atomically invalidates the old value and issues a new pair. Reuse of an invalidated refresh token revokes its session family and requires new Google login.

`POST /v1/auth/logout` revokes the current session ID and the App clears local session storage. Business routes construct `ActorContext` only from a validated NexusFlow Bearer access token. `TestActorResolver` is registered only by the explicit backend test profile and is never a production fallback.

## HTTP contract

The initial API surface is deliberately small:

```text
POST /v1/auth/google/exchange
POST /v1/auth/refresh
POST /v1/auth/logout
```

The exchange body contains the Google ID token only. The backend derives user, tenant and provider subject itself. A successful exchange and successful refresh return the same session payload: short-lived access token, refresh token, expirations, internal user ID and tenant ID. Exact request/response DTOs, Problem JSON mappings, validation rules, and negative-path tests are the executable backend-contract work for this slice.

Those HTTP DTOs live once in the shared `contracts` KMP module and use `kotlinx.serialization`; the App and backend must not maintain parallel auth request or response models. The backend HTTP platform owns Kotlinx JSON configuration, request IDs, framework error responses and readiness routes. Authentication retains ownership only of auth-specific validation and business-error mappings.

Business API clients do not exist in the current App implementation, so it does not yet inject `Authorization: Bearer` on general requests or perform a shared 401 refresh/retry. When the first business API is introduced, its shared HTTP boundary must add the access token, attempt one serialized refresh, then retry the original request once. Only that replayed request's 401 may invalidate a session, and only when the stored access token still matches the token used by that replay. A late response for an old session cannot clear a newer login.

## App lifecycle and secure storage

`AuthSessionStore` uses Android Keystore and iOS Keychain. Access tokens may be held in memory while valid; refresh tokens are stored only in the platform secure store. Neither token may appear in preferences, ordinary databases, logs, crash reports, UI state, query parameters or source control.

On launch, `AuthSessionController` restores a non-expired access token locally. If it is expired or missing, it uses the refresh token. Successful refresh overwrites the entire stored session before publishing `Authenticated`; a rejected refresh clears storage and publishes `Unauthenticated`; a storage or transport failure becomes `Unavailable` without silently manufacturing an identity.

The controller serializes restore, activation and invalidation. `AppContextSnapshot` is the sole observable current-identity fact. Its stable context ID wraps the authenticated shell with `key(contextId)`, so login, logout, refresh failure or a future account switch destroys the previous `AppShell`, `NavHost`, feature ViewModels, scoped caches and SSE runtime.

## Configuration and operational safety

The backend requires non-secret identifiers and secret values with distinct handling:

| Configuration | Secret | Purpose |
| --- | --- | --- |
| `DATABASE_URL`, `DATABASE_USER` | No | PostgreSQL connection location and principal. |
| `DATABASE_PASSWORD` | Yes | PostgreSQL password. |
| `AUTH_JWT_ISSUER`, `AUTH_JWT_AUDIENCE`, `AUTH_JWT_KEY_ID` | No | NexusFlow access-token validation and key-rotation metadata. |
| `AUTH_JWT_PRIVATE_KEY_PEM_BASE64` | Yes | Private signing key for NexusFlow access tokens. |
| `AUTH_ACCESS_TTL_SECONDS`, `AUTH_REFRESH_TTL_DAYS` | No | Access and refresh lifetime policy. |
| `GOOGLE_ALLOWED_AUDIENCES` | No | Allowed Google OAuth Web Client IDs for ID-token audience validation. |

Production secrets come from the deployment secret manager. Repository examples may contain empty placeholders only. The Android build receives only non-secret `API_BASE_URL` and `GOOGLE_SERVER_CLIENT_ID`; it must never contain backend signing material, database credentials, or a Google client secret.

Authentication telemetry uses low-cardinality provider/result/error categories only. It must not record raw token values, authorization headers, provider subject, email, Google profile data, refresh-token hash, or request bodies.

## Verification and future triggers

The slice is complete only when all of the following are proven:

- A clean local PostgreSQL database applies the initial identity/session Flyway migration reproducibly.
- Backend accepts a valid Google credential and rejects invalid signature, issuer, audience and expiry cases.
- The same verified Google subject resolves to one NexusFlow user; a client-supplied user, tenant or role cannot change the result.
- Refresh rotation invalidates the old refresh token, detects replay, and logout prevents another refresh for that session.
- Business routes reject Google credentials and development headers in production; they accept only a valid NexusFlow access token.
- Android opens Credential Manager's native account picker and iOS opens the Google Sign-In SDK account UI; cancellation returns to a retryable login state, and successful exchange enters a fresh authenticated shell.
- Logout or identity change cannot reveal the old navigation stack, cached data or scoped runtime.

Apple, Android Apple browser fallback, explicit account linking, multi-device session management, MFA, and any identity-broker introduction remain separate future decisions. Each needs its own provider configuration, backend verifier/contract, lifecycle tests and product acceptance before implementation.

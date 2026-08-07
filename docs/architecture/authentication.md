# Authentication integration convention

## Ownership

`feature/auth` owns the client authentication flow. `SessionController` is the only reader and writer of `AuthSessionStore`, and the only publisher of `AuthState`. UI, repositories, HTTP transport, and platform SDK bridges must not persist a session, clear one, or navigate based on authentication themselves.

The authentication request boundary is explicit: `AuthenticationCoordinator` depends on the domain `AuthRepository` interface; `DefaultAuthRepository` maps OIDC/HTTP responses, feature DTOs and failures to domain models; feature-local `AuthApi` owns endpoint paths and request bodies. It is constructed only for `DefaultAuthRepository`, not exposed as a Koin binding. The shared `HttpClient` reuses the application API URL and attaches client context centrally, so authentication introduces neither a second client nor a provider. See [networking.md](networking.md) for the HTTP/protocol boundary. Do not add a forwarding DataSource or a global API service unless a real second source or consumer gives it an independent responsibility.

`AuthGate` is the root-level consumer: `Restoring` shows a loading state, `Unauthenticated` the login flow, `Authenticated` the app shell, and `Unavailable` a retry state. Tokens never enter UI state, logs or navigation arguments. Regular preferences remain prohibited for tokens except for the explicit temporary session-storage deviation below.

## Session lifecycle

The long-term baseline is that `AuthSessionStore` uses `SecureStore` only. A successful session must contain a non-empty access token and user ID. At launch, `SessionController` parses the access-token JWT `exp` solely to decide whether local recovery is possible. A non-expired token restores the app without a request; an expired or malformed access token uses the refresh token. Refresh success overwrites the entire stored session before publishing `Authenticated`. A rejected refresh clears session storage before publishing `Unauthenticated`; storage or temporary transport failure becomes `Unavailable`.

The controller uses a mutex to serialize restore, activation, and invalidation. Repository requests preserve coroutine cancellation. The shared HTTP boundary owns one refresh attempt and one original-request retry. Only a replayed 401 asks the controller to clear the session, and it does so only when the current stored access token still equals the replayed token; an old request can never clear a newer session.

### Temporary session-storage deviation

Both current platform builds temporarily store the `auth/session` snapshot in the shared `KeyValueStore` / Preferences DataStore instead of `SecureStore`, because Android Keystore AES-GCM initialization is failing during login integration. This exception exists only to unblock integration and is not a replacement for the secure-storage baseline.

- Scope: only the auth session snapshot; tokens remain prohibited from UI state, logs, navigation arguments, request query and every other preference namespace.
- Risk: access and refresh tokens are no longer protected by Android Keystore or iOS Keychain. Any release carrying this behavior requires explicit product and security risk acceptance.
- Migration: secure and DataStore sessions are not migrated. Restoring `SecureStore` must ignore or clear the temporary `auth/session` value and require a new login rather than copying a token between stores.
- Re-evaluation trigger: fix or positively classify the Keystore failure, then restore `SecureStore` before treating this storage path as a long-term behavior.

## Adding a login method

- A verified channel (email, phone) implements `VerificationLoginProvider`: request challenge, then verify it.
- OIDC login starts with a feature `UiEffect`. Its Route sends a typed browser-login request through the Activity/window-owned `SystemUiGateway`; the platform UI Host completes Keycloak Authorization Code + PKCE and returns a matching result as a feature Intent.
- `AuthenticationCoordinator` consumes only an already-acquired credential, maps it through `AuthRepository`, then asks `SessionController.activate` to persist it. Neither the Route nor the platform UI Host mutates navigation or session state.
- System UI requests carry a requestId and are single-active per Activity/window. Host detach, Route cancellation, and late platform callbacks must resolve or ignore the same requestId so a login submission cannot remain pending.
- Add an explicit UI entry, error mapping, platform configuration, and focused tests.

The stable random `X-Client-Instance-Id` is a non-sensitive UUID owned by `core/network` and its `KeyValueStore` namespace. It is not a hardware, advertising, or fingerprint identifier. Access and refresh tokens remain secrets by policy and return to `SecureStore` when the temporary session-storage deviation is removed.

## Response contract

Authentication accepts only the currently agreed backend success codes: `0` and `200`. Any other business code is an authentication failure even when the HTTP response itself is successful.

## Platform and configuration

`RuntimeConfig.apiBaseUrl` and OIDC issuer/client ID are non-secret build values; an empty value is a build configuration error rather than an authentication-specific runtime state. Before release, Keycloak must register Orbit's Android package/signing SHA, iOS bundle ID/URL scheme and API audience. The app never embeds a client secret. Browser callback handling is added only with the configured OIDC dependency and URL scheme; unhandled URLs are forwarded to the deep-link bridge.

`acceptedPolicyVersion` is optional and additive in client requests, but the existing backend does not yet persist or audit it. Persisting it is a separate additive backend prerequisite; existing clients must remain valid when it is omitted.

The current reused email contract returns `cooldownUntil` as a Java `LocalDateTime` without an offset or server time-zone contract. The client therefore exposes resend and relies on the server's rate-limit response; it must not infer a client cooldown from that ambiguous value. Accurate client-side disable/countdown requires an additive backend `resendAfterSeconds` (preferred) or ISO-8601 UTC instant. Terms and Privacy Policy links also require configured HTTPS URLs; no embedded WebView is added until those URLs exist.

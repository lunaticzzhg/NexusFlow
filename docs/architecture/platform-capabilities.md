# Platform Capabilities

Platform capabilities are narrow, typed ports. `commonMain` decides product policy; Android and iOS only execute atomic system work and return structured results. Native objects, raw SDK errors, secrets and user-facing copy do not cross the boundary.

- Notification capability reports `Available`, `Denied`, `Unavailable` or `Degraded`; unavailable platform configuration must never be represented as a successful delivery. Android uses the configured notification channel/permission; iOS uses the declared APNs capability and entitlement.
- Native identity authorization is platform-owned atomic work. This delivery uses Android Credential Manager to obtain a Google ID token; the shared authentication flow exchanges it with the backend for a NexusFlow session. Provider credentials, backend signing keys and third-party OAuth secrets do not belong in the app source. Browser callback handling is a separate future capability, not a hidden fallback for Google login.
- System calendar access is an explicit user-approved capability. The feature owns the interaction and maps permission denial, unavailable calendars and cancellation to visible states; it never assumes a write succeeded from a local intent alone.
- Notification taps and any future browser callbacks are deep links. They contain stable route/task/approval references only; destination state is revalidated from the server before rendering or enabling an approval action.
- SSE transport has a feature/runtime owner. It exposes connection, event, degraded and failure state; page departure, tenant/user change and background policy close the connection. REST remains the recovery source of truth.
- Platform adapters must not embed task planning, approval policy, retry strategy, cache policy or AI logic. Those rules stay in shared feature/domain code and common tests.

For each capability, document the platform constraint, owner, lifecycle, structured failure result and minimum Android/iOS verification.

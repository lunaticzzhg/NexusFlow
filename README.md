# NexusFlow / Orbit

An approval-first, personalized leisure-planning Agent. It discovers opportunities across plug-ins (sports, films, local events), builds feasible plans, and only performs external actions after user approval.

## Repository layout

- `app/` — Kotlin Multiplatform client.
- `backend/` — Ktor backend foundation: core HTTP/persistence capabilities and authentication.
- `ai/` — Reserved for a future planning module; no AI runtime is implemented yet.
- `contracts/` — versioned App <-> Backend wire schemas; not Backend domain or AI-internal models.

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

See [infra/README.md](infra/README.md) for PostgreSQL, Redpanda and OpenTelemetry local endpoints. Use the [v0.1 RoadMap](docs/v0.1/roadmap.md) for future development sequencing; the [v0.1 App module reference](docs/v0.1/app-module-technical-plan.md) is App-specific supporting context.

The KMP module structure is Android-first and can be opened from the repository root in Android Studio.

### Android debug app

Start the local backend first; the Android debug app expects it on the Mac at
port 8080:

```bash
ORBIT_RUNTIME_PROFILE=local ./gradlew :backend:run
```

Then build, install, and start the app with the target-aware helper:

```bash
# Detect the selected target automatically.
scripts/run_android_debug.sh

# Require an Android emulator; it uses http://10.0.2.2:8080.
scripts/run_android_debug.sh --emulator

# Require a physical device; it configures adb reverse for 127.0.0.1:8080.
scripts/run_android_debug.sh --device
```

When more than one target is online, set `ANDROID_SERIAL` to select it, for
example: `ANDROID_SERIAL=emulator-5554 scripts/run_android_debug.sh --emulator`.

### iOS Simulator debug app

The iOS debug app runs only on an iOS Simulator and does not require an Apple
Developer Program membership. Start the local backend first; the Simulator uses
`http://localhost:8080` to reach it. Override the non-sensitive API endpoint
with `IOS_API_BASE_URL` when needed. A future physical-device build must use an
HTTPS endpoint; it must not use `localhost`.

```bash
scripts/run_ios_simulator_debug.sh
```

Set `IOS_SIMULATOR_UDID` when more than one iPhone Simulator is available, for
example: `IOS_SIMULATOR_UDID=<udid> scripts/run_ios_simulator_debug.sh`.

The iOS host uses the native Google Sign-In SDK and exchanges only its ID token
through the shared authentication flow. The Simulator must have a working route
to Google; complete account-flow validation on a signed physical device with an
HTTPS API endpoint before release.

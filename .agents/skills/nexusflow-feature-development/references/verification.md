# Verification Reference

Verification is derived from the touched scope. Start with the narrowest evidence that can prove the changed behavior or governance rule, then expand only when risk requires it.

## Scope Matrix

| Touched area | Default evidence | Expand when risk requires |
| --- | --- | --- |
| App/KMP | relevant App tests/compile plus `./gradlew :app:composeApp:ktlintCheck` for App Kotlin/Gradle changes | `:app:composeApp:allTests`, Android builds, iOS target tests/builds, Compose UI/render checks |
| Backend | `./gradlew :backend:test` plus relevant compile/runtime integration evidence | focused integration tests for auth, transactions, persistence, external providers, or blocking/concurrency boundaries |
| Contracts | `./gradlew :contracts:jvmTest` plus affected producer/consumer verification | compatibility tests across App/Backend/AI-facing mappings and cross-stack flow proof |
| AI | use actual `:ai` tasks when the Gradle module/runtime exists | deterministic guardrail tests, schema/contract tests, eval cases, provider-adapter tests after real source exists |
| Cross-stack | union of touched-area checks plus at least one contract/flow-level proof | end-to-end or integration checks for permission, idempotency, approval, persistence, or visible output |
| Docs/skills only | `git diff --check`, changed-path inspection, internal path/link/routing searches, and template smoke/static checks when templates are touched | generated handoff bundle smoke only when safe and useful |

Use the current `settings.gradle.kts` and real source tree as the authority for whether `:ai` verification exists. Do not invent an AI command as proof when a branch only has a reserved `ai/` root.

## Evidence By Risk

- Product behavior, state machines, permissions, trust boundaries, idempotency, durable invariants, and failure recovery need tests or concrete runtime proof.
- Pure wiring with no branch can use compile/dependency resolution as evidence when the relevant build task actually covers it.
- Third-party library behavior is not retested unless NexusFlow wraps it with product-specific semantics.
- A skipped verification must name the missing evidence and why it was not run.

## App/KMP Notes

For App/KMP source, keep using the App authority and its specialist references:

- ViewModel/state/effect and App domain behavior are usually tested in `commonTest` with `kotlin.test`.
- Use `kotlinx-coroutines-test` when the changed behavior depends on coroutine scheduling.
- Koin module tests are useful for branchy or lifecycle-sensitive App DI; pure binding can be covered by target compilation.
- Platform `actual` behavior may require Android/iOS source-set tests, emulator/device checks, or Xcode build evidence.
- App Kotlin or Gradle Kotlin DSL changes require `./gradlew :app:composeApp:ktlintCheck`; if hand-written style fails, run the App formatter, review the diff, and rerun the check.

## Backend Notes

Backend verification follows Backend ownership, not App lint rules:

- route/adapter mapping changes need HTTP input/output and failure mapping evidence;
- application-service decisions need unit or integration tests at that decision boundary;
- repository/infrastructure mutations need transaction/invariant tests;
- blocking IO and coroutine ownership need evidence from the owning boundary.

For Backend JDBC, migration, transaction, FK / UNIQUE / CHECK, idempotency, optimistic concurrency, PostgreSQL-specific SQL, or durable multi-write changes, load `backend-persistence.md`. DB-specific semantics require real PostgreSQL integration evidence. Ordinary local runs may clearly skip PostgreSQL tests when Docker is unavailable, but Work Orders or final verification that require PostgreSQL must use `NEXUSFLOW_REQUIRE_POSTGRES_TESTS=true`; with that flag, Docker/PostgreSQL unavailability must fail the test suite rather than being reported as skipped.

## Contracts Notes

Contract verification must prove the serialized shape and each affected side:

- shared model serialization/deserialization;
- producer mapping into the wire contract;
- consumer mapping out of the wire contract;
- compatibility behavior for optional/defaulted/renamed/removed fields.

## Docs And Skills Notes

For governance-only changes, do not run product suites for ceremony. Verify:

- changed paths are confined to the intended docs/skills/templates;
- old routing strings no longer point active product development at removed skills;
- template headings are module-neutral;
- architecture links referenced by templates/skills match the intended authority paths;
- Backend persistence routing points to `backend-persistence.md` instead of duplicating the full architecture text;
- no text implies an implemented AI runtime when the current branch only has a reserved `ai/` root.

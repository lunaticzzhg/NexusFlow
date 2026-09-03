# Backend Persistence Reference

This reference is an executable checklist for Backend durable writes. The Backend architecture authority remains `docs/architecture/nexusflow-backend-architecture.md`.

## When To Load This Reference

Load this for Backend JDBC, Flyway migrations, transaction boundaries, FK / UNIQUE / CHECK / NOT NULL integrity rules, idempotency, optimistic concurrency, JSONB/PostgreSQL-specific SQL, and durable multi-write behavior.

## 1. Ownership & Durable Facts

- [ ] What business invariant is being written?
- [ ] Which Application Service owns the product decision?
- [ ] Which repository/infrastructure code owns the DB mechanics?
- [ ] What rows/tables are written?
- [ ] Which mutable facts are authoritative after commit?

## 2. Integrity Rule Inventory

- [ ] Which FK/UNIQUE/CHECK/NOT NULL integrity rules matter?
- [ ] Which `ON DELETE` / `ON UPDATE` policy applies?
- [ ] Does any row reference another row created in the same transaction?
- [ ] Is any valid integrity rule being weakened? If yes, stop and reassess mutation order first.

## 3. Mutation Order

- [ ] Write the order as `Precondition -> Write #1 -> Write #2 -> Postcondition`.
- [ ] Check FK direction before implementation.
- [ ] Is the referenced row inserted before the reference becomes valid?
- [ ] Are conditional updates checked with affected-row counts?

Example: `auth_sessions.replaced_by_session_id` references another `auth_sessions.id`.

```text
current session is active and not expired
→ INSERT replacement session
→ UPDATE current session to revoked + replaced_by_session_id = replacement.id
→ require one current row was updated
→ COMMIT
```

If the update is rejected or updates zero rows, the replacement insert rolls back with the transaction.

## 4. Transaction Pre/Postconditions

- [ ] What must be true before the transaction starts?
- [ ] What is the transaction's atomic postcondition?
- [ ] Which intermediate write may fail?
- [ ] What must rollback if the last write fails?
- [ ] What does `affectedRows == 0` mean?
- [ ] What does duplicate/unique conflict mean?

## 5. Duplicate/Concurrent/Stale Cases

- [ ] How do duplicate concurrent requests terminate?
- [ ] How do competing requests for the same row terminate?
- [ ] How is a stale/late result rejected?
- [ ] Which DB integrity rule or conditional write is the final authority?
- [ ] Which application/domain result represents the conflict?

## 6. Migration Safety

- [ ] Is an existing Flyway migration being edited? If yes, stop.
- [ ] Does the new migration work on a fresh DB?
- [ ] Does it work with representative old data?
- [ ] Are changed integrity rules/indexes intentional and named?
- [ ] Is staged / expand-contract evolution needed?

## 7. Real PostgreSQL Test Matrix

- [ ] Is a real PostgreSQL test present for DB-specific semantics?
- [ ] Fresh DB migrates to latest.
- [ ] Representative prior schema/data migrates forward when the migration can affect existing rows.
- [ ] FK/UNIQUE/CHECK/JSONB/rollback/locking behavior is not proved only by fake, mock, or H2 tests.
- [ ] If final Work Order evidence depends on PostgreSQL, `NEXUSFLOW_REQUIRE_POSTGRES_TESTS=true` cannot silently skip those tests.

## 8. Debug / Human Takeover Evidence

- [ ] Which transaction owns the invariant?
- [ ] Which rows were attempted?
- [ ] Which affected-row counts or conflicts were observed?
- [ ] What rolled back?
- [ ] Which audit/log/test boundary proves the terminal result?

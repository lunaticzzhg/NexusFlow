# Context Runtime

This convention governs in-memory resources whose validity depends on the current application identity. It applies to user, tenant, task/conversation and session switches; it does not define business APIs, persistence schemas, navigation routes, or server authorization.

## Authority and hierarchy

`AppContextSnapshot` is the sole observable fact for the current identity. Its values obey:

```text
SESSION → USER → TENANT → TASK_OR_CONVERSATION
```

- No session means no user, tenant, task or conversation.
- A user switch clears tenant, task and conversation.
- A tenant switch clears task and conversation.
- A task or conversation switch preserves user and tenant.

The snapshot may expose stable UI keys such as `sessionKey`, `userKey`, `tenantKey` and `taskKey`. A key contains every identity component that changes the legality of its owned UI state.

## Runtime ownership

`ContextRuntime` owns a small group of closeable, in-memory resources at one declared level:

```kotlin
enum class ContextLevel { SESSION, USER, TENANT, TASK_OR_CONVERSATION }
```

It is not a Koin scope, navigation destination or persistence key. A tenant runtime is invalid after either user or tenant changes; a task runtime is also invalid after session changes. The runtime owner creates, observes and closes its resources; workers capture the immutable context at start and must not consult global mutable identity when publishing an old result.

| Resource data scope | Minimum runtime level |
| --- | --- |
| authentication/session | `SESSION` |
| user preferences | `USER` |
| tenant-scoped discovery and tasks | `TENANT` |
| task detail, chat negotiation or SSE subscription | `TASK_OR_CONVERSATION` |

## Transition protocol

1. Publish the next immutable snapshot.
2. Stop descendants from deepest to shallowest: task/conversation → tenant → user → session.
3. Cancel owned jobs, close SSE/notification handles and invalidate only matching caches.
4. Rebuild the lowest required runtime and UI boundary with the new stable key.
5. On late success/failure, compare the captured key with the current key before writing UI state, cache or navigation effects; discard mismatches.

Use `key(userKey)` for authenticated shells, `key(tenantKey)` for tenant-scoped screens and `key(taskKey)` only where a task-specific ViewModel must be destroyed. Tabs, filters, dates and sorting remain Intents in the same ViewModel because they do not alter ownership.

## REST, SSE and persistence

REST task detail is authoritative. SSE is owned by the matching runtime and carries an event cursor; on reconnect or a version gap, refetch the snapshot. Secure token storage and durable database records are not closed by a runtime, but their in-memory projections and writes must still be scoped by captured user/tenant identity.

## Verification

- user, tenant, task and conversation changes invalidate exactly the required runtime levels;
- old requests, SSE callbacks and scheduled UI effects cannot write into the new identity;
- cached reads/writes are keyed by the same captured identity;
- closing is idempotent and releases jobs/listeners;
- tests cover normal switch, late result, reconnect and back/foreground transition.

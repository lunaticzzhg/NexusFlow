# Task state machine

`CREATED → PLANNING → GATHERING_CONTEXT → VALIDATING → AWAITING_APPROVAL → EXECUTING → COMPLETED`

Any active state may transition to `RETRYING`, `FAILED` or `CANCELLED`. Only an approval with a matching version may transition an action out of `AWAITING_APPROVAL`. Execution must use a stable idempotency key.

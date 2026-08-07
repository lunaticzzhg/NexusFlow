-- Orbit task command model. Run via Flyway in api-service / orchestrator-worker.
-- PostgreSQL is the authority; Redis and Kafka never replace these records.

CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    owner_user_id TEXT NOT NULL,
    status TEXT NOT NULL,
    version BIGINT NOT NULL,
    request_text TEXT NOT NULL,
    timezone TEXT NOT NULL,
    conversation_id TEXT,
    source_discovery_id TEXT,
    idempotency_key TEXT NOT NULL,
    request_fingerprint TEXT NOT NULL,
    proposal_json JSONB,
    lease_owner TEXT,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT tasks_status_check CHECK (status IN (
        'QUEUED', 'GATHERING_CONTEXT', 'PLANNING', 'VALIDATING',
        'AWAITING_APPROVAL', 'EXECUTING', 'COMPLETED', 'RETRYING', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT tasks_idempotency_per_actor UNIQUE (tenant_id, owner_user_id, idempotency_key)
);

CREATE INDEX tasks_owner_updated_idx ON tasks (tenant_id, owner_user_id, updated_at DESC);
CREATE INDEX tasks_reclaim_lease_idx ON tasks (status, lease_until) WHERE lease_until IS NOT NULL;

CREATE TABLE task_events (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id),
    tenant_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,
    correlation_id TEXT NOT NULL,
    causation_id TEXT,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX task_events_timeline_idx ON task_events (task_id, occurred_at, id);

-- An outbox row is inserted in the same transaction as its task/event mutation.
-- The publisher marks it after Kafka/Redpanda acknowledgement; duplicate delivery is expected.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    topic TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX outbox_unpublished_idx ON outbox_events (occurred_at) WHERE published_at IS NULL;


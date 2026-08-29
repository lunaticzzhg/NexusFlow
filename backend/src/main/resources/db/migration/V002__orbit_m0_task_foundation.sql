-- Orbit M0 task foundation. This is normal durable state, not event sourcing.

CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    creation_request_id TEXT NOT NULL,
    initial_goal TEXT NOT NULL,
    current_goal TEXT NOT NULL,
    title TEXT NOT NULL,
    state TEXT NOT NULL,
    version BIGINT NOT NULL,
    selected_plan_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, owner_user_id, creation_request_id),
    CONSTRAINT tasks_state_check CHECK (
        state IN (
            'Draft',
            'CollectingConstraints',
            'Planning',
            'WaitingForApproval',
            'Executing',
            'NeedsAttention',
            'Completed',
            'Cancelled'
        )
    )
);

CREATE INDEX tasks_owner_updated_idx ON tasks (tenant_id, owner_user_id, updated_at DESC);

CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL UNIQUE REFERENCES tasks(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE task_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    client_message_id TEXT,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    ai_request_id TEXT,
    understood_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (conversation_id, client_message_id),
    CONSTRAINT task_messages_role_check CHECK (role IN ('User', 'Assistant'))
);

CREATE TABLE task_constraints (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    value_json JSONB NOT NULL,
    strength TEXT NOT NULL,
    source TEXT NOT NULL,
    evidence_message_id UUID NOT NULL REFERENCES task_messages(id),
    confirmed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (task_id, kind),
    CONSTRAINT task_constraints_kind_check CHECK (
        kind IN (
            'TimeWindow',
            'BudgetLimit',
            'CommuteLimit',
            'Location',
            'ActivityDomain',
            'Topic',
            'ExperiencePreference'
        )
    ),
    CONSTRAINT task_constraints_strength_check CHECK (strength IN ('Hard', 'Soft')),
    CONSTRAINT task_constraints_source_check CHECK (
        source IN ('UserExplicit', 'AcceptedSuggestion', 'OpportunityContext', 'SystemDerived')
    )
);

CREATE TABLE planning_runs (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    client_request_id TEXT NOT NULL,
    task_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (task_id, client_request_id)
);

CREATE TABLE plans (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    planning_run_id UUID NOT NULL REFERENCES planning_runs(id) ON DELETE CASCADE,
    payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE tasks
    ADD CONSTRAINT tasks_selected_plan_fk FOREIGN KEY (selected_plan_id) REFERENCES plans(id) ON DELETE SET NULL;

CREATE TABLE task_audit_events (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    request_id TEXT,
    ai_request_id TEXT,
    metadata_json JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX task_audit_events_task_occurred_idx ON task_audit_events (task_id, occurred_at);

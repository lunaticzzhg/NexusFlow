-- Orbit task foundation. This baseline is intentionally incompatible with pre-launch Task schemas.

CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    creation_request_id TEXT NOT NULL,
    intent TEXT NOT NULL,
    revision BIGINT NOT NULL,
    selected_plan_id UUID NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NULL,
    UNIQUE (tenant_id, owner_user_id, creation_request_id)
);

CREATE INDEX tasks_owner_updated_idx ON tasks (tenant_id, owner_user_id, updated_at DESC);

CREATE TABLE task_messages (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    client_message_id TEXT,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    ai_request_id TEXT,
    understood_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (task_id, client_message_id),
    CONSTRAINT task_messages_role_check CHECK (role IN ('User', 'Assistant'))
);

CREATE TABLE task_requirements (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    value_json JSONB NOT NULL,
    strength TEXT NOT NULL,
    source TEXT NOT NULL,
    evidence_message_id UUID NULL REFERENCES task_messages(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (task_id, kind),
    CONSTRAINT task_requirements_kind_check CHECK (
        kind IN (
            'TimeWindow',
            'BudgetLimit',
            'CommuteLimit',
            'CommutePreference',
            'Location',
            'ActivityDomain',
            'ActivityMode',
            'Topic',
            'ExperiencePreference'
        )
    ),
    CONSTRAINT task_requirements_strength_check CHECK (strength IN ('Must', 'Prefer')),
    CONSTRAINT task_requirements_source_check CHECK (source IN ('UserExplicit', 'SystemDerived'))
);

CREATE TABLE opportunity_snapshots (
    id UUID PRIMARY KEY,
    provider TEXT NOT NULL,
    external_key TEXT NOT NULL,
    kind TEXT NOT NULL,
    title TEXT NOT NULL,
    facts_json JSONB NOT NULL,
    sources_json JSONB NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NULL,
    CONSTRAINT opportunity_snapshots_kind_check CHECK (kind IN ('Sports', 'Movies'))
);

CREATE TABLE plans (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    direction TEXT NOT NULL,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    timeline_json JSONB NOT NULL,
    estimated_cost_json JSONB NULL,
    commute_minutes INTEGER NULL,
    tradeoffs_json JSONB NOT NULL,
    reasons_json JSONB NOT NULL,
    valid_until TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT plans_direction_check CHECK (direction IN ('BestMatch', 'MoreRelaxed', 'NewExperience'))
);

CREATE INDEX plans_task_revision_idx ON plans (task_id, revision);

CREATE TABLE plan_opportunities (
    plan_id UUID NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    opportunity_snapshot_id UUID NOT NULL REFERENCES opportunity_snapshots(id) ON DELETE RESTRICT,
    PRIMARY KEY (plan_id, opportunity_snapshot_id)
);

CREATE TABLE plan_requirement_evaluations (
    plan_id UUID NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    requirement_id UUID NOT NULL REFERENCES task_requirements(id) ON DELETE CASCADE,
    result TEXT NOT NULL,
    explanation TEXT NULL,
    PRIMARY KEY (plan_id, requirement_id),
    CONSTRAINT plan_requirement_evaluations_result_check CHECK (result IN ('Satisfied', 'NotApplicable'))
);

ALTER TABLE tasks
    ADD CONSTRAINT tasks_selected_plan_fk FOREIGN KEY (selected_plan_id) REFERENCES plans(id) ON DELETE SET NULL;

CREATE TABLE task_context_selections (
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    context_key VARCHAR(128) NOT NULL,
    selected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (task_id, context_key)
);

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

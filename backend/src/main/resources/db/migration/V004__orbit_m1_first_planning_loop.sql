CREATE TABLE explicit_preferences (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    kind TEXT NOT NULL,
    value_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX explicit_preferences_owner_idx ON explicit_preferences (tenant_id, owner_user_id);

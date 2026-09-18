CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    creation_request_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NULL,
    CONSTRAINT conversations_owner_identity_unique UNIQUE (id, tenant_id, owner_user_id),
    CONSTRAINT conversations_owner_creation_request_unique UNIQUE (tenant_id, owner_user_id, creation_request_id)
);

CREATE INDEX conversations_owner_updated_idx ON conversations (tenant_id, owner_user_id, updated_at DESC);

CREATE TABLE conversation_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    client_message_id TEXT,
    ai_request_id TEXT,
    understood_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (conversation_id, client_message_id),
    CONSTRAINT conversation_messages_role_check CHECK (role IN ('User', 'Assistant'))
);

CREATE INDEX conversation_messages_conversation_created_idx
    ON conversation_messages (conversation_id, created_at ASC, id ASC);

ALTER TABLE tasks
    ADD COLUMN conversation_id UUID NULL;

INSERT INTO conversations (id, tenant_id, owner_user_id, creation_request_id, created_at, updated_at, archived_at)
SELECT id, tenant_id, owner_user_id, creation_request_id, created_at, updated_at, archived_at
FROM tasks
WHERE conversation_id IS NULL;

UPDATE tasks
SET conversation_id = id
WHERE conversation_id IS NULL;

ALTER TABLE tasks
    ADD CONSTRAINT tasks_conversation_owner_fk
        FOREIGN KEY (conversation_id, tenant_id, owner_user_id)
        REFERENCES conversations(id, tenant_id, owner_user_id)
        ON DELETE RESTRICT;

CREATE UNIQUE INDEX tasks_conversation_id_unique_idx
    ON tasks (conversation_id)
    WHERE conversation_id IS NOT NULL;

INSERT INTO conversation_messages (
    id,
    conversation_id,
    role,
    content,
    client_message_id,
    ai_request_id,
    understood_at,
    created_at
)
SELECT
    task_messages.id,
    tasks.conversation_id,
    task_messages.role,
    task_messages.content,
    task_messages.client_message_id,
    task_messages.ai_request_id,
    task_messages.understood_at,
    task_messages.created_at
FROM task_messages
JOIN tasks ON tasks.id = task_messages.task_id
WHERE tasks.conversation_id IS NOT NULL;

ALTER TABLE task_requirements
    ADD COLUMN conversation_evidence_message_id UUID NULL;

UPDATE task_requirements
SET conversation_evidence_message_id = evidence_message_id
WHERE evidence_message_id IS NOT NULL;

ALTER TABLE task_requirements
    ADD CONSTRAINT task_requirements_conversation_evidence_message_fk
        FOREIGN KEY (conversation_evidence_message_id)
        REFERENCES conversation_messages(id)
        ON DELETE SET NULL;

ALTER TABLE conversations
    ADD COLUMN next_turn_index BIGINT;

ALTER TABLE conversation_messages
    ADD COLUMN turn_index BIGINT;

WITH user_turns AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY conversation_id
            ORDER BY created_at ASC, id ASC
        ) AS turn_index
    FROM conversation_messages
    WHERE role = 'User'
)
UPDATE conversation_messages AS message
SET turn_index = user_turns.turn_index
FROM user_turns
WHERE message.id = user_turns.id;

WITH assistant_turns AS (
    SELECT
        assistant.id AS assistant_id,
        user_message.turn_index
    FROM conversation_messages AS assistant
    JOIN LATERAL (
        SELECT turn_index
        FROM conversation_messages AS candidate
        WHERE candidate.conversation_id = assistant.conversation_id
          AND candidate.role = 'User'
          AND candidate.ai_request_id = assistant.ai_request_id
          AND candidate.turn_index IS NOT NULL
        ORDER BY candidate.created_at ASC, candidate.id ASC
        LIMIT 1
    ) AS user_message ON TRUE
    WHERE assistant.role = 'Assistant'
      AND assistant.turn_index IS NULL
      AND assistant.ai_request_id IS NOT NULL
)
UPDATE conversation_messages AS message
SET turn_index = assistant_turns.turn_index
FROM assistant_turns
WHERE message.id = assistant_turns.assistant_id;

WITH conversation_max_turn AS (
    SELECT conversation_id, COALESCE(MAX(turn_index), 0) AS max_turn_index
    FROM conversation_messages
    GROUP BY conversation_id
),
orphan_turns AS (
    SELECT
        message.id,
        conversation_max_turn.max_turn_index +
            ROW_NUMBER() OVER (
                PARTITION BY message.conversation_id
                ORDER BY message.created_at ASC, message.id ASC
            ) AS turn_index
    FROM conversation_messages AS message
    JOIN conversation_max_turn ON conversation_max_turn.conversation_id = message.conversation_id
    WHERE message.turn_index IS NULL
)
UPDATE conversation_messages AS message
SET turn_index = orphan_turns.turn_index
FROM orphan_turns
WHERE message.id = orphan_turns.id;

UPDATE conversations AS conversation
SET next_turn_index = COALESCE(message_turns.next_turn_index, 1)
FROM (
    SELECT conversation_id, MAX(turn_index) + 1 AS next_turn_index
    FROM conversation_messages
    GROUP BY conversation_id
) AS message_turns
WHERE conversation.id = message_turns.conversation_id;

UPDATE conversations
SET next_turn_index = 1
WHERE next_turn_index IS NULL;

ALTER TABLE conversations
    ALTER COLUMN next_turn_index SET NOT NULL,
    ADD CONSTRAINT conversations_next_turn_index_check CHECK (next_turn_index >= 1);

ALTER TABLE conversation_messages
    ALTER COLUMN turn_index SET NOT NULL,
    ADD CONSTRAINT conversation_messages_turn_index_check CHECK (turn_index >= 1);

CREATE TABLE response_runs (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_message_id UUID NOT NULL REFERENCES conversation_messages(id) ON DELETE CASCADE,
    turn_index BIGINT NOT NULL,

    status TEXT NOT NULL,
    stage TEXT NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,

    available_at TIMESTAMPTZ NOT NULL,
    lease_owner TEXT NULL,
    lease_expires_at TIMESTAMPTZ NULL,
    deadline_at TIMESTAMPTZ NOT NULL,

    expected_task_id UUID NULL REFERENCES tasks(id) ON DELETE SET NULL,
    expected_task_revision BIGINT NULL,
    assistant_message_id UUID NULL REFERENCES conversation_messages(id) ON DELETE SET NULL,

    failure_category TEXT NULL,

    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NULL,

    CONSTRAINT response_runs_status_check CHECK (
        status IN ('QUEUED','PROCESSING','STREAMING','COMPLETED','FAILED_RETRYABLE','FAILED','TIMED_OUT','CANCELLED')
    ),
    CONSTRAINT response_runs_stage_check CHECK (stage IN ('TURN','PLANNING')),
    CONSTRAINT response_runs_attempt_check CHECK (attempt >= 0),
    CONSTRAINT response_runs_turn_index_check CHECK (turn_index >= 1),
    CONSTRAINT response_runs_deadline_check CHECK (deadline_at >= created_at),
    CONSTRAINT response_runs_failure_category_check CHECK (
        failure_category IS NULL OR
        failure_category IN ('PROVIDER_TEMPORARY','AI_INVALID_RESULT','WORKER_LOST','RUN_TIMEOUT','INTERNAL_INVARIANT')
    )
);

CREATE INDEX response_runs_claim_idx
    ON response_runs (status, available_at, created_at);

CREATE INDEX response_runs_conversation_turn_idx
    ON response_runs (conversation_id, turn_index, created_at);

CREATE INDEX response_runs_user_message_idx
    ON response_runs (user_message_id, created_at DESC);

CREATE UNIQUE INDEX response_runs_one_active_per_user_message_idx
    ON response_runs (user_message_id)
    WHERE status IN ('QUEUED','PROCESSING','STREAMING');

INSERT INTO response_runs (
    id,
    conversation_id,
    user_message_id,
    turn_index,
    status,
    stage,
    attempt,
    available_at,
    lease_owner,
    lease_expires_at,
    deadline_at,
    expected_task_id,
    expected_task_revision,
    assistant_message_id,
    failure_category,
    created_at,
    started_at,
    updated_at,
    completed_at
)
SELECT
    user_message.id,
    user_message.conversation_id,
    user_message.id,
    user_message.turn_index,
    CASE WHEN user_message.understood_at IS NULL THEN 'QUEUED' ELSE 'COMPLETED' END,
    'TURN',
    0,
    user_message.created_at,
    NULL,
    NULL,
    user_message.created_at + INTERVAL '30 minutes',
    NULL,
    NULL,
    assistant_message.id,
    NULL,
    user_message.created_at,
    NULL,
    COALESCE(user_message.understood_at, user_message.created_at),
    user_message.understood_at
FROM conversation_messages AS user_message
LEFT JOIN LATERAL (
    SELECT id
    FROM conversation_messages AS candidate
    WHERE candidate.conversation_id = user_message.conversation_id
      AND candidate.role = 'Assistant'
      AND candidate.ai_request_id = user_message.ai_request_id
    ORDER BY candidate.created_at ASC, candidate.id ASC
    LIMIT 1
) AS assistant_message ON TRUE
WHERE user_message.role = 'User';

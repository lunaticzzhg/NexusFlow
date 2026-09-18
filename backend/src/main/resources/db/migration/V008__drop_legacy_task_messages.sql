ALTER TABLE task_requirements
    DROP CONSTRAINT IF EXISTS task_requirements_evidence_message_id_fkey;

ALTER TABLE task_requirements
    DROP COLUMN evidence_message_id;

DROP TABLE task_messages;

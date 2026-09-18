CREATE TABLE response_run_results (
    run_id UUID NOT NULL REFERENCES response_runs(id) ON DELETE CASCADE,
    attempt INTEGER NOT NULL,
    result_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NULL,
    PRIMARY KEY (run_id, attempt),
    CONSTRAINT response_run_results_attempt_check CHECK (attempt > 0),
    CONSTRAINT response_run_results_type_check CHECK (
        result_type IN ('CONVERSATION_ANSWER')
    )
);

CREATE INDEX response_run_results_pending_idx
    ON response_run_results (created_at)
    WHERE consumed_at IS NULL;

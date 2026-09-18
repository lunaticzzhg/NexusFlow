ALTER TABLE response_runs
    ADD COLUMN origin_trace_id TEXT NULL;

ALTER TABLE response_runs
    ADD CONSTRAINT response_runs_origin_trace_id_check
    CHECK (
        origin_trace_id IS NULL OR
        origin_trace_id ~ '^[0-9a-f]{32}$'
    );

ALTER TABLE response_runs
    ADD COLUMN time_zone_id TEXT NOT NULL DEFAULT 'UTC',
    ADD CONSTRAINT response_runs_time_zone_id_check CHECK (length(time_zone_id) BETWEEN 1 AND 128);

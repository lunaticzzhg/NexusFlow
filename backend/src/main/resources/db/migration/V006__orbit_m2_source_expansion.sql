ALTER TABLE opportunity_snapshots
    DROP CONSTRAINT opportunity_snapshots_kind_check;

ALTER TABLE opportunity_snapshots
    ADD CONSTRAINT opportunity_snapshots_kind_check CHECK (kind IN ('Sports', 'Movies', 'LiveEvents', 'Outdoor'));

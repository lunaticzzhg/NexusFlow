ALTER TABLE external_identities
    DROP CONSTRAINT external_identity_provider_check;

ALTER TABLE external_identities
    ADD CONSTRAINT external_identity_provider_check
    CHECK (provider IN ('GOOGLE', 'DEV_LOCAL'));

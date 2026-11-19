-- Elite Number is distinct from the ZCC application number and remains optional.
-- No format or uniqueness rule is imposed until the organisation confirms one.
ALTER TABLE members
    ADD COLUMN elite_number VARCHAR(80);

CREATE INDEX ix_members_elite_number
    ON members (organisation_id, elite_number)
    WHERE elite_number IS NOT NULL AND BTRIM(elite_number) <> '';

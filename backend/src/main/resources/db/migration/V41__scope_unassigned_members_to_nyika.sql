-- Retain the Nyika ownership of members registered before Cell placement.
-- This prevents one Nyika administrator from seeing another Nyika's unassigned members.
ALTER TABLE members
    ADD COLUMN nyika_id UUID REFERENCES organisational_units(id);

CREATE INDEX ix_members_nyika_unassigned
    ON members (organisation_id, nyika_id, last_name, first_name)
    WHERE cell_id IS NULL AND branch_id IS NULL;

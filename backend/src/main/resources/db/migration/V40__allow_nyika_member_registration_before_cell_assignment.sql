-- Allow Nyika administrators to register members before assigning them to a Cell.
-- A member must still belong to exactly one organisation; branch and Cell placement
-- may both be null only while the member is awaiting Cell assignment.
ALTER TABLE members DROP CONSTRAINT ck_members_single_location;
ALTER TABLE members
    ADD CONSTRAINT ck_members_single_location CHECK (
        (branch_id IS NOT NULL AND cell_id IS NULL)
        OR (branch_id IS NULL AND cell_id IS NOT NULL)
        OR (branch_id IS NULL AND cell_id IS NULL)
    );

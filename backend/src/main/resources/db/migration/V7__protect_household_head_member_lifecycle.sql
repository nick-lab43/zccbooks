-- Protect household-head integrity when a member is deactivated.
-- Existing deployed migrations V1-V6 remain immutable.
CREATE OR REPLACE FUNCTION prevent_deactivating_household_head()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.active = TRUE AND NEW.active = FALSE
       AND EXISTS (SELECT 1 FROM households h WHERE h.household_head_member_id = OLD.id) THEN
        RAISE EXCEPTION 'Clear or replace the household head before deactivating this member'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_deactivating_household_head
BEFORE UPDATE OF active ON members
FOR EACH ROW EXECUTE FUNCTION prevent_deactivating_household_head();

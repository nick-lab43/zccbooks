-- ZCC Books supports one permanent organisation: Zion Christian Church.
UPDATE organisations
SET name = 'Zion Christian Church';

INSERT INTO organisations (id, name)
SELECT gen_random_uuid(), 'Zion Christian Church'
WHERE NOT EXISTS (SELECT 1 FROM organisations);

CREATE OR REPLACE FUNCTION enforce_single_zcc_organisation()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' AND EXISTS (SELECT 1 FROM organisations) THEN
        RAISE EXCEPTION 'Zion Christian Church is the only organisation supported by this system.';
    END IF;
    NEW.name := 'Zion Christian Church';
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_enforce_single_zcc_organisation
BEFORE INSERT OR UPDATE OF name ON organisations
FOR EACH ROW
EXECUTE FUNCTION enforce_single_zcc_organisation();

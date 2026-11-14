-- Extend canonical Cell-based member records with optional personal contact details.
-- Existing records remain valid; profile fields are intentionally nullable.

ALTER TABLE members
    ADD COLUMN phone_number VARCHAR(30),
    ADD COLUMN email VARCHAR(254),
    ADD COLUMN date_of_birth DATE;

CREATE INDEX ix_members_email ON members (organisation_id, LOWER(email))
    WHERE email IS NOT NULL;

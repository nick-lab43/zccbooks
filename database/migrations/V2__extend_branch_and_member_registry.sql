ALTER TABLE branch
    ADD COLUMN district_name VARCHAR(160),
    ADD COLUMN country_code CHAR(2);

CREATE UNIQUE INDEX uq_member_zcc_app_number
    ON member (zcc_app_number)
    WHERE zcc_app_number IS NOT NULL;

CREATE INDEX idx_member_name
    ON member (LOWER(last_name), LOWER(first_name));
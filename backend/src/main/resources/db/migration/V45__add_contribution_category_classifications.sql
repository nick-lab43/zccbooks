-- Optional member-classification eligibility rules for central contribution categories.
CREATE TABLE contribution_category_classifications (
    category_id UUID NOT NULL REFERENCES contribution_categories(id) ON DELETE CASCADE,
    classification_id UUID NOT NULL REFERENCES classifications(id),
    PRIMARY KEY (category_id, classification_id)
);

CREATE INDEX ix_contribution_category_classifications_classification
    ON contribution_category_classifications (classification_id, category_id);

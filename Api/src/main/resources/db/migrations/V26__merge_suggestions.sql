CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;

CREATE TABLE merge_suggestions (
    id                BIGSERIAL PRIMARY KEY,
    manager_id_a      BIGINT NOT NULL REFERENCES managers(id) ON DELETE CASCADE,
    manager_id_b      BIGINT NOT NULL REFERENCES managers(id) ON DELETE CASCADE,
    confidence        VARCHAR(20)  NOT NULL,
    reason            TEXT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'pending',
    reviews_a_at_eval INT          NOT NULL DEFAULT 0,
    reviews_b_at_eval INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    last_evaluated_at TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT merge_suggestions_pair_uq UNIQUE (manager_id_a, manager_id_b),
    CONSTRAINT merge_suggestions_order_chk CHECK (manager_id_a < manager_id_b)
);

CREATE INDEX merge_suggestions_status_idx ON merge_suggestions (status);

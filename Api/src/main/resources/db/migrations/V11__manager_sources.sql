CREATE TABLE IF NOT EXISTS manager_sources (
    id               BIGSERIAL    PRIMARY KEY,
    source_type      VARCHAR(50)  NOT NULL DEFAULT 'filing',
    filing_type      VARCHAR(20),
    source_url       TEXT         NOT NULL UNIQUE,
    accession_no     VARCHAR(25),
    date_collected   DATE         NOT NULL,
    confidence_score NUMERIC(3,2),
    country          VARCHAR(5)   DEFAULT 'US',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_manager_sources_accession
    ON manager_sources(accession_no);

ALTER TABLE career_history
    ADD COLUMN IF NOT EXISTS source_id   BIGINT
        REFERENCES manager_sources(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS date_source VARCHAR(20)
        NOT NULL DEFAULT 'unknown';

CREATE INDEX IF NOT EXISTS idx_career_history_source_id
    ON career_history(source_id);

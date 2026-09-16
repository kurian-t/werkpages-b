-- Where somebody actually worked, as a thing rather than a string.
--
-- The driving case is chains. Ten Walmarts in one city can have ten different managers and ten
-- different cultures, and "Toronto" cannot tell you whether a problem belongs to Walmart or to
-- *that* Walmart. An address column on `managers` could not either: it would be typed differently
-- by every contributor, impossible to group, and attached to the manager rather than to the
-- experience somebody had.
--
-- So a location is a row, shared by everyone who worked there, and a contribution points at it.
--
-- Note what this is NOT: it is not a second kind of company. Walmart stays one company with one
-- slug and one aggregate score. Locations sit underneath it, and the company page slices by them.
CREATE TABLE company_locations (
    id                BIGSERIAL PRIMARY KEY,
    company_id        BIGINT NOT NULL REFERENCES companies(id),

    -- Provenance, never identity. Our own id is the key; this records where the row came from so a
    -- later refresh can recognise it, and so a second source can coexist without a migration.
    source            TEXT,            -- 'overture' | 'geoapify' | 'manual'
    source_place_id   TEXT,
    source_fetched_at TIMESTAMPTZ,

    -- A snapshot, not a pointer. Every field is copied when somebody selects the place, so
    -- rendering a profile never depends on an external dataset and a refresh upstream can never
    -- rewrite what a contribution said.
    display_name      TEXT,            -- "Walmart Supercentre"
    street            TEXT,
    city              VARCHAR(100),
    state             VARCHAR(100),    -- display name, e.g. "Ontario"
    state_code        VARCHAR(10),     -- ISO 3166-2, e.g. "CA-ON"
    country           VARCHAR(100),    -- display name, e.g. "Canada"
    country_code      VARCHAR(2),      -- ISO 3166-1 alpha-2
    postal_code       VARCHAR(20),

    -- Both forms of the subdivision are kept on purpose: the code is stable identity for a filter,
    -- the name is what a reader sees. Storing only one means either the URL breaks when a display
    -- name changes, or the page shows "CA-ON" to a human.

    -- No latitude/longitude. Nothing renders a map, so coordinates here would be permanent product
    -- data with no consumer. They belong in the search corpus, where they earn their place helping
    -- collapse duplicates and rank nearby candidates.

    status            TEXT NOT NULL DEFAULT 'active',  -- 'active' | 'archived_duplicate'
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per physical place per company. Partial on status so that merging two companies, which
-- can bring two rows for the same building together, archives the loser instead of being blocked.
CREATE UNIQUE INDEX company_locations_place
    ON company_locations (company_id, source, source_place_id)
    WHERE source_place_id IS NOT NULL AND status = 'active';

CREATE INDEX company_locations_company
    ON company_locations (company_id)
    WHERE status = 'active';

COMMENT ON TABLE company_locations IS
    'A physical workplace belonging to a company. Snapshotted at selection time: never re-fetched on a read path, so an upstream refresh cannot alter a historical contribution.';
COMMENT ON COLUMN company_locations.status IS
    'archived_duplicate when a company merge found another row for the same place; references are repointed to the survivor first, and undo restores both.';

-- ── The declared ladder, on every contribution ───────────────────────────────────────────────
--
-- Four tables get the same five columns, because location is a property of the contribution rather
-- than of the manager. Brenda manages the Gerrard Walmart, collects fifteen opinions, then moves to
-- Dufferin: the manager row follows her, and those fifteen opinions must not. They happened where
-- they happened.
--
-- `managers` gets them too, but for a different job - the manager's current default location, which
-- is what answers "who works at this store" for somebody with no ratings yet.
--
-- These are deliberately NOT the existing country/state/city columns. Those are filled from
-- Cloudflare headers on the /find path, which means they hold the *searcher's* location, not the
-- manager's - see V16. They stay write-only forever; nothing may display them below country.
ALTER TABLE managers
    ADD COLUMN declared_country    VARCHAR(100),
    ADD COLUMN declared_state      VARCHAR(100),
    ADD COLUMN declared_city       VARCHAR(100),
    ADD COLUMN declared_precision  TEXT,
    ADD COLUMN company_location_id BIGINT REFERENCES company_locations(id);

ALTER TABLE reviews
    ADD COLUMN declared_country    VARCHAR(100),
    ADD COLUMN declared_state      VARCHAR(100),
    ADD COLUMN declared_city       VARCHAR(100),
    ADD COLUMN declared_precision  TEXT,
    ADD COLUMN company_location_id BIGINT REFERENCES company_locations(id);

ALTER TABLE company_reviews
    ADD COLUMN declared_country    VARCHAR(100),
    ADD COLUMN declared_state      VARCHAR(100),
    ADD COLUMN declared_city       VARCHAR(100),
    ADD COLUMN declared_precision  TEXT,
    ADD COLUMN company_location_id BIGINT REFERENCES company_locations(id);

ALTER TABLE interview_reviews
    ADD COLUMN declared_country    VARCHAR(100),
    ADD COLUMN declared_state      VARCHAR(100),
    ADD COLUMN declared_city       VARCHAR(100),
    ADD COLUMN declared_precision  TEXT,
    ADD COLUMN company_location_id BIGINT REFERENCES company_locations(id);

-- How specific the person chose to be, stated rather than inferred from which columns happen to be
-- filled. It is what makes "Kitchener - use city only" a real answer instead of a gap, and it lets
-- the server validate a submission's shape without guessing at intent.
--
-- No CHECK constraint on the values: adding one would make every future rung a migration, and the
-- write paths validate this already. Left as a comment so the vocabulary is discoverable in psql.
COMMENT ON COLUMN managers.declared_precision IS
    'country | state | city | exact. How specific the contributor chose to be. exact means company_location_id is set and the coarse columns were derived from it.';
COMMENT ON COLUMN reviews.declared_precision IS
    'country | state | city | exact - see managers.declared_precision. Snapshotted at submission; a later edit must not re-copy the manager''s current location.';
COMMENT ON COLUMN company_reviews.declared_precision IS
    'country | state | city | exact - see managers.declared_precision.';
COMMENT ON COLUMN interview_reviews.declared_precision IS
    'country | state | city | exact - see managers.declared_precision. Distinct from the inferred `city` column added in V53, which stays write-only.';

CREATE INDEX reviews_company_location           ON reviews (company_location_id)           WHERE company_location_id IS NOT NULL;
CREATE INDEX company_reviews_company_location   ON company_reviews (company_location_id)   WHERE company_location_id IS NOT NULL;
CREATE INDEX interview_reviews_company_location ON interview_reviews (company_location_id) WHERE company_location_id IS NOT NULL;
CREATE INDEX managers_company_location          ON managers (company_location_id)          WHERE company_location_id IS NOT NULL;

-- ── Undo has to be able to reverse a location move ───────────────────────────────────────────
--
-- A merge repoints every contribution at the surviving location before archiving the duplicate.
-- The existing manifest records which company a row moved between, which is not enough to put a
-- location reference back. Both nullable and additive: rows written before this stay valid.
ALTER TABLE company_merge_records
    ADD COLUMN old_location_id BIGINT,
    ADD COLUMN new_location_id BIGINT;

COMMENT ON COLUMN company_merge_records.old_location_id IS
    'Set on entity_type = company_location_ref rows: the location this record pointed at before the merge, so undo can restore it.';

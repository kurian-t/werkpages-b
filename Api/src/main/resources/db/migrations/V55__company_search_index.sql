-- Make the company picker fast, and make `companies` the only thing it reads.
--
-- Measured before this migration, on 50k companies / 150k managers: 297 SECONDS for one keystroke.
-- The cause was a correlated EXISTS over managers whose OR across two columns made the company_id
-- index unusable, so every candidate company scanned the whole managers table. Normalising per row
-- and matching with leading wildcards made it worse.
--
-- Three changes, in order of how much they matter:
--   1. managers leaves the search path entirely. A company is searchable because it is a company,
--      not because a manager happens to reference it. Manager rows stop deciding company identity.
--   2. The normalised forms are stored, not computed per row per keystroke.
--   3. Indexes that the three match shapes can actually use: exact, prefix, and infix.

-- Trigram indexes serve '%term%', which no btree can. Postgres ships this in contrib; managed
-- providers allow it. If this line fails the picker still works, but infix matching degrades to a
-- sequential scan, so it is worth confirming the extension is available before deploying.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ── Stored normalisation ─────────────────────────────────────────────────────────────────────
--
-- Generated rather than trigger-maintained: the database itself guarantees the column is derived,
-- so no code path can write a normalised value that disagrees with the name it came from.
--
-- The cost of that guarantee: changing normalize_company_name() later does NOT recompute stored
-- values. Any future change to the function must ship with a migration that rebuilds these columns
-- and reindexes. That is a deliberate trade - drift becomes a visible migration task rather than an
-- invisible inconsistency.
ALTER TABLE companies
    ADD COLUMN normalized_name TEXT GENERATED ALWAYS AS (normalize_company_name(name)) STORED;

-- The alias column arrived in V54 as a plain column with a BEFORE trigger. Converting it to the
-- same mechanism means one rule for both tables rather than two things to remember. The data is
-- derived, so dropping and re-adding loses nothing.
DROP TRIGGER IF EXISTS company_aliases_normalize_trigger ON company_aliases;
DROP FUNCTION IF EXISTS company_aliases_normalize();
DROP INDEX IF EXISTS company_aliases_company_norm;
DROP INDEX IF EXISTS company_aliases_norm;

ALTER TABLE company_aliases DROP COLUMN normalized_alias;
ALTER TABLE company_aliases
    ADD COLUMN normalized_alias TEXT GENERATED ALWAYS AS (normalize_company_name(alias)) STORED;

-- ── Indexes, one per match shape ─────────────────────────────────────────────────────────────
--
-- text_pattern_ops rather than the default operator class: under any non-C collation a plain btree
-- cannot serve LIKE 'prefix%'. This is the difference between a prefix search using the index and
-- scanning the table.
CREATE INDEX companies_normalized_name_btree
    ON companies (normalized_name text_pattern_ops);
CREATE INDEX company_aliases_normalized_btree
    ON company_aliases (normalized_alias text_pattern_ops);

-- Infix and, later, similarity ranking.
CREATE INDEX companies_normalized_name_trgm
    ON companies USING GIN (normalized_name gin_trgm_ops);
CREATE INDEX company_aliases_normalized_trgm
    ON company_aliases USING GIN (normalized_alias gin_trgm_ops);

-- One company still cannot hold the same alias twice. Deliberately NOT unique across companies:
-- "Summit" and "First Choice" are many real companies, and a global unique index would let
-- whichever one was entered first own the name. Aliases rank candidates; they never assert identity.
CREATE UNIQUE INDEX company_aliases_company_norm
    ON company_aliases (company_id, normalized_alias);

-- RateMyManagers reads this same database but cannot add indexes of its own, and its picker still
-- matches on the raw name. This keeps that path off a sequential scan until it moves to the
-- normalised columns.
CREATE INDEX companies_lower_name_btree
    ON companies (LOWER(name) text_pattern_ops);

ANALYZE companies;
ANALYZE company_aliases;

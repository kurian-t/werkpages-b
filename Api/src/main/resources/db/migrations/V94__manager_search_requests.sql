-- Manager search requests: unverified leads, kept OUT of the managers table.
--
-- Why this exists
-- ---------------
-- A search that cannot find somebody used to write a `managers` row with
-- approval_status = 'pending_approval'. That conflated two different things: a manager submitted
-- for approval, and an unverified search suggesting somebody we might not have. The second is not
-- a person, and storing it as one took a manager id, a slug and a company association for a record
-- that may never become anybody.
--
-- Measured consequence before this change: one individual (Aaron Yip) held eight rows, five of
-- them non-live; 21 people held two publicly visible profiles each with reviews split across them;
-- and names such as "kuran lugo" accumulated 17 rows across 11 companies.
--
-- What this table is NOT
-- ----------------------
-- It does not replace ghost creation. A search that legitimately publishes a ghost still does so.
-- This queue is only for searches that need a human decision.
--
-- It also does not restrict company creation. Company discovery is unchanged: resolved_company_id
-- references whatever the ordinary flow resolved OR created during the search.
CREATE TABLE manager_search_requests (
    id                    BIGSERIAL PRIMARY KEY,

    -- exactly what was typed, preserved verbatim so an admin sees the search, not our reading of it
    first_name            TEXT NOT NULL,
    last_name             TEXT NOT NULL,
    company_text          TEXT NOT NULL,
    title_text            TEXT NOT NULL,

    -- normalised forms; together these are the deduplication key
    name_norm             TEXT NOT NULL,
    company_norm          TEXT NOT NULL,
    title_norm            TEXT NOT NULL,

    -- whatever the ordinary company flow produced, including a company it created just now
    resolved_company_id   BIGINT REFERENCES companies(id) ON DELETE SET NULL,

    -- the public managers shown to the searcher, so the admin sees who we already hold
    candidate_manager_ids BIGINT[] NOT NULL DEFAULT '{}',

    -- why it was queued, and the classifications that produced that decision
    reason                TEXT NOT NULL,
    name_match_class      TEXT NOT NULL,
    best_company_class    TEXT,
    best_title_class      TEXT,

    -- review state. resolution answers the three questions an admin actually has:
    -- a new person, an existing person's additional role, or not verifiable.
    status                TEXT NOT NULL DEFAULT 'open',
    resolution            TEXT,
    resolved_manager_id   BIGINT REFERENCES managers(id) ON DELETE SET NULL,
    reviewed_by           UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at           TIMESTAMPTZ,

    -- deduplication / weight of evidence
    seen_count            INTEGER NOT NULL DEFAULT 1,
    seen_since_review     INTEGER NOT NULL DEFAULT 0,
    first_seen_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT manager_search_requests_status_valid
        CHECK (status IN ('open', 'resolved', 'dismissed')),
    CONSTRAINT manager_search_requests_resolution_valid
        CHECK (resolution IS NULL OR resolution IN ('new_manager', 'existing_role', 'not_verifiable')),
    CONSTRAINT manager_search_requests_name_class_valid
        CHECK (name_match_class IN ('CONFIRMED', 'PLAUSIBLE', 'NONE'))
);

-- THE deduplication guarantee. A repeated search updates the row instead of inserting, and two
-- simultaneous identical searches cannot both insert: the loser's ON CONFLICT turns into the same
-- update. Note this is a constraint on REQUESTS, not on managers - two different John Smiths must
-- remain possible, so managers is deliberately left without any name-based uniqueness.
CREATE UNIQUE INDEX manager_search_requests_dedupe
    ON manager_search_requests (name_norm, company_norm, title_norm);

-- The admin queue reads open requests most-searched first: weight of evidence rather than age.
CREATE INDEX manager_search_requests_queue
    ON manager_search_requests (seen_count DESC, last_seen_at DESC)
    WHERE status = 'open';

-- Finding every request about one person, for the admin's candidate view.
CREATE INDEX manager_search_requests_name
    ON manager_search_requests (name_norm);

-- No searcher identity is stored, deliberately: without one, a notification cannot fire even in
-- principle. A row created by a search must never tell somebody we wrote it, which is the same
-- reasoning that already governs anonymous captures.

-- Unicode-safe name tokenisation, shared by the index and the candidate query.
--
-- The first attempt used regexp_replace(lower(name), '[^a-z0-9 ]', ' ', 'g'). That does not fold
-- accents, it DELETES whatever is not ASCII. Measured against real names:
--
--   'Jose Garcia' (with accents)  ->  {jos, "", garc, a}
--   'Helene Savard' (with accents)->  {h, l, ne, savard}
--   CJK names                     ->  {"", "", ""}        -- no tokens at all, never retrievable
--
-- A name with no tokens cannot be found by any candidate query, so those records would have been
-- invisible to manager search entirely. Accent folding is wanted - somebody typing "Helene" should
-- find "Helene" spelled with accents - but folding and deleting are different operations, and only
-- the first one preserves the name.
--
-- unaccent() is STABLE rather than IMMUTABLE because it reads a dictionary, so it cannot be used
-- in an index expression directly. The wrapper pins the dictionary, which is what makes it safe to
-- index; if the dictionary is ever changed the index must be rebuilt.
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE OR REPLACE FUNCTION immutable_unaccent(text)
RETURNS text AS $$ SELECT public.unaccent('public.unaccent', $1) $$
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE;

-- The ONE definition of a name token. Java's ManagerMatcher.norm() must agree with this exactly:
-- NFKD, drop combining marks, lowercase, punctuation to spaces, keep Unicode letters and digits.
-- A contract test asserts the two produce identical tokens for a list of international names.
CREATE OR REPLACE FUNCTION manager_name_tokens(text)
RETURNS text[] AS $$
    SELECT string_to_array(
             regexp_replace(
               -- the apostrophe JOINS (O'Dell = ODell) and so is removed, not turned into a
               -- space, before every other non-alphanumeric becomes one. Java does the same, in
               -- the same order.
               regexp_replace(
                 replace(replace(immutable_unaccent(lower($1)), '''', ''), '\u2019', ''),
                 '[^[:alnum:] ]', ' ', 'g'),
               '\s+', ' ', 'g'),
             ' ')
$$ LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE;

-- Name-token index for the candidate query.
--
-- MEASURED, not assumed. Against 30,000 managers on a disposable copy:
--
--   without this index   13.06 / 13.09 / 13.06 ms   bitmap scan on the approval_status index,
--                                                   filtering 20,010 rows down to 2,000
--   with this index       1.34 / 1.35 / 1.23 ms     Bitmap Index Scan on managers_name_tokens_gin
--
-- The planner does use it: the Index Cond reproduces this expression exactly, which is why the
-- expression here and the expression in the query must stay character-identical. Change one and
-- the other silently stops being indexed.
--
-- Write cost is negligible by comparison: managers are written a few thousand times in total and
-- searched continuously.
--
-- The query looks for name TOKENS rather than a substring so that "Li" cannot match "Lithgow",
-- and so compound names work from either side: a search for Mary Ann / De La Cruz retrieves
-- "Mary Ann De La Cruz" on the token "mary" or the token "cruz".
CREATE INDEX managers_name_tokens_gin ON managers USING GIN (manager_name_tokens(name));

-- Supporting index for the RANKED, BOUNDED display queries.
--
-- Without it PostgreSQL fetches every candidate and sorts: measured at 100,000 managers where 12%
-- share a surname, "John Smith" took 52.8 ms because 16,000 rows were sorted to return 50. With
-- it the planner index-scans in reviews order and stops at the limit: 2.27 ms, and still 2.27 ms
-- at 1,000,000 managers - the cost stops tracking table size.
--
-- Partial, because only publicly visible managers are ever ranked for display.
CREATE INDEX managers_display_rank ON managers (reviews_count DESC, id)
    WHERE approval_status IN ('approved', 'ghost');

-- Measured end to end on a disposable copy, worst case being a common given name AND a common
-- surname ("John Smith", 12% of rows sharing the surname):
--
--                            100,000 managers     1,000,000 managers
--   decision (EXISTS)              0.6 ms                0.62 ms     <- flat, never truncated
--   confirmed display              2.7 ms               30.3 ms
--   plausible display              2.3 ms                2.27 ms     <- flat
--   rare name, whole query           -                   6.2 ms
--
-- The decision query is deliberately separate from the display queries. Ghost creation is gated on
-- EXISTS, which is exact and unbounded, so a bounded display list can never cause a duplicate to
-- be published. Display is bounded and paginated; the decision is not.

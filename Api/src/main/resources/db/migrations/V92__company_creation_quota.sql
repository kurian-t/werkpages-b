-- A daily cap on how many companies one person, or one address, can bring into existence.
--
-- POST /api/companies is the only place a company is created from a free-text name, and it was the
-- only write in the product with NO per-user limit at all. Managers, reviews and edits are each
-- capped at 10 a day; companies - the one endpoint that mints a public directory row from an
-- arbitrary string - had nothing but the 30/minute per-IP write limiter, which allows 43,200 a day
-- and is per address rather than per account.
--
-- WHY A TABLE RATHER THAN A COLUMN
--
-- `companies` records neither who created a row nor from where, so there is nothing to count. A
-- created_by column would be the obvious shape, but it answers a different question - "who made
-- this" is provenance, kept forever - while this one is "how many has this person made today",
-- which should expire. Keeping them apart means the rate control can be pruned without losing
-- provenance, and adding provenance later does not have to agree with it.
--
-- WHY BOTH USER AND ADDRESS
--
-- The endpoint requires authentication, so every row has a user. The address is recorded as well
-- because twelve accounts from one address would otherwise be twelve times the cap, and signing up
-- is cheap. Either side reaching the limit is enough to refuse.
--
-- THE ADDRESS IS HASHED, NEVER STORED
--
-- Same rule as anonymous_ghost_quota: we need to know whether an address has been busy, never which
-- address it was. A rate control must not become a log of who created what from where.
CREATE TABLE IF NOT EXISTS company_creation_quota (
    id          BIGSERIAL   PRIMARY KEY,
    -- Nullable so a future unauthenticated path can be counted without a schema change. Today the
    -- endpoint is authenticated and this is always set.
    user_id     UUID,
    -- Nullable because an address we cannot identify must not be silently counted as one shared
    -- bucket with every other unidentifiable caller.
    ip_hash     TEXT,
    company_id  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT company_creation_quota_subject CHECK (user_id IS NOT NULL OR ip_hash IS NOT NULL)
);

-- The two lookups the check makes, both bounded by a 24-hour window.
CREATE INDEX IF NOT EXISTS company_creation_quota_user_idx ON company_creation_quota (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS company_creation_quota_ip_idx   ON company_creation_quota (ip_hash, created_at DESC);

-- How many companies this subject has created in the window.
--
-- Counts the USER and the ADDRESS together rather than taking the larger of the two: somebody
-- switching accounts mid-way should not get a fresh allowance, and somebody on a shared address
-- should not be punished for a stranger's twelve. Counting distinct rows attributable to either
-- means one person's twelve is twelve however they are split.
CREATE OR REPLACE FUNCTION company_creation_count(p_user_id UUID, p_ip_hash TEXT, p_hours INT DEFAULT 24)
RETURNS BIGINT AS $$
    SELECT count(*)
    FROM company_creation_quota q
    WHERE q.created_at > now() - make_interval(hours => p_hours)
      AND ( (p_user_id IS NOT NULL AND q.user_id = p_user_id)
         OR (p_ip_hash IS NOT NULL AND q.ip_hash = p_ip_hash) );
$$ LANGUAGE sql STABLE;

-- Pruning. The table is a rate control, not a record: rows older than the window answer no
-- question. Without this it grows forever and the indexes with it.
CREATE OR REPLACE FUNCTION company_creation_quota_sweep() RETURNS BIGINT AS $$
DECLARE removed BIGINT;
BEGIN
    DELETE FROM company_creation_quota WHERE created_at < now() - interval '7 days';
    GET DIAGNOSTICS removed = ROW_COUNT;
    RETURN removed;
END;
$$ LANGUAGE plpgsql;

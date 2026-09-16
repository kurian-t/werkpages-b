-- Make the whole location editable, then seed state from what we already hold.
--
-- These two things ship together on purpose, and in this order.
--
-- V69 promoted country and deliberately left state alone, on the grounds that country is displayed
-- on the profile and correctable through the edit-request flow (manager_edits.new_country, V13)
-- while state is neither. That was the right test - a value becomes publishable when somebody can
-- see it and fix it, not when somebody typed it - but it pointed at the wrong fix. The answer is
-- not to keep state hidden forever; it is to give it the same correction path country already has.
--
-- So: the edit request learns to carry a location, and only then does state become declared.
--
-- City still stays out. /find writes the *searcher's* city onto the manager they searched for
-- (V16, createAutoApproved), so unlike state it is not merely unverified - it is frequently about
-- a different person entirely. Cities arrive when somebody types one, and only then.

-- ── An edit request can now change where somebody works ──────────────────────────────────────
ALTER TABLE manager_edits
    ADD COLUMN new_declared_state      VARCHAR(100),
    ADD COLUMN new_declared_city       VARCHAR(100),
    ADD COLUMN new_declared_precision  TEXT,
    ADD COLUMN new_company_location_id BIGINT REFERENCES company_locations(id);

COMMENT ON COLUMN manager_edits.new_declared_precision IS
    'country | state | city | exact. An edit may make a location more specific - country to city, or city to an exact workplace - or correct one outright.';
COMMENT ON COLUMN manager_edits.new_company_location_id IS
    'Set when the editor picked a specific workplace. The coarse columns are then derived from that row rather than trusted from the request.';

-- ── State becomes declared ───────────────────────────────────────────────────────────────────
--
-- Guarded on declared_precision = 'country', which at this moment means exactly "a row V69 seeded
-- and nobody has answered since". A human answer is never at country precision while also having a
-- state sitting unused next to it, and after this migration the forms collect state directly - so
-- this cannot reach an answer somebody actually gave, now or later.
UPDATE managers
   SET declared_state     = state,
       declared_precision = 'state'
 WHERE state IS NOT NULL
   AND btrim(state) <> ''
   AND declared_precision = 'country'
   AND declared_state IS NULL;

-- Opinions inherit from the manager they are about, the same way and with the same caveat as V69:
-- a manager who has genuinely moved province would have older opinions stamped with the newer one.
-- Province granularity, against no location at all, is worth that.
UPDATE reviews r
   SET declared_state     = m.state,
       declared_precision = 'state'
  FROM managers m
 WHERE r.manager_id = m.id
   AND m.state IS NOT NULL
   AND btrim(m.state) <> ''
   AND r.declared_precision = 'country'
   AND r.declared_state IS NULL;

-- interview_reviews has no state column of its own - V51 added country, V53 added an inferred city
-- and no province was ever collected - so there is nothing here to promote. Those get a state when
-- the form starts asking for one.

-- Where a declared location came from, and a correction to what V69/V70 promoted.
--
-- V69 and V70 seeded declared_country and declared_state onto every manager and every opinion that
-- had them, treating `managers.country` as a value somebody had confirmed. That is true for a
-- manager somebody added through the form, where country is a visible field. It is not true for a
-- manager created by a search: `createAutoApproved` writes the *searcher's* Cloudflare geography
-- onto the manager they were looking for, and that person never saw or agreed to anything.
--
-- Two problems follow, and this migration fixes both.
--
-- 1. Nothing recorded which kind a row was, so "somebody said this experience was in Ontario" and
--    "we inherited Ontario from a ghost's visitor geo" were indistinguishable afterwards. That is
--    exactly the data-quality question worth being able to ask later.
--
-- 2. V70 promoted state for every manager. For a ghost that state is visitor geo, and unlike
--    country it has never been displayed anywhere, so nobody has had the chance to correct it.
--    It is revoked below.
--
-- Country is deliberately NOT revoked for ghosts. It is already displayed on every manager profile
-- today, ghost or not, and already correctable through the edit-request flow - so recording it as
-- declared publishes nothing new. It is simply labelled honestly.

ALTER TABLE managers          ADD COLUMN location_source TEXT;
ALTER TABLE reviews           ADD COLUMN location_source TEXT;
ALTER TABLE company_reviews   ADD COLUMN location_source TEXT;
ALTER TABLE interview_reviews ADD COLUMN location_source TEXT;

COMMENT ON COLUMN managers.location_source IS
    'contributor_declared | company_location | legacy_form_confirmed | legacy_visitor_inferred. How this location was arrived at. Never shown publicly; it exists so data quality can be audited and so a later migration can tell an answer from an inheritance.';

-- ── Label what V69/V70 already wrote ─────────────────────────────────────────────────────────
--
-- A manager somebody submitted through the Add Manager form: country was a visible field on that
-- form, so the value was confirmed. `createManager` is the only path that sets submitted_by without
-- also being a ghost - the search and capture paths (createPending, createCapturedDraft,
-- createSearchPending) set none.
UPDATE managers
   SET location_source = 'legacy_form_confirmed'
 WHERE declared_precision IS NOT NULL
   AND location_source IS NULL
   AND submitted_by IS NOT NULL
   AND approval_status <> 'ghost';

-- Everything else with a declared location got it from a visitor's headers.
UPDATE managers
   SET location_source = 'legacy_visitor_inferred'
 WHERE declared_precision IS NOT NULL
   AND location_source IS NULL;

-- Opinions inherited from their manager, so they inherit the label too.
UPDATE reviews r
   SET location_source = m.location_source
  FROM managers m
 WHERE r.manager_id = m.id
   AND r.declared_precision IS NOT NULL
   AND r.location_source IS NULL;

-- Interview experiences declared their own country directly since V51 - asked for on the form and
-- shown back on the experience - so they are confirmed, not inherited.
UPDATE interview_reviews
   SET location_source = 'legacy_form_confirmed'
 WHERE declared_precision IS NOT NULL
   AND location_source IS NULL;

-- ── Revoke the state promotion where it was never anybody's answer ───────────────────────────
--
-- Drops back to country precision rather than to nothing: the country remains legitimate for the
-- reason given above. Guarded on 'state' so a city or exact answer given since is untouched.
UPDATE managers
   SET declared_state = NULL,
       declared_precision = 'country'
 WHERE location_source = 'legacy_visitor_inferred'
   AND declared_precision = 'state';

UPDATE reviews
   SET declared_state = NULL,
       declared_precision = 'country'
 WHERE location_source = 'legacy_visitor_inferred'
   AND declared_precision = 'state';

-- Rows with a country but no country value at all should not claim a precision.
UPDATE managers SET declared_precision = NULL
 WHERE declared_precision = 'country' AND declared_country IS NULL;
UPDATE reviews SET declared_precision = NULL
 WHERE declared_precision = 'country' AND declared_country IS NULL;

-- Seed the declared ladder at country precision from the country we already publish.
--
-- V68 added the declared columns and left every existing row null, on the reasoning that a value
-- nobody confirmed must not become a public one. That reasoning is right about state and city. It
-- is wrong about country, and leaving country null throws away the one rung we legitimately have:
--
--   * managers.country is already displayed on the profile - flag and name, under the manager's
--     title - so promoting it to declared_country publishes nothing that is not public today;
--   * it is already correctable, through the same edit-request flow that handles company and
--     title, so anybody who sees a wrong country has a way to fix it;
--   * interview_reviews.country is likewise already shown as a chip on each experience.
--
-- The alternative - starting from null everywhere - means the career trajectory and every location
-- facet sit empty until enough people fill in a new optional field, while the data to populate them
-- at country level is sitting in the next column along.
--
-- Deliberately NOT backfilled:
--
--   * state. managers.state is filled from Cloudflare headers and, in Werkpages, has never been
--     rendered on any form or page. Nobody has seen it, so nobody has accepted it.
--   * city. Worse: /find writes the *searcher's* city onto the manager they searched for
--     (see V16 and createAutoApproved), so the column is contaminated by construction.
--
-- Only rows with no declaration are touched, so re-running this cannot overwrite an answer somebody
-- actually gave.

-- ── Managers ─────────────────────────────────────────────────────────────────────────────────
UPDATE managers
   SET declared_country   = country,
       declared_precision = 'country'
 WHERE country IS NOT NULL
   AND btrim(country) <> ''
   AND declared_precision IS NULL;

-- ── Manager opinions ─────────────────────────────────────────────────────────────────────────
--
-- A review's country comes from the manager it is about. This is an inference, and it is worth
-- being explicit about its one failure mode: a manager who has genuinely changed country would have
-- their older opinions stamped with the newer country, because nothing in the data says when the
-- move happened. At country granularity, against the alternative of no location at all, that trade
-- is worth making - and any opinion written from here on carries its own answer instead.
UPDATE reviews r
   SET declared_country   = m.country,
       declared_precision = 'country'
  FROM managers m
 WHERE r.manager_id = m.id
   AND m.country IS NOT NULL
   AND btrim(m.country) <> ''
   AND r.declared_precision IS NULL;

-- ── Interview experiences ────────────────────────────────────────────────────────────────────
--
-- These need no inference: the experience carries its own country, asked for directly since V51 and
-- displayed on the experience itself.
UPDATE interview_reviews
   SET declared_country   = country,
       declared_precision = 'country'
 WHERE country IS NOT NULL
   AND btrim(country) <> ''
   AND declared_precision IS NULL;

-- company_reviews is left alone: it has no country column of its own, and the company it belongs to
-- has no single location to borrow one from. Those start declaring when the form starts asking.

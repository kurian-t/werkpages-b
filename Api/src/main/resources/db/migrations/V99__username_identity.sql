-- ============================================================================
-- V99: username identity
--
-- Stage 4 of the authentication re-architecture. Two changes, both about who a
-- contributor is in public.
--
-- Design and decisions:
--   /home/noragrats/b2-verification/STAGE4_PASSWORD_GRANT_MIGRATION.md 8.1, 8.2
--
-- ── 1. THE UNIQUENESS RULE THE APPLICATION ALREADY ENFORCES ─────────────────
--
-- The application has always compared usernames case-insensitively:
--
--     SELECT ... FROM users WHERE lower(username) = lower($1)
--
-- used both for availability at signup and for resolving a typed username at
-- sign-in. The constraint is `username TEXT UNIQUE`, which in PostgreSQL is
-- CASE-SENSITIVE, and no lower(username) index exists.
--
-- So the database permits JaneDoe and janedoe as two accounts while the
-- application treats them as one identity, and findEmailByUsername returns ONE
-- of them arbitrarily. A latent integrity defect rather than an active one -
-- the application's own check is the stricter of the two, so signup cannot
-- create a collision - and by CLAUDE.md section 13 the invariant belongs here.
--
-- PREFLIGHT, MANDATORY, and it is not optional for a second time:
--     /home/noragrats/b2-verification/preflight/username_ci_collisions.sql
-- A collision makes CREATE UNIQUE INDEX fail mid-deploy and takes the whole
-- transaction with it. The operator ran it on 2026-11-11 and it passed with
-- zero collision groups; it MUST be re-run immediately before the deploy,
-- because a registration in between can introduce one.
--
-- ── 2. A PROVISIONAL USERNAME IS NOT A PUBLIC IDENTITY ──────────────────────
--
-- The social callback creates an account with "user_" plus eight hex
-- characters, and Universal Login registration will do the same: Auth0
-- authenticates somebody we have never seen, and the account row has to exist
-- before they choose a name. Approved, with the condition that such a name must
-- never become a publicly exposed contributor identity.
--
-- Today nothing distinguishes a generated username from a chosen one except
-- matching ^user_[0-9a-f]{8}$, which is a guess about a format rather than a
-- fact about an account - and wrong in the direction that matters, because
-- somebody could choose a name of that shape.
-- ============================================================================

-- ── 1. case-insensitive uniqueness ──────────────────────────────────────────

CREATE UNIQUE INDEX IF NOT EXISTS users_username_ci ON users (lower(username));

COMMENT ON INDEX users_username_ci IS
    'The uniqueness rule the application has always enforced. lower(username), NOT '
    'lower(btrim(username)): the index must normalise exactly the way findEmailByUsername '
    'compares, and that comparison does not trim.';

-- users_username_key STAYS. The new index subsumes it for uniqueness, since two rows with the
-- same exact username share a lower(), but it is still the index that serves exact-match lookups
-- and dropping a constraint on a shared table buys nothing here.

-- ── 2. the provisional-identity flag ────────────────────────────────────────

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS username_is_provisional BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN users.username_is_provisional IS
    'TRUE while the username is one we generated and the contributor has not chosen their own. '
    'Gates public-identity operations. Defaults FALSE so every account that exists today keeps '
    'its public identity: the flag is only set by rows created from the authentication callback '
    'onwards, in the same statement that creates them.';

/*
  DEFAULT FALSE is the safe direction and the whole reason this is additive.

  Existing accounts chose their usernames, so none of them is provisional. An RMM insert that omits
  the column keeps working, which CLAUDE.md section 10 requires of any change to a shared table.

  It must be set in the SAME statement that creates the row, never afterwards, or there is a window
  in which a provisional username is indistinguishable from a chosen one.
*/

-- A provisional name is ours, so it must look like ours. If this ever fails, somebody has started
-- generating names in a different shape and the gate that reads this flag needs to know.
ALTER TABLE users
    ADD CONSTRAINT provisional_username_is_generated
    CHECK (NOT username_is_provisional OR username ~ '^user_[0-9a-f]{8}$');

/*
  The constraint is deliberately one-directional.

  "Provisional implies generated-shaped" is enforceable. The reverse is not: somebody may
  legitimately choose `user_deadbeef`, and refusing it would be refusing a valid name to defend a
  convention. Which is also exactly why the flag exists rather than the regex being read as the
  answer.
*/

-- ── what this migration deliberately does NOT do ────────────────────────────
--
-- No username is renamed, normalised or copied anywhere. Not into Auth0, not
-- between accounts. "Requires Username" stays off and the Auth0 connection is
-- untouched: Auth0 has never authenticated anybody by username, and the field
-- it holds is an email-derived value that nothing in either product reads.
--
-- No account identity changes. auth0_id is what every account's continuity
-- hangs on and is not touched.

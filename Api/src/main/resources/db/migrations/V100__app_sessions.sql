-- ============================================================================
-- V100: opaque, hashed, product-scoped, revocable application sessions
--
-- Stage 5 of the authentication re-architecture. Design and decisions:
--   /home/noragrats/b2-verification/STAGE5_SESSION_DESIGN.md
--
-- ── THE DEFECT THIS CLOSES ──────────────────────────────────────────────────
--
-- Today's session IS the Auth0 access token, in an HttpOnly cookie, for 24
-- hours. `handleSignout` sets `auth_token=; Max-Age=0`, which is an instruction
-- to the browser and nothing more: a copy of that cookie taken beforehand keeps
-- working for the rest of the 24 hours. So signing out does not end a session,
-- banning somebody does not end their session, deleting an account does not end
-- its sessions, and an administrator has no way to end anybody's.
--
-- A session you cannot revoke is not a session. It is a bearer token with a
-- long fuse.
--
-- ── INERT ON PURPOSE ────────────────────────────────────────────────────────
--
-- This migration creates the table and nothing reads it. The issuing and
-- validating code lands separately, so the schema can be deployed and verified
-- before any request depends on it. Nothing in either product changes
-- behaviour when this runs.
-- ============================================================================

CREATE TABLE IF NOT EXISTS app_sessions (
    /*
      SHA-256 OF THE OPAQUE ID. The id itself is never stored.

      A dump of this table - from a backup, a replica, a log, an errant query - then hands over no
      live sessions, which is a real and common exposure route and does not require the attacker to
      have had `users` as well.

      Making the hash the PRIMARY KEY keeps the per-request lookup a single primary-key probe, so
      hashing costs nothing. An earlier draft of this design argued against hashing on the grounds
      that it would need a second index; that was wrong, and this is why.

      SHA-256, unsalted and unstretched, which is correct HERE and nowhere near a password: the
      input is 32 bytes of CSPRNG output, so there is no guessable space for a dictionary to
      search, and the hash runs on every authenticated request.
    */
    id_hash             BYTEA       PRIMARY KEY,

    /*
      WHICH PRODUCT ISSUED IT. A session is valid only to the product that minted it.

      One shared database and one `users` table, so without this column a session minted by
      RateMyManagers would authenticate to WerkPages. Cookies are domain-scoped, so no browser does
      it unprompted; this is what makes a COPIED session refused rather than merely unlikely.

      "Sign out everywhere" is the one operation that deliberately crosses the boundary, because a
      person signing out everywhere means everywhere.
    */
    product             TEXT        NOT NULL CHECK (product IN ('rmm', 'werkpages')),

    -- ON DELETE CASCADE is the account-deletion story, stated once, in the schema. The service
    -- revokes explicitly as well: belt and braces, because deletion is the one path where a
    -- surviving session would be a session belonging to nobody.
    user_id             UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    /*
      TWO CLOCKS, because one is always wrong.

      `expires_at` is idle: pushed forward on use, so ordinary people are not signed out weekly.
      `absolute_expires_at` is not: a session used constantly still ends, which is what bounds the
      damage of a token taken and used quietly.
    */
    expires_at          TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,

    -- Revoked rather than deleted, so "signed out at" survives for support and for the person's
    -- own session list. The next request carrying that cookie is refused because this is not null,
    -- WHOEVER holds it.
    revoked_at          TIMESTAMPTZ,
    revoked_reason      TEXT,

    -- For the session list a person is shown, and for nothing else.
    user_agent          TEXT,
    -- HASHED, never the address. The quota tables already establish this pattern.
    address_hash        BYTEA,

    CONSTRAINT session_absolute_outlives_idle
        CHECK (absolute_expires_at >= expires_at),
    CONSTRAINT session_revocation_has_a_reason
        CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);

COMMENT ON TABLE app_sessions IS
    'Opaque, hashed, product-scoped, revocable application sessions. Inert until the issuing code '
    'lands: nothing reads this table when the migration runs.';

-- Every live session of one person, which is what "sign out everywhere", a ban, a deletion and
-- the session list all ask for. Partial, because a revoked row is never a candidate.
CREATE INDEX IF NOT EXISTS app_sessions_user ON app_sessions (user_id) WHERE revoked_at IS NULL;

-- For the sweep that clears expired rows. Not a correctness index: an expired session is already
-- refused by the lookup's own predicate.
CREATE INDEX IF NOT EXISTS app_sessions_expiry ON app_sessions (expires_at);

-- ── the audit record for administrator-initiated revocation ─────────────────
--
-- Required by the design: when somebody other than the session's owner ends it,
-- the record says who and why. Without this, "an admin can end anybody's
-- session" is a power with no accounting, which is the kind of power that gets
-- used once and then disputed forever.

CREATE TABLE IF NOT EXISTS session_revocation_events (
    id            BIGSERIAL   PRIMARY KEY,
    -- The session ended, by hash. NOT a foreign key: the row it names may be swept away later and
    -- the record of the decision must outlive it.
    id_hash       BYTEA       NOT NULL,
    -- Whose session it was.
    user_id       UUID        NOT NULL,
    -- Who ended it. NULL when the person ended it themselves, which is the ordinary case and not
    -- an auditable event; this table exists for the rest.
    revoked_by    UUID        REFERENCES users(id) ON DELETE SET NULL,
    /*
      SET WHEN THE ACTOR'S OWN ACCOUNT IS ERASED, and the pair is the house pattern: an actor
      reference that may be withdrawn is kept beside a boolean that says it was, exactly as
      role_claims does with canonicalization_actor_withdrawn.

      Without it this table BLOCKED ACCOUNT DELETION. The foreign key nulls `revoked_by` when an
      administrator's account goes, which violated the constraint below, so deleting anybody who
      had ever ended somebody else's session failed outright. The rehearsal caught it on its first
      run, and it is the same shape as issues 16 and 18: a constraint that makes erasure
      impossible is a worse defect than the one it was guarding against.
    */
    actor_erased  BOOLEAN     NOT NULL DEFAULT FALSE,
    reason        TEXT        NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    /*
      An administrator's action must name the administrator, OR record that the name has since
      been erased. A self-service sign-out is recorded with no actor and a reason of 'signout';
      anything else has to account for itself one way or the other.
    */
    CONSTRAINT revocation_by_an_actor_is_named
        CHECK (reason = 'signout' OR revoked_by IS NOT NULL OR actor_erased)
);

/*
  The flag is set by the DATABASE, not by whoever happens to be deleting the account.

  The foreign key's SET NULL fires on its own, with no application code involved, so the only
  place that can keep the pair consistent is here. Leaving it to a caller means the one path that
  forgets produces a row the constraint refuses, during an account deletion, which is the worst
  possible moment to discover it.
*/
CREATE OR REPLACE FUNCTION session_revocation_actor_erased() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.revoked_by IS NOT NULL AND NEW.revoked_by IS NULL THEN
        NEW.actor_erased := TRUE;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER session_revocation_actor_erasure
    BEFORE UPDATE OF revoked_by ON session_revocation_events
    FOR EACH ROW EXECUTE FUNCTION session_revocation_actor_erased();

COMMENT ON TABLE session_revocation_events IS
    'Append-only record of session revocations that were not the owner''s own sign-out. '
    'id_hash is deliberately not a foreign key: the decision outlives the session row.';

CREATE INDEX IF NOT EXISTS session_revocation_events_user
    ON session_revocation_events (user_id, occurred_at DESC);

-- ── what this migration deliberately does NOT do ────────────────────────────
--
-- It does not touch the existing `auth_token` cookie, the router's JWT
-- validation, or `JwtAuthFactory`. Migrating off the access-token cookie is a
-- code change with a rollout of its own, and doing it in the same step as the
-- schema would mean a schema that cannot be deployed independently.

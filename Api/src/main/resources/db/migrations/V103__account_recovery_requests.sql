-- ============================================================================
-- V103: the per-account cooldown for username recovery
--
-- Option C recovery sends a message to an account's OWN address when somebody
-- types that account's username. The design was approved on 2026-11-11 with an
-- explicit condition: the private address must not leak through responses,
-- redirects, logs or timing, AND one person must not be able to mail-bomb
-- another through it.
--
-- Rate limiting by source address does not achieve the second half. A thousand
-- sources each sending one request is a thousand messages to one inbox, every
-- one of them inside any per-source limit. The limit that matters is per
-- ACCOUNT, and an account is a row, so this is a table.
--
-- It records attempts, not successes. A refused attempt still consumed the
-- allowance; otherwise somebody could keep an inbox at exactly the limit for
-- ever by retrying into the cooldown.
-- ============================================================================

CREATE TABLE IF NOT EXISTS account_recovery_requests (
    id            BIGSERIAL PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    /*
      Whether a message was actually handed to the provider.

      Both outcomes are recorded and both consume the allowance, but they are
      worth telling apart when somebody reports that recovery is not working:
      "we never tried" and "we tried and SES refused" are different problems.
    */
    mail_accepted BOOLEAN     NOT NULL DEFAULT FALSE,

    /*
      The SOURCE's hashed address, for monitoring, and nullable because an
      unidentifiable caller must still be recorded.

      Hashed rather than stored: this table exists to protect an inbox, and a
      table that also happened to hold "who asked about this account" in plain
      form would be a nastier disclosure than the one it prevents.
    */
    source_hash   BYTEA
);

-- The cooldown query: the newest attempt for one account.
CREATE INDEX IF NOT EXISTS account_recovery_recent
    ON account_recovery_requests (user_id, requested_at DESC);

-- Monitoring: attempts from one source across many accounts, which is what
-- enumeration looks like from the inside even though the response reveals
-- nothing.
CREATE INDEX IF NOT EXISTS account_recovery_by_source
    ON account_recovery_requests (source_hash, requested_at DESC)
    WHERE source_hash IS NOT NULL;

COMMENT ON TABLE account_recovery_requests IS
    'Attempts at username recovery, for the per-account cooldown that stops one '
    'person mail-bombing another. Per-source rate limiting cannot do this: many '
    'sources sending one request each are many messages to one inbox and no '
    'limit is exceeded.';

-- One automatic ghost per anonymous address per 7 days, enforced where it cannot be cleared.
--
-- ── The hole this closes ────────────────────────────────────────────────────────────────────
--
-- An anonymous first search auto-creates a LIVE, publicly visible manager. The only thing holding
-- "one per visitor" was a localStorage key: clear site data and you get another, forever. The
-- existing rate limiter caps requests per minute, not the total, so one address could publish tens
-- of thousands of public managers a day and a proxy pool multiplies that arbitrarily. The directory
-- is the product; flooding it is how the site goes down.
--
-- ── Why a ROLLING WINDOW and not a lifetime slot ────────────────────────────────────────────
--
-- A lifetime slot per address looks stricter and is worse. Mobile carriers put hundreds of users
-- behind one address (CGNAT); so do offices, universities and VPNs. A lifetime slot means the first
-- person behind that address burns it for everybody else permanently, and every one of them gets a
-- pending row and no tile with no way to understand why.
--
-- A window recovers on its own. The cost is real and worth stating plainly: on a shared address
-- only one visitor per window gets an auto-added manager, and the rest silently get a pending row.
-- That is the trade being made deliberately - a shorter window is kinder to shared addresses, a
-- longer one is harsher on abuse.
--
-- The localStorage key stays as the per-BROWSER rule. This is the per-ADDRESS backstop underneath
-- it, for when somebody clears it.
--
-- ── Why a hash, never the address ───────────────────────────────────────────────────────────
--
-- We only need to know whether an address has had its ghost recently, never which address it was.
-- Storing the hash keeps an abuse control from becoming a record of who searched for whom. The
-- salt lives in configuration, so the table alone does not permit a reverse lookup over the small
-- IPv4 space.
--
-- Rows are swept after 30 days: a hashed address is pseudonymised personal data, not anonymous,
-- and keeping it past its usefulness is not defensible.
--
-- Design and rollout: docs/anonymous-ghost-abuse-hardening.md

CREATE TABLE IF NOT EXISTS anonymous_ghost_quota (
    -- One row per address, refreshed when the window expires, rather than a row per attempt.
    -- Keeps the table small and makes the claim a single statement.
    ip_hash     TEXT PRIMARY KEY,
    manager_id  BIGINT      REFERENCES managers(id) ON DELETE SET NULL,
    claimed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Answers the site-wide circuit breaker ("how many anonymous ghosts in the last hour?") and the
-- retention sweep, without scanning the managers table.
CREATE INDEX IF NOT EXISTS anonymous_ghost_quota_claimed_at
    ON anonymous_ghost_quota (claimed_at DESC);

COMMENT ON TABLE anonymous_ghost_quota IS
    'One automatic ghost per anonymous address per rolling window, keyed by salted IP hash. The '
    'server-side backstop for the localStorage GHOST_KEY, which a visitor can clear. A window '
    'rather than a lifetime slot because carrier-grade NAT puts many users behind one address. '
    'See .ai-corpus/reference/patterns/ghost-pattern.md';

COMMENT ON COLUMN anonymous_ghost_quota.manager_id IS
    'The ghost this claim produced, for auditing. ON DELETE SET NULL rather than CASCADE: an admin '
    'deleting an abusive ghost must not hand its creator a fresh claim, which is what CASCADE '
    'would do.';

-- ============================================================================
-- V98 — server-side OAuth login transactions
-- ============================================================================
--
-- WHY THIS TABLE EXISTS.
--
-- The social login flow accepted an authorization code and exchanged it using the backend's client
-- secret, with nothing tying that code to the browser which began the login. `state` was minted in
-- the frontend, compared in the frontend, and the comparison logged "proceeding anyway" on a
-- mismatch, because sessionStorage is empty in private browsing and hard-failing broke real
-- sign-ins. A value the attacker's own browser holds cannot bind anything, so enforcing it there
-- would not have helped either.
--
-- RFC 9700 requires code-injection protection for every authorization-code client, confidential
-- ones included. This table is where the PKCE verifier and the state live so that neither is ever
-- in the browser's hands.
--
-- WHY A TABLE RATHER THAN A SIGNED COOKIE.
--
-- A signed stateless cookie was designed first and rejected. It cannot be single-use: clearing it
-- is an instruction to the browser, not a guarantee, and a captured value stays valid until it
-- expires. It also breaks multi-tab logins, because a second /start overwrites the first tab's
-- cookie. A row can be consumed exactly once by the database itself, and each tab gets its own.
--
-- WHY A SECOND SECRET, AND NOT JUST `state`.
--
-- Consuming a row atomically proves the transaction was used once. It proves NOTHING about who
-- used it. An attacker who starts their own login, obtains a valid state and code, and induces a
-- victim's browser to complete it would consume the attacker's own row successfully, and the
-- victim ends up signed in as the attacker. That is login CSRF, and the store does not close it.
--
-- `browser_hash` closes it: a secret held only in the browser's cookie, matched in the SAME
-- statement that consumes the row. A valid state and a valid code are not sufficient.

CREATE TABLE oauth_login_transactions (
    -- The OAuth `state`, which Auth0 echoes back on the callback. PRIMARY KEY rather than a
    -- separate id with a unique index: a duplicate state is then impossible by construction
    -- instead of by remembering to check for one.
    state          TEXT        PRIMARY KEY,

    -- sha256 of the browser's cookie secret, NEVER the secret.
    --
    -- Storing the hash means a dump of this table yields nothing a browser could present. The
    -- comparison is an equality test on the hash, so nothing is lost by it.
    browser_hash   BYTEA       NOT NULL,

    -- The PKCE code_verifier. This is the value that must never reach the browser: Auth0 checks
    -- SHA256(verifier) against the challenge recorded for this authorization request, so a code
    -- redeemed without it is refused by Auth0 itself.
    code_verifier  TEXT        NOT NULL,

    -- The Auth0 connection this login was started for, carried so the callback cannot be made to
    -- complete against a different one than the person chose.
    connection     TEXT        NOT NULL,

    -- Where to send them afterwards. Validated as a site-relative path when it is written, so an
    -- open redirect cannot be stored here and replayed later.
    return_to      TEXT        NOT NULL,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Ten minutes in practice. A login takes well under a minute and an Auth0 code is short-lived;
    -- the window exists to bound how long a captured cookie is worth anything.
    expires_at     TIMESTAMPTZ NOT NULL
);

-- For the expiry sweep only. The consumption path hits the primary key.
--
-- There is deliberately no index on browser_hash: it is never searched by itself, only ever as an
-- additional predicate on a primary-key lookup, and an index on it would be a way to ask "which
-- logins belong to this browser", which nothing should need to ask.
CREATE INDEX oauth_login_transactions_expiry
    ON oauth_login_transactions (expires_at);

-- NO FOREIGN KEY TO users, deliberately: nobody is signed in yet. Most rows belong to a person who
-- does not have an account, and some will belong to a person who never finishes making one.
--
-- NO ON DELETE anything: a row leaves exactly two ways, consumed by the callback or removed by the
-- expiry sweep. Nothing else should ever delete one.

COMMENT ON TABLE oauth_login_transactions IS
    'Short-lived server-side OAuth login transactions. One row per pending login, per browser tab. '
    'Consumed atomically by DELETE ... RETURNING on (state, browser_hash) before any token '
    'exchange. Rows are not user data and carry no account reference.';

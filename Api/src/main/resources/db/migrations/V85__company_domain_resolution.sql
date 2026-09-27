-- Company identity: the canonical domain, where it came from, and whether a human confirmed it.
--
-- WHY THIS EXISTS
--
-- Every company logo was found by GUESSING a domain from the name - "Zehrs Markets" becomes
-- zehrsmarkets.com - and handing that guess to a logo provider. A measured sample of 12 companies
-- found 7 guesses wrong. The harmless failures are the 404s, which degrade to a letter tile.
--
-- The dangerous failures are the ones that SUCCEED. "Lime" guesses lime.com, which is a real and
-- entirely different company, so the page rendered a polished, authoritative, wrong logo. That is
-- a data-integrity problem wearing a cosmetic disguise, and it was reported three times before
-- anybody worked out what it was.
--
-- Identity is therefore resolved once, stored here, and every logo provider reads it. A guessed
-- domain is never sent to a provider again.
--
-- THE SOURCE ENUM
--
--   GUESSED             legacy only. Never usable for rendering - kept so the old value is
--                       auditable rather than silently rewritten.
--   LOGODEV             resolved by Logo.dev Search.
--   BRANDFETCH          resolved by Brandfetch Search.
--   CLEARBIT            resolved by Clearbit autocomplete.
--   RESOLVER_CONSENSUS  two or more independent resolvers returned the SAME domain. The
--                       strongest automatic signal available, and stronger than any single
--                       resolver's own confidence score.
--   ADMIN               a human decided. Outranks everything, forever, and is never overwritten
--                       by an automatic pass.
--
-- domain_verified_at is NOT what makes a domain usable. An automatically resolved domain is
-- usable with verified_at NULL; the column records HUMAN confirmation, which is a separate fact.
-- Conflating the two would have left every resolved domain unrenderable.
--
-- Why consensus rather than trusting the best resolver: measured, no single resolver is reliable.
-- Clearbit gets Zehrs right and Lime wrong; Brandfetch gets Lime right and Zehrs wrong. Where two
-- agree, both were right. And a high confidence score does not mean a correct IDENTITY - Brandfetch
-- returns google.com for "Google DeepMind" at 1.00, which is a real domain for the wrong entity.
-- Disagreement goes to a human instead of being resolved by majority.

ALTER TABLE companies ADD COLUMN domain_source TEXT
    CHECK (domain_source IN ('GUESSED', 'LOGODEV', 'BRANDFETCH', 'CLEARBIT',
                             'RESOLVER_CONSENSUS', 'GUESS_VERIFIED', 'ADMIN'));

ALTER TABLE companies ADD COLUMN domain_verified_at TIMESTAMPTZ;

-- Where a company sits in the resolution pipeline. PENDING_REVIEW is the queue an admin works.
ALTER TABLE companies ADD COLUMN domain_resolution_state TEXT NOT NULL DEFAULT 'UNRESOLVED'
    CHECK (domain_resolution_state IN ('UNRESOLVED', 'PENDING_REVIEW', 'RESOLVED'));

ALTER TABLE companies ADD COLUMN domain_resolved_at TIMESTAMPTZ;

-- Brandfetch's usable asset is a SIGNED url, not one we can build from a domain. The signature
-- carries an expiry roughly 24h out - measured, not assumed - so the url is cached data with a
-- shelf life, and the brand id is the only stable part. Storing both lets a sweep refresh the url
-- without re-resolving the company's identity.
ALTER TABLE companies ADD COLUMN brandfetch_brand_id TEXT;
ALTER TABLE companies ADD COLUMN brandfetch_icon_url TEXT;
ALTER TABLE companies ADD COLUMN brandfetch_icon_expires_at TIMESTAMPTZ;

COMMENT ON COLUMN companies.domain_source IS
    'Where the domain came from. GUESSED is legacy and never renders. ADMIN always wins.';
COMMENT ON COLUMN companies.domain_verified_at IS
    'When a HUMAN confirmed the domain. NULL for automatically resolved domains, which are still usable.';
COMMENT ON COLUMN companies.brandfetch_icon_expires_at IS
    'Brandfetch signs its icon urls with a ~24h expiry. Past this, re-resolve before use.';

-- Existing domains came through findOrCreate(name, domain, logo_url), whose only caller supplying
-- a domain is the company picker - and the picker's domains come from the Clearbit autocomplete
-- proxy in handleSuggestCompanies. That provenance is traceable rather than assumed, so these are
-- CLEARBIT and keep rendering. Anything an admin later corrects becomes ADMIN and outranks it.
UPDATE companies
   SET domain_source           = 'CLEARBIT',
       domain_resolution_state = 'RESOLVED',
       domain_resolved_at      = COALESCE(updated_at, created_at, now())
 WHERE NULLIF(domain, '') IS NOT NULL;

-- The resolution queue reads exactly these two predicates, on every pass.
CREATE INDEX companies_domain_unresolved
    ON companies (id)
    WHERE domain_resolution_state = 'UNRESOLVED';

CREATE INDEX companies_domain_pending_review
    ON companies (id)
    WHERE domain_resolution_state = 'PENDING_REVIEW';

-- What each resolver said, kept per company so an admin reviewing a disagreement can see the
-- evidence rather than a verdict. Also the audit trail for a consensus: which resolvers agreed.
CREATE TABLE company_domain_candidates (
    id           BIGSERIAL PRIMARY KEY,
    company_id   BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    resolver     TEXT   NOT NULL CHECK (resolver IN ('LOGODEV', 'BRANDFETCH', 'CLEARBIT', 'GUESS_VERIFIED')),
    domain       TEXT,
    brand_id     TEXT,
    icon_url     TEXT,
    confidence   NUMERIC(5,4),
    resolved_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One current answer per resolver per company. A re-run replaces rather than accumulates.
    UNIQUE (company_id, resolver)
);

CREATE INDEX company_domain_candidates_company ON company_domain_candidates (company_id);

COMMENT ON TABLE company_domain_candidates IS
    'What each resolver returned for a company. The evidence behind a RESOLVER_CONSENSUS, and '
    'what an admin reads when resolvers disagree.';

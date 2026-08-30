-- Company aliases: the other names one company is known by.
--
-- The duplicate problem this solves: someone types "Crumbl Cookies", the picker has only ever
-- indexed the canonical name "Crumbl", finds nothing, and a second company is born. Aliases give
-- the search something to match so the existing company is offered instead.
--
-- An alias is NOT a company. It creates no identity of its own, it is never a row anyone can
-- attach a manager to, and matching one must never silently assert that two companies are the
-- same. It makes a company DISCOVERABLE; a human still decides anything stronger than that.
--
-- Owned by Werkpages because Werkpages owns the shared migration history. RateMyManagers carries a
-- byte-identical copy of this file purely so its own integration tests build a schema resembling
-- production; its Flyway is disabled in the shared deployment, exactly as V1-V43 already work.

-- ── Normalisation ────────────────────────────────────────────────────────────────────────────
--
-- Same idea as normalize_role_title() in V49, deliberately NOT the same rules. Role titles and
-- company names need different treatment: a role never ends in "Inc.", and "&" in a company name
-- ("Johnson & Johnson", "Marks and Spencer") is a spelling choice rather than a word.
--
-- IMMUTABLE so it can back a functional index. It must stay that way: change the body and every
-- index built on it silently disagrees with freshly computed values until reindexed.
CREATE OR REPLACE FUNCTION normalize_company_name(p_name TEXT) RETURNS TEXT AS $$
DECLARE
    v TEXT;
    v_stripped TEXT;
BEGIN
    IF p_name IS NULL THEN RETURN NULL; END IF;

    v := lower(trim(p_name));

    -- Accent folding without the unaccent extension, which is not installed here. Covers the Latin
    -- range that actually appears in company names ("Nestlé", "Danone Société", "Ørsted").
    v := translate(v,
                   'áàâäãåāæçćčéèêëēėęğíìîïīįłñńóòôöõøōœśšßúùûüūýÿžźż',
                   'aaaaaaaaccceeeeeeegiiiiiilnnooooooooosssuuuuuyyzzz');

    -- "&" is the same word as "and" as far as a search is concerned.
    v := regexp_replace(v, '\s*&\s*', ' and ', 'g');

    -- Punctuation carries no meaning in a company name: "Acme, Inc." and "Acme Inc" are one thing.
    v := regexp_replace(v, '[^a-z0-9\s]', ' ', 'g');
    v := regexp_replace(v, '\s+', ' ', 'g');
    v := trim(v);

    -- Legal and trading suffixes, stripped only as whole trailing words. Anchored at the end
    -- rather than replaced anywhere, so "Coca Cola" keeps its "co" and "Incoterms" keeps its
    -- "inc". Applied repeatedly because real names stack them ("Example Holdings Ltd Inc").
    v_stripped := v;
    FOR i IN 1..3 LOOP
        v_stripped := regexp_replace(
            v_stripped,
            '\s+(incorporated|inc|llc|llp|lp|ltd|limited|corporation|corp|company|co|plc|gmbh|ag|nv|bv|pty|srl|spa|sarl|sas|oy|ab|asa|as|kk|kg|holdings|group)$',
            '');
    END LOOP;

    -- A company genuinely named "Co" or "Group" would normalise to nothing, which would match
    -- every other empty string. Keep the un-stripped form rather than produce a universal key.
    IF v_stripped IS NULL OR v_stripped = '' THEN
        RETURN v;
    END IF;

    RETURN v_stripped;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- ── The alias table ──────────────────────────────────────────────────────────────────────────

CREATE TABLE company_aliases (
    id               BIGSERIAL   PRIMARY KEY,
    company_id       BIGINT      NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    alias            TEXT        NOT NULL,
    normalized_alias TEXT        NOT NULL,
    -- What kind of name this is. MERGED_NAME is written by the merge engine in phase 3; the rest
    -- are entered by an admin or imported. Constrained rather than free text so the vocabulary
    -- cannot drift into six spellings of "legal name".
    alias_type       TEXT        NOT NULL DEFAULT 'COMMON_NAME'
                     CHECK (alias_type IN ('MERGED_NAME', 'LEGAL_NAME', 'TRADE_NAME',
                                           'ABBREVIATION', 'FORMER_NAME', 'COMMON_NAME')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Keep normalized_alias derived rather than supplied. The same discipline as managers.title_
-- normalized in V49: a caller that forgets to normalise would insert an alias that can never be
-- found, and the failure would be silent.
CREATE OR REPLACE FUNCTION company_aliases_normalize() RETURNS TRIGGER AS $$
BEGIN
    NEW.normalized_alias := normalize_company_name(NEW.alias);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER company_aliases_normalize_trigger
BEFORE INSERT OR UPDATE OF alias ON company_aliases
FOR EACH ROW EXECUTE FUNCTION company_aliases_normalize();

-- One company cannot hold the same name twice.
CREATE UNIQUE INDEX company_aliases_company_norm ON company_aliases (company_id, normalized_alias);

-- Deliberately NOT unique across companies. "Summit", "First Choice" and "ABC" are genuinely many
-- different companies, and a unique index here would assert that the first one to claim a name
-- owns it. Aliases rank candidates; they never establish identity.
CREATE INDEX company_aliases_norm ON company_aliases (normalized_alias);

-- The canonical name is searched through the same normalisation, so "Acme Inc." finds "Acme".
CREATE INDEX companies_normalized_name ON companies (normalize_company_name(name));

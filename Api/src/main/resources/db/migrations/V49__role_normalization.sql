-- Role normalization.
--
-- Titles are free text, so the same job arrives as "Senior Manager", "Sr. Manager", "sr mgr",
-- "Snr. Manager" and a dozen other spellings. That breaks grouping, filtering, analytics, and
-- duplicate detection — two records for one person look unrelated when their titles differ only
-- in punctuation.
--
-- The split of responsibility here matters:
--
--   * The DATABASE owns string normalization. Managers are inserted from five different code
--     paths across two applications, plus backfills and the occasional psql session. A trigger is
--     the only place that catches all of them; V47 exists because a cache that relied on every
--     caller remembering to update it drifted in production.
--
--   * The APPLICATION owns classification (which family, which seniority). That is judgement, it
--     will be refined repeatedly, and it needs real tests — none of which plpgsql is good at.
--
--   * role_aliases holds the result, keyed by the NORMALIZED title rather than by manager. There
--     are a few thousand distinct titles behind any number of managers, so classification runs
--     once per title, and improving a rule updates every manager that shares it without a
--     backfill. Correcting a bad mapping is a one-row UPDATE.
--
-- The manager's own `title` is never modified. It is what the reviewer wrote, it is what gets
-- displayed, and company-specific titles ("Group Product Manager") carry real meaning.

-- ── String normalization ─────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION normalize_role_title(p_title TEXT) RETURNS TEXT AS $$
DECLARE
    result TEXT;
BEGIN
    IF p_title IS NULL THEN RETURN NULL; END IF;

    -- Fold case, then reduce every run of non-alphanumerics to a single space. This is what
    -- collapses "Sr. Manager", "Sr Manager" and "SR/Manager" onto the same token stream.
    result := lower(trim(p_title));
    result := regexp_replace(result, '[^a-z0-9]+', ' ', 'g');
    result := trim(regexp_replace(result, '\s+', ' ', 'g'));

    IF result = '' THEN RETURN NULL; END IF;

    -- Expand abbreviations on word boundaries only, so "senior" is not produced from inside
    -- another word. Order matters where one expansion feeds another.
    result := regexp_replace(result, '\msnr\M',    'senior',           'g');
    result := regexp_replace(result, '\msr\M',     'senior',           'g');
    result := regexp_replace(result, '\mjr\M',     'junior',           'g');
    result := regexp_replace(result, '\mmgr\M',    'manager',          'g');
    result := regexp_replace(result, '\mmgmt\M',   'management',       'g');
    result := regexp_replace(result, '\mdir\M',    'director',         'g');
    result := regexp_replace(result, '\mvp\M',     'vice president',   'g');
    result := regexp_replace(result, '\msvp\M',    'senior vice president', 'g');
    result := regexp_replace(result, '\mevp\M',    'executive vice president', 'g');
    result := regexp_replace(result, '\mavp\M',    'assistant vice president', 'g');
    result := regexp_replace(result, '\masst\M',   'assistant',        'g');
    result := regexp_replace(result, '\massoc\M',  'associate',        'g');
    result := regexp_replace(result, '\mexec\M',   'executive',        'g');
    result := regexp_replace(result, '\meng\M',    'engineering',      'g');
    result := regexp_replace(result, '\mengr\M',   'engineering',      'g');
    result := regexp_replace(result, '\mdev\M',    'development',      'g');
    result := regexp_replace(result, '\msw\M',     'software',         'g');
    result := regexp_replace(result, '\mops\M',    'operations',       'g');
    result := regexp_replace(result, '\mhr\M',     'human resources',  'g');
    result := regexp_replace(result, '\mqa\M',     'quality assurance','g');
    result := regexp_replace(result, '\mba\M',     'business analyst', 'g');
    result := regexp_replace(result, '\mcs\M',     'customer success', 'g');
    result := regexp_replace(result, '\macct\M',   'account',          'g');
    result := regexp_replace(result, '\mtech\M',   'technical',        'g');
    result := regexp_replace(result, '\madmin\M',  'administrative',   'g');
    result := regexp_replace(result, '\mgm\M',     'general manager',  'g');
    result := regexp_replace(result, '\mceo\M',    'chief executive officer',  'g');
    result := regexp_replace(result, '\mcto\M',    'chief technology officer', 'g');
    result := regexp_replace(result, '\mcfo\M',    'chief financial officer',  'g');
    result := regexp_replace(result, '\mcoo\M',    'chief operating officer',  'g');
    result := regexp_replace(result, '\mcmo\M',    'chief marketing officer',  'g');
    result := regexp_replace(result, '\mcio\M',    'chief information officer','g');
    result := regexp_replace(result, '\mcpo\M',    'chief product officer',    'g');
    result := regexp_replace(result, '\mchro\M',   'chief human resources officer', 'g');
    -- "pm" is deliberately NOT expanded. It means Product Manager to some people and Project
    -- Manager to others, and those are different jobs. Guessing here would silently merge two
    -- populations; leaving it alone lets classification treat it as its own ambiguous token.

    result := trim(regexp_replace(result, '\s+', ' ', 'g'));
    RETURN NULLIF(result, '');
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- ── managers.title_normalized ────────────────────────────────────────────────

ALTER TABLE managers ADD COLUMN title_normalized TEXT;

CREATE OR REPLACE FUNCTION managers_normalize_title_trigger() RETURNS trigger AS $$
BEGIN
    NEW.title_normalized := normalize_role_title(NEW.title);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS managers_normalize_title ON managers;

-- BEFORE, so the value is written in the same statement rather than requiring a second UPDATE,
-- and on every INSERT regardless of which of the five insert paths ran.
CREATE TRIGGER managers_normalize_title
BEFORE INSERT OR UPDATE OF title ON managers
FOR EACH ROW EXECUTE FUNCTION managers_normalize_title_trigger();

UPDATE managers SET title_normalized = normalize_role_title(title) WHERE title IS NOT NULL;

CREATE INDEX managers_title_normalized_idx ON managers (title_normalized)
    WHERE title_normalized IS NOT NULL;

-- ── role_aliases ─────────────────────────────────────────────────────────────
-- One row per distinct normalized title. Managers join to it rather than storing a copy, so a
-- rule change or a manual correction takes effect everywhere at once.

CREATE TABLE role_aliases (
    title_normalized TEXT PRIMARY KEY,

    -- What the job does. Kept deliberately coarse: a long list would be mostly unpopulated and
    -- would push judgement calls onto whoever wrote the rule.
    role_family TEXT CHECK (role_family IN (
        'engineering', 'product', 'design', 'data', 'it',
        'sales', 'marketing', 'customer_support', 'operations',
        'finance', 'hr', 'legal', 'project_management',
        'research', 'education', 'healthcare', 'general', 'other'
    )),

    -- How senior it is. Separate from family because "Senior Manager" conflates the two, and one
    -- combined enum would need every family multiplied by every level.
    seniority TEXT CHECK (seniority IN (
        'lead', 'manager', 'senior_manager', 'director', 'vp', 'executive'
    )),

    -- Where the classification came from, so a later AI pass never silently overwrites a human
    -- correction and we can measure how much of the corpus the rules actually cover.
    source TEXT NOT NULL DEFAULT 'rule' CHECK (source IN ('rule', 'ai', 'manual')),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Drives the "which titles still need classifying" query and family/seniority filtering.
CREATE INDEX role_aliases_family_idx    ON role_aliases (role_family)  WHERE role_family IS NOT NULL;
CREATE INDEX role_aliases_seniority_idx ON role_aliases (seniority)    WHERE seniority IS NOT NULL;

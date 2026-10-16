-- A location somebody typed, which the corpus could not supply.
--
-- Location is mandatory on every contribution form. Choosing an autocomplete suggestion is not,
-- and never was meant to be: a workplace the corpus has never held, a query it answers with
-- nothing, or a corpus that is simply unreachable must not be the reason somebody cannot say
-- where they worked.
--
-- WHY NEW COLUMNS RATHER THAN REUSING THE EXISTING ONES
--
-- The obvious shortcut is to parse the typed string into declared_city / declared_state and store
-- it like any other declaration. That is precisely the thing the location model forbids: it would
-- turn "Waterlooo" into a confirmed city, indistinguishable from one the corpus corroborated, and
-- publish it as fact about a real manager. declared_precision describes how SPECIFIC an answer is;
-- it cannot also describe how TRUSTED it is, because those vary independently - a manual answer
-- can be exact, and a corpus answer can be country-level.
--
--   declared_source  where the answer came from, and so what may be claimed about it
--   declared_text    what the person actually wrote, kept verbatim
--
-- Both nullable, so every existing row and every existing INSERT that omits them keeps working.
-- NULL source means "written before the distinction existed", which is honest: those rows came
-- from the picker, but nothing recorded that at the time and this migration will not invent it.
--
-- declared_precision stays NULLABLE for a manual row. A bare typed answer corroborates no rung of
-- the ladder, and writing one in anyway would be the same fabrication by another route.

ALTER TABLE managers          ADD COLUMN declared_source TEXT,
                              ADD COLUMN declared_text   TEXT;
ALTER TABLE reviews           ADD COLUMN declared_source TEXT,
                              ADD COLUMN declared_text   TEXT;
ALTER TABLE company_reviews   ADD COLUMN declared_source TEXT,
                              ADD COLUMN declared_text   TEXT;
ALTER TABLE interview_reviews ADD COLUMN declared_source TEXT,
                              ADD COLUMN declared_text   TEXT;

-- Only the two values the application writes. A CHECK rather than an enum because the set is
-- small, unlikely to grow, and a new value should be a deliberate migration rather than an
-- application-level accident.
ALTER TABLE managers          ADD CONSTRAINT managers_declared_source_valid
    CHECK (declared_source IS NULL OR declared_source IN ('corpus', 'manual'));
ALTER TABLE reviews           ADD CONSTRAINT reviews_declared_source_valid
    CHECK (declared_source IS NULL OR declared_source IN ('corpus', 'manual'));
ALTER TABLE company_reviews   ADD CONSTRAINT company_reviews_declared_source_valid
    CHECK (declared_source IS NULL OR declared_source IN ('corpus', 'manual'));
ALTER TABLE interview_reviews ADD CONSTRAINT interview_reviews_declared_source_valid
    CHECK (declared_source IS NULL OR declared_source IN ('corpus', 'manual'));

-- A manual answer must carry the words it was given. Without this a row could claim to be manual
-- while holding nothing a reader could see, which is a location that exists only as a flag.
ALTER TABLE managers          ADD CONSTRAINT managers_manual_has_text
    CHECK (declared_source <> 'manual' OR btrim(coalesce(declared_text, '')) <> '');
ALTER TABLE reviews           ADD CONSTRAINT reviews_manual_has_text
    CHECK (declared_source <> 'manual' OR btrim(coalesce(declared_text, '')) <> '');
ALTER TABLE company_reviews   ADD CONSTRAINT company_reviews_manual_has_text
    CHECK (declared_source <> 'manual' OR btrim(coalesce(declared_text, '')) <> '');
ALTER TABLE interview_reviews ADD CONSTRAINT interview_reviews_manual_has_text
    CHECK (declared_source <> 'manual' OR btrim(coalesce(declared_text, '')) <> '');

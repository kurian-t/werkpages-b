-- ROLLBACK FOR V118. READ THE WARNING BEFORE RUNNING IT.
--
-- V118 attributed legacy ratings to the company their own submitted name resolves to, so the
-- company page's Manager opinions block stops reading zero on a company that has ratings.
--
-- THIS SCRIPT REVERTS MORE THAN V118 DID, AND THERE IS NO WAY FOR IT NOT TO.
--
-- V118 did not record which rows it changed, so this cannot target them. It reverts EVERY legacy
-- rating whose attribution came from one of the two mechanisms V118 shares with the V113 trigger,
-- which means it also undoes attribution that the trigger applied to ratings written since. Those
-- ratings would then read as unattributed until something attributes them again, and nothing will:
-- the trigger only fires on INSERT.
--
-- So this is a last resort for a deployment that has to be put back, not a tidy undo. If the
-- problem is one wrong row, fix that row.
--
-- WHAT IT DOES NOT TOUCH
--
-- Ratings attributed through a claim ('role') or a kept role ('withdrawn_role'). Those are not
-- V118's doing and reverting them would break reviews_attribution_follows_basis.

BEGIN;

ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_ins;
ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_upd_new;
ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_upd_old;
ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_del;

UPDATE reviews
   SET attributed_company_id = NULL,
       attribution_basis     = 'none'
 WHERE role_basis = 'legacy'
   AND claim_id IS NULL
   AND attribution_basis IN ('submitted', 'inferred');

-- The trigger function goes back to holding its own copy of the rules, because that is what V113
-- shipped and a rollback that leaves new code behind is not a rollback. legacy_rating_employer()
-- is left in place: it is additive, nothing else reads it, and dropping it would fail while this
-- version of attribute_legacy_rating() still refers to it.
CREATE OR REPLACE FUNCTION attribute_legacy_rating() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_companies INT;
    v_company   BIGINT;
BEGIN
    IF NEW.role_basis <> 'legacy'
       OR NEW.attribution_basis <> 'none'
       OR NEW.attributed_company_id IS NOT NULL
       OR NEW.claim_id IS NOT NULL
       OR NEW.weight THEN
        RETURN NEW;
    END IF;

    IF NEW.worked_from IS NOT NULL THEN
        SELECT count(DISTINCT ch.company_id), min(ch.company_id)
          INTO v_companies, v_company
          FROM career_history ch
          JOIN companies c ON c.id = ch.company_id AND c.status <> 'merged'
         WHERE ch.manager_id = NEW.manager_id
           AND ch.state = 'accepted'
           AND ch.company_id IS NOT NULL
           AND COALESCE(ch.start_day, '-infinity'::date)
               <= COALESCE(NEW.worked_until, 'infinity'::date)
           AND COALESCE(ch.end_day, 'infinity'::date) >= NEW.worked_from;

        IF v_companies = 1 THEN
            NEW.attributed_company_id := v_company;
            NEW.attribution_basis     := 'inferred';
            RETURN NEW;
        END IF;
    END IF;

    SELECT count(DISTINCT ch.company_id), min(ch.company_id)
      INTO v_companies, v_company
      FROM career_history ch
      JOIN companies c ON c.id = ch.company_id AND c.status <> 'merged'
     WHERE ch.manager_id = NEW.manager_id
       AND ch.state = 'accepted'
       AND ch.company_id IS NOT NULL;

    IF v_companies = 1 THEN
        NEW.attributed_company_id := v_company;
        NEW.attribution_basis     := 'inferred';
        RETURN NEW;
    END IF;

    IF NEW.manager_company IS NOT NULL AND btrim(NEW.manager_company) <> '' THEN
        SELECT count(*), min(c.id)
          INTO v_companies, v_company
          FROM companies c
         WHERE c.status <> 'merged'
           AND lower(btrim(c.name)) = lower(btrim(NEW.manager_company));

        IF v_companies = 1 THEN
            NEW.attributed_company_id := v_company;
            NEW.attribution_basis     := 'submitted';
            RETURN NEW;
        END IF;
    END IF;

    RETURN NEW;
END $$;

SELECT refresh_company_employment_stats();

ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_ins;
ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_upd_new;
ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_upd_old;
ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_del;

DO $$
DECLARE
    v_disabled TEXT;
BEGIN
    SELECT string_agg(tgname, ', ') INTO v_disabled
      FROM pg_trigger
     WHERE tgrelid = 'reviews'::regclass
       AND tgname LIKE 'reviews_employment_stats%'
       AND tgenabled = 'D';

    IF v_disabled IS NOT NULL THEN
        RAISE EXCEPTION 'rollback would leave these triggers disabled: %', v_disabled;
    END IF;
END $$;

-- Flyway still holds V118 in its history. Removing the row is the caller's decision, and it only
-- makes sense if the file is also being removed from the stream; otherwise the next boot applies
-- it again, which is usually the point of rolling back.
--   DELETE FROM flyway_schema_history WHERE version = '118';

COMMIT;

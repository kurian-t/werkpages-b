-- Phase 4: companies that belong to other companies.
--
-- Zehrs is owned by Loblaw. They are two real companies, not one company with two names, and the
-- difference from a duplicate is the whole point: a Zehrs store manager is not a Loblaw corporate
-- manager, and merging them would destroy exactly the signal people come here for.
--
-- So this is deliberately NOT the merge mechanism, and merges are deliberately NOT recorded here.
-- A duplicate means "there was only ever one company"; a relationship means "these are two
-- companies and one owns the other". Putting both in one table with a type column looks tidy and
-- means every query has to remember which type it wants - and the day one forgets, a subsidiary
-- silently disappears into its parent.
--
-- A child keeps everything: its own page, managers, ratings, interview data and search presence.
-- The relationship adds navigation, and later an explicitly labelled group metric. It never
-- changes what a company's own rating means.

CREATE TABLE company_relationships (
    id                BIGSERIAL   PRIMARY KEY,

    -- Direction lives in the column names rather than in a type flag. There is exactly one way to
    -- write "Zehrs is part of Loblaw", so no second row can contradict the first by expressing the
    -- inverse. The other direction is a query against the same row, not another row.
    child_company_id  BIGINT      NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    parent_company_id BIGINT      NOT NULL REFERENCES companies(id) ON DELETE CASCADE,

    -- Constrained rather than free text: without this the vocabulary drifts into six spellings of
    -- "subsidiary" and no query can rely on any of them. The UI shows "Part of Loblaw" regardless;
    -- the distinction is for us, not the reader.
    relationship_type TEXT        NOT NULL DEFAULT 'SUBSIDIARY_OF'
                      CHECK (relationship_type IN ('SUBSIDIARY_OF', 'BRAND_OF', 'DIVISION_OF',
                                                   'OWNED_BY', 'FRANCHISE_OF', 'JOINT_VENTURE_OF')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- No company is part of itself.
    CONSTRAINT company_relationships_not_self CHECK (child_company_id <> parent_company_id)
);

-- One parent per company.
--
-- The real world has joint ventures with two owners, and this forbids them. That is a deliberate
-- V1 choice: every surface that reads this answers the question "what is this company part of?",
-- which has no sensible answer when there are two. Relaxing it later means dropping this index and
-- deciding what those surfaces should say - a conversation worth having when a real joint venture
-- turns up, rather than modelling for one now and leaving every reader ambiguous in the meantime.
CREATE UNIQUE INDEX company_relationships_one_parent ON company_relationships (child_company_id);
CREATE INDEX company_relationships_parent ON company_relationships (parent_company_id);

-- ── Cycles ───────────────────────────────────────────────────────────────────────────────────
--
-- A → B → C → A is nonsense, and it is worse than nonsense in code: the recursive queries that
-- walk this tree would never terminate. The CHECK above stops the one-step case only, so the
-- general case is enforced here, in the database, where no future code path can route around it.
CREATE OR REPLACE FUNCTION company_relationships_reject_cycles() RETURNS TRIGGER AS $$
DECLARE
    cycles INT;
BEGIN
    -- Walk up from the proposed parent. If the child appears anywhere above it, the new row would
    -- close a loop. The depth cap is a backstop: with this trigger in place a cycle cannot exist,
    -- but a runaway recursion in a trigger is a bad way to discover otherwise.
    WITH RECURSIVE ancestors AS (
        SELECT parent_company_id AS id, 1 AS depth
        FROM company_relationships
        WHERE child_company_id = NEW.parent_company_id

        UNION ALL

        SELECT r.parent_company_id, a.depth + 1
        FROM company_relationships r
        JOIN ancestors a ON r.child_company_id = a.id
        WHERE a.depth < 20
    )
    SELECT COUNT(*) INTO cycles FROM ancestors WHERE id = NEW.child_company_id;

    IF cycles > 0 THEN
        RAISE EXCEPTION 'Company % cannot be part of company %: that would create a loop in the ownership chain',
            NEW.child_company_id, NEW.parent_company_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER company_relationships_cycle_check
BEFORE INSERT OR UPDATE OF child_company_id, parent_company_id ON company_relationships
FOR EACH ROW EXECUTE FUNCTION company_relationships_reject_cycles();

COMMENT ON TABLE company_relationships IS
    'Genuine corporate structure: one company owning or operating another. Never duplicates - '
    'those are merges, see company_merges. A child keeps its own page, managers and ratings.';

-- ROLLBACK FOR V119, exact rather than approximate.
--
-- V119 rewrote stored job titles to the house spelling: unambiguous acronyms expanded, whitespace
-- collapsed, departments written with "of". It recorded every single change in
-- job_title_cleanup_v119, which is what makes this a real undo rather than a second guess.
--
-- A title changed by somebody AFTER V119 ran is left alone: the WHERE clause only restores a row
-- whose current value is still the one V119 wrote. Overwriting a later correction with an older
-- spelling would be a worse outcome than the one being rolled back.

BEGIN;

UPDATE managers m
   SET title = c.old_title,
       updated_at = now()
  FROM job_title_cleanup_v119 c
 WHERE c.source = 'manager'
   AND c.row_id = m.id::TEXT
   AND m.title = c.new_title;

UPDATE interview_reviews ir
   SET role_category = c.old_title
  FROM job_title_cleanup_v119 c
 WHERE c.source = 'interview_review'
   AND c.row_id = ir.id::TEXT
   AND ir.role_category = c.new_title;

DO $$
DECLARE v_left BIGINT;
BEGIN
    SELECT count(*) INTO v_left
      FROM job_title_cleanup_v119 c
      JOIN managers m ON m.id::TEXT = c.row_id AND c.source = 'manager'
     WHERE m.title = c.new_title;
    RAISE NOTICE 'rollback: % manager titles still hold the V119 spelling (expected 0 unless they '
                 'were edited since)', v_left;
END $$;

-- The two functions are left in place. They are additive, nothing else calls them, and dropping
-- canonicalize_job_title() would fail while expand_title_acronyms() still exists beside it.
--
-- The record of what was changed is NOT dropped either. If this rollback is itself reversed, it
-- is the only thing that knows what the house spelling was.
--   DROP TABLE job_title_cleanup_v119;

COMMIT;

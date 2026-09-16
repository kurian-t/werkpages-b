-- Re-expand published_reviews so it can see the declared location columns.
--
-- V60 defined the view as `SELECT * FROM reviews WHERE ...`. Postgres expands that star once, at
-- creation, and stores the resulting column list - so the view still describes the reviews table as
-- it looked in V60. Every column V68 added is invisible through it, and querying one fails with
-- `column "declared_country" does not exist` even though the column plainly exists on the table.
--
-- Worth knowing generally: this is not specific to location. Any future ALTER TABLE reviews ADD
-- COLUMN needs this view replaced too, or the new column silently cannot be read through it.
CREATE OR REPLACE VIEW published_reviews AS
    SELECT * FROM reviews
    WHERE disposition = 'live'
      AND deleted_at IS NULL;

COMMENT ON VIEW published_reviews IS
    'Live, undeleted reviews. Defined with SELECT *, which Postgres expands at creation time - so this view must be replaced whenever a column is added to reviews, or the new column is unreadable through it.';

-- Whether a workplace rating shows the period its author worked at the company.
--
-- The same choice V86 gave a manager rating, for the other thing a person can rate. The reason is
-- the same and so is the shape: "Mar 2019 - Aug 2024" at a company with four people in the office
-- identifies its author as precisely as a signature, and the author is the only person who can
-- judge whether that is safe for them.
--
-- A display rule, not a deletion. worked_from / worked_until keep their real values because the
-- form opens on them, the one-rating-per-person-per-company rule reads them, and the tenure
-- arithmetic needs them. CompanyReviewService masks them for other readers and keeps them for the
-- author, exactly as buildReviewJson and buildMyReviewJson do for managers.
--
-- NOT NULL WITH a default: RateMyManagers writes to this table too and does not name this column,
-- and a NOT NULL column without a default would break those INSERTs the moment this ran. FALSE
-- preserves today's behaviour for every existing row.
ALTER TABLE company_reviews
    ADD COLUMN dates_hidden BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN company_reviews.dates_hidden IS
    'Author chose not to show the period they worked at this company. A display rule applied on '
    'read - worked_from/worked_until keep their real values for the edit form and tenure maths. '
    'Mirrors reviews.dates_hidden (V86).';

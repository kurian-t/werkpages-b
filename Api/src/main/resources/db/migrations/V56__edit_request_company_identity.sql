-- Close the last route that can create a duplicate company.
--
-- A user requests "change this manager's company to Crumbl", the request stores the string, and at
-- approval time the admin's click ran findOrCreate("Crumbl") - resolving identity from a name, at a
-- moment potentially weeks after the user chose it. That is precisely the architecture V54/V55 and
-- the picker work removed everywhere else, still alive on this one path.
--
-- After this migration the request carries the identity the user actually picked, and approval uses
-- it. The name stays, but demoted: it is a snapshot for audit and display ("user requested changing
-- company from X to Crumbl"), never the thing that decides which company row is meant.

ALTER TABLE manager_edits
    ADD COLUMN requested_company_id BIGINT REFERENCES companies(id) ON DELETE SET NULL;

COMMENT ON COLUMN manager_edits.requested_company_id IS
    'The company the user selected. Identity. Approval uses this and never re-resolves a name.';

COMMENT ON COLUMN manager_edits.new_company IS
    'Display/audit snapshot of the requested company name at request time. NOT identity - see '
    'requested_company_id. Rows created before V56 have only this, and an admin must resolve them '
    'through the company picker rather than having the name silently resolved for them.';

-- Deliberately NOT backfilled by matching names to companies.
--
-- A name match would look like a safe backfill and would be the same mistake in a migration: it
-- would guess that a pending request for "Crumbl" meant whichever row currently holds that string,
-- which is exactly the assumption that produced duplicate companies in the first place. Pending
-- requests written before this migration keep a NULL identity, and the approval path refuses to
-- guess - it asks the admin to pick the company once. There are few enough pending edits at any
-- time for that to be cheap, and it is the only answer that cannot be silently wrong.

-- Finding the pending requests that need an admin to resolve them.
CREATE INDEX manager_edits_pending_unresolved
    ON manager_edits (manager_id)
    WHERE status = 'pending' AND requested_company_id IS NULL AND new_company IS NOT NULL;

package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import org.werkpages.service.DeclaredLocation;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data-access layer for the {@code manager_edits} table.
 */
public class EditRepository {

    private final SqlClient db;

    public EditRepository(SqlClient db) {
        this.db = db;
    }

    public Future<Long> countSubmittedTodayByUser(UUID userId) {
        return db.preparedQuery("SELECT COUNT(*) FROM manager_edits WHERE proposed_by = $1 AND created_at >= current_date")
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().next().getLong(0));
    }

    /**
     * Overload for callers that have no selected company. Records the request with no identity,
     * which an admin must resolve through the picker before it can be approved.
     */
    public Future<Row> upsert(long managerId, UUID proposedBy, String newCompany, String newCompanyLogoUrl,
                               String newTitle, String newStatus, String newCountry,
                               String newLinkedinUrl,
                               OffsetDateTime newStartDate, OffsetDateTime newEndDate) {
        return upsert(managerId, proposedBy, newCompany, null, newCompanyLogoUrl, newTitle,
                      newStatus, newCountry, newLinkedinUrl, newStartDate, newEndDate);
    }

    /**
     * @param requestedCompanyId the company the user picked. Identity. Approval uses this directly.
     * @param newCompany         the name they saw when they picked it - a snapshot for audit and
     *                           display, never used to decide which company row is meant.
     */
    public Future<Row> upsert(long managerId, UUID proposedBy, String newCompany, Long requestedCompanyId,
                               String newCompanyLogoUrl,
                               String newTitle, String newStatus, String newCountry,
                               String newLinkedinUrl,
                               OffsetDateTime newStartDate, OffsetDateTime newEndDate) {
        return upsert(managerId, proposedBy, newCompany, requestedCompanyId, newCompanyLogoUrl, newTitle,
                      newStatus, newCountry, null, null, null, null, newLinkedinUrl, newStartDate, newEndDate);
    }

    /**
     * Records an edit request that carries a whole location rather than only a country.
     *
     * <p>V70 added these four columns so that a location could be corrected the same way a title
     * can. Nothing ever wrote them: the form asked for a country from a dropdown, so there was
     * nothing else to store. Now that every form asks the same question through one control, an
     * edit can say "Kitchener, Ontario" or name a building, and the answer has somewhere to go.
     *
     * @param newCompanyLocationId a building the editor picked. When set, the coarse columns are a
     *                             snapshot for the admin to read; the row itself is the authority.
     */
    public Future<Row> upsert(long managerId, UUID proposedBy, String newCompany, Long requestedCompanyId,
                               String newCompanyLogoUrl,
                               String newTitle, String newStatus, String newCountry,
                               String newState, String newCity, String newPrecision,
                               Long newCompanyLocationId,
                               String newLinkedinUrl,
                               OffsetDateTime newStartDate, OffsetDateTime newEndDate) {
        return db.preparedQuery("""
                INSERT INTO manager_edits(manager_id, proposed_by, new_company, requested_company_id, new_company_logo_url, new_title, new_status, new_country, new_declared_state, new_declared_city, new_declared_precision, new_company_location_id, new_linkedin_url, new_start_date, new_end_date)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)
                ON CONFLICT (manager_id, proposed_by) WHERE status = 'pending'
                DO UPDATE SET new_company             = EXCLUDED.new_company,
                              requested_company_id    = EXCLUDED.requested_company_id,
                              new_company_logo_url    = EXCLUDED.new_company_logo_url,
                              new_title               = EXCLUDED.new_title,
                              new_status              = EXCLUDED.new_status,
                              new_country             = EXCLUDED.new_country,
                              new_declared_state      = EXCLUDED.new_declared_state,
                              new_declared_city       = EXCLUDED.new_declared_city,
                              new_declared_precision  = EXCLUDED.new_declared_precision,
                              new_company_location_id = EXCLUDED.new_company_location_id,
                              new_linkedin_url        = EXCLUDED.new_linkedin_url,
                              new_start_date          = EXCLUDED.new_start_date,
                              new_end_date            = EXCLUDED.new_end_date,
                              created_at              = now()
                RETURNING id, created_at
                """)
            .execute(Tuple.of(managerId, proposedBy, newCompany, requestedCompanyId, newCompanyLogoUrl, newTitle, newStatus, newCountry, newState, newCity, newPrecision, newCompanyLocationId, newLinkedinUrl, newStartDate, newEndDate))
            .map(rows -> rows.iterator().next());
    }

    public Future<RowSet<Row>> findPendingByManagerAndUser(long managerId, UUID userId) {
        return db.preparedQuery("""
                SELECT id, new_company, new_title, new_status, new_country,
                       new_declared_state, new_declared_city, new_declared_precision, new_company_location_id,
                       new_linkedin_url, new_start_date, new_end_date, created_at
                FROM manager_edits
                WHERE manager_id = $1 AND proposed_by = $2 AND status = 'pending'
                ORDER BY created_at DESC LIMIT 1
                """)
            .execute(Tuple.of(managerId, userId));
    }

    public Future<RowSet<Row>> findPendingForAdmin(int limit, int offset) {
        return db.preparedQuery("""
                SELECT pe.id, pe.manager_id, m.name AS manager_name,
                       m.company AS current_company, m.title AS current_title,
                       u.username AS requested_by,
                       pe.new_company, pe.new_title, pe.new_status, pe.new_country,
                       pe.new_declared_state, pe.new_declared_city, pe.new_company_location_id,
                       pe.new_linkedin_url, pe.status, pe.created_at,
                       -- Which shape the request is, and how much is in it, so the queue can tell
                       -- a one-field legacy edit from a contribution that rewrites a whole career.
                       pe.change_format, pe.new_name, pe.explanation,
                       (SELECT count(*) FROM manager_edit_employments mee
                         WHERE mee.edit_id = pe.id) AS position_count
                FROM manager_edits pe
                JOIN managers m ON m.id = pe.manager_id
                JOIN users u ON u.id = pe.proposed_by
                WHERE pe.status = 'pending'
                ORDER BY pe.created_at ASC
                LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset));
    }

    public Future<Optional<Row>> findByIdWithManager(UUID editId) {
        return db.preparedQuery("""
                SELECT pe.id, pe.manager_id, pe.new_company, pe.requested_company_id, pe.new_company_logo_url, pe.new_title, pe.new_status,
                       pe.new_country, pe.new_declared_state, pe.new_declared_city,
                       pe.new_declared_precision, pe.new_company_location_id,
                       pe.new_linkedin_url, pe.new_start_date, pe.new_end_date,
                       pe.status, pe.proposed_by,
                       -- Which shape this contribution is, and the two fields only the career
                       -- editor writes. Read from the row rather than inferred: a name-only
                       -- correction has an empty change set and would otherwise be mistaken for a
                       -- legacy proposal and approved by applying nothing.
                       pe.change_format, pe.new_name, pe.explanation,
                       m.company AS current_company, m.title AS current_title,
                       m.company_id AS current_company_id,
                       m.created_at AS manager_created_at, m.name AS manager_name
                FROM manager_edits pe
                JOIN managers m ON m.id = pe.manager_id
                WHERE pe.id = $1
                """)
            .execute(Tuple.of(editId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** Fetches a pending edit with just the fields needed for rejection notification. */
    public Future<Optional<Row>> findPendingById(UUID editId) {
        return db.preparedQuery("""
                SELECT pe.id, pe.proposed_by, pe.status, pe.change_format,
                       m.name AS manager_name
                FROM manager_edits pe
                JOIN managers m ON m.id = pe.manager_id
                WHERE pe.id = $1
                """)
            .execute(Tuple.of(editId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    public Future<Void> approve(UUID editId, UUID adminId, OffsetDateTime reviewedAt) {
        return approve(db, editId, adminId, reviewedAt);
    }

    /**
     * The same decision, inside the caller's transaction.
     *
     * <p>A career-editor contribution applies its change set and records its decision in one
     * transaction, so the decision cannot be committed while the positions it approved are rolled
     * back. The no-transaction overload above stays for the legacy path, which has nothing to
     * apply beyond the parent row.
     */
    public Future<Void> approve(SqlClient conn, UUID editId, UUID adminId, OffsetDateTime reviewedAt) {
        return conn.preparedQuery("UPDATE manager_edits SET status = 'approved', reviewed_at = $1, reviewed_by = $2 WHERE id = $3")
            .execute(Tuple.of(reviewedAt, adminId, editId))
            .mapEmpty();
    }

    public Future<Optional<Row>> reject(UUID editId, UUID adminId, OffsetDateTime reviewedAt) {
        return reject(db, editId, adminId, reviewedAt);
    }

    /**
     * The same rejection, inside the caller's transaction.
     *
     * <p>{@code manager_edits_format_guard} guards rejection as well as approval, and deliberately:
     * a backend deployed before the career editor existed cannot render a change set, so rejecting
     * one from that admin screen would be rejecting a contribution nobody had been able to read.
     * Refusing it leaves the request pending for a moderator who can see what it says.
     */
    public Future<Optional<Row>> reject(SqlClient conn, UUID editId, UUID adminId,
                                        OffsetDateTime reviewedAt) {
        return conn.preparedQuery("UPDATE manager_edits SET status = 'rejected', reviewed_at = $1, reviewed_by = $2 WHERE id = $3 RETURNING id")
            .execute(Tuple.of(reviewedAt, adminId, editId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    // ── THE CHANGE SET ──────────────────────────────────────────────────────
    //
    // A contribution is ONE manager_edits row. What it could never carry was more
    // than one position, because that row is flat: one new_title, one new_company,
    // one pair of dates. A career is a list, so the positions live in
    // manager_edit_employments and these methods read and write them.
    //
    // The parent row still owns the decision - pending/approved/rejected, who
    // proposed it, who reviewed it - so approving remains one choice over one
    // contribution. Nothing here is a second moderation system.

    /**
     * An employment exactly as it stood when a proposal was written against it.
     *
     * <p><b>Why a proposal carries the past as well as the future.</b> Comparing a proposal with
     * {@code career_history} at approval time cannot see a change made in between. If a moderator
     * fixed a title an hour after the contributor submitted, the proposal still looks like a clean
     * edit of the title it was written against, and approving it silently reverts the moderator's
     * work. Nobody is wrong; the proposal is stale, and staleness is only visible against a
     * snapshot taken at submission.
     */
    public record EmploymentSnapshot(String title, Long companyId,
                                     OffsetDateTime startDate, OffsetDateTime endDate,
                                     Boolean periodKnown, String employmentStatus,
                                     DeclaredLocation location, String state,
                                     OffsetDateTime updatedAt) {

        public static final EmploymentSnapshot NONE =
            new EmploymentSnapshot(null, null, null, null, null, null, null, null, null);

        public DeclaredLocation locationOrNone() {
            return location != null ? location : DeclaredLocation.NONE;
        }
    }

    /**
     * One position inside a contribution.
     *
     * <p>{@code changedFields} is what makes this honest. A null {@code endDate} is ambiguous
     * between "leave the end date alone" and "this role has no end date after all", and those are
     * opposite instructions. The field list says which of the values below were actually proposed;
     * everything absent from it is left exactly as it is. The same distinction the moderator's
     * {@code Correction} patch carries as its {@code *Given} booleans.
     *
     * @param intent   {@code correct} fixes this employment's record, {@code add} records a
     *                 distinct one that was never recorded, {@code supersede} says this employment
     *                 names the wrong employer. The database refuses any other value.
     * @param roleId   the employment being changed, by {@code career_history.id}, which is the only
     *                 identifier that can tell two spells at one employer apart. Null only for
     *                 {@code add}, and the database enforces that pairing.
     * @param snapshot what that employment said at submission. Required for {@code correct} and
     *                 {@code supersede}, absent for {@code add}, and the database enforces that too.
     */
    public record EmploymentChange(String intent, Long roleId, List<String> changedFields,
                                   String title, Long companyId, String companyName,
                                   OffsetDateTime startDate, OffsetDateTime endDate,
                                   Boolean periodKnown, String employmentStatus,
                                   DeclaredLocation location, EmploymentSnapshot snapshot,
                                   int position) {

        public static final String CORRECT   = "correct";
        public static final String ADD       = "add";
        public static final String SUPERSEDE = "supersede";

        /** Field names, matching edit_employment_fields_known and role_corrections.field. */
        public static final String TITLE             = "title";
        public static final String COMPANY           = "company";
        public static final String LOCATION          = "location";
        public static final String START_DATE        = "start_date";
        public static final String END_DATE          = "end_date";
        public static final String PERIOD_KNOWN      = "period_known";
        public static final String EMPLOYMENT_STATUS = "employment_status";

        public boolean changes(String field) {
            return changedFields != null && changedFields.contains(field);
        }

        public DeclaredLocation locationOrNone() {
            return location != null ? location : DeclaredLocation.NONE;
        }

        public EmploymentSnapshot snapshotOrNone() {
            return snapshot != null ? snapshot : EmploymentSnapshot.NONE;
        }
    }

    /**
     * Record a contribution written by the career history editor.
     *
     * <p>Separate from {@link #upsert} rather than another overload of it, because the two shapes
     * differ in kind and not merely in arguments. This one declares
     * {@code change_format = 'career_editor'}, which is what stops an older backend from approving
     * it by applying nothing, and it deliberately leaves the legacy single-position columns
     * ({@code new_title}, {@code new_company}, the dates) NULL: the positions live in the change
     * set, and writing them here as well would give approval two disagreeing accounts of the same
     * contribution.
     *
     * <p>Upserts on the same one-pending-per-contributor index the legacy path uses, so
     * re-submitting replaces a previous draft instead of queueing a second decision.
     */
    public Future<Row> upsertCareerEdit(SqlClient tx, long managerId, UUID proposedBy,
                                        String newName, String explanation) {
        return tx.preparedQuery("""
                INSERT INTO manager_edits (manager_id, proposed_by, new_name, explanation,
                                           change_format, status)
                VALUES ($1, $2, $3, $4, 'career_editor', 'pending')
                ON CONFLICT (manager_id, proposed_by) WHERE status = 'pending'
                DO UPDATE SET new_name      = EXCLUDED.new_name,
                              explanation   = EXCLUDED.explanation,
                              change_format = EXCLUDED.change_format,
                              -- A re-submission through the editor supersedes whatever the legacy
                              -- form had left pending, so those columns are cleared rather than
                              -- left to be applied alongside a change set that contradicts them.
                              new_company             = NULL,
                              requested_company_id    = NULL,
                              new_company_logo_url    = NULL,
                              new_title               = NULL,
                              new_status              = NULL,
                              new_country             = NULL,
                              new_declared_state      = NULL,
                              new_declared_city       = NULL,
                              new_declared_precision  = NULL,
                              new_company_location_id = NULL,
                              new_linkedin_url        = NULL,
                              new_start_date          = NULL,
                              new_end_date            = NULL,
                              created_at              = now()
                RETURNING id, created_at
                """)
            .execute(Tuple.of(managerId, proposedBy, newName, explanation))
            .map(rows -> rows.iterator().next());
    }

    /**
     * The employment as it stands right now, for snapshotting into a proposal.
     *
     * <p>Taken inside the submitting transaction and with the row locked by the caller, so the
     * snapshot is of a state that genuinely existed rather than one assembled from two reads.
     */
    public Future<Optional<Row>> readEmploymentForSnapshot(SqlClient tx, long roleId) {
        return tx.preparedQuery("""
                SELECT id, manager_id, title, company_id, company, start_date, end_date,
                       period_known, employment_status, state, updated_at,
                       declared_country, declared_state, declared_city, declared_precision,
                       company_location_id, declared_source, declared_text
                FROM career_history
                WHERE id = $1
                """)
            .execute(Tuple.of(roleId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /**
     * Replace a contribution's positions with the ones given, inside the caller's transaction.
     *
     * <p><b>Replace, not merge.</b> {@code manager_edits} is upserted on
     * {@code (manager_id, proposed_by) WHERE status = 'pending'}, so re-submitting an edit
     * overwrites the previous pending proposal rather than adding a second one. The positions have
     * to follow that: merging would leave a position from an abandoned draft sitting in a change
     * set the contributor never reviewed, and the moderator would approve something nobody
     * submitted. The delete is scoped to one edit id and runs in the same transaction as the
     * insert, so the change set is never observed half-replaced.
     */
    public Future<Void> replaceChangeSet(SqlClient tx, UUID editId, List<EmploymentChange> changes) {
        return tx.preparedQuery("DELETE FROM manager_edit_employments WHERE edit_id = $1")
            .execute(Tuple.of(editId))
            .compose(deleted -> {
                if (changes == null || changes.isEmpty()) return Future.succeededFuture();
                List<Tuple> batch = new ArrayList<>(changes.size());
                for (EmploymentChange c : changes) {
                    DeclaredLocation loc  = c.locationOrNone();
                    EmploymentSnapshot wa = c.snapshotOrNone();
                    DeclaredLocation wasLoc = wa.locationOrNone();
                    batch.add(Tuple.wrap(new Object[] {
                        editId, c.intent(), c.roleId(),
                        c.changedFields() == null
                            ? new String[0]
                            : c.changedFields().toArray(new String[0]),
                        c.title(), c.companyId(), c.companyName(),
                        c.startDate(), c.endDate(), c.periodKnown(), c.employmentStatus(),
                        loc.country(), loc.state(), loc.city(), loc.precision(),
                        loc.companyLocationId(), loc.source(), loc.text(),
                        wa.title(), wa.companyId(), wa.startDate(), wa.endDate(),
                        wa.periodKnown(), wa.employmentStatus(),
                        wasLoc.country(), wasLoc.state(), wasLoc.city(), wasLoc.precision(),
                        wasLoc.companyLocationId(),
                        wa.state(), wa.updatedAt(),
                        c.position()
                    }));
                }
                return tx.preparedQuery("""
                        INSERT INTO manager_edit_employments
                            (edit_id, intent, role_id, changed_fields,
                             new_title, new_company_id, new_company_name,
                             new_start_date, new_end_date, new_period_known,
                             new_employment_status,
                             new_declared_country, new_declared_state, new_declared_city,
                             new_declared_precision, new_company_location_id,
                             new_declared_source, new_declared_text,
                             was_title, was_company_id, was_start_date, was_end_date,
                             was_period_known, was_employment_status,
                             was_declared_country, was_declared_state, was_declared_city,
                             was_declared_precision, was_company_location_id,
                             was_state, was_updated_at,
                             position)
                        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11,
                                $12, $13, $14, $15, $16, $17, $18,
                                $19, $20, $21, $22, $23, $24,
                                $25, $26, $27, $28, $29,
                                $30, $31, $32)
                        """)
                    .executeBatch(batch)
                    .mapEmpty();
            });
    }

    /**
     * A contribution's positions, in the order the contributor built them, each beside the
     * employment as it stands today.
     *
     * <p>Three accounts of the same employment travel together on purpose: what it said when the
     * proposal was written ({@code was_*}), what the contributor proposes ({@code new_*}), and
     * what it says now ({@code current_*}). A moderator needs all three to tell a correction from
     * a revert, and comparing {@code was_*} with {@code current_*} is what detects that somebody
     * else changed this employment after the proposal was submitted.
     */
    public Future<RowSet<Row>> findChangeSet(SqlClient conn, UUID editId) {
        return conn.preparedQuery("""
                SELECT mee.id, mee.intent, mee.role_id, mee.changed_fields,
                       mee.new_title, mee.new_company_id, mee.new_company_name,
                       mee.new_start_date, mee.new_end_date, mee.new_period_known,
                       mee.new_employment_status,
                       mee.new_declared_country, mee.new_declared_state, mee.new_declared_city,
                       mee.new_declared_precision, mee.new_company_location_id,
                       mee.new_declared_source, mee.new_declared_text,
                       mee.was_title, mee.was_company_id, mee.was_start_date, mee.was_end_date,
                       mee.was_period_known, mee.was_employment_status,
                       mee.was_declared_country, mee.was_declared_state, mee.was_declared_city,
                       mee.was_declared_precision, mee.was_company_location_id,
                       mee.was_state, mee.was_updated_at,
                       mee.position,
                       c.name  AS new_company_resolved_name,
                       wc.name AS was_company_name,
                       ch.title      AS current_title,
                       ch.company_id AS current_company_id,
                       ch.company    AS current_company,
                       ch.start_date AS current_start_date,
                       ch.end_date   AS current_end_date,
                       ch.period_known      AS current_period_known,
                       ch.employment_status AS current_employment_status,
                       ch.state             AS current_state,
                       ch.updated_at        AS current_updated_at,
                       ch.declared_country   AS current_declared_country,
                       ch.declared_state     AS current_declared_state,
                       ch.declared_city      AS current_declared_city,
                       ch.declared_precision AS current_declared_precision,
                       ch.company_location_id AS current_company_location_id
                FROM manager_edit_employments mee
                LEFT JOIN companies      c  ON c.id  = mee.new_company_id
                LEFT JOIN companies      wc ON wc.id = mee.was_company_id
                LEFT JOIN career_history ch ON ch.id = mee.role_id
                WHERE mee.edit_id = $1
                ORDER BY mee.position, mee.id
                """)
            .execute(Tuple.of(editId));
    }

    public Future<RowSet<Row>> findChangeSet(UUID editId) {
        return findChangeSet(db, editId);
    }

    /**
     * Which shape a contribution is, which decides how approving it must behave.
     *
     * <p><b>Not inferred from whether a change set exists.</b> A contributor who corrects only the
     * manager's name touches no employment, so their change set is legitimately empty; routed by
     * the presence of rows, that submission would reach the legacy path, which reads
     * {@code new_title} / {@code new_company} - all null - and would mark it approved having
     * applied nothing. The name correction would be accepted and silently dropped. The format is
     * therefore declared on the row, and read from it.
     */
    public Future<String> changeFormat(SqlClient conn, UUID editId) {
        return conn.preparedQuery("SELECT change_format FROM manager_edits WHERE id = $1")
            .execute(Tuple.of(editId))
            .map(rows -> rows.iterator().hasNext()
                ? rows.iterator().next().getString("change_format")
                : null);
    }

    /**
     * Announce, for this transaction only, that the code applying a career-editor contribution
     * understands the format.
     *
     * <p>{@code manager_edits_format_guard} refuses to let a career-editor row leave `pending`
     * without this, so that a backend deployed before the editor existed fails loudly instead of
     * approving a change set by applying none of it. Same mechanism ModerationService already uses
     * to tell the audit triggers that a human made the decision.
     */
    public Future<Void> markCareerEditApply(SqlClient tx) {
        return tx.query("SELECT set_config('rmm.career_edit_apply', 'on', true)")
            .execute().mapEmpty();
    }
}

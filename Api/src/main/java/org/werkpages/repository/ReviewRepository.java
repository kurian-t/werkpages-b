package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.werkpages.service.DeclaredLocation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Data-access layer for the {@code reviews} table.
 */
public class ReviewRepository {

    private final SqlClient db;

    public ReviewRepository(SqlClient db) {
        this.db = db;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Public view: live ratings only. */
    public Future<RowSet<Row>> findByManager(long managerId, int limit, int offset,
                                              String sortBy, UUID userIdFilter) {
        return findByManager(managerId, limit, offset, sortBy, userIdFilter, null, false);
    }

    /**
     * When the reviewer stopped working with this manager, bounded by the manager's own role.
     *
     * <p>A review card said "Jan 2024 - Present" for a manager who had left the company, because
     * {@code worked_until} is the REVIEWER's date: they are still there, so it is null. But the
     * manager is not, and nobody can still be working with someone who left.
     *
     * <p>{@code LEAST} ignores nulls in Postgres, which gives exactly the rule wanted:
     *
     * <ul>
     *   <li>both null - still current, and rightly so;
     *   <li>reviewer still there, manager's role ended - capped at the role's end;
     *   <li>reviewer left, role still open - the reviewer's own date, unchanged;
     *   <li>both set - whichever came first.
     * </ul>
     *
     * <p>Matched on the company and title the review itself records, so a manager holding two
     * roles at two companies at once caps each review against the right one - and neither, while
     * both remain open.
     */
    private static final String EFFECTIVE_WORKED_UNTIL = """
            LEAST(
                r.worked_until,
                (SELECT MAX(ch.end_date)::date
                   FROM career_history ch
                  WHERE ch.manager_id = r.manager_id
                    AND LOWER(TRIM(ch.company)) = LOWER(TRIM(r.manager_company))
                    AND LOWER(TRIM(ch.title))   = LOWER(TRIM(r.manager_title))
                    AND NOT EXISTS (
                          SELECT 1 FROM career_history o
                           WHERE o.manager_id = ch.manager_id
                             AND LOWER(TRIM(o.company)) = LOWER(TRIM(ch.company))
                             AND LOWER(TRIM(o.title))   = LOWER(TRIM(ch.title))
                             AND o.end_date IS NULL
                        ))
            ) AS effective_worked_until
            """;

    /**
     * The manager's ratings, as this particular caller is entitled to see them.
     *
     * <p>A held rating is withheld from the public, and from nobody else. Its author has to see it
     * or the page they just contributed to looks empty, and an admin has to see it or moderating
     * means reading a queue and a profile side by side and matching them up by eye.
     *
     * <p>{@code viewerId} is resolved from the caller's token, never from the {@code userId} query
     * parameter, which is client-supplied: keying visibility off that would let anyone read
     * anyone's withheld ratings by guessing an id.
     *
     * @param userIdFilter narrow to one author's ratings; a display filter, not a permission
     * @param viewerId     who is asking, from their token
     * @param isAdmin      whether they moderate
     */
    public Future<RowSet<Row>> findByManager(long managerId, int limit, int offset,
                                              String sortBy, UUID userIdFilter,
                                              UUID viewerId, boolean isAdmin) {
        String orderBy = switch (sortBy) {
            case "helpful" -> "helpful_count DESC";
            case "highest" -> "overall_rating DESC";
            case "lowest"  -> "overall_rating ASC";
            default        -> "created_at DESC";
        };

        // Exclude expired placeholder reviews; active placeholders (weight_expires_on IS NULL or future) are shown.
        // User-filtered queries naturally exclude placeholders (user_id IS NULL) via the user_id condition.
        // Soft-deleted reviews (deleted_at IS NOT NULL) are always excluded.
        if (userIdFilter != null) {
            String sql = String.format("SELECT r.*, " + EFFECTIVE_WORKED_UNTIL
                + " FROM reviews r WHERE r.manager_id = $1 AND r.user_id = $4 AND r.deleted_at IS NULL"
                + " ORDER BY %s LIMIT $2 OFFSET $3", orderBy.replace("created_at", "r.created_at")
                    .replace("helpful_count", "r.helpful_count").replace("overall_rating", "r.overall_rating"));
            return db.preparedQuery(sql).execute(Tuple.of(managerId, limit, offset, userIdFilter));
        } else {
            // Not the view here, because the view answers only "is it published" and this query
            // also has to answer "is it yours" and "do you moderate".
            String sql = String.format(
                "SELECT r.*, " + EFFECTIVE_WORKED_UNTIL
                + " FROM reviews r WHERE r.manager_id = $1 AND r.deleted_at IS NULL "
                + "AND (r.weight = FALSE OR r.weight_expires_on IS NULL OR r.weight_expires_on > CURRENT_DATE) "
                + "AND (r.disposition = 'live' OR $4 = TRUE OR ($5::uuid IS NOT NULL AND r.user_id = $5)) "
                + "ORDER BY %s LIMIT $2 OFFSET $3", orderBy.replace("created_at", "r.created_at")
                    .replace("helpful_count", "r.helpful_count").replace("overall_rating", "r.overall_rating"));
            return db.preparedQuery(sql).execute(Tuple.of(managerId, limit, offset, isAdmin, viewerId));
        }
    }

    public Future<RowSet<Row>> findCareerSegmentsByManager(long managerId, int limit, int offset) {
        return findCareerSegmentsByManager(managerId, limit, offset, null, false);
    }

    /**
     * The career trajectory: when each role ran, and how it was rated.
     *
     * <p><b>Dates come from career_history; ratings come from reviews.</b> They used to both come
     * from reviews, which meant a role's start, end and "Present" were really the dates of
     * whoever happened to review it. A manager who had left showed "Present" because their
     * reviewer was still at the company, and a role nobody had reviewed could never show as
     * current no matter how it was recorded. Editing the manager's own career history changed
     * none of it, because the panel was not reading that table at all.
     *
     * <p>A FULL OUTER JOIN rather than a plain one, so neither side loses rows: a role recorded
     * with no reviews yet still appears (rated "NO REVIEWS YET"), and reviews naming a
     * company/title that was never recorded as a role still appear rather than vanishing from the
     * page. Career history wins on dates wherever it has an entry.
     *
     * <p>Same visibility rule as the review list: public sees live, an author and an admin see
     * theirs.
     */
    public Future<RowSet<Row>> findCareerSegmentsByManager(long managerId, int limit, int offset,
                                                           UUID viewerId, boolean isAdmin) {
        return db.preparedQuery(SEGMENTS_SQL + """
                ORDER BY start_date ASC NULLS LAST
                LIMIT $2 OFFSET $3
                """)
            .execute(Tuple.of(managerId, limit, offset, isAdmin, viewerId));
    }

    /**
     * Roles from career history and rated groups from reviews, reconciled.
     *
     * <p>Shared by the listing and its count so the number above the panel can never disagree with
     * what the panel shows - they were separate queries with separate grouping, which is its own
     * way to be wrong.
     */
    private static final String SEGMENTS_SQL = """
            WITH ch AS (
                SELECT LOWER(TRIM(company)) AS ck,
                       LOWER(TRIM(title))   AS tk,
                       MIN(company)         AS company,
                       MIN(title)           AS title,
                       MIN(start_date)::date AS start_date,
                       MAX(end_date)::date   AS end_date,
                       BOOL_OR(end_date IS NULL) AS is_current,
                       MIN(company_id)      AS company_id
                  FROM career_history
                 WHERE manager_id = $1
                 GROUP BY 1, 2
            ),
            rv AS (
                SELECT LOWER(TRIM(manager_company)) AS ck,
                       LOWER(TRIM(manager_title))   AS tk,
                       MIN(manager_company)         AS company,
                       MIN(manager_title)           AS title,
                       MIN(worked_from)             AS start_date,
                       MAX(worked_until)            AS end_date,
                       BOOL_OR(worked_until IS NULL) AS is_current,
                       AVG(overall_rating)                    AS avg_rating,
                       COUNT(*)                               AS review_count,
                       AVG(communication_style)               AS communication_style,
                       AVG(perceived_approachability)         AS perceived_approachability,
                       AVG(perceived_clarity_of_expectations) AS perceived_clarity_of_expectations,
                       AVG(feedback_style)                    AS feedback_style,
                       AVG(perceived_supportiveness)          AS perceived_supportiveness,
                       AVG(decision_making_style)             AS decision_making_style,
                       AVG(organization_and_planning_style)   AS organization_and_planning_style,
                       AVG(delegation_style)                  AS delegation_style,
                       AVG(perceived_professional_demeanor)   AS perceived_professional_demeanor,
                       AVG(overall_working_experience)        AS overall_working_experience,
                       MIN(manager_role_start)                AS manager_role_start,
                       MAX(manager_role_end)                  AS manager_role_end
                  FROM reviews
                 WHERE manager_id = $1 AND deleted_at IS NULL
                   AND (disposition = 'live' OR $4 = TRUE OR ($5::uuid IS NOT NULL AND user_id = $5))
                 GROUP BY 1, 2
            )
            SELECT
                COALESCE(ch.company, rv.company) AS company,
                COALESCE(ch.title,   rv.title)   AS role,
                COALESCE(ch.start_date, rv.start_date) AS start_date,
                CASE WHEN ch.ck IS NOT NULL THEN ch.end_date   ELSE rv.end_date   END AS end_date,
                CASE WHEN ch.ck IS NOT NULL THEN ch.is_current ELSE COALESCE(rv.is_current, FALSE) END AS is_current,
                rv.avg_rating,
                COALESCE(rv.review_count, 0) AS review_count,
                rv.communication_style,
                rv.perceived_approachability,
                rv.perceived_clarity_of_expectations,
                rv.feedback_style,
                rv.perceived_supportiveness,
                rv.decision_making_style,
                rv.organization_and_planning_style,
                rv.delegation_style,
                rv.perceived_professional_demeanor,
                rv.overall_working_experience,
                rv.manager_role_start,
                rv.manager_role_end,
                /*
                  The logo of the company this role was actually AT.

                  The panel returned no logo at all, so every past company fell through to the
                  frontend's guess-the-domain fallback and rendered a stranger's mark. Picking
                  the right company in the admin editor stored its id and changed nothing on
                  screen, because nothing read it back.

                  Preference is the company the admin PICKED (career_history.company_id), then
                  the one the name resolves to - unique per companies_name_ci, so neither join
                  can multiply rows and the count above the panel stays honest.
                */
                COALESCE(picked_co.logo_url, named_co.logo_url) AS logo_url
              FROM ch FULL OUTER JOIN rv ON ch.ck = rv.ck AND ch.tk = rv.tk
              LEFT JOIN companies picked_co ON picked_co.id = ch.company_id
              LEFT JOIN companies named_co  ON LOWER(TRIM(named_co.name)) = COALESCE(ch.ck, rv.ck)
            """;

    /**
     * How many segments the panel will show - counted from the same reconciliation it renders.
     *
     * <p>This counted review groups only, so a manager with roles recorded but not yet reviewed
     * was told there were fewer segments than the page then displayed.
     */
    public Future<Long> countCareerSegmentsByManager(long managerId) {
        return countCareerSegmentsByManager(managerId, null, false);
    }

    public Future<Long> countCareerSegmentsByManager(long managerId, UUID viewerId, boolean isAdmin) {
        return db.preparedQuery("SELECT COUNT(*) FROM (" + SEGMENTS_SQL + ") seg")
            .execute(Tuple.of(managerId, 0, 0, isAdmin, viewerId))
            .map(rows -> rows.iterator().next().getLong(0));
    }

    public Future<Long> countByManager(long managerId, UUID userIdFilter) {
        return countByManager(managerId, userIdFilter, null, false);
    }

    /** Counts what this caller can see, so the number above the list matches the list. */
    public Future<Long> countByManager(long managerId, UUID userIdFilter,
                                       UUID viewerId, boolean isAdmin) {
        if (userIdFilter != null) {
            return db.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND user_id = $2 AND deleted_at IS NULL")
                .execute(Tuple.of(managerId, userIdFilter))
                .map(rows -> rows.iterator().next().getLong(0));
        }
        return db.preparedQuery(
                "SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND deleted_at IS NULL "
                + "AND (weight = FALSE OR weight_expires_on IS NULL OR weight_expires_on > CURRENT_DATE) "
                + "AND (disposition = 'live' OR $2 = TRUE OR ($3::uuid IS NOT NULL AND user_id = $3))")
            .execute(Tuple.of(managerId, isAdmin, viewerId))
            .map(rows -> rows.iterator().next().getLong(0));
    }

    public Future<RowSet<Row>> findByUser(UUID userId, int limit, int offset) {
        return db.preparedQuery("""
                SELECT r.id, r.manager_id, r.author, r.overall_rating,
                    r.communication_style, r.perceived_approachability,
                    r.perceived_clarity_of_expectations, r.feedback_style,
                    r.perceived_supportiveness, r.decision_making_style,
                    r.organization_and_planning_style, r.delegation_style,
                    r.perceived_professional_demeanor, r.overall_working_experience,
                    r.manager_company, r.manager_title, r.text, r.verified, r.helpful_count,
                    r.created_at, r.updated_at, r.worked_from, r.worked_until,
                    r.manager_role_start, r.manager_role_end,
                """
                // Same cap as the manager's own rating list, from the same fragment: "My Reviews"
                // went on saying "to present" for a role the career history had already closed.
                + EFFECTIVE_WORKED_UNTIL + """
                    , m.name AS manager_name, m.image AS manager_image, m.status AS manager_status
                FROM reviews r
                JOIN managers m ON m.id = r.manager_id
                WHERE r.user_id = $1 AND r.deleted_at IS NULL
                ORDER BY r.created_at DESC LIMIT $2 OFFSET $3
                """)
            .execute(Tuple.of(userId, limit, offset));
    }

    public Future<Long> countByUser(UUID userId) {
        return db.preparedQuery("SELECT COUNT(*) FROM reviews WHERE user_id = $1 AND deleted_at IS NULL")
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().next().getLong(0));
    }

    /**
     * Lightweight query — returns just the fields needed for cap / overlap / role-duplicate checks.
     * No JOIN, no large text columns.
     */
    public Future<RowSet<Row>> findByUserForValidation(UUID userId, String author) {
        return db.preparedQuery(
                "SELECT id, manager_id, manager_title, manager_company, worked_from, worked_until, " +
                "manager_role_start, manager_role_end " +
                "FROM reviews WHERE (user_id = $1 OR (author = $2 AND user_id IS NULL)) AND deleted_at IS NULL AND weight = FALSE")
            .execute(Tuple.of(userId, author));
    }

    /** Returns role-period rows for all reviews of a manager (any user). Used to detect concurrent-role conflicts. */
    public Future<RowSet<Row>> findRolePeriodsForManager(long managerId) {
        return db.preparedQuery(
                "SELECT id, manager_title, manager_company, manager_role_start, manager_role_end " +
                "FROM reviews WHERE manager_id = $1 AND manager_role_start IS NOT NULL AND deleted_at IS NULL")
            .execute(Tuple.of(managerId));
    }

    /**
     * How much of today's allowance this user has spent.
     *
     * Deletions count. Without them the daily limit refunds itself: a deleted review no longer
     * satisfies {@code deleted_at IS NULL}, so the count drops and another submission is allowed.
     * The 30-day cooldown does not close that, because it is keyed per manager - review one
     * manager, delete, review the next, and the limit never binds.
     *
     * The same reasoning, and the same fix, as InterviewRepository.countSubmittedTodayByUser.
     * That one was written correctly and this one was not, which is what two copies of a rule do.
     */
    public Future<Long> countSubmittedTodayByUser(UUID userId) {
        return db.preparedQuery("""
                SELECT (
                    SELECT COUNT(*) FROM reviews
                    WHERE user_id = $1 AND created_at >= current_date AND deleted_at IS NULL
                ) + (
                    SELECT COUNT(*) FROM review_deletions
                    WHERE user_id = $1 AND deleted_at >= current_date
                ) AS c
                """)
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().next().getLong("c"));
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    public Future<Row> create(long managerId, UUID userId, String author,
                               double overallRating,
                               double communicationStyle, double perceivedApproachability,
                               double perceivedClarityOfExpectations, double feedbackStyle,
                               double perceivedSupportiveness, double decisionMakingStyle,
                               double organizationAndPlanningStyle, double delegationStyle,
                               double perceivedProfessionalDemeanor, double overallWorkingExperience,
                               String managerCompany, String managerTitle, String text,
                               LocalDate workedFrom, LocalDate workedUntil,
                               LocalDate managerRoleStart, LocalDate managerRoleEnd) {
        return db.preparedQuery("""
                INSERT INTO reviews (
                    manager_id, user_id, author, overall_rating,
                    communication_style, perceived_approachability, perceived_clarity_of_expectations,
                    feedback_style, perceived_supportiveness, decision_making_style,
                    organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                    overall_working_experience, manager_company, manager_title, text,
                    worked_from, worked_until, manager_role_start, manager_role_end,
                    verified, helpful_count, created_at, updated_at
                )
                VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,true,0,now(),now())
                RETURNING *
                """)
            .execute(Tuple.of(
                managerId, userId, author, overallRating,
                communicationStyle, perceivedApproachability, perceivedClarityOfExpectations,
                feedbackStyle, perceivedSupportiveness, decisionMakingStyle,
                organizationAndPlanningStyle, delegationStyle, perceivedProfessionalDemeanor,
                overallWorkingExperience, managerCompany, managerTitle, text,
                workedFrom, workedUntil, managerRoleStart, managerRoleEnd
            ))
            .map(rows -> rows.iterator().next());
    }

    /**
     * Edits a review, leaving its location exactly as it was.
     *
     * <p>The overload below is for the case where the person actually reopened the location field.
     * Keeping them separate is deliberate: "no location was sent" and "the location was cleared"
     * are different intents, and a single nullable parameter cannot tell them apart.
     */
    public Future<Optional<Row>> update(UUID reviewId, long managerId, UUID callerId, String author,
                                         double overallRating,
                                         double communicationStyle, double perceivedApproachability,
                                         double perceivedClarityOfExpectations, double feedbackStyle,
                                         double perceivedSupportiveness, double decisionMakingStyle,
                                         double organizationAndPlanningStyle, double delegationStyle,
                                         double perceivedProfessionalDemeanor, double overallWorkingExperience,
                                         String managerCompany, String managerTitle, String text,
                                         LocalDate workedFrom, LocalDate workedUntil,
                                         LocalDate managerRoleStart, LocalDate managerRoleEnd) {
        return update(db, reviewId, managerId, callerId, author, overallRating,
            communicationStyle, perceivedApproachability, perceivedClarityOfExpectations,
            feedbackStyle, perceivedSupportiveness, decisionMakingStyle,
            organizationAndPlanningStyle, delegationStyle, perceivedProfessionalDemeanor,
            overallWorkingExperience, managerCompany, managerTitle, text,
            workedFrom, workedUntil, managerRoleStart, managerRoleEnd, null);
    }

    /**
     * Edits a review and, when {@code declared} is non-null, restates where it happened.
     *
     * <p>A null {@code declared} means the request carried no {@code declaredPrecision} at all, and
     * the stored location is left untouched. That is the common case - somebody fixing their stars
     * - and re-copying the manager's current location there would silently migrate an old opinion
     * every time the manager changed branch.
     *
     * <p>A non-null one replaces all five columns together, so coarsening an exact pick clears the
     * building rather than leaving a city that contradicts an address.
     */
    public Future<Optional<Row>> update(SqlClient conn,
                                         UUID reviewId, long managerId, UUID callerId, String author,
                                         double overallRating,
                                         double communicationStyle, double perceivedApproachability,
                                         double perceivedClarityOfExpectations, double feedbackStyle,
                                         double perceivedSupportiveness, double decisionMakingStyle,
                                         double organizationAndPlanningStyle, double delegationStyle,
                                         double perceivedProfessionalDemeanor, double overallWorkingExperience,
                                         String managerCompany, String managerTitle, String text,
                                         LocalDate workedFrom, LocalDate workedUntil,
                                         LocalDate managerRoleStart, LocalDate managerRoleEnd,
                                         DeclaredLocation declared) {
        // COALESCE is not an option here: it cannot express "clear this column", which is exactly
        // what coarsening an exact pick has to do. Two statements, one intent each.
        String locationSql = declared == null ? "" : """
                    , declared_country = $23, declared_state = $24, declared_city = $25,
                      declared_precision = $26, company_location_id = $27
                """;
        Tuple params = Tuple.of(
            overallRating, communicationStyle, perceivedApproachability,
            perceivedClarityOfExpectations, feedbackStyle, perceivedSupportiveness,
            decisionMakingStyle, organizationAndPlanningStyle, delegationStyle,
            perceivedProfessionalDemeanor, overallWorkingExperience,
            managerCompany, managerTitle, text, workedFrom, workedUntil,
            author, reviewId, managerId, callerId,
            managerRoleStart, managerRoleEnd);
        if (declared != null) {
            params.addString(declared.country()).addString(declared.state())
                  .addString(declared.city()).addString(declared.precision())
                  .addValue(declared.companyLocationId());
        }
        return conn.preparedQuery("""
                UPDATE reviews SET
                    overall_rating = $1,
                    communication_style = $2, perceived_approachability = $3,
                    perceived_clarity_of_expectations = $4, feedback_style = $5,
                    perceived_supportiveness = $6, decision_making_style = $7,
                    organization_and_planning_style = $8, delegation_style = $9,
                    perceived_professional_demeanor = $10, overall_working_experience = $11,
                    manager_company = $12, manager_title = $13, text = $14,
                    worked_from = $15, worked_until = $16, author = $17,
                    manager_role_start = $21, manager_role_end = $22,
                    updated_at = now()
                """ + locationSql + """
                WHERE id = $18 AND manager_id = $19 AND user_id = $20
                RETURNING *
                """)
            .execute(params)
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /**
     * The columns the location read model counts, for one review, inside a caller's transaction.
     *
     * <p>Read before an edit so the projection can subtract what the review used to contribute
     * before adding what it now does. Named explicitly rather than {@code SELECT *} - a projection
     * that silently picks up a new column is a projection whose arithmetic changed without anyone
     * deciding it should.
     */
    public Future<Optional<Row>> findForProjection(SqlClient conn, UUID reviewId) {
        return conn.preparedQuery("""
                SELECT overall_rating,
                       communication_style, perceived_approachability,
                       perceived_clarity_of_expectations, feedback_style,
                       perceived_supportiveness, decision_making_style,
                       organization_and_planning_style, delegation_style,
                       perceived_professional_demeanor, overall_working_experience,
                       declared_country, declared_state, declared_city, company_location_id
                FROM reviews WHERE id = $1
                """)
            .execute(Tuple.of(reviewId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** Returns true if the review was found and belonged to the user. */
    public Future<Optional<UUID>> findOwnerUserId(UUID reviewId, long managerId) {
        return db.preparedQuery("SELECT user_id FROM reviews WHERE id = $1 AND manager_id = $2")
            .execute(Tuple.of(reviewId, managerId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next().getUUID("user_id"))
                : Optional.empty());
    }

    /** Soft-deletes a review: hides it from public queries and strips user_id immediately.
     *  After 3 days the review resurfaces as anonymous via {@link #restoreExpiredDeletions()}. */
    public Future<Void> delete(UUID reviewId, long managerId) {
        return delete(db, reviewId, managerId);
    }

    /** As above, inside a caller's transaction - so the projection moves with the hide. */
    public Future<Void> delete(SqlClient conn, UUID reviewId, long managerId) {
        return conn.preparedQuery(
                "UPDATE reviews SET deleted_at = now(), user_id = NULL WHERE id = $1 AND manager_id = $2")
            .execute(Tuple.of(reviewId, managerId))
            .mapEmpty();
    }

    /** Restores reviews whose 3-day soft-delete window has expired, making them anonymous. */
    public Future<Integer> restoreExpiredDeletions() {
        return db.preparedQuery(
                "UPDATE reviews SET deleted_at = NULL " +
                "WHERE deleted_at IS NOT NULL AND deleted_at < now() - INTERVAL '3 days'")
            .execute()
            .map(RowSet::rowCount);
    }

    /** Records that a user deleted a review for a manager (for the 30-day re-review cooldown). */
    public Future<Void> recordDeletion(UUID userId, long managerId) {
        return db.preparedQuery("INSERT INTO review_deletions (user_id, manager_id) VALUES ($1, $2)")
            .execute(Tuple.of(userId, managerId))
            .mapEmpty();
    }

    /**
     * Returns the most recent deletion timestamp for this user+manager pair within the last 30 days,
     * or empty if none exists (i.e. no active cooldown).
     */
    public Future<Optional<java.time.OffsetDateTime>> findRecentDeletion(UUID userId, long managerId) {
        return db.preparedQuery("""
                SELECT deleted_at FROM review_deletions
                WHERE user_id = $1 AND manager_id = $2
                  -- Bound, not written out: the cooldown is one number, and it lives in
                  -- SubmissionLimits. It used to appear here and again in the caller's Java.
                  AND deleted_at > now() - make_interval(days => $3)
                ORDER BY deleted_at DESC
                LIMIT 1
                """)
            .execute(Tuple.of(userId, managerId, org.werkpages.service.SubmissionLimits.COOLDOWN_DAYS))
            .map(rows -> {
                if (!rows.iterator().hasNext()) return Optional.empty();
                return Optional.of(rows.iterator().next().getOffsetDateTime("deleted_at"));
            });
    }

    /** Moves reviews from one manager to another, skipping users who already reviewed keepId. */
    public Future<Integer> moveToManager(long fromManagerId, long toManagerId) {
        // Skip seed reviews (weight = TRUE) when the target already has one —
        // idx_one_seed_per_manager allows only one seed per manager. The leftover
        // seed on the source is cleaned up by the subsequent deleteByManager call.
        // Skip only true role duplicates (same user + same manager_company + same manager_title
        // already exists on the target). Blocking on user_id alone was wrong — a user who
        // reviewed the same manager at two different companies/roles should have both reviews
        // preserved after a merge.
        return db.preparedQuery("""
                UPDATE reviews SET manager_id = $1
                WHERE manager_id = $2
                  AND deleted_at IS NULL
                  AND (weight = FALSE OR NOT EXISTS (
                      SELECT 1 FROM reviews WHERE manager_id = $1 AND weight = TRUE
                  ))
                  AND NOT EXISTS (
                      SELECT 1 FROM reviews t
                      WHERE t.manager_id = $1
                        AND t.user_id IS NOT NULL
                        AND t.user_id = reviews.user_id
                        AND LOWER(TRIM(t.manager_company)) = LOWER(TRIM(reviews.manager_company))
                        AND LOWER(TRIM(t.manager_title))   = LOWER(TRIM(reviews.manager_title))
                  )
                """)
            .execute(Tuple.of(toManagerId, fromManagerId))
            .map(RowSet::rowCount);
    }

    public Future<Void> deleteByManager(long managerId) {
        return db.preparedQuery("DELETE FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .mapEmpty();
    }

    private static final String[] SEED_ADJ    = {
        "Brave", "Swift", "Bold", "Calm", "Keen", "Wise", "Fair", "Kind",
        "Sharp", "Quiet", "Clear", "Warm", "Cool", "Bright", "Loyal"
    };
    private static final String[] SEED_ANIMAL = {
        "Falcon", "Tiger", "Eagle", "Wolf", "Bison", "Crane", "Lynx",
        "Otter", "Raven", "Gecko", "Heron", "Panda", "Finch", "Moose"
    };

    /** Inserts a system-generated placeholder review for a newly created ghost manager. */
    public Future<Row> createSeedReview(long managerId, String managerCompany, String managerTitle) {
        Random rng = new Random();
        // Target overall in 3.5–4.9 range; each category must be a whole number (1–5)
        // matching what the star-rating UI produces.
        double target = 3.5 + rng.nextDouble() * 1.4;
        int[] cats = new int[10];
        for (int i = 0; i < 10; i++) {
            double v = target + (rng.nextDouble() - 0.5) * 2;
            cats[i] = Math.min(5, Math.max(3, (int) Math.round(v)));
        }
        // overall_rating mirrors how real reviews work: average of the 10 categories
        double overall = Math.round(
            java.util.Arrays.stream(cats).average().orElse(4.0) * 10.0) / 10.0;
        // At least 1 day ago (never today), random time-of-day so it doesn't always land at midnight
        int daysAgo              = rng.nextInt(179) + 1;
        OffsetDateTime createdAt = LocalDate.now()
            .minusDays(daysAgo)
            .atTime(rng.nextInt(24), rng.nextInt(60), rng.nextInt(60))
            .atOffset(java.time.ZoneOffset.UTC);
        LocalDate workedFrom = createdAt.toLocalDate().minusMonths(12 + rng.nextInt(24));
        String author = SEED_ADJ[rng.nextInt(SEED_ADJ.length)]
            + SEED_ANIMAL[rng.nextInt(SEED_ANIMAL.length)]
            + (10 + rng.nextInt(90));

        return db.preparedQuery("""
                INSERT INTO reviews (
                    manager_id, user_id, author, overall_rating,
                    communication_style, perceived_approachability, perceived_clarity_of_expectations,
                    feedback_style, perceived_supportiveness, decision_making_style,
                    organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                    overall_working_experience, manager_company, manager_title,
                    worked_from, verified, helpful_count, weight,
                    created_at, updated_at
                )
                VALUES ($1, NULL, $2, $3,
                        $4, $5, $6, $7, $8, $9, $10, $11, $12, $13,
                        $14, $15, $16,
                        true, 0, true,
                        $17, $17)
                RETURNING *
                """)
            .execute(Tuple.of(
                managerId, author, overall,
                cats[0], cats[1], cats[2], cats[3], cats[4],
                cats[5], cats[6], cats[7], cats[8], cats[9],
                managerCompany, managerTitle, workedFrom, createdAt
            ))
            .map(rows -> rows.iterator().next());
    }

    /**
     * Starts the placeholder's 14-day countdown, once.
     *
     * <p>{@code AND weight_expires_on IS NULL} is the whole point of this clause. Without it every
     * subsequent real review re-ran this UPDATE and pushed the expiry out another fortnight, so a
     * manager receiving a review even occasionally kept its placeholder <em>forever</em> - the
     * countdown restarted before it could ever finish. The clock is meant to start when the first
     * real review arrives and then run down regardless of what else happens.
     */
    public Future<Void> scheduleSeedExpiry(long managerId) {
        return db.preparedQuery(
                "UPDATE reviews SET weight_expires_on = now() + INTERVAL '14 days' "
              + "WHERE manager_id = $1 AND weight = TRUE AND weight_expires_on IS NULL")
            .execute(Tuple.of(managerId))
            .mapEmpty();
    }

    /**
     * Deletes placeholder reviews whose countdown has run out.
     *
     * <p>Expiry already removed them from the reviews list and from the cached rating, so this
     * changes no number anybody sees - it removes the row itself. Until now nothing ever did:
     * "expired" meant ignored-everywhere-but-still-present, and the placeholders accumulated in
     * the table indefinitely.
     *
     * <p><b>{@code user_id IS NULL} is a deliberate second lock on the door.</b> A placeholder is
     * defined by {@code weight = TRUE}, and that alone is sufficient: the column is
     * {@code NOT NULL DEFAULT FALSE}, the real review INSERT never names it, no UPDATE anywhere
     * sets it, and both the generator and the V33 backfill write {@code user_id = NULL}. So a
     * real review cannot carry it. But this statement deletes rows in bulk and unattended, and if
     * that invariant were ever broken by a migration or a manual fix, the cost would be somebody's
     * real review disappearing with nothing to recover it from. The guard makes such a row survive
     * instead. V36 states the same pair as the definition of a seed, and the other seed deletes in
     * this codebase already pair them.
     *
     * <p>Must run <em>after</em> the expired-weight recalculation in the same sweep. That query
     * finds managers by {@code EXISTS (expired placeholder)}, so deleting first would hide every
     * manager whose cached count had not yet caught up, and their figures would stay wrong with
     * nothing left to point at the problem.
     */
    public Future<Integer> deleteExpiredSeedReviews() {
        return db.preparedQuery(
                "DELETE FROM reviews WHERE weight = TRUE AND user_id IS NULL "
              + "AND weight_expires_on IS NOT NULL AND weight_expires_on <= CURRENT_DATE")
            .execute()
            .map(RowSet::rowCount);
    }

    public Future<Void> deleteSeedReview(long managerId) {
        return db.preparedQuery("DELETE FROM reviews WHERE manager_id = $1 AND weight = TRUE")
            .execute(Tuple.of(managerId))
            .mapEmpty();
    }

    public Future<Void> deleteByDraftToken(UUID draftToken) {
        return db.preparedQuery("DELETE FROM reviews WHERE draft_token = $1 AND user_id IS NULL")
            .execute(Tuple.of(draftToken))
            .mapEmpty();
    }

    /**
     * Returns the single most current review for a manager: a review with
     * {@code worked_until IS NULL} (still working there) takes precedence;
     * otherwise the review with the latest {@code worked_from} is returned.
     * Returns null via the future if no reviews exist.
     */
    public Future<Row> findMostCurrentReviewForManager(long managerId) {
        return db.preparedQuery("""
                SELECT id, manager_company, manager_title, worked_from, worked_until
                -- Live only, because this drives the manager's displayed company, title and logo.
                -- A rating being withheld pending proof must not rewrite what the public profile
                -- says somebody's current role is.
                FROM reviews r
                WHERE
                """ + ReviewSql.live("r") + """
                  AND manager_id = $1
                ORDER BY
                    CASE WHEN worked_until IS NULL THEN 0 ELSE 1 END,
                    worked_from DESC
                LIMIT 1
                """)
            .execute(Tuple.of(managerId))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next() : null);
    }
}

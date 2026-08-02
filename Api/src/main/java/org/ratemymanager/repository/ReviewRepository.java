package org.ratemymanager.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

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

    public Future<RowSet<Row>> findByManager(long managerId, int limit, int offset,
                                              String sortBy, UUID userIdFilter) {
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
            String sql = String.format("SELECT * FROM reviews WHERE manager_id = $1 AND user_id = $4 AND deleted_at IS NULL ORDER BY %s LIMIT $2 OFFSET $3", orderBy);
            return db.preparedQuery(sql).execute(Tuple.of(managerId, limit, offset, userIdFilter));
        } else {
            String sql = String.format(
                "SELECT * FROM reviews WHERE manager_id = $1 AND deleted_at IS NULL AND (weight = FALSE OR weight_expires_on IS NULL OR weight_expires_on > CURRENT_DATE) ORDER BY %s LIMIT $2 OFFSET $3", orderBy);
            return db.preparedQuery(sql).execute(Tuple.of(managerId, limit, offset));
        }
    }

    public Future<RowSet<Row>> findCareerSegmentsByManager(long managerId, int limit, int offset) {
        return db.preparedQuery("""
                SELECT
                  MIN(manager_company)                        AS company,
                  MIN(manager_title)                          AS role,
                  MIN(worked_from)                            AS start_date,
                  MAX(worked_until)                           AS end_date,
                  BOOL_OR(worked_until IS NULL)               AS is_current,
                  AVG(overall_rating)                         AS avg_rating,
                  COUNT(*)                                    AS review_count,
                  AVG(communication_style)                    AS communication_style,
                  AVG(perceived_approachability)              AS perceived_approachability,
                  AVG(perceived_clarity_of_expectations)      AS perceived_clarity_of_expectations,
                  AVG(feedback_style)                         AS feedback_style,
                  AVG(perceived_supportiveness)               AS perceived_supportiveness,
                  AVG(decision_making_style)                  AS decision_making_style,
                  AVG(organization_and_planning_style)        AS organization_and_planning_style,
                  AVG(delegation_style)                       AS delegation_style,
                  AVG(perceived_professional_demeanor)        AS perceived_professional_demeanor,
                  AVG(overall_working_experience)             AS overall_working_experience,
                  MIN(manager_role_start)                     AS manager_role_start,
                  MAX(manager_role_end)                       AS manager_role_end
                FROM reviews
                WHERE manager_id = $1 AND deleted_at IS NULL
                GROUP BY LOWER(TRIM(manager_company)), LOWER(TRIM(manager_title))
                ORDER BY MIN(worked_from) ASC NULLS LAST
                LIMIT $2 OFFSET $3
                """)
            .execute(Tuple.of(managerId, limit, offset));
    }

    public Future<Long> countCareerSegmentsByManager(long managerId) {
        return db.preparedQuery("""
                SELECT COUNT(*) FROM (
                  SELECT 1 FROM reviews
                  WHERE manager_id = $1 AND deleted_at IS NULL
                  GROUP BY LOWER(TRIM(manager_company)), LOWER(TRIM(manager_title))
                ) sub
                """)
            .execute(Tuple.of(managerId))
            .map(rows -> rows.iterator().next().getLong(0));
    }

    public Future<Long> countByManager(long managerId, UUID userIdFilter) {
        if (userIdFilter != null) {
            return db.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND user_id = $2 AND deleted_at IS NULL")
                .execute(Tuple.of(managerId, userIdFilter))
                .map(rows -> rows.iterator().next().getLong(0));
        }
        return db.preparedQuery(
                "SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND deleted_at IS NULL AND (weight = FALSE OR weight_expires_on IS NULL OR weight_expires_on > CURRENT_DATE)")
            .execute(Tuple.of(managerId))
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
                    m.name AS manager_name, m.image AS manager_image, m.status AS manager_status
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
    public Future<RowSet<Row>> findByUserForValidation(UUID userId) {
        return db.preparedQuery(
                "SELECT id, manager_id, manager_title, manager_company, worked_from, worked_until, " +
                "manager_role_start, manager_role_end " +
                "FROM reviews WHERE user_id = $1 AND deleted_at IS NULL")
            .execute(Tuple.of(userId));
    }

    /** Returns role-period rows for all reviews of a manager (any user). Used to detect concurrent-role conflicts. */
    public Future<RowSet<Row>> findRolePeriodsForManager(long managerId) {
        return db.preparedQuery(
                "SELECT id, manager_title, manager_company, manager_role_start, manager_role_end " +
                "FROM reviews WHERE manager_id = $1 AND manager_role_start IS NOT NULL AND deleted_at IS NULL")
            .execute(Tuple.of(managerId));
    }

    public Future<Long> countSubmittedTodayByUser(UUID userId) {
        return db.preparedQuery("SELECT COUNT(*) FROM reviews WHERE user_id = $1 AND created_at >= current_date AND deleted_at IS NULL")
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().next().getLong(0));
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
        return db.preparedQuery("""
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
                WHERE id = $18 AND manager_id = $19 AND user_id = $20
                RETURNING *
                """)
            .execute(Tuple.of(
                overallRating, communicationStyle, perceivedApproachability,
                perceivedClarityOfExpectations, feedbackStyle, perceivedSupportiveness,
                decisionMakingStyle, organizationAndPlanningStyle, delegationStyle,
                perceivedProfessionalDemeanor, overallWorkingExperience,
                managerCompany, managerTitle, text, workedFrom, workedUntil,
                author, reviewId, managerId, callerId,
                managerRoleStart, managerRoleEnd
            ))
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
        return db.preparedQuery(
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
                  AND deleted_at > now() - INTERVAL '30 days'
                ORDER BY deleted_at DESC
                LIMIT 1
                """)
            .execute(Tuple.of(userId, managerId))
            .map(rows -> {
                if (!rows.iterator().hasNext()) return Optional.empty();
                return Optional.of(rows.iterator().next().getOffsetDateTime("deleted_at"));
            });
    }

    /** Moves reviews from one manager to another, skipping users who already reviewed keepId. */
    public Future<Integer> moveToManager(long fromManagerId, long toManagerId) {
        return db.preparedQuery("""
                UPDATE reviews SET manager_id = $1
                WHERE manager_id = $2
                  AND deleted_at IS NULL
                  AND (user_id IS NULL
                       OR user_id NOT IN (
                           SELECT user_id FROM reviews
                           WHERE manager_id = $1 AND user_id IS NOT NULL
                       ))
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

    public Future<Void> scheduleSeedExpiry(long managerId) {
        return db.preparedQuery(
                "UPDATE reviews SET weight_expires_on = now() + INTERVAL '14 days' WHERE manager_id = $1 AND weight = TRUE")
            .execute(Tuple.of(managerId))
            .mapEmpty();
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
                FROM reviews
                WHERE manager_id = $1 AND deleted_at IS NULL
                ORDER BY
                    CASE WHEN worked_until IS NULL THEN 0 ELSE 1 END,
                    worked_from DESC
                LIMIT 1
                """)
            .execute(Tuple.of(managerId))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next() : null);
    }
}

package org.ratemymanager.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.Optional;
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

        if (userIdFilter != null) {
            String sql = String.format("SELECT * FROM reviews WHERE manager_id = $1 AND user_id = $4 ORDER BY %s LIMIT $2 OFFSET $3", orderBy);
            return db.preparedQuery(sql).execute(Tuple.of(managerId, limit, offset, userIdFilter));
        } else {
            String sql = String.format("SELECT * FROM reviews WHERE manager_id = $1 ORDER BY %s LIMIT $2 OFFSET $3", orderBy);
            return db.preparedQuery(sql).execute(Tuple.of(managerId, limit, offset));
        }
    }

    public Future<Long> countByManager(long managerId, UUID userIdFilter) {
        if (userIdFilter != null) {
            return db.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND user_id = $2")
                .execute(Tuple.of(managerId, userIdFilter))
                .map(rows -> rows.iterator().next().getLong(0));
        }
        return db.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rows -> rows.iterator().next().getLong(0));
    }

    public Future<RowSet<Row>> findByUser(UUID userId) {
        return db.preparedQuery("""
                SELECT r.id, r.manager_id, r.author, r.overall_rating,
                    r.communication_style, r.perceived_approachability,
                    r.perceived_clarity_of_expectations, r.feedback_style,
                    r.perceived_supportiveness, r.decision_making_style,
                    r.organization_and_planning_style, r.delegation_style,
                    r.perceived_professional_demeanor, r.overall_working_experience,
                    r.manager_company, r.manager_title, r.text, r.verified, r.helpful_count,
                    r.created_at, r.updated_at, r.worked_from, r.worked_until,
                    m.name AS manager_name, m.image AS manager_image, m.status AS manager_status
                FROM reviews r
                JOIN managers m ON m.id = r.manager_id
                WHERE r.user_id = $1
                ORDER BY r.created_at DESC LIMIT 500
                """)
            .execute(Tuple.of(userId));
    }

    public Future<Long> countSubmittedTodayByUser(UUID userId) {
        return db.preparedQuery("SELECT COUNT(*) FROM reviews WHERE user_id = $1 AND created_at >= current_date")
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
                               LocalDate workedFrom, LocalDate workedUntil) {
        return db.preparedQuery("""
                INSERT INTO reviews (
                    manager_id, user_id, author, overall_rating,
                    communication_style, perceived_approachability, perceived_clarity_of_expectations,
                    feedback_style, perceived_supportiveness, decision_making_style,
                    organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                    overall_working_experience, manager_company, manager_title, text,
                    worked_from, worked_until, verified, helpful_count, created_at, updated_at
                )
                VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,true,0,now(),now())
                RETURNING *
                """)
            .execute(Tuple.of(
                managerId, userId, author, overallRating,
                communicationStyle, perceivedApproachability, perceivedClarityOfExpectations,
                feedbackStyle, perceivedSupportiveness, decisionMakingStyle,
                organizationAndPlanningStyle, delegationStyle, perceivedProfessionalDemeanor,
                overallWorkingExperience, managerCompany, managerTitle, text,
                workedFrom, workedUntil
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
                                         LocalDate workedFrom, LocalDate workedUntil) {
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
                author, reviewId, managerId, callerId
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

    public Future<Void> delete(UUID reviewId, long managerId) {
        return db.preparedQuery("DELETE FROM reviews WHERE id = $1 AND manager_id = $2")
            .execute(Tuple.of(reviewId, managerId))
            .mapEmpty();
    }

    /** Moves reviews from one manager to another, skipping users who already reviewed keepId. */
    public Future<Integer> moveToManager(long fromManagerId, long toManagerId) {
        return db.preparedQuery("""
                UPDATE reviews SET manager_id = $1
                WHERE manager_id = $2
                  AND user_id NOT IN (SELECT user_id FROM reviews WHERE manager_id = $1)
                """)
            .execute(Tuple.of(toManagerId, fromManagerId))
            .map(RowSet::rowCount);
    }

    public Future<Void> deleteByManager(long managerId) {
        return db.preparedQuery("DELETE FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .mapEmpty();
    }
}

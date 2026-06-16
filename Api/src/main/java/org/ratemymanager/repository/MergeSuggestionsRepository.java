package org.ratemymanager.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.ratemymanager.service.AnthropicClient.ManagerProfile;

import java.util.ArrayList;
import java.util.List;

public class MergeSuggestionsRepository {

    private final SqlClient db;

    public MergeSuggestionsRepository(SqlClient db) {
        this.db = db;
    }

    /**
     * Returns candidate pairs whose names are within Levenshtein distance 2
     * and have not been evaluated yet, or were previously evaluated as DIFFERENT
     * but one side has gained new reviews since the last evaluation.
     */
    public Future<List<CandidatePair>> findCandidatePairs() {
        return db.query("""
                SELECT
                    a.id AS id_a, a.name AS name_a, a.company AS company_a,
                    a.title AS title_a, a.country AS country_a, a.state AS state_a,
                    a.city AS city_a, COALESCE(a.reviews_count, 0) AS reviews_a,
                    b.id AS id_b, b.name AS name_b, b.company AS company_b,
                    b.title AS title_b, b.country AS country_b, b.state AS state_b,
                    b.city AS city_b, COALESCE(b.reviews_count, 0) AS reviews_b,
                    ms.id AS suggestion_id,
                    ms.reviews_a_at_eval, ms.reviews_b_at_eval
                FROM managers a
                JOIN managers b ON a.id < b.id
                LEFT JOIN merge_suggestions ms
                    ON ms.manager_id_a = a.id AND ms.manager_id_b = b.id
                WHERE a.approval_status IN ('approved', 'ghost')
                  AND b.approval_status IN ('approved', 'ghost')
                  AND levenshtein(LOWER(a.name), LOWER(b.name)) <= 2
                  AND (
                    ms.id IS NULL
                    OR (
                      ms.confidence = 'DIFFERENT'
                      AND ms.status != 'dismissed'
                      AND (
                        COALESCE(a.reviews_count, 0) > ms.reviews_a_at_eval
                        OR COALESCE(b.reviews_count, 0) > ms.reviews_b_at_eval
                      )
                    )
                  )
                LIMIT 100
                """)
            .execute()
            .map(rows -> {
                List<CandidatePair> pairs = new ArrayList<>();
                for (Row row : rows) {
                    ManagerProfile profileA = new ManagerProfile(
                        row.getLong("id_a"),
                        row.getString("name_a"),
                        row.getString("company_a"),
                        row.getString("title_a"),
                        row.getString("country_a"),
                        row.getString("state_a"),
                        row.getString("city_a"),
                        row.getInteger("reviews_a")
                    );
                    ManagerProfile profileB = new ManagerProfile(
                        row.getLong("id_b"),
                        row.getString("name_b"),
                        row.getString("company_b"),
                        row.getString("title_b"),
                        row.getString("country_b"),
                        row.getString("state_b"),
                        row.getString("city_b"),
                        row.getInteger("reviews_b")
                    );
                    Long suggestionId = row.getLong("suggestion_id");
                    pairs.add(new CandidatePair(profileA, profileB, suggestionId));
                }
                return pairs;
            });
    }

    /**
     * Inserts a new suggestion or updates an existing DIFFERENT row on re-evaluation.
     */
    public Future<Void> upsert(long managerIdA, long managerIdB,
                               String confidence, String reason,
                               int reviewsA, int reviewsB) {
        String status = "DIFFERENT".equals(confidence) ? "different" : "pending";
        return db.preparedQuery("""
                INSERT INTO merge_suggestions
                    (manager_id_a, manager_id_b, confidence, reason, status,
                     reviews_a_at_eval, reviews_b_at_eval, created_at, last_evaluated_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, now(), now())
                ON CONFLICT (manager_id_a, manager_id_b) DO UPDATE SET
                    confidence        = EXCLUDED.confidence,
                    reason            = EXCLUDED.reason,
                    status            = EXCLUDED.status,
                    reviews_a_at_eval = EXCLUDED.reviews_a_at_eval,
                    reviews_b_at_eval = EXCLUDED.reviews_b_at_eval,
                    last_evaluated_at = now()
                """)
            .execute(Tuple.of(managerIdA, managerIdB, confidence, reason, status, reviewsA, reviewsB))
            .mapEmpty();
    }

    public Future<RowSet<Row>> findPending(int limit, int offset) {
        return db.preparedQuery("""
                SELECT
                    ms.id, ms.confidence, ms.reason, ms.status,
                    ms.created_at, ms.last_evaluated_at,
                    a.id AS id_a, a.name AS name_a, a.company AS company_a,
                    a.title AS title_a, a.country AS country_a,
                    COALESCE(a.reviews_count, 0) AS reviews_a,
                    b.id AS id_b, b.name AS name_b, b.company AS company_b,
                    b.title AS title_b, b.country AS country_b,
                    COALESCE(b.reviews_count, 0) AS reviews_b
                FROM merge_suggestions ms
                JOIN managers a ON a.id = ms.manager_id_a
                JOIN managers b ON b.id = ms.manager_id_b
                WHERE ms.status = 'pending'
                ORDER BY
                    CASE ms.confidence WHEN 'SAME' THEN 0 WHEN 'LIKELY_SAME' THEN 1 ELSE 2 END,
                    ms.last_evaluated_at DESC
                LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset));
    }

    public Future<Integer> countPending() {
        return db.query("SELECT COUNT(*) FROM merge_suggestions WHERE status = 'pending'")
            .execute()
            .map(rows -> rows.iterator().next().getInteger(0));
    }

    public Future<Void> updateStatus(long suggestionId, String status) {
        return db.preparedQuery(
                "UPDATE merge_suggestions SET status = $1 WHERE id = $2")
            .execute(Tuple.of(status, suggestionId))
            .mapEmpty();
    }

    public record CandidatePair(ManagerProfile profileA, ManagerProfile profileB, Long existingSuggestionId) {}
}

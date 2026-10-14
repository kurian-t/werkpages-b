package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.*;

import java.util.List;
import java.util.UUID;

/**
 * The admin review queue for unverified searches.
 *
 * <p>A search request is NOT a person. It used to be stored as a {@code managers} row with
 * {@code approval_status = 'pending_approval'}, which took a manager id, a slug and a company
 * association for something that might never become anybody: one individual accumulated eight rows
 * that way, and 21 people ended up with two public profiles each.
 *
 * <p>Nothing here is ever public, so it cannot produce a tile and cannot be mistaken for a profile.
 */
public class ManagerSearchRequestRepository {

    private final SqlClient db;

    public ManagerSearchRequestRepository(SqlClient db) { this.db = db; }

    /**
     * Records a search needing review, or folds it into the request already describing it.
     *
     * <h2>Concurrency</h2>
     *
     * <p>Two visitors searching the same unknown person at the same moment must not produce two
     * queue entries. The unique index on the normalised triple makes that impossible: whichever
     * INSERT loses becomes the UPDATE branch, so the outcome is one row with {@code seen_count}
     * at two either way. No lock is taken and no read-then-write race exists, because the database
     * decides rather than the application.
     *
     * <p>A dismissed request is NOT reopened automatically. It accumulates {@code seen_since_review}
     * so that a manager people keep searching for resurfaces by weight of evidence rather than by a
     * timer - we deliberately have no cooldown, because a cooldown is a guess about duration while
     * this is a fact about how many.
     */
    public Future<Row> record(String firstName, String lastName, String companyText, String titleText,
                              String nameNorm, String companyNorm, String titleNorm,
                              Long resolvedCompanyId, List<Long> candidateManagerIds,
                              String reason, String nameClass, String companyClass, String titleClass) {
        return db.preparedQuery("""
                INSERT INTO manager_search_requests
                       (first_name, last_name, company_text, title_text,
                        name_norm, company_norm, title_norm,
                        resolved_company_id, candidate_manager_ids,
                        reason, name_match_class, best_company_class, best_title_class)
                VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
                ON CONFLICT (name_norm, company_norm, title_norm) DO UPDATE
                   SET seen_count        = manager_search_requests.seen_count + 1,
                       seen_since_review = manager_search_requests.seen_since_review + 1,
                       last_seen_at      = now(),
                       -- refresh the evidence an admin will look at, without touching the review
                       -- decision: status and resolution are the admin's, not the searcher's.
                       candidate_manager_ids = EXCLUDED.candidate_manager_ids,
                       resolved_company_id   = COALESCE(EXCLUDED.resolved_company_id,
                                                        manager_search_requests.resolved_company_id),
                       best_company_class    = EXCLUDED.best_company_class,
                       best_title_class      = EXCLUDED.best_title_class
                RETURNING *
                """)
            .execute(Tuple.tuple()
                .addString(firstName).addString(lastName).addString(companyText).addString(titleText)
                .addString(nameNorm).addString(companyNorm).addString(titleNorm)
                .addLong(resolvedCompanyId)
                .addArrayOfLong(candidateManagerIds == null ? new Long[0]
                                                            : candidateManagerIds.toArray(new Long[0]))
                .addString(reason).addString(nameClass).addString(companyClass).addString(titleClass))
            .map(rows -> rows.iterator().next());
    }

    /** The queue: most-searched first, so weight of evidence leads rather than age. */
    public Future<RowSet<Row>> findOpen(int limit, int offset) {
        return db.preparedQuery("""
                SELECT * FROM manager_search_requests
                 WHERE status = 'open'
                 ORDER BY seen_count DESC, last_seen_at DESC
                 LIMIT $1 OFFSET $2
                """).execute(Tuple.of(limit, offset));
    }

    /** Requests previously dismissed but searched again since - evidence we may have been wrong. */
    public Future<RowSet<Row>> findDismissedButRecurring(int limit) {
        return db.preparedQuery("""
                SELECT * FROM manager_search_requests
                 WHERE status = 'dismissed' AND seen_since_review > 0
                 ORDER BY seen_since_review DESC, last_seen_at DESC
                 LIMIT $1
                """).execute(Tuple.of(limit));
    }

    public Future<Row> findById(long id) {
        return db.preparedQuery("SELECT * FROM manager_search_requests WHERE id = $1")
            .execute(Tuple.of(id))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next() : null);
    }

    /**
     * An admin's decision. {@code resolution} answers the only three questions there are: a new
     * person, an existing person's additional role, or not verifiable.
     *
     * <p>{@code seen_since_review} resets, so the "dismissed and searched again" view counts
     * searches since THIS decision rather than since the beginning.
     */
    /**
     * Takes the decision on a request, atomically, or reports that somebody else already has.
     *
     * <p>The three admin actions used to READ the request, check its status was {@code open}, and
     * only then do the work. Two admins opening the queue together both read {@code open}, both
     * passed the check, and both acted: two managers created from one request, or the same job
     * added twice to a career history. The window is the length of a database round trip plus
     * however long the admin UI takes, which is exactly the window two people clicking the same
     * row occupy.
     *
     * <p>The guard is {@code AND status = 'open'} in the UPDATE itself, so PostgreSQL decides the
     * winner. A null return means the row was not {@code open} any more, and the caller must stop
     * rather than continue on stale information.
     *
     * <p>The terminal status is written immediately rather than an intermediate "in review" one,
     * because the CHECK constraint in V94 admits only open, resolved and dismissed, and widening
     * it would be a migration for a state nothing reads. {@link #reopen} undoes the claim if the
     * work it was guarding then fails.
     */
    public Future<Row> claim(long id, String status, String resolution, UUID reviewedBy) {
        return db.preparedQuery("""
                UPDATE manager_search_requests
                   SET status = $2, resolution = $3,
                       reviewed_by = $4, reviewed_at = now(), seen_since_review = 0
                 WHERE id = $1
                   AND status = 'open'
                RETURNING *
                """)
            .execute(Tuple.tuple().addLong(id).addString(status).addString(resolution)
                          .addUUID(reviewedBy))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next() : null);
    }

    /** Records which manager the decision produced, once it exists. */
    public Future<Void> setResolvedManager(long id, Long managerId) {
        return db.preparedQuery("UPDATE manager_search_requests SET resolved_manager_id = $2 WHERE id = $1")
            .execute(Tuple.tuple().addLong(id).addLong(managerId))
            .mapEmpty();
    }

    /**
     * Puts a claimed request back in the queue because the work behind it failed.
     *
     * <p>Without this a failed creation would leave the request marked resolved with nothing to
     * show for it, and the person it describes would never be looked at again.
     */
    public Future<Void> reopen(long id) {
        return db.preparedQuery("""
                UPDATE manager_search_requests
                   SET status = 'open', resolution = NULL,
                       reviewed_by = NULL, reviewed_at = NULL
                 WHERE id = $1
                """)
            .execute(Tuple.of(id))
            .mapEmpty();
    }

    public Future<Row> resolve(long id, String status, String resolution,
                               Long resolvedManagerId, UUID reviewedBy) {
        return db.preparedQuery("""
                UPDATE manager_search_requests
                   SET status = $2, resolution = $3, resolved_manager_id = $4,
                       reviewed_by = $5, reviewed_at = now(), seen_since_review = 0
                 WHERE id = $1
                RETURNING *
                """)
            .execute(Tuple.tuple().addLong(id).addString(status).addString(resolution)
                          .addLong(resolvedManagerId).addUUID(reviewedBy))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next() : null);
    }
}

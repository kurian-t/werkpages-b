package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import org.werkpages.repository.CompanyRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds and reconciles {@code company_stats_live} against the tables it is derived from.
 *
 * <p>A denormalised read table is safe architecture only with all five of the properties in
 * CLAUDE.md section 21, and the last two are these: a rebuild that can recreate the table from
 * source data alone, and a reconciliation that proves it has not drifted. Without them the table
 * is not a cache, it is a second source of truth that disagrees with the first one silently.
 *
 * <p>Why this matters more here than the word "cache" suggests: {@code findCompanyListing} selects
 * <em>from</em> this projection and filters on {@code manager_count > 0}, so a wrong row is a
 * wrong public surface rather than a wrong dashboard.
 *
 * <h2>What already maintains it, and why this class is still needed</h2>
 *
 * The manager-derived columns are maintained by a database trigger, not by application code alone:
 * {@code managers_company_stats} (V47) fires on insert, delete, and updates of
 * {@code company_id}, {@code approval_status}, {@code external_id}, {@code reviews_count},
 * {@code overall_rating} and {@code company_logo_url}, and calls {@code refresh_company_stats},
 * which ends with an explicit DELETE for the orphan case - a company whose last qualifying manager
 * has gone.
 *
 * <p>An earlier version of this comment said the opposite: that {@code refreshCompanyStats}
 * leaving orphan rows behind was the live risk. That is true of the Java method in isolation and
 * misleading about the system, because the trigger removes those rows before any application code
 * looks at them. The correction is recorded here because reasoning from the old claim leads
 * somewhere wrong.
 *
 * <p>Since V89 there are four such triggers, not one: {@code managers}, {@code companies.logo_url},
 * {@code company_reviews} and {@code interview_reviews}. V89 also made
 * {@code refresh_company_stats} write all nine columns, which is what allowed the Java writers in
 * both backends to be deleted. The workplace and interview columns, which had no trigger at all
 * and were maintained only by {@link CompanyReviewService} and {@link InterviewService}, are
 * covered by it too.
 *
 * <p>The triggers being right today is not a reason to skip a rebuild and a drift check. They are
 * objects in a shared schema that one repository migrates, they can be dropped or replaced, their
 * explicit column lists can fall behind a new write path, and anything written straight to
 * {@code company_stats_live} bypasses all of them. The question this class answers is not "do the
 * triggers work" but "if these numbers are wrong, how do I prove it and how do I get back to
 * correct".
 *
 * <p>The projection carries three independent datasets - managers, workplace ratings and interview
 * experiences - each maintained by its own write path. Three writers is three chances for one of
 * them to be missed when somebody adds a fourth way to delete a rating, which is exactly the
 * failure {@link #reconcile()} is here to make loud.
 *
 * <p>Modelled on {@link LocationStatsRebuilder}, deliberately: two read models in one product that
 * are rebuilt and checked in two different shapes is two things to learn. RateMyManagers carries
 * the same class, with the managers dataset only, since it neither reads nor writes the workplace
 * and interview columns.
 */
public class CompanyStatsRebuilder {

    private final Pool db;
    private final CompanyRepository companyRepo;

    public CompanyStatsRebuilder(Pool db, CompanyRepository companyRepo) {
        this.db = db;
        this.companyRepo = companyRepo;
    }

    /**
     * Recreates every row from the source tables.
     *
     * <p>Not incremental and not clever: this is the escape hatch for when the projection is known
     * to be wrong and the question is how to get back to correct, not how to get there quickly.
     * {@code refreshCompanyStats} already writes all nine columns from source, so the rebuild is
     * that statement plus the removal of rows whose company no longer qualifies.
     */
    public Future<Void> rebuild() {
        /*
          Stale rows first, then the upsert, in one transaction.

          refreshCompanyStats only inserts and updates, and it reaches a company through an inner
          join to managers - so a company with no qualifying managers is not in its result set at
          all and keeps whatever the projection last said about it. In normal operation the V47
          trigger has already removed that row; this statement is what makes the rebuild correct
          without depending on it, which is the whole point of a path that can reconstruct the
          table from source data alone.

          One transaction, because a rebuild is one logical operation. If the delete committed and
          the recompute then failed, the projection would be left missing rows it should hold -
          repairing it would have made it wrong in a new way, which is the one outcome a repair
          tool must never produce.
        */
        return db.withTransaction(conn ->
            conn.query("""
                    DELETE FROM company_stats_live cs
                     WHERE NOT EXISTS (
                           SELECT 1 FROM managers m
                            WHERE m.company_id = cs.company_id
                              AND m.approval_status IN ('approved', 'ghost')
                              AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%'))
                    """).execute()
                .compose(ignored -> companyRepo.refreshCompanyStats(conn)));
    }

    /** One disagreement between the projection and the source, for the operator reading it. */
    public record Drift(long companyId, String dataset, long projected, long actual) {
        @Override public String toString() {
            return "company " + companyId + " " + dataset + ": projected " + projected + ", actual " + actual;
        }
    }

    /**
     * What the projection claims, against what the source tables actually hold.
     *
     * <p>Counts only, across all three datasets. An average that disagrees always has a count that
     * disagrees behind it, and comparing rounded numerics invites false alarms over the last
     * decimal place.
     *
     * <p>Driven from a union of projected and source company ids, not a scan of the projection,
     * because the two interesting failures point in opposite directions: a row that should have
     * been removed and was not, and a company that should have a row and has none. Iterating
     * {@code company_stats_live} alone can only ever see the first, because the row it would need
     * to read is the one that is missing. The previous version of this method did exactly that and
     * could not report a missing row at all.
     */
    public Future<List<Drift>> reconcile() {
        return db.query("""
                WITH ids AS (
                    SELECT company_id FROM company_stats_live
                    UNION
                    SELECT m.company_id
                      FROM managers m
                     WHERE m.company_id IS NOT NULL
                       AND m.approval_status IN ('approved', 'ghost')
                       AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                    UNION
                    SELECT cr.company_id
                      FROM company_reviews cr
                     WHERE cr.company_id IS NOT NULL AND cr.deleted_at IS NULL
                    UNION
                    SELECT ir.company_id
                      FROM interview_reviews ir
                     WHERE ir.company_id IS NOT NULL AND ir.deleted_at IS NULL
                ),
                src AS (
                    SELECT i.company_id,
                           (SELECT COUNT(DISTINCT m.id)
                              FROM managers m
                             WHERE m.company_id = i.company_id
                               AND m.approval_status IN ('approved', 'ghost')
                               AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                           ) AS manager_count,
                           (SELECT COALESCE(SUM(m.reviews_count), 0)
                              FROM managers m
                             WHERE m.company_id = i.company_id
                               AND m.approval_status IN ('approved', 'ghost')
                               AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                           ) AS total_reviews,
                           (SELECT COUNT(*)
                              FROM company_reviews cr
                             WHERE cr.company_id = i.company_id AND cr.deleted_at IS NULL
                           ) AS workplace_count,
                           (SELECT COUNT(*)
                              FROM interview_reviews ir
                             WHERE ir.company_id = i.company_id AND ir.deleted_at IS NULL
                           ) AS interview_count
                      FROM ids i
                )
                SELECT s.company_id,
                       COALESCE(p.manager_count, 0)   AS projected_managers,
                       s.manager_count                AS actual_managers,
                       COALESCE(p.total_reviews, 0)   AS projected_reviews,
                       s.total_reviews                AS actual_reviews,
                       COALESCE(p.workplace_count, 0) AS projected_workplace,
                       s.workplace_count              AS actual_workplace,
                       COALESCE(p.interview_count, 0) AS projected_interview,
                       s.interview_count              AS actual_interview
                  FROM src s
                  LEFT JOIN company_stats_live p ON p.company_id = s.company_id
                 WHERE COALESCE(p.manager_count, 0)   <> s.manager_count
                    OR COALESCE(p.total_reviews, 0)   <> s.total_reviews
                    OR COALESCE(p.workplace_count, 0) <> s.workplace_count
                    OR COALESCE(p.interview_count, 0) <> s.interview_count
                 ORDER BY s.company_id
                """).execute()
            .map(rs -> {
                List<Drift> drifts = new ArrayList<>();
                for (Row row : rs) {
                    long id = row.getLong("company_id");
                    compare(drifts, id, "managers",  row, "projected_managers",  "actual_managers");
                    compare(drifts, id, "reviews",   row, "projected_reviews",   "actual_reviews");
                    compare(drifts, id, "workplace", row, "projected_workplace", "actual_workplace");
                    compare(drifts, id, "interview", row, "projected_interview", "actual_interview");
                }
                return drifts;
            });
    }

    private static void compare(List<Drift> drifts, long companyId, String dataset,
                                Row row, String projectedColumn, String actualColumn) {
        long projected = row.getLong(projectedColumn);
        long actual    = row.getLong(actualColumn);
        if (projected != actual) drifts.add(new Drift(companyId, dataset, projected, actual));
    }
}

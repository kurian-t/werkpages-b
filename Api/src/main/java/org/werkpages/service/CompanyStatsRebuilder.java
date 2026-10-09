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
 * <p>The projection carries three independent datasets now - managers, workplace ratings and
 * interview experiences - each maintained by its own write path. Three writers is three chances
 * for one of them to be missed when somebody adds a fourth way to delete a rating, which is
 * exactly the failure {@link #reconcile()} is here to make loud.
 *
 * <p>Modelled on {@link LocationStatsRebuilder}, deliberately: two read models in one product
 * that are rebuilt and checked in two different shapes is two things to learn.
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
     * <p>Not incremental and not clever: this is the escape hatch for when the projection is
     * known to be wrong and the question is how to get back to correct, not how to get there
     * quickly. {@code refreshCompanyStats} already writes all nine columns from source, so the
     * rebuild is that statement plus the removal of rows whose company no longer qualifies.
     */
    public Future<Void> rebuild() {
        /*
          Stale rows first, then the upsert.

          refreshCompanyStats only inserts and updates; a company whose last manager was deleted
          keeps whatever the projection last said about it. Clearing those is the difference
          between "refresh" and "rebuild".
        */
        return db.query("""
                DELETE FROM company_stats_live cs
                 WHERE NOT EXISTS (
                       SELECT 1 FROM managers m
                        WHERE m.company_id = cs.company_id
                          AND m.approval_status IN ('approved', 'ghost')
                          AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%'))
                """).execute()
            .compose(ignored -> companyRepo.refreshCompanyStats());
    }

    /**
     * What the projection claims, against what the source tables actually hold.
     *
     * <p>Counts only. An average that disagrees always has a count that disagrees behind it, and
     * comparing rounded numerics invites false alarms over the last decimal place.
     */
    public record Drift(long companyId, String dataset, long projected, long actual) {
        @Override public String toString() {
            return "company " + companyId + " " + dataset + ": projected " + projected + ", actual " + actual;
        }
    }

    public Future<List<Drift>> reconcile() {
        return db.query("""
                SELECT cs.company_id,
                       cs.workplace_count AS projected_workplace,
                       cs.interview_count AS projected_interview,
                       (SELECT COUNT(*) FROM company_reviews cr
                         WHERE cr.company_id = cs.company_id AND cr.deleted_at IS NULL) AS actual_workplace,
                       (SELECT COUNT(*) FROM interview_reviews ir
                         WHERE ir.company_id = cs.company_id AND ir.deleted_at IS NULL) AS actual_interview
                FROM company_stats_live cs
                """).execute()
            .map(rs -> {
                List<Drift> drifts = new ArrayList<>();
                for (Row row : rs) {
                    long id = row.getLong("company_id");
                    long pw = row.getLong("projected_workplace");
                    long aw = row.getLong("actual_workplace");
                    long pi = row.getLong("projected_interview");
                    long ai = row.getLong("actual_interview");
                    if (pw != aw) drifts.add(new Drift(id, "workplace", pw, aw));
                    if (pi != ai) drifts.add(new Drift(id, "interview", pi, ai));
                }
                return drifts;
            });
    }
}

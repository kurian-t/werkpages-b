package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The company_stats_live lifecycle, as seen from Java.
 *
 * <p><b>This class contains no statistics arithmetic.</b> Every method here is a call into the
 * database functions introduced by V90, which hold the single implementation shared by the live
 * triggers, the automatic repair and the admin action. That is the whole point of it: this
 * projection has now been broken three separate times by a second copy of the maths drifting from
 * the first - V79's application writer against V47's trigger, and then this class's own rebuild
 * against V89's trigger, which deleted exactly the rows V89 had been written to preserve.
 *
 * <p>If you are tempted to add a recount, an orphan rule or an eligibility filter here, it belongs
 * in {@code company_stats_expected} in the migration stream instead, where both backends and all
 * three maintenance paths will use it.
 *
 * <h2>What maintains the projection</h2>
 *
 * <pre>
 *   ordinary write  ──►  statement-level trigger  ──►  company_stats_apply()   (same transaction)
 *   every 6 hours   ──►  maintain()               ──►  company_stats_maintain()
 *   admin action    ──►  maintain() / rebuild()   ──►  the same two functions
 * </pre>
 *
 * Normal operation and routine drift recovery need nobody to press anything. The admin action
 * exists for the case where somebody wants it to happen <em>now</em>.
 *
 * <h2>Concurrency</h2>
 *
 * Explained in full at the top of V90. In short: company_stats_apply takes a transaction-scoped
 * advisory lock per company <em>before</em> it reads the source tables, so a repair can never write
 * numbers it read before a rating that has since committed; the ids are sorted so two transactions
 * touching the same pair of companies cannot deadlock; and the lock is advisory rather than a row
 * lock because the row may not exist, which is the case repair most needs to handle.
 */
public class CompanyStatsRebuilder {

    /**
     * Guards against two maintenance runs overlapping, including across the two backends.
     *
     * <p>A JVM flag cannot do this: Werkpages and RateMyManagers are separate processes against one
     * database, so an admin pressing the button in one while the other's six-hourly job is running
     * is an ordinary occurrence. A session-scoped advisory lock in the shared database is the only
     * thing both can see.
     *
     * <p>Distinct from the per-company lock company_stats_apply takes - that one serializes
     * individual recomputes, this one stops two whole sweeps interleaving and repeating each
     * other's work.
     *
     * <p>Taken as pg_try_advisory_XACT_lock, never the session form. A session lock survives
     * rollback and must be released by hand on every exit path; miss one on a pooled connection and
     * it leaks, after which every sweep skips forever and the only symptom is statistics quietly
     * going stale. The transaction form is released by PostgreSQL however the transaction ends.
     */
    private static final int MAINTENANCE_LOCK_KEY = 847232;

    /** How many companies one maintenance pass will repair. Bounded so a backlog clears over several runs. */
    private static final int DEFAULT_BATCH = 200;

    /**
     * How long maintenance may go without a completed run before that is itself worth reporting.
     *
     * <p>No process can reliably alert on its own death, so this is checked at startup and by
     * whatever external monitor reads company_stats_maintenance_age(). Twice the six-hour interval,
     * so one missed tick is not an alarm and a stopped timer is.
     */
    public static final Duration STALE_AFTER = Duration.ofHours(13);

    /** The age reported when no sweep has ever completed, or when the heartbeat cannot be read. */
    public static final Duration NEVER_RUN = Duration.ofDays(3650);

    /** Which backend this instance belongs to, recorded on every run. */
    private static final String SOURCE = "werkpages";

    private final Pool db;

    public CompanyStatsRebuilder(Pool db) {
        this.db = db;
    }

    /**
     * What one maintenance pass found and fixed, and what is worth shouting about.
     *
     * <p>{@code recurringCompanies} is the count of companies that had already been repaired
     * successfully and have drifted AGAIN inside the recurrence window. It is here because a
     * company repaired every six hours reports a clean success every six hours: each run is fine
     * and the bug behind it is invisible. That was the one gap the drift log was created to close
     * and did not, because nothing asked "again?".
     */
    public record MaintenanceResult(boolean ran, int driftFound, int companiesRepaired,
                                    int stillDrifted, int recurringCompanies) {

        /** Repair ran, was re-checked, and something STILL disagrees. Something is actively writing wrongly. */
        public boolean repairIncomplete() { return ran && stillDrifted > 0; }

        /** Repairs are working, but the same companies keep breaking. The repair is masking a bug. */
        public boolean driftRecurring() { return recurringCompanies > 0; }

        /** Anything an operator should be told about, rather than have to go looking for. */
        public boolean needsAttention() { return repairIncomplete() || driftRecurring(); }

        @Override public String toString() {
            if (!ran) return "skipped (another maintenance run holds the lock)";
            StringBuilder sb = new StringBuilder();
            sb.append(driftFound == 0 ? "no drift"
                : driftFound + " discrepancy(ies) across " + companiesRepaired
                  + " company(ies) repaired, " + stillDrifted + " still disagreeing");
            if (recurringCompanies > 0) {
                sb.append("; ").append(recurringCompanies)
                  .append(" company(ies) have drifted again after a successful repair");
            }
            return sb.toString();
        }
    }

    /**
     * Detect, record, repair, verify. The scheduled job and the admin button both call this.
     *
     * <p>Compares first and repairs only what disagrees, rather than rewriting every row on every
     * run. Blind rewriting is what the six-hourly job used to do, and it meant a disagreement
     * between the triggers and that query was repaired invisibly every six hours while nobody
     * learned the triggers were wrong.
     */
    public Future<MaintenanceResult> maintain() {
        return maintain(DEFAULT_BATCH);
    }

    public Future<MaintenanceResult> maintain(int batch) {
        /*
          ONE TRANSACTION, AND A TRANSACTION-SCOPED LOCK. Both halves of that matter.

          A session-scoped lock (pg_advisory_lock) survives both COMMIT and ROLLBACK, so on a
          pooled connection it has to be released explicitly on every exit path - and if the
          release is attached with onComplete, which does not delay the outer future, the pool can
          hand the connection back before the unlock runs. The lock then leaks and EVERY future
          sweep skips forever, silently, because skipping is the designed response to a held lock.
          That is a self-inflicted outage with no symptom.

          pg_try_advisory_xact_lock cannot leak: PostgreSQL releases it when the transaction ends,
          whether that is a commit, a rollback, an error, or the connection dying. withTransaction
          commits on success and rolls back on failure, so there is no exit path left to forget.

          The cost is that a sweep which fails mid-way rolls back its own drift-log rows. That is
          acceptable and deliberate: evidence of UNREPAIRED drift is re-derivable, because the drift
          is still there and the next sweep finds it again. Evidence of REPAIRED drift - the record
          that actually has to survive, since a repaired company looks healthy afterwards - is
          written on the committing path.

          try_, never a blocking wait: if another sweep holds it, the right answer is to skip this
          tick rather than queue up and then redo the winner's work.
        */
        final OffsetDateTime startedAt = OffsetDateTime.now();

        Future<MaintenanceResult> sweep = db.withTransaction(conn ->
            conn.preparedQuery("SELECT pg_try_advisory_xact_lock($1)").execute(Tuple.of(MAINTENANCE_LOCK_KEY))
                .compose(lockRows -> {
                    if (!lockRows.iterator().next().getBoolean(0)) {
                        return Future.succeededFuture(new MaintenanceResult(false, 0, 0, 0, 0));
                    }
                    return conn.preparedQuery("SELECT * FROM company_stats_maintain($1)")
                        .execute(Tuple.of(batch))
                        .map(rows -> {
                            Row r = rows.iterator().next();
                            return new MaintenanceResult(true,
                                r.getInteger("drift_found"),
                                r.getInteger("companies_repaired"),
                                r.getInteger("still_drifted"), 0);
                        });
                }));

        /*
          Recording and recurrence happen OUTSIDE the sweep transaction, and that is the point.

          A failure inside the sweep rolls the sweep back - including, necessarily, any record of
          the failure written inside it. So the run log is written afterwards, on both paths, which
          is the only arrangement where "the sweep errored" survives the error. Repeated failure
          used to be indistinguishable from silence.
        */
        return sweep
            .compose(result -> result.ran()
                ? countRecurring().map(recurring -> new MaintenanceResult(
                      true, result.driftFound(), result.companiesRepaired(),
                      result.stillDrifted(), recurring))
                : Future.succeededFuture(result))
            .compose(
                result -> recordRun(startedAt, result.ran() ? "ok" : "skipped", result, null)
                    .map(v -> result),
                error -> recordRun(startedAt, "failed",
                        new MaintenanceResult(true, 0, 0, 0, 0), String.valueOf(error.getMessage()))
                    // The original failure is re-raised, never swallowed by the bookkeeping.
                    .compose(v -> Future.failedFuture(error),
                             bookkeepingFailed -> Future.failedFuture(error)));
    }

    /** Companies that have drifted again since a repair that was verified as successful. */
    private Future<Integer> countRecurring() {
        return db.query("SELECT count(*) AS n FROM company_stats_recurring_drift(7, 2)")
            .execute()
            .map(rows -> rows.iterator().next().getInteger("n"))
            // Recurrence reporting must never be the reason a sweep is reported as failed.
            .otherwise(0);
    }

    private Future<Void> recordRun(OffsetDateTime startedAt, String outcome,
                                   MaintenanceResult result, String errorDetail) {
        return db.preparedQuery("""
                INSERT INTO company_stats_maintenance_runs
                       (started_at, outcome, drift_found, companies_repaired, still_drifted,
                        error_detail, source)
                VALUES ($1, $2, $3, $4, $5, $6, $7)
                """)
            .execute(Tuple.of(startedAt, outcome, result.driftFound(), result.companiesRepaired(),
                              result.stillDrifted(), errorDetail, SOURCE))
            .map(rows -> (Void) null)
            // Losing the heartbeat must not fail the sweep that actually did the work.
            .otherwise((Void) null);
    }

    /**
     * Recompute every company, whether or not it looks wrong.
     *
     * <p>The escape hatch, and deliberately NOT what routine maintenance does. {@link #maintain()}
     * is cheaper and leaves evidence; this is for when the projection is believed wrong in a way
     * detection might share - a bad deploy, a restore, a schema change - and the question is how to
     * get back to correct rather than how to get there quickly.
     *
     * <p>Takes the same cross-process lock as maintain(), so it cannot overlap a scheduled sweep.
     */
    public Future<Long> rebuild() {
        // Same transaction-scoped lock as maintain(), for the same reason: nothing to release, so
        // nothing to leak if this fails halfway through a large table.
        return db.withTransaction(conn ->
            conn.preparedQuery("SELECT pg_try_advisory_xact_lock($1)").execute(Tuple.of(MAINTENANCE_LOCK_KEY))
                .compose(lockRows -> {
                    if (!lockRows.iterator().next().getBoolean(0)) {
                        return Future.failedFuture(new IllegalStateException(
                            "another company_stats maintenance run is in progress"));
                    }
                    /*
                      Both projections, in this one transaction.

                      company_interview_stats is the second incrementally maintained read table
                      over the same sources (V93), and a rebuild that recomputed only the first
                      would answer "get back to correct" with "half of it". They share the
                      per-company advisory lock, so doing them together costs one lock acquisition
                      per company rather than two, and either both land or neither does.
                    */
                    return conn.query("SELECT company_stats_apply(NULL) AS written")
                        .execute()
                        .compose(rows -> {
                            long live = rows.iterator().next().getLong("written");
                            return conn.query("SELECT company_interview_stats_apply(NULL) AS written")
                                .execute()
                                .map(ivRows -> live + ivRows.iterator().next().getLong("written"));
                        });
                }));
    }

    /**
     * How long since maintenance last completed, for a startup check or an external monitor.
     *
     * <p>Counts only completed runs, never skipped ones: a long run of skips means the lock is
     * stuck or one backend is doing all the work, and treating that as healthy is how a stuck
     * sweep stays invisible.
     */
    public Future<Duration> timeSinceLastCompletedRun() {
        /*
          Cast to double precision IN THE QUERY rather than coercing in Java.

          EXTRACT returns `numeric`, which this driver surfaces as its own Numeric type, and asking
          for Number.class threw - which the otherwise() below then turned into "never run" on every
          single call. Two bugs of the same shape in one method: a fallback that cannot distinguish
          "no runs" from "I could not read it". The cast removes the guesswork; the fallback stays,
          because stale is the safe answer, and a permanent read failure now shows up as a standing
          "may have stopped running" warning rather than as silence.
        */
        return db.query("SELECT EXTRACT(EPOCH FROM company_stats_maintenance_age())::double precision AS secs")
            .execute()
            .map(rows -> {
                Double secs = rows.iterator().next().getDouble("secs");
                // NULL means no sweep has ever completed. That is the "it has never run" case and
                // it is STALE, not healthy.
                if (secs == null) return NEVER_RUN;
                return Duration.ofSeconds((long) secs.doubleValue());
            })
            /*
              UNKNOWN IS STALE, NEVER HEALTHY.

              This used to fall back to Duration.ZERO, so a failure to read the heartbeat - or the
              error the age function itself raised when no run existed - was reported as "last
              completed 0h ago". The check silently vouched for a system it had not managed to
              inspect, which is the one thing a health check must never do.
            */
            .otherwise(NEVER_RUN);
    }

    /**
     * Recompute just these companies, through the shared implementation.
     *
     * <p>Both projections, for the reason given on {@link #rebuild()}: they summarise the same
     * sources, and recomputing one of them for a company leaves the other saying something else
     * about it.
     *
     * <p>Returns the number of {@code company_stats_live} rows written, which is one per company.
     * The interview projection has a row only for a company that has interview reviews, so adding
     * its count here would make the figure mean neither "companies" nor "rows".
     */
    public Future<Long> apply(List<Long> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) return Future.succeededFuture(0L);
        Long[] ids = companyIds.toArray(new Long[0]);
        return db.preparedQuery("SELECT company_stats_apply($1) AS written")
            .execute(Tuple.of(ids))
            .compose(rows -> {
                long written = rows.iterator().next().getLong("written");
                return db.preparedQuery("SELECT company_interview_stats_apply($1)")
                    .execute(Tuple.of(ids))
                    .map(ignored -> written);
            });
    }

    /** One discrepancy between the source tables and the projection. */
    public record Drift(long companyId, String field, String expected, String actual, String kind) {
        @Override public String toString() {
            return "company " + companyId + " " + field + ": expected " + expected
                 + ", actual " + actual + (kind.equals("value") ? "" : " [" + kind + "]");
        }
    }

    /**
     * What disagrees, without changing anything.
     *
     * <p>Read-only, so it is safe to call while anything else is running, and it is what tests and
     * any future admin read-out use.
     */
    public Future<List<Drift>> reconcile() {
        return db.preparedQuery("SELECT * FROM company_stats_detect_drift($1)")
            .execute(Tuple.of(1000))
            .map(rs -> {
                List<Drift> drifts = new ArrayList<>();
                for (Row row : rs) {
                    drifts.add(new Drift(
                        row.getLong("company_id"),
                        row.getString("field"),
                        row.getString("expected"),
                        row.getString("actual"),
                        row.getString("drift_kind")));
                }
                return drifts;
            });
    }

    /**
     * How often each of these companies has drifted before, newest first.
     *
     * <p>A successful repair must not erase the evidence of a recurring bug: if one company drifts
     * every six hours, each individual run looks like a success and the numbers are correct whenever
     * anybody checks. This is what makes that visible.
     */
    public Future<List<Row>> recentDrift(int limit) {
        return db.preparedQuery("""
                SELECT company_id, count(*) AS occurrences,
                       max(detected_at) AS last_seen,
                       count(*) FILTER (WHERE repair_result = 'unverified') AS unrepaired
                FROM company_stats_drift_log
                WHERE detected_at > now() - interval '7 days'
                GROUP BY company_id
                ORDER BY occurrences DESC, last_seen DESC
                LIMIT $1
                """)
            .execute(Tuple.of(limit))
            .map(rs -> { List<Row> out = new ArrayList<>(); rs.forEach(out::add); return out; });
    }
}

package org.werkpages.repository;

/**
 * Shared SQL fragments for querying {@code reviews}.
 *
 * <p>This replaces the {@code published_reviews} view. The view expressed one rule — a review is
 * publicly visible when it is live and not deleted — and hid it behind a database object, which
 * cost more than it saved:
 *
 * <ul>
 *   <li>the rule was invisible to anybody reading the repository, who saw a table name they could
 *       not find in any migration;</li>
 *   <li>it was defined as {@code SELECT * FROM reviews}, and PostgreSQL expands that star once, at
 *       creation. Columns added later were silently unreadable through it — queries failed with
 *       {@code column "..." does not exist} while the column plainly existed on the table.</li>
 * </ul>
 *
 * <p>Keeping the rule here keeps one source of truth while leaving every query visibly a query
 * against {@code reviews}.
 *
 * <p><b>Why a method rather than a constant:</b> queries alias {@code reviews} differently, and some
 * join it more than once. A single constant would force callers into string replacement to make it
 * fit, which is the failure this exists to prevent.
 */
public final class ReviewSql {

    private ReviewSql() {}

    /**
     * A review the public may see: live, and not deleted.
     *
     * <p>Held ratings ({@code disposition <> 'live'}) are withheld from public surfaces but remain
     * visible to their author and to an admin, so callers serving those readers deliberately do not
     * use this fragment.
     *
     * @param alias the table alias in the calling query, e.g. {@code "r"}; pass {@code "reviews"}
     *              when the query does not alias it
     */
    public static String live(String alias) {
        return alias + ".disposition = 'live' AND " + alias + ".deleted_at IS NULL";
    }

    /**
     * A review that counts toward genuine statistics.
     *
     * <p>{@link #live} plus "written by a person". A platform-generated example is a demonstration
     * tile: it is shown, deliberately, but it contributes nothing to a manager's average, its
     * rating count, category scores, company or industry statistics, rankings, weighted opinions,
     * contributor participation or profile-unlocking eligibility. A manager with one example and
     * zero genuine ratings has zero genuine ratings and no genuine average.
     *
     * <p><b>Why this is a fragment and not a filter applied at each call site.</b> Company and
     * industry statistics are not computed from {@code reviews} at all - {@code
     * company_stats_expected} aggregates {@code SUM(m.reviews_count)} and {@code
     * AVG(m.overall_rating)} off the MANAGER cache, which {@code recalculate} writes. So excluding
     * examples here, in the one query that feeds that cache, propagates through V89/V90's trigger
     * machinery to every downstream projection without a single trigger function changing. Get
     * this fragment wrong and the error reaches company stats, industry stats and rankings
     * silently.
     *
     * @param alias the table alias in the calling query
     */
    public static String genuine(String alias) {
        return live(alias) + " AND " + alias + ".origin = 'contributor'";
    }

    /**
     * Only the platform-generated examples, for the surfaces that show them as demonstrations.
     *
     * <p>Deliberately NOT {@code NOT genuine(...)}: that would also match held and deleted rows.
     */
    public static String example(String alias) {
        return live(alias) + " AND " + alias + ".origin = 'platform_example'";
    }
}

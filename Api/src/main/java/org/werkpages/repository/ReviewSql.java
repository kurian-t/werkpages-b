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
}

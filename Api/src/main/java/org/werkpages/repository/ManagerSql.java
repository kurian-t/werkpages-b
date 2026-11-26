package org.werkpages.repository;

/**
 * Shared SQL predicates about the {@code managers} table, so a question is asked in one place.
 *
 * <p>The counterpart of {@link ReviewSql}, and it exists for the same reason: these predicates
 * were spelled out by hand at each call site, and the hand-written copies drifted from one
 * another until the admin panel was the one surface in the product that disagreed with itself.
 */
public final class ManagerSql {

    private ManagerSql() {
    }

    /**
     * Publicly visible: in the directory, and openable by a visitor.
     *
     * <p>Ghost is live. It means auto-approved, not pending, not hidden.
     */
    public static String live(String alias) {
        return alias + ".approval_status IN ('approved','ghost')";
    }

    /**
     * A profile a person added, which is the population every admin statistic counts.
     *
     * <p><b>An import counts toward the scraped counter and nothing else.</b> That is a product
     * decision, taken on 2026-11-25: the admin figures exist to show what people have
     * contributed, and ~4,700 of 7,090 live profiles arrived in a one-off import, so including
     * them buries the number the panel is there to report. "Managers by Country" read 5,608 for
     * the United States, of which only a minority was submitted by anybody.
     *
     * <p>{@code external_id} is null only for a profile somebody submitted or created by
     * searching. An imported profile carries the identifier it was imported with, and a platform
     * seed carries a {@code seed_} one, so this excludes both and the two have counters of their
     * own.
     *
     * <p><b>This governs the admin statistics only.</b> Imported profiles stay live, searchable
     * and publicly visible, their ratings stay published and keep counting toward every manager
     * average, company projection and industry figure, and nothing here changes what a visitor
     * sees. Section 16 of CLAUDE.md still owns public visibility, and it filters on
     * {@code approval_status} alone.
     */
    public static String contributed(String alias) {
        return live(alias) + " AND " + alias + ".external_id IS NULL";
    }
}

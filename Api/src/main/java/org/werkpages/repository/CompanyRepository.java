package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import org.werkpages.service.AnthropicClient;

import java.util.List;
import java.util.Optional;

/**
 * Data-access layer for the {@code companies} table.
 * Analytics (manager count, avg rating, etc.) are computed on the fly via JOINs —
 * see CLAUDE.md for the materialized-view plan when performance needs it.
 */
public class CompanyRepository {

    private final SqlClient db;

    /** Optional AI classifier. When set, brand-new companies are classified into an
     *  industry on creation (fire-and-forget). Null when no ANTHROPIC_API_KEY is configured. */
    private AnthropicClient classifier;

    public CompanyRepository(SqlClient db) {
        this.db = db;
    }

    public void setClassifier(AnthropicClient classifier) {
        this.classifier = classifier;
    }

    /**
     * Company suggestions for the picker.
     *
     * Reads companies and company_aliases. Nothing else. In particular it does not read managers:
     * a company is searchable because it is a company, not because a manager row happens to
     * reference it. That was the old model wearing a new hat, and it kept a canonical company with
     * no managers yet invisible to the very picker meant to stop people re-creating it.
     *
     * Structured as ranked tiers rather than one condition full of ORs. Each tier is a separate,
     * individually indexable predicate with its own bound, so Postgres can use an index per shape
     * instead of evaluating every matching technique against every row. The previous single-
     * condition version measured 297 seconds on 50k companies; this one is milliseconds.
     *
     * Tier order is lexical relevance first, popularity only as a tie-break. Typing "Apple" must
     * put Apple above Apple Hospitality REIT even when the REIT has more managers.
     *
     *   1  exact canonical name        4  alias starts with
     *   2  exact alias                 5  canonical name contains
     *   3  canonical name starts with  6  alias contains
     *
     * The two "contains" tiers need at least three characters. Two-character queries like "co"
     * match an enormous fraction of any company table, and the work is wasted because nobody picks
     * from a list of 4,000 near-identical candidates.
     */
    public Future<RowSet<Row>> searchForPicker(String query) {
        return db.preparedQuery("""
                WITH q AS (SELECT normalize_company_name($1) AS nq),
                matches AS (
                    -- Each branch is bounded so a short query cannot drag the whole table through
                    -- the sort. Six small index scans beat one large one.
                    (SELECT c.id, 1 AS tier FROM companies c, q
                      WHERE c.normalized_name = q.nq LIMIT 20)
                    UNION ALL
                    (SELECT a.company_id AS id, 2 AS tier FROM company_aliases a, q
                      WHERE a.normalized_alias = q.nq LIMIT 20)
                    UNION ALL
                    (SELECT c.id, 3 AS tier FROM companies c, q
                      WHERE c.normalized_name LIKE q.nq || '%' LIMIT 40)
                    UNION ALL
                    (SELECT a.company_id AS id, 4 AS tier FROM company_aliases a, q
                      WHERE a.normalized_alias LIKE q.nq || '%' LIMIT 40)
                    UNION ALL
                    (SELECT c.id, 5 AS tier FROM companies c, q
                      WHERE length(q.nq) >= 3 AND c.normalized_name LIKE '%' || q.nq || '%' LIMIT 40)
                    UNION ALL
                    (SELECT a.company_id AS id, 6 AS tier FROM company_aliases a, q
                      WHERE length(q.nq) >= 3 AND a.normalized_alias LIKE '%' || q.nq || '%' LIMIT 40)
                ),
                -- A company reached by several tiers keeps only its best one, so an exact name
                -- match is not diluted by also matching an alias further down.
                best AS (SELECT id, MIN(tier) AS tier FROM matches GROUP BY id)
                SELECT c.id, c.name, c.logo_url, c.industry,
                       (best.tier <= 4) AS starts_with
                FROM best
                JOIN companies c ON c.id = best.id
                -- LEFT, never INNER: stats decide ordering, never whether a company exists. An
                -- INNER JOIN here would quietly reinstate "only companies with managers are
                -- findable", which is the bug this whole change exists to remove.
                LEFT JOIN company_stats_live s ON s.company_id = c.id
                ORDER BY best.tier,
                         COALESCE(s.manager_count, 0) DESC,
                         length(c.name),
                         c.name
                LIMIT 8
                """)
            .execute(Tuple.of(query));
    }

    /**
     * Resolves a company the caller has already identified, or falls back to resolving by name.
     *
     * This is the write path's entry point now that the picker returns IDs. When an ID is supplied
     * it is used directly and the name is never consulted, which is the whole point: two spellings
     * of one company can no longer become two companies.
     *
     * An unknown ID falls back to the name rather than failing. IDs reach us from a client that may
     * have been holding the page open across a merge or a deletion, and refusing the submission
     * would lose a contribution over a stale identifier. Falling back resolves it the old way,
     * which is no worse than before the ID existed.
     */
    public Future<Row> resolve(Long companyId, String name, String domain, String logoUrl) {
        if (companyId == null) return findOrCreate(name, domain, logoUrl);
        return db.preparedQuery("SELECT * FROM companies WHERE id = $1")
            .execute(Tuple.of(companyId))
            .compose(rs -> rs.iterator().hasNext()
                ? Future.succeededFuture(rs.iterator().next())
                : findOrCreate(name, domain, logoUrl));
    }

    /**
     * Returns an existing company matching {@code name} (case-insensitive) or creates
     * a ghost entry. The logo_url and domain are only written on INSERT; an existing
     * row is touched only to update updated_at so the RETURNING clause is always valid.
     */
    public Future<Row> findOrCreate(String name, String domain, String logoUrl) {
        // Primary conflict target is the slug index. Two names that produce the same slug
        // (e.g. "Acme Corp" and "Acme Corp.") are treated as the same company.
        //
        // A .recover() fallback handles the two remaining 23505 scenarios:
        //   companies_slug_idx  — same slug but was missed by ON CONFLICT (shouldn't happen
        //                         but guards against trigger-generated slugs in the DB)
        //   companies_name_ci   — same LOWER(TRIM(name)) but slug differs (e.g. the existing
        //                         row was inserted without a slug and the trigger added "<base>-<id>")
        // Both cases are resolved by a SELECT that tries name then slug.
        return db.preparedQuery("""
                INSERT INTO companies (name, domain, logo_url, status, slug, created_at, updated_at)
                VALUES ($1, $2, $3, 'ghost',
                    lower(regexp_replace(regexp_replace(lower(trim($1)), '[^a-z0-9\\s-]', '', 'g'), '\\s+', '-', 'g')),
                    now(), now())
                ON CONFLICT (slug) DO UPDATE
                    SET updated_at = now()
                RETURNING *
                """)
            .execute(Tuple.of(name.trim(), domain, logoUrl))
            .map(rows -> rows.iterator().next())
            .recover(err -> {
                if (err.getMessage() != null && err.getMessage().contains("23505")) {
                    return db.preparedQuery("""
                            SELECT * FROM companies
                            WHERE LOWER(TRIM(name)) = LOWER(TRIM($1))
                               OR slug = lower(regexp_replace(regexp_replace(lower(trim($1)), '[^a-z0-9\\s-]', '', 'g'), '\\s+', '-', 'g'))
                            ORDER BY (LOWER(TRIM(name)) = LOWER(TRIM($1))) DESC
                            LIMIT 1
                            """)
                        .execute(Tuple.of(name.trim()))
                        .map(rows -> rows.iterator().next());
                }
                return Future.failedFuture(err);
            })
            .map(this::classifyIfNeeded);
    }

    /**
     * Fire-and-forget: if this company has no industry yet and a classifier is configured,
     * classify it in the background and persist the result. Returns the row unchanged so it
     * never blocks or fails the caller (a manager create must not wait on an AI call).
     */
    private Row classifyIfNeeded(Row company) {
        if (classifier == null || company.getString("industry") != null) return company;
        long   id     = company.getLong("id");
        String name   = company.getString("name");
        String domain = company.getString("domain");
        classifier.classifyIndustries(List.of(new AnthropicClient.CompanyToClassify(id, name, domain)))
            .compose(map -> {
                String industry = map.get(id);
                return industry != null ? updateIndustry(id, industry) : Future.succeededFuture();
            })
            .onFailure(e -> System.err.println("industry classify-on-create failed for company " + id + ": " + e.getMessage()));
        return company;
    }

    /** Sets a company's industry. */
    public Future<Void> updateIndustry(long companyId, String industry) {
        return db.preparedQuery("UPDATE companies SET industry = $1, updated_at = now() WHERE id = $2")
            .execute(Tuple.of(industry, companyId))
            .mapEmpty();
    }

    /**
     * Companies still lacking an industry that actually appear in the directory (>=1 approved/ghost
     * manager). Used by the one-time backfill so we never spend API calls on empty ghost shells.
     */
    public Future<RowSet<Row>> findUnclassified(int limit) {
        return db.preparedQuery("""
                SELECT c.id, c.name, c.domain
                FROM companies c
                JOIN company_stats_live cs ON cs.company_id = c.id AND cs.manager_count > 0
                WHERE c.industry IS NULL
                ORDER BY cs.manager_count DESC
                LIMIT $1
                """)
            .execute(Tuple.of(limit));
    }

    /** Count of directory-visible companies still awaiting classification. */
    public Future<Long> countUnclassified() {
        return db.query("""
                SELECT COUNT(*) FROM companies c
                JOIN company_stats_live cs ON cs.company_id = c.id AND cs.manager_count > 0
                WHERE c.industry IS NULL
                """)
            .execute()
            .map(rs -> rs.iterator().next().getLong(0));
    }

    /**
     * One tile per industry: number of companies, managers, reviews and the average manager rating
     * across the industry. Aggregated straight from managers so the average is a true per-manager
     * mean (not an average of per-company averages). Only approved/ghost, non-seed managers count.
     */
    public Future<RowSet<Row>> findIndustryListing() {
        return db.query("""
                SELECT c.industry AS industry,
                       COUNT(DISTINCT c.id)                    AS company_count,
                       COUNT(DISTINCT m.id)                    AS manager_count,
                       COALESCE(SUM(m.reviews_count), 0)       AS total_reviews,
                       ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL
                             AND m.reviews_count > 0)::NUMERIC, 1) AS avg_rating
                FROM companies c
                JOIN managers m ON m.company_id = c.id
                WHERE c.industry IS NOT NULL
                  AND m.approval_status IN ('approved', 'ghost')
                  AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                GROUP BY c.industry
                ORDER BY manager_count DESC, company_count DESC, c.industry ASC
                """)
            .execute();
    }

    /**
     * Every distinct industry actually stored on companies. Used to resolve an industry slug
     * that is not in IndustryTaxonomy — the listing is built from this column, so anything it
     * can display must also be openable, or a tile leads to "Industry not found".
     */
    public Future<RowSet<Row>> findDistinctIndustries() {
        return db.query("SELECT DISTINCT industry FROM companies WHERE industry IS NOT NULL").execute();
    }

    /** Aggregate stats for a single industry (same shape as one findIndustryListing row). */
    public Future<Optional<Row>> findIndustryStats(String industry) {
        return db.preparedQuery("""
                SELECT c.industry AS industry,
                       COUNT(DISTINCT c.id)                    AS company_count,
                       COUNT(DISTINCT m.id)                    AS manager_count,
                       COALESCE(SUM(m.reviews_count), 0)       AS total_reviews,
                       ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL
                             AND m.reviews_count > 0)::NUMERIC, 1) AS avg_rating
                FROM companies c
                JOIN managers m ON m.company_id = c.id
                WHERE c.industry = $1
                  AND m.approval_status IN ('approved', 'ghost')
                  AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                GROUP BY c.industry
                """)
            .execute(Tuple.of(industry))
            .map(rs -> rs.iterator().hasNext() ? Optional.of(rs.iterator().next()) : Optional.empty());
    }

    /** Per-manager category_averages for an industry, for aggregating the 10-category breakdown. */
    public Future<RowSet<Row>> findManagerCategoriesByIndustry(String industry) {
        return db.preparedQuery("""
                SELECT m.category_averages
                FROM managers m
                JOIN companies c ON c.id = m.company_id
                WHERE c.industry = $1
                  AND m.approval_status IN ('approved', 'ghost')
                  AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                  AND m.reviews_count > 0
                  AND m.category_averages IS NOT NULL
                """)
            .execute(Tuple.of(industry));
    }

    /** Companies within an industry, same card shape as findCompanyListing(). */
    public Future<RowSet<Row>> findCompaniesByIndustry(String industry) {
        return db.preparedQuery("""
                SELECT c.id, c.name, c.slug, c.industry, cs.logo_url, cs.manager_count, cs.total_reviews, cs.avg_rating
                FROM company_stats_live cs
                JOIN companies c ON c.id = cs.company_id
                WHERE cs.manager_count > 0 AND c.industry = $1
                ORDER BY cs.total_reviews DESC, cs.manager_count DESC, c.name ASC
                """)
            .execute(Tuple.of(industry));
    }

    /**
     * Companies that have at least one approved or ghost manager, ordered by manager
     * count descending then name ascending.
     */
    public Future<RowSet<Row>> findCompanyListing() {
        return db.query("""
                SELECT c.id, c.name, c.slug, c.industry, cs.logo_url, cs.manager_count, cs.total_reviews, cs.avg_rating
                FROM company_stats_live cs
                JOIN companies c ON c.id = cs.company_id
                WHERE cs.manager_count > 0
                ORDER BY cs.total_reviews DESC, cs.manager_count DESC, c.name ASC
                """)
            .execute();
    }

    /** Targeted upsert for the company that owns the given manager. Fast — one indexed lookup. */
    public Future<Void> updateCompanyStatsForManager(long managerId) {
        return db.preparedQuery("""
                INSERT INTO company_stats_live (company_id, manager_count, total_reviews, avg_rating, logo_url, updated_at)
                SELECT c.id,
                       COUNT(DISTINCT m.id),
                       COALESCE(SUM(m.reviews_count), 0),
                       ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL AND m.reviews_count > 0)::NUMERIC, 1),
                       COALESCE(MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'), c.logo_url, MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)),
                       now()
                FROM companies c
                JOIN managers m ON m.company_id = c.id
                WHERE c.id = (SELECT company_id FROM managers WHERE id = $1)
                  AND m.approval_status IN ('approved', 'ghost')
                  AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                GROUP BY c.id, c.logo_url
                ON CONFLICT (company_id) DO UPDATE SET
                    manager_count = EXCLUDED.manager_count,
                    total_reviews = EXCLUDED.total_reviews,
                    avg_rating    = EXCLUDED.avg_rating,
                    logo_url      = EXCLUDED.logo_url,
                    updated_at    = now()
                """)
            .execute(Tuple.of(managerId))
            .mapEmpty();
    }

    /** Targeted upsert for a specific company. Used after rename/merge. */
    public Future<Void> updateCompanyStatsForCompany(long companyId) {
        return db.preparedQuery("""
                INSERT INTO company_stats_live (company_id, manager_count, total_reviews, avg_rating, logo_url, updated_at)
                SELECT c.id,
                       COUNT(DISTINCT m.id),
                       COALESCE(SUM(m.reviews_count), 0),
                       ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL AND m.reviews_count > 0)::NUMERIC, 1),
                       COALESCE(MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'), c.logo_url, MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)),
                       now()
                FROM companies c
                LEFT JOIN managers m ON m.company_id = c.id
                    AND m.approval_status IN ('approved', 'ghost')
                    AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                WHERE c.id = $1
                GROUP BY c.id, c.logo_url
                ON CONFLICT (company_id) DO UPDATE SET
                    manager_count = EXCLUDED.manager_count,
                    total_reviews = EXCLUDED.total_reviews,
                    avg_rating    = EXCLUDED.avg_rating,
                    logo_url      = EXCLUDED.logo_url,
                    updated_at    = now()
                """)
            .execute(Tuple.of(companyId))
            .mapEmpty();
    }

    /** Full sync of company_stats_live from source tables.
     *  Background safety net — guarded by AtomicBoolean in MainVerticle, do not call on hot paths. */
    public Future<Void> refreshCompanyStats() {
        return db.query("""
                INSERT INTO company_stats_live (company_id, manager_count, total_reviews, avg_rating, logo_url, updated_at)
                SELECT c.id,
                       COUNT(DISTINCT m.id),
                       COALESCE(SUM(m.reviews_count), 0),
                       ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL AND m.reviews_count > 0)::NUMERIC, 1),
                       COALESCE(
                           MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'),
                           c.logo_url,
                           MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)
                       ),
                       now()
                FROM companies c
                JOIN managers m ON m.company_id = c.id
                WHERE m.approval_status IN ('approved', 'ghost')
                  AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                GROUP BY c.id, c.logo_url
                ON CONFLICT (company_id) DO UPDATE SET
                    manager_count = EXCLUDED.manager_count,
                    total_reviews = EXCLUDED.total_reviews,
                    avg_rating    = EXCLUDED.avg_rating,
                    logo_url      = EXCLUDED.logo_url,
                    updated_at    = now()
                """)
            .execute()
            .mapEmpty();
    }

    /** Looks up a company by name (case-insensitive). */
    public Future<Optional<Row>> findByName(String name) {
        return db.preparedQuery("""
                SELECT id, name, slug, logo_url, status, industry
                FROM companies
                WHERE LOWER(TRIM(name)) = LOWER(TRIM($1))
                LIMIT 1
                """)
            .execute(Tuple.of(name))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** Looks up a company by its URL slug. Also returns the logo from company_stats_live
     *  (which reflects only current FK-linked managers) as stats_logo_url. */
    public Future<Optional<Row>> findBySlug(String slug) {
        return db.preparedQuery("""
                SELECT c.id, c.name, c.slug, c.logo_url, c.status, c.industry,
                       cs.logo_url AS stats_logo_url
                FROM companies c
                LEFT JOIN company_stats_live cs ON cs.company_id = c.id
                WHERE c.slug = $1
                LIMIT 1
                """)
            .execute(Tuple.of(slug))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** All approved/ghost managers belonging to this company, ordered by review count. */
    public Future<RowSet<Row>> findManagersByCompanyId(long companyId) {
        return db.preparedQuery("""
                WITH target AS (SELECT LOWER(TRIM(name)) AS lname FROM companies WHERE id = $1)
                SELECT DISTINCT m.id, m.name, m.title, m.image, m.overall_rating, m.reviews_count,
                       m.company_logo_url, m.category_averages, m.company, m.slug, m.approval_status
                FROM managers m, target
                WHERE m.approval_status IN ('approved', 'ghost')
                  AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                  AND (
                    m.company_id = $1
                    OR LOWER(TRIM(m.company)) = target.lname
                    OR EXISTS (
                        SELECT 1 FROM career_history ch
                        WHERE ch.manager_id = m.id
                          AND (ch.company_id = $1
                               OR (ch.company_id IS NULL AND LOWER(TRIM(ch.company)) = target.lname))
                    )
                    OR EXISTS (
                        SELECT 1 FROM reviews r
                        WHERE r.manager_id = m.id
                          AND LOWER(TRIM(r.manager_company)) = target.lname
                    )
                  )
                ORDER BY m.reviews_count DESC NULLS LAST, m.overall_rating DESC NULLS LAST, m.name ASC
                """)
            .execute(Tuple.of(companyId));
    }

    public Future<Boolean> updateLogo(long id, String logoUrl) {
        return db.preparedQuery("UPDATE companies SET logo_url = $1, updated_at = now() WHERE id = $2")
            .execute(Tuple.of(logoUrl, id))
            .map(rows -> rows.rowCount() > 0);
    }

    /** All companies ordered by name, for the admin panel. */
    public Future<RowSet<Row>> findAllForAdmin() {
        return db.query("""
                SELECT c.id, c.name, c.status,
                       COUNT(m.id) FILTER (WHERE m.approval_status IN ('approved','ghost')
                                             AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')) AS manager_count
                FROM companies c
                LEFT JOIN managers m ON m.company_id = c.id
                GROUP BY c.id, c.name, c.status
                ORDER BY c.name ASC
                """)
            .execute();
    }

    /**
     * Renames a company and cascades the new name to all linked managers, career history,
     * and review snapshots. Blocked at the service layer if the new name already exists.
     */
    public Future<Void> renameCompany(long companyId, String newName) {
        String trimmed = newName.trim();
        return db.preparedQuery("UPDATE companies SET name = $1, updated_at = now() WHERE id = $2")
            .execute(Tuple.of(trimmed, companyId))
            .compose(v -> db.preparedQuery("UPDATE managers SET company = $1 WHERE company_id = $2")
                .execute(Tuple.of(trimmed, companyId)))
            .compose(v -> db.preparedQuery("UPDATE career_history SET company = $1 WHERE company_id = $2")
                .execute(Tuple.of(trimmed, companyId)))
            .compose(v -> db.preparedQuery("""
                    UPDATE reviews SET manager_company = $1
                    WHERE manager_id IN (SELECT id FROM managers WHERE company_id = $2)
                    """)
                .execute(Tuple.of(trimmed, companyId)))
            .mapEmpty();
    }

    /**
     * Merges {@code mergeId} into {@code keepId}: reassigns all managers, career history
     * entries, and review snapshots, then deletes the source company row.
     */
    public Future<Void> mergeCompanies(long keepId, long mergeId) {
        return db.preparedQuery("SELECT name FROM companies WHERE id = $1").execute(Tuple.of(keepId))
            .compose(rows -> {
                if (!rows.iterator().hasNext())
                    return Future.failedFuture("Target company not found");
                String keepName = rows.iterator().next().getString("name");
                return db.preparedQuery("UPDATE managers SET company_id = $1, company = $2 WHERE company_id = $3")
                    .execute(Tuple.of(keepId, keepName, mergeId))
                    .compose(v -> db.preparedQuery("UPDATE career_history SET company_id = $1, company = $2 WHERE company_id = $3")
                        .execute(Tuple.of(keepId, keepName, mergeId)))
                    .compose(v -> db.preparedQuery("""
                            UPDATE reviews SET manager_company = $1
                            WHERE manager_id IN (SELECT id FROM managers WHERE company_id = $2)
                            """)
                        .execute(Tuple.of(keepName, keepId)))
                    .compose(v -> db.preparedQuery("DELETE FROM companies WHERE id = $1")
                        .execute(Tuple.of(mergeId)))
                    .mapEmpty();
            });
    }
}

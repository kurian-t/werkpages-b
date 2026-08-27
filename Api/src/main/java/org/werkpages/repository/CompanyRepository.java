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

package org.ratemymanager.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.Optional;

/**
 * Data-access layer for the {@code companies} table.
 * Analytics (manager count, avg rating, etc.) are computed on the fly via JOINs —
 * see CLAUDE.md for the materialized-view plan when performance needs it.
 */
public class CompanyRepository {

    private final SqlClient db;

    public CompanyRepository(SqlClient db) {
        this.db = db;
    }

    /**
     * Returns an existing company matching {@code name} (case-insensitive) or creates
     * a ghost entry. The logo_url and domain are only written on INSERT; an existing
     * row is touched only to update updated_at so the RETURNING clause is always valid.
     */
    public Future<Row> findOrCreate(String name, String domain, String logoUrl) {
        // Slug is generated from name inline. Company names are unique (case-insensitive),
        // so slug conflicts are extremely rare and caught by the unique index.
        return db.preparedQuery("""
                INSERT INTO companies (name, domain, logo_url, status, slug, created_at, updated_at)
                VALUES ($1, $2, $3, 'ghost',
                    lower(regexp_replace(regexp_replace(lower(trim($1)), '[^a-z0-9\\s-]', '', 'g'), '\\s+', '-', 'g')),
                    now(), now())
                ON CONFLICT ((LOWER(TRIM(name)))) DO UPDATE
                    SET updated_at = now()
                RETURNING *
                """)
            .execute(Tuple.of(name.trim(), domain, logoUrl))
            .map(rows -> rows.iterator().next());
    }

    /**
     * Companies that have at least one approved or ghost manager, ordered by manager
     * count descending then name ascending.
     */
    public Future<RowSet<Row>> findCompanyListing() {
        return db.query("""
                SELECT c.id, c.name, c.slug, cs.logo_url, cs.manager_count, cs.total_reviews, cs.avg_rating
                FROM company_stats_live cs
                JOIN companies c ON c.id = cs.company_id
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

    /** Refreshes the company_stats materialized view and syncs company_stats_live from it.
     *  Background safety net — guarded by AtomicBoolean in MainVerticle, do not call on hot paths. */
    public Future<Void> refreshCompanyStats() {
        return db.query("REFRESH MATERIALIZED VIEW CONCURRENTLY company_stats")
            .execute()
            .compose(v -> db.query("""
                    INSERT INTO company_stats_live (company_id, manager_count, total_reviews, avg_rating, logo_url, updated_at)
                    SELECT company_id, manager_count, total_reviews, avg_rating, logo_url, now()
                    FROM company_stats
                    ON CONFLICT (company_id) DO UPDATE SET
                        manager_count = EXCLUDED.manager_count,
                        total_reviews = EXCLUDED.total_reviews,
                        avg_rating    = EXCLUDED.avg_rating,
                        logo_url      = EXCLUDED.logo_url,
                        updated_at    = now()
                    """).execute())
            .mapEmpty();
    }

    /** Looks up a company by name (case-insensitive). */
    public Future<Optional<Row>> findByName(String name) {
        return db.preparedQuery("""
                SELECT id, name, slug, logo_url, status
                FROM companies
                WHERE LOWER(TRIM(name)) = LOWER(TRIM($1))
                LIMIT 1
                """)
            .execute(Tuple.of(name))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** Looks up a company by its URL slug. */
    public Future<Optional<Row>> findBySlug(String slug) {
        return db.preparedQuery("""
                SELECT id, name, slug, logo_url, status
                FROM companies
                WHERE slug = $1
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
                       m.company_logo_url, m.category_averages, m.company, m.slug
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

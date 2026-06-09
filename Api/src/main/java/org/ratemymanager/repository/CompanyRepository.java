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
        return db.preparedQuery("""
                INSERT INTO companies (name, domain, logo_url, status, created_at, updated_at)
                VALUES ($1, $2, $3, 'ghost', now(), now())
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
                SELECT c.id, c.name, cs.logo_url, cs.manager_count, cs.total_reviews, cs.avg_rating
                FROM company_stats cs
                JOIN companies c ON c.id = cs.company_id
                ORDER BY cs.total_reviews DESC, cs.manager_count DESC, c.name ASC
                """)
            .execute();
    }

    public Future<Void> refreshCompanyStats() {
        return db.query("REFRESH MATERIALIZED VIEW CONCURRENTLY company_stats")
            .execute()
            .mapEmpty();
    }

    /** Looks up a company by name (case-insensitive). */
    public Future<Optional<Row>> findByName(String name) {
        return db.preparedQuery("""
                SELECT id, name, logo_url, status
                FROM companies
                WHERE LOWER(TRIM(name)) = LOWER(TRIM($1))
                LIMIT 1
                """)
            .execute(Tuple.of(name))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** All approved/ghost managers belonging to this company, ordered by review count. */
    public Future<RowSet<Row>> findManagersByCompanyId(long companyId) {
        return db.preparedQuery("""
                WITH target AS (SELECT LOWER(TRIM(name)) AS lname FROM companies WHERE id = $1)
                SELECT DISTINCT m.id, m.name, m.title, m.image, m.overall_rating, m.reviews_count,
                       m.company_logo_url, m.category_averages, m.company
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
}

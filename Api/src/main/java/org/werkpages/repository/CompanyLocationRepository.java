package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.Optional;

/**
 * Data access for {@code company_locations} — the physical workplaces under a company.
 *
 * <p>One creation path, for the same reason companies have one: a second way to make a location is
 * a second way to make a duplicate, and duplicates here fragment every facet built on top of them.
 */
public class CompanyLocationRepository {

    private final SqlClient db;

    public CompanyLocationRepository(SqlClient db) {
        this.db = db;
    }

    /** An active location by id, or empty. Archived duplicates are deliberately not returned. */
    public Future<Optional<Row>> findActiveById(SqlClient conn, long id) {
        return conn.preparedQuery("""
                SELECT id, company_id, display_name, street, city, state, state_code,
                       country, country_code, postal_code
                FROM company_locations
                WHERE id = $1 AND status = 'active'
                """)
            .execute(Tuple.of(id))
            .map(rs -> {
                var it = rs.iterator();
                return it.hasNext() ? Optional.of(it.next()) : Optional.<Row>empty();
            });
    }

    public Future<Optional<Row>> findActiveById(long id) {
        return findActiveById(db, id);
    }

    /**
     * The locations a company's own contributions point at, with how many point at each.
     *
     * <p>Counted from contributions rather than from the location table, because a location that
     * nobody has worked at is not a fact about the company worth showing.
     */
    public Future<io.vertx.sqlclient.RowSet<Row>> findFacetsForCompany(long companyId) {
        return db.preparedQuery("""
                SELECT l.id, l.display_name, l.street, l.city, l.state, l.state_code,
                       l.country, l.country_code,
                       COUNT(DISTINCT m.id) AS manager_count
                FROM company_locations l
                LEFT JOIN managers m
                       ON m.company_location_id = l.id
                      AND m.approval_status IN ('approved','ghost')
                WHERE l.company_id = $1 AND l.status = 'active'
                GROUP BY l.id
                ORDER BY manager_count DESC, l.city, l.street
                """)
            .execute(Tuple.of(companyId));
    }
}

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
     * Places and geography to offer for one company, narrowed by what is already settled.
     *
     * <p>Two kinds, in one list, because both are valid answers. A building is what somebody who
     * knows the branch wants; a city or province is a complete answer for everybody else, and
     * forcing a street address to submit a rating would simply lose the rating.
     *
     * <p>Every row returned is a value already normalized and stored — never something assembled
     * from the query text. That is the difference between a suggestion and an invention: a
     * directory that accepts typed geography fills up with places that do not exist.
     */
    public Future<io.vertx.sqlclient.RowSet<Row>> suggest(Long companyId, String companyName,
                                                          String query, String country, String state) {
        String like = "%" + (query == null ? "" : query.trim().toLowerCase()) + "%";
        return db.preparedQuery("""
                -- Buildings belonging to this company.
                SELECT 'place'         AS kind,
                       COALESCE(l.display_name, l.street) AS label,
                       CONCAT_WS(' · ', l.street, CONCAT_WS(', ', l.city, l.state)) AS detail,
                       l.country, l.state, l.city, l.id AS company_location_id,
                       -- Second, behind confirmed geography. See the tier note below.
                       2 AS tier, 0 AS rank
                  FROM company_locations l
                 WHERE l.status = 'active'
                   AND ($1::bigint IS NULL OR l.company_id = $1)
                   AND ($3::text IS NULL OR l.country IS NOT DISTINCT FROM $3)
                   AND ($4::text IS NULL OR l.state   IS NOT DISTINCT FROM $4)
                   AND (LOWER(COALESCE(l.display_name,'')) LIKE $2
                        OR LOWER(COALESCE(l.street,'')) LIKE $2
                        OR LOWER(COALESCE(l.city,''))   LIKE $2)

                UNION ALL

                -- Geography anybody has already confirmed for this company. Distinct, so a city
                -- that fifty people named is offered once.
                --
                -- Tier 1, ahead of the buildings above. A city or a province is the answer most
                -- people actually have, and while buildings led this query a company with eight
                -- matching branches pushed every coarse answer off a list capped at eight - the
                -- companies with the most locations being exactly the ones where somebody is
                -- least likely to know which branch they mean.
                SELECT DISTINCT 'geo' AS kind,
                       CONCAT_WS(', ', m.declared_city, m.declared_state, m.declared_country) AS label,
                       NULL AS detail,
                       m.declared_country, m.declared_state, m.declared_city, NULL::bigint,
                       1 AS tier, 0 AS rank
                  FROM managers m
                 WHERE m.declared_country IS NOT NULL
                   AND ($1::bigint IS NULL OR m.company_id = $1)
                   AND ($3::text IS NULL OR m.declared_country IS NOT DISTINCT FROM $3)
                   AND (LOWER(COALESCE(m.declared_city,''))  LIKE $2
                        OR LOWER(COALESCE(m.declared_state,'')) LIKE $2
                        OR LOWER(COALESCE(m.declared_country,'')) LIKE $2)

                -- The corpus tiers that used to sit here - real-world places and the geography
                -- distilled from them - are gone from this query on purpose. They are not Postgres
                -- data: they are 72 million rows refreshed monthly from Overture, and copying them
                -- into a shared production database to be searched would cost ~35GB and a rebuild
                -- window nobody wants. They live as partitioned Parquet in S3 and are read through
                -- DuckDB instead. The pipeline that builds that corpus is not in this repository - nothing
                -- here invokes it - it lives in the corpus repo at
                -- .ai-corpus/reference/scripts/location-corpus/.
                --
                -- What remains here is what genuinely belongs in Postgres: places somebody has
                -- already selected, and geography somebody has already confirmed. Those are ours.

                ORDER BY tier, rank, label
                LIMIT 8
                """)
            // Four parameters, matching $1..$4. companyName is deliberately NOT bound: these two
            // tiers narrow by company_id, and a name has nothing to match against in either. It
            // stays on the signature because the corpus tier does use it, to rank a brand match
            // above a name match - see ManagerService.appendCorpusSuggestions.
            //
            // It used to be passed here, which made every call to this endpoint fail with
            // "expected 4 parameters but the actual number is 5".
            .execute(io.vertx.sqlclient.Tuple.of(companyId, like, blankToNull(country),
                                                 blankToNull(state)));
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /**
     * Promotes a place chosen from the search corpus into a real location, or returns the existing
     * one.
     *
     * <p><b>One creation path.</b> This is the only way a corpus place becomes a row, for the same
     * reason companies have a single {@code findOrCreate}: a second route is a second way to make a
     * duplicate, and duplicates here split a store's ratings across two entries that look identical
     * on the page.
     *
     * <p>Keyed on {@code (company_id, source, source_place_id)}, matching the partial unique index
     * from V68. The index predicate is repeated in the {@code ON CONFLICT} clause because Postgres
     * cannot infer a <em>partial</em> index without it — omitting it fails at runtime with "no
     * unique or exclusion constraint matching the ON CONFLICT specification", and only on the
     * second person to pick the same store.
     *
     * <p>Takes the caller's client so it joins their transaction. Promotion and the contribution
     * that triggered it are one operation: a location created for a review that then failed to
     * insert is a building nobody ever worked at, sitting in the facets forever.
     *
     * @return the id of the active location for this place
     */
    public Future<Long> findOrCreate(SqlClient conn, long companyId,
                                     org.werkpages.service.CorpusPlace place) {
        return conn.preparedQuery("""
                INSERT INTO company_locations
                    (company_id, source, source_place_id, display_name, street, city,
                     state, state_code, country, country_code, postal_code,
                     source_fetched_at, status)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, now(), 'active')
                ON CONFLICT (company_id, source, source_place_id)
                    WHERE source_place_id IS NOT NULL AND status = 'active'
                DO UPDATE SET updated_at = now()
                RETURNING id
                """)
            .execute(Tuple.of(companyId, org.werkpages.service.CorpusPlace.SOURCE,
                              place.sourcePlaceId(), place.displayName(), place.street(),
                              place.city(), place.state(), place.stateCode(),
                              place.country(), place.countryCode(), place.postalCode()))
            .map(rs -> rs.iterator().next().getLong("id"));
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

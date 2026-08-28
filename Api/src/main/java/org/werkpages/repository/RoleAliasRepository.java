package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.List;
import java.util.Optional;

/**
 * Data access for {@code role_aliases} — one row per distinct normalized job title.
 *
 * <p>Normalized titles are produced by the database trigger added in V49; nothing here writes
 * {@code managers.title_normalized}.
 */
public class RoleAliasRepository {

    private final SqlClient db;

    public RoleAliasRepository(SqlClient db) {
        this.db = db;
    }

    /**
     * Distinct normalized titles in use by real managers that have no alias row yet.
     *
     * <p>Seeded and soft-deleted managers are excluded: classifying titles that no visible manager
     * holds spends effort on rows nobody will ever filter by.
     */
    public Future<List<String>> findUnclassifiedTitles(int limit) {
        return db.preparedQuery("""
                SELECT DISTINCT m.title_normalized
                FROM managers m
                LEFT JOIN role_aliases ra ON ra.title_normalized = m.title_normalized
                WHERE m.title_normalized IS NOT NULL
                  AND ra.title_normalized IS NULL
                  AND m.approval_status IN ('approved','ghost')
                  AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                ORDER BY m.title_normalized
                LIMIT $1
                """)
            .execute(Tuple.of(limit))
            .map(rows -> {
                List<String> titles = new java.util.ArrayList<>();
                for (Row row : rows) titles.add(row.getString("title_normalized"));
                return titles;
            });
    }

    /**
     * Records a classification.
     *
     * <p>A row whose {@code source} is {@code manual} is never overwritten by an automated pass —
     * a human correction is the most reliable signal we have, and losing it to the next rule
     * sweep would make corrections feel pointless.
     */
    public Future<Void> upsert(String titleNormalized, String roleFamily, String seniority, String source) {
        return db.preparedQuery("""
                INSERT INTO role_aliases (title_normalized, role_family, seniority, source)
                VALUES ($1, $2, $3, $4)
                ON CONFLICT (title_normalized) DO UPDATE SET
                    role_family = EXCLUDED.role_family,
                    seniority   = EXCLUDED.seniority,
                    source      = EXCLUDED.source,
                    updated_at  = now()
                WHERE role_aliases.source <> 'manual'
                """)
            .execute(Tuple.of(titleNormalized, roleFamily, seniority, source))
            .mapEmpty();
    }

    /**
     * Records a human decision. Unlike {@link #upsert}, this overwrites whatever is there and
     * claims the row as {@code manual}, which puts it out of reach of every automated pass.
     */
    public Future<Void> upsertManual(String titleNormalized, String roleFamily, String seniority) {
        return db.preparedQuery("""
                INSERT INTO role_aliases (title_normalized, role_family, seniority, source)
                VALUES ($1, $2, $3, 'manual')
                ON CONFLICT (title_normalized) DO UPDATE SET
                    role_family = EXCLUDED.role_family,
                    seniority   = EXCLUDED.seniority,
                    source      = 'manual',
                    updated_at  = now()
                """)
            .execute(Tuple.of(titleNormalized, roleFamily, seniority))
            .mapEmpty();
    }

    public Future<Optional<Row>> findByTitle(String titleNormalized) {
        return db.preparedQuery("SELECT * FROM role_aliases WHERE title_normalized = $1")
            .execute(Tuple.of(titleNormalized))
            .map(rows -> rows.iterator().hasNext() ? Optional.of(rows.iterator().next()) : Optional.empty());
    }

    /**
     * The alias table with the number of managers behind each title, most common first.
     *
     * <p>This is the review surface: the way to judge whether normalization is working is to read
     * the mapping in frequency order, because a wrong rule on a common title matters far more than
     * a wrong rule on a title one person holds.
     */
    public Future<RowSet<Row>> findAllWithCounts(int limit, int offset) {
        return db.preparedQuery("""
                SELECT ra.title_normalized,
                       ra.role_family,
                       ra.seniority,
                       ra.source,
                       COUNT(m.id) AS manager_count,
                       MIN(m.title) AS sample_title
                FROM role_aliases ra
                LEFT JOIN managers m
                       ON m.title_normalized = ra.title_normalized
                      AND m.approval_status IN ('approved','ghost')
                GROUP BY ra.title_normalized, ra.role_family, ra.seniority, ra.source
                ORDER BY COUNT(m.id) DESC, ra.title_normalized ASC
                LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset));
    }

    /**
     * Title suggestions for the add-manager form.
     *
     * <p>This is the highest-leverage half of normalization: cleaning up history is a one-off, but
     * offering the spelling other people already used stops new variants being created at all.
     *
     * <p>The query is normalized before matching, so typing "sr" finds "senior manager" — the
     * abbreviation expansion works for the searcher as well as for storage. What comes back is the
     * most common REAL spelling in each normalized group, not the normalized string itself:
     * nobody wants to pick "senior engineering manager" out of a list when they wrote
     * "Sr. Engineering Manager".
     */
    public Future<RowSet<Row>> suggestTitles(String query, int limit) {
        return db.preparedQuery("""
                WITH q AS (
                    SELECT
                        -- Case-folded and de-punctuated only. Needed because the full normalizer
                        -- expands abbreviations, and expanding a PARTIAL word breaks the search:
                        -- "dev" becomes "development", which no longer matches "developer".
                        trim(regexp_replace(lower($1), '[^a-z0-9]+', ' ', 'g')) AS loose,
                        -- Fully normalized, so typing "sr" still finds "senior manager".
                        normalize_role_title($1)                                AS strict
                )
                SELECT m.title_normalized,
                       MODE() WITHIN GROUP (ORDER BY m.title) AS display_title,
                       COUNT(*)                               AS manager_count
                FROM managers m, q
                WHERE m.title_normalized IS NOT NULL
                  AND m.approval_status IN ('approved','ghost')
                  AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                  AND (
                        (q.loose  <> '' AND m.title_normalized LIKE '%' || q.loose  || '%')
                     OR (q.strict IS NOT NULL AND m.title_normalized LIKE '%' || q.strict || '%')
                  )
                GROUP BY m.title_normalized, q.loose, q.strict
                -- Prefix matches first: someone typing "eng" means titles that start that way
                -- before ones that merely contain it. Then by how many people use the spelling.
                ORDER BY (m.title_normalized LIKE COALESCE(q.strict, q.loose) || '%'
                          OR m.title_normalized LIKE q.loose || '%') DESC,
                         COUNT(*) DESC,
                         m.title_normalized ASC
                LIMIT $2
                """)
            .execute(Tuple.of(query, limit));
    }

    /** Normalized titles held by visible managers but not yet classified — the coverage gap. */
    public Future<Row> findCoverage() {
        return db.preparedQuery("""
                SELECT COUNT(DISTINCT m.title_normalized)                                AS distinct_titles,
                       COUNT(DISTINCT ra.title_normalized)                               AS classified_titles,
                       COUNT(m.id)                                                       AS managers_total,
                       COUNT(m.id) FILTER (WHERE ra.role_family IS NOT NULL)             AS managers_with_family,
                       COUNT(m.id) FILTER (WHERE ra.seniority IS NOT NULL)               AS managers_with_seniority
                FROM managers m
                LEFT JOIN role_aliases ra ON ra.title_normalized = m.title_normalized
                WHERE m.title_normalized IS NOT NULL
                  AND m.approval_status IN ('approved','ghost')
                  AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                """)
            .execute()
            .map(rows -> rows.iterator().next());
    }
}

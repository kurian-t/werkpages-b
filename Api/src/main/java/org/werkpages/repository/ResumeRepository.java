package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ResumeRepository {

    private final SqlClient db;

    public ResumeRepository(SqlClient db) {
        this.db = db;
    }

    public Future<Optional<Row>> findByUserId(UUID userId) {
        return db.preparedQuery(
                "SELECT * FROM user_resumes WHERE user_id = $1")
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    public Future<Row> upsert(UUID userId, String summary,
                              JsonArray skills, JsonArray education,
                              JsonArray workEntries, JsonArray extraLinks,
                              JsonObject design) {
        return db.preparedQuery("""
                INSERT INTO user_resumes (user_id, summary, skills, education, work_entries, extra_links, design, created_at, updated_at)
                VALUES ($1, $2, $3::jsonb, $4::jsonb, $5::jsonb, $6::jsonb, $7::jsonb, now(), now())
                ON CONFLICT (user_id) DO UPDATE SET
                    summary      = EXCLUDED.summary,
                    skills       = EXCLUDED.skills,
                    education    = EXCLUDED.education,
                    work_entries = EXCLUDED.work_entries,
                    extra_links  = EXCLUDED.extra_links,
                    design       = EXCLUDED.design,
                    updated_at   = now()
                RETURNING *
                """)
            .execute(Tuple.of(userId, summary,
                skills.encode(), education.encode(),
                workEntries.encode(), extraLinks.encode(),
                design != null ? design.encode() : null))
            .map(rows -> rows.iterator().next());
    }

    /** Returns work history entries derived from the user's review submissions, including company logo_url. */
    public Future<RowSet<Row>> getPrefillEntries(UUID userId) {
        return db.preparedQuery("""
                SELECT DISTINCT ON (r.manager_id)
                    m.company       AS company,
                    c.logo_url      AS logo_url,
                    r.manager_title AS title,
                    r.worked_from,
                    r.worked_until,
                    m.id            AS manager_id,
                    m.slug          AS manager_slug,
                    m.company_id
                FROM reviews r
                JOIN managers m ON m.id = r.manager_id
                LEFT JOIN companies c ON c.id = m.company_id
                WHERE r.user_id = $1
                  AND r.deleted_at IS NULL
                  AND m.approval_status IN ('approved', 'ghost')
                ORDER BY r.manager_id, r.worked_from DESC NULLS LAST
                """)
            .execute(Tuple.of(userId));
    }

    /**
     * Returns a map of lowercase-trimmed company name → logo_url for the given names.
     * Only returns companies that have a non-null logo_url.
     */
    public Future<Map<String, String>> findLogosByCompanyNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Future.succeededFuture(Map.of());
        }
        String[] lowerNames = names.stream()
            .map(n -> n.trim().toLowerCase())
            .toArray(String[]::new);
        return db.preparedQuery(
                "SELECT LOWER(TRIM(name)) AS key, logo_url FROM companies " +
                "WHERE LOWER(TRIM(name)) = ANY($1) AND logo_url IS NOT NULL")
            .execute(Tuple.of((Object) lowerNames))
            .map(rows -> {
                Map<String, String> map = new HashMap<>();
                for (Row row : rows) map.put(row.getString("key"), row.getString("logo_url"));
                return map;
            });
    }
}

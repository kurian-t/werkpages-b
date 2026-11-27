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
                ),
                -- A manager's title and the role someone interviewed for are the same vocabulary,
                -- so both feed the same suggestions. Keeping them apart would let the two halves
                -- of the product drift into different spellings of the same job.
                used_titles AS (
                    SELECT m.title_normalized AS norm, m.title AS display
                    FROM managers m
                    WHERE m.title_normalized IS NOT NULL
                      AND m.approval_status IN ('approved','ghost')
                      AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                    UNION ALL
                    SELECT normalize_role_title(ir.role_category), ir.role_category
                    FROM interview_reviews ir
                    WHERE ir.role_category IS NOT NULL
                      AND ir.deleted_at IS NULL
                ),
                -- What this product's own people actually wrote. Ranked first, always.
                used AS (
                    SELECT t.norm                                   AS title_normalized,
                           MODE() WITHIN GROUP (ORDER BY t.display) AS display_title,
                           NULL::TEXT                               AS acronym,
                           COUNT(*)::BIGINT                         AS usage_count,
                           0                                        AS tier
                    FROM used_titles t, q
                    WHERE t.norm IS NOT NULL
                      AND (
                            (q.loose  <> '' AND t.norm LIKE '%' || q.loose  || '%')
                         OR (q.strict IS NOT NULL AND t.norm LIKE '%' || q.strict || '%')
                         -- A typo must find a spelling people here already use, too.
                         OR (q.loose <> '' AND similarity(t.norm, q.loose) > 0.4)
                      )
                    GROUP BY t.norm, q.loose, q.strict
                ),
                /*
                  The curated vocabulary (V116), so a sector with no rows yet can still be
                  offered its own words. A nurse must be able to pick "Head Nurse" before any
                  nurse has ever been added, and a directory that only suggests what it already
                  holds can never bootstrap a sector.

                  Matched three ways, because people reach a title by three different routes:
                  the normalized form, the acronym (typing CEO must resolve), and the literal
                  words. An acronym repeated across rows is an ambiguity, and both rows come
                  back on purpose so the person can say which they meant.
                */
                cat AS (
                    SELECT jt.match_key AS title_normalized,
                           jt.title     AS display_title,
                           jt.acronym   AS acronym,
                           0::BIGINT    AS usage_count,
                           1            AS tier
                    FROM job_titles jt, q
                    WHERE (q.loose  <> '' AND jt.match_key LIKE '%' || q.loose || '%')
                       OR (q.strict IS NOT NULL AND jt.match_key LIKE '%' || q.strict || '%')
                       OR (jt.acronym IS NOT NULL AND upper(jt.acronym) LIKE upper(btrim($1)) || '%')
                       OR lower(jt.title) LIKE '%' || lower(btrim($1)) || '%'
                       /*
                         A TYPO MUST STILL FIND THE TITLE IT MEANT.

                         Substring matching answers nothing for "Managett", so the picker showed
                         only "Not listed? Add Managett" and the person had no way to reach
                         "Manager" except by noticing their own mistake. That is how a one-off
                         misspelling becomes a permanent entry in the vocabulary, which is the
                         thing this whole design exists to stop.

                         pg_trgm is installed (V55) and V116 puts a GIN trigram index on
                         match_key. 0.4 is deliberately loose for a picker: a wrong suggestion
                         costs a glance, a missing one costs a new bad spelling.
                       */
                       OR (q.loose <> '' AND similarity(jt.match_key, q.loose) > 0.4)
                )
                SELECT s.title_normalized, s.display_title, s.acronym, s.usage_count
                FROM (
                    SELECT * FROM used
                    UNION ALL
                    -- A catalogue entry whose key somebody here already uses would be a
                    -- duplicate row saying the same thing, so the live spelling wins.
                    SELECT c.* FROM cat c
                    WHERE NOT EXISTS (SELECT 1 FROM used u
                                      WHERE u.title_normalized = c.title_normalized)
                ) s, q
                -- Real usage above catalogue; then prefix matches, because someone typing
                -- "eng" means titles that start that way before ones that merely contain it;
                -- then how many people use the spelling.
                ORDER BY s.tier ASC,
                         (s.title_normalized LIKE COALESCE(q.strict, q.loose) || '%'
                          OR s.title_normalized LIKE q.loose || '%'
                          OR upper(COALESCE(s.acronym, '')) = upper(btrim($1))) DESC,
                         -- Then an exact-ish spelling ahead of a loose one, so a typo lands on
                         -- its nearest real title rather than on whatever merely contains it.
                         similarity(s.title_normalized, q.loose) DESC,
                         s.usage_count DESC,
                         s.title_normalized ASC
                LIMIT $2
                """)
            .execute(Tuple.of(query, limit));
    }

    /** Normalized titles held by visible managers but not yet classified — the coverage gap. */
    /**
     * Titles people have used that the catalogue does not hold.
     *
     * <h2>Why this is a list rather than a flag on a submission</h2>
     *
     * <p>The picker offers "Not listed? Add &lt;what you typed&gt;" for a title it cannot find, and
     * that row keeps the typed wording and sends nothing to anybody: the title reaches the site by
     * being submitted, and nothing ever proposes it for the curated vocabulary. So the question
     * "which titles are new?" cannot be answered from a record of who clicked what. It is answered
     * from the titles that exist, which also catches the ones nobody clicked that row for.
     *
     * <p>Grouped by the normalised key rather than the text, because "Sr. Manager" and "Snr
     * Manager" are one decision, not two. The spelling offered for promotion is the commonest one
     * in use, not the first or the longest, so promoting it blesses what people actually type.
     *
     * <p>Seeded managers are excluded for the reason every other aggregate excludes them: a
     * platform-created row is not somebody's answer.
     */
    public Future<RowSet<Row>> findTitlesNotInCatalogue(int limit) {
        return db.preparedQuery("""
                SELECT m.title_normalized                           AS match_key,
                       mode() WITHIN GROUP (ORDER BY btrim(m.title)) AS title,
                       count(*)                                     AS manager_count,
                       count(*) FILTER (
                           WHERE m.approval_status = 'pending_approval')  AS pending_count,
                       max(m.created_at)                            AS last_used
                  FROM managers m
                 WHERE m.title_normalized IS NOT NULL
                   AND btrim(COALESCE(m.title, '')) <> ''
                   AND m.approval_status IN ('approved', 'ghost', 'pending_approval')
                   AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                   AND NOT EXISTS (SELECT 1 FROM job_titles jt
                                    WHERE jt.match_key = m.title_normalized)
                 GROUP BY m.title_normalized
                 ORDER BY count(*) DESC, max(m.created_at) DESC
                 LIMIT $1
                """)
            .execute(Tuple.of(limit));
    }

    /**
     * Adds a title to the curated vocabulary, as {@code source = 'promoted'}.
     *
     * <p>V116 reserved that value in {@code job_titles_source_set} and nothing has ever written it:
     * the catalogue could only be changed by writing a migration. This is the path that was missing.
     *
     * <p>Refuses a duplicate on BOTH keys. {@code title} is unique in the schema, but
     * {@code match_key} is not, and two rows normalising to the same key would offer the same job
     * twice in one list - the picker dedupes a catalogue entry against live usage, not against
     * another catalogue entry.
     *
     * @return true when a row was added, false when the catalogue already held it
     */
    public Future<Boolean> promoteTitle(String title) {
        return db.preparedQuery("""
                INSERT INTO job_titles (title, match_key, source)
                SELECT btrim($1), normalize_role_title($1), 'promoted'
                 WHERE normalize_role_title($1) IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM job_titles
                                    WHERE match_key = normalize_role_title($1))
                ON CONFLICT (title) DO NOTHING
                RETURNING id
                """)
            .execute(Tuple.of(title))
            .map(rows -> rows.iterator().hasNext());
    }

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

package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import org.werkpages.service.AnthropicClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
                -- Never offer a retired company. Selecting one would attach a new manager to a
                -- company that has been absorbed, which is the one thing a merge is supposed to
                -- have ended. Its name still finds the survivor, because the merge left that name
                -- behind as an alias on the target.
                JOIN companies c ON c.id = best.id AND c.status <> 'merged'
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
     * Looks a company up by name without creating one. Used by the explicit creation endpoint to
     * tell "this already exists" from "this is new", which is the difference between returning an
     * ID and minting one.
     *
     * Matches on the normalised form, so "Acme, Inc." finds "Acme" rather than adding a near-twin.
     * Distinct from findByName, which matches the raw name exactly and would miss that.
     */
    public Future<Optional<Row>> findByNormalizedName(String name) {
        return db.preparedQuery(
                "SELECT * FROM companies WHERE normalized_name = normalize_company_name($1) LIMIT 1")
            .execute(Tuple.of(name))
            .map(rows -> rows.iterator().hasNext() ? Optional.of(rows.iterator().next()) : Optional.empty());
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
     *
     * An ID pointing at a company that has since been merged follows the merge. That is precisely
     * the stale-page case: somebody opened the form, an admin merged the company underneath them,
     * and their submission should land on the surviving company rather than on a headstone.
     */
    public Future<Row> resolve(Long companyId, String name, String domain, String logoUrl) {
        if (companyId == null) return findOrCreate(name, domain, logoUrl);
        return db.preparedQuery("SELECT * FROM companies WHERE id = $1")
            .execute(Tuple.of(companyId))
            .compose(rs -> {
                if (!rs.iterator().hasNext()) return findOrCreate(name, domain, logoUrl);
                Row row = rs.iterator().next();
                if (!"merged".equals(row.getString("status"))) return Future.succeededFuture(row);
                return resolveMergeTarget(companyId)
                    .compose(target -> target.<Future<Row>>map(Future::succeededFuture)
                        .orElseGet(() -> findOrCreate(name, domain, logoUrl)));
            });
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
    /**
     * Updates the cached company stats and waits for it, turning failure into a logged no-op.
     *
     * Replaces the fire-and-forget pattern this used to be called with: the future was created
     * inside a .map() and abandoned, so the request returned while the write was still running.
     * Two consequences, both real. In tests, a background write outlived the test that triggered
     * it and deadlocked against the next test's TRUNCATE - the statement holds company_stats_live
     * and wants managers, TRUNCATE holds managers and wants company_stats_live through its cascade.
     * In production the failure was swallowed, so the visible symptom was company stats quietly
     * going stale rather than an error anyone would see.
     *
     * Awaited, so the write is finished before the response and its ordering is deterministic.
     * Recovered, so a stats problem still cannot fail the user's actual operation - that part of
     * the original intent was right and is kept.
     */
    public Future<Void> syncStatsForManager(long managerId) {
        return updateCompanyStatsForManager(managerId)
            .recover(err -> {
                System.err.println("company_stats_live update failed for manager " + managerId
                                   + ": " + err.getMessage());
                return Future.succeededFuture();
            });
    }

    /** Company-keyed counterpart of {@link #syncStatsForManager}. Same await-and-recover contract. */
    public Future<Void> syncStatsForCompany(long companyId) {
        return updateCompanyStatsForCompany(companyId)
            .recover(err -> {
                System.err.println("company_stats_live update failed for company " + companyId
                                   + ": " + err.getMessage());
                return Future.succeededFuture();
            });
    }

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

    // ── Living with merged companies ──────────────────────────────────────────
    //
    // A merged company is retired, not deleted: its row survives so its URL resolves and its
    // history stays readable. That survival has a cost, which these two methods pay. Every surface
    // that offers a company to write against, or resolves one from a name or a slug, has to know
    // the difference between a company and a headstone.

    /**
     * Follows a merged company to whatever survived it.
     *
     * Merges chain: A is merged into B, and later B into C. Somebody holding A's link deserves to
     * land on C rather than on a retired B, so this walks the chain rather than taking one step.
     * The hop cap is a backstop; the merge itself refuses to create a loop.
     */
    public Future<Optional<Row>> resolveMergeTarget(long companyId) {
        return db.preparedQuery("""
                WITH RECURSIVE chain AS (
                    SELECT c.id, c.status, 0 AS hops
                    FROM companies c WHERE c.id = $1

                    UNION ALL

                    SELECT target.id, target.status, chain.hops + 1
                    FROM chain
                    JOIN company_merges m ON m.source_company_id = chain.id AND m.status = 'completed'
                    JOIN companies target ON target.id = m.target_company_id
                    WHERE chain.status = 'merged' AND chain.hops < 10
                )
                SELECT c.* FROM chain
                JOIN companies c ON c.id = chain.id
                WHERE chain.status <> 'merged'
                ORDER BY chain.hops DESC
                LIMIT 1
                """)
            .execute(Tuple.of(companyId))
            .map(rows -> rows.iterator().hasNext() ? Optional.of(rows.iterator().next()) : Optional.empty());
    }

    /**
     * The company a retired slug should lead to, if any.
     *
     * Reads the redirect written at merge time. Without this the merge's promise that old links
     * keep working is only a promise: the retired company still owns its slug, so the link would
     * render an empty page for a company whose managers have all moved elsewhere - worse than a
     * 404, because it looks like the company simply has nothing.
     */
    public Future<Optional<Row>> findRedirectTargetBySlug(String oldSlug) {
        return db.preparedQuery("""
                SELECT c.* FROM company_redirects r
                JOIN companies c ON c.id = r.company_id
                WHERE r.old_slug = $1 AND c.status <> 'merged'
                """)
            .execute(Tuple.of(oldSlug))
            .map(rows -> rows.iterator().hasNext() ? Optional.of(rows.iterator().next()) : Optional.empty());
    }

    // ── Corporate relationships ───────────────────────────────────────────────
    //
    // Two real companies where one owns the other, as opposed to two rows describing one company.
    // Nothing here merges anything: the child keeps its page, its managers and its ratings, and a
    // relationship only adds navigation between them.

    /**
     * Records that {@code childId} is part of {@code parentId}, replacing any existing parent.
     *
     * A company has one parent, so this is an upsert rather than an insert - re-pointing a
     * subsidiary is a correction, not a second relationship. Loops are rejected by a database
     * trigger rather than checked here, so a future caller cannot route around the check.
     */
    public Future<Void> setCompanyParent(long childId, long parentId, String relationshipType) {
        return db.preparedQuery("""
                INSERT INTO company_relationships (child_company_id, parent_company_id, relationship_type)
                VALUES ($1, $2, $3)
                ON CONFLICT (child_company_id)
                DO UPDATE SET parent_company_id = EXCLUDED.parent_company_id,
                              relationship_type = EXCLUDED.relationship_type,
                              created_at        = now()
                """)
            .execute(Tuple.of(childId, parentId, relationshipType))
            .mapEmpty();
    }

    /** Detaches a company from its parent. The company itself is untouched. */
    public Future<Boolean> removeCompanyParent(long childId) {
        return db.preparedQuery("DELETE FROM company_relationships WHERE child_company_id = $1")
            .execute(Tuple.of(childId))
            .map(rs -> rs.rowCount() > 0);
    }

    /**
     * The company this one is part of, if any.
     *
     * Excludes a merged parent: a retired company is not something to send a reader to, and
     * showing "Part of" a company that no longer exists as its own entity would be worse than
     * showing nothing.
     */
    public Future<Optional<Row>> findCompanyParent(long childId) {
        return db.preparedQuery("""
                SELECT p.id, p.name, p.slug, p.logo_url, r.relationship_type
                FROM company_relationships r
                JOIN companies p ON p.id = r.parent_company_id
                WHERE r.child_company_id = $1
                  AND p.status <> 'merged'
                """)
            .execute(Tuple.of(childId))
            .map(rows -> rows.iterator().hasNext() ? Optional.of(rows.iterator().next()) : Optional.empty());
    }

    /**
     * The companies that are part of this one, with the stats needed to list them.
     *
     * Ordered by size so the brands a reader recognises come first. Stats are LEFT JOINed, never
     * INNER: a subsidiary with no managers yet is still part of the group and still belongs in
     * the list, which is the same reasoning that keeps company_stats_live out of the picker's
     * WHERE clause.
     */
    public Future<RowSet<Row>> findCompanyChildren(long parentId) {
        return db.preparedQuery("""
                SELECT c.id, c.name, c.slug, c.logo_url, r.relationship_type,
                       COALESCE(s.manager_count, 0) AS manager_count,
                       COALESCE(s.total_reviews, 0) AS total_reviews,
                       s.avg_rating
                FROM company_relationships r
                JOIN companies c ON c.id = r.child_company_id
                LEFT JOIN company_stats_live s ON s.company_id = c.id
                WHERE r.parent_company_id = $1
                  AND c.status <> 'merged'
                ORDER BY COALESCE(s.manager_count, 0) DESC, c.name ASC
                """)
            .execute(Tuple.of(parentId));
    }

    /**
     * Management across a whole corporate group: the company plus everything beneath it.
     *
     * A second, separate figure - never a replacement for the company's own. Loblaw's rating means
     * Loblaw's managers; the group rating means Loblaw's managers and Zehrs' and Shoppers'. Folding
     * hundreds of store managers into a corporate average would flatter or damn either one for the
     * wrong reason, so both numbers exist and both are labelled.
     *
     * The average is computed the same way as a single company's - an unweighted mean of manager
     * ratings - specifically so the two numbers displayed side by side are comparable. Averaging
     * the companies' averages instead would let a two-manager brand pull as hard as a
     * two-hundred-manager one.
     *
     * Walks the tree, so a group three levels deep counts all of it. The depth cap mirrors the
     * cycle trigger's: loops are already impossible, and this is the backstop that keeps a bug
     * from becoming a hang.
     */
    public Future<Optional<Row>> findGroupStats(long parentId) {
        return db.preparedQuery("""
                WITH RECURSIVE tree AS (
                    SELECT id, 0 AS depth FROM companies WHERE id = $1
                    UNION ALL
                    SELECT r.child_company_id, t.depth + 1
                    FROM company_relationships r
                    JOIN tree t ON r.parent_company_id = t.id
                    WHERE t.depth < 10
                )
                -- Each manager belongs to exactly one company, and the tree holds each company
                -- once, so no manager is counted twice and a plain SUM is correct.
                SELECT COUNT(DISTINCT c.id)              AS company_count,
                       COUNT(DISTINCT m.id)              AS manager_count,
                       COALESCE(SUM(m.reviews_count), 0) AS total_reviews,
                       ROUND(AVG(m.overall_rating) FILTER (
                           WHERE m.overall_rating IS NOT NULL AND m.reviews_count > 0
                       )::NUMERIC, 1)                    AS avg_rating
                FROM tree
                JOIN companies c ON c.id = tree.id AND c.status <> 'merged'
                LEFT JOIN managers m
                       ON m.company_id = c.id
                      AND m.approval_status IN ('approved', 'ghost')
                      AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                """)
            .execute(Tuple.of(parentId))
            .map(rows -> rows.iterator().hasNext() ? Optional.of(rows.iterator().next()) : Optional.empty());
    }

    /**
     * A read-only assessment of what merging {@code mergeId} into {@code keepId} would do.
     *
     * Runs before anything is written, so an admin sees the size of the operation and, more
     * importantly, whether it is safe to perform at all. Nothing here modifies data.
     *
     * The blocking case is a unique-index collision on interview reviews: the index is
     * (user_id, company_id, interview_year) WHERE deleted_at IS NULL, so if one person reviewed
     * interviewing at both companies in the same year, moving their reviews together makes two rows
     * that cannot coexist. There is no correct automatic answer - both are genuine contributions
     * from the same person - so the merge refuses and a human decides which stands.
     */
    public Future<io.vertx.core.json.JsonObject> previewMerge(long keepId, long mergeId) {
        if (keepId == mergeId) {
            return Future.failedFuture("Cannot merge a company into itself");
        }
        return db.preparedQuery("""
                SELECT
                  (SELECT COUNT(*) FROM managers          WHERE company_id = $2) AS managers,
                  (SELECT COUNT(*) FROM career_history    WHERE company_id = $2) AS career_entries,
                  (SELECT COUNT(*) FROM interview_reviews WHERE company_id = $2 AND deleted_at IS NULL) AS interviews,
                  (SELECT COUNT(*) FROM company_aliases   WHERE company_id = $2) AS aliases,
                  (SELECT COUNT(*) FROM manager_edits     WHERE requested_company_id = $2) AS pending_edits,
                  (SELECT name FROM companies WHERE id = $1) AS keep_name,
                  (SELECT name FROM companies WHERE id = $2) AS merge_name,
                  (SELECT slug FROM companies WHERE id = $2) AS merge_slug,
                  (SELECT status FROM companies WHERE id = $2) AS merge_status,
                  -- The blocking conflict: the same person, the same year, both companies.
                  (SELECT COUNT(*) FROM interview_reviews a
                     JOIN interview_reviews b
                       ON a.user_id = b.user_id
                      AND a.interview_year = b.interview_year
                    WHERE a.company_id = $2 AND b.company_id = $1
                      AND a.deleted_at IS NULL AND b.deleted_at IS NULL
                      AND a.user_id IS NOT NULL) AS interview_conflicts,
                  -- Not blocking, but the admin should see it: the same person may now appear
                  -- twice under one company. Company identity and manager identity are separate
                  -- problems and this merge deliberately does not touch the second one.
                  (SELECT COUNT(*) FROM managers a
                     JOIN managers b ON LOWER(TRIM(a.name)) = LOWER(TRIM(b.name))
                    WHERE a.company_id = $2 AND b.company_id = $1) AS duplicate_managers
                """)
            .execute(Tuple.of(keepId, mergeId))
            .map(rows -> {
                Row r = rows.iterator().next();
                if (r.getString("keep_name") == null)  throw new IllegalArgumentException("Target company not found");
                if (r.getString("merge_name") == null) throw new IllegalArgumentException("Source company not found");
                return new io.vertx.core.json.JsonObject()
                    .put("keepName",           r.getString("keep_name"))
                    .put("mergeName",          r.getString("merge_name"))
                    .put("mergeStatus",        r.getString("merge_status"))
                    .put("managers",           r.getLong("managers"))
                    .put("careerEntries",      r.getLong("career_entries"))
                    .put("interviews",         r.getLong("interviews"))
                    .put("aliases",            r.getLong("aliases"))
                    .put("pendingEdits",       r.getLong("pending_edits"))
                    .put("interviewConflicts", r.getLong("interview_conflicts"))
                    .put("duplicateManagers",  r.getLong("duplicate_managers"))
                    .put("blocked",            r.getLong("interview_conflicts") > 0);
            });
    }

    /**
     * Merges {@code mergeId} into {@code keepId}.
     *
     * Replaces an earlier version that finished with {@code DELETE FROM companies}. That delete
     * cascaded: interview_reviews, interview_review_deletions and company_aliases all reference
     * companies with ON DELETE CASCADE, so merging a company silently destroyed every interview
     * review written about it. It also left no record of what had moved, which meant no undo, and
     * it ran as loose statements rather than a transaction, so a failure halfway through left the
     * data split between two companies.
     *
     * This version:
     *   - runs in one transaction, so it either all happens or none of it does;
     *   - moves interview data instead of letting it be deleted;
     *   - retires the source rather than deleting it, so nothing cascades and its URL survives;
     *   - writes a manifest of every row it moved, by id, which is what makes an undo possible;
     *   - refuses outright when moving the data would violate a unique index.
     */
    public Future<UUID> mergeCompanies(long keepId, long mergeId, UUID adminUserId) {
        if (keepId == mergeId) return Future.failedFuture("Cannot merge a company into itself");

        return previewMerge(keepId, mergeId).compose(preview -> {
            if (preview.getBoolean("blocked")) {
                return Future.failedFuture(
                    "Cannot merge: " + preview.getLong("interviewConflicts") + " interview review(s) "
                    + "would collide, because the same person reviewed interviewing at both companies "
                    + "in the same year. Both are real contributions and neither should be discarded "
                    + "automatically. Resolve them first, then merge.");
            }
            String keepName  = preview.getString("keepName");
            String mergeName = preview.getString("mergeName");

            return ((Pool) db).withTransaction(conn ->
                // The manifest header first: every moved row references it.
                conn.preparedQuery("""
                        INSERT INTO company_merges (source_company_id, target_company_id, merged_by, source_snapshot)
                        VALUES ($1, $2, $3, $4::JSONB)
                        RETURNING id
                        """)
                    // The snapshot carries the source's status because undo has to put it back.
                    // A ghost company that was merged must return as a ghost, not be promoted to
                    // approved by the act of being restored.
                    .execute(Tuple.of(mergeId, keepId, adminUserId,
                        new io.vertx.core.json.JsonObject()
                            .put("name", mergeName)
                            .put("status", preview.getString("mergeStatus"))
                            .put("preview", preview).encode()))
                    .map(rs -> rs.iterator().next().getUUID("id"))

                    // Managers: the FK and the denormalised name move together. Recording the old
                    // text is what lets an undo put the picker back the way it was.
                    .compose(mergeUuid -> conn.preparedQuery("""
                            INSERT INTO company_merge_records (merge_id, entity_type, record_id, old_company_id, new_company_id, old_company_text)
                            SELECT $1, 'manager', m.id::TEXT, $3, $2, m.company
                            FROM managers m WHERE m.company_id = $3
                            """)
                        .execute(Tuple.of(mergeUuid, keepId, mergeId))
                        .compose(v -> conn.preparedQuery("UPDATE managers SET company_id = $1, company = $2 WHERE company_id = $3")
                            .execute(Tuple.of(keepId, keepName, mergeId)))

                        // Career history: same shape, same reasoning.
                        .compose(v -> conn.preparedQuery("""
                                INSERT INTO company_merge_records (merge_id, entity_type, record_id, old_company_id, new_company_id, old_company_text)
                                SELECT $1, 'career_history', ch.id::TEXT, $3, $2, ch.company
                                FROM career_history ch WHERE ch.company_id = $3
                                """)
                            .execute(Tuple.of(mergeUuid, keepId, mergeId)))
                        .compose(v -> conn.preparedQuery("UPDATE career_history SET company_id = $1, company = $2 WHERE company_id = $3")
                            .execute(Tuple.of(keepId, keepName, mergeId)))

                        // Interview reviews: moved, not destroyed. The preview has already proved
                        // no two of them will collide.
                        .compose(v -> conn.preparedQuery("""
                                INSERT INTO company_merge_records (merge_id, entity_type, record_id, old_company_id, new_company_id)
                                SELECT $1, 'interview_review', ir.id::TEXT, $3, $2
                                FROM interview_reviews ir WHERE ir.company_id = $3
                                """)
                            .execute(Tuple.of(mergeUuid, keepId, mergeId)))
                        .compose(v -> conn.preparedQuery("UPDATE interview_reviews SET company_id = $1 WHERE company_id = $2")
                            .execute(Tuple.of(keepId, mergeId)))
                        .compose(v -> conn.preparedQuery("UPDATE interview_review_deletions SET company_id = $1 WHERE company_id = $2")
                            .execute(Tuple.of(keepId, mergeId)))

                        // Pending edit requests that pointed at the source now point at the target,
                        // so an admin approving one afterwards lands on the surviving company.
                        .compose(v -> conn.preparedQuery("UPDATE manager_edits SET requested_company_id = $1 WHERE requested_company_id = $2")
                            .execute(Tuple.of(keepId, mergeId)))

                        // Aliases move, and the source's own name becomes one. That is the whole
                        // point: someone searching the old name must now find the surviving company
                        // rather than a blank, or they will simply create it again.
                        //
                        // Moved by UPDATE rather than copy-and-delete. Copying would give the
                        // target brand new alias rows and destroy the originals, leaving an undo
                        // nothing to put back. Updating keeps each row's identity, so the manifest
                        // can name it and the reverse is a straight UPDATE.
                        //
                        // An alias the target already has cannot move onto it - the unique index
                        // forbids two rows with the same normalised alias under one company. Those
                        // stay where they are, on the retired source, rather than being deleted.
                        //
                        // Leaving them is not laziness, it is the only version that survives an
                        // undo. A deleted row cannot be updated back, so undo would have to
                        // re-create it from the manifest - and the manifest does not carry the
                        // alias text or its type, so what came back would not be what was lost.
                        // A row left on a merged company is already invisible: every read filters
                        // status <> 'merged'. It costs nothing while the merge stands and is
                        // simply correct again the moment the source is restored.
                        //
                        // Recorded anyway, with no undo action attached, so the manifest still
                        // explains what the merge decided about every alias it saw.
                        .compose(v -> conn.preparedQuery("""
                                INSERT INTO company_merge_records (merge_id, entity_type, record_id, old_company_id, new_company_id, conflict_action)
                                SELECT $1, 'company_alias', a.id::TEXT, $3, $2, 'archived_duplicate'
                                FROM company_aliases a
                                WHERE a.company_id = $3
                                  AND EXISTS (SELECT 1 FROM company_aliases t
                                              WHERE t.company_id = $2 AND t.normalized_alias = a.normalized_alias)
                                """)
                            .execute(Tuple.of(mergeUuid, keepId, mergeId)))
                        .compose(v -> conn.preparedQuery("""
                                INSERT INTO company_merge_records (merge_id, entity_type, record_id, old_company_id, new_company_id)
                                SELECT $1, 'company_alias', a.id::TEXT, $3, $2
                                FROM company_aliases a
                                WHERE a.company_id = $3
                                  AND NOT EXISTS (SELECT 1 FROM company_aliases t
                                                  WHERE t.company_id = $2 AND t.normalized_alias = a.normalized_alias)
                                """)
                            .execute(Tuple.of(mergeUuid, keepId, mergeId)))
                        .compose(v -> conn.preparedQuery("""
                                UPDATE company_aliases a SET company_id = $1
                                WHERE a.company_id = $2
                                  AND NOT EXISTS (SELECT 1 FROM company_aliases t
                                                  WHERE t.company_id = $1 AND t.normalized_alias = a.normalized_alias)
                                """)
                            .execute(Tuple.of(keepId, mergeId)))

                        // The source's own name, added to the target. Recorded separately because
                        // undo has to remove it rather than move it back: it never belonged to the
                        // source as an alias, it WAS the source.
                        .compose(v -> conn.preparedQuery("""
                                INSERT INTO company_aliases (company_id, alias, alias_type)
                                VALUES ($1, $2, 'MERGED_NAME')
                                ON CONFLICT (company_id, normalized_alias) DO NOTHING
                                RETURNING id
                                """)
                            .execute(Tuple.of(keepId, mergeName))
                            .compose(rs -> rs.iterator().hasNext()
                                ? conn.preparedQuery("""
                                        INSERT INTO company_merge_records (merge_id, entity_type, record_id, old_company_id, new_company_id)
                                        VALUES ($1, 'company_alias_added', $2, $3, $4)
                                        """)
                                    .execute(Tuple.of(mergeUuid, String.valueOf(rs.iterator().next().getLong("id")), mergeId, keepId))
                                    .mapEmpty()
                                : Future.succeededFuture()))

                        // Corporate structure follows the survivor.
                        //
                        // Without this a merge quietly costs you a relationship: absorb "Zehrs
                        // Markets" into "Zehrs" and the link saying it is part of Loblaw is left
                        // pointing at a retired company, where every reader filters it out. Nothing
                        // breaks and nothing complains - the group silently loses a member, and the
                        // admin who set it up has to notice and redo it.
                        //
                        // Two directions, handled separately because they fail differently.
                        //
                        // First, who the source belongs to. This is blocked when the survivor
                        // already has a parent of its own, which is the common case and not an
                        // error: the survivor's own answer to "what is this part of?" wins, and one
                        // parent per company is the point of the unique index.
                        .compose(v -> conn.preparedQuery("""
                                WITH RECURSIVE ds AS (
                                    SELECT $2::BIGINT AS id, 0 AS depth
                                    UNION ALL
                                    SELECT r.child_company_id, d.depth + 1
                                    FROM company_relationships r JOIN ds d ON r.parent_company_id = d.id
                                    WHERE d.depth < 20
                                )
                                INSERT INTO company_merge_records (merge_id, entity_type, record_id, old_company_id, new_company_id)
                                SELECT $1, 'company_relationship_child', r.id::TEXT, $3, $2
                                FROM company_relationships r
                                WHERE r.child_company_id = $3
                                  AND NOT EXISTS (SELECT 1 FROM company_relationships k WHERE k.child_company_id = $2)
                                  AND r.parent_company_id NOT IN (SELECT id FROM ds)
                                """)
                            .execute(Tuple.of(mergeUuid, keepId, mergeId)))
                        .compose(v -> conn.preparedQuery("""
                                UPDATE company_relationships r SET child_company_id = $2
                                FROM company_merge_records rec
                                WHERE rec.merge_id = $1 AND rec.entity_type = 'company_relationship_child'
                                  AND r.id = rec.record_id::BIGINT
                                """)
                            .execute(Tuple.of(mergeUuid, keepId)))

                        // Then what belongs to the source. Its children become the survivor's, one
                        // row each, so a group keeps its members when one of its own companies is
                        // absorbed. Evaluated after the step above, because that step may just have
                        // given the survivor a parent and so changed what would close a loop.
                        //
                        // The NOT IN guards are cycle guards. The database rejects loops with a
                        // trigger, but a trigger firing here would abort the whole merge over an
                        // edge case, so the impossible rows are left behind instead of attempted.
                        // A relationship left on a retired company is inert - every read filters
                        // status <> 'merged' - and becomes live again if the merge is undone.
                        .compose(v -> conn.preparedQuery("""
                                WITH RECURSIVE anc AS (
                                    SELECT $2::BIGINT AS id, 0 AS depth
                                    UNION ALL
                                    SELECT r.parent_company_id, a.depth + 1
                                    FROM company_relationships r JOIN anc a ON r.child_company_id = a.id
                                    WHERE a.depth < 20
                                )
                                INSERT INTO company_merge_records (merge_id, entity_type, record_id, old_company_id, new_company_id)
                                SELECT $1, 'company_relationship_parent', r.id::TEXT, $3, $2
                                FROM company_relationships r
                                WHERE r.parent_company_id = $3
                                  AND r.child_company_id NOT IN (SELECT id FROM anc)
                                """)
                            .execute(Tuple.of(mergeUuid, keepId, mergeId)))
                        .compose(v -> conn.preparedQuery("""
                                UPDATE company_relationships r SET parent_company_id = $2
                                FROM company_merge_records rec
                                WHERE rec.merge_id = $1 AND rec.entity_type = 'company_relationship_parent'
                                  AND r.id = rec.record_id::BIGINT
                                """)
                            .execute(Tuple.of(mergeUuid, keepId)))

                        // The stats tables are keyed by company_id as a primary key, so their rows
                        // cannot be moved onto a target that already has one. Drop the source's and
                        // let the target's be recomputed after the transaction.
                        .compose(v -> conn.preparedQuery("DELETE FROM company_stats_live WHERE company_id = $1")
                            .execute(Tuple.of(mergeId)))
                        .compose(v -> conn.preparedQuery("DELETE FROM company_interview_stats WHERE company_id = $1")
                            .execute(Tuple.of(mergeId)))

                        // The old address keeps working. People share company links.
                        .compose(v -> conn.preparedQuery("""
                                INSERT INTO company_redirects (old_slug, company_id)
                                SELECT c.slug, $1 FROM companies c WHERE c.id = $2 AND c.slug IS NOT NULL
                                ON CONFLICT (old_slug) DO UPDATE SET company_id = EXCLUDED.company_id
                                """)
                            .execute(Tuple.of(keepId, mergeId)))

                        // Retired, not deleted. Nothing cascades, the history stays readable, and
                        // the row is still there to be pointed at by the redirect above.
                        .compose(v -> conn.preparedQuery("UPDATE companies SET status = 'merged', updated_at = now() WHERE id = $1")
                            .execute(Tuple.of(mergeId)))

                        // Review snapshots on the moved managers show the surviving name.
                        .compose(v -> conn.preparedQuery("""
                                UPDATE reviews SET manager_company = $1
                                WHERE manager_id IN (SELECT id FROM managers WHERE company_id = $2)
                                """)
                            .execute(Tuple.of(keepName, keepId)))
                        .map(v -> mergeUuid)));
        });
    }

    /**
     * Reverses a merge, putting every row back where it came from.
     *
     * This is why the merge writes a manifest of row ids rather than a summary. Undo does not
     * reason about what "probably" belonged to the source: it reads the list of rows the merge
     * moved and moves exactly those back. Anything added to the target after the merge stays with
     * the target, because it is not on the list.
     *
     * Runs in one transaction. A half-undone merge would be worse than either state.
     */
    public Future<io.vertx.core.json.JsonObject> undoMerge(UUID mergeRecordId) {
        return db.preparedQuery("""
                SELECT m.id, m.source_company_id, m.target_company_id, m.status, m.source_snapshot
                FROM company_merges m WHERE m.id = $1
                """)
            .execute(Tuple.of(mergeRecordId))
            .compose(rs -> {
                if (!rs.iterator().hasNext())
                    return Future.failedFuture("Merge not found");
                Row merge = rs.iterator().next();
                if (!"completed".equals(merge.getString("status")))
                    return Future.failedFuture("This merge has already been reverted");

                long sourceId = merge.getLong("source_company_id");
                long targetId = merge.getLong("target_company_id");
                // JSONB arrives as either a JsonObject or its string form depending on how the
                // column was written, so it is parsed rather than cast - the same defensive read
                // the category_averages column needs elsewhere.
                Object rawSnapshot = merge.getValue("source_snapshot");
                io.vertx.core.json.JsonObject snapshot =
                    rawSnapshot instanceof io.vertx.core.json.JsonObject
                        ? (io.vertx.core.json.JsonObject) rawSnapshot
                        : rawSnapshot != null
                            ? new io.vertx.core.json.JsonObject(rawSnapshot.toString())
                            : new io.vertx.core.json.JsonObject();
                // A ghost company that was merged comes back as a ghost. Being restored is not a
                // promotion.
                String restoredStatus = snapshot.getString("status", "approved");

                return ((Pool) db).withTransaction(conn ->
                    // Managers and career history: the foreign key and the denormalised name both
                    // go back, or the picker would keep showing the surviving company's name for a
                    // manager that no longer belongs to it.
                    conn.preparedQuery("""
                            UPDATE managers m
                            SET company_id = r.old_company_id::BIGINT,
                                company    = COALESCE(r.old_company_text, m.company)
                            FROM company_merge_records r
                            WHERE r.merge_id = $1 AND r.entity_type = 'manager'
                              AND m.id = r.record_id::BIGINT
                            """)
                        .execute(Tuple.of(mergeRecordId))
                        .compose(v -> conn.preparedQuery("""
                                UPDATE career_history ch
                                SET company_id = r.old_company_id::BIGINT,
                                    company    = COALESCE(r.old_company_text, ch.company)
                                FROM company_merge_records r
                                WHERE r.merge_id = $1 AND r.entity_type = 'career_history'
                                  AND ch.id = r.record_id::BIGINT
                                """)
                            .execute(Tuple.of(mergeRecordId)))
                        .compose(v -> conn.preparedQuery("""
                                UPDATE interview_reviews ir
                                SET company_id = r.old_company_id::BIGINT
                                FROM company_merge_records r
                                WHERE r.merge_id = $1 AND r.entity_type = 'interview_review'
                                  AND ir.id = r.record_id::UUID
                                """)
                            .execute(Tuple.of(mergeRecordId)))

                        // Aliases that moved go back; the source's own name, which the merge added
                        // to the target, is removed rather than moved - it was never an alias of
                        // the source, it was the source.
                        .compose(v -> conn.preparedQuery("""
                                UPDATE company_aliases a
                                SET company_id = r.old_company_id::BIGINT
                                FROM company_merge_records r
                                WHERE r.merge_id = $1 AND r.entity_type = 'company_alias'
                                  AND r.conflict_action IS NULL
                                  AND a.id = r.record_id::BIGINT
                                """)
                            .execute(Tuple.of(mergeRecordId)))
                        .compose(v -> conn.preparedQuery("""
                                DELETE FROM company_aliases a
                                USING company_merge_records r
                                WHERE r.merge_id = $1 AND r.entity_type = 'company_alias_added'
                                  AND a.id = r.record_id::BIGINT
                                """)
                            .execute(Tuple.of(mergeRecordId)))

                        // Corporate structure goes back the way it came. Only the rows the merge
                        // actually moved are listed, so a parent link the survivor had before the
                        // merge, or gained after it, stays exactly where it is.
                        .compose(v -> conn.preparedQuery("""
                                UPDATE company_relationships r SET child_company_id = rec.old_company_id
                                FROM company_merge_records rec
                                WHERE rec.merge_id = $1 AND rec.entity_type = 'company_relationship_child'
                                  AND r.id = rec.record_id::BIGINT
                                """)
                            .execute(Tuple.of(mergeRecordId)))
                        .compose(v -> conn.preparedQuery("""
                                UPDATE company_relationships r SET parent_company_id = rec.old_company_id
                                FROM company_merge_records rec
                                WHERE rec.merge_id = $1 AND rec.entity_type = 'company_relationship_parent'
                                  AND r.id = rec.record_id::BIGINT
                                """)
                            .execute(Tuple.of(mergeRecordId)))

                        // Pending edit requests follow their company back.
                        .compose(v -> conn.preparedQuery(
                                "UPDATE manager_edits SET requested_company_id = $1 WHERE requested_company_id = $2")
                            .execute(Tuple.of(sourceId, targetId)))

                        // The redirect goes: the source is a real company again and owns its URL.
                        .compose(v -> conn.preparedQuery(
                                "DELETE FROM company_redirects WHERE old_slug IN (SELECT slug FROM companies WHERE id = $1)")
                            .execute(Tuple.of(sourceId)))

                        .compose(v -> conn.preparedQuery("UPDATE companies SET status = $2, updated_at = now() WHERE id = $1")
                            .execute(Tuple.of(sourceId, restoredStatus)))

                        // Marked rather than deleted, so the history of what was done and undone
                        // survives. The manifest stays too: it is the evidence.
                        .compose(v -> conn.preparedQuery("UPDATE company_merges SET status = 'reverted' WHERE id = $1")
                            .execute(Tuple.of(mergeRecordId)))

                        .map(v -> new io.vertx.core.json.JsonObject()
                            .put("success", true)
                            .put("restoredCompanyId", sourceId)
                            .put("targetCompanyId", targetId)));
            });
    }
}

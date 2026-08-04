package org.ratemymanager.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data-access layer for the {@code managers} and {@code career_history} tables.
 */
public class ManagerRepository {

    // ── SQL constants ─────────────────────────────────────────────────────────

    static final String GET_BY_ID_SQL = """
            SELECT
                m.id, m.name, m.company, m.title, m.image, m.overall_rating,
                m.reviews_count, m.bio, m.status, m.approval_status,
                m.category_averages, m.linkedin_url, m.company_logo_url, m.country,
                m.created_at, m.submitted_by, m.slug,
                c.slug AS company_slug,
                COALESCE(ch.career_history, '[]') AS career_history,
                COALESCE(r.reviews, '[]') AS reviews
            FROM managers m
            LEFT JOIN companies c ON c.id = m.company_id
            LEFT JOIN (
                SELECT manager_id,
                    json_agg(jsonb_build_object(
                        'company', company, 'title', title,
                        'startDate', start_date, 'endDate', end_date
                    ) ORDER BY start_date DESC) AS career_history
                FROM career_history GROUP BY manager_id
            ) ch ON ch.manager_id = m.id
            LEFT JOIN (
                SELECT manager_id,
                    json_agg(jsonb_build_object(
                        'id', id, 'author', author,
                        'overallRating', overall_rating,
                        'ratings', jsonb_build_object(
                            'communication_style', communication_style,
                            'perceived_approachability', perceived_approachability,
                            'perceived_clarity_of_expectations', perceived_clarity_of_expectations,
                            'feedback_style', feedback_style,
                            'perceived_supportiveness', perceived_supportiveness,
                            'decision_making_style', decision_making_style,
                            'organization_and_planning_style', organization_and_planning_style,
                            'delegation_style', delegation_style,
                            'perceived_professional_demeanor', perceived_professional_demeanor,
                            'overall_working_experience', overall_working_experience
                        ),
                        'managerCompany', manager_company, 'managerTitle', manager_title,
                        'text', text, 'verified', verified, 'helpfulCount', helpful_count,
                        'createdAt', created_at, 'workedFrom', worked_from, 'workedUntil', worked_until
                    ) ORDER BY created_at DESC) AS reviews
                FROM reviews WHERE deleted_at IS NULL GROUP BY manager_id
            ) r ON r.manager_id = m.id
            WHERE m.id = $1
            LIMIT 1
            """;

    private static final String SELECT_BODY = """
            SELECT
                m.id, m.name, m.company, m.title, m.image, m.overall_rating,
                m.reviews_count, m.bio, m.status, m.approval_status,
                m.category_averages, m.linkedin_url, m.company_logo_url, m.country, m.created_at,
                m.submitted_by, m.external_id, m.company_id, m.slug,
                c.slug AS company_slug,
                COALESCE(
                    json_agg(json_build_object(
                        'company', ch.company, 'title', ch.title,
                        'startDate', ch.start_date, 'endDate', ch.end_date
                    ) ORDER BY ch.start_date DESC)
                    FILTER (WHERE ch.id IS NOT NULL), '[]'
                ) AS career_history
            FROM managers m
            LEFT JOIN companies c ON c.id = m.company_id
            LEFT JOIN career_history ch ON ch.manager_id = m.id
            """;

    private final SqlClient db;

    public ManagerRepository(SqlClient db) {
        this.db = db;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public Future<Optional<Row>> findById(long id) {
        return db.preparedQuery(GET_BY_ID_SQL)
            .execute(Tuple.of(id))
            .map(rows -> {
                Iterator<Row> it = rows.iterator();
                return it.hasNext() ? Optional.of(it.next()) : Optional.empty();
            });
    }

    public Future<RowSet<Row>> search(int limit, int offset, String searchPattern, String companyPattern, String sortBy) {
        return search(limit, offset, searchPattern, companyPattern, sortBy, null);
    }

    public Future<RowSet<Row>> search(int limit, int offset, String searchPattern, String companyPattern, String sortBy, UUID userId) {
        boolean hasSearch  = searchPattern  != null;
        boolean hasCompany = companyPattern != null;
        String  orderBy    = buildOrderBy(sortBy);

        String sql;
        Tuple  tuple;

        if (userId != null) {
            if (hasSearch && hasCompany) {
                sql   = SELECT_BODY + "WHERE (m.name ILIKE $3 OR m.company ILIKE $3 OR m.title ILIKE $3) AND m.company ILIKE $4 AND (m.approval_status IN ('approved','ghost') OR (m.approval_status = 'pending_approval' AND m.search_created_by_user_id = $5)) GROUP BY m.id, c.slug " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, searchPattern, companyPattern, userId);
            } else if (hasSearch) {
                sql   = SELECT_BODY + "WHERE (m.name ILIKE $3 OR m.company ILIKE $3 OR m.title ILIKE $3) AND (m.approval_status IN ('approved','ghost') OR (m.approval_status = 'pending_approval' AND m.search_created_by_user_id = $4)) GROUP BY m.id, c.slug " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, searchPattern, userId);
            } else if (hasCompany) {
                sql   = SELECT_BODY + "WHERE m.company ILIKE $3 AND (m.approval_status IN ('approved','ghost') OR (m.approval_status = 'pending_approval' AND m.search_created_by_user_id = $4)) GROUP BY m.id, c.slug " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, companyPattern, userId);
            } else {
                sql   = SELECT_BODY + "WHERE (m.approval_status IN ('approved','ghost') OR (m.approval_status = 'pending_approval' AND m.search_created_by_user_id = $3)) GROUP BY m.id, c.slug " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, userId);
            }
        } else {
            if (hasSearch && hasCompany) {
                sql   = SELECT_BODY + "WHERE (m.name ILIKE $3 OR m.company ILIKE $3 OR m.title ILIKE $3) AND m.company ILIKE $4 AND m.approval_status IN ('approved','ghost') GROUP BY m.id, c.slug " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, searchPattern, companyPattern);
            } else if (hasSearch) {
                sql   = SELECT_BODY + "WHERE (m.name ILIKE $3 OR m.company ILIKE $3 OR m.title ILIKE $3) AND m.approval_status IN ('approved','ghost') GROUP BY m.id, c.slug " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, searchPattern);
            } else if (hasCompany) {
                sql   = SELECT_BODY + "WHERE m.company ILIKE $3 AND m.approval_status IN ('approved','ghost') GROUP BY m.id, c.slug " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, companyPattern);
            } else {
                sql   = SELECT_BODY + "WHERE m.approval_status IN ('approved','ghost') GROUP BY m.id, c.slug " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset);
            }
        }

        return db.preparedQuery(sql).execute(tuple);
    }

    private String buildOrderBy(String sortBy) {
        return switch (sortBy == null ? "featured" : sortBy) {
            case "rating"  -> "ORDER BY m.overall_rating DESC NULLS LAST, m.id ASC";
            case "reviews" -> "ORDER BY m.reviews_count DESC, m.id ASC";
            case "name"    -> "ORDER BY m.name ASC";
            // Tier 0: user-submitted managers
            // Tier 1: seeded/scraped — real logos (company_logo_url IS NOT NULL) surface first,
            //         then the rest in stable pseudo-random order so pagination doesn't shift.
            default        -> """
                ORDER BY
                  CASE WHEN m.external_id IS NULL AND COALESCE(m.reviews_count, 0) > 0 THEN 0
                       WHEN m.external_id IS NULL THEN 1
                       ELSE 2 END,
                  CASE WHEN m.company_logo_url IS NOT NULL THEN 0 ELSE 1 END,
                  MD5(CAST(m.id AS text))""";
        };
    }

    public Future<Long> count(String searchPattern, String companyPattern) {
        boolean hasSearch  = searchPattern  != null;
        boolean hasCompany = companyPattern != null;

        if (hasSearch && hasCompany) {
            return db.preparedQuery("SELECT COUNT(*) FROM managers WHERE (name ILIKE $1 OR company ILIKE $1 OR title ILIKE $1) AND company ILIKE $2 AND approval_status IN ('approved','ghost')")
                .execute(Tuple.of(searchPattern, companyPattern))
                .map(rows -> rows.iterator().next().getLong(0));
        } else if (hasSearch) {
            return db.preparedQuery("SELECT COUNT(*) FROM managers WHERE (name ILIKE $1 OR company ILIKE $1 OR title ILIKE $1) AND approval_status IN ('approved','ghost')")
                .execute(Tuple.of(searchPattern))
                .map(rows -> rows.iterator().next().getLong(0));
        } else if (hasCompany) {
            return db.preparedQuery("SELECT COUNT(*) FROM managers WHERE company ILIKE $1 AND approval_status IN ('approved','ghost')")
                .execute(Tuple.of(companyPattern))
                .map(rows -> rows.iterator().next().getLong(0));
        } else {
            return db.query("SELECT COUNT(*) FROM managers WHERE approval_status IN ('approved','ghost')")
                .execute()
                .map(rows -> rows.iterator().next().getLong(0));
        }
    }

    public Future<Long> countApproved() {
        return db.query("SELECT COUNT(*) FROM managers WHERE approval_status IN ('approved','ghost')")
            .execute()
            .map(rows -> rows.iterator().next().getLong(0));
    }

    public Future<RowSet<Row>> findAllCompanies() {
        return db.query("SELECT DISTINCT company FROM managers WHERE approval_status IN ('approved','ghost') ORDER BY company LIMIT 100")
            .execute();
    }

    public Future<RowSet<Row>> findCompaniesByQuery(String query) {
        // Match the query as a prefix of the company name ($1) or of any later word
        // within it ($2) — so "face" → "Facebook", "morgan" → "JP Morgan", but never
        // "Interface". Starts-with matches are ranked ahead of mid-name word matches.
        String prefix    = query + "%";
        String wordStart = "% " + query + "%";
        return db.preparedQuery("""
                SELECT company,
                       MIN(company_logo_url) AS company_logo_url,
                       bool_or(company ILIKE $1) AS starts_with
                FROM managers
                WHERE approval_status IN ('approved','ghost')
                  AND (company ILIKE $1 OR company ILIKE $2)
                GROUP BY company
                ORDER BY starts_with DESC, company
                LIMIT 6
                """)
            .execute(Tuple.of(prefix, wordStart));
    }

    public Future<RowSet<Row>> findSimilar(String nameLike, String companyLike) {
        return db.preparedQuery("""
                SELECT id, name, company, title, overall_rating, company_logo_url, approval_status
                FROM managers
                WHERE approval_status IN ('approved', 'ghost')
                  AND name ILIKE $1
                ORDER BY
                  CASE WHEN company ILIKE $2 THEN 0 ELSE 1 END,
                  name
                LIMIT 5
                """)
            .execute(Tuple.of(nameLike, companyLike));
    }

    public Future<RowSet<Row>> findPendingByUser(UUID userId) {
        return db.preparedQuery("""
                SELECT id, name, company, title, image, overall_rating, reviews_count,
                       bio, status, approval_status, linkedin_url, company_logo_url, country, created_at
                FROM managers
                WHERE submitted_by = $1 AND approval_status IN ('pending_approval', 'rejected')
                  AND search_created_by_user_id IS NULL
                ORDER BY created_at DESC LIMIT 200
                """)
            .execute(Tuple.of(userId));
    }

    public Future<RowSet<Row>> findGhostForAdmin(int limit, int offset) {
        return db.preparedQuery("""
                SELECT m.id, m.name, m.company, m.title, m.image, m.overall_rating,
                       m.reviews_count, m.created_at, m.company_logo_url
                FROM managers m
                WHERE m.approval_status = 'ghost'
                ORDER BY m.created_at DESC
                LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset));
    }

    public Future<Optional<Row>> approveGhost(long managerId) {
        return db.preparedQuery("""
                UPDATE managers SET approval_status = 'approved', updated_at = now()
                WHERE id = $1 AND approval_status = 'ghost'
                RETURNING id, name, company, company_id
                """)
            .execute(Tuple.of(managerId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    public Future<RowSet<Row>> findPendingForAdmin(int limit, int offset) {
        return db.preparedQuery("""
                SELECT m.id, m.name, m.company, m.title, m.image, m.created_at,
                       u.username AS submitted_by_username,
                       (m.search_created_by_user_id IS NOT NULL OR m.submitted_by IS NULL) AS is_auto_created
                FROM managers m
                LEFT JOIN users u ON u.id = m.submitted_by
                WHERE m.approval_status = 'pending_approval'
                ORDER BY m.created_at ASC
                LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset));
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    public Future<Row> create(String name, String company, String title, String image,
                               String bio, String status, String country, String linkedinUrl,
                               String logoUrl, UUID submittedBy) {
        return db.preparedQuery("""
                INSERT INTO managers
                (name, company, title, image, bio, status, approval_status, country, linkedin_url,
                 company_logo_url, overall_rating, reviews_count, category_averages, created_at, submitted_by)
                VALUES ($1,$2,$3,$4,$5,$6,'pending_approval',$7,$8,$9,0,0,'{}'::jsonb,now(),$10)
                RETURNING *
                """)
            .execute(Tuple.of(name, company, title, image, bio, status, country, linkedinUrl, logoUrl, submittedBy))
            .map(rows -> rows.iterator().next());
    }

    public Future<Optional<Row>> update(long id, String newCompany, String newTitle,
                                         String newImage, String newBio, String newStatus,
                                         String newCountry, String newLinkedinUrl, String newLogoUrl,
                                         Long newCompanyId) {
        StringBuilder sql = new StringBuilder("UPDATE managers SET updated_at = now()");
        List<Object> params = new ArrayList<>();
        int idx = 1;
        if (newCompany     != null) { sql.append(", company = $").append(idx++);           params.add(newCompany); }
        if (newTitle       != null) { sql.append(", title = $").append(idx++);             params.add(newTitle); }
        if (newImage       != null) { sql.append(", image = $").append(idx++);             params.add(newImage); }
        if (newBio         != null) { sql.append(", bio = $").append(idx++);               params.add(newBio); }
        if (newStatus      != null) { sql.append(", status = $").append(idx++);            params.add(newStatus); }
        if (newCountry     != null) { sql.append(", country = $").append(idx++);           params.add(newCountry); }
        if (newLinkedinUrl != null) { sql.append(", linkedin_url = $").append(idx++);      params.add(newLinkedinUrl); }
        if (newLogoUrl     != null) { sql.append(", company_logo_url = $").append(idx++);  params.add(newLogoUrl); }
        if (newCompanyId   != null) { sql.append(", company_id = $").append(idx++);        params.add(newCompanyId); }
        sql.append(" WHERE id = $").append(idx).append(" RETURNING *");
        params.add(id);
        return db.preparedQuery(sql.toString())
            .execute(Tuple.from(params))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    public Future<Optional<Row>> approve(long managerId) {
        return db.preparedQuery("""
                UPDATE managers SET approval_status = 'approved', updated_at = now()
                WHERE id = $1 AND approval_status = 'pending_approval'
                RETURNING id, name, company, company_logo_url, submitted_by, company_id
                """)
            .execute(Tuple.of(managerId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    public Future<Boolean> updateLogoUrl(long managerId, String logoUrl) {
        return db.preparedQuery("UPDATE managers SET company_logo_url = $1 WHERE id = $2")
            .execute(Tuple.of(logoUrl, managerId))
            .map(rows -> rows.rowCount() > 0);
    }

    public Future<Optional<Row>> reject(long managerId) {
        return db.preparedQuery("""
                UPDATE managers SET approval_status = 'rejected', updated_at = now()
                WHERE id = $1 AND approval_status = 'pending_approval'
                RETURNING id, name, company, submitted_by
                """)
            .execute(Tuple.of(managerId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    public Future<Void> delete(long managerId) {
        return db.preparedQuery("DELETE FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .mapEmpty();
    }

    public Future<Integer> countExistingById(Long[] ids) {
        return db.preparedQuery("SELECT id FROM managers WHERE id = ANY($1::bigint[])")
            .execute(Tuple.of(ids))
            .map(RowSet::rowCount);
    }

    // ── Career history ────────────────────────────────────────────────────────

    public Future<RowSet<Row>> getCareerHistory(long managerId) {
        return db.preparedQuery("""
                SELECT company, title, start_date, end_date
                FROM career_history WHERE manager_id = $1 ORDER BY start_date DESC
                """)
            .execute(Tuple.of(managerId));
    }

    public Future<Void> insertCareerEntry(long managerId, String company, String title,
                                           OffsetDateTime startDate, OffsetDateTime endDate, Long companyId) {
        return db.preparedQuery("""
                INSERT INTO career_history(manager_id, company, title, start_date, end_date, company_id)
                VALUES ($1, $2, $3, $4, $5, $6)
                """)
            .execute(Tuple.of(managerId, company, title, startDate, endDate, companyId))
            .mapEmpty();
    }

    public Future<Integer> closeOpenCareerEntry(long managerId, OffsetDateTime endDate) {
        return db.preparedQuery("UPDATE career_history SET end_date = $1 WHERE manager_id = $2 AND end_date IS NULL")
            .execute(Tuple.of(endDate, managerId))
            .map(RowSet::rowCount);
    }

    /** Updates the open (current) career entry in place — used for typo/spelling corrections. */
    public Future<Void> updateOpenCareerEntry(long managerId, String company, String title) {
        return db.preparedQuery("""
                UPDATE career_history SET company = $2, title = $3
                WHERE manager_id = $1 AND end_date IS NULL
                """)
            .execute(Tuple.of(managerId, company, title))
            .mapEmpty();
    }

    // ── Stats recalculation ───────────────────────────────────────────────────

    /** Fire-and-forget: recalculates and persists rating stats without blocking. */
    public void recalculateInBackground(long managerId) {
        recalculate(managerId).onFailure(err ->
            System.err.println("Background recalculate failed for manager " + managerId + ": " + err.getMessage())
        );
    }

    public Future<Void> recalculate(long managerId) {
        String recalcSql = """
            SELECT
                COUNT(*)::INTEGER AS reviews_count,
                ROUND(AVG(overall_rating)::NUMERIC, 1) AS overall_rating,
                ROUND(AVG(communication_style)::NUMERIC, 1) AS communication_style,
                ROUND(AVG(perceived_approachability)::NUMERIC, 1) AS perceived_approachability,
                ROUND(AVG(perceived_clarity_of_expectations)::NUMERIC, 1) AS perceived_clarity_of_expectations,
                ROUND(AVG(feedback_style)::NUMERIC, 1) AS feedback_style,
                ROUND(AVG(perceived_supportiveness)::NUMERIC, 1) AS perceived_supportiveness,
                ROUND(AVG(decision_making_style)::NUMERIC, 1) AS decision_making_style,
                ROUND(AVG(organization_and_planning_style)::NUMERIC, 1) AS organization_and_planning_style,
                ROUND(AVG(delegation_style)::NUMERIC, 1) AS delegation_style,
                ROUND(AVG(perceived_professional_demeanor)::NUMERIC, 1) AS perceived_professional_demeanor,
                ROUND(AVG(overall_working_experience)::NUMERIC, 1) AS overall_working_experience
            FROM reviews WHERE manager_id = $1 AND deleted_at IS NULL
            """;

        return db.preparedQuery(recalcSql)
            .execute(Tuple.of(managerId))
            .compose(rows -> {
                Row stats = rows.iterator().next();
                int reviewsCount = stats.getInteger("reviews_count");
                BigDecimal overallRating = reviewsCount > 0 ? stats.getBigDecimal("overall_rating") : null;
                io.vertx.core.json.JsonObject categoryAvg = null;
                if (reviewsCount > 0) {
                    categoryAvg = new io.vertx.core.json.JsonObject()
                        .put("Communication Style",               nullSafe(stats.getBigDecimal("communication_style")))
                        .put("Perceived Approachability",         nullSafe(stats.getBigDecimal("perceived_approachability")))
                        .put("Perceived Clarity of Expectations", nullSafe(stats.getBigDecimal("perceived_clarity_of_expectations")))
                        .put("Feedback Style",                    nullSafe(stats.getBigDecimal("feedback_style")))
                        .put("Perceived Supportiveness",          nullSafe(stats.getBigDecimal("perceived_supportiveness")))
                        .put("Decision Making Style",             nullSafe(stats.getBigDecimal("decision_making_style")))
                        .put("Organization and Planning Style",   nullSafe(stats.getBigDecimal("organization_and_planning_style")))
                        .put("Delegation Style",                  nullSafe(stats.getBigDecimal("delegation_style")))
                        .put("Perceived Professional Demeanor",   nullSafe(stats.getBigDecimal("perceived_professional_demeanor")))
                        .put("Overall Working Experience",        nullSafe(stats.getBigDecimal("overall_working_experience")));
                }
                return db.preparedQuery("""
                        UPDATE managers SET overall_rating = $1, reviews_count = $2,
                               category_averages = $3, updated_at = now()
                        WHERE id = $4
                        """)
                    .execute(Tuple.of(overallRating, reviewsCount, categoryAvg, managerId))
                    .mapEmpty();
            });
    }

    private static double nullSafe(BigDecimal v) {
        return v != null ? v.doubleValue() : 0.0;
    }

    // ── Long daily-count check ────────────────────────────────────────────────

    public Future<Long> countSubmittedTodayByUser(UUID userId) {
        return db.preparedQuery("SELECT COUNT(*) FROM managers WHERE submitted_by = $1 AND created_at >= current_date")
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().next().getLong(0));
    }

    // ── Merge helpers ─────────────────────────────────────────────────────────

    public Future<Void> mergeInlineRecalculate(long keepId) {
        String sql = """
            UPDATE managers SET
                reviews_count     = sub.cnt,
                overall_rating    = sub.overall_rating,
                category_averages = sub.cats::jsonb
            FROM (
                SELECT
                    COUNT(*)::INTEGER AS cnt,
                    ROUND(AVG(overall_rating)::NUMERIC, 1) AS overall_rating,
                    json_build_object(
                        'Communication Style',               ROUND(AVG(communication_style)::NUMERIC,1),
                        'Perceived Approachability',         ROUND(AVG(perceived_approachability)::NUMERIC,1),
                        'Perceived Clarity of Expectations', ROUND(AVG(perceived_clarity_of_expectations)::NUMERIC,1),
                        'Feedback Style',                    ROUND(AVG(feedback_style)::NUMERIC,1),
                        'Perceived Supportiveness',          ROUND(AVG(perceived_supportiveness)::NUMERIC,1),
                        'Decision Making Style',             ROUND(AVG(decision_making_style)::NUMERIC,1),
                        'Organization and Planning Style',   ROUND(AVG(organization_and_planning_style)::NUMERIC,1),
                        'Delegation Style',                  ROUND(AVG(delegation_style)::NUMERIC,1),
                        'Perceived Professional Demeanor',   ROUND(AVG(perceived_professional_demeanor)::NUMERIC,1),
                        'Overall Working Experience',        ROUND(AVG(overall_working_experience)::NUMERIC,1)
                    )::text AS cats
                FROM reviews WHERE manager_id = $1 AND deleted_at IS NULL
            ) sub
            WHERE managers.id = $1
            """;
        return db.preparedQuery(sql).execute(Tuple.of(keepId)).mapEmpty();
    }

    // ── Admin direct edit (cascading) ─────────────────────────────────────────

    /**
     * Atomically updates name/title/company/linkedinUrl on the manager and cascades
     * title/company corrections to reviews and career_history where old values match.
     * Pass null for any field that should not change.
     */
    public Future<Optional<io.vertx.core.json.JsonObject>> adminEdit(long managerId, String newName, String newTitle,
                                                                      String newCompany, String newLinkedinUrl,
                                                                      Long newCompanyId) {
        return ((Pool) db).withTransaction(conn ->
            conn.preparedQuery("SELECT name, title, company FROM managers WHERE id = $1")
                .execute(Tuple.of(managerId))
                .compose(rows -> {
                    if (!rows.iterator().hasNext())
                        return Future.succeededFuture(Optional.empty());
                    Row cur       = rows.iterator().next();
                    String oldName    = cur.getString("name");
                    String oldTitle   = cur.getString("title");
                    String oldCompany = cur.getString("company");
                    String effName    = newName    != null ? newName    : oldName;
                    String effTitle   = newTitle   != null ? newTitle   : oldTitle;
                    String effCompany = newCompany != null ? newCompany : oldCompany;

                    List<Object> params = new ArrayList<>();
                    params.add(effName); params.add(effTitle); params.add(effCompany);
                    int idx = 4;
                    StringBuilder sql = new StringBuilder(
                        "UPDATE managers SET updated_at = now(), name = $1, title = $2, company = $3");
                    if (newLinkedinUrl != null) {
                        sql.append(", linkedin_url = $").append(idx++);
                        params.add(newLinkedinUrl);
                    }
                    if (newCompanyId != null) {
                        sql.append(", company_id = $").append(idx++);
                        params.add(newCompanyId);
                    }
                    params.add(managerId);
                    sql.append(" WHERE id = $").append(idx).append(" RETURNING id");

                    boolean titleChanged   = newTitle   != null && !newTitle.equals(oldTitle);
                    boolean companyChanged = newCompany != null && !newCompany.equals(oldCompany);

                    return conn.preparedQuery(sql.toString()).execute(Tuple.from(params))
                        .compose(v -> {
                            Future<Void> cascade = Future.succeededFuture();
                            if (titleChanged) {
                                cascade = cascade.compose(x ->
                                    conn.preparedQuery(
                                        "UPDATE reviews SET manager_title = $1 WHERE manager_id = $2 AND manager_title = $3")
                                        .execute(Tuple.of(effTitle, managerId, oldTitle)).mapEmpty());
                            }
                            if (companyChanged) {
                                cascade = cascade.compose(x ->
                                    conn.preparedQuery(
                                        "UPDATE reviews SET manager_company = $1 WHERE manager_id = $2 AND manager_company = $3")
                                        .execute(Tuple.of(effCompany, managerId, oldCompany)).mapEmpty());
                            }
                            if (titleChanged || companyChanged) {
                                cascade = cascade.compose(x ->
                                    conn.preparedQuery(
                                        "UPDATE career_history SET title = $1, company = $2 WHERE manager_id = $3 AND title = $4 AND company = $5")
                                        .execute(Tuple.of(effTitle, effCompany, managerId, oldTitle, oldCompany)).mapEmpty());
                            }
                            return cascade.map(x -> Optional.of(new io.vertx.core.json.JsonObject()
                                .put("success", true)
                                .put("name", effName)
                                .put("title", effTitle)
                                .put("company", effCompany)));
                        });
                })
        );
    }

    // ── Find-or-create ────────────────────────────────────────────────────────

    public Future<RowSet<Row>> findByNameAndCompany(String fullName, String company) {
        return db.preparedQuery(SELECT_BODY + """
                WHERE m.name ILIKE $1
                  AND m.company ILIKE $2
                  AND m.approval_status IN ('approved', 'ghost')
                GROUP BY m.id, c.slug
                ORDER BY m.reviews_count DESC, m.id ASC
                LIMIT 5
                """)
            .execute(Tuple.of(fullName, "%" + company.trim() + "%"));
    }

    public Future<Row> createSearchPending(String name, String company, String title,
                                           String country, String state, String city,
                                           String logoUrl, Long companyId, UUID searchCreatedByUserId) {
        return generateUniqueSlug(name, company).compose(slug ->
            db.preparedQuery("""
                    INSERT INTO managers
                    (name, company, title, status, approval_status, country, state, city,
                     overall_rating, reviews_count, category_averages,
                     company_logo_url, company_id, search_created_by_user_id, submitted_by,
                     slug, created_at, updated_at)
                    VALUES ($1,$2,$3,'active','pending_approval',$4,$5,$6,
                            0,0,'{}'::jsonb,
                            $7,$8,$9,$9,
                            $10,now(),now())
                    RETURNING *
                    """)
                .execute(Tuple.of(name, company.trim(), title.trim(),
                                  country != null ? country.trim() : null,
                                  state, city, logoUrl, companyId, searchCreatedByUserId, slug))
                .map(rows -> rows.iterator().next()));
    }

    public Future<Row> createAutoApproved(String name, String company, String title,
                                          String country, String state, String city,
                                          UUID submittedBy, String logoUrl, Long companyId) {
        return generateUniqueSlug(name, company).compose(slug ->
            db.preparedQuery("""
                    INSERT INTO managers
                    (name, company, title, status, approval_status, country, state, city,
                     overall_rating, reviews_count, category_averages, submitted_by,
                     company_logo_url, company_id, slug, created_at, updated_at)
                    VALUES ($1,$2,$3,'active','ghost',$4,$5,$6,
                            0,0,'{}'::jsonb,$7,
                            $8,$9,$10,now(),now())
                    RETURNING *
                    """)
                .execute(Tuple.of(name, company.trim(), title.trim(), country.trim(), state, city, submittedBy, logoUrl, companyId, slug))
                .map(rows -> rows.iterator().next()));
    }

    public Future<Row> createGhost(String name, String company, String title,
                                   String country, String state, String city, String logoUrl, Long companyId) {
        return generateUniqueSlug(name, company).compose(slug ->
            db.preparedQuery("""
                    INSERT INTO managers
                    (name, company, title, status, approval_status, country, state, city,
                     overall_rating, reviews_count, category_averages,
                     company_logo_url, company_id, slug, created_at, updated_at)
                    VALUES ($1,$2,$3,'active','ghost',$4,$5,$6,
                            0,0,'{}'::jsonb,
                            $7,$8,$9,now(),now())
                    RETURNING *
                    """)
                .execute(Tuple.of(name, company.trim(), title.trim(), country.trim(), state, city, logoUrl, companyId, slug))
                .map(rows -> rows.iterator().next()));
    }

    /** Creates a pending_approval manager record (no auth required) for drop-off draft capture. */
    public Future<Row> createPending(String name, String company, String title,
                                      String status, String country, String state,
                                      String logoUrl, Long companyId) {
        return generateUniqueSlug(name, company).compose(slug ->
            db.preparedQuery("""
                    INSERT INTO managers
                    (name, company, title, status, approval_status, country, state,
                     overall_rating, reviews_count, category_averages,
                     company_logo_url, company_id, slug, created_at, updated_at)
                    VALUES ($1,$2,$3,$4,'pending_approval',$5,$6,
                            0,0,'{}'::jsonb,
                            $7,$8,$9,now(),now())
                    RETURNING *
                    """)
                .execute(Tuple.of(name, company.trim(), title.trim(), status, country.trim(), state, logoUrl, companyId, slug))
                .map(rows -> rows.iterator().next()));
    }

    // ── Find-or-attach helpers ────────────────────────────────────────────────

    /** Returns all non-rejected managers for an exact company name (case-insensitive). */
    public Future<RowSet<Row>> findByCompanyExact(String company) {
        return db.preparedQuery("""
                SELECT id, name, approval_status
                FROM managers
                WHERE LOWER(TRIM(company)) = LOWER(TRIM($1))
                  AND approval_status NOT IN ('rejected')
                ORDER BY reviews_count DESC NULLS LAST
                LIMIT 50
                """)
            .execute(Tuple.of(company));
    }

    /** Updates a ghost manager with richer data from the add-manager form. */
    public Future<Optional<Row>> updateForAttach(long id, String name, String title,
                                                  String status, String country,
                                                  String linkedinUrl, String logoUrl,
                                                  UUID submittedBy) {
        return db.preparedQuery("""
                UPDATE managers
                SET name = $1, title = $2, status = $3, country = $4,
                    linkedin_url = $5, company_logo_url = $6,
                    submitted_by = COALESCE(submitted_by, $7),
                    updated_at = now()
                WHERE id = $8
                RETURNING *
                """)
            .execute(Tuple.of(name, title, status, country, linkedinUrl, logoUrl, submittedBy, id))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** Simple flat SELECT for returning manager data to the handler after an attach. */
    public Future<Optional<Row>> findByIdFlat(long id) {
        return db.preparedQuery("SELECT * FROM managers WHERE id = $1")
            .execute(Tuple.of(id))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    public Future<Boolean> hasCareerHistory(long managerId) {
        return db.preparedQuery("SELECT COUNT(*) FROM career_history WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rows -> rows.iterator().next().getLong(0) > 0);
    }

    /** Deletes one fake (seed_*) manager — fewest reviews first — and all its cascaded data. */
    public void deleteFakeManagerInBackground() {
        db.query("""
                SELECT id FROM managers
                WHERE external_id LIKE 'seed_%'
                  AND approval_status IN ('approved', 'ghost')
                ORDER BY COALESCE(reviews_count, 0) ASC, created_at ASC
                LIMIT 1
                """)
            .execute()
            .compose(rows -> {
                if (!rows.iterator().hasNext()) return Future.succeededFuture();
                long id = rows.iterator().next().getLong("id");
                return db.preparedQuery("DELETE FROM managers WHERE id = $1")
                    .execute(Tuple.of(id))
                    .mapEmpty();
            })
            .onFailure(err -> System.err.println("Fake manager cleanup failed: " + err.getMessage()));
    }

    /** Promotes a ghost to pending_approval once a real user has attached a rated review. */
    public Future<Optional<Row>> promoteGhostToPending(long id) {
        return db.preparedQuery("""
                UPDATE managers SET approval_status = 'pending_approval', updated_at = now()
                WHERE id = $1 AND approval_status = 'ghost'
                RETURNING *
                """)
            .execute(Tuple.of(id))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    // ── Slug helpers ──────────────────────────────────────────────────────────

    /** Converts a name to a URL-safe base slug: lowercase, hyphens, ASCII only. */
    private static String toBaseSlug(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9\\s-]", "")
            .trim()
            .replaceAll("\\s+", "-")
            .replaceAll("-{2,}", "-");
    }

    /**
     * Generates a globally unique manager slug.
     * Strategy: name → name-company → name-company-2 → name-company-3 …
     * Numbers only appear when two managers share both name and company.
     */
    public Future<String> generateUniqueSlug(String name, String company) {
        String raw = toBaseSlug(name.trim());
        final String base = raw.isEmpty() ? "manager" : raw;
        return slugAvailable(base).compose(free -> {
            if (free) return Future.succeededFuture(base);
            String companyPart = toBaseSlug(company == null ? "" : company.trim());
            String withCompany = companyPart.isEmpty() ? base : base + "-" + companyPart;
            return trySlug(withCompany, 2);
        });
    }

    private Future<Boolean> slugAvailable(String candidate) {
        return db.preparedQuery("SELECT 1 FROM managers WHERE slug = $1 LIMIT 1")
            .execute(Tuple.of(candidate))
            .map(rows -> !rows.iterator().hasNext());
    }

    private Future<String> trySlug(String base, int attempt) {
        String candidate = attempt == 2 ? base : base + "-" + (attempt - 1);
        return slugAvailable(candidate).compose(free ->
            free ? Future.succeededFuture(candidate) : trySlug(base, attempt + 1));
    }

    /** Looks up a manager by their globally unique slug.
     *  Joins to companies so the caller can read company_slug for redirect checks. */
    public Future<Optional<Row>> findBySlug(String slug) {
        return db.preparedQuery(GET_BY_ID_SQL.replace("WHERE m.id = $1", "WHERE m.slug = $1"))
            .execute(Tuple.of(slug))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** Records an old (company_slug, manager_slug) pair for 301 redirect lookups after company changes. */
    public Future<Void> recordUrlHistory(long managerId, String oldCompanySlug, String managerSlug) {
        return db.preparedQuery("""
                INSERT INTO manager_url_history (manager_id, company_slug, manager_slug)
                VALUES ($1, $2, $3)
                ON CONFLICT DO NOTHING
                """)
            .execute(Tuple.of(managerId, oldCompanySlug, managerSlug))
            .mapEmpty();
    }

    /** Returns the manager_id for a stale (company_slug, manager_slug) URL, or empty if not found. */
    public Future<Optional<Long>> findByOldUrl(String companySlug, String managerSlug) {
        return db.preparedQuery("""
                SELECT manager_id FROM manager_url_history
                WHERE company_slug = $1 AND manager_slug = $2
                ORDER BY created_at DESC LIMIT 1
                """)
            .execute(Tuple.of(companySlug, managerSlug))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next().getLong("manager_id"))
                : Optional.empty());
    }

    /**
     * Returns true if any approved/ghost manager is linked to this company name via their
     * current company, career history, or a review snapshot. Mirrors the lookup logic of
     * {@link CompanyRepository#findManagersByCompanyId}. Used to decide whether to
     * auto-create a ghost company row on a profile visit.
     */
    public Future<Boolean> existsManagerWithCompanyName(String name) {
        return db.preparedQuery("""
                SELECT EXISTS(
                    SELECT 1 FROM managers
                    WHERE LOWER(TRIM(company)) = LOWER(TRIM($1))
                      AND approval_status IN ('approved', 'ghost')
                      AND (external_id IS NULL OR external_id NOT LIKE 'seed_%')
                    UNION ALL
                    SELECT 1 FROM career_history ch
                    JOIN managers m ON m.id = ch.manager_id
                    WHERE LOWER(TRIM(ch.company)) = LOWER(TRIM($2))
                      AND m.approval_status IN ('approved', 'ghost')
                      AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                    UNION ALL
                    SELECT 1 FROM reviews r
                    JOIN managers m ON m.id = r.manager_id
                    WHERE LOWER(TRIM(r.manager_company)) = LOWER(TRIM($3))
                      AND m.approval_status IN ('approved', 'ghost')
                      AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
                )
                """)
            .execute(Tuple.of(name, name, name))
            .map(rows -> rows.iterator().next().getBoolean(0));
    }
}

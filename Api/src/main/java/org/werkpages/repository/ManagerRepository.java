package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlResult;
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

    /**
     * The platform example's OWN rating and count, for the surfaces that must show them.
     *
     * <p><b>Why these are separate columns and not folded into {@code overall_rating}.</b> A
     * generated example is deliberately outside every statistic: {@code recalculate} writes the
     * cache from {@link ReviewSql#genuine}, and {@code company_stats_live} aggregates that cache,
     * so letting an example reach {@code overall_rating} would put fabricated numbers into company
     * statistics, industry statistics and rankings. That exclusion is the whole point of
     * {@code reviews.origin} and must not be undone to fix a display problem.
     *
     * <p><b>But the display problem is real.</b> A newly created ghost holds exactly one example,
     * so its cached rating is correctly NULL - and the tile and the profile headline took their
     * number from that cache and drew nothing. Reported in production: "a user searched, the
     * profile was auto-created, and the tile came back with an empty space where the rating goes."
     * The row was there the whole time, which is what made it look random.
     *
     * <p>So the example's number travels beside the genuine one, flagged, and each surface decides
     * what to do with it: shown with an "Example" pill where a reader is looking at this one
     * manager, ignored wherever numbers are compared or ranked.
     *
     * <p>Scalar subqueries rather than a join, because {@code SELECT_BODY} already groups by
     * {@code m.id} and a lateral would have to be added to its GROUP BY. {@code reviews} is
     * indexed on {@code manager_id}, and {@code reviews_published} is a partial index over exactly
     * the live rows these read.
     */
    private static final String EXAMPLE_COLUMNS =
          "                (SELECT ROUND(AVG(r.overall_rating)::NUMERIC, 1) FROM reviews r\n"
        + "                  WHERE r.manager_id = m.id AND " + ReviewSql.example("r")
        + ") AS example_rating,\n"
        + "                (SELECT COUNT(*)::INTEGER FROM reviews r\n"
        + "                  WHERE r.manager_id = m.id AND " + ReviewSql.example("r")
        + ") AS example_reviews,\n";

    public static final String GET_BY_ID_SQL = """
            SELECT
                m.id, m.name, m.company, m.title, m.image, m.overall_rating,
                m.reviews_count, m.bio, m.status, m.approval_status,
                m.category_averages, m.linkedin_url, m.company_logo_url,
                -- Where the manager works. country was selected and state/city were not, so an
                -- edit could save them and nothing could ever show them back.
                m.country, m.state, m.city,
                -- How that location was arrived at. Never shown; it decides whether the
                -- sub-country detail may be shown at all. See V72 and managerLocation().
                m.location_source,
                /*
                  The exact place, when one was chosen.

                  country/state/city is the coarse answer; a manager can also be pinned to a real
                  address through company_location_id. Nothing selected it, so an exact location
                  could be stored and never shown - the profile fell back to "Toronto" for a
                  manager somebody had placed at a specific building.
                */
                m.company_location_id,
                loc.display_name AS location_name,
                loc.street       AS location_street,
                loc.postal_code  AS location_postal_code,
                loc.city         AS location_city,
                loc.state        AS location_state,
                m.created_at, m.submitted_by, m.company_id, m.slug,
                c.slug AS company_slug,
                -- The company's RESOLVED identity, for the logo chain. Never a guessed domain.
                c.domain AS company_domain,
                c.brandfetch_icon_url AS company_brandfetch_icon_url,
                c.industry AS industry,
                """ + EXAMPLE_COLUMNS + """
                COALESCE(ch.career_history, '[]') AS career_history,
                COALESCE(r.reviews, '[]') AS reviews
            FROM managers m
            LEFT JOIN companies c ON c.id = m.company_id
            LEFT JOIN company_locations loc ON loc.id = m.company_location_id
            LEFT JOIN (
                SELECT ch2.manager_id,
                    json_agg(jsonb_build_object(
                        'id', ch2.id, 'company', ch2.company, 'title', ch2.title,
                        'startDate', ch2.start_date, 'endDate', ch2.end_date,
                        /*
                          WHETHER THE PERIOD IS KNOWN AT ALL, and what the record says about the
                          employment still running. Both were absent, and their absence is a
                          defect the merge made visible.

                          With only the two dates, a reader cannot tell "this employment has no
                          stated period" from "this employment is still running", because both
                          arrive as a null end date. So the trajectory called every undated row
                          the person's CURRENT job - and a merge moves the absorbed profile's
                          employments across, which are undated exactly when nobody rated them.
                          Every merged-in job therefore drew as the most recent one.

                          employment_status is the resolution of that question (V109 onward) and
                          is believed where it states an answer; period_known says whether there
                          is a period to reason about. Additive: no existing field changes, so a
                          client that does not read them behaves exactly as before.
                        */
                        'periodKnown', ch2.period_known,
                        'employmentStatus', ch2.employment_status,
                        /*
                          The employer's identity, so the tile can draw its logo.

                          It carried the company NAME and nothing else, so the career trajectory
                          had no domain to build a logo URL from and fell through to a letter -
                          while the profile header above it, which does get the identity, showed
                          the real logo. The same company, two tiles, two different answers.

                          Guessing a domain from the name is not the fix and was removed on
                          purpose: it resolved "Lime" to lime.com and put a stranger's logo on a
                          manager. The identity is looked up, not inferred.
                        */
                        'companyId', ch2.company_id,
                        'companyDomain', cc.domain,
                        'companyBrandfetchIconUrl', cc.brandfetch_icon_url,
                        'companyLogoUrl', cc.logo_url
                    ) ORDER BY ch2.start_date DESC NULLS LAST) AS career_history
                FROM career_history ch2
                LEFT JOIN companies cc ON cc.id = ch2.company_id
                GROUP BY ch2.manager_id
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
                        /* No 'text': free-form review text was removed on 2026-11-18. A rating
                           is scores, nothing writes the column and nothing may serve it. */
                        'verified', verified, 'helpfulCount', helpful_count,
                        'createdAt', created_at, 'workedFrom', worked_from, 'workedUntil', worked_until,
                        /*
                          Whether this one is public yet, so the page can say so and put it first.
                          Without it the client had no way to tell a published review from one
                          waiting on a moderator, and sorted them as though they were the same.
                        */
                        'published', (disposition = 'live'),
                        /*
                          WHETHER THIS ONE IS A PLATFORM EXAMPLE, which the client needs and was
                          never sent.

                          `isExample` was produced in exactly one place, ManagerService's row
                          mapper, and the profile does not go through it: the reviews array is
                          built here, by this json_agg, which projected no provenance at all. So
                          every example arrived at the browser indistinguishable from a genuine
                          rating, and the label the client renders for one could never appear on a
                          profile. The feature was complete on both sides and disconnected in the
                          middle.

                          THE WEIGHT PAIR, NOT `origin`, and the choice is about deployability
                          rather than taste. `origin` is the durable classification and the row
                          mapper rightly prefers it, but that column ARRIVES WITH V106: this is raw
                          SQL in the profile query, so naming a column that does not exist yet does
                          not fail closed to false the way the mapper does, it fails the entire
                          manager profile endpoint. `weight` and `user_id` predate V106, are never
                          written after insert anywhere in either product, and select exactly the
                          same rows, because V106's own backfill classified precisely the set
                          `user_id IS NULL AND weight = TRUE`.
                        */
                        'isExample', (weight AND user_id IS NULL)
                    /*
                      UNPUBLISHED FIRST, then newest to oldest.

                      The one somebody is waiting on is the one they came back to look at. After
                      that, recency. FALSE sorts before TRUE in Postgres, so ordering on the
                      published flag ascending puts the held ones on top without a CASE.
                    */
                    ) ORDER BY (disposition = 'live'), created_at DESC) AS reviews
                FROM reviews
                /*
                  LIVE FOR EVERYONE, PLUS THE VIEWER'S OWN.

                  This filtered on deleted_at alone, so every held and rejected review on a profile
                  - including other people's, with their text - was served to anyone who asked.
                  Every other surface in this codebase goes through ReviewSql.live; this one was
                  the exception, and nothing in the client was hiding them either.

                  A contributor still sees their OWN unpublished review, which is the thing they
                  came back to check on. $2 is their user id, null when nobody is signed in.
                */
                 WHERE deleted_at IS NULL
                   AND (disposition = 'live'
                        OR ($2::uuid IS NOT NULL AND user_id = $2::uuid))
                 GROUP BY manager_id
            ) r ON r.manager_id = m.id
            WHERE m.id = $1
            LIMIT 1
            """;

    private static final String SELECT_BODY = """
            SELECT
                m.id, m.name, m.company, m.title, m.image, m.overall_rating,
                m.reviews_count, m.bio, m.status, m.approval_status,
                m.category_averages, m.linkedin_url, m.company_logo_url,
                m.country, m.state, m.city, m.location_source, m.created_at,
                m.submitted_by, m.external_id, m.company_id, m.slug,
                c.slug AS company_slug,
                -- The company's RESOLVED identity, for the logo chain. Never a guessed domain.
                c.domain AS company_domain,
                c.brandfetch_icon_url AS company_brandfetch_icon_url,
                c.industry AS industry,
                """ + EXAMPLE_COLUMNS + """
                COALESCE(
                    json_agg(json_build_object(
                        'id', ch.id, 'company', ch.company, 'title', ch.title,
                        'startDate', ch.start_date, 'endDate', ch.end_date
                    ) ORDER BY ch.start_date DESC NULLS LAST)
                    FILTER (WHERE ch.id IS NOT NULL), '[]'
                ) AS career_history
            FROM managers m
            LEFT JOIN companies c ON c.id = m.company_id
            LEFT JOIN company_locations loc ON loc.id = m.company_location_id
            LEFT JOIN career_history ch ON ch.manager_id = m.id
            """;

    /**
     * A Pool rather than a SqlClient, because three of the career writes below must be
     * transactional with the status resolution that follows them.
     *
     * <p>{@code Database.getPool()} exists for exactly this: "the few callers that need a
     * connection rather than a query". An accepted employment committed with an unresolved status
     * is the drift the reconciliation query hunts for, and creating it one statement at a time
     * would be manufacturing the thing being guarded against.
     */
    private final Pool db;

    public ManagerRepository(Pool db) {
        this.db = db;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public Future<Optional<Row>> findById(long id) {
        return findById(id, null);
    }

    /**
     * The profile, with the reviews this VIEWER may see.
     *
     * @param viewerId the signed-in user, or null. Their own unpublished reviews come back; nobody
     *                 else's ever do.
     */
    public Future<Optional<Row>> findById(long id, UUID viewerId) {
        /*
          BUILT EXPLICITLY, because Tuple.of(id, viewerId) did not survive a null viewer.

          GET_BY_ID_SQL takes two parameters; with no signed-in viewer the driver was handed a
          tuple of ONE and refused the query:

              The number of parameters to execute should be consistent with the expected number
              of parameters = [2] but the actual number is [1]

          Which is every anonymous visit to a manager profile, by id or by slug. Caught by
          SlugIntegrationTest and CareerHistoryAdminIntegrationTest, which were red.

          addUUID(null) keeps the arity at two and tells the driver the column's type, so there is
          no overload resolution to reason about and no untyped null to infer.
        */
        return db.preparedQuery(GET_BY_ID_SQL)
            .execute(Tuple.tuple().addLong(id).addUUID(viewerId))
            .map(rows -> {
                Iterator<Row> it = rows.iterator();
                return it.hasNext() ? Optional.of(it.next()) : Optional.empty();
            });
    }

    /**
     * Resolves a manager id through any merges it has been through.
     *
     * <p>Merging keeps the merged-away row and marks it {@code rejected}, pointing at the survivor
     * via {@code merged_into} (V62). Nothing followed that pointer, so merging A into B made A's
     * profile 404 - to an admin who had just merged two records, the manager had simply vanished.
     * A merge is a statement that two rows are the same person, so the old URL has to lead to them.
     *
     * <p>Walks the chain, because a row merged twice points at a row that was itself merged. The
     * depth cap is there so a cycle - which the schema does not forbid - cannot spin forever.
     */
    private static final String RESOLVE_MERGE_SQL = """
            WITH RECURSIVE chain AS (
                SELECT id, merged_into, 0 AS depth FROM managers WHERE %s
                UNION ALL
                SELECT m.id, m.merged_into, c.depth + 1
                FROM managers m JOIN chain c ON m.id = c.merged_into
                WHERE c.depth < 10
            )
            SELECT id FROM chain ORDER BY depth DESC LIMIT 1
            """;

    /** {@link #findById}, but an id that was merged away resolves to the manager it was merged into. */
    public Future<Optional<Row>> findByIdFollowingMerges(long id) {
        return findByIdFollowingMerges(id, null);
    }

    /** {@link #findByIdFollowingMerges(long)}, showing this viewer their own unpublished reviews. */
    public Future<Optional<Row>> findByIdFollowingMerges(long id, UUID viewerId) {
        return db.preparedQuery(RESOLVE_MERGE_SQL.formatted("id = $1"))
            .execute(Tuple.of(id))
            .compose(rows -> rows.iterator().hasNext()
                ? findById(rows.iterator().next().getLong("id"), viewerId)
                : Future.succeededFuture(Optional.empty()));
    }

    /** {@link #findBySlug}, but a slug that was merged away resolves to the surviving manager. */
    public Future<Optional<Row>> findBySlugFollowingMerges(String slug) {
        return findBySlugFollowingMerges(slug, null);
    }

    /**
     * {@link #findBySlugFollowingMerges(String)}, falling back to a URL this manager has LEFT.
     *
     * <p><b>This is what makes moving a slug safe, and until now nothing did it.</b>
     * {@code manager_url_history} has been written on every slug move for a long time - at
     * approval, by the merge's {@code reclaimBaseSlug}, and now when an admin corrects a
     * misspelled name - and {@link #findByOldUrl} exists to read it. But findByOldUrl had NO
     * CALLERS in either backend, so the table was a write-only log and every retired URL answered
     * 404. Two comments, including one of mine, claimed otherwise.
     *
     * <p>Keyed on the manager slug alone, because {@code /manager/:slug} carries no company slug -
     * findByOldUrl needs both, which is why it only ever suited the nested company route. The most
     * recent record wins: a slug can be left, taken by somebody else, and left again.
     *
     * <p>It resolves to the manager's CURRENT slug and re-enters the same query, so the row shape
     * is identical to a direct hit and a profile that has since been merged away still follows the
     * merge to its survivor.
     */
    public Future<Optional<Row>> findBySlugFollowingMergesOrHistory(String slug, UUID viewerId) {
        return findBySlugFollowingMerges(slug, viewerId).compose(found -> found.isPresent()
            ? Future.succeededFuture(found)
            : db.preparedQuery("""
                    SELECT m.slug
                      FROM manager_url_history h
                      JOIN managers m ON m.id = h.manager_id
                     WHERE h.manager_slug = $1
                     ORDER BY h.created_at DESC
                     LIMIT 1
                    """)
                .execute(Tuple.of(slug))
                .compose(rows -> {
                    if (!rows.iterator().hasNext()) return Future.succeededFuture(Optional.empty());
                    String current = rows.iterator().next().getString("slug");
                    // Guard against a history row that points back at the slug being asked for,
                    // which would recurse for ever.
                    if (current == null || current.equals(slug)) {
                        return Future.succeededFuture(Optional.empty());
                    }
                    return findBySlugFollowingMerges(current, viewerId);
                }));
    }

    /** {@link #findBySlugFollowingMerges(String)}, with this viewer's own unpublished reviews. */
    public Future<Optional<Row>> findBySlugFollowingMerges(String slug, UUID viewerId) {
        return db.preparedQuery(RESOLVE_MERGE_SQL.formatted("slug = $1"))
            .execute(Tuple.of(slug))
            .compose(rows -> rows.iterator().hasNext()
                ? findById(rows.iterator().next().getLong("id"), viewerId)
                : Future.succeededFuture(Optional.empty()));
    }

    /**
     * Managers whose company is one of {@code companyIds}, matched on name.
     *
     * <p>The company half of a search by <em>identity</em> rather than by text. The client already
     * resolves which company was chosen and sends its id; matching on the name it happens to be
     * stored under instead is what let "Revvity" and "Revvity Chemagen Technologie" look like two
     * unrelated employers, and what makes a search for one miss the managers of the other.
     *
     * <p>The caller decides the scope. For a selected company that is a parent, it is the parent
     * plus its descendants - see {@link CompanyRepository#findDescendantIds}.
     *
     * <p>Falls back to nothing when the list is empty: an empty scope is not "every company".
     */
    public Future<RowSet<Row>> searchInCompanies(int limit, int offset, String searchPattern,
                                                 java.util.List<Long> companyIds, String sortBy,
                                                 UUID userId) {
        if (companyIds == null || companyIds.isEmpty()) {
            return db.preparedQuery("SELECT 1 WHERE false").execute();
        }
        String orderBy = buildOrderBy(sortBy);
        return db.preparedQuery(SELECT_BODY + """
                WHERE (m.name ILIKE $3 OR m.title ILIKE $3)
                  AND m.company_id = ANY($4)
                  AND (m.approval_status IN ('approved','ghost')
                       OR (m.approval_status = 'pending_approval' AND m.search_created_by_user_id = $5))
                GROUP BY m.id, c.slug, c.industry, c.domain, c.brandfetch_icon_url
                """ + orderBy + " LIMIT $1 OFFSET $2")
            .execute(Tuple.of(limit, offset, searchPattern,
                              companyIds.toArray(new Long[0]), userId));
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
                sql   = SELECT_BODY + "WHERE (m.name ILIKE $3 OR m.company ILIKE $3 OR m.title ILIKE $3) AND m.company ILIKE $4 AND (m.approval_status IN ('approved','ghost') OR (m.approval_status = 'pending_approval' AND m.search_created_by_user_id = $5)) GROUP BY m.id, c.slug, c.industry, c.domain, c.brandfetch_icon_url " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, searchPattern, companyPattern, userId);
            } else if (hasSearch) {
                sql   = SELECT_BODY + "WHERE (m.name ILIKE $3 OR m.company ILIKE $3 OR m.title ILIKE $3) AND (m.approval_status IN ('approved','ghost') OR (m.approval_status = 'pending_approval' AND m.search_created_by_user_id = $4)) GROUP BY m.id, c.slug, c.industry, c.domain, c.brandfetch_icon_url " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, searchPattern, userId);
            } else if (hasCompany) {
                sql   = SELECT_BODY + "WHERE m.company ILIKE $3 AND (m.approval_status IN ('approved','ghost') OR (m.approval_status = 'pending_approval' AND m.search_created_by_user_id = $4)) GROUP BY m.id, c.slug, c.industry, c.domain, c.brandfetch_icon_url " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, companyPattern, userId);
            } else {
                sql   = SELECT_BODY + "WHERE (m.approval_status IN ('approved','ghost') OR (m.approval_status = 'pending_approval' AND m.search_created_by_user_id = $3)) GROUP BY m.id, c.slug, c.industry, c.domain, c.brandfetch_icon_url " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, userId);
            }
        } else {
            if (hasSearch && hasCompany) {
                sql   = SELECT_BODY + "WHERE (m.name ILIKE $3 OR m.company ILIKE $3 OR m.title ILIKE $3) AND m.company ILIKE $4 AND m.approval_status IN ('approved','ghost') GROUP BY m.id, c.slug, c.industry, c.domain, c.brandfetch_icon_url " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, searchPattern, companyPattern);
            } else if (hasSearch) {
                sql   = SELECT_BODY + "WHERE (m.name ILIKE $3 OR m.company ILIKE $3 OR m.title ILIKE $3) AND m.approval_status IN ('approved','ghost') GROUP BY m.id, c.slug, c.industry, c.domain, c.brandfetch_icon_url " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, searchPattern);
            } else if (hasCompany) {
                sql   = SELECT_BODY + "WHERE m.company ILIKE $3 AND m.approval_status IN ('approved','ghost') GROUP BY m.id, c.slug, c.industry, c.domain, c.brandfetch_icon_url " + orderBy + " LIMIT $1 OFFSET $2";
                tuple = Tuple.of(limit, offset, companyPattern);
            } else {
                sql   = SELECT_BODY + "WHERE m.approval_status IN ('approved','ghost') GROUP BY m.id, c.slug, c.industry, c.domain, c.brandfetch_icon_url " + orderBy + " LIMIT $1 OFFSET $2";
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
                SELECT m.id, m.name, m.company, m.company_id, m.title, m.image, m.overall_rating,
                       m.reviews_count, m.bio, m.status, m.approval_status, m.linkedin_url,
                       m.company_logo_url, m.country, m.state, m.city, m.location_source, m.created_at,
                       /*
                         The employer's resolved identity, so a pending card renders the same
                         logo the directory does.

                         Resolved by foreign key FIRST, then by exact name. A pending manager is
                         often linked to a half-typed fragment - the add form commits a company
                         while somebody is still typing, so a submission labelled "Discord" can
                         point at a row called "Di". The label is right and the key is wrong, and
                         the tile showed a letter beside identical tiles that rendered.

                         The name match is exact and case-insensitive against our OWN resolved
                         companies - not a guess about the outside world - and company names are
                         unique case-insensitively, so it cannot pick the wrong row.
                       */
                       COALESCE(c.domain, byname.domain) AS company_domain,
                       COALESCE(c.brandfetch_icon_url, byname.brandfetch_icon_url)
                           AS company_brandfetch_icon_url
                FROM managers m
                LEFT JOIN companies c ON c.id = m.company_id
            LEFT JOIN company_locations loc ON loc.id = m.company_location_id
                LEFT JOIN companies byname
                       ON NULLIF(c.domain, '') IS NULL
                      AND LOWER(TRIM(byname.name)) = LOWER(TRIM(m.company))
                      AND NULLIF(byname.domain, '') IS NOT NULL
                WHERE m.submitted_by = $1 AND m.approval_status IN ('pending_approval', 'rejected')
                  AND m.search_created_by_user_id IS NULL
                ORDER BY m.created_at DESC LIMIT 200
                """)
            .execute(Tuple.of(userId));
    }

    public Future<RowSet<Row>> findGhostForAdmin(int limit, int offset) {
        return db.preparedQuery("""
                SELECT m.id, m.name, m.company, m.title, m.image, m.overall_rating,
                       m.reviews_count, m.created_at, m.company_logo_url, m.company_id,
                       -- WHERE THE MANAGER WORKS, which this query did not select at all.
                       --
                       -- REPORTED, 2026-11-26: every row in the admin Live Profiles tab read
                       -- "No location set", including profiles that plainly have one. The tab was
                       -- not reporting the data, it was reporting the absence of these columns:
                       -- the panel falls back to that line when the payload carries no location,
                       -- and the payload could not carry one because nothing selected it.
                       --
                       -- findPendingForAdmin already carries exactly this set, for the reason its
                       -- own comment gives: a location nobody can see is a location nobody can
                       -- correct. A live profile is the one that is already published, so that
                       -- argument is stronger here, not weaker.
                       -- Whether the catalogue already holds this title. The admin reviewing a
                       -- submission is the only person who can promote a new one, and they cannot
                       -- do that if nothing tells them the title is new. Computed rather than
                       -- stored: it answers for every row, including the ones submitted before
                       -- anything recorded the question.
                       (m.title_normalized IS NOT NULL
                        AND NOT EXISTS (SELECT 1 FROM job_titles jt
                                         WHERE jt.match_key = m.title_normalized)) AS title_unlisted,
                       m.country, m.state, m.city, m.company_location_id, m.location_source,
                       loc.display_name AS location_name, loc.street AS location_street
                FROM managers m
                LEFT JOIN company_locations loc ON loc.id = m.company_location_id
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
                       -- THE COMPANY'S OWN IDENTITY, so the edit form can show its logo.
                       -- Only the NAME was selected, so re-opening an edit had nothing to render
                       -- but an initial, and a logo an admin had just changed was invisible here.
                       m.company_id, m.company_logo_url,
                       u.username AS submitted_by_username,
                       (m.search_created_by_user_id IS NOT NULL OR m.submitted_by IS NULL) AS is_auto_created,
                       -- Where the manager works, so the queue can show it and an admin can fix it.
                       -- It was not selected at all, so a location nobody could see was also a
                       -- location nobody could correct before approving it into the directory.
                       m.country, m.state, m.city, m.company_location_id, m.location_source,
                       loc.display_name AS location_name, loc.street AS location_street,
                       -- Whether the catalogue already holds this title. The admin reviewing a
                       -- submission is the only person who can promote a new one, and they cannot
                       -- do that if nothing tells them the title is new. Computed rather than
                       -- stored: it answers for every row, including the ones submitted before
                       -- anything recorded the question.
                       (m.title_normalized IS NOT NULL
                        AND NOT EXISTS (SELECT 1 FROM job_titles jt
                                         WHERE jt.match_key = m.title_normalized)) AS title_unlisted
                FROM managers m
                LEFT JOIN users u ON u.id = m.submitted_by
                LEFT JOIN company_locations loc ON loc.id = m.company_location_id
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
        return update(id, newCompany, newTitle, newImage, newBio, newStatus, newCountry,
                      null, null, null, newLinkedinUrl, newLogoUrl, newCompanyId);
    }

    /**
     * As above, but able to write the whole location rather than only the country.
     *
     * <p>Approving an edit request went through the country-only form, so an approved request to
     * move a manager to "Kitchener, Ontario" silently kept the old city. Each part stays
     * independent and optional: null leaves a column alone, so an edit about a job title cannot
     * blank a location it never asked about. A negative {@code newCompanyLocationId} clears the
     * exact place, matching {@code adminEdit}.
     */
    public Future<Optional<Row>> update(long id, String newCompany, String newTitle,
                                         String newImage, String newBio, String newStatus,
                                         String newCountry, String newState, String newCity,
                                         Long newCompanyLocationId,
                                         String newLinkedinUrl, String newLogoUrl,
                                         Long newCompanyId) {
        StringBuilder sql = new StringBuilder("UPDATE managers SET updated_at = now()");
        List<Object> params = new ArrayList<>();
        int idx = 1;
        if (newCompany     != null) { sql.append(", company = $").append(idx++);           params.add(newCompany); }
        if (newTitle       != null) { sql.append(", title = $").append(idx++);             params.add(newTitle); }
        if (newImage       != null) { sql.append(", image = $").append(idx++);             params.add(newImage); }
        if (newBio         != null) { sql.append(", bio = $").append(idx++);               params.add(newBio); }
        if (newStatus      != null) { sql.append(", status = $").append(idx++);            params.add(newStatus); }
        boolean locationTouched = newCountry != null || newState != null || newCity != null
                                  || newCompanyLocationId != null;
        if (newCountry     != null) { sql.append(", country = $").append(idx++);           params.add(newCountry.isBlank() ? null : newCountry); }
        if (newState       != null) { sql.append(", state = $").append(idx++);             params.add(newState.isBlank()   ? null : newState); }
        if (newCity        != null) { sql.append(", city = $").append(idx++);              params.add(newCity.isBlank()    ? null : newCity); }
        if (newCompanyLocationId != null) { sql.append(", company_location_id = $").append(idx++); params.add(newCompanyLocationId < 0 ? null : newCompanyLocationId); }
        /*
          An edited location is somebody's answer, so it is recorded as one.

          Without this the write succeeds and stays invisible: the reads publish sub-country detail
          only for a confirmed location, and a manager created by a search carries
          'legacy_visitor_inferred' until something says otherwise. Correcting a ghost's city would
          save and never display - the same silently-discarded edit this whole change set exists to
          remove.
        */
        if (locationTouched) { sql.append(", location_source = $").append(idx++); params.add("contributor_declared"); }
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
                RETURNING id, name, company, company_logo_url, submitted_by, company_id, search_created_by_user_id
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

    /**
     * Rejects a submitted manager. Touches nothing else.
     *
     * <p>This used to also sweep up "twins" - ghost managers with the same name whose company was a
     * prefix of the submitted one, left behind by the search box creating a record as somebody
     * typed. It was the wrong place to solve that. Pending and live are separate states: a pending
     * row is awaiting a decision and is not public, a live row has already been decided. An admin
     * rejecting something that was never public should not be able to change something that is,
     * and every attempt to scope that sweep narrowly still got it wrong - first by name alone,
     * which took out unrelated namesakes, then by a prefix rule a blank company defeated.
     *
     * <p>The problem it was compensating for is gone at the source: {@code createCapturedDraft}
     * writes {@code pending_approval}, so typing no longer publishes anything. A half-typed capture
     * now lands in the same admin queue as everything else and is rejected on its own row.
     */
    public Future<Optional<Row>> reject(long managerId) {
        return db.preparedQuery("""
                UPDATE managers SET approval_status = 'rejected', updated_at = now()
                WHERE id = $1 AND approval_status = 'pending_approval'
                RETURNING id, name, company, submitted_by, search_created_by_user_id
                """)
            .execute(Tuple.of(managerId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /*
      mergeInto lived here until phase 3 and is deliberately GONE rather than deprecated.

      It repointed reviews.manager_id at the survivor, soft-deleted whatever would collide on
      company, title and user-or-author, and touched career_history zero times. Two things make
      keeping it unacceptable rather than merely redundant:

        - it took real opinions out of public view over a collision nobody was shown, which is
          the defect rev 268 names;
        - it becomes UNSAFE the moment any rating carries a claim. reviews_claim_is_the_managers
          is DEFERRABLE INITIALLY IMMEDIATE, so moving a rating without moving its claim and its
          role is refused outright by the database.

      The whole operation now lives in ManagerMergeService, which moves the employment history
      and the claims, canonicalizes equivalent roles with an audit, holds and marks duplicate
      contributions instead of deleting them, folds the absorbed profile's moderation tasks, and
      recalculates both managers - in one transaction. mergeInlineRecalculate went with it: it was
      a merge helper, and the merge now recalculates on its own connection inside the transaction
      rather than afterwards and only for the survivor.
    */

    /**
     * Removes a manager, keeping every review a person wrote.
     *
     * <p>Seeded placeholders go; anything with an author is soft-deleted and the manager row is
     * retired rather than removed, because {@code reviews.manager_id} cascades and deleting the
     * row would destroy them. A manager nobody has reviewed is deleted outright, which is what
     * "delete" means for the empty rows this is mostly used on.
     *
     * @return true when the row was deleted, false when it was retired because it held reviews
     */
    public Future<Boolean> deleteOrRetire(long managerId) {
        return ((io.vertx.sqlclient.Pool) db).withTransaction(conn ->
            conn.preparedQuery(
                    "DELETE FROM reviews WHERE manager_id = $1 AND weight = TRUE AND user_id IS NULL")
                .execute(Tuple.of(managerId))
                .compose(ignored -> conn.preparedQuery(
                        "SELECT COUNT(*) AS c FROM reviews WHERE manager_id = $1")
                    .execute(Tuple.of(managerId)))
                .compose(rows -> {
                    /*
                        One path, and it never destroys the row.

                        A review-less manager used to be hard-DELETEd. Three things followed from
                        that, and all three are worse than keeping the row:

                          - the row vanished, so manager_url_history had nothing to point at and
                            every inbound link became a hard 404, including anything indexed;
                          - the slug was silently freed and could later be handed to a DIFFERENT
                            person, so an old link resolved to the wrong human - the worst outcome
                            available here;
                          - no trace was left of a destructive admin action.

                        Retiring costs a row and keeps all three properties. The slug is parked in
                        the rejected namespace so the NAME is still freed for whoever should have
                        it, which was the only real argument for deleting.

                        A genuine hard delete still exists for spam and PII - see delete() - but it
                        is a separate, explicit action rather than a side effect of "remove".
                    */
                    return conn.preparedQuery(
                            "UPDATE reviews SET deleted_at = now() "
                            + "WHERE manager_id = $1 AND deleted_at IS NULL")
                        .execute(Tuple.of(managerId))
                        .compose(v -> conn.preparedQuery(
                                "UPDATE managers SET approval_status = 'rejected', updated_at = now() "
                                + "WHERE id = $1")
                            .execute(Tuple.of(managerId)))
                        .compose(v -> conn.preparedQuery("""
                                UPDATE managers
                                   SET slug = CASE
                                                WHEN EXISTS (SELECT 1 FROM managers x
                                                              WHERE x.slug = managers.slug || $2
                                                                AND x.id <> managers.id)
                                                THEN managers.slug || $2 || '-' || managers.id
                                                ELSE managers.slug || $2
                                              END,
                                       updated_at = now()
                                 WHERE id = $1 AND slug IS NOT NULL AND position($2 in slug) = 0
                                """)
                            .execute(Tuple.of(managerId, REJECTED_SLUG_SUFFIX)))
                        .map(false);
                }));
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

    /**
     * Whether a manager is a legitimate party to a merge: it exists and has not already been
     * merged away.
     *
     * <p>The plain existence check this sits beside counts any row at all, retired ones included.
     * A merge retires the row it absorbs rather than deleting it - so a manager that had already
     * been merged remained a valid target, and merging into one retired the survivor too, taking
     * both out of the directory. Repeating one suggestion, or acting on a reciprocal pair
     * (A into B, then B into A), was enough to do it, and the manager count fell with every click.
     */
    public Future<Boolean> isMergeable(long id) {
        return db.preparedQuery(
                "SELECT 1 FROM managers WHERE id = $1 AND merged_into IS NULL "
                + "AND approval_status <> 'rejected'")
            .execute(Tuple.of(id))
            .map(rows -> rows.rowCount() > 0);
    }

    // ── Career history ────────────────────────────────────────────────────────

    public Future<RowSet<Row>> getCareerHistory(long managerId) {
        return db.preparedQuery("""
                SELECT company, title, start_date, end_date
                FROM career_history WHERE manager_id = $1
                -- NULLS LAST, so an undated employment reads as the OLDEST in this
                -- newest-first list rather than the newest. Without it PostgreSQL's
                -- DESC default is NULLS FIRST, which put a row nobody has dated at the
                -- top - disagreeing with the aggregate above, which has always said
                -- NULLS LAST, about the same rows.
                ORDER BY start_date DESC NULLS LAST
                """)
            .execute(Tuple.of(managerId));
    }

    /**
     * The career entry a manager is currently in, if any.
     *
     * <p>"Current" is the open entry - {@code end_date IS NULL} - and the most recently started
     * one when several are open. Defined once here so every caller agrees; it was previously
     * re-expressed in each query that needed it.
     */
    public Future<Optional<Row>> findOpenCareerEntry(long managerId) {
        return db.preparedQuery("""
                SELECT id, company, company_id, title, start_date, end_date
                  FROM career_history
                 WHERE manager_id = $1 AND end_date IS NULL
                 ORDER BY start_date DESC NULLS LAST
                 LIMIT 1
                """)
            .execute(Tuple.of(managerId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /**
     * Rebuilds the manager's headline from their career history.
     *
     * <p><b>This exists because three tables each owned part of the same truth and nothing
     * reconciled them.</b> The header came from the {@code managers} row, the role cards from
     * {@code career_history}, and the trajectory's dates from {@code reviews} - so a manager could
     * be shown at Lime, listed at ICAT, and dated from whenever their reviewer happened to work
     * there. Editing one surface silently left the others behind, which is why this page has been
     * repaired repeatedly and kept coming back.
     *
     * <p>Career history is now the owner of company, company_id, title, logo and retirement.
     * Every path that changes a career entry ends here, so the headline cannot drift from the
     * roles beneath it rather than being re-synchronised by hand at each call site.
     *
     * <p>The current role wins; with none open the most recent closed one does, and the manager is
     * retired by definition - nobody is "actively leading" a role that has an end date. A manager
     * with no career history at all is left completely alone: there is nothing to derive from, and
     * inventing a headline would be worse than keeping the one they have.
     */
    public Future<Void> syncHeadlineFromCareerHistory(long managerId) {
        return db.preparedQuery("""
                UPDATE managers m
                   SET company        = ch.company,
                       company_id     = COALESCE(ch.company_id, m.company_id),
                       title          = ch.title,
                       company_logo_url = COALESCE(c.logo_url, m.company_logo_url),
                       status         = CASE WHEN ch.end_date IS NULL THEN 'active' ELSE 'retired' END,
                       updated_at     = now()
                  FROM (
                        SELECT DISTINCT ON (manager_id)
                               manager_id, company, company_id, title, end_date
                          FROM career_history
                         WHERE manager_id = $1
                         ORDER BY manager_id,
                                  (end_date IS NULL) DESC,
                                  start_date DESC NULLS LAST,
                                  id DESC
                       ) ch
                  LEFT JOIN companies c ON c.id = ch.company_id
                 WHERE m.id = ch.manager_id
                """)
            .execute(Tuple.of(managerId))
            .mapEmpty();
    }

    /**
     * Start date of the manager's genuinely-current role: the open (end_date IS NULL) career
     * entry with the latest start_date. Returns empty when the manager has no open career entry
     * (callers fall back to the manager's created_at as the implicit current-role start).
     *
     * Used to decide whether a newly-added open-ended role is actually more recent than the
     * current one — an OLDER role must not take over the manager's headline company/title/logo.
     */
    public Future<Optional<OffsetDateTime>> findCurrentRoleStart(long managerId) {
        return db.preparedQuery("""
                SELECT start_date FROM career_history
                WHERE manager_id = $1 AND end_date IS NULL
                ORDER BY start_date DESC NULLS LAST
                LIMIT 1
                """)
            .execute(Tuple.of(managerId))
            .map(rs -> {
                Iterator<Row> it = rs.iterator();
                return it.hasNext()
                    ? Optional.ofNullable(it.next().getOffsetDateTime("start_date"))
                    : Optional.<OffsetDateTime>empty();
            });
    }

    public Future<Void> insertCareerEntry(long managerId, String company, String title,
                                           OffsetDateTime startDate, OffsetDateTime endDate, Long companyId) {
        // The row and its employment status in ONE transaction. A career entry carrying an end
        // date IS ended employment the moment it exists, and nothing downstream has to remember
        // to say so.
        return db.withTransaction(tx -> tx.preparedQuery("""
                INSERT INTO career_history(manager_id, company, title, start_date, end_date, company_id)
                VALUES ($1, $2, $3, $4, $5, $6)
                RETURNING id
                """)
            .execute(Tuple.of(managerId, company, title, startDate, endDate, companyId))
            .compose(rs -> EmploymentStatusResolver.resolveRole(
                tx, rs.iterator().next().getLong("id"))));
    }

    public Future<Integer> closeOpenCareerEntry(long managerId, OffsetDateTime endDate) {
        // Only close entries whose start_date <= endDate to avoid violating the CHECK (end_date >= start_date) constraint.
        // If the current open entry started after endDate (e.g. inserting a historical position), it stays open.
        // RETURNING id, because this closes however many entries were open and every one of
        // them has just become ended employment. Resolving the rows it actually touched beats
        // resolving the manager: it is the same work for one row and less for the common case.
        return db.withTransaction(tx -> tx.preparedQuery(
                "UPDATE career_history SET end_date = $1 WHERE manager_id = $2 "
                + "AND end_date IS NULL AND start_date <= $1 RETURNING id")
            .execute(Tuple.of(endDate, managerId))
            .compose(rs -> {
                Future<Void> chain = Future.succeededFuture();
                for (Row r : rs) {
                    long id = r.getLong("id");
                    chain = chain.compose(v -> EmploymentStatusResolver.resolveRole(tx, id));
                }
                return chain.map(v -> rs.rowCount());
            }));
    }

    /**
     * Edits one career entry.
     *
     * company_id moves with the company name. Leaving it behind produces a row that says one
     * company and points at another - and since the queries that decide which managers appear on a
     * company page match on the id, the display name is the half that does not count. A manager
     * corrected onto a new company would keep showing up under the old one with no visible reason.
     *
     * COALESCE so a caller that could not resolve an id leaves the existing one alone rather than
     * nulling it.
     */
    /**
     * One career entry's current company and title, read before it is changed.
     *
     * <p>Needed because the ratings that belong to a role are found by matching their snapshotted
     * company and title against it. Once the row is updated the old pair is gone, so there is
     * nothing left to match and the ratings are stranded under a title nobody can reach.
     */
    public Future<java.util.Optional<Row>> findCareerEntry(long entryId, long managerId) {
        return db.preparedQuery(
                // company_id travels with it because a role merge may only join two employments
                // at the SAME employer, and the caller has to be able to tell before it tries.
                "SELECT company, title, company_id FROM career_history "
                + "WHERE id = $1 AND manager_id = $2")
            .execute(Tuple.of(entryId, managerId))
            .map(rows -> rows.size() == 0
                ? java.util.Optional.<Row>empty()
                : java.util.Optional.of(rows.iterator().next()));
    }

    /**
     * Moves the ratings recorded under one role name to another, for one manager.
     *
     * <h2>Why this is needed at all</h2>
     *
     * <p>A rating snapshots the company and title it was written about, and the career trajectory
     * groups by that pair. So correcting a role's title in {@code career_history} alone splits the
     * trajectory in two: the corrected entry with no ratings, and the ratings still filed under
     * the old wording. To an admin it looks as though the edit did not save.
     *
     * <h2>Why rewriting a snapshot is right HERE and nowhere else</h2>
     *
     * <p>Snapshots exist so that a manager later changing jobs never rewrites where an opinion was
     * formed. This is not that: an administrator editing the role is asserting what the role was
     * actually called, which is the one case where the stored wording is the thing being
     * corrected. It is driven by an explicit human decision about one entry, not by a side effect
     * of the manager moving.
     *
     * <p>Matched on the same normalisation the trajectory groups by - whitespace stripped from the
     * company, collapsed in the title, both lower-cased - so exactly the ratings that were showing
     * under the old name are the ones that move, and no more.
     *
     * <p>Soft-deleted ratings move too. They are invisible today, but one that is later restored
     * would otherwise reappear under a title that no longer exists anywhere.
     */
    public Future<Integer> moveReviewsBetweenRoles(long managerId,
                                                   String fromCompany, String fromTitle,
                                                   String toCompany, String toTitle) {
        return db.preparedQuery("""
                UPDATE reviews
                   SET manager_company = $4, manager_title = $5
                 WHERE manager_id = $1
                   AND REGEXP_REPLACE(LOWER(TRIM(manager_company)), '\\s+', '', 'g')
                     = REGEXP_REPLACE(LOWER(TRIM($2::text)), '\\s+', '', 'g')
                   AND REGEXP_REPLACE(LOWER(TRIM(manager_title)), '\\s+', ' ', 'g')
                     = REGEXP_REPLACE(LOWER(TRIM($3::text)), '\\s+', ' ', 'g')
                """)
            .execute(Tuple.of(managerId, fromCompany, fromTitle, toCompany, toTitle))
            .map(RowSet::rowCount);
    }

    public Future<Integer> updateCareerEntry(long entryId, long managerId, String company, String title,
                                              OffsetDateTime startDate, OffsetDateTime endDate,
                                              Long companyId) {
        // An edit can set an end date, change one, or clear one, and the stored status follows
        // the record in all three cases. Clearing one is the case worth naming: without the
        // resolution the row would go on asserting that employment finished.
        return db.withTransaction(tx -> tx.preparedQuery("""
                UPDATE career_history
                SET company = $3, title = $4, start_date = $5, end_date = $6,
                    company_id = COALESCE($7::BIGINT, company_id), updated_at = now()
                WHERE id = $1 AND manager_id = $2
                RETURNING id
                """)
            .execute(Tuple.of(entryId, managerId, company, title, startDate, endDate, companyId))
            .compose(rs -> rs.rowCount() == 0
                ? Future.succeededFuture(0)
                : EmploymentStatusResolver.resolveRole(tx, rs.iterator().next().getLong("id"))
                    .map(v -> rs.rowCount())));
    }

    /**
     * The predicate that identifies ONE role's ratings, keyed exactly as the trajectory groups.
     *
     * <p>Every statement below used to match on the COMPANY alone, which is wrong the moment a
     * manager holds two roles at one employer: "Director" and "VP" at the same company are two
     * stages on the chart, and an admin removing one of them was deleting the ratings under both.
     * The destructive case is the one that matters - {@code DELETE FROM reviews WHERE
     * manager_company = ...} destroyed a sibling role's genuine opinions, and nothing on screen
     * said so.
     *
     * <p>Normalised the same way {@code ReviewRepository.SEGMENTS_SQL} groups: whitespace stripped
     * from the company, collapsed to one space in the title, both lower-cased. That is not a
     * stylistic choice. If this matched differently from the chart then what an admin was shown
     * and what the statement touched would be two different sets of ratings, which is the whole
     * class of bug being fixed.
     *
     * <p>Binds {@code $2} to the company and {@code $3} to the title, so every caller passes
     * {@code (managerId, company, title)} in that order.
     */
    private static final String REVIEW_ROLE_MATCH = """
              AND REGEXP_REPLACE(LOWER(TRIM(COALESCE(manager_company, ''))), '\\s+', '', 'g')
                = REGEXP_REPLACE(LOWER(TRIM($2::text)), '\\s+', '', 'g')
              AND REGEXP_REPLACE(LOWER(TRIM(COALESCE(manager_title, ''))), '\\s+', ' ', 'g')
                = REGEXP_REPLACE(LOWER(TRIM($3::text)), '\\s+', ' ', 'g')
            """;

    /**
     * What a career stage is actually made of, so an admin is told before they destroy it.
     *
     * <p>A stage on the trajectory is not necessarily a {@code career_history} row. Most are not:
     * the chart groups a manager's reviews by {@code reviews.manager_company}, so a stage exists
     * wherever a review claims one, whether or not anybody recorded the role. That is how a company
     * nobody ever worked at can become a whole career stage, complete with a rating, from a single
     * placeholder review that captured a half-typed company name.
     *
     * <p>Returns the seed count and the real count separately because they justify very different
     * actions. Deleting placeholders is housekeeping. Deleting somebody's genuine review is not,
     * and an admin pressing a button on a chart should be told which one they are about to do.
     */
    public Future<Row> describeCareerStage(long managerId, String company, String title) {
        return db.preparedQuery("""
                SELECT
                    COUNT(*)::INTEGER AS total_reviews,
                    COUNT(*) FILTER (WHERE user_id IS NULL AND weight = TRUE)::INTEGER AS seed_reviews,
                    COUNT(*) FILTER (WHERE NOT (user_id IS NULL AND weight = TRUE))::INTEGER AS real_reviews
                FROM reviews
                WHERE manager_id = $1
                """ + REVIEW_ROLE_MATCH)
            .execute(Tuple.of(managerId, company, title))
            .map(rows -> rows.iterator().next());
    }

    /**
     * Removes every review belonging to one career stage.
     *
     * <p>Matched on {@link #REVIEW_ROLE_MATCH}, exactly as {@link #describeCareerStage} counts
     * them, so what is deleted is what the admin was shown. Matching the company alone deleted
     * every role at that employer; matching the raw string would let "Ro " and "Ro" be counted
     * together and deleted apart.
     */
    public Future<Integer> deleteCareerStageReviews(long managerId, String company, String title) {
        return db.preparedQuery("""
                DELETE FROM reviews
                WHERE manager_id = $1
                """ + REVIEW_ROLE_MATCH)
            .execute(Tuple.of(managerId, company, title))
            .map(SqlResult::rowCount);
    }

    /**
     * Moves a career stage's reviews onto another role.
     *
     * <p>For the case where the stage is wrong but the ratings are real: somebody's genuine review
     * is sitting under a company name that should never have existed, and deleting it would throw
     * away a real opinion to tidy up a chart.
     *
     * <p>The reviewer's own {@code worked_from} and {@code worked_until} are deliberately NOT
     * touched. They are that person's statement about when they were there, not a property of the
     * stage, and rewriting them to fit the target role would be inventing testimony. The visible
     * effect is that the target stage may widen to cover the moved reviews' dates, which is
     * correct: those months are now claimed under that role.
     */
    public Future<Integer> reassignCareerStageReviews(long managerId,
                                                      String fromCompany, String fromTitle,
                                                      String toCompany, String toTitle) {
        return db.preparedQuery("""
                UPDATE reviews
                SET manager_company = $4,
                    manager_title   = COALESCE($5, manager_title),
                    updated_at      = now()
                WHERE manager_id = $1
                """ + REVIEW_ROLE_MATCH)
            .execute(Tuple.of(managerId, fromCompany, fromTitle, toCompany, toTitle))
            .map(SqlResult::rowCount);
    }

    /**
     * The {@code career_history} rows behind one stage, matched the same way as its reviews.
     *
     * <h2>Why every id, and why not the newest one</h2>
     *
     * <p>This was {@code findCareerEntryIdByCompany}, which matched the company alone and then took
     * {@code ORDER BY start_date DESC LIMIT 1}. For a manager with two roles at one employer that
     * is a coin toss: an admin removing the earlier role had the LATER row deleted, and the role
     * they meant to remove stayed on the chart. Being a lookup for a destructive action made it
     * worse than merely wrong.
     *
     * <p>Several rows CAN legitimately back one stage, because the chart groups on the normalised
     * pair: two spellings of one employer, or the same title recorded twice across a gap, collapse
     * into a single tile. All of them belong to the stage the admin clicked, so all of them are
     * returned and the caller acts on the set rather than picking from it.
     */
    public Future<java.util.List<Long>> findCareerEntryIdsByRole(long managerId, String company,
                                                                 String title) {
        return findCareerPeriodsByRole(managerId, company, title)
            .map(rows -> {
                java.util.List<Long> ids = new java.util.ArrayList<>();
                for (Row r : rows) ids.add(r.getLong("id"));
                return ids;
            });
    }

    /**
     * Every employment period recorded under one (company, title) stage, oldest first.
     *
     * <p>A STAGE IS NOT ALWAYS ONE JOB, and assuming it was is the second form of the defect this
     * class's {@code REVIEW_ROLE_MATCH} fixes. Somebody can leave an employer and come back to the
     * same title years later: two genuinely separate employments, identical (company, title), and
     * the trajectory draws them as ONE tile because it groups on that pair. A destructive operation
     * that took "the stage" would take both periods, and the admin was shown one.
     *
     * <p>So the rows come back as periods with their dates rather than as a bare id list, and the
     * caller refuses to act on an ambiguous stage until the request names which period it means.
     * The dates are here so the refusal can describe the choice instead of merely declining.
     *
     * <p>{@code state <> 'superseded'} matches {@code ReviewRepository.SEGMENTS_SQL}. Without it a
     * role that {@link org.werkpages.service.ManagerMergeService#mergeRoles} has superseded still
     * carries its old (company, title), so it would count as a second period here and make every
     * later operation on the surviving role look ambiguous - a merge would poison the stage it had
     * just repaired.
     */
    public Future<java.util.List<Row>> findCareerPeriodsByRole(long managerId, String company,
                                                               String title) {
        return db.preparedQuery("""
                -- ::date deliberately. career_history.start_date is a TIMESTAMPTZ, and the
                -- refusal message below renders these as the calendar days the schema's own
                -- uniqueness key uses (start_day), not as instants in some timezone.
                SELECT id, company, title, company_id,
                       start_date::date AS start_date,
                       end_date::date   AS end_date
                FROM career_history
                WHERE manager_id = $1
                  AND state <> 'superseded'
                  AND REGEXP_REPLACE(LOWER(TRIM(company)), '\\s+', '', 'g')
                    = REGEXP_REPLACE(LOWER(TRIM($2::text)), '\\s+', '', 'g')
                  AND REGEXP_REPLACE(LOWER(TRIM(COALESCE(title, ''))), '\\s+', ' ', 'g')
                    = REGEXP_REPLACE(LOWER(TRIM($3::text)), '\\s+', ' ', 'g')
                ORDER BY start_date NULLS FIRST, id
                """)
            .execute(Tuple.of(managerId, company, title))
            .map(rows -> {
                java.util.List<Row> out = new java.util.ArrayList<>();
                for (Row r : rows) out.add(r);
                return out;
            });
    }

    public Future<Integer> deleteCareerEntry(long entryId, long managerId) {
        return db.preparedQuery("DELETE FROM career_history WHERE id = $1 AND manager_id = $2")
            .execute(Tuple.of(entryId, managerId))
            .map(RowSet::rowCount);
    }

    /**
     * Updates the open (current) career entry in place — used for typo/spelling corrections.
     *
     * Carries company_id for the same reason as updateCareerEntry above: the caller has already
     * resolved the company and written it to the manager row, so leaving the history row pointing
     * at the previous company makes the two disagree about the same fact.
     */
    public Future<Void> updateOpenCareerEntry(long managerId, String company, String title, Long companyId) {
        return db.preparedQuery("""
                UPDATE career_history
                SET company = $2, title = $3, company_id = COALESCE($4::BIGINT, company_id)
                WHERE manager_id = $1 AND end_date IS NULL
                """)
            .execute(Tuple.of(managerId, company, title, companyId))
            .mapEmpty();
    }

    // ── Stats recalculation ───────────────────────────────────────────────────

    /**
     * Managers whose cached rating count disagrees with the ratings they actually hold.
     *
     * <p>Nothing in the request path fires for this class of drift, so without a sweep the figures
     * stay wrong indefinitely: a manager keeps showing a rating and a count while the list beneath
     * it shows something else, until some unrelated write happens to recalculate them.
     *
     * <p><b>This asks the cache's own question, and that is the fix.</b> It used to ask a different
     * one: find managers holding a placeholder whose weight window had lapsed, then compare the
     * cache against a count computed from that same window. Both halves described the lifecycle
     * that {@code reviews.origin} replaced, and the mismatch had two consequences.
     *
     * <ul>
     *   <li><b>It could not converge.</b> {@link #recalculate} writes the cache from
     *       {@link ReviewSql#genuine}, so for a placeholder still classified
     *       {@code origin = 'contributor'} - precisely the three rows found in production - the
     *       cache was 1 and the window-based count was 0. The manager was reported stale, was
     *       recalculated, came back 1 again, and was reported stale again. Every night, forever,
     *       printing a line that said work had been done.</li>
     *   <li><b>It was blind to every other cause.</b> Any drift not accompanied by an expired
     *       weight window went unnoticed, which is most of them: an interrupted write, a manual
     *       correction, a deployment whose arithmetic predated V106.</li>
     * </ul>
     *
     * <p>Comparing the cache against the genuine count instead is self-correcting by construction:
     * {@code recalculate} sets the cache to exactly this number, so a manager it has fixed cannot
     * be returned again, and a manager it has not fixed is returned until it is. An example
     * contributes to neither side, so a date passing no longer means anything here, which is the
     * point: the example lifecycle has no clock in it.
     */
    public Future<RowSet<Row>> findManagersWithStaleRatingCache() {
        return db.query("""
                SELECT m.id
                FROM managers m
                WHERE m.reviews_count <> (
                    SELECT COUNT(*) FROM reviews r
                    -- The predicate goes on its own line deliberately: a text block strips the
                    -- trailing whitespace before its closing quotes, so "AND " followed by the
                    -- fragment concatenates into "ANDr.disposition" and Postgres reports a syntax
                    -- error at "ANDr". Ending the line after AND leaves the newline as the
                    -- separator. recalculate() above splits its WHERE the same way, for the same
                    -- reason.
                    WHERE r.manager_id = m.id AND
                """ + ReviewSql.genuine("r") + """
                )
                """)
            .execute();
    }

    /** Fire-and-forget: recalculates and persists rating stats without blocking. */
    public void recalculateInBackground(long managerId) {
        recalculate(managerId).onFailure(err ->
            System.err.println("Background recalculate failed for manager " + managerId + ": " + err.getMessage())
        );
    }

    /**
     * The manager's cached rating and count, recomputed on a connection the caller chooses.
     *
     * <p>{@code on} is the pool for every form path, which is what this always used. It is the
     * CALLER'S CONNECTION when the recomputation must happen inside a transaction that is still
     * open: the admin queue writes a review and then recalculates, and doing the second half on a
     * different connection waits for locks the first half is still holding. The symptom was not an
     * error but a hang, and then every later test timing out on TRUNCATE.
     */
    public Future<Void> recalculate(long managerId) {
        return recalculate(db, managerId);
    }

    public Future<Void> recalculate(io.vertx.sqlclient.SqlClient on, long managerId) {
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
            -- Live only. A held rating must not reach the cached rating or count: this is a write
            -- path, so a leak here outlives the hold being lifted or the rating being rejected. The
            -- comment below records the same class of bug happening once already, which is why the
            -- predicate comes from ReviewSql rather than being retyped at each site.
            FROM reviews r
            WHERE
            """ + ReviewSql.genuine("r") + """
              AND manager_id = $1
            """;
        /*
          GENUINE, NOT live. The weight/expiry clause that used to be here is GONE, and both
          changes are the same decision.

          It read:
              AND (weight = FALSE OR weight_expires_on IS NULL OR weight_expires_on > CURRENT_DATE)

          which counted a platform-generated seed toward the manager's cached average and count
          while its weighting window was open, then stopped. That was the mechanism that put a
          rating on a brand-new ghost manager's tile. An example must now contribute nothing, ever,
          so the exclusion is by provenance and the window is irrelevant: a row is either a
          person's rating or it is a demonstration.

          Two consequences, both intended:

            * a manager with one example and no genuine ratings now has reviews_count 0 and a NULL
              overall_rating - a genuine zero, which is the point;
            * the gated tile therefore cannot take its number from this cache any more. It renders
              from the example ROW, which the profile payload still carries, flagged as an example.
              The blurred appearance is unchanged; what changed is where the number comes from.

          company_stats_expected aggregates SUM(m.reviews_count) and AVG(m.overall_rating) off this
          cache, so company, industry and ranking statistics inherit the exclusion through the
          existing V89/V90 triggers without a trigger function changing.
        */

        return on.preparedQuery(recalcSql)
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
                return on.preparedQuery("""
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

    // ── Admin direct edit (cascading) ─────────────────────────────────────────

    /**
     * Atomically updates name/title/company/linkedinUrl on the manager and cascades
     * title/company corrections to reviews and career_history where old values match.
     * Pass null for any field that should not change.
     */
/** Without location - the long-standing form, unchanged for its callers. */
    public Future<Optional<io.vertx.core.json.JsonObject>> adminEdit(long managerId, String newName,
            String newTitle, String newCompany, String newLinkedinUrl, Long newCompanyId) {
        return adminEdit(managerId, newName, newTitle, newCompany, newLinkedinUrl, newCompanyId,
                         null, null, null, null, null);
    }

    /** With location. Any of country/state/city may be null, meaning "leave it alone". */
    public Future<Optional<io.vertx.core.json.JsonObject>> adminEdit(long managerId, String newName,
                                                                     String newTitle, String newCompany,
                                                                     String newLinkedinUrl, Long newCompanyId,
                                                                     String newCountry, String newState,
                                                                     String newCity, String newCompanyLogoUrl,
                                                                     Long newCompanyLocationId) {
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

                    // Computed before the UPDATE, because the logo decision depends on it.
                    boolean companyChangedEarly =
                        (newCompany != null && !newCompany.equals(oldCompany)) || newCompanyId != null;

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
                    /*
                      A changed company must not keep the old company's logo.

                      company_logo_url is the FIRST thing the logo chain tries, ahead of the
                      company's own resolved identity - so moving a manager to a different
                      employer while leaving this set meant the profile went on showing the
                      previous company's mark, and re-picking in the admin editor appeared to do
                      nothing at all.

                      Cleared rather than guessed at: with it empty the chain resolves from the
                      NEW company's domain, which is the identity the admin just chose. An
                      explicitly picked logo overrides that and is stored as given.
                    */
                    if (newCompanyLogoUrl != null && !newCompanyLogoUrl.isBlank()) {
                        sql.append(", company_logo_url = $").append(idx++);
                        params.add(newCompanyLogoUrl.trim());
                    } else if (companyChangedEarly) {
                        sql.append(", company_logo_url = NULL");
                    }
                    /*
                      Where the manager actually works.
                      
                      Stored on the row since the beginning and settable only on the ADD form -
                      no edit surface, admin or otherwise, could correct it. A manager filed
                      against the wrong country stayed there, and country is what the directory
                      filters and what the geo checks read.
                      
                      Each part is optional and independent: passing null leaves it alone, so an
                      edit that touches only the title cannot blank a location it never asked
                      about.
                    */
                    if (newCountry != null) {
                        sql.append(", country = $").append(idx++);
                        params.add(newCountry.isBlank() ? null : newCountry);
                    }
                    if (newState != null) {
                        sql.append(", state = $").append(idx++);
                        params.add(newState.isBlank() ? null : newState);
                    }
                    if (newCity != null) {
                        sql.append(", city = $").append(idx++);
                        params.add(newCity.isBlank() ? null : newCity);
                    }
                    /*
                      The exact place, when one was picked.
                      
                      country/state/city is the coarse answer and company_location_id is the
                      precise one; an admin could see an address on the profile but never set or
                      correct it, because nothing wrote this column.
                      
                      A negative id means "clear it" - the only way to express "this manager is no
                      longer at a specific building" through a field that is otherwise additive.
                    */
                    if (newCompanyLocationId != null) {
                        sql.append(", company_location_id = $").append(idx++);
                        params.add(newCompanyLocationId < 0 ? null : newCompanyLocationId);
                    }
                    /*
                      An admin setting a location is declaring one, so it is recorded as declared.

                      Same reason as in update(): the reads publish sub-country detail only for a
                      confirmed location, and a search-created manager carries
                      'legacy_visitor_inferred'. Without this an admin could correct a ghost's city
                      and the profile would carry on showing the country alone.
                    */
                    if (newCountry != null || newState != null || newCity != null
                            || newCompanyLocationId != null) {
                        sql.append(", location_source = $").append(idx++);
                        params.add("contributor_declared");
                    }
                    params.add(managerId);
                    sql.append(" WHERE id = $").append(idx).append(" RETURNING id");

                    boolean titleChanged   = newTitle   != null && !newTitle.equals(oldTitle);
                    boolean companyChanged = newCompany != null && !newCompany.equals(oldCompany);

                    return conn.preparedQuery(sql.toString()).execute(Tuple.from(params))
                        .compose(v -> {
                            Future<Void> cascade = Future.succeededFuture();
                            /*
                              BOTH HALVES OF THE PAIR, on every one of these statements.

                              These two matched on one half each while the career_history statement
                              below them matched on both. So correcting the headline of a manager
                              who holds two roles at one employer rewrote the rating snapshots of
                              the OTHER role as well - it shares the company - and the same for two
                              employers sharing a title. The chart then drew one stage where there
                              were two, with another role's opinions folded into it, while
                              career_history still recorded both. A rating's snapshot is the record
                              of which job it was written about; moving it to a different job is
                              exactly the attribution loss this whole correction exists to prevent.

                              The pair is the role, so the pair is what these match on.
                            */
                            if (titleChanged || companyChanged) {
                                cascade = cascade.compose(x ->
                                    conn.preparedQuery("""
                                        UPDATE reviews
                                           SET manager_title   = $1,
                                               manager_company = $2
                                         WHERE manager_id = $3
                                           AND manager_title   = $4
                                           AND manager_company = $5
                                        """)
                                        .execute(Tuple.of(effTitle, effCompany, managerId,
                                                          oldTitle, oldCompany)).mapEmpty());
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

    /**
     * The same lookup as {@link #findByNameAndCompany}, but able to see captures too.
     *
     * <p>Used only by the capture path. That path now writes {@code pending_approval}, and the
     * public lookup admits only {@code approved} and {@code ghost} - which is correct for the
     * public lookup and pinned by the filter table in CLAUDE.md, so it is left alone. Reusing it
     * here would make every capture invisible to the check meant to stop duplicates, and each
     * visit to the add form would file another copy of the same person in the admin queue.
     *
     * <p>Rejected rows stay excluded: a person an admin has already turned down should not come
     * back as an existing match.
     */
    /**
     * Adopts the capture THIS attempt left behind, instead of filing a second person.
     *
     * <p>The add-manager form posts a capture as soon as its first step is valid, which is before
     * the company box is finished. So one attempt to add "Eddie Junior at Microsoft" left a
     * capture at "Mi", and the submission that followed created a second row - two managers for
     * one person, differing only by how much of the company name had been typed.
     *
     * <h2>PHASE 1: by token, never by resemblance</h2>
     *
     * <p>This used to find the row by description - the same name, the captured company a prefix
     * of the submitted one, no submitter yet, under an hour old - and that predicate was called
     * "deliberately tight". It is tight about the right race and it is still a guess: it also
     * describes a <em>different</em> Eddie Junior whom somebody else happened to half-type at a
     * company whose name starts the same way, minutes earlier. That row was then rewritten to the
     * adopter's company and handed to the adopter's account. One attempt's work silently became
     * another's, and the person who typed it first lost their draft.
     *
     * <p>The client now mints a token when step one first posts and presents the same token at
     * submit, so the row is identified rather than recognised. {@code capture_token} is written by
     * {@link #createCapturedDraft} inside the transaction that claims the attempt, which is what
     * makes it trustworthy here.
     *
     * <p>The three remaining conditions are not resemblance, they are eligibility:
     * <ul>
     *   <li>still {@code pending_approval} - a published manager is nobody's draft to adopt;</li>
     *   <li>no submitter yet - a draft already claimed is not reassigned;</li>
     *   <li>the attempt is unresolved - a spent token never adopts again, which is what makes a
     *       replay harmless.</li>
     * </ul>
     *
     * <p>What it still writes is what the submission legitimately knows about its own draft: the
     * finished company name and its id, the title when one was supplied, and the submitter. The
     * name is not among them - see the Phase 0 note on {@code updateForAttach}.
     *
     * @return the adopted row, or empty when this attempt has no draft left to adopt
     */
    public Future<Optional<Row>> adoptCaptureByToken(UUID captureToken, String company, String title,
                                                     UUID submittedBy, Long companyId) {
        if (captureToken == null) return Future.succeededFuture(Optional.empty());
        return db.preparedQuery("""
                UPDATE managers m
                SET company = $2, title = COALESCE($3, m.title), company_id = COALESCE($5, m.company_id),
                    submitted_by = $4, updated_at = now()
                WHERE m.capture_token = $1
                  AND m.approval_status = 'pending_approval'
                  AND m.submitted_by IS NULL
                  AND EXISTS (
                        SELECT 1 FROM capture_attempts a
                         WHERE a.token = m.capture_token
                           AND a.resolved_at IS NULL
                      )
                RETURNING *
                """)
            .execute(Tuple.of(captureToken, company, title, submittedBy, companyId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /**
     * The same person, whether or not the company name was finished being typed.
     *
     * <p>The company test used to run one way only: {@code m.company ILIKE '%typed%'}, which asks
     * whether the STORED name contains what was just typed. That catches a stored "Lumenwerx Inc"
     * when somebody types "Lumenwerx". It cannot catch the opposite, and the opposite is what
     * actually happens.
     *
     * <p>The partial-search capture fires on a pause in typing, so it stores whatever was in the
     * box at that moment - "Lum". When the finished search arrives with "Lumenwerx", the old test
     * asked whether "Lum" contains "Lumenwerx", got no, and treated a manager it already had as a
     * new one. Production produced exactly that: id 8717 "Sourabh Setia" at "Lum" holding the
     * clean slug {@code sourabh-setia}, and id 8718 "Sourabh Setia" at "Lumenwerx" thirteen
     * seconds later, pushed onto {@code sourabh-setia-lumenwerx} because the base was taken.
     *
     * <p>So the containment is tested in both directions. The new direction uses {@code position}
     * rather than a built LIKE pattern, so a company name containing % or _ cannot act as a
     * wildcard, and it requires at least three stored characters - one or two letters would match
     * most employers on the site. An exact-side match still sorts first, so a fully typed company
     * is never beaten by a fragment.
     */
    public Future<RowSet<Row>> findCapturedByNameAndCompany(String fullName, String company) {
        String typed = company.trim();
        return db.preparedQuery(SELECT_BODY + """
                WHERE m.name ILIKE $1
                  AND (
                        m.company ILIKE $2
                     OR (
                          length(btrim(m.company)) >= 3
                          AND position(lower(btrim(m.company)) in lower($3)) > 0
                        )
                      )
                  AND m.approval_status IN ('approved', 'ghost', 'pending_approval')
                GROUP BY m.id, c.slug, c.industry, c.domain, c.brandfetch_icon_url
                ORDER BY (m.company ILIKE $2) DESC, m.reviews_count DESC, m.id ASC
                LIMIT 5
                """)
            .execute(Tuple.of(fullName, "%" + typed + "%", typed));
    }

    /**
     * Completes a company name that was captured before the person finished typing it.
     *
     * <p>The partial-search capture stores whatever was in the box at the pause - "Lum" for
     * "Lumenwerx" - and that fragment is what the manager is then listed under. Once the finished
     * search arrives and matches that row, the full name is known and the row should carry it.
     *
     * <p>Only ever lengthens, and only when the stored value is genuinely a fragment of the new
     * one: a manager legitimately listed at a shorter company is left alone. Pending rows only,
     * so nothing published is renamed underneath anybody.
     */
    public Future<Void> refineCapturedCompany(long managerId, String fullCompany, Long companyId) {
        return db.preparedQuery("""
                UPDATE managers
                   SET company = $2,
                       company_id = COALESCE($3, company_id),
                       updated_at = now()
                 WHERE id = $1
                   AND approval_status = 'pending_approval'
                   AND length(btrim(company)) < length(btrim($2))
                   AND position(lower(btrim(company)) in lower($2)) > 0
                """)
            .execute(Tuple.of(managerId, fullCompany.trim(), companyId))
            .mapEmpty();
    }

    /**
     * This searcher's own queued row for the same manager, left behind mid-typing.
     *
     * <p>A debounced search fires while the employer is still being typed, so one person looking
     * for "Bob Burgers at Netflix" queued a row at "Net" and then another at "Netflix" a second
     * later. {@code search()} cannot see that the first is the second: it matches with
     * {@code company ILIKE '%Netflix%'} against the stored {@code Net}, which tests containment in
     * one direction only, so the finished search reads as a different employer.
     *
     * <p>Same fragment test as {@link #findCapturedByNameAndCompany}, including the three-character
     * floor: one or two stored letters would match most employers on the site. {@code position}
     * rather than a built LIKE pattern, so a company name containing % or _ cannot act as a
     * wildcard.
     *
     * <p><b>Scoped to {@code search_created_by_user_id}, and that is the whole safety of it.</b>
     * Folding against any matching pending row is what once rewrote a stranger's half-typed
     * namesake to this submitter's company and handed it to their account. A row somebody else
     * queued is not this searcher's to refine, so it is not a candidate.
     */
    public Future<Optional<Long>> findOwnRefinableSearchPending(String fullName, String typedCompany,
                                                               UUID searcherId) {
        if (searcherId == null || fullName == null || typedCompany == null) {
            return Future.succeededFuture(Optional.empty());
        }
        return db.preparedQuery("""
                SELECT m.id
                  FROM managers m
                 WHERE m.search_created_by_user_id = $3
                   AND m.approval_status = 'pending_approval'
                   AND m.name ILIKE $1
                   AND length(btrim(m.company)) >= 3
                   AND length(btrim(m.company)) < length(btrim($2))
                   AND position(lower(btrim(m.company)) in lower($2)) > 0
                 ORDER BY m.created_at DESC
                 LIMIT 1
                """)
            .execute(Tuple.of(fullName, typedCompany.trim(), searcherId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next().getLong("id"))
                : Optional.<Long>empty());
    }

    public Future<RowSet<Row>> findByNameAndCompany(String fullName, String company) {
        return db.preparedQuery(SELECT_BODY + """
                WHERE m.name ILIKE $1
                  AND m.company ILIKE $2
                  AND m.approval_status IN ('approved', 'ghost')
                GROUP BY m.id, c.slug, c.industry, c.domain, c.brandfetch_icon_url
                ORDER BY m.reviews_count DESC, m.id ASC
                LIMIT 5
                """)
            .execute(Tuple.of(fullName, "%" + company.trim() + "%"));
    }

    public Future<Row> createSearchPending(String name, String company, String title,
                                           String country, String state, String city,
                                           String logoUrl, Long companyId, UUID searchCreatedByUserId) {
        return generatePendingSlug(name).compose(slug ->
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

    /**
     * The ghost INSERT, shared by the plain path and the serialised one below, so the column list
     * exists once.
     */
    private static final String GHOST_INSERT_SQL = """
            INSERT INTO managers
            (name, company, title, status, approval_status, country, state, city,
             overall_rating, reviews_count, category_averages, submitted_by,
             company_logo_url, company_id, slug, created_at, updated_at)
            VALUES ($1,$2,$3,'active','ghost',$4,$5,$6,
                    0,0,'{}'::jsonb,$7,
                    $8,$9,$10,now(),now())
            RETURNING *
            """;

    public Future<Row> createAutoApproved(String name, String company, String title,
                                          String country, String state, String city,
                                          UUID submittedBy, String logoUrl, Long companyId) {
        return generateUniqueSlug(name, company).compose(slug ->
            db.preparedQuery(GHOST_INSERT_SQL)
                .execute(Tuple.of(name, company.trim(), title.trim(), country.trim(), state, city, submittedBy, logoUrl, companyId, slug))
                .map(rows -> rows.iterator().next()));
    }

    /**
     * A ghost insert's result: the row, and whether this call is what wrote it.
     *
     * <p>{@code created} is false when another request had already published this manager, so the
     * row is theirs. The caller must then not seed a review onto it, and must not spend the
     * searcher's one-time slot, because this call published nothing.
     */
    public record GhostInsert(Row row, boolean created) {}

    /** Is there already a live manager under exactly this name at exactly this company? */
    private static final String EXISTING_PUBLIC_GHOST_SQL = """
            SELECT id FROM managers
             WHERE lower(btrim(name)) = lower(btrim($1))
               AND lower(btrim(company)) = lower(btrim($2))
               AND approval_status IN ('approved', 'ghost')
             ORDER BY id ASC
             LIMIT 1
            """;

    /**
     * Publishes a ghost, or returns the one another request published first.
     *
     * <p><b>The race.</b> Deciding to auto-create and then auto-creating were two separate
     * database conversations with nothing holding between them. Six people searching the same
     * unknown manager at the same instant all ran the matcher before any of them had inserted, so
     * all six concluded the manager did not exist, and all six went on to create it.
     *
     * <p><b>Why the unique index was not enough.</b> {@code managers_slug_idx} caught only the
     * narrowest version of this. Two racers collide there only if they compute the SAME slug,
     * which happens when both read the slug namespace before either insert lands. If the second
     * racer reads it just AFTER the first commits, the plain-name slug is taken,
     * {@link #generateUniqueSlug} appends the company, the slug differs, and the duplicate inserts
     * cleanly. A stress run of eight rounds of six simultaneous searches produced an exception in
     * one round from the collision, and in another round two live rows for one person.
     *
     * <p><b>Why not a unique constraint on the table.</b> Two different people who share a name
     * may both be managers at one company, and the directory has to be able to hold both. The
     * duplicate here is not a schema violation, it is one decision taken six times, so what needs
     * to be serialised is the decision.
     *
     * <p><b>How.</b> A transaction-scoped advisory lock keyed on this name and company, then the
     * existence check re-run INSIDE that lock, then the insert. Whoever holds the lock either
     * finds the manager already there and returns it, or creates it; the next caller through the
     * lock finds it. The lock is released by the transaction ending, with no cleanup to get wrong,
     * and it constrains nothing in the schema. A hash collision between two unrelated names costs
     * one brief wait and nothing else.
     *
     * <p>Only this read-then-write is wrapped. The surrounding ghost flow stays composed rather
     * than transactional, exactly as its own comments require: the slot claim, the seed review and
     * the recalculation are separate writes with a deliberate order, and nothing here changes it.
     *
     * <p>The slug fallback below is kept as a second line of defence. The lock makes a collision
     * between two racers for the SAME manager impossible, but two DIFFERENT names can still
     * slugify to one string, and that insert should not surface as a raw driver error either.
     */
    public Future<GhostInsert> createAutoApprovedOrExisting(String name, String company, String title,
                                                            String country, String state, String city,
                                                            UUID submittedBy, String logoUrl, Long companyId) {
        final String lockKey = (name == null ? "" : name.trim().toLowerCase())
                             + "|" + (company == null ? "" : company.trim().toLowerCase());

        return generateUniqueSlug(name, company).compose(slug ->
            ((Pool) db).withTransaction(conn ->
                conn.preparedQuery("SELECT pg_advisory_xact_lock(hashtext($1))")
                    .execute(Tuple.of(lockKey))
                    .compose(locked -> conn.preparedQuery(EXISTING_PUBLIC_GHOST_SQL)
                        .execute(Tuple.of(name, company.trim())))
                    .compose(existing -> {
                        Iterator<Row> it = existing.iterator();
                        if (it.hasNext()) {
                            // Somebody else got here first. Their id now, their full row below.
                            return Future.succeededFuture(
                                new ExistingOrInserted(null, it.next().getLong("id")));
                        }
                        return conn.preparedQuery(GHOST_INSERT_SQL)
                            .execute(Tuple.of(name, company.trim(), title.trim(), country.trim(),
                                              state, city, submittedBy, logoUrl, companyId, slug))
                            .map(rows -> new ExistingOrInserted(rows.iterator().next(), null));
                    })))
            .compose(outcome -> {
                if (outcome.inserted() != null) {
                    return Future.succeededFuture(new GhostInsert(outcome.inserted(), true));
                }
                // Re-read through findById so the row's shape matches the inserted one exactly.
                return findById(outcome.existingId())
                    .map(found -> new GhostInsert(found.orElseThrow(() ->
                        new IllegalStateException(
                            "manager " + outcome.existingId() + " vanished during ghost creation")),
                        false));
            })
            .recover(err -> {
                if (!isSlugCollision(err)) return Future.failedFuture(err);
                return findByNameAndCompany(name, company).compose(rows -> {
                    Iterator<Row> it = rows.iterator();
                    if (!it.hasNext()) return Future.failedFuture(err);
                    return findById(it.next().getLong("id"))
                        .compose(winner -> winner.isPresent()
                            ? Future.succeededFuture(new GhostInsert(winner.get(), false))
                            : Future.failedFuture(err));
                });
            });
    }

    /** One of the two, never both: the row this call inserted, or the id of the one already there. */
    private record ExistingOrInserted(Row inserted, Long existingId) {}

    /** True when a write was rejected by the unique index on {@code managers.slug}. */
    private static boolean isSlugCollision(Throwable err) {
        return PgErrors.isUniqueViolationOn(err, "slug");
    }

    /**
     * Records a manager somebody began adding, for an admin to look at. Never published.
     *
     * <p>The add-manager form posts here as soon as its first step is valid, so the work is not
     * lost if the person abandons the flow. That capture used to insert {@code 'ghost'}, which is
     * live and publicly visible: typing into a form put a manager on the site, before any rating,
     * any submit click, and any check. It also captured whatever was in the company box at that
     * instant, which is why the directory grew entries at companies called "Mi" and "Ju".
     *
     * <p>It now lands in the admin queue as {@code pending_approval}. Capturing a drop-off and
     * publishing it are different things, and only the first was ever the point.
     *
     * <p>Not to be confused with {@link #createAutoApproved}, which is the {@code /find} path and
     * is <em>meant</em> to be live: that one fires on a deliberate search, once per user ever,
     * tracked by {@code users.has_auto_created_manager}.
     */
    public Future<Row> createCapturedDraft(String name, String company, String title,
                                   String country, String state, String city, String logoUrl, Long companyId) {
        return generatePendingSlug(name).compose(slug ->
            insertCapturedDraft(null, name, company, title, country, state, city, logoUrl,
                                companyId, slug, null));
    }

    /**
     * The same capture, written on a transaction the caller holds and stamped with the attempt
     * that produced it.
     *
     * <p>PHASE 1. {@code captureToken} is how this row is found again. The submission that
     * follows adopts <em>this</em> manager because it presents the same token, rather than
     * because some row resembles what was typed - see {@link #adoptCaptureByToken}.
     *
     * <p>{@code conn} is not optional in spirit even though it is in type: the ledger row and this
     * insert have to commit together, or the token is consumed with nothing behind it.
     *
     * <p><b>The slug arrives already decided, and that is not tidiness.</b> Generating it reads
     * {@code managers} on whatever client it is given; asking the POOL for that while holding a
     * transaction borrows a second connection, and enough concurrent captures doing that at once
     * exhaust the pool with every holder waiting for a connection none of them will release.
     * {@code createManager} generates its slug outside its transaction for the same reason.
     */
    public Future<Row> insertCapturedDraft(io.vertx.sqlclient.SqlClient conn,
                                   String name, String company, String title,
                                   String country, String state, String city, String logoUrl,
                                   Long companyId, String slug, UUID captureToken) {
        io.vertx.sqlclient.SqlClient target = conn == null ? db : conn;
        return target.preparedQuery("""
                    INSERT INTO managers
                    (name, company, title, status, approval_status, country, state, city,
                     overall_rating, reviews_count, category_averages,
                     company_logo_url, company_id, slug, capture_token, created_at, updated_at)
                    VALUES ($1,$2,$3,'active','pending_approval',$4,$5,$6,
                            0,0,'{}'::jsonb,
                            $7,$8,$9,$10,now(),now())
                    RETURNING *
                    """)
            .execute(Tuple.of(name, company.trim(), title.trim(), country.trim(), state, city, logoUrl, companyId, slug, captureToken))
            .map(rows -> rows.iterator().next());
    }

    /** Creates a pending_approval manager record (no auth required) for drop-off draft capture. */
    public Future<Row> createPending(String name, String company, String title,
                                      String status, String country, String state,
                                      String logoUrl, Long companyId) {
        /*
          No submitter, deliberately.

          This overload is the drop-off capture, which runs for somebody who has not signed in.
          An ownerless pending row is refused to EVERYBODY by enforceSubmitterAccess until a
          moderator approves it, which is the intended treatment for a row nobody has claimed.
        */
        return createPending(db, name, company, title, status, country, state, logoUrl, companyId,
                             null);
    }

    /**
     * The same insert, on a CALLER'S CONNECTION, so it can live inside somebody else's transaction.
     *
     * <p>Path 3 of the role-aware submission needs exactly this: the contributor said "none of
     * these people", so the manager and the rating have to be written together or not at all. On
     * the pool they were two transactions, and a submission that failed after the manager was
     * created left a pending person nobody had rated. See issue 34.
     *
     * <p>The slug is still resolved on the pool. A slug race is caught by the unique index exactly
     * as it was before, and reading availability inside the transaction would make it no more
     * reliable: the answer can go stale between the read and the insert either way.
     */
    public Future<Row> createPending(SqlClient conn, String name, String company, String title,
                                      String status, String country, String state,
                                      String logoUrl, Long companyId, UUID submittedBy) {
        return generatePendingSlug(name).compose(slug ->
            conn.preparedQuery("""
                    INSERT INTO managers
                    (name, company, title, status, approval_status, country, state,
                     overall_rating, reviews_count, category_averages,
                     company_logo_url, company_id, slug, submitted_by, created_at, updated_at)
                    VALUES ($1,$2,$3,$4,'pending_approval',$5,$6,
                            0,0,'{}'::jsonb,
                            $7,$8,$9,$10,now(),now())
                    RETURNING *
                    """)
                // Null-safe: a captured partial search may carry no country (geolocation failed)
                // and no title, and those absences must not cost us the record.
                .execute(Tuple.of(name,
                                  company != null ? company.trim() : "",
                                  title   != null ? title.trim()   : "",
                                  status,
                                  country != null ? country.trim() : "",
                                  state, logoUrl, companyId, slug)
                             .addValue(submittedBy))
                .map(rows -> rows.iterator().next()));
    }

    // ── Find-or-attach helpers ────────────────────────────────────────────────

    /** Returns all non-rejected managers for an exact company name (case-insensitive). */
    /**
     * The fuzzy path's candidate pool, scoped by company identity rather than by company text.
     *
     * <p>Same shape as {@link #findByCompanyExact}, taking the id scope a search resolved - the
     * selected company plus its descendants - so a near-miss name is checked against the whole
     * group rather than against whichever spelling of the employer the row happens to carry.
     */
    public Future<RowSet<Row>> findInCompanies(java.util.List<Long> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) {
            return db.preparedQuery("SELECT 1 WHERE false").execute();
        }
        return db.preparedQuery("""
                SELECT id, name, approval_status
                FROM managers
                WHERE company_id = ANY($1)
                  AND approval_status NOT IN ('rejected')
                ORDER BY reviews_count DESC NULLS LAST
                LIMIT 50
                """)
            .execute(Tuple.of((Object) companyIds.toArray(new Long[0])));
    }

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

    /**
     * Stamps the submitter on a row that has none, and returns the row either way.
     *
     * <p>For the attach path. A capture carries no {@code submitted_by} - nobody had submitted
     * anything when it was written - and if a submission attaches to one instead of adopting it,
     * the row stays ownerless. {@code enforceSubmitterAccess} refuses an ownerless pending row to
     * everybody, so the person who just filled in the form is told the manager does not exist.
     *
     * <p>COALESCE, never an overwrite: a manager somebody else submitted keeps its original
     * submitter. This only fills a gap.
     */
    public Future<Optional<Row>> claimSubmitterIfUnowned(long managerId, UUID userId) {
        if (userId == null) return findByIdFlat(managerId);
        return db.preparedQuery("""
                UPDATE managers
                   SET submitted_by = COALESCE(submitted_by, $1), updated_at = now()
                 WHERE id = $2
                RETURNING *
                """)
            .execute(Tuple.of(userId, managerId))
            .map(rs -> rs.iterator().hasNext() ? Optional.of(rs.iterator().next()) : Optional.empty());
    }

    /**
     * Enriches a ghost manager with the detail an add-manager submission supplies, WITHOUT
     * touching anything that says who the manager is.
     *
     * <p>PHASE 0 of the manager-identity redesign. This statement used to read
     * {@code SET name = $1, title = $2, ..., linkedin_url = $5}, and its caller attaches a review
     * to whichever existing manager at that company has a name within Damerau-Levenshtein 1 of
     * the one typed. So rating "Emma Davies" renamed a live, publicly visible "Emma Davis",
     * retitled her, and wrote a stranger's LinkedIn URL onto her profile - and
     * {@code syncSlugToName} then moved her URL to match. One person's spelling rewrote who a
     * published page says it is about, with no review and no record of who did it.
     *
     * <p>A name, a title and a LinkedIn URL are identity: together they assert which real human
     * the page is about. A submission may no longer write any of them. Renaming still exists as
     * {@link #adminEdit}, which is the reviewed path and records the previous values.
     *
     * <p>What it still writes is detail that is not an identity claim: employment status, country,
     * and the company logo. {@code submitted_by} is COALESCEd, never overwritten, so a manager
     * somebody else submitted keeps its original submitter and only a gap is filled.
     */
    public Future<Optional<Row>> updateForAttach(long id, String status, String country,
                                                  String logoUrl, UUID submittedBy) {
        return db.preparedQuery("""
                UPDATE managers
                SET status = $1, country = $2, company_logo_url = $3,
                    submitted_by = COALESCE(submitted_by, $4),
                    updated_at = now()
                WHERE id = $5
                RETURNING *
                """)
            .execute(Tuple.of(status, country, logoUrl, submittedBy, id))
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

    /**
     * Accepted employment whose stored status does not follow from the record itself.
     *
     * <p>Delegates to the one place the rules live. Exposed here because the maintenance sweep
     * talks to repositories and the resolver is where the query belongs.
     */
    public Future<java.util.List<Long>> driftingEmploymentStatuses() {
        return EmploymentStatusResolver.drifting(db);
    }

    /**
     * Deletes one fake (seed_*) manager, fewest reviews first, and all its cascaded data.
     *
     * <h2>Why the selection carries five NOT EXISTS clauses</h2>
     *
     * <p>Deleting a manager cascades to {@code career_history}, and several tables refuse to let
     * that happen: {@code employment_status_events} and {@code claim_resolution_events} and
     * {@code role_corrections} reference roles with ON DELETE RESTRICT, and
     * {@code role_claims.manager_id} and {@code reviews.original_manager_id} are ON DELETE NO
     * ACTION. All of them are deliberate. This project's first principle is that history is
     * preserved, and deleting a manager must not erase the record that their employment was ever
     * resolved, corrected or disputed.
     *
     * <p>Without these clauses the consequence is worse than a failed delete. The ordering is
     * deterministic, so the same manager is chosen every time: the job would pick it, be refused,
     * print one line to stderr, and pick the same one again on the next call, for ever, while the
     * seed population it exists to drain never shrinks by a single row. A cleanup that cannot
     * delete anything and reports a one-line warning is indistinguishable from one that is
     * working.
     *
     * <p>So the query asks for a manager it can actually delete. One whose history makes deletion
     * impermissible is skipped deliberately, and the next candidate is tried instead.
     */
    public void deleteFakeManagerInBackground() {
        db.query("""
                SELECT m.id FROM managers m
                WHERE m.external_id LIKE 'seed_%'
                  AND m.approval_status IN ('approved', 'ghost')
                  AND NOT EXISTS (
                        SELECT 1 FROM role_claims rc
                         WHERE rc.manager_id = m.id OR rc.original_manager_id = m.id
                        UNION ALL
                        SELECT 1 FROM reviews r WHERE r.original_manager_id = m.id
                        UNION ALL
                        SELECT 1 FROM employment_status_events e
                          JOIN career_history ch ON ch.id = e.role_id
                         WHERE ch.manager_id = m.id
                        UNION ALL
                        SELECT 1 FROM claim_resolution_events ce
                          JOIN career_history ch ON ch.id IN (ce.from_role_id, ce.to_role_id)
                         WHERE ch.manager_id = m.id
                        UNION ALL
                        SELECT 1 FROM role_corrections rk
                          JOIN career_history ch ON ch.id = rk.role_id
                         WHERE ch.manager_id = m.id)
                ORDER BY COALESCE(m.reviews_count, 0) ASC, m.created_at ASC
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
    /**
     * The live manager already holding the clean slug this pending row wants, if there is one.
     *
     * <p>Approving a pending row moves it out of the {@code -pending} namespace and onto the name
     * a reader would expect. Usually that name is free. When it is not, somebody published is
     * already on it, and that is a decision rather than a collision: the same person entered
     * twice, or two different people who share a name. Only an admin can tell which, so this
     * reports the conflict instead of quietly appending something.
     */
    public Future<Optional<Row>> findLiveHolderOfSlug(String slug) {
        return db.preparedQuery("""
                SELECT m.id, m.name, m.company, m.slug, m.approval_status,
                       m.reviews_count, m.overall_rating
                  FROM managers m
                 WHERE m.slug = $1
                   AND m.approval_status IN ('approved', 'ghost')
                 LIMIT 1
                """)
            .execute(Tuple.of(slug))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /**
     * The slug a name resolves to, before anything is asked about availability.
     *
     * <p>The single definition of that. It was written out three times - here, in
     * {@link #generateUniqueSlug} and in {@link #generatePendingSlug} - each repeating
     * {@code toBaseSlug} plus the same "manager" fallback for a name that slugifies to nothing.
     * Three copies of one rule is three places for it to drift.
     *
     * <p>Note that {@code V82__pending_slug_namespace.sql} reimplements this in SQL, because a
     * migration cannot call Java. That copy is unavoidable and is commented there; it is the only
     * one that should exist.
     */
    public String cleanSlugFor(String name) {
        String raw = toBaseSlug(name == null ? "" : name.trim());
        return raw.isEmpty() ? "manager" : raw;
    }

    /**
     * Moves a manager onto a new slug, recording where it used to live.
     *
     * <p>Used at approval, when a row leaves the pending namespace. The old URL was never public -
     * only the submitter could open it - but the history row costs nothing and means a bookmark
     * somebody kept still resolves.
     */
    public Future<Void> reslug(long managerId, String newSlug) {
        return findSlugs(managerId).compose(before -> {
            /*
                History only when there is a URL to remember.

                manager_url_history.company_slug is NOT NULL, and a pending manager may have no
                company attached yet - company_id is nullable precisely for rows in this state.
                Recording nothing is right anyway: with no company slug there was never a nested
                URL for anyone to have bookmarked.
            */
            String oldCompanySlug = before.map(r -> r.getString("company_slug")).orElse(null);
            String oldManagerSlug = before.map(r -> r.getString("slug")).orElse(null);
            Future<Void> history = (oldCompanySlug != null && oldManagerSlug != null)
                ? recordUrlHistory(managerId, oldCompanySlug, oldManagerSlug)
                : Future.succeededFuture();
            return history.compose(v -> db.preparedQuery(
                    "UPDATE managers SET slug = $2, updated_at = now() WHERE id = $1")
                .execute(Tuple.of(managerId, newSlug))
                .mapEmpty());
        });
    }

    /**
     * Live managers sitting on a company-suffixed slug whose plain name is now free.
     *
     * <p>A slug is decided once, at INSERT, against whatever held the name at that instant - and
     * what held it was often a row no visitor could reach. A draft captured mid-typing, or a
     * manager later rejected, took {@code silvea-chowdhury}, so the real person was pushed onto
     * {@code silvea-chowdhury-acs-athletics} and stayed there. No merge was involved; the name was
     * simply taken at the wrong moment by something invisible.
     *
     * <p>Once the pending and rejected namespaces free those names, the plain slug is available
     * again - but nothing goes back to check. This finds exactly those rows.
     *
     * <p>Deliberately not a migration. Moving a live slug changes a public, possibly indexed URL,
     * so it is offered to an admin to run when the timing suits rather than firing on deploy.
     * Conservative: only rows whose plain name is genuinely unheld, so two different people who
     * share a name are never offered.
     */
    public Future<RowSet<Row>> findSlugReclaimCandidates(int limit) {
        return db.preparedQuery("""
                SELECT m.id, m.name, m.company, m.slug, m.reviews_count,
                       regexp_replace(lower(btrim(m.name)), '[^a-z0-9]+', '-', 'g') AS clean_slug
                  FROM managers m
                 WHERE m.approval_status IN ('approved', 'ghost')
                   AND m.slug IS NOT NULL
                   AND regexp_replace(lower(btrim(m.name)), '[^a-z0-9]+', '-', 'g') <> ''
                   AND m.slug <> regexp_replace(lower(btrim(m.name)), '[^a-z0-9]+', '-', 'g')
                   AND NOT EXISTS (
                         SELECT 1 FROM managers h
                          WHERE h.slug = regexp_replace(lower(btrim(m.name)), '[^a-z0-9]+', '-', 'g')
                       )
                 ORDER BY m.reviews_count DESC NULLS LAST, m.id
                 LIMIT $1
                """)
            .execute(Tuple.of(limit));
    }

    /** The namespace hidden rows live in, once an admin has taken them out of circulation. */
    public static final String REJECTED_SLUG_SUFFIX = "-rejected";

    /**
     * Moves a hidden row's slug out of the public namespace, freeing the name.
     *
     * <p>A rejected or removed manager is invisible on every public surface, but it went on
     * holding its slug for ever - so a name an admin had explicitly taken down could never be used
     * by the real person it belonged to. Same defect as pending rows squatting on clean names,
     * one status along.
     *
     * <p>Idempotent, and guarded on the current slug so a row already parked is left alone. The id
     * breaks ties: two rejected managers of the same name cannot collide.
     */
    public Future<Void> parkSlugAsRejected(long managerId) {
        return db.preparedQuery("""
                UPDATE managers
                   SET slug = CASE
                                WHEN EXISTS (SELECT 1 FROM managers x
                                              WHERE x.slug = managers.slug || $2 AND x.id <> managers.id)
                                THEN managers.slug || $2 || '-' || managers.id
                                ELSE managers.slug || $2
                              END,
                       updated_at = now()
                 WHERE id = $1
                   AND slug IS NOT NULL
                   AND position($2 in slug) = 0
                """)
            .execute(Tuple.of(managerId, REJECTED_SLUG_SUFFIX))
            .mapEmpty();
    }

    /** The namespace pending rows live in. Lowercase, because every slug on the site is. */
    public static final String PENDING_SLUG_SUFFIX = "-pending";

    /**
     * A slug for a row the public cannot reach.
     *
     * <p>Slug allocation asks {@code SELECT 1 FROM managers WHERE slug = $1} - every row blocks a
     * name, including rows no visitor can ever open. So a half-typed draft took
     * {@code sourabh-setia} and the real manager, published seconds later, was pushed onto
     * {@code sourabh-setia-lumenwerx}. The mangled slug was not a naming decision; it was a
     * collision with something invisible.
     *
     * <p>Putting pending rows in their own namespace removes the collision rather than handling
     * it. {@code sourabh-setia} stays free for whoever is actually published, with no special case
     * in the allocator: nothing pending can hold it any more.
     *
     * <p>The company is not used here. A pending slug is scaffolding - it is replaced with the
     * real one at approval - so there is nothing to gain from making it descriptive, and plenty
     * to lose from baking in a company name captured halfway through being typed.
     */
    public Future<String> generatePendingSlug(String name) {
        String base = cleanSlugFor(name) + PENDING_SLUG_SUFFIX;
        return slugAvailable(base).compose(free ->
            free ? Future.succeededFuture(base) : trySlug(base, 2));
    }

    public Future<String> generateUniqueSlug(String name, String company) {
        final String base = cleanSlugFor(name);
        return slugAvailable(base).compose(free -> {
            if (free) return Future.succeededFuture(base);
            String companyPart = toBaseSlug(company == null ? "" : company.trim());
            String withCompany = companyPart.isEmpty() ? base : base + "-" + companyPart;
            return trySlug(withCompany, 2);
        });
    }

    /**
     * Gives a manager back the plain slug for its name, when nothing else holds it any more.
     *
     * <p>{@link #generateUniqueSlug} appends the company when the plain name slug is taken, so a
     * second "Poonam Yadav" becomes {@code poonam-yadav-aditya-birla-group}. The slug is decided
     * once, at INSERT, and nothing recomputed it - so merging the two, which is precisely the act
     * that frees the plain slug, left the survivor wearing a collision-breaker for a collision
     * that no longer existed.
     *
     * <p>Called after a merge commits. It is deliberately conservative:
     *
     * <ul>
     *   <li>it does nothing unless the plain slug is genuinely unheld, so a different person of
     *       the same name keeps theirs;
     *   <li>it records the outgoing {@code (company_slug, manager_slug)} pair in
     *       {@code manager_url_history} <em>before</em> moving, so links already shared - and
     *       anything a search engine indexed - resolve through the history table instead of 404ing.
     *       Both backends read that table, and {@code managers} is shared between them;
     *   <li>the UPDATE is guarded on the old slug and tolerates a unique violation, so two merges
     *       racing for the same freed slug leave one winner rather than an error.
     * </ul>
     *
     * @return true when the slug moved
     */
    public Future<Boolean> reclaimBaseSlug(long managerId) {
        return db.preparedQuery("""
                SELECT m.name, m.slug, c.slug AS company_slug
                FROM managers m
                LEFT JOIN companies c ON c.id = m.company_id
            LEFT JOIN company_locations loc ON loc.id = m.company_location_id
                WHERE m.id = $1
                """)
            .execute(Tuple.of(managerId))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) return Future.succeededFuture(false);
                Row row = rows.iterator().next();
                String name = row.getString("name");
                String current = row.getString("slug");
                if (name == null || current == null) return Future.succeededFuture(false);

                String base = toBaseSlug(name.trim());
                if (base.isEmpty() || base.equals(current)) return Future.succeededFuture(false);

                String oldCompanySlug = row.getString("company_slug");
                /*
                  The duplicate usually IS the holder.

                  A merge retires the absorbed row rather than deleting it, and a retired row keeps
                  its slug - so the plain slug is still taken by the very manager we just merged
                  away, and the survivor could never reclaim it. Park that row's slug so it stops
                  holding the name it no longer represents.

                  Narrow on purpose: only a row whose merged_into points at THIS manager is parked.
                  A different person of the same name is untouched, and nothing is parked outside
                  the merge that just happened. The parked row is invisible anyway - every public
                  surface filters approval_status to approved and ghost.
                */
                return releaseSlugFromMergedDuplicate(base, managerId)
                    .compose(v -> slugAvailable(base))
                    .compose(free -> {
                    if (!free) return Future.succeededFuture(false);
                    Future<Void> history = oldCompanySlug == null
                        ? Future.succeededFuture()
                        : recordUrlHistory(managerId, oldCompanySlug, current);
                    return history.compose(v -> db.preparedQuery(
                            "UPDATE managers SET slug = $1, updated_at = now() WHERE id = $2 AND slug = $3")
                        .execute(Tuple.of(base, managerId, current))
                        .map(res -> res.rowCount() > 0)
                        // Another merge took the same freed slug between the check and the write.
                        // Keeping the one we have is the correct outcome, not an error.
                        .recover(err -> Future.succeededFuture(false)));
                });
            });
    }

    /**
     * Moves a manager onto the slug their CURRENT name implies, recording the URL being left.
     *
     * <p>A slug is decided once, at INSERT - {@code managers_auto_slug_trg} is BEFORE INSERT only -
     * so an admin correcting a misspelled name fixed the name, the title, the company and every
     * snapshot of them, and left the URL still spelling the mistake. The profile for "Jonathon
     * Smyth", corrected to "Jonathan Smith", stayed at {@code /manager/jonathon-smyth} for ever.
     *
     * <p>This is the same operation {@link #reclaimBaseSlug} performs after a merge, with one
     * difference: a merge only ever wants the PLAIN name and declines anything else, because its
     * whole purpose is shedding a collision-breaker. Here the name itself changed, so the old slug
     * is wrong whatever happens, and falling back to the company-suffixed form is better than
     * keeping a URL that spells a different person's name. {@link #generateUniqueSlug} decides
     * that, exactly as it does at creation, so a corrected name lands where a correct name would
     * have landed in the first place.
     *
     * <p><b>The old URL keeps working.</b> {@link #reslug} writes the outgoing
     * {@code (company_slug, manager_slug)} pair into {@code manager_url_history} before moving,
     * and {@link #findByOldUrl} resolves it, so links already shared and anything a search engine
     * indexed redirect rather than 404. That is the whole reason this reuses reslug instead of
     * writing its own UPDATE.
     *
     * <p>Does nothing when the slug already matches the name, so an edit that leaves the name
     * alone cannot move a URL.
     *
     * @return the slug after this call, whether or not it moved; null when no such manager
     */
    public Future<String> reslugForCurrentName(long managerId) {
        return db.preparedQuery("SELECT name, company, slug FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) return Future.succeededFuture(null);
                Row row = rows.iterator().next();
                String name    = row.getString("name");
                String company = row.getString("company");
                String current = row.getString("slug");
                if (name == null || current == null) return Future.succeededFuture(current);
                // Already correct. Checked against the PLAIN form first so a manager legitimately
                // wearing "<name>-<company>" is not dragged off it on an unrelated edit.
                if (cleanSlugFor(name).equals(current)) return Future.succeededFuture(current);
                return generateUniqueSlug(name, company).compose(candidate ->
                    candidate.equals(current)
                        ? Future.succeededFuture(current)
                        : reslug(managerId, candidate).map(v -> candidate));
            });
    }

    /** Parks the slug of a row merged into {@code survivorId}, so the survivor can take it back. */
    private Future<Void> releaseSlugFromMergedDuplicate(String slug, long survivorId) {
        return db.preparedQuery("""
                UPDATE managers
                   SET slug = slug || '-merged-' || id, updated_at = now()
                 WHERE slug = $1 AND merged_into = $2
                """)
            .execute(Tuple.of(slug, survivorId))
            .mapEmpty();
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
        return findBySlug(slug, null);
    }

    /**
     * The profile by slug, with the reviews this VIEWER may see.
     *
     * <p><b>The viewer parameter is not optional any more, and that is what was wrong here.</b>
     * This method reuses {@code GET_BY_ID_SQL} with its WHERE swapped, and that statement gained a
     * second parameter when "show a viewer their own held reviews" was added. The swap was updated;
     * the argument list was not. So every by-slug lookup failed:
     *
     * <pre>
     *   The number of parameters to execute should be consistent with the expected number of
     *   parameters = [2] but the actual number is [1]
     * </pre>
     *
     * <p>By-slug is the canonical URL, which is what every shared link uses. Caught by
     * SlugIntegrationTest and CareerHistoryAdminIntegrationTest, both of which were red before
     * this fix and are the regression coverage for it.
     *
     * <p>The tuple is built with typed adders rather than {@code Tuple.of}, so a null viewer keeps
     * the arity at two and the driver is told the column's type instead of inferring it.
     */
    public Future<Optional<Row>> findBySlug(String slug, UUID viewerId) {
        return db.preparedQuery(GET_BY_ID_SQL.replace("WHERE m.id = $1", "WHERE m.slug = $1"))
            .execute(Tuple.tuple().addString(slug).addUUID(viewerId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** Returns the manager's own slug and current company slug — used to snapshot URL before a company change. */
    public Future<Optional<Row>> findSlugs(long managerId) {
        return db.preparedQuery("""
                SELECT m.slug, c.slug AS company_slug
                FROM managers m
                LEFT JOIN companies c ON c.id = m.company_id
            LEFT JOIN company_locations loc ON loc.id = m.company_location_id
                WHERE m.id = $1
                """)
            .execute(Tuple.of(managerId))
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

    // ── Name-first candidate discovery (see ManagerMatcher and section 5a) ────

    /**
     * PHASE ONE: every public manager sharing a name token, id and name only.
     *
     * <p>Uncapped and never tiered. Running an exact-match query first and widening only when it
     * returns nothing would hide real candidates: searching "Aron Yip" while both "Aron Yip" and
     * "Aaron Yip" exist would find the first and never look for the second, and the rule is that
     * every credible public candidate is discoverable.
     *
     * <p>Only two columns come back, so even a common surname costs little to fetch. Full rows and
     * employment records are loaded afterwards, for the few that actually classified as a match.
     *
     * <p>{@code manager_name_tokens} is the SAME function the GIN index is built on, and agrees
     * character for character with {@code ManagerMatcher.norm}. A contract test holds them together:
     * if they drift, this query stops returning rows the matcher would have matched, and the search
     * silently starts creating duplicates again.
     */
    public Future<RowSet<Row>> findNameCandidates(java.util.List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return db.preparedQuery("SELECT 1 WHERE false").execute();
        return db.preparedQuery("""
                SELECT m.id, m.name
                  FROM managers m
                 WHERE m.approval_status IN ('approved', 'ghost')
                   AND public.manager_name_tokens(m.name) && $1::text[]
                """)
            .execute(Tuple.tuple().addArrayOfString(tokens.toArray(new String[0])));
    }

    /**
     * Every employment record for these managers: the CURRENT role unioned with career history.
     *
     * <p>The union is not optional. {@code createAutoApproved}, {@code createSearchPending} and
     * {@code createCapturedDraft} write no career row at all, so a search-created manager's only
     * employment is the one on {@code managers}. Reading career history alone would make every
     * ghost look unemployed and every search look like a miss.
     *
     * <p>Company and title arrive together, per record, because they are only meaningful as a pair.
     */
    public Future<RowSet<Row>> findEmployments(java.util.List<Long> managerIds) {
        if (managerIds == null || managerIds.isEmpty()) return db.preparedQuery("SELECT 1 WHERE false").execute();
        return db.preparedQuery("""
                SELECT m.id AS manager_id, m.company_id, m.company, m.title
                  FROM managers m
                 WHERE m.id = ANY($1)
                UNION ALL
                SELECT ch.manager_id, ch.company_id, ch.company, ch.title
                  FROM career_history ch
                 WHERE ch.manager_id = ANY($1)
                """)
            .execute(Tuple.tuple().addArrayOfLong(managerIds.toArray(new Long[0])));
    }


    /**
     * Employments a contributor may SELECT, with the identity each selection needs.
     *
     * <p>Separate from {@link #findEmployments}, which answers "does what they typed match this
     * person at all" and therefore needs only company and title. A contributor choosing which job
     * they worked under needs more: the {@code career_history.id} to put in
     * {@code role_claims.selected_role_id}, the state so the form can say whether a rating will
     * publish at once or wait for a moderator, and the period so two spells in the same job are
     * distinguishable.
     *
     * <p><b>The manager's current role has no {@code career_history} row</b>, and that is not an
     * oversight: {@code createAutoApproved}, {@code createSearchPending} and
     * {@code createCapturedDraft} write none. It is returned with a null {@code role_id} and is
     * therefore NOT selectable as a resolved employment - choosing it is the "employment missing"
     * path, which proposes the role. Returning it anyway matters, because it is often the only
     * employment a search-created manager has, and hiding it would make the person look like they
     * had never worked anywhere.
     *
     * <p>Rejected and superseded roles are excluded. A contributor must not be offered an
     * employment a moderator has already refused, which is the reuse that quietly undid a
     * rejection.
     */

    /**
     * Whether this manager is one a contributor may legitimately rate.
     *
     * <p>The same visibility rule the public directory uses: {@code approved} or {@code ghost}, and
     * nothing else. A {@code pending_approval} row is in the admin queue and a {@code rejected} one
     * has been refused, so neither is reachable from any public surface - which means an id naming
     * one did not come from a contributor looking at the product.
     *
     * <p>Exists because a manager id in a request body is an assertion, not a fact. Our own form
     * putting it there is no evidence: anybody can post that body.
     */
    public Future<Boolean> findPubliclyVisibleId(long managerId) {
        return db.preparedQuery(
                "SELECT 1 FROM managers WHERE id = $1 "
                + "AND approval_status IN ('approved','ghost')")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.rowCount() == 1);
    }

    public Future<RowSet<Row>> findSelectableEmployments(java.util.List<Long> managerIds) {
        if (managerIds == null || managerIds.isEmpty()) {
            return db.preparedQuery("SELECT 1 WHERE false").execute();
        }
        return db.preparedQuery("""
                SELECT ch.manager_id,
                       ch.id          AS role_id,
                       ch.company_id,
                       ch.company,
                       ch.title,
                       ch.state,
                       ch.start_date,
                       ch.end_date,
                       ch.period_known,
                       FALSE          AS is_current_role_without_record,
                       /*
                         THE EMPLOYER'S OWN LOGO IDENTITY, so a selection screen can draw the
                         company rather than the first letter of its name. Guessing a domain from
                         the name is what makes a wrong logo look like a working one, so the three
                         fields the renderer actually resolves are carried instead.
                       */
                       cc.domain              AS company_domain,
                       cc.logo_url            AS company_logo_url,
                       cc.brandfetch_icon_url AS company_brandfetch_icon_url
                  FROM career_history ch
                  LEFT JOIN companies cc ON cc.id = ch.company_id
                 WHERE ch.manager_id = ANY($1)
                   AND ch.state IN ('proposed','accepted')
                UNION ALL
                SELECT m.id,
                       NULL::BIGINT   AS role_id,
                       m.company_id,
                       m.company,
                       m.title,
                       'accepted'     AS state,
                       NULL::DATE     AS start_date,
                       NULL::DATE     AS end_date,
                       FALSE          AS period_known,
                       TRUE           AS is_current_role_without_record,
                       mc.domain              AS company_domain,
                       COALESCE(m.company_logo_url, mc.logo_url) AS company_logo_url,
                       mc.brandfetch_icon_url AS company_brandfetch_icon_url
                  FROM managers m
                  LEFT JOIN companies mc ON mc.id = m.company_id
                 WHERE m.id = ANY($1)
                   AND NOT EXISTS (
                       SELECT 1 FROM career_history c2
                        WHERE c2.manager_id = m.id
                          AND c2.company_id IS NOT DISTINCT FROM m.company_id
                          AND normalise_title(c2.title) = normalise_title(m.title)
                          AND c2.state IN ('proposed','accepted'))
                 ORDER BY manager_id, role_id NULLS LAST, start_date NULLS LAST
                """)
            .execute(Tuple.tuple().addArrayOfLong(managerIds.toArray(new Long[0])));
    }

    /** Full rows for the managers that actually classified as candidates, ordered as given. */
    public Future<RowSet<Row>> findByIdsOrdered(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return db.preparedQuery("SELECT 1 WHERE false").execute();
        return db.preparedQuery(SELECT_BODY + """
                WHERE m.id = ANY($1)
                GROUP BY m.id, c.slug, c.industry, c.domain, c.brandfetch_icon_url
                ORDER BY array_position($1, m.id)
                """)
            .execute(Tuple.tuple().addArrayOfLong(ids.toArray(new Long[0])));
    }

    /**
     * EXISTENCE detection over rows that are NOT publicly visible.
     *
     * <p>Separate from {@link #findNameCandidates} on purpose. That one answers "what may we show?"
     * and is restricted to approved and ghost so the tile invariant holds. This one answers a
     * different question - "does this manager already exist in some form?" - and the answer must
     * include rows nobody can see, or a search would publish a ghost duplicating a manager already
     * sitting in the admin queue.
     *
     * <p>Nothing here is ever rendered. The statuses come back so the caller can apply the policy:
     *
     * <ul>
     *   <li><b>pending_approval</b> - somebody submitted this person and an admin has not decided
     *       yet. Publishing a ghost now would pre-empt that decision and create a duplicate, so the
     *       search queues a review instead.</li>
     *   <li><b>rejected</b> - an admin decided this record should not exist. Publishing a ghost
     *       would silently overturn that, so the search queues a review instead. It is NOT a
     *       permanent block: the request carries seen_since_review, so a person people keep
     *       searching for comes back to the admin with evidence rather than being refused forever.
     *       </li>
     * </ul>
     */
    public Future<RowSet<Row>> findNonPublicNameCandidates(java.util.List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return db.preparedQuery("SELECT 1 WHERE false").execute();
        return db.preparedQuery("""
                SELECT m.id, m.name, m.approval_status, m.search_created_by_user_id
                  FROM managers m
                 WHERE m.approval_status IN ('pending_approval', 'rejected')
                   AND public.manager_name_tokens(m.name) && $1::text[]
                """)
            .execute(Tuple.tuple().addArrayOfString(tokens.toArray(new String[0])));
    }

    /**
     * An admin judging a search request to describe a genuinely new person.
     *
     * <p>'approved' rather than 'ghost': a human decided this manager exists, which is a stronger
     * statement than the automatic publication a ghost represents. {@code submitted_by} records
     * WHICH admin, so the decision is attributable rather than appearing from nowhere; no
     * {@code search_created_by_user_id} is written, because nobody's search created this - a review
     * did, and notifications are keyed on that distinction.
     */
    public Future<Row> createApprovedFromRequest(String name, String company, String title,
                                                 Long companyId, UUID adminId) {
        return generateUniqueSlug(name, company).compose(slug ->
            db.preparedQuery("""
                    INSERT INTO managers
                    (name, company, title, status, approval_status, country,
                     overall_rating, reviews_count, category_averages, submitted_by,
                     company_id, slug, created_at, updated_at)
                    VALUES ($1,$2,$3,'active','approved','Canada',
                            0,0,'{}'::jsonb,$4,
                            $5,$6,now(),now())
                    RETURNING *
                    """)
                .execute(Tuple.of(name, company.trim(), title.trim(), adminId, companyId, slug))
                .map(rows -> rows.iterator().next()));
    }
}

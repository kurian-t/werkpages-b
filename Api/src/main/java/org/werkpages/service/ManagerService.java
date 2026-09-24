package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.GeoObservation;
import org.werkpages.repository.GeoObservationRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.ReviewSql;
import org.werkpages.repository.UserRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Business logic for managers and their reviews.
 * Coordinates ManagerRepository, ReviewRepository, UserRepository, and EditRepository.
 */
public class ManagerService {

    private static final String[] RATING_KEYS = {
        "Communication Style", "Perceived Approachability", "Perceived Clarity of Expectations",
        "Feedback Style", "Perceived Supportiveness", "Decision Making Style",
        "Organization and Planning Style", "Delegation Style",
        "Perceived Professional Demeanor", "Overall Working Experience"
    };
    private static final String[] RATING_KEYS_SNAKE = {
        "communication_style", "perceived_approachability", "perceived_clarity_of_expectations",
        "feedback_style", "perceived_supportiveness", "decision_making_style",
        "organization_and_planning_style", "delegation_style",
        "perceived_professional_demeanor", "overall_working_experience"
    };

    private final ManagerRepository          managerRepo;
    private final ReviewRepository           reviewRepo;
    private final UserRepository             userRepo;
    private final EditRepository             editRepo;
    private final ReportRepository           reportRepo;
    private final CompanyRepository          companyRepo;
    private final SqlClient                  db; // needed for transactions
    private final Function<String, String>   logoResolver;
    private final ProofOfWorkService         proofOfWork;
    private final GeoObservationRepository   geoObservations;
    private final DeclaredLocationResolver   declaredLocations;
    private final ReviewDisposition          reviewDisposition;
    private final LocationStatsProjector     locationStats;
    private final org.werkpages.repository.LocationCorpusRepository locationCorpus;
    private final org.werkpages.repository.AnonymousGhostSlotRepository ghostSlots;

    public ManagerService(ManagerRepository managerRepo, ReviewRepository reviewRepo,
                          UserRepository userRepo, EditRepository editRepo,
                          ReportRepository reportRepo, SqlClient db) {
        this(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, new CompanyRepository(db), db, company -> null);
    }

    public ManagerService(ManagerRepository managerRepo, ReviewRepository reviewRepo,
                          UserRepository userRepo, EditRepository editRepo,
                          ReportRepository reportRepo, SqlClient db,
                          Function<String, String> logoResolver) {
        this(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, new CompanyRepository(db), db, logoResolver);
    }

    public ManagerService(ManagerRepository managerRepo, ReviewRepository reviewRepo,
                          UserRepository userRepo, EditRepository editRepo,
                          ReportRepository reportRepo, CompanyRepository companyRepo,
                          SqlClient db, Function<String, String> logoResolver) {
        this.managerRepo  = managerRepo;
        this.reviewRepo   = reviewRepo;
        this.userRepo     = userRepo;
        this.editRepo     = editRepo;
        this.reportRepo   = reportRepo;
        this.companyRepo  = companyRepo;
        this.db           = db;
        this.logoResolver = logoResolver;
        // Built here rather than injected because every constructor overload would otherwise have
        // to thread it through, and it holds no state of its own.
        this.proofOfWork  = new ProofOfWorkService(
            new org.werkpages.repository.ProofChallengeRepository(db),
            new org.werkpages.repository.ConfidenceRepository(db));
        // Same reasoning as proofOfWork above: stateless, and injecting it would mean threading it
        // through every overload.
        this.geoObservations = new GeoObservationRepository(db);
        this.declaredLocations = new DeclaredLocationResolver(
            new org.werkpages.repository.CompanyLocationRepository(db));
        this.locationStats = new LocationStatsProjector();
        this.reviewDisposition = new ReviewDisposition();
        // Holds a DuckDB connection to the S3 corpus, opened lazily on first use. Constructing it
        // is free and cannot fail: an unreachable bucket or an unpublished release disables
        // suggestions rather than breaking anything that constructs a ManagerService, which
        // includes every unit test.
        this.locationCorpus = new org.werkpages.repository.LocationCorpusRepository();
        // Same construction as the others here: stateless beyond its client, and threading it
        // through every constructor overload would touch every test.
        this.ghostSlots = new org.werkpages.repository.AnonymousGhostSlotRepository(db);
    }

    // ── GET managers list ─────────────────────────────────────────────────────

    public Future<JsonObject> getManagers(int limit, int offset, String search, String company,
                                           String logoUrlResolver) {
        // logoUrlResolver is a Function<String,String> but we pass it in as a string
        // because CompanyLogoUtils is in RestApi. The handler passes resolved logos.
        // Actually we'll resolve in the handler; here we just return raw rows.
        int effectiveLimit  = Math.min(limit, 100);
        int effectiveOffset = Math.max(offset, 0);
        String searchPattern  = (search  != null && !search.isBlank())  ? "%" + search.trim()  + "%" : null;
        String companyPattern = (company != null && !company.isBlank()) ? "%" + company.trim() + "%" : null;

        if (searchPattern != null && search.trim().length() > 100) {
            return Future.failedFuture(ServiceException.badRequest("Search query too long"));
        }
        if (companyPattern != null && company.trim().length() > 100) {
            return Future.failedFuture(ServiceException.badRequest("Company filter too long"));
        }

        Future<Long>         totalFuture = managerRepo.count(searchPattern, companyPattern);
        Future<RowSet<Row>>  dataFuture  = managerRepo.search(effectiveLimit, effectiveOffset, searchPattern, companyPattern, null);

        return Future.all(totalFuture, dataFuture)
            .map(cf -> new JsonObject()
                .put("_rows", dataFuture.result())   // raw for handler to map
                .put("total",  totalFuture.result())
                .put("limit",  effectiveLimit)
                .put("offset", effectiveOffset)
            );
    }

    /** Returns raw RowSet so the handler can resolve logos. */
    public Future<RowSet<Row>> getManagerRows(int limit, int offset, String search, String company, String sortBy) {
        int effectiveLimit  = Math.min(Math.max(limit, 1), 100);
        int effectiveOffset = Math.max(offset, 0);
        String searchPattern  = (search  != null && !search.isBlank())  ? "%" + search.trim()  + "%" : null;
        String companyPattern = (company != null && !company.isBlank()) ? "%" + company.trim() + "%" : null;
        return managerRepo.search(effectiveLimit, effectiveOffset, searchPattern, companyPattern, sortBy);
    }

    public Future<Long> countManagers(String search, String company) {
        String searchPattern  = (search  != null && !search.isBlank())  ? "%" + search.trim()  + "%" : null;
        String companyPattern = (company != null && !company.isBlank()) ? "%" + company.trim() + "%" : null;
        return managerRepo.count(searchPattern, companyPattern);
    }

    // ── GET manager by ID ─────────────────────────────────────────────────────

    public Future<Row> getManagerById(long managerId, String auth0Id) {
        // Follows merges: an id that was merged away lands on the manager it was merged into.
        return managerRepo.findByIdFollowingMerges(managerId)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                Row row = opt.get();
                String approvalStatus = row.getString("approval_status");
                if ("pending_approval".equals(approvalStatus)) {
                    return enforceSubmitterAccess(row, auth0Id);
                }
                if ("rejected".equals(approvalStatus)) {
                    return Future.failedFuture(ServiceException.notFound("Manager not found"));
                }
                return Future.succeededFuture(row);
            });
    }

    private Future<Row> enforceSubmitterAccess(Row row, String auth0Id) {
        UUID submittedBy = row.getUUID("submitted_by");
        if (auth0Id == null || submittedBy == null) {
            return Future.failedFuture(ServiceException.notFound("Manager not found"));
        }
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty() || !opt.get().equals(submittedBy)) {
                    return Future.failedFuture(ServiceException.notFound("Manager not found"));
                }
                return Future.succeededFuture(row);
            });
    }

    /** Returns whether the given user has reported this manager. */
    public Future<Boolean> hasReported(long managerId, String auth0Id) {
        if (auth0Id == null) return Future.succeededFuture(false);
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.succeededFuture(false);
                return reportRepo.alreadyReported(managerId, opt.get());
            });
    }

    /** Looks up a manager by slug. Same access rules as getManagerById. */
    public Future<Row> getManagerBySlug(String slug, String auth0Id) {
        return managerRepo.findBySlugFollowingMerges(slug)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                Row row = opt.get();
                String approvalStatus = row.getString("approval_status");
                if ("pending_approval".equals(approvalStatus)) {
                    return enforceSubmitterAccess(row, auth0Id);
                }
                if ("rejected".equals(approvalStatus)) {
                    return Future.failedFuture(ServiceException.notFound("Manager not found"));
                }
                return Future.succeededFuture(row);
            });
    }

    // ── GET companies ─────────────────────────────────────────────────────────

    public Future<JsonObject> getCompanies() {
        return managerRepo.findAllCompanies()
            .map(rows -> {
                JsonArray companies = new JsonArray();
                for (Row row : rows) {
                    String c = row.getString("company");
                    if (c != null && !c.isBlank()) companies.add(c);
                }
                return new JsonObject().put("data", companies);
            });
    }

    // ── Company listing (for Companies tab) ───────────────────────────────────

    public Future<JsonObject> getCompanyListing() {
        return companyRepo.findCompanyListing()
            .map(rows -> {
                JsonArray companies = new JsonArray();
                for (Row row : rows) {
                    String name = row.getString("name");
                    if (name == null || name.isBlank()) continue;
                    String storedLogoUrl = row.getString("logo_url");
                    String logoUrl = (storedLogoUrl != null && !storedLogoUrl.isBlank())
                        ? storedLogoUrl
                        : logoResolver.apply(name);
                    String slug = row.getString("slug");
                    String cardIndustry = row.getString("industry");
                    JsonObject co = new JsonObject()
                        .put("name",         name)
                        .put("slug",         slug)
                        .put("industry",     cardIndustry)
                        .put("industrySlug", IndustryTaxonomy.slug(cardIndustry))
                        .put("managerCount", row.getLong("manager_count"))
                        .put("totalReviews", row.getLong("total_reviews"))
                        .put("avgRating",    row.getBigDecimal("avg_rating"))
                        /*
                          All three datasets, named for what they are.

                          One bare "avgRating" on a tile reads as a verdict on the company; it is
                          the mean of its managers' ratings and says nothing about working there
                          or interviewing there, both of which are separately rated. The tile can
                          only say so if it is sent all three.
                        */
                        .put("workplaceCount",  row.getLong("workplace_count"))
                        .put("workplaceRating", row.getBigDecimal("workplace_avg_rating"))
                        .put("interviewCount",  row.getLong("interview_count"))
                        .put("interviewRating", row.getBigDecimal("interview_avg_rating"));
                    if (logoUrl != null && !logoUrl.isBlank()) co.put("logoUrl", logoUrl);
                    companies.add(co);
                }
                return new JsonObject().put("data", companies);
            });
    }

    public Future<JsonObject> getCompanyProfile(String company) {
        if (company == null || company.isBlank())
            return Future.failedFuture(ServiceException.badRequest("company parameter is required"));
        final String companyName = company.trim();
        String resolvedLogoUrl = logoResolver.apply(companyName);
        return companyRepo.findByName(companyName)
            .compose(opt -> opt.isPresent()
                ? io.vertx.core.Future.succeededFuture(opt.get())
                : companyRepo.findOrCreate(companyName, null, resolvedLogoUrl))
            .compose(companyRow -> {
                long companyId = companyRow.getLong("id");
                String canonicalName = companyRow.getString("name");
                String companySlug   = companyRow.getString("slug");
                String companyIndustry = companyRow.getString("industry");
                String logoUrl = bestCompanyLogo(companyRow, canonicalName);
                return companyRepo.findManagersByCompanyId(companyId)
                    .map(rows -> {
                        if (!rows.iterator().hasNext()) {
                            JsonObject empty = new JsonObject()
                                .put("id",              companyId)
                                .put("name",            canonicalName)
                                .put("slug",            companySlug)
                                .put("industry",        companyIndustry)
                                .put("industrySlug",    IndustryTaxonomy.slug(companyIndustry))
                                .put("managerCount",    0)
                                .put("totalReviews",    0)
                                .put("avgRating",       (Object) null)
                                .put("categoryAverages", new JsonObject())
                                .put("managers",        new JsonArray());
                            if (logoUrl != null && !logoUrl.isBlank()) empty.put("logoUrl", logoUrl);
                            return empty;
                        }
                        final String finalLogoUrl = managerSuppliedLogo(rows, logoUrl);
                        JsonArray managers   = new JsonArray();
                        long   totalReviews  = 0;
                        double ratingSum     = 0.0;
                        int    ratingCount   = 0;
                        Map<String, Double>  catSum   = new LinkedHashMap<>();
                        Map<String, Integer> catCount = new LinkedHashMap<>();
                        for (Row row : rows) {
                            String mgrLogoUrl = (finalLogoUrl != null && !finalLogoUrl.isBlank())
                                ? finalLogoUrl : row.getString("company_logo_url");
                            Integer reviews = row.getInteger("reviews_count");
                            totalReviews += (reviews != null ? reviews : 0);
                            BigDecimal rating = row.getBigDecimal("overall_rating");
                            boolean hasRating = rating != null && (reviews != null && reviews > 0);
                            if (hasRating) { ratingSum += rating.doubleValue(); ratingCount++; }
                            Object catObj = row.getValue("category_averages");
                            if (catObj != null) {
                                JsonObject cats = catObj instanceof JsonObject
                                    ? (JsonObject) catObj : new JsonObject(catObj.toString());
                                for (String key : cats.fieldNames()) {
                                    Object val = cats.getValue(key);
                                    if (val instanceof Number) {
                                        double v = ((Number) val).doubleValue();
                                        catSum.merge(key, v, Double::sum);
                                        catCount.merge(key, 1, Integer::sum);
                                    }
                                }
                            }
                            JsonObject mgr = new JsonObject()
                                .put("id",             row.getLong("id"))
                                .put("name",           row.getString("name"))
                                .put("title",          row.getString("title"))
                                .put("image",          row.getString("image"))
                                .put("overallRating",  hasRating ? rating : (Object) null)
                                .put("reviewsCount",   reviews != null ? reviews : 0)
                                .put("company",        row.getString("company"))
                                .put("slug",           row.getString("slug"))
                                .put("approvalStatus", row.getString("approval_status"));
                            if (mgrLogoUrl != null && !mgrLogoUrl.isBlank()) mgr.put("companyLogoUrl", mgrLogoUrl);
                            managers.add(mgr);
                        }
                        JsonObject categoryAverages = new JsonObject();
                        for (Map.Entry<String, Double> e : catSum.entrySet()) {
                            int cnt = catCount.get(e.getKey());
                            categoryAverages.put(e.getKey(), Math.round(e.getValue() / cnt * 10.0) / 10.0);
                        }
                        JsonObject result = new JsonObject()
                            .put("id",              companyId)
                            .put("name",            canonicalName)
                            .put("slug",            companySlug)
                            .put("industry",        companyIndustry)
                            .put("industrySlug",    IndustryTaxonomy.slug(companyIndustry))
                            .put("managerCount",    managers.size())
                            .put("totalReviews",    totalReviews)
                            .put("avgRating",       ratingCount > 0
                                ? Math.round(ratingSum / ratingCount * 10.0) / 10.0 : null)
                            .put("categoryAverages", categoryAverages)
                            .put("managers",        managers);
                        if (finalLogoUrl != null && !finalLogoUrl.isBlank()) result.put("logoUrl", finalLogoUrl);
                        return result;
                    })
                    // Corporate structure, attached last so a failure to load it cannot cost the
                    // reader the company page itself. "Part of Loblaw" is useful context; it is
                    // not worth a 500 if the relationship tables are unavailable.
                    .compose(result -> withCorporateStructure(result, companyId))
                    .compose(result -> withCompanyRating(result, companyId));
            });
    }

    /**
     * Adds a company's parent and children to its profile.
     *
     * Navigation only. The company's own rating and review count are untouched by anything here:
     * a subsidiary's score is its own, and a parent's score is the parent's. A combined group
     * figure, if it ever exists, is an additional and explicitly labelled number rather than a
     * quiet redefinition of what a company's rating means.
     */
    /**
     * The logo a company should show, by the same precedence the company page itself uses.
     *
     * stats first (computed from the company's own managers), then the stored column, then the
     * resolver. Group tiles used to read the stored column alone, so a company with a null
     * logo_url showed a letter in its parent's group list while its own page showed its logo -
     * the same component, fed worse data.
     */
    /**
     * A real logo.dev URL borrowed from one of the company's managers.
     *
     * Only ever a fallback. A manager reached through career history may still carry the logo of
     * a different employer, so letting a manager override a logo the company already has puts
     * someone else's brand on the page - which is what the by-name route used to do while the
     * by-slug route guarded against it.
     */
    private static String managerSuppliedLogo(RowSet<Row> rows, String existing) {
        if (existing != null && !existing.isBlank()) return existing;
        for (Row row : rows) {
            String mgrLogo = row.getString("company_logo_url");
            if (mgrLogo != null && mgrLogo.contains("logo.dev")) return mgrLogo;
        }
        return existing;
    }

    private String bestCompanyLogo(Row row, String name) {
        // Column-safe: not every query that produces a company row selects the stats logo, and
        // reading an absent column throws rather than returning null. That is precisely how every
        // merged company's URL came to return a 500 - a row from the redirect lookup was handed to
        // code expecting the shape of a row from the slug lookup.
        String stats  = row.getColumnIndex("stats_logo_url") >= 0 ? row.getString("stats_logo_url") : null;
        String stored = row.getColumnIndex("logo_url") >= 0 ? row.getString("logo_url") : null;
        if (stats  != null && stats.contains("logo.dev"))  return stats;
        if (stored != null && stored.contains("logo.dev")) return stored;
        String resolved = logoResolver.apply(name);
        if (resolved != null && !resolved.isBlank()) return resolved;
        return stored != null && !stored.isBlank() ? stored : stats;
    }

    /**
     * Company ratings, when the app has wired them.
     *
     * Optional so the many constructors - and the tests behind them - do not all have to learn
     * about a dataset most of them do not exercise. Null simply means the profile carries no
     * company rating block, which is also what an unrated company looks like.
     */
    private org.werkpages.repository.CompanyReviewRepository companyReviewRepo;

    public void setCompanyReviewRepo(org.werkpages.repository.CompanyReviewRepository repo) {
        this.companyReviewRepo = repo;
    }

    /**
     * Attaches the company's workplace rating alongside its manager ratings.
     *
     * Two numbers, never merged. A company page shows both precisely so they can disagree: a good
     * employer with uneven managers is a real thing, and averaging them into one score would hide
     * exactly the signal people come for.
     *
     * Absent rather than zero when nobody has rated it - the same rule the tiles follow.
     */
    private Future<JsonObject> withCompanyRating(JsonObject profile, long companyId) {
        if (companyReviewRepo == null) return Future.succeededFuture(profile);
        return companyReviewRepo.findCompanyAggregate(companyId)
            .map(opt -> {
                opt.ifPresent(row -> {
                    JsonObject categories = new JsonObject();
                    for (String c : org.werkpages.repository.CompanyReviewRepository.CATEGORIES) {
                        java.math.BigDecimal v = row.getBigDecimal(c);
                        categories.put(c, v == null ? null : v.doubleValue());
                    }
                    java.math.BigDecimal overall = row.getBigDecimal("overall_rating");
                    profile.put("companyRating", new JsonObject()
                        .put("ratingCount",   row.getLong("rating_count"))
                        .put("overallRating", overall == null ? null : overall.doubleValue())
                        .put("categories",    categories));
                });
                return profile;
            })
            // A company page must not 500 because its rating aggregate failed - the manager data
            // is the older and more important half of the page.
            .recover(err -> {
                System.err.println("company rating aggregate failed for company " + companyId
                    + ": " + err.getMessage());
                return Future.succeededFuture(profile);
            });
    }

    private Future<JsonObject> withCorporateStructure(JsonObject profile, long companyId) {
        return companyRepo.findCompanyParent(companyId)
            .compose(parentOpt -> companyRepo.findCompanyChildren(companyId).map(childRows -> {
                parentOpt.ifPresent(p -> {
                    JsonObject parent = new JsonObject()
                        .put("id",   p.getLong("id"))
                        .put("name", p.getString("name"))
                        .put("slug", p.getString("slug"))
                        .put("relationshipType", p.getString("relationship_type"));
                    String logo = bestCompanyLogo(p, p.getString("name"));
                    if (logo != null && !logo.isBlank()) parent.put("logoUrl", logo);
                    profile.put("partOf", parent);
                });

                JsonArray children = new JsonArray();
                for (Row c : childRows) {
                    JsonObject child = new JsonObject()
                        .put("id",           c.getLong("id"))
                        .put("name",         c.getString("name"))
                        .put("slug",         c.getString("slug"))
                        .put("managerCount", c.getLong("manager_count"))
                        .put("totalReviews", c.getLong("total_reviews"))
                        .put("avgRating",    c.getBigDecimal("avg_rating"))
                        .put("relationshipType", c.getString("relationship_type"));
                    String logo = bestCompanyLogo(c, c.getString("name"));
                    if (logo != null && !logo.isBlank()) child.put("logoUrl", logo);
                    children.add(child);
                }
                if (!children.isEmpty()) profile.put("companiesInGroup", children);
                return profile;
            }))
            // The group figure is attached only when the company actually heads a group. For a
            // company with no children it would be the company's own rating printed twice under a
            // grander heading, which is noise dressed as insight.
            .compose(withGroup -> withGroup.containsKey("companiesInGroup")
                ? companyRepo.findGroupStats(companyId).map(statsOpt -> {
                    statsOpt.ifPresent(s -> {
                        Long managerCount = s.getLong("manager_count");
                        if (managerCount != null && managerCount > 0) {
                            withGroup.put("groupStats", new JsonObject()
                                .put("companyCount", s.getLong("company_count"))
                                .put("managerCount", managerCount)
                                .put("totalReviews", s.getLong("total_reviews"))
                                .put("avgRating",    s.getBigDecimal("avg_rating")));
                        }
                    });
                    return withGroup;
                })
                : Future.succeededFuture(withGroup))
            .recover(err -> {
                System.err.println("Corporate structure lookup failed for company " + companyId
                                   + ": " + err.getMessage());
                return Future.succeededFuture(profile);
            });
    }

    /**
     * Same as getCompanyProfile but looked up by URL slug.
     *
     * A slug belonging to a merged company serves the surviving company instead. The retired row
     * still owns its slug, so without this a shared link would render a company page with no
     * managers on it - which reads as "this company has nothing" rather than "this company is now
     * part of another one". Because the response carries the survivor's own slug, the client's
     * existing canonical-URL redirect rewrites the address on its own; no new frontend code, and
     * the link the person followed still works.
     */
    public Future<JsonObject> getCompanyBySlug(String slug) {
        if (slug == null || slug.isBlank())
            return Future.failedFuture(ServiceException.badRequest("companySlug is required"));
        String trimmedSlug = slug.trim();
        return companyRepo.findBySlug(trimmedSlug)
            .compose(found -> {
                boolean retired = found.isPresent() && "merged".equals(found.get().getString("status"));
                if (!found.isPresent() || retired) {
                    // Either the slug is unknown, or it belongs to a company that has been
                    // absorbed. Both are answered the same way: follow the redirect if one exists.
                    return companyRepo.findRedirectTargetBySlug(trimmedSlug)
                        .map(target -> target.isPresent() ? target : found);
                }
                return Future.succeededFuture(found);
            })
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Company not found"));
                Row companyRow = opt.get();
                String canonicalName    = companyRow.getString("name");
                String companySlug      = companyRow.getString("slug");
                // Prefer stats_logo_url (computed from current FK-linked managers only) so
                // career-history-linked managers from other companies don't bleed their logos in.
                String logoUrl = bestCompanyLogo(companyRow, canonicalName);
                long companyId = companyRow.getLong("id");
                String companyIndustry = companyRow.getString("industry");
                return companyRepo.findManagersByCompanyId(companyId)
                    .map(rows -> buildCompanyProfileResponse(companyId, canonicalName, companySlug,
                                                             companyIndustry, logoUrl, rows))
                    // The slug route is the one people actually reach from a link, so it needs the
                    // corporate structure just as much as the by-name route.
                    .compose(result -> withCorporateStructure(result, companyId))
                    .compose(result -> withCompanyRating(result, companyId));
            });
    }

    private JsonObject buildCompanyProfileResponse(long companyId, String canonicalName,
                                                   String companySlug, String companyIndustry,
                                                   String logoUrl,
                                                   io.vertx.sqlclient.RowSet<Row> rows) {
        if (!rows.iterator().hasNext()) {
            JsonObject empty = new JsonObject()
                .put("id",               companyId)
                .put("name",             canonicalName)
                .put("slug",             companySlug)
                .put("industry",         companyIndustry)
                .put("industrySlug",     IndustryTaxonomy.slug(companyIndustry))
                .put("managerCount",     0)
                .put("totalReviews",     0)
                .put("avgRating",        (Object) null)
                .put("categoryAverages", new JsonObject())
                .put("managers",         new JsonArray());
            if (logoUrl != null && !logoUrl.isBlank()) empty.put("logoUrl", logoUrl);
            return empty;
        }
        // Only fall back to manager logo scan when we have no authoritative logo from
        // company_stats_live. If we already have a logo, don't override it — career-history
        // managers may have logos from their current (different) company.
        final String finalLogoUrl = managerSuppliedLogo(rows, logoUrl);
        JsonArray managers  = new JsonArray();
        long   totalReviews = 0;
        double ratingSum    = 0.0;
        int    ratingCount  = 0;
        Map<String, Double>  catSum   = new LinkedHashMap<>();
        Map<String, Integer> catCount = new LinkedHashMap<>();
        for (Row row : rows) {
            String mgrLogoUrl = (finalLogoUrl != null && !finalLogoUrl.isBlank())
                ? finalLogoUrl : row.getString("company_logo_url");
            Integer reviews = row.getInteger("reviews_count");
            totalReviews += (reviews != null ? reviews : 0);
            BigDecimal rating = row.getBigDecimal("overall_rating");
            boolean hasRating = rating != null && (reviews != null && reviews > 0);
            if (hasRating) { ratingSum += rating.doubleValue(); ratingCount++; }
            Object catObj = row.getValue("category_averages");
            if (catObj != null) {
                JsonObject cats = catObj instanceof JsonObject
                    ? (JsonObject) catObj : new JsonObject(catObj.toString());
                for (String key : cats.fieldNames()) {
                    Object val = cats.getValue(key);
                    if (val instanceof Number) {
                        double v = ((Number) val).doubleValue();
                        catSum.merge(key, v, Double::sum);
                        catCount.merge(key, 1, Integer::sum);
                    }
                }
            }
            JsonObject mgr = new JsonObject()
                .put("id",             row.getLong("id"))
                .put("name",           row.getString("name"))
                .put("title",          row.getString("title"))
                .put("image",          row.getString("image"))
                .put("overallRating",  hasRating ? rating : (Object) null)
                .put("reviewsCount",   reviews != null ? reviews : 0)
                .put("company",        row.getString("company"))
                .put("slug",           row.getString("slug"))
                .put("approvalStatus", row.getString("approval_status"));
            if (mgrLogoUrl != null && !mgrLogoUrl.isBlank()) mgr.put("companyLogoUrl", mgrLogoUrl);
            managers.add(mgr);
        }
        JsonObject categoryAverages = new JsonObject();
        for (Map.Entry<String, Double> e : catSum.entrySet()) {
            int cnt = catCount.get(e.getKey());
            categoryAverages.put(e.getKey(), Math.round(e.getValue() / cnt * 10.0) / 10.0);
        }
        JsonObject result = new JsonObject()
            .put("id",               companyId)
            .put("name",             canonicalName)
            .put("slug",             companySlug)
            .put("industry",         companyIndustry)
            .put("industrySlug",     IndustryTaxonomy.slug(companyIndustry))
            .put("managerCount",     managers.size())
            .put("totalReviews",     totalReviews)
            .put("avgRating",        ratingCount > 0 ? Math.round(ratingSum / ratingCount * 10.0) / 10.0 : null)
            .put("categoryAverages", categoryAverages)
            .put("managers",         managers);
        if (finalLogoUrl != null && !finalLogoUrl.isBlank()) result.put("logoUrl", finalLogoUrl);
        return result;
    }

    // ── POST create company ───────────────────────────────────────────────────

    /**
     * Brings a company into existence from a name, or returns the one that name already resolves to.
     *
     * The single sanctioned place that happens. Everything else in the write path takes an ID, so
     * a caller that wants to add a company the picker did not offer comes here first and carries
     * the returned ID onward. Keeping creation in one visible, deliberate step is what stops a
     * company name quietly becoming a company as a side effect of some other operation.
     *
     * Idempotent, because the alternative is worse: a double-clicked submit or a retried request
     * would otherwise create the second Crumbl this whole effort exists to prevent.
     */
    public Future<JsonObject> createCompany(String name) {
        if (name == null || name.trim().length() < 2) {
            return Future.failedFuture(ServiceException.badRequest("Company name must be at least 2 characters"));
        }
        String trimmed = name.trim();
        return companyRepo.findByNormalizedName(trimmed).compose(existing -> {
            if (existing.isPresent()) {
                Row row = existing.get();
                return Future.succeededFuture(new JsonObject()
                    .put("id", row.getLong("id"))
                    .put("name", row.getString("name"))
                    .put("created", false));
            }
            return companyRepo.findOrCreate(trimmed, null, logoResolver.apply(trimmed))
                .map(row -> new JsonObject()
                    .put("id", row.getLong("id"))
                    .put("name", row.getString("name"))
                    .put("created", true));
        });
    }

    // ── GET company suggestions ───────────────────────────────────────────────

    /**
     * Company suggestions for the picker.
     *
     * Each suggestion now carries the company ID. That is the whole point: a caller that selects a
     * suggestion can persist the ID, so the company's display name never has to be re-resolved into
     * an identity on the write path. The name is display data from here on.
     *
     * `industry` rides along because the picker needs it to tell two similarly-named companies
     * apart before the user commits to one.
     */
    /**
     * Places to offer for one company: its buildings, and geography anybody has already confirmed
     * for it.
     *
     * <p>Both kinds come back in one list because both are valid answers. Somebody who knows the
     * branch picks the building; somebody who only knows the province picks the province and is
     * done. Requiring a street address to file a rating would lose the rating.
     */
    public Future<JsonArray> suggestCompanyLocations(Long companyId, String companyName, String query,
                                                     String country, String state) {
        if (query == null || query.trim().length() < 2) return Future.succeededFuture(new JsonArray());
        return new org.werkpages.repository.CompanyLocationRepository(db)
            .suggest(companyId, companyName, query, country, state)
            .map(rows -> {
                JsonArray out = new JsonArray();
                for (Row row : rows) {
                    // (mapping below unchanged)
                    boolean isPlace = "place".equals(row.getString("kind"));
                    JsonObject item = new JsonObject()
                        .put("kind", row.getString("kind"))
                        .put("label", row.getString("label"))
                        .put("country", row.getString("country"));
                    if (row.getString("state") != null) item.put("state", row.getString("state"));
                    if (row.getString("city")  != null) item.put("city",  row.getString("city"));
                    if (isPlace) {
                        item.put("detail", row.getString("detail"))
                            .put("precision", DeclaredLocation.EXACT)
                            .put("companyLocationId", row.getLong("company_location_id"));
                    } else {
                        // How specific this geography is, from how much of it there is.
                        String precision = row.getString("city")  != null ? DeclaredLocation.CITY
                                         : row.getString("state") != null ? DeclaredLocation.STATE
                                         : DeclaredLocation.COUNTRY;
                        item.put("precision", precision);
                    }
                    out.add(item);
                }
                return out;
            })
            .compose(confirmed -> appendCorpusSuggestions(confirmed, companyName, query, country, state));
    }

    /** How many suggestions the picker shows in total, across every tier. */
    private static final int SUGGESTION_LIMIT = 8;

    /**
     * How many coarse places lead the list, before any building.
     *
     * <p>Geography went last and was merely reserved a seat, which put "Toronto, Ontario, Canada"
     * at the bottom of eight street addresses — visible, but below every answer somebody had not
     * asked for. The coarse answer is the one most people can actually give, so it goes first.
     *
     * <p>Capped rather than unlimited, and the rest of the list is buildings: somebody who knows
     * the branch still finds it without scrolling past every suburb of the city they typed.
     */
    private static final int GEOGRAPHY_FIRST = 3;

    /**
     * Adds corpus candidates beneath what is already confirmed.
     *
     * <p><b>Order is the point.</b> Everything from Postgres — buildings already selected, geography
     * already confirmed for this company — comes first, because those are facts about this company
     * that somebody stood behind. Corpus rows are candidates from a map: useful, plentiful, and
     * never allowed to push a real answer off the list.
     *
     * <p>Once the confirmed tiers fill the list, the corpus is not queried at all. A company whose
     * locations are already known should not pay an S3 round trip per keystroke to be told about
     * places nobody there has worked.
     *
     * <p>Failure is silent, by construction: the corpus repository returns an empty array rather
     * than failing, so an unreachable bucket costs suggestions and nothing else.
     */
    private Future<JsonArray> appendCorpusSuggestions(JsonArray confirmed, String companyName,
                                                      String query, String country, String state) {
        if (confirmed.size() >= SUGGESTION_LIMIT) return Future.succeededFuture(confirmed);

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object entry : confirmed) {
            String label = ((JsonObject) entry).getString("label");
            if (label != null) seen.add(label.toLowerCase());
        }

        /*
          Both at once. They are independent reads of different files, and running them one after
          the other doubled the wait on every keystroke for no reason.
        */
        Future<JsonArray> placesF    = locationCorpus.suggestPlaces(companyName, query, country);
        Future<JsonArray> geographyF = locationCorpus.suggestGeography(query, country, null);

        return Future.all(placesF, geographyF).map(cf -> {
            JsonArray places    = cf.resultAt(0);
            JsonArray geography = cf.resultAt(1);
            JsonArray merged = confirmed.copy();

            /*
              Geography gets its own seats, and buildings cannot take them.

              Buildings are listed first because somebody typing a company or a street wants the
              branch. But there are thousands of them and only a handful of matching places, so
              filling the list with buildings meant "Toronto, Ontario, Canada" never appeared at
              all - somebody who only knows the city was shown eight street addresses and no way to
              say what they actually knew. A coarse answer is a complete answer, and it has to be
              reachable.
            */
            /*
              Coarse first, then buildings, then any geography that did not fit at the top.

              A city or a province is the answer most people have, and it was being printed
              underneath eight street addresses nobody had asked about. Leading with it costs the
              person who knows the exact branch three rows of scrolling; burying it cost everybody
              else the ability to answer at all.
            */
            int geoSeats = Math.min(GEOGRAPHY_FIRST, geography.size());
            addCorpusGeography(merged, geography, seen, country,
                               Math.min(merged.size() + geoSeats, SUGGESTION_LIMIT));
            addCorpusPlaces(merged, places, seen, country, SUGGESTION_LIMIT);
            addCorpusGeography(merged, geography, seen, country, SUGGESTION_LIMIT);
            return merged;
        });
    }

    private void addCorpusPlaces(JsonArray out, JsonArray places,
                                 java.util.Set<String> seen, String country, int limit) {
        for (Object entry : places) {
            if (out.size() >= limit) return;
            JsonObject place = (JsonObject) entry;
            String label = place.getString("name");
            if (label == null || !seen.add(label.toLowerCase() + "|" + place.getString("street"))) continue;

            String detail = java.util.stream.Stream
                .of(place.getString("street"),
                    join(", ", place.getString("city"), place.getString("stateCode")))
                .filter(part -> part != null && !part.isBlank())
                .collect(java.util.stream.Collectors.joining(" · "));

            out.add(new JsonObject()
                .put("kind", "place")
                .put("label", label)
                .put("detail", detail)
                .put("country", country)
                .put("city", place.getString("city"))
                .put("precision", DeclaredLocation.EXACT)
                // No companyLocationId: this building is not in the database yet. The client sends
                // corpusPlace back on submit and it is promoted then - see CorpusPlace.
                .put("corpusPlace", place.copy().put("countryCode",
                        org.werkpages.repository.LocationCorpusRepository.iso(country))));
        }
    }

    private void addCorpusGeography(JsonArray out, JsonArray geography,
                                    java.util.Set<String> seen, String country, int limit) {
        for (Object entry : geography) {
            if (out.size() >= limit) return;
            JsonObject geo = (JsonObject) entry;
            String name  = geo.getString("name");
            String kind  = geo.getString("geoKind");
            if (name == null) continue;

            String stateName = geo.getString("stateName");
            String label = "country".equals(kind) ? name
                         : "region".equals(kind)  ? join(", ", name, country)
                         : join(", ", name, stateName, country);
            if (!seen.add(label.toLowerCase())) continue;

            JsonObject item = new JsonObject()
                .put("kind", "geo")
                .put("label", label)
                .put("country", country);

            switch (kind == null ? "" : kind) {
                case "country" -> item.put("precision", DeclaredLocation.COUNTRY);
                case "region"  -> item.put("state", name).put("precision", DeclaredLocation.STATE);
                default        -> {
                    item.put("city", name).put("precision", DeclaredLocation.CITY);
                    if (stateName != null) item.put("state", stateName);
                    // A city with no region cannot be stored at city precision, which requires
                    // country + state + city. Hong Kong and Monaco are genuinely like this, so it
                    // is offered as the province-level answer rather than dropped.
                    if (stateName == null) item.put("precision", DeclaredLocation.COUNTRY);
                }
            }
            out.add(item);
        }
    }

    /** Joins the non-blank parts with a separator. */
    private static String join(String separator, String... parts) {
        return java.util.Arrays.stream(parts)
            .filter(part -> part != null && !part.isBlank())
            .collect(java.util.stream.Collectors.joining(separator));
    }

    public Future<JsonArray> suggestCompanies(String query) {
        if (query == null || query.isBlank()) return Future.succeededFuture(new JsonArray());
        return companyRepo.searchForPicker(query.trim())
            .map(rows -> {
                JsonArray result = new JsonArray();
                for (Row row : rows) {
                    String name = row.getString("name");
                    if (name != null && !name.isBlank()) {
                        JsonObject suggestion = new JsonObject().put("name", name);
                        // Omitted rather than null for a name that has no company row yet: a
                        // client checks for the key's presence to decide between "select this
                        // company" and "create it", and `"id": null` reads as a broken record.
                        Long id = row.getLong("id");
                        if (id != null) suggestion.put("id", id);
                        // Same omit-rather-than-null rule: a caller that needs the company's URL
                        // checks for the key. Older rows predate slugs and legitimately have none.
                        String slug = row.getString("slug");
                        if (slug != null && !slug.isBlank()) suggestion.put("slug", slug);
                        // Resolver first, stored logo second - unchanged precedence, so a company
                        // whose logo was resolved from its domain keeps the better image.
                        String logoUrl = logoResolver.apply(name);
                        if (logoUrl == null || logoUrl.isBlank()) {
                            logoUrl = row.getString("logo_url");
                        }
                        if (logoUrl != null && !logoUrl.isBlank()) {
                            suggestion.put("logoUrl", logoUrl);
                        }
                        String industry = row.getString("industry");
                        if (industry != null && !industry.isBlank()) {
                            suggestion.put("industry", industry);
                        }
                        result.add(suggestion);
                    }
                }
                return result;
            });
    }

    // ── GET similar managers ──────────────────────────────────────────────────

    public Future<JsonObject> getSimilarManagers(String name, String company) {
        if (name == null || name.isBlank()) {
            return Future.failedFuture(ServiceException.badRequest("name parameter is required"));
        }
        String nameLike    = "%" + name.trim() + "%";
        String companyLike = (company != null && !company.isBlank()) ? "%" + company.trim() + "%" : "%";
        return managerRepo.findSimilar(nameLike, companyLike)
            .map(rows -> {
                JsonArray results = new JsonArray();
                for (Row row : rows) {
                    results.add(new JsonObject()
                        .put("id", row.getLong("id"))
                        .put("name", row.getString("name"))
                        .put("company", row.getString("company"))
                        .put("title", row.getString("title"))
                        .put("overallRating", row.getBigDecimal("overall_rating"))
                        .put("companyLogoUrl", row.getString("company_logo_url"))
                        .put("approvalStatus", row.getString("approval_status"))
                    );
                }
                return new JsonObject().put("data", results);
            });
    }

    // ── GET stats ─────────────────────────────────────────────────────────────

    public Future<JsonObject> getStats() {
        Future<Long> userSubmittedFuture = db.query("SELECT COUNT(*) FROM managers WHERE approval_status IN ('approved','ghost') AND external_id IS NULL")
            .execute().map(rows -> rows.iterator().next().getLong(0));
        Future<Long> realReviewsFuture = db.query("SELECT COUNT(*) FROM reviews r JOIN managers m ON r.manager_id = m.id WHERE m.approval_status IN ('approved','ghost') AND m.external_id IS NULL AND r.weight = FALSE")
            .execute().map(rows -> rows.iterator().next().getLong(0));
        Future<Long> weightedOpinionsFuture = db.query("SELECT COUNT(*) FROM reviews WHERE weight = TRUE AND (weight_expires_on IS NULL OR weight_expires_on > CURRENT_DATE)")
            .execute().map(rows -> rows.iterator().next().getLong(0));
        Future<Long> seededManagersFuture = db.query("SELECT COUNT(*) FROM managers WHERE approval_status IN ('approved','ghost') AND external_id LIKE 'seed_%'")
            .execute().map(rows -> rows.iterator().next().getLong(0));
        Future<Long> scrapedManagersFuture = db.query("SELECT COUNT(*) FROM managers WHERE approval_status IN ('approved','ghost') AND external_id IS NOT NULL AND external_id NOT LIKE 'seed_%'")
            .execute().map(rows -> rows.iterator().next().getLong(0));
        return Future.all(userSubmittedFuture, realReviewsFuture, weightedOpinionsFuture, seededManagersFuture, scrapedManagersFuture)
            .map(cf -> new JsonObject()
                .put("realManagers",       userSubmittedFuture.result())
                .put("realReviews",        realReviewsFuture.result())
                .put("weightedOpinions",   weightedOpinionsFuture.result())
                .put("seededManagers",     seededManagersFuture.result())
                .put("scrapedManagers",    scrapedManagersFuture.result())
            );
    }

    // ── GET my submitted managers ─────────────────────────────────────────────

    public Future<RowSet<Row>> getMySubmittedManagers(String auth0Id) {
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("User not found"));
                return managerRepo.findPendingByUser(opt.get());
            });
    }

    // ── CREATE manager ────────────────────────────────────────────────────────

    /**
     * For callers with no request behind them — tests, and anything invoking this outside an HTTP
     * context. Records an empty observation rather than none at all, so the "one row per write"
     * rule holds regardless of who called.
     */
    public Future<Row> createManager(String auth0Id, JsonObject body, String resolvedLogoUrl) {
        return createManager(auth0Id, body, resolvedLogoUrl, SubmissionContext.NONE);
    }



    /** The two values a manager's status may take, or null for anything else - including absence. */
    private static String statusOrNull(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase();
        return ("active".equals(value) || "retired".equals(value)) ? value : null;
    }

    /**
     * Which required review field is absent, phrased for a reader, or null when none is.
     *
     * <p>Shared by the three paths that accept a review - submitted with a new manager, added to
     * an existing one, and replacing an earlier one - so the same omission reads the same way
     * wherever it happens.
     *
     * <p>The company and title are the manager's, as the reviewer knew them: they live on the
     * review rather than being read from the manager row because a manager moves, and an opinion
     * records the job it was about rather than the job they hold now.
     */
    private static String reviewFieldMissing(Double overallRating, JsonObject ratings,
                                             String managerCompany, String managerTitle) {
        if (overallRating == null)   return "Your review needs an overall rating.";
        if (ratings == null)         return "Your review needs its category ratings.";
        if (isBlank(managerCompany)) return "Your review needs the company you worked with this manager at.";
        if (isBlank(managerTitle))   return "Your review needs the job title this manager held.";
        return null;
    }

    /** All validation and business logic for POST /api/managers. Returns the created manager row. */
    public Future<Row> createManager(String auth0Id, JsonObject body, String resolvedLogoUrl,
                                     SubmissionContext submission) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));
        final GeoObservation observed = submission.observed();
        final DeclaredLocation declared = submission.declared();
        // Null unless the form named a building that is not in the database yet. Promoted inside
        // the transaction below, so a location is only ever created alongside the manager it
        // belongs to.
        final CorpusPlace corpusPlace = submission.corpusPlace();

        String name    = toProperNameCase(body.getString("name"));
        String company = body.getString("company") != null ? body.getString("company").trim() : null;
        String title   = body.getString("title")   != null ? body.getString("title").trim()   : null;
        String image   = body.getString("image");
        /*
          Name the field, rather than saying "something".

          "Missing required fields" is what a person saw after filling in a form where every
          visible question was answered - it names nothing, points nowhere, and cannot be acted on
          or usefully reported. Each of these four is a distinct question somebody can go answer.
        */
        String missing = isBlank(name)    ? "a name"
                       : isBlank(company) ? "a company"
                       : isBlank(title)   ? "a job title"
                       : isBlank(image)   ? "an avatar initial"
                       : null;
        if (missing != null) {
            return Future.failedFuture(ServiceException.badRequest("This manager needs " + missing + "."));
        }
        if (name.length() > 100)    return Future.failedFuture(ServiceException.badRequest("Manager name must be at most 100 characters"));
        if (company.length() < 2)   return Future.failedFuture(ServiceException.badRequest("Company name must be at least 2 characters"));
        if (company.length() > 100) return Future.failedFuture(ServiceException.badRequest("Company must be at most 100 characters"));
        if (title.length() > 100)   return Future.failedFuture(ServiceException.badRequest("Title must be at most 100 characters"));

        // The add-manager form submits one "First Last" string, so split it and apply the same name
        // rules the find-or-create / ghost paths use. Without this, single-letter and junk names
        // ("A B", "-- --") reached the directory through the form.
        String[] nameParts = name.trim().split("\\s+", 2);
        NameValidator.ValidationResult nameValidation =
            NameValidator.validateFullName(nameParts[0], nameParts.length > 1 ? nameParts[1] : "");
        if (!nameValidation.valid())
            return Future.failedFuture(ServiceException.badRequest(nameValidation.reason()));

        String country     = body.getString("country")     != null ? body.getString("country").trim()     : null;
        String state       = body.getString("state")       != null ? body.getString("state").trim()       : null;
        String city        = body.getString("city")        != null ? body.getString("city").trim()        : null;
        String bio         = body.getString("bio")         != null ? body.getString("bio").trim()         : null;
        String linkedinUrl = body.getString("linkedinUrl") != null ? body.getString("linkedinUrl").trim() : null;
        if (isBlank(country)) return Future.failedFuture(ServiceException.badRequest("Country is required"));
        if (country.length() > 100) return Future.failedFuture(ServiceException.badRequest("Country must be at most 100 characters"));
        if (isBlank(state)) state = null;
        if (isBlank(city))  city  = null;
        if (state != null && state.length() > 100) return Future.failedFuture(ServiceException.badRequest("State must be at most 100 characters"));
        if (city  != null && city.length()  > 100) return Future.failedFuture(ServiceException.badRequest("City must be at most 100 characters"));
        if (bio != null && bio.length() > 1000) return Future.failedFuture(ServiceException.badRequest("Bio must be at most 1000 characters"));
        if (!isBlank(linkedinUrl)) {
            if (linkedinUrl.length() > 500) return Future.failedFuture(ServiceException.badRequest("LinkedIn URL must be at most 500 characters"));
            if (!isValidLinkedinUrl(linkedinUrl)) return Future.failedFuture(ServiceException.badRequest("LinkedIn URL must be a valid linkedin.com URL"));
        }

        String submittedStatus = body.getString("status");
        if (submittedStatus == null || (!submittedStatus.equals("active") && !submittedStatus.equals("retired"))) {
            submittedStatus = "active";
        }
        boolean isRetired = "retired".equals(submittedStatus);
        LocalDate today = LocalDate.now();

        LocalDate startDateLocal = parseYearMonth(body.getString("startDate"));
        if (startDateLocal == null) return Future.failedFuture(ServiceException.badRequest("Manager start date is required"));
        if (startDateLocal.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Manager start date cannot be in the future"));

        LocalDate endDateLocal = parseYearMonth(body.getString("endDate"));
        if (isRetired) {
            if (endDateLocal == null) return Future.failedFuture(ServiceException.badRequest("End date is required for a retired manager"));
            if (endDateLocal.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Manager end date cannot be in the future"));
            if (endDateLocal.isBefore(startDateLocal)) return Future.failedFuture(ServiceException.badRequest("Manager end date must be on or after the start date"));
        } else {
            endDateLocal = null;
        }

        JsonObject reviewBody = body.getJsonObject("review");
        if (reviewBody == null) return Future.failedFuture(ServiceException.badRequest("A review is required when submitting a manager"));

        // Validate review fields
        Double overallRating = reviewBody.getDouble("overallRating");
        JsonObject ratings   = reviewBody.getJsonObject("ratings");
        String managerCompany = reviewBody.getString("managerCompany");
        String managerTitle   = reviewBody.getString("managerTitle");
        String reviewText     = reviewBody.getString("text");
        LocalDate workedFrom  = parseYearMonth(reviewBody.getString("workedFrom"));
        LocalDate workedUntil = parseYearMonth(reviewBody.getString("workedUntil"));

        String missingReview = reviewFieldMissing(overallRating, ratings, managerCompany, managerTitle);
        if (missingReview != null) {
            return Future.failedFuture(ServiceException.badRequest(missingReview));
        }
        if (workedFrom == null) return Future.failedFuture(ServiceException.badRequest("Your start date working with this manager is required"));
        if (workedFrom.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("The 'from' date cannot be in the future"));
        if (workedUntil != null && workedUntil.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("The 'to' date cannot be in the future"));
        if (isRetired && workedUntil == null) return Future.failedFuture(ServiceException.badRequest("A retired manager cannot have a current reviewer — end date is required"));
        if (workedUntil != null && workedUntil.isBefore(workedFrom)) return Future.failedFuture(ServiceException.badRequest("The 'to' date cannot be before the 'from' date"));
        if (workedFrom.isBefore(startDateLocal)) return Future.failedFuture(ServiceException.badRequest("You cannot have worked with this manager before they started in this role"));
        if (endDateLocal != null) {
            if (workedFrom.isAfter(endDateLocal)) return Future.failedFuture(ServiceException.badRequest("Your 'from' date cannot be after the manager's end date"));
            if (workedUntil != null && workedUntil.isAfter(endDateLocal)) return Future.failedFuture(ServiceException.badRequest("Your 'to' date cannot be after the manager's end date"));
        }
        if (managerCompany.length() > 100) return Future.failedFuture(ServiceException.badRequest("Manager company must be at most 100 characters"));
        if (managerTitle.length() > 100)   return Future.failedFuture(ServiceException.badRequest("Manager title must be at most 100 characters"));
        if (reviewText != null && reviewText.length() > 2000) return Future.failedFuture(ServiceException.badRequest("Review text must be at most 2000 characters"));
        if (!isValidRating(overallRating)) return Future.failedFuture(ServiceException.badRequest("Overall rating must be between 1 and 5"));
        for (int i = 0; i < RATING_KEYS.length; i++) {
            Double v = getRating(ratings, i);
            if (!isValidRating(v)) return Future.failedFuture(ServiceException.badRequest("Rating for '" + RATING_KEYS[i] + "' must be between 1 and 5"));
        }

        UUID draftTokenParsed = null;
        String draftTokenStr = body.getString("draftToken");
        if (draftTokenStr != null && !draftTokenStr.isBlank()) {
            try { draftTokenParsed = UUID.fromString(draftTokenStr); } catch (IllegalArgumentException ignored) {}
        }

        final String   fStatus               = submittedStatus;
        final LocalDate fStartDate           = startDateLocal;
        final String   fReviewAuthorType     = reviewBody.getString("authorType", "username");
        final String   fReviewClientAuthor   = reviewBody.getString("author", "").trim();
        final LocalDate fEndDate      = endDateLocal;
        final String   fCountry       = country;
        final String   fState         = state;
        final String   fCity          = city;
        final String   fBio           = bio;
        final String   fLinkedinUrl   = linkedinUrl;
        final String   fReviewText    = reviewText;
        final Double   fOverallRating = overallRating;
        final JsonObject fRatings     = ratings;
        final String   fMgrCompany    = managerCompany;
        final String   fMgrTitle      = managerTitle;
        final LocalDate fWorkedFrom   = workedFrom;
        final LocalDate fWorkedUntil  = workedUntil;
        final UUID     fDraftToken    = draftTokenParsed;

        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("User not found"));
                Row userRow = opt.get();
                if (userRow.getBoolean("is_banned")) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                UUID userId = userRow.getUUID("id");
                String dbUsername = userRow.getString("username");
                String author;
                if ("anonymous".equals(fReviewAuthorType)) {
                    // Use the client-provided pseudonym (from generateUsername() on the frontend).
                    // Never fall back to dbUsername — generate a fresh pseudonym server-side instead.
                    author = (!fReviewClientAuthor.isEmpty() && fReviewClientAuthor.length() <= 100)
                        ? fReviewClientAuthor : generatePseudonym();
                } else if ("real_name".equals(fReviewAuthorType)
                        && !fReviewClientAuthor.isEmpty() && fReviewClientAuthor.length() <= 100) {
                    author = fReviewClientAuthor;
                } else {
                    author = dbUsername;
                }
                return managerRepo.countSubmittedTodayByUser(userId)
                    .compose(todayCount -> SubmissionLimits.checkDailyLimit(todayCount, SubmissionLimits.DAILY_MANAGERS))
                    .compose(withinLimit -> {
                        // Check for an existing manager with the same company and a fuzzy-matching name
                        // (Levenshtein distance ≤ 1). If found, attach the review there instead of
                        // creating a duplicate pending_approval entry.
                        /*
                          First, adopt the capture this same attempt left behind.

                          The add form posts a capture as soon as step one is valid, which is
                          before the company box is finished - so adding "Eddie Junior at
                          Microsoft" left a row at "Eddie Junior at Mi", and the submission then
                          filed a second row for the same person.

                          Adopting rewrites that capture's company to the one actually submitted,
                          which is what lets the ordinary duplicate detection below find it. No
                          special case downstream: by the time it runs, there is simply an existing
                          manager at this company with this name.
                        */
                        return managerRepo.adoptCapture(name, company, title, userId, null)
                            .compose(adopted -> managerRepo.findByCompanyExact(company))
                            .compose(candidates -> {
                                Row fuzzyMatch = findFuzzyNameMatch(candidates, name);
                                if (fuzzyMatch != null) {
                                    return doAttachToExisting(fuzzyMatch, userId, author,
                                        name, company, title, fStatus, fCountry, fLinkedinUrl, resolvedLogoUrl,
                                        fStartDate, fEndDate, fOverallRating, fRatings,
                                        fMgrCompany, fMgrTitle, fReviewText, fWorkedFrom, fWorkedUntil, fDraftToken);
                                }

                                // No match — create a new pending_approval manager with its first review.
                                // Resolve (or create) the company row first so we can link company_id.
                                OffsetDateTime startDt = fStartDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
                                OffsetDateTime endDt   = fEndDate != null ? fEndDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() : null;
                                final String fCompany = company;
                                return companyRepo.resolve(body.getLong("companyId"), company, null, resolvedLogoUrl)
                                    .compose(companyRow -> {
                                long companyId = companyRow.getLong("id");
                                return managerRepo.generateUniqueSlug(name, company)
                                    .compose(slug ->
                                ((Pool) db).withTransaction(conn ->
                                    // Resolved inside the transaction: at exact precision this reads
                                    // the chosen location row - or creates it, when the place came
                                    // from the corpus - and it must be the same row the insert
                                    // below points at.
                                    declaredLocations.resolve(conn, declared, corpusPlace, companyId)
                                    .compose(loc ->
                                    conn.preparedQuery("""
                                        INSERT INTO managers
                                        (name, company, title, image, bio, status, approval_status, country, state, city, linkedin_url,
                                         company_logo_url, company_id, slug, overall_rating, reviews_count, category_averages, created_at, submitted_by,
                                         declared_country, declared_state, declared_city, declared_precision, company_location_id)
                                        VALUES ($1,$2,$3,$4,$5,$6,'pending_approval',$7,$8,$9,$10,$11,$12,$13,0,0,'{}'::jsonb,now(),$14,
                                                $15,$16,$17,$18,$19)
                                        RETURNING *
                                        """)
                                        .execute(Tuple.of(name, fCompany, title, image, fBio, fStatus, fCountry, fState, fCity, fLinkedinUrl, resolvedLogoUrl, companyId, slug, userId,
                                                          loc.country(), loc.state(), loc.city(), loc.precision(), loc.companyLocationId()))
                                        .compose(managerResult -> {
                                            Row managerRow = managerResult.iterator().next();
                                            long managerId = managerRow.getLong("id");
                                            conn.preparedQuery("INSERT INTO career_history(manager_id, company, title, start_date, end_date, company_id) VALUES ($1,$2,$3,$4,$5,$6)")
                                                .execute(Tuple.of(managerId, company, title, startDt, endDt, companyId), ignored -> {});
                                            Future<Void> deleteDraft = (fDraftToken != null)
                                                ? conn.preparedQuery("DELETE FROM reviews WHERE draft_token = $1 AND user_id IS NULL")
                                                      .execute(Tuple.of(fDraftToken))
                                                      .mapEmpty()
                                                : Future.succeededFuture();
                                            return deleteDraft.compose(v ->
                                                conn.preparedQuery("""
                                                    INSERT INTO reviews (
                                                        manager_id, user_id, author, overall_rating,
                                                        communication_style, perceived_approachability, perceived_clarity_of_expectations,
                                                        feedback_style, perceived_supportiveness, decision_making_style,
                                                        organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                                                        overall_working_experience, manager_company, manager_title, text,
                                                        worked_from, worked_until, verified, helpful_count, created_at, updated_at
                                                    )
                                                    VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,true,0,now(),now())
                                                    RETURNING id
                                                    """)
                                                    .execute(Tuple.of(
                                                        managerId, userId, author, fOverallRating,
                                                        getRating(fRatings, 0), getRating(fRatings, 1), getRating(fRatings, 2),
                                                        getRating(fRatings, 3), getRating(fRatings, 4), getRating(fRatings, 5),
                                                        getRating(fRatings, 6), getRating(fRatings, 7), getRating(fRatings, 8),
                                                        getRating(fRatings, 9), fMgrCompany, fMgrTitle, fReviewText,
                                                        fWorkedFrom, fWorkedUntil
                                                    ))
                                                    .compose(reviewIns -> {
                                                        // The manager row is new, so the figures
                                                        // list can only match on name + company —
                                                        // which is exactly why that form exists.
                                                        UUID newReviewId = reviewIns.iterator().next().getUUID("id");
                                                        String[] parts = splitName(name);
                                                        // The response for this path is the
                                                        // manager, not the review, so the
                                                        // post-decision row is not needed here.
                                                        return proofOfWork.applyTo(conn, userId, managerId,
                                                            newReviewId, parts[0], parts[1], companyId);
                                                    })
                                                    // Inside the transaction on purpose: if the
                                                    // manager does not survive, neither does the
                                                    // claim that we saw somebody create one.
                                                    .compose(ignored -> geoObservations.record(
                                                        conn, GeoObservationRepository.SUBJECT_MANAGER, managerId,
                                                        GeoObservationRepository.ACTION_CREATE, observed))
                                                    .map(ignored -> managerRow)
                                            );
                                        })
                                ) // compose(loc ->
                                ).onSuccess(managerRow -> {
                                    // Pending managers must NOT have a cached rating — recalculate
                                    // runs on admin approval instead (AdminService.approvePendingManager).
                                    managerRepo.deleteFakeManagerInBackground();
                                })
                                ); // compose(slug ->
                            }); // compose(companyRow -> {
                            });
                    });
            });
    }


    /**
     * Whether two names are close enough that the second is worth showing for the first.
     *
     * <p>Not "the same person" - a suggestion. The reader decides, so this is tuned to avoid
     * hiding a real match rather than to avoid showing a near miss.
     *
     * <p>The rule: the first names must match exactly, and the typed surname must be a plausible
     * fragment of the stored one. "Daniel Pa" for "Daniel Perovic" is the case this exists for -
     * two letters that are not even a prefix - so a short fragment is accepted only when it shares
     * the first letter, which keeps "Daniel Ko" from dragging in every Daniel at the company.
     *
     * <p>A typed surname of one character is rejected outright: at that length everything is
     * plausible, and a list of everyone called Daniel is not an answer.
     */
    private static boolean isPlausibleSameName(String typed, String stored) {
        String[] t = typed.trim().split("\\s+", 2);
        String[] c = stored.trim().split("\\s+", 2);
        if (t.length < 2 || c.length < 2) return false;
        if (!t[0].equalsIgnoreCase(c[0])) return false;

        String typedLast  = t[1].trim();
        String storedLast = c[1].trim();
        if (typedLast.length() < 2) return false;
        if (storedLast.toLowerCase().startsWith(typedLast.toLowerCase())) return true;
        // Same initial and a short fragment: "Pa" against "Perovic". Long fragments have to be
        // near-misses rather than merely alphabetical neighbours.
        if (typedLast.length() <= 3) {
            return Character.toLowerCase(typedLast.charAt(0)) == Character.toLowerCase(storedLast.charAt(0));
        }
        return LevenshteinUtil.distance(typedLast.toLowerCase(), storedLast.toLowerCase()) <= 2;
    }

    private static Row findFuzzyNameMatch(RowSet<Row> candidates, String targetName) {
        for (Row row : candidates) {
            String candidateName = row.getString("name");
            if (candidateName != null && LevenshteinUtil.distance(targetName, candidateName) <= 1) {
                return row;
            }
        }
        return null;
    }

    /**
     * Attaches a new review to an existing manager instead of creating a duplicate.
     * For ghost managers, also enriches the manager record with the richer form data.
     */
    private Future<Row> doAttachToExisting(
            Row match, UUID userId, String author,
            String name, String company, String title,
            String status, String country, String linkedinUrl, String logoUrl,
            LocalDate startDate, LocalDate endDate,
            double overallRating, JsonObject ratings,
            String mgrCompany, String mgrTitle, String reviewText,
            LocalDate workedFrom, LocalDate workedUntil, UUID draftToken) {

        long existingId      = match.getLong("id");
        String approvalStatus = match.getString("approval_status");

        String workedFromStr  = workedFrom  != null ? workedFrom.toString().substring(0, 7)  : null;
        String workedUntilStr = workedUntil != null ? workedUntil.toString().substring(0, 7) : null;
        JsonObject reviewBody = new JsonObject()
            .put("overallRating",  overallRating)
            .put("ratings",        ratings)
            .put("managerCompany", mgrCompany)
            .put("managerTitle",   mgrTitle)
            .put("text",           reviewText)
            .put("workedFrom",     workedFromStr)
            .put("workedUntil",    workedUntilStr);

        if ("ghost".equals(approvalStatus)) {
            // Enrich the ghost record with the more complete form data, add career history,
            // then attach the review.
            return managerRepo.updateForAttach(existingId, name, title, status, country, linkedinUrl, logoUrl, userId)
                .compose(updatedOpt -> {
                    if (updatedOpt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                    Row updatedRow = updatedOpt.get();
                    OffsetDateTime startDt = startDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
                    OffsetDateTime endDt   = endDate != null ? endDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() : null;
                    return managerRepo.hasCareerHistory(existingId)
                        .compose(hasHistory -> {
                            Future<Void> histFuture = hasHistory
                                ? Future.succeededFuture()
                                : companyRepo.findOrCreate(company, null, logoUrl)
                                    .compose(cRow -> managerRepo.insertCareerEntry(existingId, company, title, startDt, endDt, cRow.getLong("id")));
                            // First-time raters (non-contributors) who rate the ghost manager they
                            // found via /find get the seed deleted immediately so their real rating
                            // shows at once. Contributors who happen to rate a ghost manager keep
                            // the seed on the 14-day expiry counter instead.
                            Future<Long> reviewCountFuture = userId != null
                                ? db.preparedQuery("SELECT COUNT(*) AS cnt FROM reviews WHERE user_id = $1 AND deleted_at IS NULL")
                                    .execute(Tuple.of(userId))
                                    .map(rs -> rs.iterator().next().getLong("cnt"))
                                : Future.succeededFuture(1L); // anonymous → treat as contributor
                            return histFuture
                                .compose(v -> reviewCountFuture)
                                .compose(existingReviewCount -> existingReviewCount == 0
                                    ? reviewRepo.deleteSeedReview(existingId)
                                    : reviewRepo.scheduleSeedExpiry(existingId))
                                .compose(v -> validateAndInsertReview(reviewBody, existingId, userId, author, logoUrl, draftToken))
                                .map(ignored -> updatedRow);
                        });
                });
        } else {
            /*
              approved or pending_approval — delete any legacy seed, then attach review.

              The submitter is stamped on the way through. A capture this submission attached to
              rather than adopted carries no submitted_by, and enforceSubmitterAccess refuses an
              ownerless pending row to everybody - so without this the person who just submitted
              is shown "Manager Not Found" straight after being told it went for review.

              COALESCE inside the update, so a manager somebody else submitted keeps its original
              submitter; this only fills a gap.
            */
            return managerRepo.claimSubmitterIfUnowned(existingId, userId)
                .compose(opt -> {
                    if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                    Row existingRow = opt.get();
                    return reviewRepo.deleteSeedReview(existingId)
                        .compose(v -> validateAndInsertReview(reviewBody, existingId, userId, author, logoUrl, draftToken))
                        .map(ignored -> existingRow);
                });
        }
    }

    // ── UPDATE manager ────────────────────────────────────────────────────────

    public Future<JsonObject> updateManager(String auth0Id, long managerId, JsonObject body) {
        if (body == null || body.isEmpty()) return Future.failedFuture(ServiceException.badRequest("Nothing to update"));

        String newCompany     = body.getString("company");
        String newTitle       = body.getString("title");
        String newImage       = body.getString("image");
        String newBio         = body.getString("bio");
        String newStatus      = body.getString("status");
        String newCountry     = body.getString("country");
        String newLinkedinUrl = body.getString("linkedinUrl");
        String newLogoUrl     = body.getString("resolvedLogoUrl");
        String startDateStr   = body.getString("startDate");
        String endDateStr     = body.getString("endDate");

        if (newCompany == null && newTitle == null && newImage == null && newBio == null && newStatus == null && newCountry == null && newLinkedinUrl == null) {
            return Future.failedFuture(ServiceException.badRequest("Nothing to update"));
        }
        if (newCompany != null && (newCompany.isBlank() || newCompany.length() < 2 || newCompany.length() > 100)) return Future.failedFuture(ServiceException.badRequest("Company must be between 2 and 100 characters"));
        if (newTitle   != null && (newTitle.isBlank()   || newTitle.length()   > 100)) return Future.failedFuture(ServiceException.badRequest("Title must be between 1 and 100 characters"));
        if (newBio     != null && newBio.length() > 1000) return Future.failedFuture(ServiceException.badRequest("Bio must be at most 1000 characters"));
        if (newStatus  != null && !newStatus.equals("active") && !newStatus.equals("retired")) return Future.failedFuture(ServiceException.badRequest("Status must be 'active' or 'retired'"));
        if (!isBlank(newLinkedinUrl)) {
            if (newLinkedinUrl.length() > 500) return Future.failedFuture(ServiceException.badRequest("LinkedIn URL must be at most 500 characters"));
            if (!isValidLinkedinUrl(newLinkedinUrl)) return Future.failedFuture(ServiceException.badRequest("LinkedIn URL must be a valid linkedin.com URL"));
        }

        return userRepo.isBanned(auth0Id)
            .compose(isBanned -> {
                if (isBanned) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                return managerRepo.findById(managerId);
            })
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                Row current = opt.get();
                String currentCompany = current.getString("company");
                String currentTitle   = current.getString("title");

                boolean companyChanged = newCompany != null && !newCompany.equals(currentCompany);
                boolean titleChanged   = newTitle   != null && !newTitle.equals(currentTitle);

                if (companyChanged || titleChanged) {
                    String effectiveCo  = newCompany != null ? newCompany : currentCompany;
                    String effectiveTit = newTitle   != null ? newTitle   : currentTitle;
                    Long   oldCompanyId = current.getLong("company_id");

                    // Resolve the company row (and company_id) for the new effective company.
                    return companyRepo.resolve(body.getLong("companyId"), effectiveCo, null, logoResolver.apply(effectiveCo))
                        .compose(effectiveCoRow -> {
                            long newCompanyId = effectiveCoRow.getLong("id");
                            LocalDate oldStartLocal = parseYearMonth(startDateStr);

                            if (oldStartLocal == null) {
                                // No start date provided — treat as a spelling/typo correction.
                                // Update the existing open career entry in place; don't fork a new segment.
                                return managerRepo.updateOpenCareerEntry(managerId, effectiveCo, effectiveTit, newCompanyId)
                                    .compose(v -> doUpdate(managerId, newCompany, newTitle, newImage, newBio, newStatus, newCountry, newLinkedinUrl, newLogoUrl, newCompanyId));
                            }

                            LocalDate endDateLocal = parseYearMonth(endDateStr);

                            if (endDateLocal != null) {
                                // Both start and end date provided — user is adding a PAST role.
                                // Insert the segment with its dates but DO NOT change manager.company.
                                OffsetDateTime pastStart = oldStartLocal.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
                                OffsetDateTime pastEnd   = endDateLocal.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
                                return managerRepo.insertCareerEntry(managerId, effectiveCo, effectiveTit, pastStart, pastEnd, newCompanyId)
                                    .compose(v -> doUpdate(managerId, null, null, newImage, newBio, newStatus, newCountry, newLinkedinUrl, null, null));
                            }

                            // Start date provided, no end date. This is only a genuine *current* role
                            // change when the new role starts on/after the manager's existing current
                            // role. If it starts earlier, the user is recording an OLDER role they simply
                            // didn't mark as ended — archive it as a past segment and DO NOT move the
                            // manager's headline company/title/logo off the most-recent role.
                            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                            OffsetDateTime newPosStart = oldStartLocal.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

                            return managerRepo.findCurrentRoleStart(managerId).compose(curStartOpt -> {
                                OffsetDateTime currentStart = curStartOpt.orElse(current.getOffsetDateTime("created_at"));
                                if (currentStart != null && newPosStart.isBefore(currentStart)) {
                                    // Older open-ended role → store it as a closed past segment ending when
                                    // the current role began; leave the manager's headline untouched.
                                    return managerRepo.insertCareerEntry(managerId, effectiveCo, effectiveTit, newPosStart, currentStart, newCompanyId)
                                        .compose(v -> doUpdate(managerId, null, null, newImage, newBio, newStatus, newCountry, newLinkedinUrl, null, null));
                                }
                                return managerRepo.closeOpenCareerEntry(managerId, now)
                                    .compose(closedRows -> {
                                        Future<Void> archiveOld;
                                        if (closedRows == 0) {
                                            OffsetDateTime oldStart = current.getOffsetDateTime("created_at");
                                            archiveOld = managerRepo.insertCareerEntry(managerId, currentCompany, currentTitle, oldStart, now, oldCompanyId);
                                        } else {
                                            archiveOld = Future.succeededFuture();
                                        }
                                        return archiveOld.compose(v ->
                                            managerRepo.insertCareerEntry(managerId, effectiveCo, effectiveTit, newPosStart, null, newCompanyId)
                                        );
                                    })
                                    .compose(v -> doUpdate(managerId, newCompany, newTitle, newImage, newBio, newStatus, newCountry, newLinkedinUrl, newLogoUrl, newCompanyId));
                            });
                        });
                } else {
                    return doUpdate(managerId, newCompany, newTitle, newImage, newBio, newStatus, newCountry, newLinkedinUrl, newLogoUrl, null);
                }
            });
    }

    private Future<JsonObject> doUpdate(long managerId, String newCompany, String newTitle,
                                         String newImage, String newBio, String newStatus, String newCountry,
                                         String newLinkedinUrl, String newLogoUrl, Long newCompanyId) {
        return managerRepo.update(managerId, newCompany, newTitle, newImage, newBio, newStatus, newCountry, newLinkedinUrl, newLogoUrl, newCompanyId)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                Row row = opt.get();
                return companyRepo.syncStatsForManager(managerId)
                    .compose(statsDone -> managerRepo.getCareerHistory(managerId)
                    .map(chRows -> buildManagerUpdateJson(row, chRows)));
            });
    }

    // ── CREATE review ─────────────────────────────────────────────────────────

    /** For callers with no request behind them — see the createManager overload above. */
    public Future<Row> createReview(String auth0Id, long managerId, JsonObject body, String resolvedLogoUrl) {
        return createReview(auth0Id, managerId, body, resolvedLogoUrl, SubmissionContext.NONE);
    }

    public Future<Row> createReview(String auth0Id, long managerId, JsonObject body, String resolvedLogoUrl,
                                    SubmissionContext submission) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("User not found"));
                Row userRow = opt.get();
                if (userRow.getBoolean("is_banned")) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                UUID userId = userRow.getUUID("id");
                String dbUsername = userRow.getString("username");

                String authorType = body.getString("authorType", "username");
                String author;
                if ("anonymous".equals(authorType)) {
                    String clientAuthor = toProperNameCase(body.getString("author", ""));
                    author = (!clientAuthor.isEmpty() && clientAuthor.length() <= 100) ? clientAuthor : generatePseudonym();
                } else if ("real_name".equals(authorType)) {
                    String clientAuthor = toProperNameCase(body.getString("author", ""));
                    author = (!clientAuthor.isEmpty() && clientAuthor.length() <= 100) ? clientAuthor : dbUsername;
                } else {
                    author = dbUsername;
                }

                return reviewRepo.countSubmittedTodayByUser(userId)
                    .compose(todayCount -> SubmissionLimits.checkDailyLimit(todayCount, SubmissionLimits.DAILY_REVIEWS))
                    .compose(v -> reviewRepo.findRecentDeletion(userId, managerId))
                    .compose(recentDeletion -> SubmissionLimits.checkCooldown(recentDeletion, "review"))
                    .compose(v -> {
                        UUID draftToken = null;
                        String draftTokenStr = body.getString("draftToken");
                        if (draftTokenStr != null && !draftTokenStr.isBlank()) {
                            try { draftToken = UUID.fromString(draftTokenStr); } catch (IllegalArgumentException ignored) {}
                        }
                        /*
                            Start the placeholder's countdown once the real review is in.

                            This path - opening a ghost's profile and pressing "Write a Review" -
                            retired the placeholder neither way, so the seeded review kept counting
                            toward the average forever alongside the genuine one.

                            AFTER the insert, not before. Retiring it first means a submission that
                            then fails validation has already timed out the placeholder, leaving
                            the manager with a retired seed and no review to replace it.

                            Always the 14-day countdown, never an immediate delete: no manager's
                            displayed rating changes the instant somebody submits.
                        */
                        return validateAndInsertReview(body, managerId, userId, author,
                                resolvedLogoUrl, draftToken, submission)
                            .compose(reviewRow -> reviewRepo.scheduleSeedExpiry(managerId)
                                .map(seedScheduled -> reviewRow));
                    });
            });
    }

    public Future<Void> createDropOffReview(long managerId, JsonObject body, String resolvedLogoUrl) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        String author = body.getString("author");
        if (isBlank(author)) author = generatePseudonym();
        final String fAuthor = author;

        UUID draftToken = null;
        String draftTokenStr = body.getString("draftToken");
        if (draftTokenStr != null && !draftTokenStr.isBlank()) {
            try { draftToken = UUID.fromString(draftTokenStr); } catch (IllegalArgumentException ignored) {}
        }
        final UUID fDraftToken = draftToken;

        // allowIncomplete: this is the capture path. Someone rated a manager and left before
        // saying when they worked with them, and half a review is worth keeping.
        //
        // reviews.manager_company and manager_title are NOT NULL, and someone who stopped at the
        // stars may not have reached either. Rather than relax a real constraint or invent an
        // employer, fall back to what the manager record already says - the likeliest answer, and
        // the same thing the page was showing the person as they rated.
        return managerRepo.findById(managerId).compose(mgrOpt -> {
            if (mgrOpt.isPresent()) {
                Row mgr = mgrOpt.get();
                if (isBlank(body.getString("managerCompany"))) body.put("managerCompany", mgr.getString("company"));
                if (isBlank(body.getString("managerTitle")))   body.put("managerTitle",   mgr.getString("title"));
            }
            return validateAndInsertReview(body, managerId, null, fAuthor, resolvedLogoUrl, fDraftToken, true);
        }).mapEmpty();
    }

    private Future<Row> validateAndInsertReview(JsonObject body, long managerId, UUID userId, String author, String resolvedLogoUrl, UUID draftToken) {
        return validateAndInsertReview(body, managerId, userId, author, resolvedLogoUrl, draftToken, false, SubmissionContext.NONE);
    }

    private Future<Row> validateAndInsertReview(JsonObject body, long managerId, UUID userId, String author, String resolvedLogoUrl, UUID draftToken, SubmissionContext submission) {
        return validateAndInsertReview(body, managerId, userId, author, resolvedLogoUrl, draftToken, false, submission);
    }

    private Future<Row> validateAndInsertReview(JsonObject body, long managerId, UUID userId, String author, String resolvedLogoUrl, UUID draftToken, boolean allowIncomplete) {
        return validateAndInsertReview(body, managerId, userId, author, resolvedLogoUrl, draftToken, allowIncomplete, SubmissionContext.NONE);
    }

    /**
     * @param allowIncomplete true only for drop-off capture, where the point is to keep what
     *                        somebody had when they walked away. A real submission must still state
     *                        when they worked with the manager; a capture cannot, because the
     *                        person never reached that field. Requiring it there threw away exactly
     *                        the submissions the capture exists to save.
     */
    private Future<Row> validateAndInsertReview(JsonObject body, long managerId, UUID userId, String author, String resolvedLogoUrl, UUID draftToken, boolean allowIncomplete, SubmissionContext submission) {
        Double overallRating      = body.getDouble("overallRating");
        JsonObject ratings        = body.getJsonObject("ratings");
        String managerCompany     = body.getString("managerCompany") != null ? body.getString("managerCompany").trim() : null;
        String managerTitle       = body.getString("managerTitle")   != null ? body.getString("managerTitle").trim()   : null;
        String text               = body.getString("text")           != null ? body.getString("text").trim()           : null;
        LocalDate workedFrom      = parseYearMonth(body.getString("workedFrom"));
        LocalDate workedUntil     = parseYearMonth(body.getString("workedUntil"));
        LocalDate managerRoleStart = parseYearMonth(body.getString("managerRoleStart"));
        LocalDate managerRoleEnd   = parseYearMonth(body.getString("managerRoleEnd")); // null = still in role
        /*
          Whether the manager was still in the role, as this reviewer knew it - the one field the
          add-manager form asked for and this one could not. Optional: a body that omits it leaves
          the manager's own status alone rather than asserting one on their behalf.
        */
        final String managerStatus = statusOrNull(body.getString("managerStatus"));
        if (body.getString("managerStatus") != null && managerStatus == null) {
            return Future.failedFuture(ServiceException.badRequest(
                "Manager status must be either active or retired."));
        }
        LocalDate today = LocalDate.now();

        // ── Manager role date validation (optional — not all reviewers know manager tenure) ─
        if (managerRoleStart != null) {
            if (managerRoleStart.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Manager role start date cannot be in the future"));
            if (managerRoleEnd != null) {
                if (managerRoleEnd.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Manager role end date cannot be in the future"));
                if (managerRoleEnd.isBefore(managerRoleStart)) return Future.failedFuture(ServiceException.badRequest("Manager role end date must be on or after the start date"));
            }
        }

        // ── User work date validation ─────────────────────────────────────────────
        if (workedFrom == null && !allowIncomplete) return Future.failedFuture(ServiceException.badRequest("Your start date working with this manager is required"));
        if (workedFrom != null && workedFrom.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Your 'from' date cannot be in the future"));
        if (workedUntil != null && workedUntil.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Your 'to' date cannot be in the future"));
        if (workedFrom != null && workedUntil != null && workedFrom != null && workedFrom.isAfter(workedUntil)) return Future.failedFuture(ServiceException.badRequest("Your 'from' date cannot be later than your 'to' date"));

        // ── Cross-validation: user dates vs manager role period (only when provided) ─
        if (managerRoleStart != null) {
            if (workedFrom != null && workedFrom.isBefore(managerRoleStart)) return Future.failedFuture(ServiceException.badRequest("Your start date cannot be before the manager started this role (" + formatYM(managerRoleStart) + ")"));
            if (managerRoleEnd != null && workedFrom != null && workedFrom.isAfter(managerRoleEnd)) return Future.failedFuture(ServiceException.badRequest("Your start date cannot be after the manager left this role (" + formatYM(managerRoleEnd) + ")"));
            if (managerRoleEnd != null && workedUntil != null && workedUntil.isAfter(managerRoleEnd)) return Future.failedFuture(ServiceException.badRequest("Your end date cannot be after the manager left this role (" + formatYM(managerRoleEnd) + ")"));
        }

        // A capture keeps whatever the person had. The one thing it cannot do without is a rating:
        // with no rating there is nothing to keep, and an empty row would just be noise for an
        // admin to wade through. Company and title are frequently blank because the form asks for
        // them after the stars.
        if (allowIncomplete) {
            if (ratings == null && overallRating == null) return Future.failedFuture(ServiceException.badRequest("Nothing to capture"));
        } else if (overallRating == null || ratings == null || isBlank(managerCompany) || isBlank(managerTitle)) {
            return Future.failedFuture(ServiceException.badRequest("Missing required fields"));
        }
        if (managerCompany != null && managerCompany.length() < 2 && !allowIncomplete)   return Future.failedFuture(ServiceException.badRequest("Company name must be at least 2 characters"));
        if (managerCompany != null && managerCompany.length() > 100) return Future.failedFuture(ServiceException.badRequest("Manager company must be at most 100 characters"));
        if (managerTitle != null && managerTitle.length()   > 100) return Future.failedFuture(ServiceException.badRequest("Manager title must be at most 100 characters"));
        if (text != null && text.length() > 2000) return Future.failedFuture(ServiceException.badRequest("Review text must be at most 2000 characters"));
        if (overallRating != null && !isValidRating(overallRating)) return Future.failedFuture(ServiceException.badRequest("Overall rating must be between 1 and 5"));
        for (int i = 0; i < RATING_KEYS.length; i++) {
            Double v = getRating(ratings, i);
            // A capture keeps the categories the person got to and ignores the ones they did not.
            // Anything they did rate still has to be a real rating.
            if (allowIncomplete && !hasRating(ratings, i)) continue;
            if (!isValidRating(v)) return Future.failedFuture(ServiceException.badRequest("Rating for '" + RATING_KEYS[i] + "' must be between 1 and 5"));
        }

        // When an authenticated user submits using a draftToken, delete the anonymous drop-off review
        // before the duplicate check. Without this, findByUserForValidation finds the drop-off via
        // author match and rejects the submission as a duplicate even though it's the same user.
        Future<Void> deleteDraftFirst = (userId != null && draftToken != null)
            ? db.preparedQuery("DELETE FROM reviews WHERE draft_token = $1 AND user_id IS NULL")
                  .execute(Tuple.of(draftToken))
                  .mapEmpty()
            : Future.succeededFuture();

        // Fetch all existing reviews by this user (or anonymous reviews with the same author name)
        return deleteDraftFirst.compose(v ->
            reviewRepo.findByUserForValidation(userId != null ? userId : UUID.fromString("00000000-0000-0000-0000-000000000000"), author)
            .compose(existingRows -> {
                List<Row> existing = new ArrayList<>();
                for (Row r : existingRows) existing.add(r);

                // ── 1. Cap: max 5 reviews for a single manager per user ───────────────
                long reviewsForThisManager = existing.stream()
                    .filter(r -> r.getLong("manager_id") == managerId)
                    .count();
                if (reviewsForThisManager >= 5) {
                    return Future.failedFuture(ServiceException.conflict("role_limit_reached"));
                }

                // ── 2. Role duplicate: same normalised title+company under same manager ─
                // A capture may hold neither, because the form asks for them after the stars.
                // With nothing to compare, there is no role to have already reviewed.
                String normTitle   = managerTitle   != null ? managerTitle.trim().toLowerCase()   : null;
                String normCompany = managerCompany != null ? managerCompany.trim().toLowerCase() : null;
                boolean roleTaken = normTitle != null && normCompany != null && existing.stream()
                    .filter(r -> r.getLong("manager_id") == managerId)
                    .anyMatch(r -> {
                        String t = r.getString("manager_title");
                        String c = r.getString("manager_company");
                        return t != null && c != null
                            && t.trim().equalsIgnoreCase(normTitle)
                            && c.trim().equalsIgnoreCase(normCompany);
                    });
                if (roleTaken) {
                    return Future.failedFuture(ServiceException.conflict("already_reviewed_this_role"));
                }

                // ── 3. Manager role period overlap (only when role dates were provided) ─
                if (managerRoleStart == null) {
                    return insertReviewTransactionally(managerId, userId, author, overallRating,
                            ratings, managerCompany, managerTitle, text,
                            workedFrom, workedUntil, null, null, resolvedLogoUrl, draftToken, submission,
                            managerStatus);
                }
                return reviewRepo.findRolePeriodsForManager(managerId)
                    .compose(allRoleRows -> {
                        LocalDate newRoleEnd = managerRoleEnd != null ? managerRoleEnd : LocalDate.of(9999, 12, 31);
                        for (Row r : allRoleRows) {
                            LocalDate existRoleStart = r.getLocalDate("manager_role_start");
                            LocalDate existRoleEndRaw = r.getLocalDate("manager_role_end");
                            LocalDate existRoleEnd = existRoleEndRaw != null ? existRoleEndRaw : LocalDate.of(9999, 12, 31);
                            boolean overlaps = !managerRoleStart.isAfter(existRoleEnd) && !existRoleStart.isAfter(newRoleEnd);
                            if (overlaps) {
                                String existTitle   = r.getString("manager_title");
                                String existCompany = r.getString("manager_company");
                                return Future.failedFuture(ServiceException.conflict(
                                    "manager_role_overlap:" + existTitle + ":" + existCompany + ":" +
                                    formatYM(existRoleStart) + ":" + (existRoleEndRaw != null ? formatYM(existRoleEndRaw) : "present")));
                            }
                        }

                        return insertReviewTransactionally(managerId, userId, author, overallRating,
                                ratings, managerCompany, managerTitle, text,
                                workedFrom, workedUntil, managerRoleStart, managerRoleEnd, resolvedLogoUrl, draftToken, submission,
                                managerStatus);
                    });  // closes allRoleRows compose
            })  // closes existingRows compose
        );  // closes deleteDraftFirst compose
    }

    /**
     * Inserts a review and, within the same transaction, updates the manager's
     * company/title/logo if this review is the most current one for that manager.
     * Either both succeed or both roll back — the caller gets a failed Future on error.
     */

    /**
     * Resolves a declared location for a contribution about a manager.
     *
     * <p>A review's company is the manager's company, so the ownership check needs a lookup. Only
     * performed at exact precision - the coarse rungs have nothing to own.
     */

    /**
     * Adds a freshly written opinion to the location read model.
     *
     * <p>Reads the company off the manager rather than trusting the request: the projection is
     * keyed by company, and a contribution filed under the wrong one is invisible to the page that
     * should show it and inflates a page that should not.
     */
    private Future<Void> projectNewReview(SqlClient conn, long managerId, Row reviewRow) {
        return conn.preparedQuery("SELECT company_id FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .compose(rs -> {
                var it = rs.iterator();
                Long companyId = it.hasNext() ? it.next().getLong("company_id") : null;
                // A manager with no company yet - a pending submission whose company never
                // resolved - has nothing to project onto. The rebuild will pick it up if one
                // is attached later.
                if (companyId == null) return Future.succeededFuture();
                return locationStats.applyManagerReview(conn,
                    LocationStatsProjector.ManagerReviewFacts.from(reviewRow, companyId));
            });
    }

    /**
     * Moves an edited opinion from what it contributed to what it now contributes.
     *
     * <p>Subtract-then-add rather than a computed difference, because an edit that changes the
     * location does not merely change a number - it moves the contribution to a different set of
     * scope rows entirely, and a difference has nowhere to go in that case.
     *
     * <p>Ratings were editable long before location was, and this path did not maintain the
     * projection at all: re-rating a manager from 5 to 1 left the location figures still counting
     * the 5. Putting location on the edit form is what made that visible, so it is fixed here
     * rather than left for the next person to find.
     */
    private Future<Void> reprojectEditedReview(SqlClient conn, long managerId, Row before, Row after) {
        if (before == null) return Future.succeededFuture();
        return conn.preparedQuery("SELECT company_id FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .compose(rs -> {
                var it = rs.iterator();
                Long companyId = it.hasNext() ? it.next().getLong("company_id") : null;
                // No company means nothing was ever projected for this review, so there is nothing
                // to move. A rebuild picks it up if a company is attached later.
                if (companyId == null) return Future.succeededFuture();
                return locationStats.applyChange(conn,
                    LocationStatsProjector.ManagerReviewFacts.from(before, companyId),
                    LocationStatsProjector.ManagerReviewFacts.from(after,  companyId));
            });
    }

    private Future<DeclaredLocation> resolveDeclaredForManager(SqlClient conn, DeclaredLocation declared,
                                                               CorpusPlace corpusPlace, long managerId) {
        if (declared == null || declared.isEmpty() || !DeclaredLocation.EXACT.equals(declared.precision())) {
            return declaredLocations.resolve(conn, declared, null, null);
        }
        // The company comes from the manager rather than the request: a review is about a manager,
        // and the building has to belong to that manager's company. Taking it from the body would
        // let a stale or crafted form file a Walmart address under a review of somebody at Loblaws.
        return conn.preparedQuery("SELECT company_id FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .compose(rs -> {
                var it = rs.iterator();
                Long companyId = it.hasNext() ? it.next().getLong("company_id") : null;
                return declaredLocations.resolve(conn, declared, corpusPlace, companyId);
            });
    }

    private Future<Row> insertReviewTransactionally(
            long managerId, UUID userId, String author, double overallRating,
            JsonObject ratings, String managerCompany, String managerTitle, String text,
            LocalDate workedFrom, LocalDate workedUntil,
            LocalDate managerRoleStart, LocalDate managerRoleEnd,
            String resolvedLogoUrl, UUID draftToken, SubmissionContext submission,
            String managerStatus) {

        return ((Pool) db).withTransaction(conn -> {
            // Authenticated submit with a token: delete the matching anonymous drop-off draft first.
            Future<Void> deleteDraft = (userId != null && draftToken != null)
                ? conn.preparedQuery("DELETE FROM reviews WHERE draft_token = $1 AND user_id IS NULL")
                      .execute(Tuple.of(draftToken))
                      .mapEmpty()
                : Future.succeededFuture();

            // draft_token is stored only on anonymous drop-off inserts; authenticated reviews get null.
            UUID tokenToStore = (userId == null) ? draftToken : null;

            return deleteDraft
                .compose(v -> resolveDeclaredForManager(conn, submission.declared(),
                                                        submission.corpusPlace(), managerId))
                .compose(resolvedDeclared ->
                conn.preparedQuery("""
                        INSERT INTO reviews (
                            manager_id, user_id, author, overall_rating,
                            communication_style, perceived_approachability, perceived_clarity_of_expectations,
                            feedback_style, perceived_supportiveness, decision_making_style,
                            organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                            overall_working_experience, manager_company, manager_title, text,
                            worked_from, worked_until, manager_role_start, manager_role_end,
                            draft_token, verified, helpful_count, created_at, updated_at,
                            declared_country, declared_state, declared_city, declared_precision, company_location_id,
                            manager_status
                        )
                        VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,true,0,now(),now(),
                                $23,$24,$25,$26,$27,$28)
                        RETURNING *
                        """)
                    .execute(Tuple.of(
                        managerId, userId, author, overallRating,
                        getRating(ratings, 0), getRating(ratings, 1), getRating(ratings, 2),
                        getRating(ratings, 3), getRating(ratings, 4), getRating(ratings, 5),
                        getRating(ratings, 6), getRating(ratings, 7), getRating(ratings, 8),
                        getRating(ratings, 9), managerCompany, managerTitle, text,
                        workedFrom, workedUntil, managerRoleStart, managerRoleEnd, tokenToStore,
                        resolvedDeclared.country(), resolvedDeclared.state(), resolvedDeclared.city(),
                        resolvedDeclared.precision(), resolvedDeclared.companyLocationId(),
                        managerStatus
                    ))
                .compose(reviewResult -> {
                    Row reviewRow = reviewResult.iterator().next();
                    UUID newId = reviewRow.getUUID("id");
                    // Saved first, questioned second. If this rating is held, the hold lands
                    // inside this transaction, so it is never briefly visible and never opens the
                    // gate for an instant.
                    //
                    // The observation rides the same transaction: an opinion that exists without a
                    // record of where it came from is the gap this table was added to close.
                    return geoObservations.record(conn, GeoObservationRepository.SUBJECT_REVIEW,
                                                  newId.toString(), GeoObservationRepository.ACTION_REVIEW, submission.observed())
                        // The location projection rides the same transaction as the opinion that
                        // caused it. A count that can drift from its source is worse than no count:
                        // nobody checks a number that has always been roughly right.
                        .compose(ignoredObs -> projectNewReview(conn, managerId, reviewRow))
                        .compose(ignoredProj -> applyProofOfWork(conn, managerId, userId, newId))
                        .compose(afterDecision -> conn.preparedQuery("""
                            SELECT id, manager_company, manager_title, manager_status,
                                   worked_from, worked_until,
                                   declared_country, declared_state, declared_city,
                                   declared_precision, company_location_id
                            -- Same reason as ReviewRepository.findMostCurrentReviewForManager:
                            -- this decides the manager's public company, title and location.
                            FROM reviews r
                            WHERE
                            """ + ReviewSql.live("r") + """
                              AND manager_id = $1 AND weight = FALSE
                            ORDER BY
                                CASE WHEN worked_until IS NULL THEN 0 ELSE 1 END,
                                worked_from DESC
                            LIMIT 1
                            """)
                        .execute(Tuple.of(managerId))
                        .compose(currentResult -> {
                            var it = currentResult.iterator();
                            Row mostCurrent = it.hasNext() ? it.next() : null;
                            if (mostCurrent == null) return Future.succeededFuture(reviewRow);
                            // Always sync the manager's company/title/logo from whatever
                            // the true most-current review is — this handles the case where a
                            // replace pushes the new review's dates earlier than another review.
                            String currentCompany = newId.equals(mostCurrent.getUUID("id"))
                                ? managerCompany
                                : mostCurrent.getString("manager_company");
                            String currentTitle = newId.equals(mostCurrent.getUUID("id"))
                                ? managerTitle
                                : mostCurrent.getString("manager_title");
                            String currentLogo = newId.equals(mostCurrent.getUUID("id"))
                                ? resolvedLogoUrl
                                : logoResolver.apply(currentCompany);
                            /*
                              Whether they are still in the role, decided the same way as their
                              company and title: by the most current opinion rather than by
                              whoever submitted last. A review that said nothing leaves the
                              manager's status alone - silence is not a claim that they retired.
                            */
                            String currentStatus = newId.equals(mostCurrent.getUUID("id"))
                                ? managerStatus
                                : mostCurrent.getString("manager_status");
                            /*
                              Where the manager works now, decided the same way as their company and
                              title: by whichever opinion is the most current one, not by whichever
                              arrived last. A rating about a role somebody left in 2019 must not
                              relocate a manager who is working somewhere else today - which the
                              ORDER BY above already guarantees, since a role with no end date sorts
                              ahead of every finished one.

                              An opinion that declared nothing leaves the manager's location alone
                              rather than clearing it: silence is not a claim that they work nowhere.
                            */
                            final DeclaredLocation currentLocation = newId.equals(mostCurrent.getUUID("id"))
                                ? resolvedDeclared
                                : new DeclaredLocation(
                                    mostCurrent.getString("declared_country"),
                                    mostCurrent.getString("declared_state"),
                                    mostCurrent.getString("declared_city"),
                                    mostCurrent.getString("declared_precision"),
                                    mostCurrent.getLong("company_location_id"));
                            // SELECT first to avoid aborting the transaction with a constraint
                            // violation. ON CONFLICT inside withTransaction leaves the
                            // connection in an aborted state; SAVEPOINT or pre-check avoids it.
                            return conn.preparedQuery("""
                                        SELECT id FROM companies
                                        WHERE LOWER(TRIM(name)) = LOWER(TRIM($1))
                                           OR slug = lower(regexp_replace(regexp_replace(lower(trim($1)), '[^a-z0-9\\s-]', '', 'g'), '\\s+', '-', 'g'))
                                        ORDER BY (LOWER(TRIM(name)) = LOWER(TRIM($1))) DESC
                                        LIMIT 1
                                        """)
                                .execute(Tuple.of(currentCompany))
                                .compose(existing -> {
                                    if (existing.iterator().hasNext()) {
                                        return Future.succeededFuture(existing);
                                    }
                                    return conn.preparedQuery("""
                                            INSERT INTO companies (name, status, slug, created_at, updated_at)
                                            VALUES ($1, 'ghost',
                                                lower(regexp_replace(regexp_replace(lower(trim($1)), '[^a-z0-9\\s-]', '', 'g'), '\\s+', '-', 'g')),
                                                now(), now())
                                            ON CONFLICT (slug) DO UPDATE SET updated_at = now()
                                            RETURNING id
                                            """)
                                        .execute(Tuple.of(currentCompany));
                                })
                                .compose(cmpResult -> {
                                    long cmpId = cmpResult.iterator().next().getLong("id");
                                    /*
                                      COALESCE on the status, not an assignment: a review that
                                      said nothing about it must leave the manager's own alone.
                                      Passing NULL here would retire somebody because the person
                                      rating them did not answer a question.
                                    */
                                    if (currentLocation.isEmpty()) {
                                        return conn.preparedQuery(
                                                "UPDATE managers SET updated_at = now(), company = $1, title = $2, company_logo_url = $3, company_id = $4, status = COALESCE($6, status) WHERE id = $5")
                                            .execute(Tuple.of(currentCompany, currentTitle, currentLogo, cmpId, managerId, currentStatus));
                                    }
                                    return conn.preparedQuery("""
                                            UPDATE managers
                                               SET updated_at = now(), company = $1, title = $2,
                                                   company_logo_url = $3, company_id = $4,
                                                   declared_country = $6, declared_state = $7,
                                                   declared_city = $8, declared_precision = $9,
                                                   company_location_id = $10,
                                                   status = COALESCE($11, status)
                                             WHERE id = $5
                                            """)
                                        .execute(Tuple.of(currentCompany, currentTitle, currentLogo, cmpId, managerId,
                                                          currentLocation.country(), currentLocation.state(),
                                                          currentLocation.city(), currentLocation.precision(),
                                                          currentLocation.companyLocationId(), currentStatus));
                                })
                                // The post-decision row when the rating was held, so the response
                                // says what the server actually did rather than what the INSERT
                                // returned a moment earlier.
                                .map(ignored -> afterDecision != null ? afterDecision : reviewRow);
                        }));
                }));
        }).compose(row -> {
            /*
                The recalculation comes first, and is awaited.

                company_stats_live is computed FROM managers.reviews_count and
                managers.overall_rating. Syncing it while the recalculation that writes those two
                columns is still in flight derives the company's figures from the previous
                numbers, and nothing corrects them until the next review lands.

                The sync was already awaited, and a comment here said so. The thing it depends on
                was not, which made the guarantee half a guarantee.
            */
            return managerRepo.recalculate(managerId)
                .compose(recalced -> companyRepo.syncStatsForManager(managerId))
                .map(statsDone -> row);
        });
    }

    // ── GET manager reviews ───────────────────────────────────────────────────

    public Future<JsonObject> getManagerReviews(long managerId, int limit, int offset,
                                                  String sortBy, UUID userIdFilter) {
        return getManagerReviews(managerId, limit, offset, sortBy, userIdFilter, null);
    }

    /**
     * The manager's ratings as this caller may see them.
     *
     * <p>Resolving the caller from their token rather than trusting the {@code userId} query
     * parameter is the whole point: that parameter is a display filter anybody can set, so
     * granting visibility on it would hand every withheld rating to anyone willing to guess an id.
     *
     * <p>An unauthenticated or unknown caller degrades to the public view rather than failing.
     * Reading a manager profile has never required an account and must not start to.
     */
    public Future<JsonObject> getManagerReviews(long managerId, int limit, int offset,
                                                  String sortBy, UUID userIdFilter,
                                                  String callerAuth0Id) {
        Future<Row> viewer = callerAuth0Id == null
            ? Future.succeededFuture((Row) null)
            : userRepo.findByAuth0IdWithBan(callerAuth0Id).map(opt -> opt.orElse(null))
                .otherwise((Row) null);

        return viewer.compose(v -> {
            UUID    viewerId = v == null ? null : v.getUUID("id");
            boolean isAdmin  = v != null && "admin".equals(v.getString("role"));
            return buildReviewsPage(managerId, limit, offset, sortBy, userIdFilter, viewerId, isAdmin);
        });
    }

    private Future<JsonObject> buildReviewsPage(long managerId, int limit, int offset,
                                                String sortBy, UUID userIdFilter,
                                                UUID viewerId, boolean isAdmin) {
        Future<Long>        totalFuture = reviewRepo.countByManager(managerId, userIdFilter, viewerId, isAdmin);
        Future<RowSet<Row>> dataFuture  = reviewRepo.findByManager(managerId, limit, offset, sortBy, userIdFilter, viewerId, isAdmin);

        return Future.all(totalFuture, dataFuture)
            .map(cf -> {
                JsonArray data = new JsonArray();
                for (Row row : dataFuture.result()) {
                    data.add(buildReviewJson(row));
                }
                return new JsonObject()
                    .put("data",   data)
                    .put("total",  totalFuture.result())
                    .put("limit",  limit)
                    .put("offset", offset);
            });
    }

    // ── GET manager career segments ───────────────────────────────────────────

    public Future<JsonObject> getManagerCareerSegments(long managerId, int limit, int offset) {
        return getManagerCareerSegments(managerId, limit, offset, null);
    }

    /** Same visibility rule as the review list: the public sees live, an author and an admin see theirs. */
    public Future<JsonObject> getManagerCareerSegments(long managerId, int limit, int offset,
                                                        String callerAuth0Id) {
        Future<Row> viewer = callerAuth0Id == null
            ? Future.succeededFuture((Row) null)
            : userRepo.findByAuth0IdWithBan(callerAuth0Id).map(o -> o.orElse(null)).otherwise((Row) null);
        return viewer.compose(v -> buildCareerSegments(managerId, limit, offset,
            v == null ? null : v.getUUID("id"),
            v != null && "admin".equals(v.getString("role"))));
    }

    private Future<JsonObject> buildCareerSegments(long managerId, int limit, int offset,
                                                    UUID viewerId, boolean isAdmin) {
        int effectiveLimit  = Math.min(Math.max(limit, 1), 50);
        int effectiveOffset = Math.max(offset, 0);
        return Future.all(
            reviewRepo.countCareerSegmentsByManager(managerId),
            // An anonymous caller goes through the public overload unchanged; only a signed-in
            // author or an admin needs the wider one.
            (viewerId == null && !isAdmin)
                ? reviewRepo.findCareerSegmentsByManager(managerId, effectiveLimit, effectiveOffset)
                : reviewRepo.findCareerSegmentsByManager(managerId, effectiveLimit, effectiveOffset, viewerId, isAdmin)
        ).map(cf -> {
            long total = cf.resultAt(0);
            RowSet<Row> rows = cf.resultAt(1);
            JsonArray segments = new JsonArray();
            for (Row row : rows) {
                boolean isCurrent   = Boolean.TRUE.equals(row.getBoolean("is_current"));
                LocalDate endRaw    = row.getLocalDate("end_date");
                LocalDate startRaw  = row.getLocalDate("start_date");

                JsonObject categoryAverages = new JsonObject()
                    .put("Communication Style",               r1(row.getBigDecimal("communication_style")))
                    .put("Perceived Approachability",         r1(row.getBigDecimal("perceived_approachability")))
                    .put("Perceived Clarity of Expectations", r1(row.getBigDecimal("perceived_clarity_of_expectations")))
                    .put("Feedback Style",                    r1(row.getBigDecimal("feedback_style")))
                    .put("Perceived Supportiveness",          r1(row.getBigDecimal("perceived_supportiveness")))
                    .put("Decision Making Style",             r1(row.getBigDecimal("decision_making_style")))
                    .put("Organization and Planning Style",   r1(row.getBigDecimal("organization_and_planning_style")))
                    .put("Delegation Style",                  r1(row.getBigDecimal("delegation_style")))
                    .put("Perceived Professional Demeanor",   r1(row.getBigDecimal("perceived_professional_demeanor")))
                    .put("Overall Working Experience",        r1(row.getBigDecimal("overall_working_experience")));

                LocalDate mrStart = row.getLocalDate("manager_role_start");
                LocalDate mrEnd   = row.getLocalDate("manager_role_end");
                segments.add(new JsonObject()
                    .put("company",           row.getString("company"))
                    .put("role",              row.getString("role"))
                    .put("startDate",         startRaw  != null ? startRaw.toString() : null)
                    .put("endDate",           isCurrent ? null : (endRaw != null ? endRaw.toString() : null))
                    .put("isCurrent",         isCurrent)
                    .put("averageRating",     r1(row.getBigDecimal("avg_rating")))
                    .put("reviewCount",       row.getLong("review_count").intValue())
                    .put("categoryAverages",  categoryAverages)
                    .put("managerRoleStart",  mrStart != null ? mrStart.toString() : null)
                    .put("managerRoleEnd",    mrEnd   != null ? mrEnd.toString()   : null));
            }
            return new JsonObject()
                .put("data",   segments)
                .put("total",  total)
                .put("limit",  effectiveLimit)
                .put("offset", effectiveOffset);
        });
    }

    private static double r1(BigDecimal v) {
        if (v == null) return 0.0;
        return Math.round(v.doubleValue() * 10.0) / 10.0;
    }

    // ── UPDATE review ─────────────────────────────────────────────────────────

    public Future<Row> updateReview(String auth0Id, long managerId, UUID reviewId, JsonObject body) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        Double overallRating       = body.getDouble("overallRating");
        JsonObject ratings         = body.getJsonObject("ratings");
        String managerCompany      = body.getString("managerCompany") != null ? body.getString("managerCompany").trim() : null;
        String managerTitle        = body.getString("managerTitle")   != null ? body.getString("managerTitle").trim()   : null;
        String text                = body.getString("text")           != null ? body.getString("text").trim()           : null;
        String authorType          = body.getString("authorType", "username");
        String clientAuthor        = toProperNameCase(body.getString("author", ""));
        LocalDate workedFrom       = parseYearMonth(body.getString("workedFrom"));
        LocalDate workedUntil      = parseYearMonth(body.getString("workedUntil"));
        LocalDate managerRoleStart = parseYearMonth(body.getString("managerRoleStart")); // optional for legacy edits
        LocalDate managerRoleEnd   = parseYearMonth(body.getString("managerRoleEnd"));
        LocalDate today = LocalDate.now();

        // ── User work date validation ─────────────────────────────────────────────
        if (workedFrom != null && workedUntil != null && workedFrom.isAfter(workedUntil)) return Future.failedFuture(ServiceException.badRequest("Your 'from' date cannot be later than your 'to' date"));
        if (workedFrom != null && workedFrom.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Your 'from' date cannot be in the future"));
        if (workedUntil != null && workedUntil.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Your 'to' date cannot be in the future"));

        // ── Manager role date validation (if provided) ───────────────────────────
        if (managerRoleStart != null) {
            if (managerRoleStart.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Manager role start date cannot be in the future"));
            if (managerRoleEnd != null) {
                if (managerRoleEnd.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Manager role end date cannot be in the future"));
                if (managerRoleEnd.isBefore(managerRoleStart)) return Future.failedFuture(ServiceException.badRequest("Manager role end date must be on or after the start date"));
            }
            // Cross-validation: user dates must fall within the manager's role period
            if (workedFrom != null && workedFrom.isBefore(managerRoleStart)) return Future.failedFuture(ServiceException.badRequest("Your start date cannot be before the manager started this role (" + formatYM(managerRoleStart) + ")"));
            if (managerRoleEnd != null && workedFrom != null && workedFrom.isAfter(managerRoleEnd)) return Future.failedFuture(ServiceException.badRequest("Your start date cannot be after the manager left this role (" + formatYM(managerRoleEnd) + ")"));
            if (managerRoleEnd != null && workedUntil != null && workedUntil.isAfter(managerRoleEnd)) return Future.failedFuture(ServiceException.badRequest("Your end date cannot be after the manager left this role (" + formatYM(managerRoleEnd) + ")"));
        }

        String missingReviewField = reviewFieldMissing(overallRating, ratings, managerCompany, managerTitle);
        if (missingReviewField != null) return Future.failedFuture(ServiceException.badRequest(missingReviewField));
        if (!isValidRating(overallRating)) return Future.failedFuture(ServiceException.badRequest("Overall rating must be between 1 and 5"));
        if (managerCompany.length() > 100) return Future.failedFuture(ServiceException.badRequest("Manager company must be at most 100 characters"));
        if (managerTitle.length()   > 100) return Future.failedFuture(ServiceException.badRequest("Manager title must be at most 100 characters"));
        if (text != null && text.length() > 2000) return Future.failedFuture(ServiceException.badRequest("Review text must be at most 2000 characters"));
        for (String key : RATING_KEYS) {
            if (!isValidRating(ratings.getDouble(key))) return Future.failedFuture(ServiceException.badRequest("Rating for '" + key + "' must be between 1 and 5"));
        }

        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("Unauthorized"));
                Row callerRow = opt.get();
                if (callerRow.getBoolean("is_banned")) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                UUID callerId = callerRow.getUUID("id");
                String dbUsername = callerRow.getString("username");
                String author;
                if ("anonymous".equals(authorType)) {
                    author = (!clientAuthor.isEmpty() && clientAuthor.length() <= 100) ? clientAuthor : generatePseudonym();
                } else if ("real_name".equals(authorType) && !clientAuthor.isEmpty() && clientAuthor.length() <= 100) {
                    author = clientAuthor;
                } else {
                    author = dbUsername;
                }

                return reviewRepo.findByUserForValidation(callerId, author)
                    .compose(existingRows -> {
                        List<Row> existing = new ArrayList<>();
                        for (Row r : existingRows) existing.add(r);

                        // ── Role duplicate (exclude current review) ───────────────────────
                        String normTitle   = managerTitle.trim().toLowerCase();
                        String normCompany = managerCompany.trim().toLowerCase();
                        boolean roleTaken = existing.stream()
                            .filter(r -> !r.getUUID("id").equals(reviewId))
                            .filter(r -> r.getLong("manager_id") == managerId)
                            .anyMatch(r -> {
                                String t = r.getString("manager_title");
                                String c = r.getString("manager_company");
                                return t != null && c != null
                                    && t.trim().equalsIgnoreCase(normTitle)
                                    && c.trim().equalsIgnoreCase(normCompany);
                            });
                        if (roleTaken) {
                            return Future.failedFuture(ServiceException.conflict("already_reviewed_this_role"));
                        }

                        // ── Manager role period overlap (exclude current review) ──────────
                        if (managerRoleStart != null) {
                            LocalDate newRoleEnd = managerRoleEnd != null ? managerRoleEnd : LocalDate.of(9999, 12, 31);
                            for (Row r : existing) {
                                if (r.getUUID("id").equals(reviewId)) continue; // skip self
                                if (r.getLong("manager_id") != managerId) continue;
                                LocalDate existRoleStart = r.getLocalDate("manager_role_start");
                                if (existRoleStart == null) continue;
                                LocalDate existRoleEndRaw = r.getLocalDate("manager_role_end");
                                LocalDate existRoleEnd = existRoleEndRaw != null ? existRoleEndRaw : LocalDate.of(9999, 12, 31);
                                boolean overlaps = !managerRoleStart.isAfter(existRoleEnd) && !existRoleStart.isAfter(newRoleEnd);
                                if (overlaps) {
                                    String existTitle   = r.getString("manager_title");
                                    String existCompany = r.getString("manager_company");
                                    return Future.failedFuture(ServiceException.conflict(
                                        "manager_role_overlap:" + existTitle + ":" + existCompany + ":" +
                                        formatYM(existRoleStart) + ":" + (existRoleEndRaw != null ? formatYM(existRoleEndRaw) : "present")));
                                }
                            }
                        }

                        /*
                          Where the opinion happened is only revisited when the form actually
                          asked again. A body with no declaredPrecision is an edit to something
                          else, and re-deriving a location there would migrate a 2019 opinion to
                          wherever the manager works today - the same failure the manager-transfer
                          case exists to prevent, arriving through the edit form instead.
                        */
                        DeclaredLocation declared = DeclaredLocation.fromBody(body);
                        boolean restatesLocation = !declared.isEmpty();
                        CorpusPlace corpusPlace  = CorpusPlace.fromBody(body.getJsonObject("corpusPlace"));

                        return ((Pool) db).withTransaction(conn ->
                            reviewRepo.findForProjection(conn, reviewId).compose(beforeOpt ->
                            (restatesLocation
                                ? resolveDeclaredForManager(conn, declared, corpusPlace, managerId)
                                : Future.<DeclaredLocation>succeededFuture(null))
                            .compose(resolved -> reviewRepo.update(conn,
                                reviewId, managerId, callerId, author, overallRating,
                                ratings.getDouble("Communication Style"), ratings.getDouble("Perceived Approachability"),
                                ratings.getDouble("Perceived Clarity of Expectations"), ratings.getDouble("Feedback Style"),
                                ratings.getDouble("Perceived Supportiveness"), ratings.getDouble("Decision Making Style"),
                                ratings.getDouble("Organization and Planning Style"), ratings.getDouble("Delegation Style"),
                                ratings.getDouble("Perceived Professional Demeanor"), ratings.getDouble("Overall Working Experience"),
                                managerCompany, managerTitle, text, workedFrom, workedUntil,
                                managerRoleStart, managerRoleEnd, resolved))
                            .compose(rowOpt -> {
                                if (rowOpt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Review not found"));
                                // The read model is maintained in the same transaction as the write
                                // it describes, per the incrementally-maintained-read-table rules:
                                // a projection updated afterwards is a projection that drifts the
                                // first time the second statement fails.
                                return reprojectEditedReview(conn, managerId,
                                        beforeOpt.orElse(null), rowOpt.get())
                                    .map(v -> rowOpt.get());
                            })))
                            // Awaited: the client refetches the manager the moment this
                            // returns, and an un-awaited recalculation loses that race - the
                            // edit appears to have had no effect until the next reload.
                            .compose(row -> managerRepo.recalculate(managerId)
                                .map(recalced -> row));
                    });
            });
    }

    // ── DELETE review ─────────────────────────────────────────────────────────

    public Future<JsonObject> deleteReview(String auth0Id, long managerId, UUID reviewId) {
        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("Unauthorized"));
                Row callerRow = opt.get();
                if (callerRow.getBoolean("is_banned")) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                UUID userId = callerRow.getUUID("id");
                return reviewRepo.findOwnerUserId(reviewId, managerId)
                    .compose(ownerOpt -> {
                        if (ownerOpt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Review not found"));
                        if (!ownerOpt.get().equals(userId)) return Future.failedFuture(ServiceException.forbidden("Forbidden"));
                        /*
                          The hide and the projection move together, or the figures keep counting
                          a rating the page no longer shows. Soft-deleting is a boundary crossing
                          like any other, so it goes through the projector rather than being
                          remembered here.
                        */
                        return ((Pool) db).withTransaction(conn ->
                                locationStats.contributionOf(conn, reviewId)
                                    .compose(before -> reviewRepo.delete(conn, reviewId, managerId)
                                        .compose(v -> locationStats.resyncManagerReview(conn, reviewId, before))))
                            .compose(v ->
                                /*
                                    Recalculate immediately after the soft-delete and before
                                    recordDeletion, so stale stats are never left behind if
                                    recordDeletion fails.

                                    That was the stated intent and it was only ever a comment:
                                    un-awaited, the recalculation could still be in flight when
                                    recordDeletion ran, or fail silently after the response had
                                    gone - leaving a deleted review still counted in the average.
                                */
                                managerRepo.recalculate(managerId)
                                    /*
                                        And sync the company, which this path never did at all.

                                        Deleting a review changed the manager's cached count and
                                        rating, but company_stats_live is derived from those two
                                        columns and nothing here told it to re-read them. The
                                        company's average therefore kept counting a review that
                                        had been removed - indefinitely, since the only other
                                        writer is the next mutation on that company.

                                        Separate defect from the ordering above; both live here.
                                    */
                                    .compose(recalced -> companyRepo.syncStatsForManager(managerId))
                                    .compose(synced -> reviewRepo.recordDeletion(userId, managerId)))
                            .map(v -> new JsonObject().put("success", true).put("message", "Review deleted"));
                    });
            });
    }


    // ── Crossing the projection boundary ──────────────────────────────────────

    /**
     * Brings back ratings whose three-day delete window has expired, as anonymous.
     *
     * <p>A delete here hides a rating rather than destroying it, and after three days it
     * resurfaces. That makes restoring a boundary crossing in the other direction, and the
     * projection has to follow: subtracting on delete without adding back on restore would leave
     * every restored rating permanently uncounted, silently and forever.
     *
     * <p>The ids are read first, because once {@code deleted_at} is cleared there is nothing left
     * to identify which rows this run touched.
     *
     * @return how many were restored
     */
    public Future<Integer> restoreExpiredReviewDeletions() {
        return ((Pool) db).withTransaction(conn ->
            conn.preparedQuery("""
                    SELECT id FROM reviews
                     WHERE deleted_at IS NOT NULL AND deleted_at < now() - INTERVAL '3 days'
                    """)
                .execute()
                .compose(rs -> {
                    List<UUID> ids = new ArrayList<>();
                    for (Row r : rs) ids.add(r.getUUID("id"));
                    if (ids.isEmpty()) return Future.succeededFuture(0);
                    return conn.preparedQuery("""
                            UPDATE reviews SET deleted_at = NULL
                             WHERE deleted_at IS NOT NULL AND deleted_at < now() - INTERVAL '3 days'
                            """)
                        .execute()
                        .compose(updated -> {
                            // They were out of the projection while hidden, so each starts from
                            // "contributing nothing" and is added back at whatever it now is.
                            Future<Void> chain = Future.succeededFuture();
                            for (UUID id : ids) {
                                chain = chain.compose(v ->
                                    locationStats.resyncManagerReview(conn, id, Optional.empty()));
                            }
                            return chain.map(ids.size());
                        });
                }));
    }

    /** Holds, releases or rejects a rating. See {@link ReviewDisposition}. */
    public Future<Void> setReviewDisposition(UUID reviewId, String disposition) {
        return ((Pool) db).withTransaction(conn -> reviewDisposition.set(conn, reviewId, disposition));
    }

    // ── REPLACE review (delete old + create new, no cooldown recorded) ────────

    public Future<Row> replaceReview(String auth0Id, long managerId, UUID oldReviewId, JsonObject body, String resolvedLogoUrl) {
        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("Unauthorized"));
                Row userRow = opt.get();
                if (userRow.getBoolean("is_banned")) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                UUID userId = userRow.getUUID("id");
                String dbUsername = userRow.getString("username");

                String authorType = body.getString("authorType", "username");
                String author;
                if ("real_name".equals(authorType) || "anonymous".equals(authorType)) {
                    String clientAuthor = toProperNameCase(body.getString("author", ""));
                    author = (clientAuthor.isEmpty() || clientAuthor.length() > 100) ? dbUsername : clientAuthor;
                } else {
                    author = dbUsername;
                }

                // Validate body synchronously before touching the DB — if the body is
                // invalid we must reject before deleting, otherwise the old review would
                // be permanently lost with nothing replacing it.
                ServiceException syncError = validateBodySync(body);
                if (syncError != null) return Future.failedFuture(syncError);

                return reviewRepo.findOwnerUserId(oldReviewId, managerId)
                    .compose(ownerOpt -> {
                        if (ownerOpt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Review not found"));
                        if (!ownerOpt.get().equals(userId)) return Future.failedFuture(ServiceException.forbidden("Forbidden"));
                        // Delete without recording cooldown, then create new review
                        return reviewRepo.delete(oldReviewId, managerId)
                            .compose(v -> validateAndInsertReview(body, managerId, userId, author, resolvedLogoUrl, null));
                    });
            });
    }

    /**
     * Runs all synchronous (non-DB) field validation on a review body.
     * Returns a {@link ServiceException} if invalid, or {@code null} if valid.
     * Used by replaceReview to guard against deleting a review before validation passes.
     */
    private ServiceException validateBodySync(JsonObject body) {
        if (body == null) return ServiceException.badRequest("Missing request body");
        Double overallRating  = body.getDouble("overallRating");
        JsonObject ratings    = body.getJsonObject("ratings");
        String managerCompany = body.getString("managerCompany");
        String managerTitle   = body.getString("managerTitle");
        String text           = body.getString("text");
        LocalDate workedFrom  = parseYearMonth(body.getString("workedFrom"));
        LocalDate workedUntil = parseYearMonth(body.getString("workedUntil"));
        LocalDate managerRoleStart = parseYearMonth(body.getString("managerRoleStart"));
        LocalDate managerRoleEnd   = parseYearMonth(body.getString("managerRoleEnd"));
        LocalDate today = LocalDate.now();

        if (managerRoleStart != null) {
            if (managerRoleStart.isAfter(today)) return ServiceException.badRequest("Manager role start date cannot be in the future");
            if (managerRoleEnd != null) {
                if (managerRoleEnd.isAfter(today)) return ServiceException.badRequest("Manager role end date cannot be in the future");
                if (managerRoleEnd.isBefore(managerRoleStart)) return ServiceException.badRequest("Manager role end date must be on or after the start date");
            }
        }
        if (workedFrom == null) return ServiceException.badRequest("Your start date working with this manager is required");
        if (workedFrom.isAfter(today)) return ServiceException.badRequest("Your 'from' date cannot be in the future");
        if (workedUntil != null && workedUntil.isAfter(today)) return ServiceException.badRequest("Your 'to' date cannot be in the future");
        if (workedUntil != null && workedFrom.isAfter(workedUntil)) return ServiceException.badRequest("Your 'from' date cannot be later than your 'to' date");
        if (managerRoleStart != null) {
            if (workedFrom.isBefore(managerRoleStart)) return ServiceException.badRequest("Your start date cannot be before the manager started this role (" + formatYM(managerRoleStart) + ")");
            if (managerRoleEnd != null && workedFrom.isAfter(managerRoleEnd)) return ServiceException.badRequest("Your start date cannot be after the manager left this role (" + formatYM(managerRoleEnd) + ")");
            if (managerRoleEnd != null && workedUntil != null && workedUntil.isAfter(managerRoleEnd)) return ServiceException.badRequest("Your end date cannot be after the manager left this role (" + formatYM(managerRoleEnd) + ")");
        }
        String missingSync = reviewFieldMissing(overallRating, ratings, managerCompany, managerTitle);
        if (missingSync != null) return ServiceException.badRequest(missingSync);
        if (managerCompany.length() > 100) return ServiceException.badRequest("Manager company must be at most 100 characters");
        if (managerTitle.length()   > 100) return ServiceException.badRequest("Manager title must be at most 100 characters");
        if (text != null && text.length() > 2000) return ServiceException.badRequest("Review text must be at most 2000 characters");
        if (!isValidRating(overallRating)) return ServiceException.badRequest("Overall rating must be between 1 and 5");
        for (int i = 0; i < RATING_KEYS.length; i++) {
            Double v = getRating(ratings, i);
            if (!isValidRating(v)) return ServiceException.badRequest("Rating for '" + RATING_KEYS[i] + "' must be between 1 and 5");
        }
        return null;
    }

    // ── GET my reviews ────────────────────────────────────────────────────────

    public Future<JsonObject> hasContributed(String auth0Id) {
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("User not found"));
                return userRepo.hasContributed(opt.get());
            })
            .map(contributed -> new JsonObject().put("hasContributed", contributed));
    }

    /** Returns true if the user identified by auth0Id has at least one review; false if null or not found. */
    public Future<Boolean> isContributor(String auth0Id) {
        if (auth0Id == null) return Future.succeededFuture(false);
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> opt.isPresent()
                ? userRepo.hasContributed(opt.get())
                : Future.succeededFuture(false));
    }

    public Future<JsonObject> getMyReviews(String auth0Id, int limit, int offset) {
        int effectiveLimit  = Math.min(Math.max(limit, 1), 50);
        int effectiveOffset = Math.max(offset, 0);
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("User not found"));
                UUID userId = opt.get();
                return Future.all(
                    reviewRepo.countByUser(userId),
                    reviewRepo.findByUser(userId, effectiveLimit, effectiveOffset)
                );
            })
            .map(cf -> {
                long total = cf.resultAt(0);
                RowSet<Row> rows = cf.resultAt(1);
                JsonArray data = new JsonArray();
                for (Row row : rows) data.add(buildMyReviewJson(row));
                return new JsonObject()
                    .put("data",   data)
                    .put("total",  total)
                    .put("limit",  effectiveLimit)
                    .put("offset", effectiveOffset);
            });
    }

    // ── Edit requests ─────────────────────────────────────────────────────────

    public Future<JsonObject> createEditRequest(String auth0Id, long managerId, JsonObject body) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));
        String newCompany        = body.getString("company");
        String newCompanyLogoUrl = body.getString("companyLogoUrl");
        String newTitle          = body.getString("title");
        String newStatus         = body.getString("status");
        String newCountry        = body.getString("country");
        String newLinkedinUrl    = body.getString("linkedinUrl");
        String startDateStr   = body.getString("startDate");
        String endDateStr     = body.getString("endDate");

        if (isBlank(newCompany) && isBlank(newTitle) && isBlank(newStatus) && isBlank(newCountry) && isBlank(newLinkedinUrl)
                && isBlank(startDateStr) && isBlank(endDateStr)) {
            return Future.failedFuture(ServiceException.badRequest("At least one field is required"));
        }
        if (newCompany    != null && newCompany.length() > 100)    return Future.failedFuture(ServiceException.badRequest("Company must be at most 100 characters"));
        if (newTitle      != null && newTitle.length()   > 100)    return Future.failedFuture(ServiceException.badRequest("Title must be at most 100 characters"));
        if (newStatus     != null && !newStatus.equals("active") && !newStatus.equals("retired")) return Future.failedFuture(ServiceException.badRequest("Status must be 'active' or 'retired'"));
        if (newCountry    != null && newCountry.length() > 100)    return Future.failedFuture(ServiceException.badRequest("Country must be at most 100 characters"));
        if (newLinkedinUrl != null && newLinkedinUrl.length() > 500) return Future.failedFuture(ServiceException.badRequest("LinkedIn URL must be at most 500 characters"));

        LocalDate startDateLocal = parseYearMonth(startDateStr);
        LocalDate endDateLocal   = parseYearMonth(endDateStr);
        OffsetDateTime newStartDate = startDateLocal != null ? startDateLocal.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() : null;
        OffsetDateTime newEndDate   = endDateLocal   != null ? endDateLocal.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()   : null;

        String effectiveCompany        = toNullIfBlank(newCompany);
        String effectiveCompanyLogoUrl = toNullIfBlank(newCompanyLogoUrl);
        String effectiveTitle          = toNullIfBlank(newTitle);
        String effectiveStatus         = toNullIfBlank(newStatus);
        String effectiveCountry        = toNullIfBlank(newCountry);
        String effectiveLinkedinUrl    = toNullIfBlank(newLinkedinUrl);

        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("User not found"));
                Row userRow = opt.get();
                if (userRow.getBoolean("is_banned")) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                UUID userId = userRow.getUUID("id");
                return editRepo.countSubmittedTodayByUser(userId)
                    .compose(todayEdits -> SubmissionLimits.checkDailyLimit(todayEdits, SubmissionLimits.DAILY_EDITS))
                    .compose(v -> managerRepo.findById(managerId)
                            .compose(mgrOpt -> {
                                if (mgrOpt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                                // The company the user picked travels with the request. The name
                                // beside it is a snapshot for the admin to read, not identity.
                                return editRepo.upsert(managerId, userId, effectiveCompany, body.getLong("companyId"), effectiveCompanyLogoUrl, effectiveTitle, effectiveStatus, effectiveCountry, effectiveLinkedinUrl, newStartDate, newEndDate)
                                    .map(row -> new JsonObject()
                                        .put("id", row.getUUID("id").toString())
                                        .put("managerId", managerId)
                                        .put("newCompany", effectiveCompany)
                                        .put("newTitle", effectiveTitle)
                                        .put("newStatus", effectiveStatus)
                                        .put("newCountry", effectiveCountry)
                                        .put("newLinkedinUrl", effectiveLinkedinUrl)
                                        .put("status", "pending")
                                        .put("createdAt", row.getOffsetDateTime("created_at").toString())
                                    );
                            }));
            });
    }

    public Future<JsonObject> getPendingEditsForManager(long managerId, String auth0Id) {
        if (auth0Id == null) return Future.succeededFuture(new JsonObject().put("data", new JsonArray()));
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.succeededFuture(new JsonObject().put("data", new JsonArray()));
                return editRepo.findPendingByManagerAndUser(managerId, opt.get())
                    .map(rows -> {
                        JsonArray result = new JsonArray();
                        for (Row row : rows) {
                            result.add(new JsonObject()
                                .put("id", row.getUUID("id").toString())
                                .put("newCompany", row.getString("new_company"))
                                .put("newTitle", row.getString("new_title"))
                                .put("newStatus", row.getString("new_status"))
                                .put("newCountry", row.getString("new_country"))
                                .put("newLinkedinUrl", row.getString("new_linkedin_url"))
                                .put("createdAt", row.getOffsetDateTime("created_at").toString())
                            );
                        }
                        return new JsonObject().put("data", result);
                    });
            });
    }

    // ── JSON builders ─────────────────────────────────────────────────────────

    public static JsonObject buildReviewJson(Row row) {
        JsonObject ratings = new JsonObject()
            .put("Communication Style",               row.getBigDecimal("communication_style"))
            .put("Perceived Approachability",         row.getBigDecimal("perceived_approachability"))
            .put("Perceived Clarity of Expectations", row.getBigDecimal("perceived_clarity_of_expectations"))
            .put("Feedback Style",                    row.getBigDecimal("feedback_style"))
            .put("Perceived Supportiveness",          row.getBigDecimal("perceived_supportiveness"))
            .put("Decision Making Style",             row.getBigDecimal("decision_making_style"))
            .put("Organization and Planning Style",   row.getBigDecimal("organization_and_planning_style"))
            .put("Delegation Style",                  row.getBigDecimal("delegation_style"))
            .put("Perceived Professional Demeanor",   row.getBigDecimal("perceived_professional_demeanor"))
            .put("Overall Working Experience",        row.getBigDecimal("overall_working_experience"));
        return new JsonObject()
            .put("id",            row.getUUID("id"))
            .put("managerId",     row.getLong("manager_id"))
            .put("author",        row.getString("author"))
            .put("overallRating", row.getBigDecimal("overall_rating"))
            .put("ratings",       ratings)
            .put("managerCompany", row.getString("manager_company"))
            .put("managerTitle",  row.getString("manager_title"))
            .put("text",          row.getString("text"))
            .put("verified",      row.getBoolean("verified"))
            .put("helpfulCount",  row.getInteger("helpful_count"))
            .put("createdAt",     row.getOffsetDateTime("created_at").toString())
            .put("updatedAt",     row.getOffsetDateTime("updated_at").toString())
            /*
              Where the opinion says the work happened. Exposed because the edit form has to open
              with what is already stored - without these, reopening a review showed an empty
              location field, and saving it would have looked like the person had cleared it.
            */
            .put("declaredCountry",   row.getString("declared_country"))
            .put("declaredState",     row.getString("declared_state"))
            .put("declaredCity",      row.getString("declared_city"))
            .put("declaredPrecision", row.getString("declared_precision"))
            .put("companyLocationId", row.getLong("company_location_id"))
            .put("workedFrom",    row.getLocalDate("worked_from")  != null ? row.getLocalDate("worked_from").toString()  : null)
            .put("workedUntil",   row.getLocalDate("worked_until") != null ? row.getLocalDate("worked_until").toString() : null)
            /*
              Whether this rating is on the site.

              Additive, and always "live" on any public surface - those filter on ReviewSql.live, so
              a held rating cannot appear there at all. It is only ever anything else on a caller's
              view of their own rating, which is exactly where a client needs to tell "saved" apart
              from "published".

              The alternative was for the client to POST and then ask a second endpoint what had
              just happened, which is a race the write itself does not have: the server knows the
              disposition at the moment it writes it.
            */
            .put("disposition",   dispositionOf(row));
    }

    /** Tolerates rows selected before V60, and rows from projections that omit the column. */
    private static String dispositionOf(Row row) {
        try {
            String value = row.getString("disposition");
            return value == null ? "live" : value;
        } catch (Exception e) {
            return "live";
        }
    }

    private JsonObject buildMyReviewJson(Row row) {
        JsonObject ratings = new JsonObject()
            .put("Communication Style",               row.getBigDecimal("communication_style"))
            .put("Perceived Approachability",         row.getBigDecimal("perceived_approachability"))
            .put("Perceived Clarity of Expectations", row.getBigDecimal("perceived_clarity_of_expectations"))
            .put("Feedback Style",                    row.getBigDecimal("feedback_style"))
            .put("Perceived Supportiveness",          row.getBigDecimal("perceived_supportiveness"))
            .put("Decision Making Style",             row.getBigDecimal("decision_making_style"))
            .put("Organization and Planning Style",   row.getBigDecimal("organization_and_planning_style"))
            .put("Delegation Style",                  row.getBigDecimal("delegation_style"))
            .put("Perceived Professional Demeanor",   row.getBigDecimal("perceived_professional_demeanor"))
            .put("Overall Working Experience",        row.getBigDecimal("overall_working_experience"));
        return new JsonObject()
            .put("id",            row.getUUID("id").toString())
            .put("managerId",     row.getLong("manager_id"))
            .put("managerName",   row.getString("manager_name"))
            .put("managerImage",  row.getString("manager_image"))
            .put("managerStatus", row.getString("manager_status"))
            .put("author",        row.getString("author"))
            .put("overallRating", row.getBigDecimal("overall_rating"))
            .put("ratings",       ratings)
            .put("managerCompany", row.getString("manager_company"))
            .put("managerTitle",  row.getString("manager_title"))
            .put("text",          row.getString("text"))
            .put("verified",      row.getBoolean("verified"))
            .put("helpfulCount",  row.getInteger("helpful_count"))
            .put("createdAt",     row.getOffsetDateTime("created_at").toString())
            .put("updatedAt",     row.getOffsetDateTime("updated_at").toString())
            .put("workedFrom",         row.getLocalDate("worked_from")        != null ? row.getLocalDate("worked_from").toString()        : null)
            .put("workedUntil",        row.getLocalDate("worked_until")       != null ? row.getLocalDate("worked_until").toString()       : null)
            .put("managerRoleStart",   row.getLocalDate("manager_role_start") != null ? row.getLocalDate("manager_role_start").toString() : null)
            .put("managerRoleEnd",     row.getLocalDate("manager_role_end")   != null ? row.getLocalDate("manager_role_end").toString()   : null);
    }

    private JsonObject buildManagerUpdateJson(Row row, RowSet<Row> chRows) {
        JsonArray careerHistory = new JsonArray();
        for (Row r : chRows) {
            careerHistory.add(new JsonObject()
                .put("company",   r.getString("company"))
                .put("title",     r.getString("title"))
                .put("startDate", r.getOffsetDateTime("start_date").toString())
                .put("endDate",   r.getOffsetDateTime("end_date") != null ? r.getOffsetDateTime("end_date").toString() : null)
            );
        }
        return new JsonObject()
            .put("id",             row.getLong("id"))
            .put("name",           row.getString("name"))
            .put("company",        row.getString("company"))
            .put("title",          row.getString("title"))
            .put("image",          row.getString("image"))
            .put("overallRating",  row.getBigDecimal("overall_rating"))
            .put("reviews",        row.getInteger("reviews_count"))
            .put("bio",            row.getString("bio"))
            .put("status",         row.getString("status"))
            .put("approvalStatus", row.getString("approval_status"))
            .put("categoryAverages", row.getJsonObject("category_averages"))
            .put("linkedinUrl",    row.getString("linkedin_url"))
            .put("country",        row.getString("country"))
            .put("createdAt",      row.getOffsetDateTime("created_at").toString())
            .put("careerHistory",  careerHistory);
    }

    // ── Validation helpers ────────────────────────────────────────────────────

    private static boolean isBlank(String s) { return Fields.isBlank(s); }

    private static boolean isValidRating(Double v) { return v != null && v >= 1 && v <= 5; }

    /**
     * Returns true if the first name should be allowed to auto-create a ghost manager.
     * Exactly 2-char names (DJ, TJ, etc.) always pass. All others must contain at least
     * one vowel — a/e/i/o/u/y (Y counts) — to avoid ghosting garbage keystrokes like "Lxmb".
     */
    static boolean firstNamePassesVowelCheck(String firstName) {
        String f = firstName == null ? "" : firstName.trim();
        if (f.length() == 2) return true;
        return f.toLowerCase().chars().anyMatch(c -> "aeiouy".indexOf(c) >= 0);
    }

    private static boolean isValidLinkedinUrl(String url) {
        return url != null && (url.startsWith("https://www.linkedin.com/") || url.startsWith("https://linkedin.com/"));
    }

    private static String formatYM(LocalDate d) {
        return d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + d.getYear();
    }

    private static LocalDate parseYearMonth(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return LocalDate.parse(str + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String toNullIfBlank(String s) {
        return (s != null && !s.isBlank()) ? s.trim() : null;
    }

    /**
     * Runs the tier decision for a rating attached to an existing manager row.
     *
     * <p>Reads the manager's own name and company rather than trusting anything the client sent:
     * the figures list identifies people, and the identity that matters is the row being rated,
     * not the text somebody typed on the way in.
     */
    private Future<Row> applyProofOfWork(io.vertx.sqlclient.SqlConnection conn,
                                         long managerId, UUID userId, UUID reviewId) {
        if (userId == null) return Future.succeededFuture(null);
        return conn.preparedQuery("SELECT name, company_id FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) return Future.succeededFuture((Row) null);
                Row m = rows.iterator().next();
                String[] parts = splitName(m.getString("name"));
                return proofOfWork.applyTo(conn, userId, managerId, reviewId,
                                           parts[0], parts[1], m.getLong("company_id"));
            });
    }

    /** "Satya Nadella" → {"Satya", "Nadella"}. A single-word name has no surname to compare. */
    static String[] splitName(String fullName) {
        if (fullName == null || fullName.isBlank()) return new String[] { "", "" };
        String[] words = fullName.trim().split("\\s+");
        if (words.length == 1) return new String[] { words[0], "" };
        // Everything after the first word is the surname, so "Mary Lou Retton" compares as
        // "Mary" / "Lou Retton" rather than silently dropping a name part.
        return new String[] {
            words[0],
            String.join(" ", java.util.Arrays.copyOfRange(words, 1, words.length))
        };
    }

    // Converts any casing variant to proper name case: "TIM COOK" / "tIM cOOk" → "Tim Cook".
    // Handles hyphenated names (Smith-Jones) and Irish/Scottish apostrophes (O'Brien).
    static String toProperNameCase(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return trimmed;
        String[] words = trimmed.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) result.append(' ');
            result.append(capitalizeNameWord(words[i]));
        }
        return result.toString();
    }

    private static String capitalizeNameWord(String word) {
        if (word.isEmpty()) return word;
        if (word.contains("-")) {
            String[] parts = word.split("-", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append('-');
                sb.append(capitalizeNameWord(parts[i]));
            }
            return sb.toString();
        }
        int ap = word.indexOf('\'');
        if (ap > 0 && ap < word.length() - 1) {
            String before = word.substring(0, ap);
            String after  = word.substring(ap + 1);
            return Character.toUpperCase(before.charAt(0)) + before.substring(1).toLowerCase()
                + "'"
                + Character.toUpperCase(after.charAt(0)) + after.substring(1).toLowerCase();
        }
        return Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase();
    }

    /** Returns the rating value, trying the pretty key first then the snake_case fallback. */
    /** Whether the caller actually supplied this category, as opposed to leaving it out. */
    private static boolean hasRating(JsonObject ratings, int index) {
        if (ratings == null) return false;
        return ratings.getValue(RATING_KEYS[index]) != null
            || ratings.getValue(RATING_KEYS_SNAKE[index]) != null;
    }

    private static double getRating(JsonObject ratings, int index) {
        Double v = ratings.getDouble(RATING_KEYS[index]);
        if (v == null) v = ratings.getDouble(RATING_KEYS_SNAKE[index]);
        return v != null ? v : 0.0;
    }

    // ── Find-or-create ────────────────────────────────────────────────────────

    /**
     * Overload for callers with no picker selection to pass on: identity is then resolved from the
     * company name, exactly as it was before IDs existed. Kept explicit rather than making the
     * parameter optional so that "no company was chosen" is stated at the call site rather than
     * being an accident of a shorter argument list.
     */
    public Future<JsonObject> findOrCreate(String auth0Id,
                                           String firstName, String lastName,
                                           String title, String company, String country,
                                           String state, String city,
                                           String resolvedLogoUrl) {
        return findOrCreate(auth0Id, firstName, lastName, title, company, country,
                            state, city, resolvedLogoUrl, null, SubmissionContext.NONE);
    }

    public Future<JsonObject> findOrCreate(String auth0Id,
                                           String firstName, String lastName,
                                           String title, String company, String country,
                                           String state, String city,
                                           String resolvedLogoUrl, Long companyId) {
        return findOrCreate(auth0Id, firstName, lastName, title, company, country,
                            state, city, resolvedLogoUrl, companyId, SubmissionContext.NONE);
    }

    /**
     * @param companyId the company the user actually picked, when they picked one. Non-null means
     *                  identity is already settled and {@code company} is display text only, which
     *                  is what stops a second spelling becoming a second company.
     */
    public Future<JsonObject> findOrCreate(String auth0Id,
                                           String firstName, String lastName,
                                           String title, String company, String country,
                                           String state, String city,
                                           String resolvedLogoUrl, Long companyId,
                                           SubmissionContext submission) {
        final GeoObservation observed = submission.observed();
        NameValidator.ValidationResult validation =
            NameValidator.validate(firstName, lastName, title, company, country);
        if (!validation.valid())
            return Future.failedFuture(ServiceException.badRequest(validation.reason()));

        String fullName = firstName.trim() + " " + lastName.trim();

        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty())
                    return Future.failedFuture(ServiceException.unauthorized("User not found"));
                UUID userId = opt.get();

                /*
                  The company half, by identity when we have one.

                  The client resolves which company was chosen and sends its id; this used to
                  match on the text it happened to be stored under instead, so "Revvity" and
                  "Revvity Chemagen Technologie" looked like two unrelated employers and a search
                  for one could not see the managers of the other.

                  Scope is the selected company plus its DESCENDANTS - downward only. Selecting a
                  parent considers its subsidiaries, because somebody looking for a colleague
                  should not have to know which legal entity payroll uses. Selecting a child does
                  NOT pull in its parent or its siblings: those people do not work at the company
                  that was chosen.

                  Text remains the fallback for a search that resolved no company at all.
                */
                Future<RowSet<Row>> searchFuture = companyId == null
                    ? managerRepo.search(5, 0, "%" + fullName + "%", "%" + company.trim() + "%", "featured", userId)
                    /*
                      Note what is matched here: the FIRST name, not the whole typed string.

                      The company scope has already done the narrowing - this is one employer and
                      its subsidiaries, not the directory - so the SQL's job is to hand the name
                      rule a small, relevant set to judge. Matching the full string here instead
                      put the decision back in an ILIKE: "Daniel Pa" excluded Daniel Perovic
                      before anything had a chance to notice they might be the same person, which
                      is the bug this whole change exists to fix.

                      Company resolution narrows; name resolution decides.
                    */
                    : companyRepo.findDescendantIds(companyId).compose(scope ->
                          managerRepo.searchInCompanies(20, 0, "%" + firstName.trim() + "%", scope, "featured", userId));

                return Future.all(
                    userRepo.hasContributed(userId),
                    searchFuture
                ).compose(cf -> {
                    boolean     contributed = cf.resultAt(0);
                    RowSet<Row> rows        = cf.resultAt(1);

                    /*
                      An exact name is a match. A plausible partial is a CANDIDATE.

                      This gate used to accept only equalsIgnoreCase on the whole name, which made
                      the search above decorative: whatever it returned was discarded unless the
                      reader had typed the manager's name character for character. Somebody
                      searching "Daniel Pa" for Daniel Perovic therefore found nothing - and then
                      the create branch below minted a second Daniel at the same employer.

                      A candidate is surfaced and suppresses creation. It is deliberately NOT
                      treated as "this is definitely them": the reader decides, and the cost of
                      showing a near miss is a glance, where the cost of hiding it is a duplicate
                      human in the directory for good.
                    */
                    List<Row> matched = new ArrayList<>();
                    List<Row> candidates = new ArrayList<>();
                    for (Row row : rows) {
                        String candidateName = row.getString("name");
                        if (candidateName == null) continue;
                        if (candidateName.equalsIgnoreCase(fullName.trim())) matched.add(row);
                        else if (isPlausibleSameName(fullName.trim(), candidateName)) candidates.add(row);
                    }

                    // Pending managers are never returned to users — they are invisible until
                    // an admin approves them. Only approved/ghost managers are surfaced.
                    List<Row> visibleMatched = matched.stream()
                        .filter(r -> !"pending_approval".equals(r.getString("approval_status")))
                        .collect(Collectors.toList());

                    if (!visibleMatched.isEmpty()) {
                        JsonArray data = new JsonArray();
                        for (Row row : visibleMatched) data.add(rowToManagerJson(row));
                        return Future.succeededFuture(
                            new JsonObject()
                                .put("data", data)
                                .put("created", false)
                                .put("hasContributed", contributed));
                    }

                    /*
                      No exact match, but somebody close enough to be worth showing.

                      Returned as data so the reader sees them, and - the point of this branch -
                      nothing is created. Every row here is already approved or ghost, so each one
                      has a profile that will actually render: the tile invariant in section 41
                      holds.
                    */
                    List<Row> visibleCandidates = candidates.stream()
                        .filter(r -> !"pending_approval".equals(r.getString("approval_status")))
                        .collect(Collectors.toList());
                    if (!visibleCandidates.isEmpty()) {
                        JsonArray data = new JsonArray();
                        for (Row row : visibleCandidates) data.add(rowToManagerJson(row));
                        return Future.succeededFuture(
                            new JsonObject()
                                .put("data", data)
                                .put("created", false)
                                .put("candidates", true)
                                .put("hasContributed", contributed));
                    }

                    // visibleMatched is empty. Two sub-cases:
                    // (a) pending rows were filtered → manager already queued, don't create a duplicate
                    // (b) matched is completely empty → manager doesn't exist yet
                    boolean alreadyPending = !matched.isEmpty();

                    final String trimmedState = isBlank(state) ? null : state.trim();
                    final String trimmedCity  = isBlank(city)  ? null : city.trim();

                    if (alreadyPending) {
                        // Already in the admin queue — return empty, nothing to show.
                        return Future.succeededFuture(
                            new JsonObject()
                                .put("data", new JsonArray())
                                .put("created", false)
                                .put("hasContributed", contributed));
                    }

                    // Before creating anything, check for a Levenshtein-close name at the same
                    // company (same guard used in createManager). A typo like "John Smyth" must
                    // not spawn a new ghost/pending alongside the real "John Smith" at Starbucks.
                    Future<RowSet<Row>> fuzzyPool = companyId == null
                        ? managerRepo.findByCompanyExact(company.trim())
                        : companyRepo.findDescendantIds(companyId)
                              .compose(scope -> managerRepo.findInCompanies(scope));
                    return fuzzyPool.compose(fuzzyCandidates -> {
                        Row fuzzyMatch = findFuzzyNameMatch(fuzzyCandidates, fullName.trim());
                        if (fuzzyMatch != null && !"pending_approval".equals(fuzzyMatch.getString("approval_status"))) {
                            JsonArray data = new JsonArray().add(rowToManagerJson(fuzzyMatch));
                            return Future.succeededFuture(
                                new JsonObject()
                                    .put("data", data)
                                    .put("created", false)
                                    .put("hasContributed", contributed));
                        }

                    // Manager not found: ghost/pending flow (regardless of contribution status).
                    boolean shortNames = fullName.trim().length() < 4 || company.trim().length() < 4;

                    if (shortNames) {
                        // Short name — can't safely ghost. Return empty; user can add explicitly.
                        return Future.succeededFuture(
                            new JsonObject()
                                .put("data", new JsonArray())
                                .put("created", false)
                                .put("hasContributed", contributed));
                    }

                    // Vowel check: first names of 3+ chars with no vowels (a/e/i/o/u/y) look like
                    // garbage input (e.g. "Lxmb", "Qwrt"). Exactly 2-char names always pass (DJ, TJ).
                    // Failed check → send to pending_approval without claiming the slot so the user
                    // can retry with a correctly-spelled name.
                    if (!firstNamePassesVowelCheck(firstName)) {
                        final String fState2 = trimmedState;
                        final String fCity2  = trimmedCity;
                        return companyRepo.resolve(companyId, company, null, resolvedLogoUrl)
                            .compose(cRow -> managerRepo.createSearchPending(
                                fullName, company, title, country,
                                fState2, fCity2, resolvedLogoUrl, cRow.getLong("id"), userId))
                            .map(row -> new JsonObject()
                                .put("data", new JsonArray())
                                .put("created", false)
                                .put("hasContributed", contributed));
                    }

                    /*
                      A listed name never spends the slot.

                      Everybody gets one automatic ghost, ever. Searching "Steve Jobs" used to
                      consume it - so a first-time visitor could burn their one creation on a name
                      that was never going to publish, and the manager they actually came to add
                      would land in the admin queue instead. The search still records a pending row
                      for an admin to look at; it just does not cost the searcher anything.
                    */
                    return proofOfWork.isListedName(fullName).compose(listedName -> {
                    if (listedName) {
                        final String fStateL = trimmedState;
                        final String fCityL  = trimmedCity;
                        return companyRepo.resolve(companyId, company, null, resolvedLogoUrl)
                            .compose(cRow -> managerRepo.createSearchPending(
                                fullName, company, title, country,
                                fStateL, fCityL, resolvedLogoUrl, cRow.getLong("id"), userId))
                            .map(row -> new JsonObject()
                                .put("data", new JsonArray())
                                .put("created", false)
                                .put("hasContributed", contributed));
                    }

                    // Atomically claim the one-time ghost slot. Only one concurrent request wins;
                    // the loser gets empty results — no silent pending that could trigger a
                    // confusing rejection notification.
                    return userRepo.claimAutoCreatedManagerSlot(userId).compose(claimed -> {
                        if (!claimed) {
                            // Ghost slot already used — silently create a pending_approval for admin
                            // review. No notification is sent on approval or rejection: the user
                            // searched but did not explicitly submit, so they must not receive emails
                            // about a submission they aren't aware of.
                            final String fState3 = trimmedState;
                            final String fCity3  = trimmedCity;
                            return companyRepo.resolve(companyId, company, null, resolvedLogoUrl)
                                .compose(cRow -> managerRepo.createSearchPending(
                                    fullName, company, title, country,
                                    fState3, fCity3, resolvedLogoUrl, cRow.getLong("id"), userId))
                                .map(row -> new JsonObject()
                                    .put("data", new JsonArray())
                                    .put("created", false)
                                    .put("hasContributed", contributed));
                        }

                        // Slot claimed — create ghost. If the insert fails, release the slot so
                        // the user can try again on their next search.
                        return companyRepo.resolve(companyId, company, null, resolvedLogoUrl)
                            .compose(companyRow -> managerRepo.createAutoApproved(fullName, company, title, country,
                                trimmedState, trimmedCity, userId, resolvedLogoUrl, companyRow.getLong("id")))
                            .compose(row -> {
                                long newId = row.getLong("id");
                                // Composed, not transactional — unlike every other path here.
                                // The ghost flow is a chain of separate writes with a deliberate
                                // ordering invariant (createAutoApproved before the user's slot is
                                // marked), and wrapping it in a transaction would restructure that.
                                // So a failure here surfaces as an error and the recover() below
                                // releases the slot; it cannot silently vanish.
                                return geoObservations.record(
                                        GeoObservationRepository.SUBJECT_MANAGER, String.valueOf(newId),
                                        GeoObservationRepository.ACTION_SEARCH, observed)
                                    .compose(obsDone -> reviewRepo.createSeedReview(newId, company, title))
                                    /*
                                        The response must describe the manager AFTER the seed review, not before it.

                                        `row` is the insert's own RETURNING row, captured before the seed review existed -
                                        reviews_count 0, overall_rating null. createSeedReview only writes to `reviews`; the
                                        counts on the manager are written by recalculate(), which used to run fire-and-forget
                                        while the caller was handed the stale `row` regardless. The tile for a just-created
                                        profile therefore rendered with no rating even though the seed review was already in
                                        the database, and reloading fixed it - which is what made it look intermittent.

                                        syncStatsForManager is deliberately left as it was: it touches company_stats, not this
                                        row, and nothing here needs to wait for it.
                                    */
                                    .compose(ignored -> managerRepo.recalculate(newId))
                                    .compose(recalced -> managerRepo.findById(newId))
                                    .compose(fresh -> companyRepo.syncStatsForManager(newId)
                                        .map(statsDone -> fresh.orElse(row)))
                                    .recover(err -> {
                                        System.err.println("Seed review creation failed for auto-approved manager " + newId + ": " + err.getMessage());
                                        err.printStackTrace(System.err);
                                        return companyRepo.syncStatsForManager(newId)
                                            .compose(statsDone -> Future.succeededFuture(row));
                                    });
                            })
                            .recover(err -> {
                                // Ghost insert failed — release slot so user gets another chance
                                return userRepo.resetAutoCreatedManagerSlot(userId)
                                    .compose(v -> Future.failedFuture(err));
                            })
                            .map(row -> {
                                JsonArray data = new JsonArray().add(rowToManagerJson(row));
                                return new JsonObject()
                                    .put("data", data)
                                    .put("created", true)
                                    .put("hasContributed", contributed);
                            });
                    });
                    }); // isListedName guard
                    }); // findByCompanyExact fuzzy-guard
                });
            });
    }

    // ── Ghost capture ─────────────────────────────────────────────────────────

    /**
     * Creates a ghost manager record for early intent capture (no auth required).
     * Returns the existing record if a matching approved/ghost manager already exists.
     */
    public Future<JsonObject> createGhostManager(JsonObject body, String resolvedLogoUrl) {
        return createGhostManager(body, resolvedLogoUrl, SubmissionContext.NONE);
    }

    public Future<JsonObject> createGhostManager(JsonObject body, String resolvedLogoUrl,
                                                 SubmissionContext submission) {
        final GeoObservation observed = submission.observed();
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));
        // The address behind this request, already extracted by the same proxy-aware helper the
        // rate limiter uses. Null when we cannot identify one, which the quota treats as "no free
        // ghost" rather than as an unlimited supply.
        final String clientIp = submission.clientIp();
        // Carries the publish decision out of the async chain to the response below. The decision
        // is made before the insert; the response is built after it.
        final java.util.concurrent.atomic.AtomicBoolean publishedRef =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        String name    = toProperNameCase(body.getString("name"));
        String company = body.getString("company") != null ? body.getString("company").trim() : null;
        String title   = body.getString("title")   != null ? body.getString("title").trim()   : null;
        String country = body.getString("country") != null ? body.getString("country").trim() : null;
        String state   = body.getString("state")   != null ? body.getString("state").trim()   : null;
        String city    = body.getString("city")    != null ? body.getString("city").trim()    : null;
        if (isBlank(name) || isBlank(company) || isBlank(title) || isBlank(country)) {
            return Future.failedFuture(ServiceException.badRequest("Missing required fields"));
        }
        String tooLong = Fields.firstProblem(
            Fields.maxLength(name,    "Name"),
            Fields.maxLength(company, "Company"),
            Fields.maxLength(title,   "Title"),
            Fields.maxLength(country, "Country"));
        if (tooLong != null) return Future.failedFuture(ServiceException.badRequest(tooLong));
        if (isBlank(state)) state = null;
        if (isBlank(city))  city  = null;
        String geoTooLong = Fields.firstProblem(
            Fields.maxLength(state, "State"),
            Fields.maxLength(city,  "City"));
        if (geoTooLong != null) return Future.failedFuture(ServiceException.badRequest(geoTooLong));

        String[] nameParts = name.trim().split("\\s+", 2);
        String firstName = nameParts[0];
        String lastName  = nameParts.length > 1 ? nameParts[1] : "";
        NameValidator.ValidationResult nameValidation =
            NameValidator.validate(firstName, lastName, title, company, country);
        if (!nameValidation.valid())
            return Future.failedFuture(ServiceException.badRequest(nameValidation.reason()));

        final String fState = state;
        final String fCity  = city;

        return managerRepo.findCapturedByNameAndCompany(name, company)
            .compose(rows -> {
                if (rows.iterator().hasNext()) {
                    Row row = rows.iterator().next();
                    /*
                        `published` belongs here too, and its absence was the "Manager Not Found"
                        outage.

                        findCapturedByNameAndCompany deliberately matches 'pending_approval' as
                        well as 'approved' and 'ghost' - that is the point of it, so a second
                        visitor adopts an existing captured draft instead of creating a duplicate.
                        But this branch returned only {id, name, created}, with no `published`
                        field at all.

                        The client guards with `if (ghostRow?.published === false) return []`.
                        Undefined is not false, so the guard did not fire, and a clickable locked
                        tile was built pointing at a pending row. Clicking it reaches
                        getManagerById -> enforceSubmitterAccess, and a captured draft carries no
                        submitted_by, so the server refuses it to everybody: "Manager Not Found".

                        Reported three times from production. It only happens when somebody
                        searches a name that an earlier visitor had already half-typed into the add
                        form, which is why it looked intermittent.

                        The invariant is the one the corpus states: a tile is only ever rendered
                        for a row the profile page will actually serve. So this reports publishable
                        exactly when the row is one the profile page serves.
                    */
                    String existingStatus = row.getString("approval_status");
                    boolean servable = "approved".equals(existingStatus) || "ghost".equals(existingStatus);
                    return Future.succeededFuture(
                        new JsonObject()
                            .put("id", row.getLong("id"))
                            .put("name", row.getString("name"))
                            .put("created", false)
                            .put("published", servable)
                    );
                }
                /*
                  Two callers, two different meanings, and conflating them is what broke the
                  find flow in production.

                  `fromSearch` is a DELIBERATE SEARCH that found nobody. Per the product rule it
                  auto-adds the manager as 'ghost': live, publicly visible, and shown straight back
                  to the searcher as a clickable locked tile. Once per visitor - the client holds
                  that slot, the /find path holds it in users.has_auto_created_manager.

                  Without it, this is the ADD-FORM DROP-OFF capture: somebody started typing into
                  /add and may never submit. That lands in the admin queue as 'pending_approval'
                  and is never published - typing a name must not put a manager on the site.

                  Both used to create a captured draft. The search then rendered a tile linking to
                  a profile the server refused to serve, and every one of those clicks landed on
                  "Manager not found".
                */
                boolean fromSearch = Boolean.TRUE.equals(body.getBoolean("fromSearch"));

                /*
                  Whether this search may publish, decided BEFORE anything is written.

                  Two gates, and they are checked in this order on purpose:

                    1. the site-wide ceiling - if automatic creation is paused, nobody gets a
                       public manager, and no per-address quota is consumed while the site is
                       already under pressure;
                    2. this address's own quota - one auto-created manager per window, the
                       server-side backstop for the localStorage key a visitor can clear.

                  Failing either is NOT an error. The search still records a pending row for an
                  admin; it simply does not publish. The caller is told via `published` so it knows
                  not to render a tile - a tile for an unpublished row links to a profile the
                  server refuses to serve, which is the "Manager not found" outage.
                */
                Future<Boolean> mayPublish = !fromSearch
                    ? Future.succeededFuture(false)
                    : ghostSlots.withinSiteWideCeiling()
                        .compose(withinCeiling -> withinCeiling
                            ? ghostSlots.claim(clientIp)
                            : Future.succeededFuture(false));

                return mayPublish.compose(publish ->
                    companyRepo.resolve(body.getLong("companyId"), company, null, resolvedLogoUrl)
                    .compose(companyRow -> publish
                        ? managerRepo.createAutoApproved(name, company, title, country, fState, fCity,
                                                         null, resolvedLogoUrl, companyRow.getLong("id"))
                        : managerRepo.createCapturedDraft(name, company, title, country, fState, fCity,
                                                          resolvedLogoUrl, companyRow.getLong("id")))
                    .recover(err -> {
                        // The quota was spent before the insert was attempted, so a failure here
                        // would otherwise cost this address its window for nothing.
                        if (!publish) return Future.failedFuture(err);
                        return ghostSlots.release(clientIp).compose(released -> Future.failedFuture(err));
                    })
                    .map(row -> { publishedRef.set(publish); return row; }))
                    .compose(row -> {
                        long newId = row.getLong("id");
                        // Composed rather than transactional, for the same reason as the /find
                        // path: this is a chain of separate writes and wrapping it would change
                        // the capture flow's ordering.
                        return geoObservations.record(
                                GeoObservationRepository.SUBJECT_MANAGER, String.valueOf(newId),
                                GeoObservationRepository.ACTION_CREATE, observed)
                            .compose(obsDone -> reviewRepo.createSeedReview(newId, company, title))
                            /*
                                The response must describe the manager AFTER the seed review, not before it.

                                `row` is the insert's own RETURNING row, captured before the seed review existed -
                                reviews_count 0, overall_rating null. createSeedReview only writes to `reviews`; the
                                counts on the manager are written by recalculate(), which used to run fire-and-forget
                                while the caller was handed the stale `row` regardless. The tile for a just-created
                                profile therefore rendered with no rating even though the seed review was already in
                                the database, and reloading fixed it - which is what made it look intermittent.

                                syncStatsForManager is deliberately left as it was: it touches company_stats, not this
                                row, and nothing here needs to wait for it.
                            */
                            .compose(ignored -> managerRepo.recalculate(newId))
                            .compose(recalced -> managerRepo.findById(newId))
                            .compose(fresh -> companyRepo.syncStatsForManager(newId)
                                .map(statsDone -> fresh.orElse(row)))
                            .recover(err -> {
                                System.err.println("Seed review creation failed for ghost manager " + newId + ": " + err.getMessage());
                                return companyRepo.syncStatsForManager(newId)
                                    .compose(statsDone -> Future.succeededFuture(row));
                            });
                    })
                    .compose(row -> {
                        long newId = row.getLong("id");
                        JsonObject result = new JsonObject()
                            .put("id", newId)
                            .put("name", row.getString("name"))
                            .put("created", true)
                            // Whether a PUBLIC manager exists, not merely that a row was written.
                            // The client renders a tile and spends its one-per-browser slot only
                            // on true - a tile for an unpublished row points at a profile the
                            // server refuses to serve.
                            .put("published", publishedRef.get());
                        return publishedRef.get()
                            ? ghostSlots.recordManager(clientIp, newId).map(done -> result)
                            : Future.succeededFuture(result);
                    });
            });
    }

    /**
     * Silently creates a pending_approval manager when an anonymous user searches for a
     * non-existent manager after their one-time ghost slot has already been used.
     * Returns nothing useful to the caller — the record goes straight to the admin queue.
     */
    public Future<Void> captureAnonymousSearch(JsonObject body, String resolvedLogoUrl) {
        return captureAnonymousSearch(body, resolvedLogoUrl, SubmissionContext.NONE);
    }

    public Future<Void> captureAnonymousSearch(JsonObject body, String resolvedLogoUrl,
                                               SubmissionContext submission) {
        final GeoObservation observed = submission.observed();
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));
        String name    = toProperNameCase(body.getString("name"));
        String company = body.getString("company") != null ? body.getString("company").trim() : null;
        String title   = body.getString("title")   != null ? body.getString("title").trim()   : null;
        String country = body.getString("country") != null ? body.getString("country").trim() : null;
        String state   = body.getString("state")   != null ? body.getString("state").trim()   : null;
        // A name plus at least one identifying detail. Requiring all four threw away the searches
        // most worth keeping: someone who typed a name and a company, or a name and a job title,
        // was rejected outright. A bare first and last name is not worth an admin's time - there is
        // nothing to tell two people of that name apart - so that one is still declined.
        //
        // Country comes from geolocation rather than the person, and is frequently absent, so it
        // never counts as the identifying detail.
        boolean hasDetail = !isBlank(company) || !isBlank(title);
        if (isBlank(name) || !hasDetail)
            return Future.failedFuture(ServiceException.badRequest("Missing required fields"));
        String tooLong = Fields.firstProblem(
            Fields.maxLength(name,    "Name"),
            Fields.maxLength(company, "Company"),
            Fields.maxLength(title,   "Title"),
            Fields.maxLength(country, "Country"));
        if (tooLong != null) return Future.failedFuture(ServiceException.badRequest(tooLong));
        if (isBlank(state)) state = null;
        String stateTooLong = Fields.maxLength(state, "State");
        if (stateTooLong != null) return Future.failedFuture(ServiceException.badRequest(stateTooLong));

        String[] nameParts = name.trim().split("\\s+", 2);
        String firstName = nameParts[0];
        String lastName  = nameParts.length > 1 ? nameParts[1] : "";
        // Partial: the name is held to the full standard, the fields they did not reach are not.
        NameValidator.ValidationResult nameValidation =
            NameValidator.validatePartial(firstName, lastName, title, company, country);
        if (!nameValidation.valid())
            return Future.failedFuture(ServiceException.badRequest(nameValidation.reason()));

        final String fState = state;
        // managers.company and managers.title are NOT NULL, and a partial capture may hold only
        // one of them. Empty rather than invented: these rows land in the admin queue as
        // pending_approval and are never public, so a blank is a visible gap for the admin to
        // fill rather than a wrong answer presented as fact.
        final String fCompany = company != null ? company : "";
        final String fTitle   = title   != null ? title   : "";

        // Recorded before the branch, and with a null subject: what happened here is that somebody
        // searched, which is true whether or not it produced a row. Hanging the observation off the
        // created manager would silently drop every repeat search for a name we already hold —
        // exactly the searches worth counting.
        return geoObservations.record(GeoObservationRepository.SUBJECT_SEARCH, null,
                                      GeoObservationRepository.ACTION_SEARCH, observed)
            .compose(obsDone -> managerRepo.findCapturedByNameAndCompany(name, fCompany))
            // If an approved/ghost manager already exists, skip — it's already visible.
            // If it's already pending (from a prior anonymous capture), skip — don't duplicate.
            .compose(rows -> {
                if (rows.iterator().hasNext()) return Future.<Void>succeededFuture(); // already exists
                return companyRepo.resolve(body.getLong("companyId"), fCompany, null, resolvedLogoUrl)
                    .compose(companyRow -> managerRepo.createPending(
                        name, fCompany, fTitle, "active", country, fState, resolvedLogoUrl, companyRow.getLong("id")))
                    .mapEmpty();
            });
    }

    /**
     * Captures a full drop-off form submission (manager + review) with no authentication.
     * Used when a non-logged-in user fills the add-manager form and is shown the auth modal.
     * Creates a pending_approval manager with an anonymous review — goes to the admin queue.
     */
    public Future<JsonObject> createDropOffDraft(JsonObject body, String resolvedLogoUrl) {
        return createDropOffDraft(body, resolvedLogoUrl, SubmissionContext.NONE);
    }

    /**
     * Records one {@code review} observation, not a {@code manager} one: a drop-off is a captured
     * rating that happens to need a manager row to hang off. One submission, one observation, keyed
     * to the thing the person was actually writing.
     */
    public Future<JsonObject> createDropOffDraft(JsonObject body, String resolvedLogoUrl,
                                                 SubmissionContext submission) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        String name    = toProperNameCase(body.getString("name"));
        String company = body.getString("company") != null ? body.getString("company").trim() : null;
        String title   = body.getString("title")   != null ? body.getString("title").trim()   : null;
        String country = body.getString("country") != null ? body.getString("country").trim() : null;
        String state   = body.getString("state")   != null ? body.getString("state").trim()   : null;
        String status  = "retired".equals(body.getString("status")) ? "retired" : "active";

        if (isBlank(name) || isBlank(company) || isBlank(title) || isBlank(country))
            return Future.failedFuture(ServiceException.badRequest("Missing required fields: name, company, title, country"));
        String tooLong = Fields.firstProblem(
            Fields.maxLength(name,    "Name"),
            Fields.maxLength(company, "Company"),
            Fields.maxLength(title,   "Title"),
            Fields.maxLength(country, "Country"));
        if (tooLong != null) return Future.failedFuture(ServiceException.badRequest(tooLong));
        if (isBlank(state)) state = null;
        String stateTooLong = Fields.maxLength(state, "State");
        if (stateTooLong != null) return Future.failedFuture(ServiceException.badRequest(stateTooLong));

        JsonObject review = body.getJsonObject("review");
        if (review == null) return Future.failedFuture(ServiceException.badRequest("Missing review data"));

        String author = review.getString("author");
        if (isBlank(author)) author = generatePseudonym();

        String[] nameParts = name.trim().split("\\s+", 2);
        String firstName = nameParts[0];
        String lastName  = nameParts.length > 1 ? nameParts[1] : "";
        NameValidator.ValidationResult nameValidation = NameValidator.validate(firstName, lastName, title, company, country);
        if (!nameValidation.valid()) return Future.failedFuture(ServiceException.badRequest(nameValidation.reason()));

        UUID dropOffToken = null;
        String dropOffTokenStr = body.getString("draftToken");
        if (dropOffTokenStr != null && !dropOffTokenStr.isBlank()) {
            try { dropOffToken = UUID.fromString(dropOffTokenStr); } catch (IllegalArgumentException ignored) {}
        }

        final String fState  = state;
        final String fStatus = status;
        final String fAuthor = author;
        final UUID   fDropOffToken = dropOffToken;

        // reviews.manager_company and manager_title are NOT NULL. The form asks for both on step
        // one and the capture only fires once step one is valid, so a genuine submission always
        // carries them - this is a guard on a public endpoint, not the usual case. Falls back to
        // the top-level company and title the request already validated, rather than relaxing a
        // real constraint or inventing an employer.
        if (isBlank(review.getString("managerCompany"))) review.put("managerCompany", company);
        if (isBlank(review.getString("managerTitle")))   review.put("managerTitle",   title);

        // Nothing to keep, so nothing to create. Checked before any write: the failure below used
        // to happen after the manager row had already been committed, which is how three orphan
        // managers appeared for one abandoned review.
        if (review.getJsonObject("ratings") == null && review.getDouble("overallRating") == null)
            return Future.failedFuture(ServiceException.badRequest("Nothing to capture"));

        return managerRepo.findCapturedByNameAndCompany(name, company)
            .compose(rows -> {
                if (rows.iterator().hasNext()) {
                    Row existing = rows.iterator().next();
                    long existingId = existing.getLong("id");
                    String approvalStatus = existing.getString("approval_status");

                    // allowIncomplete, because this is a capture. Somebody who walked away at the
                    // sign-in step never reached the "when did you work with them" field, and
                    // demanding it here discards the exact submission the capture exists to save.
                    // This path was calling the strict overload, so every abandoned review for a
                    // new manager was validated as though it were a finished one, and rejected.
                    if ("ghost".equals(approvalStatus)) {
                        return validateAndInsertReview(review, existingId, null, fAuthor, resolvedLogoUrl, fDropOffToken, true, submission)
                            .compose(ignored -> reviewRepo.scheduleSeedExpiry(existingId))
                            .map(ignored -> new JsonObject().put("id", existingId).put("created", false));
                    } else {
                        return validateAndInsertReview(review, existingId, null, fAuthor, resolvedLogoUrl, fDropOffToken, true, submission)
                            .map(ignored -> new JsonObject().put("id", existingId).put("created", false));
                    }
                } else {
                    return companyRepo.resolve(body.getLong("companyId"), company, null, resolvedLogoUrl)
                        .compose(companyRow -> managerRepo.createPending(name, company, title, fStatus, country, fState, resolvedLogoUrl, companyRow.getLong("id")))
                        .compose(managerRow -> {
                            long managerId = managerRow.getLong("id");
                            return validateAndInsertReview(review, managerId, null, fAuthor, resolvedLogoUrl, fDropOffToken, true, submission)
                                .map(ignored -> new JsonObject().put("id", managerId).put("created", true))
                                // The manager row is already committed by the time the review can
                                // fail, and there is no transaction spanning the two. Rather than
                                // leave a manager nobody submitted sitting in the admin queue,
                                // take it back out. Safe to delete unconditionally: it was created
                                // by this request, moments ago, and nothing else can reference it.
                                .recover(err -> {
                                    System.err.println("Drop-off capture failed for new manager " + managerId
                                        + " (" + name + " @ " + company + "): " + err.getMessage()
                                        + " - removing the orphaned manager row");
                                    return managerRepo.delete(managerId)
                                        .recover(cleanupErr -> {
                                            System.err.println("Could not remove orphaned manager " + managerId
                                                + ": " + cleanupErr.getMessage());
                                            return Future.succeededFuture();
                                        })
                                        .compose(v -> Future.<JsonObject>failedFuture(err));
                                });
                        });
                }
            })
            // Logged here, never returned to the client. A capture is fire-and-forget by design and
            // the response tells an anonymous caller nothing about why it failed; this is so the
            // failure is visible to us rather than silent on both ends.
            .onFailure(err -> System.err.println(
                "Drop-off capture failed for " + name + " @ " + company + ": " + err.getMessage()));
    }

    private static final String[] PSEUDO_ADJ = {
        "Brave", "Swift", "Bold", "Calm", "Keen", "Wise", "Fair", "Kind",
        "Sharp", "Quiet", "Clear", "Warm", "Cool", "Bright", "Loyal"
    };
    private static final String[] PSEUDO_ANIMAL = {
        "Falcon", "Tiger", "Eagle", "Wolf", "Bison", "Crane", "Lynx",
        "Otter", "Raven", "Gecko", "Heron", "Panda", "Finch", "Moose"
    };
    private static final Random PSEUDO_RNG = new Random();

    /** Creates a pending_approval manager — goes to the admin queue with no seed review. */
    private Future<Row> createSearchPending(
            String fullName, String company, String title, String country,
            String state, String city, String resolvedLogoUrl, Long companyId, UUID userId) {
        return managerRepo.createSearchPending(fullName, company, title, country,
                state, city, resolvedLogoUrl, companyId, userId);
    }

    /** Mirrors the frontend generateUsername() format: AdjectiveAnimal + 10–99 */
    static String generatePseudonym() {
        String adj    = PSEUDO_ADJ[PSEUDO_RNG.nextInt(PSEUDO_ADJ.length)];
        String animal = PSEUDO_ANIMAL[PSEUDO_RNG.nextInt(PSEUDO_ANIMAL.length)];
        int    num    = 10 + PSEUDO_RNG.nextInt(90);
        return adj + animal + num;
    }

    private static JsonObject rowToManagerJson(Row row) {
        return new JsonObject()
            .put("id",             row.getLong("id"))
            .put("name",           row.getString("name"))
            .put("company",        row.getString("company"))
            .put("title",          row.getString("title"))
            .put("image",          row.getString("image"))
            .put("overallRating",  row.getBigDecimal("overall_rating"))
            .put("reviews",        row.getInteger("reviews_count"))
            .put("status",         row.getString("status"))
            .put("country",        row.getString("country"))
            .put("companyLogoUrl", row.getString("company_logo_url"))
            .put("approvalStatus", row.getString("approval_status"))
            // Industry of the manager's company, for the third line on manager cards.
            // Null until the AI classifier has run for that company.
            .put("industry",       optionalString(row, "industry"));
    }

    /**
     * Reads a column that may not be present in the result set at all.
     *
     * Rows reaching this mapper come from two shapes: SELECT_BODY, which joins companies and
     * therefore has `industry`, and INSERT ... RETURNING * on `managers` alone, which does not.
     * Vert.x throws NoSuchElementException for an absent column rather than returning null, so
     * a plain getString() here breaks every findOrCreate path that returns a freshly created
     * manager.
     */
    private static String optionalString(Row row, String column) {
        int index = row.getColumnIndex(column);
        return index < 0 ? null : row.getString(index);
    }
}

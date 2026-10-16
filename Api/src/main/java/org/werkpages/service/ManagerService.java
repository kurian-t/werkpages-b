package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.Promise;
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
                    /*
                      The resolved identity, for the logo chain. A provider is only ever given a
                      domain somebody established - never one derived from the name, because a
                      guess that resolves renders another company's logo with full confidence.
                      Absent until resolution has run, and absent means "show the letter".
                    */
                    String resolvedDomain = row.getString("domain");
                    String bfIcon         = row.getString("brandfetch_icon_url");
                    if (resolvedDomain != null && !resolvedDomain.isBlank()) co.put("domain", resolvedDomain);
                    if (bfIcon != null && !bfIcon.isBlank()) co.put("brandfetchIconUrl", bfIcon);
                    companies.add(co);
                }
                return new JsonObject().put("data", companies);
            });
    }

    /**
     * The logo the person CHOSE, falling back to the one guessed from the name.
     *
     * <p>The picker resolves a real domain and shows the logo built from it; the guess turns
     * "Lime" into lime.com, a real and unrelated company. When somebody actually picked, their
     * choice is the better answer and the guess is only there for when nobody did.
     *
     * <p>Neither is trusted on sight. Both travel to CompanyRepository, which validates the pair
     * and applies it only where it improves the stored identity.
     */
    private static String pickedOrResolvedLogo(JsonObject body, String resolvedLogoUrl) {
        String picked = body == null ? null : body.getString("companyLogoUrl");
        return picked != null && !picked.isBlank() ? picked : resolvedLogoUrl;
    }

    public Future<JsonObject> getCompanyProfile(String company) {
        return getCompanyProfile(company, null, null);
    }

    /**
     * @param pickedDomain  the domain of the company the reader CHOSE from the picker, or null
     * @param pickedLogoUrl the logo shown beside that choice, or null
     *
     * <h2>Why a page view carries a pick at all</h2>
     *
     * <p>Choosing a company in the dropdown and landing on its page used to change its mark: the
     * picker shows the logo built from a RESOLVED domain, while this method guesses one from the
     * name - "Lime" becomes lime.com, a real and unrelated company - and stores that guess with no
     * domain at all. So the reader clicked one logo and arrived at another, which is the complaint
     * this answers.
     *
     * <p>The pick is not trusted on sight. It goes through the same writer and the same provenance
     * ladder every other path uses, so it can fill an empty identity or correct a GUESSED one, and
     * can never flatten a resolved, consensus or admin-set identity. A reader browsing cannot
     * degrade a company's data; they can only improve it.
     */
    public Future<JsonObject> getCompanyProfile(String company, String pickedDomain,
                                                String pickedLogoUrl) {
        if (company == null || company.isBlank())
            return Future.failedFuture(ServiceException.badRequest("company parameter is required"));
        final String companyName = company.trim();
        /*
          The guess is the FALLBACK, never the preference. It is what this page has always done
          when nothing better is known, and it is why so many rows carry GUESSED identities.
        */
        String resolvedLogoUrl = pickedLogoUrl != null && !pickedLogoUrl.isBlank()
                               ? pickedLogoUrl : logoResolver.apply(companyName);
        return companyRepo.findByName(companyName)
            .compose(opt -> opt.isPresent()
                // An existing row still gets the pick offered to it: that is the whole point, and
                // the writer decides whether it outranks what is already there.
                ? companyRepo.resolve(opt.get().getLong("id"), companyName,
                                      pickedDomain, pickedLogoUrl)
                : companyRepo.findOrCreate(companyName, pickedDomain, resolvedLogoUrl))
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
                    putCompanyIdentity(mgr, row);
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
                    putCompanyIdentity(mgr, row);
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
        /*
          The company's own resolved identity, so its header renders the same logo its tile does.
          Read from the company row rather than derived from the name - a guessed domain that
          resolves shows a different company's mark with full confidence.
        */
        if (!managers.isEmpty()) {
            JsonObject first = managers.getJsonObject(0);
            String cDomain = first.getString("companyDomain");
            String cIcon   = first.getString("companyBrandfetchIconUrl");
            if (cDomain != null && !cDomain.isBlank()) result.put("domain", cDomain);
            if (cIcon   != null && !cIcon.isBlank())   result.put("brandfetchIconUrl", cIcon);
        }
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
    /**
     * Create a company from a name, or return the one that name already resolves to.
     *
     * <p>Rate limited per person and per address. This is the only endpoint that turns free text
     * into a public directory row, and it was the only write in the product with no per-user limit
     * - managers, reviews and edits are each capped at 10 a day while this had nothing but the
     * 30/minute per-IP write limiter, which permits 43,200 a day and is per address rather than per
     * account.
     *
     * <p>The cap counts the user AND the hashed address together, so twelve accounts from one
     * address are still twelve companies rather than one hundred and forty-four.
     *
     * <p><b>Returning an existing company does not count.</b> The endpoint is idempotent by design
     * - a double-clicked submit must not mint a second row - and charging somebody for resolving a
     * name that already exists would turn a safety feature into a penalty, and would let anybody
     * exhaust their own allowance by retrying.
     */
    public Future<JsonObject> createCompany(String name, String auth0Id, String clientIp) {
        if (name == null || name.trim().length() < 2) {
            return Future.failedFuture(ServiceException.badRequest("Company name must be at least 2 characters"));
        }
        String trimmed = name.trim();
        return companyRepo.findByNormalizedName(trimmed).compose(existing -> {
            if (existing.isPresent()) {
                Row row = existing.get();
                // Resolving an existing name is free. See the note on idempotency above.
                return Future.succeededFuture(new JsonObject()
                    .put("id", row.getLong("id"))
                    .put("name", row.getString("name"))
                    .put("created", false));
            }
            // Only a genuinely new row is charged, and the check happens before the write.
            return userRepo.findIdByAuth0Id(auth0Id).compose(createdBy ->
                   companyRepo.countRecentCompanyCreations(createdBy.orElse(null), clientIp)
                .compose(used -> {
                    if (used >= SubmissionLimits.DAILY_COMPANIES) {
                        return Future.failedFuture(ServiceException.tooManyRequests(
                            "You have added as many companies as we allow in a day. "
                          + "Please try again tomorrow."));
                    }
                    return companyRepo.findOrCreate(trimmed, null, logoResolver.apply(trimmed))
                        .compose(row -> companyRepo.recordCompanyCreation(
                                    createdBy.orElse(null), clientIp, row.getLong("id"))
                            .map(ignored -> new JsonObject()
                                .put("id", row.getLong("id"))
                                .put("name", row.getString("name"))
                                .put("created", true)));
                }));
        });
    }

    /**
     * The company id for a row that is NOT being published, honouring the daily company cap.
     *
     * <p>Three anonymous paths - the exhausted-slot branch of {@code createGhostManager},
     * {@code captureAnonymousSearch} and {@code createDropOffDraft} - create a
     * {@code pending_approval} manager that nobody can see. Each one also called
     * {@code companyRepo.resolve}, which creates the company when the name is new, and a created
     * company is {@code status = 'ghost'}: publicly listed in the Companies tab the moment it
     * exists (CLAUDE.md section 18).
     *
     * <p>So an anonymous caller could mint unlimited <b>public</b> companies by repeating a capture
     * with a different company name each time, having published no manager at all. Nothing counted
     * it: the only brake was the shared 30-writes-a-minute limiter, which allows 43,200 a day.
     * Capping the authenticated endpoint alone would have left the larger hole open and keyed on
     * the one identity that is harder to get.
     *
     * <h2>Over the cap, the capture still happens</h2>
     *
     * <p>This returns {@code null} rather than failing. The row is written with no company id and
     * its free-text company name intact, which is the state section 17 already describes for a
     * pending manager: "company_id is nullable because pending/rejected managers may not have a
     * company entity attached yet". An admin approving it resolves the company then.
     *
     * <p>Failing instead would throw away the submission, which is the one thing the capture paths
     * exist to prevent (section 44). The person is not the attacker here; the refusal is about not
     * minting a public directory row, not about rejecting their work.
     *
     * <h2>What is NOT capped</h2>
     *
     * <p>The publishing branch of {@code createGhostManager} does not come through here. A
     * published ghost is bounded already, and far more tightly: one per address per window via
     * {@code anonymous_ghost_quota}, under a site-wide ceiling. It also must keep its company,
     * because a public manager with no company would not appear under any company in the
     * directory - and the section 41 invariant is that a tile is only rendered for a row the
     * profile page serves properly.
     *
     * <p>Selecting an existing company costs nothing, and neither does naming one that already
     * exists. Only a genuinely new row is charged, so ordinary use of these forms cannot exhaust
     * the allowance.
     */
    private Future<Long> companyIdForCapture(Long companyId, String companyName,
                                             String logoUrl, String clientIp) {
        // An explicit selection is identity that already exists. Nothing is minted, nothing
        // charged, and the id is honoured exactly as before.
        if (companyId != null) {
            return companyRepo.resolve(companyId, companyName, null, logoUrl).map(row -> row.getLong("id"));
        }
        if (isBlank(companyName)) return Future.succeededFuture(null);
        String trimmed = companyName.trim();

        return companyRepo.findByNormalizedName(trimmed).compose(existing -> {
            // Already in the directory: resolving it creates nothing.
            if (existing.isPresent()) return Future.succeededFuture(existing.get().getLong("id"));

            return companyRepo.countRecentCompanyCreations(null, clientIp).compose(used -> {
                if (used >= SubmissionLimits.DAILY_COMPANIES) {
                    // Over the cap. The capture proceeds without a company row; see above.
                    System.err.println("Company creation cap reached for this address; capturing \""
                        + trimmed + "\" without a company row");
                    return Future.succeededFuture((Long) null);
                }
                return companyRepo.findOrCreate(trimmed, null, logoUrl)
                    .compose(row -> companyRepo.recordCompanyCreation(null, clientIp, row.getLong("id"))
                        .map(ignored -> row.getLong("id")));
            });
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
        /*
          Bounded on input length before anything else: the token work is linear in the number of
          words, and an arbitrarily long paste should not become an arbitrarily large query.
        */
        String trimmed = query.trim();
        if (trimmed.length() > 120) trimmed = trimmed.substring(0, 120);

        /*
          THE LOCAL INDEX IS NOT A REPLACEMENT FOR THIS QUERY.

          It was wired in at the top of this method, returning its results INSTEAD of everything
          below. That dropped both Postgres tiers whenever the local index answered, and they are
          the tiers that must not be dropped: the buildings this company already has (carrying the
          company_location_id the frontend persists) and the geography somebody already confirmed
          for it. Losing them meant a branch that already existed stopped being offered, so the
          next person to name it created a SECOND row for the same building - the exact duplication
          CompanyLocationRepository.findOrCreate exists to prevent.

          The local index substitutes for ONE tier, the corpus buildings read, inside
          appendCorpusSuggestions where that read already happens. Everything else - the company
          filter, the country and state filters, the suggestion cap, the dedup, the seat allocation
          between coarse and exact answers - is left where it is and keeps applying.
        */
        return localSearchable(companyId, companyName, trimmed, country, state);
    }

    private Future<JsonArray> localSearchable(Long companyId, String companyName, String query,
                                              String country, String state) {
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

    /**
     * Whether a coarse row is the thing that was typed, rather than merely sharing a word with it.
     *
     * <p>Decides whether the expensive buildings file is read. A geography label leads with its own
     * name - "Elk Grove, California, United States" - so a query the coarse file genuinely answers
     * is a prefix of some label. "toronto" is; "canada goose" is not, and neither is a street
     * address, which is how both reach the buildings they need.
     *
     * <p>Compared on a collapsed, lower-cased form so punctuation and spacing do not decide it.
     */
    static boolean geographyAnswers(JsonArray geography, String query) {
        if (geography == null || geography.isEmpty()) return false;
        String needle = collapse(query);
        if (needle.isEmpty()) return false;
        for (int i = 0; i < geography.size(); i++) {
            JsonObject row = geography.getJsonObject(i);
            if (row == null) continue;
            /*
              Built from the row's own fields, because a raw corpus row has no "label" yet.

              This read getString("label"), which is null at this stage - the label is composed
              later, when a suggestion is built. So the comparison was always against an empty
              string, the gate never fired, and every keystroke read the 16.1M-row buildings file.
              The instrumentation added alongside it is what caught this: the decision column said
              a number on every single query and "skipped" on none.
            */
            /*
              AN INHABITED PLACE ONLY.

              This gate skips the expensive buildings file when somebody is plainly typing a city
              name. Worldwide geography broke that reasoning: there is a hamlet somewhere on earth
              beginning with almost any brand name, so "McDonalds" matched McDonalds Corner in
              Papua New Guinea - population 0 - and business search was skipped entirely. Measured
              through the endpoint, the query returned eight geography rows and no McDonald's.

              The corpus's population figure is the evidence. "Paris" is a city and skipping
              buildings for it is right; "McDonalds Corner" is not what anybody meant. A place the
              corpus gives no population counts as no evidence, so the gate does not fire and the
              buildings file is read - the safe direction, costing work rather than results.
            */
            if (row.getLong("population", 0L) <= 0L) continue;
            String name  = row.getString("name");
            String state = row.getString("stateName");
            String text  = collapse(name + " " + (state == null ? "" : state));
            if (text.startsWith(needle)) return true;
        }
        return false;
    }

    /** Lower-cased, punctuation-free, single-spaced, for comparing a typed query with a label. */
    static String collapse(String value) {
        if (value == null) return "";
        return value.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    /**
     * Whether some building matched the query as a phrase, rather than only its stripped reading.
     *
     * <p>Carried from the corpus query, where the distinction is made: a row matching every token
     * separately is a weak signal ("Northwest Face and Body" contains both words of "North Face"),
     * while a row containing the typed phrase is strong evidence that an employer was meant.
     */
    static boolean hasLiteralPlace(JsonArray places) {
        if (places == null) return false;
        for (int i = 0; i < places.size(); i++) {
            JsonObject row = places.getJsonObject(i);
            if (row != null && Boolean.TRUE.equals(row.getBoolean("literalHit"))) return true;
        }
        return false;
    }

    // ── Local corpus search (feature-flagged) ────────────────────────────────

    /**
     * The local corpus pool, or null when local search is off or no usable release is published.
     *
     * <p>Built once at startup, not per request: opening a multi-gigabyte database for a keystroke
     * would cost more than the search. Null is a supported state and means every suggestion falls
     * through to the existing remote path, so a missing or incompatible artifact degrades the
     * feature rather than the application.
     */
    /*
      RESOLVED ON FIRST USE, not at class load.

      It was a `static final` assigned from a static initialiser, which had two costs. Opening
      several DuckDB connections ran during class initialisation, so a slow or broken release
      delayed the first request that touched this class rather than the search it belongs to. And
      nothing could ever enable it in a test: the field was fixed before any test could set an
      environment variable, so the whole local path - including the defect where it REPLACED the
      Postgres tiers - was unreachable by the test suite and had to be verified by hand.
    */
    private static volatile org.werkpages.repository.LocalCorpusRegistry localRegistry;
    private static volatile boolean localPoolResolved;

    private static org.werkpages.repository.LocalCorpusRegistry localRegistry() {
        if (!localPoolResolved) {
            synchronized (ManagerService.class) {
                if (!localPoolResolved) {
                    localRegistry = openLocalPool();
                    localPoolResolved = true;
                }
            }
        }
        return localRegistry;
    }

    /** The pool for one country, or null when no local index can answer for it. */
    private static org.werkpages.repository.LocalCorpusPool localPool(String country) {
        org.werkpages.repository.LocalCorpusRegistry r = localRegistry();
        return r == null ? null
             : r.poolFor(org.werkpages.repository.LocationCorpusRepository.iso(country));
    }

    private static org.werkpages.repository.LocalCorpusRegistry openLocalPool() {
        org.werkpages.repository.OffloadedSearch.configure(intEnv("LOCAL_CORPUS_POOL", 3));
        // Off unless explicitly enabled, so the new path can be switched off without a deploy.
        if (!"true".equalsIgnoreCase(setting("LOCATION_LOCAL_SEARCH", "false"))) return null;
        String root = setting("LOCAL_CORPUS_ROOT", "/var/lib/location-corpus");
        int pool    = intEnv("LOCAL_CORPUS_POOL", 3);
        /*
          ONE THREAD PER QUERY, which is the configuration that was actually validated.

          DuckDB's `threads` is INSTANCE-wide, not per connection, so a pool of 3 at 2 threads each
          is not 6 workers politely sharing - it is one engine told to use 2 while three callers
          drive it. Concurrent search under that arrangement segfaulted the API outright on the
          older driver, and taking it to 1 is what stopped it.

          The default matters more than the env var here. The local path is off unless
          LOCATION_LOCAL_SEARCH is set, so whoever turns it on is reaching for one switch, and if
          this default disagrees with the approved candidate config they get an untested engine
          configuration at the same moment they expose it to traffic. Approved candidate: pool 3,
          one thread, 384MB DuckDB, 384MB heap, inside 2 GiB per API.
        */
        int threads = intEnv("LOCAL_CORPUS_THREADS", 1);
        String mem  = setting("LOCAL_CORPUS_MEMORY", "384MB");
        long waitMs = intEnv("LOCAL_CORPUS_ACQUIRE_MS", 120);
        /*
          HOW MANY COUNTRIES MAY BE OPEN AT ONCE.

          Each one is three DuckDB connections over a memory-mapped index, allocated outside the
          JVM heap, so this is the bound that keeps two backends inside the host's memory rather
          than the heap ceiling. Two is the measured default; the registry closes the least
          recently used beyond it, and closing drains rather than interrupting.
        */
        int countries = intEnv("LOCAL_CORPUS_COUNTRIES", 2);
        return org.werkpages.repository.LocalCorpusRegistry.open(
            java.nio.file.Path.of(root), pool, threads, mem,
            java.time.Duration.ofMillis(waitMs), countries);
    }

    /**
     * A setting from a system property, else the environment, else the fallback.
     *
     * <p>Production sets the environment, as it always has. The property is what lets a test enable
     * the local index against a fixture release; section 29 already relies on this precedence for
     * {@code api.version}, which the shaded docker client reads only as a property.
     */
    private static String setting(String name, String fallback) {
        String property = System.getProperty(name);
        if (property != null && !property.isBlank()) return property;
        String env = System.getenv(name);
        return (env == null || env.isBlank()) ? fallback : env;
    }

    private static int intEnv(String name, int fallback) {
        try { return Integer.parseInt(setting(name, null)); }
        catch (Exception e) { return fallback; }
    }

    /** Health and metrics for the local corpus, or null when it is not in use. */
    public static org.werkpages.repository.LocalCorpusPool.Stats localCorpusStats() {
        var r = localRegistry();
        return r == null || r.current() == null ? null : r.current().stats();
    }

    /**
     * Suggestions from the local corpus, or null to fall through to the remote path.
     *
     * <p>Returns null rather than an empty array on failure, so "the local index is unavailable"
     * and "there is no such place" stay distinguishable. Conflating them is what made an outage
     * look like a legitimate empty result earlier in this work.
     */
    /**
     * The buildings tier: the local index where it applies, the remote corpus otherwise.
     *
     * <p>Substituted HERE rather than ahead of the whole pipeline, so the Postgres tiers and every
     * filter around this call keep applying. See the note in suggestCompanyLocations.
     */
    /**
     * The configured shortlist, used only while the worldwide geography file is still unavailable.
     *
     * <p>Extracted so the readiness wait above has something to fall back TO. It searches CA, US
     * and GB in order and reads buildings from the first country only, which is what it always
     * did; the comments inside explain why each of those choices is the way it is.
     */
    private Future<JsonArray> shortlistSuggestions(JsonArray confirmed, String companyName,
                                                   String query, String state) {
        Future<JsonArray> chain = Future.succeededFuture(confirmed);
        boolean first = true;
        for (String fallback : DEFAULT_SEARCH_COUNTRIES) {
            // The DISPLAY name, not the code. This string is not only a search scope: it ends
            // up in the suggestion's label and in the country stored when somebody picks it,
            // so passing "CA" through would offer "Toronto, Ontario, CA" and then save a
            // manager's country as "CA" while every other row says "Canada".
            String named = displayNameOf(fallback);
            /*
              BUILDINGS ONLY FROM THE FIRST COUNTRY.

              Widening exists so somebody whose country we cannot read can still name their
              city. Geography answers that and is cheap; the buildings file is six times the
              cost and is not what "Palo Alto" is asking for. Reading it once per fallback
              country is what made an unmatched query take ten seconds: Canada found too few
              rows to fill the list, so the search moved to the United States and paid 4.8s
              for buildings nobody had asked about.

              Once a suggestion is picked the country is known, and the building search runs
              normally against it.
            */
            boolean places = first;
            first = false;
            chain = chain.compose(soFar -> soFar.size() >= SUGGESTION_LIMIT
                ? Future.succeededFuture(soFar)
                : appendCorpusSuggestions(soFar, companyName, query, named, null, places));
        }
    return chain;
    }

    private Future<JsonArray> placesTier(String companyName, String query, String country) {
        /*
          A BLANK COUNTRY STILL HAS TO FIND BUSINESSES.

          This regressed when worldwide geography went in: with no country, placesTier fell to
          remotePlaces(.., null), suggestPlaces refused a null country, and the buildings file was
          never read. Measured through the endpoint, "Walmart", "Starbucks" and "Tim Hortons"
          returned ZERO rows and "McDonalds" returned only McDonalds Corner, Papua New Guinea.

          That is the DEFAULT state of the location field - it opens with no country selected - so
          it was the most common first keystroke in the product that broke. Worldwide geography is
          additional discovery; it does not replace business search.
        */
        if (country == null || country.isBlank()) return placesWithoutCountry(companyName, query);
        if (coveredByLocalIndex(country)) {
            return localSuggestions(query, companyName, SUGGESTION_LIMIT, country).compose(local -> {
                if (local != null) org.werkpages.repository.CorpusDiagnostics.servedLocally(local.size());
                // null means the index could not answer (disabled, saturated, or failed). An EMPTY
                // list IS an answer and is returned: falling through on a genuine miss is what made
                // "Zzzqqx Nonexistent" a 26-second request, a full unprunable scan of the United
                // States partition to re-establish what the index settled in a millisecond.
                if (local != null) return Future.succeededFuture(local);
                return remotePlaces(companyName, query, country);
            });
        }
        // The US-only gate sent this elsewhere; counted so a validation run can tell a gated
        // request from one the index failed to answer.
        org.werkpages.repository.CorpusDiagnostics.fallbackNotCovered();
        return remotePlaces(companyName, query, country);
    }

    /**
     * Buildings when the caller named no country.
     *
     * <p>Candidates, in the order the agreed precedence requires:
     *
     * <ol>
     *   <li>the country the QUERY names, when it names one confidently - "Starbucks Paris" means
     *       France even with nothing selected, which is the case the configured list could never
     *       serve;</li>
     *   <li>the configured default countries, which are a deliberate choice rather than a guess.
     *       Nothing here infers a country from a business NAME - "Paris Baguette" is a bakery -
     *       and nothing restricts the search to whichever pools happen to be open, which would
     *       make the answer depend on what somebody else searched a moment ago.</li>
     * </ol>
     *
     * <p>Locally indexed candidates are read first because a local read costs milliseconds. A
     * candidate without a local index is NOT dropped: it is read remotely, but only while the
     * local ones have left the list short, because the buildings file is six times the cost of
     * geography over S3 and reading it per candidate is what made an unmatched query take ten
     * seconds.
     *
     * <p>Results are INTERLEAVED and DEDUPLICATED before the limit is applied. Taking a share each
     * and then topping up from the same countries added rows that were already present: three
     * candidates could return two each, and the top-up then re-added the first country's first two,
     * filling eight slots with six distinct places. Identity is the source place id, which is
     * stable across both the local and the remote path.
     */
    private Future<JsonArray> placesWithoutCountry(String companyName, String query) {
        return locationCorpus.intendedPlace(query).compose(intended -> {
            String intendedCountry = intended == null ? null : intended.getString("country_code");
            String intendedCity    = intended == null ? null : intended.getString("name");

            java.util.List<String> local = new java.util.ArrayList<>();
            java.util.List<String> remote = new java.util.ArrayList<>();
            String intendedNamed = intendedCountry == null ? null : displayNameOf(intendedCountry);
            if (intendedNamed != null && !intendedNamed.isBlank()) {
                if (coveredByLocalIndex(intendedNamed)) local.add(intendedNamed);
                else remote.add(intendedNamed);
            }
            for (String configured : DEFAULT_SEARCH_COUNTRIES) {
                String named = displayNameOf(configured);
                if (named == null || named.equals(intendedNamed)) continue;
                if (coveredByLocalIndex(named)) local.add(named); else remote.add(named);
            }
            /*
              A COUNTRY THE QUERY NAMED IS SEARCHED FIRST, EVEN WITH NO LOCAL INDEX FOR IT.

              Ordering by cost alone was wrong here. "McDonalds Tokyo" names Japan, which has no
              local release, so a cost-ordered list put Canada and the United States first, they
              filled the eight slots between them, and Japan was never reached - the inferred
              country was resolved and then thrown away. Stated intent outranks a configured
              default whichever storage happens to back it.
            */
            boolean intendedIsRemote = intendedNamed != null && remote.contains(intendedNamed)
                                    && !remote.isEmpty() && remote.get(0).equals(intendedNamed);
            /*
              THE ROUTING DECISION, RECORDED. Without this a first request that answered
              differently from the second could only be guessed at: the same typed query returned
              Canada once and France afterwards, and the geography-readiness warning did not fire,
              so the latch was not the explanation and there was nothing else to read.
            */
            System.err.println("location-route q=\"" + query + "\""
                + " intent=" + (intendedCountry == null ? "none" : intendedCountry)
                + " city=" + (intendedCity == null ? "none" : intendedCity)
                + " geographyReady=" + locationCorpus.geographyIsWorldwide()
                + " local=" + local + " remote=" + remote
                + " reason=" + (intendedCountry != null ? "query-geography" : "configured-default"));
            return gatherPlaces(companyName, query, local, remote, intendedCity, intendedIsRemote);
        });
    }

    /** Reads each candidate, then interleaves them so no one country takes the whole list. */
    private Future<JsonArray> gatherPlaces(String companyName, String query,
                                           java.util.List<String> local,
                                           java.util.List<String> remote,
                                           String city, boolean remoteFirst) {
        java.util.List<JsonArray> perCountry = java.util.Collections.synchronizedList(
            new java.util.ArrayList<>());
        Future<Void> chain = Future.succeededFuture();
        // The named country leads even when only S3 can serve it; see placesWithoutCountry.
        if (remoteFirst && !remote.isEmpty()) {
            String named = remote.get(0);
            chain = chain.compose(ignored ->
                remotePlaces(companyName, query, named, city).map(rows -> {
                    JsonArray verified = verifyCity(rows, city, named);
                    if (verified != null && !verified.isEmpty()) {
                        perCountry.add(stampAll(verified, named));
                    }
                    return null;
                }));
        }
        for (String named : local) {
            chain = chain.compose(ignored ->
                localSuggestions(query, companyName, SUGGESTION_LIMIT, named).map(rows -> {
                    // null means the index could not answer for that country - saturated, or no
                    // release for it. The other candidates still can.
                    if (rows != null && !rows.isEmpty()) perCountry.add(stampAll(rows, named));
                    return null;
                }));
        }
        return chain.compose(ignored -> {
            JsonArray merged = interleave(perCountry);
            /*
              AT MOST ONE REMOTE SCAN PER REQUEST, EVER.

              The buildings file over S3 is the most expensive read in the system, and reading it
              once per candidate country is what made an unmatched query take ten seconds. Running
              the named country remotely AND then topping up remotely did it again, measured: a
              country-free "McDonalds Tokyo" took 8.9 seconds across a Japanese scan, two local
              searches and a British scan.

              So if the named country was already fetched remotely, that was the one remote read
              this request is allowed. A configured country with no local index is still not
              silently omitted - it gets the slot whenever intent did not claim it.
            */
            if (merged.size() >= SUGGESTION_LIMIT || remote.isEmpty() || remoteFirst) {
                return Future.succeededFuture(merged);
            }
            String named = remote.get(0);
            return remotePlaces(companyName, query, named, city).map(rows -> {
                JsonArray verified = verifyCity(rows, city, named);
                if (verified != null && !verified.isEmpty()) {
                    perCountry.add(stampAll(verified, named));
                }
                return interleave(perCountry);
            });
        });
    }

    /**
     * One row from each country in turn, skipping places already taken.
     *
     * <p>Round robin rather than share-then-top-up, so the budget is spent on DISTINCT places and
     * every candidate country is represented while results last.
     */
    // Package-private so the deduplication and fairness rules can be asserted directly.
    static JsonArray interleave(java.util.List<JsonArray> perCountry) {
        JsonArray out = new JsonArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int[] at = new int[perCountry.size()];
        boolean progressed = true;
        while (out.size() < SUGGESTION_LIMIT && progressed) {
            progressed = false;
            for (int i = 0; i < perCountry.size() && out.size() < SUGGESTION_LIMIT; i++) {
                JsonArray rows = perCountry.get(i);
                while (at[i] < rows.size()) {
                    Object row = rows.getValue(at[i]++);
                    String id = row instanceof JsonObject object
                        ? object.getString("sourcePlaceId") : null;
                    if (id != null && !seen.add(id)) continue;   // already taken from elsewhere
                    out.add(row);
                    progressed = true;
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Keeps only the places whose city is CONFIRMED to be the one the query named.
     *
     * <p>A country match is not a city match, and presenting one as the other is the failure this
     * exists to stop: "McDonalds Tokyo" resolved Japan correctly and then offered a McDonald's in
     * KASUKABE, which satisfies nothing anybody asked for. Ordering the scan by city helps only
     * where the two datasets spell a city alike; Overture stores Japanese city names while the
     * geography corpus supplies romanised ones, so for Japan nothing matches and the ordering
     * changes nothing.
     *
     * <p>So when the city cannot be confirmed, the buildings are DROPPED rather than offered. That
     * is not a worse answer than Kasukabe - it is an honest one, and the worldwide geography row
     * for Tokyo is still returned, so the reader is offered the city itself and can pick it. An
     * unverifiable city match is reported in the log rather than disguised as a result.
     *
     * <p>Folded with LocalPlaceIndex.fold, the same rule the index uses, so "montreal" confirms
     * "Montreal" spelled with its accent and this does not reject on one.
     */
    // Package-private so the confirmed-city rule can be asserted directly.
    static JsonArray verifyCity(JsonArray rows, String city, String country) {
        if (rows == null || city == null || city.isBlank()) return rows;
        String want = org.werkpages.repository.LocalPlaceIndex.fold(city);
        JsonArray kept = new JsonArray();
        for (Object row : rows) {
            if (!(row instanceof JsonObject place)) continue;
            if (want.equals(org.werkpages.repository.LocalPlaceIndex.fold(place.getString("city")))) {
                kept.add(place);
            }
        }
        if (kept.isEmpty() && !rows.isEmpty()) {
            System.err.println("location-suggest: " + rows.size() + " place(s) in " + country
                + " could not be confirmed as " + city
                + "; dropped rather than offered as a match for it");
        }
        return kept;
    }

    private static JsonArray stampAll(JsonArray rows, String country) {
        for (Object row : rows) stamp(row, country);
        return rows;
    }

    /**
     * Records which country a raw corpus row was found in.
     *
     * <p>The local index carries a state code and no country, and until now the country came from
     * the REQUEST - which is empty on the first keystroke. A row with no country is not cosmetic:
     * findOrCreate stores it, and this very query filters on
     * {@code l.country IS NOT DISTINCT FROM $3}, so a building promoted without one became
     * invisible to the Postgres tier that should have found it next time. That defect has been
     * fixed once already; searching several candidate countries would have reintroduced it.
     *
     * <p>The country is known here because this row came from that country's index, by name.
     */
    private static Object stamp(Object row, String country) {
        if (row instanceof JsonObject object && object.getString("country") == null) {
            object.put("country", country);
        }
        return row;
    }

    private Future<JsonArray> remotePlaces(String companyName, String query, String country) {
        return remotePlaces(companyName, query, country, null);
    }

    /** @param city the city the query named, so the remote scan is not merely country-wide. */
    private Future<JsonArray> remotePlaces(String companyName, String query, String country,
                                           String city) {
        org.werkpages.repository.CorpusDiagnostics.remoteCall();
        return guarded(PLACES_BREAKER,
            () -> locationCorpus.suggestPlaces(companyName, query, country, city),
            "places", PLACES_DEADLINE_MS);
    }

    /**
     * Whether a local index holds this country at all.
     *
     * <p>This used to be a hardcoded list of names for the United States, because that was the
     * only country ever built. It is now whatever is published under the corpus root, so adding a
     * country is a matter of publishing its release rather than changing this code.
     *
     * <p>The reason the gate exists has not changed. A narrower index must never shadow a wider
     * one: answering a French query from the American index would return nothing, and because an
     * empty local answer is deliberately final it would REPLACE the remote answer that does cover
     * France with silence.
     *
     * <p>AN UNKNOWN OR ABSENT COUNTRY IS NOT COVERED, and is not guessed at. It is not taken to be
     * the country of whichever index happens to be open, nor inferred from the business name - a
     * name containing a place is not a request to search that place. Those requests go to the
     * remote corpus, which covers the world.
     *
     * <p>Matched on the display name because that is what this pipeline passes around - see the
     * note on displayNameOf - with the ISO code accepted too, both via
     * {@code LocationCorpusRepository.iso}.
     */
    // Package-private so the gate can be asserted directly; see LocationGateTest.
    static boolean coveredByLocalIndex(String country) {
        if (country == null || country.isBlank()) return false;   // widening handles this
        org.werkpages.repository.LocalCorpusRegistry r = localRegistry();
        return r != null && r.covers(org.werkpages.repository.LocationCorpusRepository.iso(country));
    }

    /** For the validation-only diagnostics listener; see CorpusDiagnostics. */
    public static JsonObject corpusDiagnostics() {
        var r = localRegistry();
        return org.werkpages.repository.CorpusDiagnostics.snapshot(
            r == null ? null : r.current(), corpusBreakerState());
    }

    /** Bounds a corpus read, so a slow one degrades to fewer suggestions rather than a hung form. */
    private static Future<JsonArray> withDeadline(Future<JsonArray> read, String what,
                                                  long deadlineMs) {
        final io.vertx.core.Context caller = io.vertx.core.Vertx.currentContext();
        Promise<JsonArray> bounded = Promise.promise();
        java.util.concurrent.ScheduledFuture<?> timer = DEADLINE_TIMER.schedule(() -> {
            if (!bounded.future().isComplete()) {
                // FAILS rather than answering empty, so the breaker can count it. A timeout that
                // looks like "no results" is indistinguishable from a dependency that is simply
                // down, and the breaker would then never open.
                resumeOn(caller, () -> bounded.tryFail(new java.util.concurrent.TimeoutException(
                    "corpus " + what + " exceeded " + deadlineMs + "ms")));
            }
        }, deadlineMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        read.onComplete(ar -> {
            timer.cancel(false);
            if (ar.succeeded()) bounded.tryComplete(ar.result());
            else bounded.tryFail(ar.cause());
        });
        return bounded.future();
    }

    /*
      ONE BREAKER PER TIER, not one for the corpus.

      They read different files at very different cost: geography is 194k rows, places is 16.1M.
      A failure peculiar to the large file must not stop the cheap read that answers "which city",
      which is what most of this field is actually asked.
    */
    private static final org.werkpages.repository.CorpusBreaker GEO_BREAKER =
        new org.werkpages.repository.CorpusBreaker("geography", 5, 30_000);
    private static final org.werkpages.repository.CorpusBreaker PLACES_BREAKER =
        new org.werkpages.repository.CorpusBreaker("places", 5, 30_000);

    /** Both breakers, for the health endpoint and the logs. */
    public static String corpusBreakerState() {
        return GEO_BREAKER.state() + " | " + PLACES_BREAKER.state();
    }

    /**
     * One corpus read: skipped when its breaker is open, bounded by a deadline, never failing.
     *
     * <p>A corpus read is allowed to be down; it is not allowed to look empty to something that
     * caches, and it is not allowed to make every keystroke wait for it to time out.
     */
    private Future<JsonArray> guarded(org.werkpages.repository.CorpusBreaker breaker,
                                      java.util.function.Supplier<Future<JsonArray>> read,
                                      String what, long deadlineMs) {
        if (!breaker.allow()) {
            // Open: answer from whatever else the caller has, immediately.
            org.werkpages.repository.CorpusDiagnostics.breakerSkip();
            return Future.succeededFuture(new JsonArray());
        }
        return withDeadline(read.get(), what, deadlineMs)
            .onSuccess(v -> breaker.recordSuccess())
            .otherwise(err -> {
                breaker.recordFailure();
                System.err.println("location corpus " + what + " unavailable: " + err);
                return new JsonArray();
            });
    }

    /*
      A DEADLINE PER TIER, sized from what each tier actually costs.

      One shared 1500ms deadline was wrong, and wrong in a way that would have shipped quietly. The
      two reads differ by more than six times: measured against the live bucket for one United
      States query, geography is 0.76s over 194k rows and places is 4.82s over 16.1M. A 1500ms bound
      therefore let geography through and cut places off EVERY time - and five consecutive cut-offs
      open the breaker, so the remote places tier would have switched itself off for thirty seconds
      at a stretch.

      That matters most where there is no local index: the release is United States only, so Canada
      and the United Kingdom always read remotely, and this would have degraded them while the
      change was nominally about making search faster. The flag being off would not have saved them
      either, because the deadline applies to the remote path regardless.

      The places bound still exists to stop the pathological case - a cold unprunable scan measured
      at 26 seconds - rather than to trim a normal read.
    */
    /*
      4s, not 1.5s. The first value was measured UNLOADED and had no margin.

      Geography reads 194k rows in 0.76s on an idle machine, so 1500ms looked like twice the
      headroom needed. Under two backends sharing two vCPUs it is not: measured 16 timeouts, the
      breaker opening twice, and 30s blackouts in which **61 of 112 city queries returned no
      geography at all**. A person typing "Chicago" was shown businesses in Chicago instead of the
      city, and no correctness check caught it because the response was neither empty nor
      unselectable - just wrong.

      The deadline exists to stop a dependency hanging a request, not to trim a slow-but-working
      read. Under contention this read legitimately takes seconds, so the bound belongs above that
      and the real fix is to stop making it remotely at all: the local index already holds the
      geography names it would need.
    */
    private static final long GEOGRAPHY_DEADLINE_MS = 4000;
    private static final long PLACES_DEADLINE_MS    = 6000;

    /** One daemon thread, for deadlines only. It never does work, it only fires timeouts. */
    private static final java.util.concurrent.ScheduledExecutorService DEADLINE_TIMER =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "location-deadline"); t.setDaemon(true); return t;
        });

    /** Run {@code action} back on the context that started the request, or inline if there is none. */
    private static void resumeOn(io.vertx.core.Context caller, Runnable action) {
        if (caller == null) action.run();
        else caller.runOnContext(v -> action.run());
    }

    /**
     * The local search, OFF THE EVENT LOOP.
     *
     * <p>Offloaded through {@link org.werkpages.repository.OffloadedSearch}, which is shared with the
     * tests that prove a saturated search does not stall the loop. {@code null} is the shed value:
     * it means "no answer from the index", which is what makes the caller fall back to the remote
     * corpus rather than treat an outage as an empty result.
     */
    private Future<JsonArray> localSuggestions(String query, String companyName, int limit,
                                               String country) {
        // Decided by COVERAGE, which opens nothing. Fetching a pool here and again inside the
        // blocking task left a window in which it could be evicted between the two; the registry
        // now reserves the country across the whole call. See LocalCorpusRegistry#withCountry.
        if (localRegistry() == null || !coveredByLocalIndex(country)) {
            return Future.succeededFuture(null);
        }
        /*
          THE CACHE IS CONSULTED HERE AND POPULATED WHERE THE QUERY RUNS, never around this call.

          Wrapping the whole call cached whatever came back, and what comes back is one of three
          things: an answer, an EMPTY array when the search executor sheds, or null when the index
          cannot answer. Caching a shed turned a momentary saturation into an hour of "no results"
          for that query.
        */
        String key = localCacheKey(query, companyName, limit, country);
        JsonArray hit = key == null ? null : locationCorpus.localSearchHit(key);
        if (hit != null) return Future.succeededFuture(hit);
        return org.werkpages.repository.OffloadedSearch.run(
            () -> localSuggestionsBlocking(query, companyName, limit, country),
            // Saturated: no suggestions for THIS keystroke. Never the remote scan, which would
            // amplify a local burst onto the metered path.
            new JsonArray(),
            // Genuinely broken: let the remote corpus try, bounded by its deadline.
            null);
    }

    /**
     * The key a local result is cached under, or null when it must not be cached at all.
     *
     * <p>Everything that can change the answer is in it: the words, the country, the company whose
     * brand biases the ranking, how many rows were asked for, and THE RELEASE that answered. The
     * release matters because immutability is what makes memoising safe in the first place - a new
     * release makes the old answers wrong, and without it they would be served until the hour ran
     * out.
     *
     * <p>Null before any pool has opened for that country, because the release is not yet known
     * and a key that cannot name it would collide across releases.
     */
    private static String localCacheKey(String query, String companyName, int limit,
                                        String country) {
        var registry = localRegistry();
        if (registry == null) return null;
        String iso = org.werkpages.repository.LocationCorpusRepository.iso(country);
        String release = registry.releaseIdFor(iso);
        if (iso == null || release == null) return null;
        return release + "|" + iso + "|" + (companyName == null ? "" : companyName)
             + "|" + limit + "|" + query;
    }

    private JsonArray localSuggestionsBlocking(String query, String companyName, int limit,
                                               String country) {
        org.werkpages.repository.LocalCorpusRegistry registry = localRegistry();
        if (registry == null) { org.werkpages.repository.CorpusDiagnostics.fallbackDisabled(); return null; }
        try {
            java.util.List<org.werkpages.repository.LocalPlaceIndex.Hit> hits =
                registry.withCountry(org.werkpages.repository.LocationCorpusRepository.iso(country),
                                     idx -> idx.search(query, limit,
                    org.werkpages.repository.LocalPlaceIndex.Completion.PARTIAL, companyName));
            /*
              RAW ROWS, in the shape suggestPlaces returns.

              This built FINISHED suggestion objects, which meant none of them survived: the merge
              reads `name` off each row to form the label, and a finished suggestion has `label`
              instead, so addCorpusPlaces hit its `label == null` guard and skipped every single
              local result. The local path silently contributed nothing, and the "places=N" log line
              counted what went INTO the merge rather than what came out of it, so it still looked
              like it was working.

              Emitting raw rows means addCorpusPlaces builds the label, the detail, the countryCode
              and the corpusPlace for BOTH paths. One implementation, so a local suggestion cannot
              drift from a remote one - and corpusPlace, which is what makes a suggestion
              selectable at all, is no longer assembled twice.
            */
            JsonArray out = new JsonArray();
            for (var h : hits) {
                out.add(new JsonObject()
                    .put("kind", "place")
                    .put("sourcePlaceId", h.sourcePlaceId())
                    .put("name", h.name())
                    .put("brandName", h.brandName())
                    .put("street", h.street())
                    .put("city", h.city())
                    .put("stateCode", h.stateCode())
                    .put("postalCode", h.postalCode()));
            }
            /*
              CACHED HERE, where a query demonstrably ran. A shed never reaches this line, and
              neither does a failure: both leave by the catch blocks below.
            */
            String key = localCacheKey(query, companyName, limit, country);
            if (key != null) locationCorpus.putLocalSearch(key, out);
            // The proof that a query really ran against the index, and what it returned.
            org.werkpages.repository.CorpusDiagnostics.localQuery(out.size());
            return out;
        } catch (org.werkpages.repository.LocalCorpusPool.Overloaded o) {
            // Shed rather than queue: an answer that arrives after the next keystroke is worthless.
            System.err.println("local corpus overloaded, falling back: " + o.getMessage());
            org.werkpages.repository.CorpusDiagnostics.fallbackShed();
            return null;
        } catch (Exception e) {
            System.err.println("local corpus search failed, falling back: " + e);
            org.werkpages.repository.CorpusDiagnostics.fallbackFailed();
            return null;
        }
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
    /**
     * The countries searched when the caller knows of none.
     *
     * <p>The corpus is partitioned by country, so a lookup needs one to read. The forms used to
     * always have one because the location field arrived prefilled from the visitor's IP - and
     * that prefill was removed deliberately, because a guessed city published against a named
     * person is identifying in a way nobody agreed to.
     *
     * <p>Removing it left nothing to scope the search by. A visitor whose geography we cannot
     * read - no Cloudflare header, which is every local developer and anyone behind a proxy that
     * strips it - got an empty list for every query, so the field could not be filled in at all.
     *
     * <p>A short, configured list rather than "search everywhere": every extra country is another
     * partition read on the same keystroke, and the answer for somebody in an unlisted country
     * still arrives as soon as they type its name, which {@code countryNamedIn} picks up.
     */
    private static final List<String> DEFAULT_SEARCH_COUNTRIES =
        List.of(defaultCountriesFromEnv().split("\\s*,\\s*"));

    /** A country's English display name, given its code or its name; the input if neither. */
    private static String displayNameOf(String country) {
        String code = org.werkpages.repository.LocationCorpusRepository.iso(country);
        if (code == null) return country;
        String name = java.util.Locale.of("", code).getDisplayCountry(java.util.Locale.ENGLISH);
        return (name == null || name.isBlank() || name.equalsIgnoreCase(code)) ? country : name;
    }

    /** The configured list, or the built-in one when nothing is set. */
    private static String defaultCountriesFromEnv() {
        String configured = System.getenv("LOCATION_DEFAULT_COUNTRIES");
        return (configured == null || configured.isBlank()) ? "CA,US,GB" : configured.trim();
    }

    private Future<JsonArray> appendCorpusSuggestions(JsonArray confirmed, String companyName,
                                                      String query, String country, String state) {
        return appendCorpusSuggestions(confirmed, companyName, query, country, state, true);
    }

    /**
     * @param withPlaces whether to read the buildings file as well as the geography one.
     *
     * <p>They cost very different amounts. Measured against the live bucket for one United States
     * query: <b>geography 0.76s over 194k rows, places 4.82s over 16.1M</b>. A location field is
     * mostly asked "which city", and the city lives in the cheap file.
     */
    private Future<JsonArray> appendCorpusSuggestions(JsonArray confirmed, String companyName,
                                                      String query, String country, String state,
                                                      boolean withPlaces) {
        if (confirmed.size() >= SUGGESTION_LIMIT) return Future.succeededFuture(confirmed);

        /*
          With no country there is no partition to read, so fall back to the configured list and
          merge what each one finds. Sequential rather than parallel on purpose: the first country
          usually answers, and firing three corpus reads per keystroke to discard two is the cost
          this whole area has been trying to remove.
        */
        /*
          ONE WORLDWIDE READ, when the geography file is local.

          The shortlist below exists because the S3 layout addresses one country partition per
          read, so searching everywhere meant 258 reads. The local file holds every country in
          54MB and answers in one, which makes the shortlist both unnecessary and wrong: a
          shortlist of CA, US and GB silently excludes the other 255 countries, so somebody in
          Dublin or Lagos got nothing back until they typed their country's name, and the first
          country on the list was read on every keystroke regardless.

          Nothing is guessed here. No country is assumed, none is inferred from the business name,
          and the answer is ranked by the corpus's own population figures so that the Paris with
          two million people comes before the one with 25,000.

          So the shortlist below runs ONLY while the file is still remote. Otherwise this falls
          through to the ordinary read with no country, which is now a worldwide one. The buildings
          file is not read without a country: it is addressed per country partition and has no
          worldwide form, and suggestPlaces returns nothing rather than guessing one.
        */
        if ((country == null || country.isBlank()) && !locationCorpus.geographyIsWorldwide()) {
            /*
              ONE BOUNDED WAIT BEFORE FALLING BACK, for the same reason the places tier waits: the
              file is usually already on disk and arrives within moments of the first request, and
              taking the shortlist instead means the FIRST search of a process answers differently
              from every one after it. That was observed and could not be explained until both
              tiers were instrumented.
            */
            return locationCorpus.awaitGeography().compose(ready -> ready
                ? appendCorpusSuggestions(confirmed, companyName, query, country, state, withPlaces)
                : shortlistSuggestions(confirmed, companyName, query, state));
        }

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object entry : confirmed) {
            String label = ((JsonObject) entry).getString("label");
            if (label != null) seen.add(label.toLowerCase());
        }

        /*
          Both at once. They are independent reads of different files, and running them one after
          the other doubled the wait on every keystroke for no reason.
        */
        /*
          GEOGRAPHY FIRST, AND USUALLY INSTEAD.

          These were read together on every keystroke, and they do not cost the same. Measured
          against the live bucket for one United States query: geography 0.76s over 194k rows,
          places 4.82s over 16.1M. Reading both meant every keystroke paid the larger number, and
          at the production thread count that is the 6-8s people were waiting for while typing a
          city.

          A location field is overwhelmingly asked "which city", and the city is in the cheap file.
          The expensive one answers a different question - "which branch of this company" - and it
          is only worth asking when the coarse answer came back empty, which is exactly what
          somebody naming a business rather than a place produces.

          So the buildings file is read only when geography found nothing. Typing "Toronto" no
          longer scans sixteen million rows to offer a bar and grill nobody asked about.
        */
        /*
          The corpus is allowed to be down; it is not allowed to look empty.

          Softened HERE rather than inside the repository, so the failure is visible to something
          that can act on it and is never written into the result cache as a valid answer. The
          reader still gets whatever Postgres confirmed, which is the part that must keep working:
          a form that cannot be submitted is worse than a form with fewer suggestions.
        */
        Future<JsonArray> geographyF =
            guarded(GEO_BREAKER, () -> locationCorpus.suggestGeography(query, country, null),
                    "geography", GEOGRAPHY_DEADLINE_MS);

        return geographyF.compose(geography -> {
            /*
              Places are skipped only when geography actually ANSWERED the question.

              This read `geography.isEmpty()`, so one coarse row anywhere suppressed the entire
              building search. "Canada Goose" matched the hamlet of Goose Cove, so the sixteen
              Canada Goose stores in the corpus - one of them at confidence 0.999 - were never
              looked for. The same silence hid North Face, and would hide Boston Pizza, Jersey
              Mike's and every other business whose name shares a single word with a place name.
              The cost saving was real; the condition for taking it was not.

              The question "which city" is answered when a geography row IS the thing typed, not
              when one merely shares a word with it. So the test is whether some coarse answer
              starts with the whole query: "toronto" is answered by "Toronto, Ontario, Canada" and
              skips the expensive file, while "canada goose" is answered by nothing and pays for
              it. Typing a city still costs 0.76s rather than 4.82s, which is what the optimisation
              was for.
            */
            /*
              THE SKIP IS AN S3 OPTIMISATION, AND ONLY APPLIES WHERE S3 IS INVOLVED.

              Skipping buildings when the query looks like a city name was worth it when the
              buildings file meant a multi-second scan of an S3 partition. Against a LOCAL index it
              costs milliseconds, and the gate's cost is wrong answers: there is a populated
              locality in Gujarat called "Reliance Complex", so a search for "Reliance" in India
              was answered with that township and NO businesses, while the index held 12,967
              matching postings. "McDonalds" matched a hamlet in Papua New Guinea the same way.
              The gate cannot reliably tell a city from a company, because plenty of places are
              named after companies.

              So where the country is served locally, the buildings are always read and the ranking
              decides. The gate survives only for the remote path, where the saving is real.
            */
            boolean needPlaces = withPlaces
                && (coveredByLocalIndex(country) || !geographyAnswers(geography, query));
            return (needPlaces ? placesTier(companyName, query, country)
                               : Future.succeededFuture(new JsonArray()))
                .map(places -> {
            JsonArray merged = confirmed.copy();

            /*
              One line per corpus suggestion, saying what actually happened.

              Silence was being read as success. A stray per-cent sign in a SQL comment threw on
              every query for a whole round of measurement, and because a failure returned an empty
              array the benchmark reported ten legitimate "no match" results. Nothing in the log
              said otherwise, and "no SQL error logged" was taken as evidence the query had run.

              So the decision is recorded rather than inferred: whether the buildings file was read
              at all, how much each tier returned, and which tier led. A skipped search and an
              empty one are different events and have to look different.
            */
            System.err.println("location-suggest q=\"" + query + "\" country=" + country
                + " geo=" + geography.size()
                + " places=" + (needPlaces ? String.valueOf(places.size()) : "skipped")
                + " lead=" + (geographyAnswers(geography, query) ? "city"
                            : hasLiteralPlace(places) ? "employer" : "default"));

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
            /*
              WHICH TIER LEADS IS DECIDED BY MATCH QUALITY, NOT BY A FIXED ALLOCATION.

              Geography used to hold the first three seats unconditionally, which is right for
              somebody typing a city and wrong for everybody else: searching "Canada Goose" put
              Goose Cove, Goose Creek and Goose River above the employer, which was the fourth row
              on a list that is only eight long. One more loosely matching hamlet would have pushed
              it off the list entirely.

              So the question asked is which tier actually answered:

                * a coarse row that IS what was typed - "toronto" against "Toronto, Ontario" -
                  means this is a city query, and the city leads;
                * otherwise a place matching the typed phrase means this is an employer query, and
                  the employer leads;
                * with neither, the old order stands, because a coarse answer is the one most
                  people can give and it must stay reachable.

              Both tiers always appear. This decides the order, never the membership, so somebody
              who wanted the city can still find it under an employer and the other way round.
            */
            int geoSeats = Math.min(GEOGRAPHY_FIRST, geography.size());
            boolean cityQuery     = geographyAnswers(geography, query);
            boolean employerQuery = !cityQuery && hasLiteralPlace(places);

            if (employerQuery) {
                addCorpusPlaces(merged, places, seen, country, SUGGESTION_LIMIT);
                addCorpusGeography(merged, geography, seen, country, SUGGESTION_LIMIT);
            } else {
                addCorpusGeography(merged, geography, seen, country,
                                   Math.min(merged.size() + geoSeats, SUGGESTION_LIMIT));
                addCorpusPlaces(merged, places, seen, country, SUGGESTION_LIMIT);
                addCorpusGeography(merged, geography, seen, country, SUGGESTION_LIMIT);
            }
            return merged;
                })
                .compose(merged -> {
                    if (merged.size() > confirmed.size()) return Future.succeededFuture(merged);
                    /*
                      A HOUSE NUMBER WE DO NOT HAVE MUST NOT BLANK THE STREET.

                      Overture lists businesses, not every address. "45 Cedarhill Crescent" found
                      nothing at all even though the corpus holds 142 and 143 on that street: every
                      token must match and no row contains "45". Somebody typing their real address
                      was told their street does not exist.

                      Retried without the numbers, and answered with the CITY rather than with the
                      other businesses on that street. Offering those would be worse than offering
                      nothing: picking one stores its company_location_id, so a person at number 45
                      would be recorded at number 142. A wrong precise answer is harder to notice,
                      and to undo, than a coarse right one.
                    */
                    String withoutNumbers = org.werkpages.repository.LocationCorpusRepository
                        .stripLeadingNumbers(query);
                    if (withoutNumbers == null) return Future.succeededFuture(merged);
                    return locationCorpus.citiesOnStreet(withoutNumbers, country).map(cities -> {
                        JsonArray out = merged.copy();
                        addCorpusGeography(out, cities, new java.util.HashSet<>(), country, SUGGESTION_LIMIT);
                        return out;
                    });
                });
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
                // The ROW's country when the request named none: see stamp(). A corpus row
                // promoted with a null country is written to Postgres with a null country and
                // then cannot be found by the tier that filters on it.
                .put("country", place.getString("country") != null
                                ? place.getString("country") : country)
                .put("city", place.getString("city"))
                .put("precision", DeclaredLocation.EXACT)
                // No companyLocationId: this building is not in the database yet. The client sends
                // corpusPlace back on submit and it is promoted then - see CorpusPlace.
                /*
                  THE COUNTRY GOES IN TOO, not just the code.

                  findOrCreate stores place.country(), and CorpusPlace.fromBody reads it from this
                  object - which carried only countryCode. So every location promoted from the
                  corpus was written with country = NULL, and the Postgres tier of this very query
                  filters on `l.country IS NOT DISTINCT FROM $3`. The effect was that a building,
                  once stored, could never be offered back by its id: the next contributor was
                  handed the corpus place again. The ON CONFLICT key on
                  (company_id, source, source_place_id) stopped that becoming a duplicate ROW, which
                  is why nothing looked broken, but the stored location was invisible to search.

                  The DISPLAY name, for the same reason the geography tier uses it: a row saying
                  "US" while every other row says "United States" does not match either.
                */
                .put("corpusPlace", place.copy()
                        .put("country", country)
                        .put("countryCode",
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
            /*
              THE COUNTRY COMES FROM THE ROW when the request did not name one.

              A worldwide search has no country to stamp on its results, and this used to stamp
              the request's - which was null. The country is not decoration: it goes into the
              label, and it is STORED when somebody picks the suggestion, so a null here would
              file a manager with no country while every other row has one. The row knows which
              country it is in; the request does not have to.
            */
            /*
              THE ROW'S OWN COUNTRY WINS whenever it has one.

              Reading the request's first was wrong as soon as a row could come from somewhere the
              request had not named. The geography fallback that offers the city a query named
              returns Tokyo, in JAPAN, and when the caller happened to be scanning its configured
              shortlist that row was labelled "Tokyo, Tokyo, Canada" and then "Tokyo, Tokyo,
              United States" - the same city, relabelled once per country searched. A row that
              knows where it is should never be told otherwise.
            */
            String rowCountry = geo.getString("countryCode") != null
                ? displayNameOf(geo.getString("countryCode"))
                : country;
            String label = "country".equals(kind) ? name
                         : "region".equals(kind)  ? join(", ", name, rowCountry)
                         : join(", ", name, stateName, rowCountry);
            if (!seen.add(label.toLowerCase())) continue;

            JsonObject item = new JsonObject()
                .put("kind", "geo")
                .put("label", label)
                .put("country", rowCountry);

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
            // The company's resolved identity, for the logo chain. Never a guessed domain.
            .put("companyDomain", identityOrNull(row, "company_domain"))
            .put("companyBrandfetchIconUrl", identityOrNull(row, "company_brandfetch_icon_url"))
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
        /*
          Workplace ratings, counted the same way the rest of the product counts them: live rows
          only. A withdrawn rating is gone as far as every reader is concerned, so an admin tile
          that still counted it would disagree with the company pages it is meant to summarise.
        */
        Future<Long> companyReviewsFuture = db.query("SELECT COUNT(*) FROM company_reviews WHERE deleted_at IS NULL")
            .execute().map(rows -> rows.iterator().next().getLong(0));
        return Future.all(java.util.List.of(userSubmittedFuture, realReviewsFuture, weightedOpinionsFuture,
                                            seededManagersFuture, scrapedManagersFuture, companyReviewsFuture))
            .map(cf -> new JsonObject()
                .put("realManagers",       userSubmittedFuture.result())
                .put("realReviews",        realReviewsFuture.result())
                .put("weightedOpinions",   weightedOpinionsFuture.result())
                /*
                  Still returned, though the admin panel no longer shows it. Removing a field from
                  a response is a breaking change for anything else reading it, and this one costs
                  a single COUNT.
                */
                .put("seededManagers",     seededManagersFuture.result())
                .put("scrapedManagers",    scrapedManagersFuture.result())
                .put("companyReviews",     companyReviewsFuture.result())
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
        /*
          Whether to publish the period. Absent means show, which is what every existing rating
          does - this is an opt-in to privacy, not a change of default. The dates themselves are
          still required and still stored: they drive the overlap check and the tenure maths, and
          only the DISPLAY is withheld.
        */
        boolean datesHidden   = reviewBody.getBoolean("datesHidden", false);

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
        final boolean  fDatesHidden   = datesHidden;
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
                                        fMgrCompany, fMgrTitle, fReviewText, fWorkedFrom, fWorkedUntil, fDraftToken,
                                        fDatesHidden);
                                }

                                // No match — create a new pending_approval manager with its first review.
                                // Resolve (or create) the company row first so we can link company_id.
                                OffsetDateTime startDt = fStartDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
                                OffsetDateTime endDt   = fEndDate != null ? fEndDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() : null;
                                final String fCompany = company;
                                return companyRepo.resolve(body.getLong("companyId"), company, body.getString("companyDomain"), pickedOrResolvedLogo(body, resolvedLogoUrl))
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
                                         declared_country, declared_state, declared_city, declared_precision, company_location_id,
                                         declared_source, declared_text)
                                        VALUES ($1,$2,$3,$4,$5,$6,'pending_approval',$7,$8,$9,$10,$11,$12,$13,0,0,'{}'::jsonb,now(),$14,
                                                $15,$16,$17,$18,$19,$20,$21)
                                        RETURNING *
                                        """)
                                        .execute(Tuple.of(name, fCompany, title, image, fBio, fStatus, fCountry, fState, fCity, fLinkedinUrl, resolvedLogoUrl, companyId, slug, userId,
                                                          loc.country(), loc.state(), loc.city(), loc.precision(), loc.companyLocationId(),
                                                          // Carried so a location somebody TYPED survives the insert. Without
                                                          // these the row keeps the words nowhere and reads as though it came
                                                          // from the corpus, which is the claim a manual answer may not make.
                                                          loc.source(), loc.text()))
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
                                                        worked_from, worked_until, dates_hidden,
                                                        verified, helpful_count, created_at, updated_at
                                                    )
                                                    VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,true,0,now(),now())
                                                    RETURNING id
                                                    """)
                                                    .execute(Tuple.of(
                                                        managerId, userId, author, fOverallRating,
                                                        getRating(fRatings, 0), getRating(fRatings, 1), getRating(fRatings, 2),
                                                        getRating(fRatings, 3), getRating(fRatings, 4), getRating(fRatings, 5),
                                                        getRating(fRatings, 6), getRating(fRatings, 7), getRating(fRatings, 8),
                                                        getRating(fRatings, 9), fMgrCompany, fMgrTitle, fReviewText,
                                                        fWorkedFrom, fWorkedUntil, fDatesHidden
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
            LocalDate workedFrom, LocalDate workedUntil, UUID draftToken,
            boolean datesHidden) {

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
            .put("workedUntil",    workedUntilStr)
            /*
              This body is rebuilt from the values passed down rather than forwarded whole, so
              every field the insert reads has to be restated here. datesHidden was missing, and
              validateAndInsertReview defaults it to false - so a submitter who hid their dates
              had them published whenever their manager fuzzy-matched somebody already present.
            */
            .put("datesHidden",    datesHidden);

        if ("ghost".equals(approvalStatus)) {
            // Enrich the ghost record with the more complete form data, add career history,
            // then attach the review.
            return managerRepo.updateForAttach(existingId, name, title, status, country, linkedinUrl, logoUrl, userId)
                /*
                  The name may have just changed - this is the path that supplies a fuller one for
                  a row the search created from whatever was typed. The slug has to follow, or the
                  person is renamed and left on an address derived from a truncation.
                */
                .compose(updated -> managerRepo.syncSlugToName(existingId).map(moved -> updated))
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
                // managerRepo.update already wrote the triggered columns (V89).
                return managerRepo.getCareerHistory(managerId)
                    .map(chRows -> buildManagerUpdateJson(row, chRows));
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
        // Opt-in: absent means show, exactly as every rating written before this existed does.
        boolean datesHidden       = body.getBoolean("datesHidden", false);
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
                            managerStatus, datesHidden);
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
                                managerStatus, datesHidden);
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
            String managerStatus, boolean datesHidden) {

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
                            manager_status, dates_hidden
                        )
                        VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,true,0,now(),now(),
                                $23,$24,$25,$26,$27,$28,$29)
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
                        managerStatus, datesHidden
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
            // recalculate writes reviews_count and overall_rating, and the managers trigger fires on it, so company_stats_live follows inside that same statement (V89).
            return managerRepo.recalculate(managerId).map(recalced -> row);
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
                    data.add(buildReviewJson(row, viewerId));
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
                    .put("managerRoleEnd",    mrEnd   != null ? mrEnd.toString()   : null)
                    .put("logoUrl",           row.getString("logo_url")));
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
        /*
          Defaulted from what is already stored, not to false. An edit form that omits the field -
          a client that predates it, or a request that only restates the stars - must not quietly
          republish dates its author had chosen to withhold.
        */
        boolean datesHidden        = body.getBoolean("datesHidden", false);
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
                                managerRoleStart, managerRoleEnd, resolved, datesHidden))
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
                                    .compose(recalced -> reviewRepo.recordDeletion(userId, managerId)))
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
        /*
          The rest of the location, which the edit form now collects through the same control
          every other form uses. V70 added somewhere to put it; until this, nothing sent it.
        */
        String newState          = body.getString("state");
        String newCity           = body.getString("city");
        String newPrecision      = body.getString("precision");
        Long   newCompanyLocationId = body.getLong("companyLocationId");
        String newLinkedinUrl    = body.getString("linkedinUrl");
        String startDateStr   = body.getString("startDate");
        String endDateStr     = body.getString("endDate");

        if (isBlank(newCompany) && isBlank(newTitle) && isBlank(newStatus) && isBlank(newCountry)
                && isBlank(newState) && isBlank(newCity) && newCompanyLocationId == null && isBlank(newLinkedinUrl)
                && isBlank(startDateStr) && isBlank(endDateStr)) {
            return Future.failedFuture(ServiceException.badRequest("At least one field is required"));
        }
        if (newCompany    != null && newCompany.length() > 100)    return Future.failedFuture(ServiceException.badRequest("Company must be at most 100 characters"));
        if (newTitle      != null && newTitle.length()   > 100)    return Future.failedFuture(ServiceException.badRequest("Title must be at most 100 characters"));
        if (newStatus     != null && !newStatus.equals("active") && !newStatus.equals("retired")) return Future.failedFuture(ServiceException.badRequest("Status must be 'active' or 'retired'"));
        if (newCountry    != null && newCountry.length() > 100)    return Future.failedFuture(ServiceException.badRequest("Country must be at most 100 characters"));
        if (newState      != null && newState.length()   > 100)    return Future.failedFuture(ServiceException.badRequest("State must be at most 100 characters"));
        if (newCity       != null && newCity.length()    > 100)    return Future.failedFuture(ServiceException.badRequest("City must be at most 100 characters"));
        if (newPrecision  != null && !newPrecision.isBlank()
                && !List.of("country", "state", "city", "exact").contains(newPrecision)) {
            return Future.failedFuture(ServiceException.badRequest("Precision must be one of country, state, city, exact"));
        }
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
        /*
          NOT toNullIfBlank, unlike every field above it.

          Null and empty mean different things for a location part. Null is "this edit did not ask
          about the city", so approval leaves it alone - that is what stops a title correction from
          wiping a location. Empty is "the editor removed the city", which has to reach the
          database as a NULL column.

          Collapsing empty to null here made the two indistinguishable, so a city could be entered
          and corrected but never removed: the clearing edit arrived looking like an edit that had
          not mentioned the city at all. Caught by anEmptyStringClearsAPartOfTheLocation.
        */
        String effectiveState          = newState;
        String effectiveCity           = newCity;
        String effectivePrecision      = toNullIfBlank(newPrecision);
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
                                return editRepo.upsert(managerId, userId, effectiveCompany, body.getLong("companyId"), effectiveCompanyLogoUrl, effectiveTitle, effectiveStatus, effectiveCountry, effectiveState, effectiveCity, effectivePrecision, newCompanyLocationId, effectiveLinkedinUrl, newStartDate, newEndDate)
                                    .map(row -> new JsonObject()
                                        .put("id", row.getUUID("id").toString())
                                        .put("managerId", managerId)
                                        .put("newCompany", effectiveCompany)
                                        .put("newTitle", effectiveTitle)
                                        .put("newStatus", effectiveStatus)
                                        .put("newCountry", effectiveCountry)
                                        .put("newState", effectiveState)
                                        .put("newCity", effectiveCity)
                                        .put("newCompanyLocationId", newCompanyLocationId)
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
        return buildReviewJson(row, null);
    }

    /**
     * A review as JSON, with the working period masked unless the caller wrote it.
     *
     * <p>{@code viewerId} is the AUTHENTICATED caller, resolved from the token - never the
     * {@code userId} query parameter, which is a display filter and carries no authority. Passing
     * null masks, which is what every public surface wants.
     *
     * <p>Why the author is exempt: their edit form has to open with the dates they stored. It read
     * this endpoint, got nulls, opened blank - and the UPDATE writes {@code worked_from} and
     * {@code worked_until} unconditionally, so saving an unrelated change to a rating with hidden
     * dates ERASED them. The flag is a decision about who may read the dates, not an instruction
     * to forget them, and the one person it was never meant to hide them from is the person who
     * chose it.
     */
    public static JsonObject buildReviewJson(Row row, UUID viewerId) {
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
        /*
          Whether this rating's author asked for their working period to stay private.

          Fails CLOSED when the column is absent from a projection: hidden rather than shown. An
          unusual projection then loses the dates, which is visible and fixable; the other way
          round leaks the one thing somebody explicitly asked to withhold, silently. Every review
          projection reads r.*, so absence means a new query, not normal operation.
        */
        boolean datesHidden = row.getColumnIndex("dates_hidden") < 0
                           || Boolean.TRUE.equals(row.getBoolean("dates_hidden"));
        /*
          The author reads their own dates; everybody else does not.

          Fails CLOSED like the flag above: a projection without user_id cannot establish
          authorship, so it masks. Getting this wrong in the other direction would publish the one
          thing somebody explicitly asked to withhold.
        */
        boolean isAuthor = viewerId != null
                        && row.getColumnIndex("user_id") >= 0
                        && viewerId.equals(row.getUUID("user_id"));
        /*
          A SEEDED review has no period to publish.

          createSeedReview invents worked_from as "created_at minus 12-36 months" (ReviewRepository),
          so the date is a plausible-looking fiction nobody ever stated. That was harmless while a
          ghost manager had no career history - and stopped being harmless the moment one was added:
          effective_worked_until caps the review at the manager's departure, and a cap earlier than
          an invented start printed "Jun 2024 - Jan 2019" on a live profile. An end before its own
          beginning, on a date nobody chose.

          Treating a weighted review exactly as a hidden one is the honest fix, and it needs no new
          concept: the dates simply are not published. The card already renders no period line when
          both are absent, so nothing shows rather than something false. Clamping the invented date
          to the manager's tenure was the alternative and it is worse - it would dress the fiction
          up as corroborated.

          Not expressed as datesHidden, because that flag is a person's privacy CHOICE and drives
          the edit form's toggle. A seed has no author and no choice to represent.
        */
        boolean weighted  = row.getColumnIndex("weight") >= 0 && Boolean.TRUE.equals(row.getBoolean("weight"));
        boolean maskDates = (datesHidden && !isAuthor) || weighted;
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
            /*
              Withheld together. Leaving effectiveWorkedUntil in place would publish the end date
              through the back door, since that is the value the profile actually renders.
            */
            .put("workedFrom",    maskDates ? null : dateOrNull(row, "worked_from"))
            .put("workedUntil",   maskDates ? null : dateOrNull(row, "worked_until"))
            .put("effectiveWorkedUntil", maskDates ? null : effectiveWorkedUntil(row))
            /*
              The real flag either way. An author who can see their dates still needs to be told
              they are hidden - it is what their edit form opens the toggle from, and what every
              display rule keys off.
            */
            .put("datesHidden",   datesHidden)
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

    /** A date column as "YYYY-MM-DD", or null. Repeated in every rating mapper before this. */
    private static String dateOrNull(Row row, String column) {
        java.time.LocalDate d = row.getLocalDate(column);
        return d != null ? d.toString() : null;
    }

    /**
     * When the rating's subject actually left this role, as opposed to when the reviewer did.
     *
     * <p>{@code worked_until} belongs to the REVIEWER, who may still be at a company the manager
     * has since left - which is why a card read "Jan 2024 - Present" for somebody who had moved
     * on. The bounded value is computed by EFFECTIVE_WORKED_UNTIL in ReviewRepository.
     *
     * <p>Sent ALONGSIDE the raw date, never instead of it: the edit form opens with what the
     * reviewer stored, and saving the bounded value back would silently rewrite their answer.
     * Display reads this; forms and overlap checks read {@code workedUntil}.
     */
    private static String effectiveWorkedUntil(Row row) {
        return row.getColumnIndex("effective_worked_until") < 0
            ? dateOrNull(row, "worked_until")
            : dateOrNull(row, "effective_worked_until");
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
            /*
              NOT masked here, unlike buildReviewJson. This is the author's view of their own
              rating, and the edit form has to open with what they actually stored - blanking the
              dates would make a save look like they had cleared them. The flag rides along so the
              form can open with the toggle in the state they chose.
            */
            .put("workedFrom",         dateOrNull(row, "worked_from"))
            .put("workedUntil",        dateOrNull(row, "worked_until"))
            .put("effectiveWorkedUntil", effectiveWorkedUntil(row))
            .put("datesHidden",        row.getColumnIndex("dates_hidden") >= 0
                                       && Boolean.TRUE.equals(row.getBoolean("dates_hidden")))
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
                /*
                  The company-filtered search that stood here has been REMOVED, not just bypassed.

                  It narrowed by company before deciding whether the manager existed, which is the
                  defect this work exists to fix, and once runMatcher took over the decision its
                  result was never read - so every search was paying for a query whose answer was
                  discarded. On the companyId path that was two: findDescendantIds followed by
                  searchInCompanies.
                */
                /*
                  THE CLIENT'S COMPANY ID IS VALIDATED ONCE, HERE, AND NOTHING DOWNSTREAM SEES
                  THE RAW ONE.

                  Validating it inside the matcher only was half a fix: a fabricated id would be
                  refused for matching and then handed straight to company resolution and ghost
                  creation, which would happily attach the new manager to whatever company it
                  named. An id that does not identify the company that was TYPED is treated as no
                  selection at all - exactly what isSelectedCompany already decides for the
                  short-company guard - so the rest of this method cannot trust what matching
                  rejected.

                  A null id is not an error: it means "resolve the company by name", which is the
                  ordinary path and leaves company creation behaviour untouched.
                */
                Future<Long> validatedCompanyId = companyId == null
                    ? Future.succeededFuture(null)
                    : companyRepo.isSelectedCompany(companyId, company)
                          .map(ok -> ok ? companyId : null);

                return Future.all(userRepo.hasContributed(userId), validatedCompanyId)
                    .compose(pre -> {
                    boolean contributed = pre.resultAt(0);
                    final Long companyIdSafe = pre.resultAt(1);

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
                    /*
                      NAME-FIRST MATCHING. See manager-search-flow.md section 5a.

                      What stood here classified only the rows a company-filtered query had already
                      returned, so a manager at a DIFFERENT employer was never a candidate at all -
                      the search could not see somebody it was about to duplicate. Aaron Yip
                      accumulated eight rows that way, and 21 people ended up with two public
                      profiles each.

                      runMatcher owns the whole decision now: it discovers by name alone, classifies
                      every candidate, pairs company and title within single employment records, and
                      consults non-public rows for existence without ever rendering them.
                    */
                    final String trimmedState = isBlank(state) ? null : state.trim();
                    final String trimmedCity  = isBlank(city)  ? null : city.trim();

                    return runMatcher(firstName, lastName, company, title, companyIdSafe)
                        .compose(outcome -> {
                        ManagerMatcher.Decision decision = outcome.decision();

                        if (decision.outcome() == ManagerMatcher.Outcome.RETURN_EXISTING) {
                            return Future.succeededFuture(new JsonObject()
                                .put("data", outcome.tiles())
                                .put("created", false)
                                .put("hasContributed", contributed));
                        }

                        if (decision.outcome() == ManagerMatcher.Outcome.QUEUE_REVIEW) {
                            /*
                              The tiles are still returned. A search that needs review has usually
                              found somebody worth showing - that is often WHY it needs review - and
                              withholding them would send the reader away believing we hold nobody.

                              incompleteEvidence is reported rather than hidden: a truncated list
                              must never be presented as the whole answer.
                            */
                            return companyIdForCapture(companyIdSafe, company, resolvedLogoUrl, null)
                                .compose(cid -> queueReview(firstName, lastName, company, title,
                                                            cid, outcome.candidateIds(), decision))
                                .map(v -> new JsonObject()
                                    .put("data", outcome.tiles())
                                    .put("created", false)
                                    .put("candidates", decision.nameClass() == ManagerMatcher.NameClass.PLAUSIBLE)
                                    .put("moreMatchesMayExist", outcome.incompleteEvidence())
                                    .put("hasContributed", contributed));
                        }

                        // CREATE_GHOST: fall through to the eligibility rules below, unchanged.

                    // Manager not found: ghost/pending flow (regardless of contribution status).
                    boolean shortPerson  = fullName.trim().length() < 4;
                    boolean shortCompany = company.trim().length() < 4;

                    /*
                      A company chosen from the dropdown is exempt from the company half of this
                      guard. The person's own name is not, and is still judged on its own.

                      The rule exists to stop a ghost being minted from a few characters somebody
                      was still typing. It was never meant to refuse the short employers that are
                      genuinely short - IBM, SAP, BBC, EY, 3M - and it did: a reader who picked IBM
                      from the suggestions and searched for their manager got an empty result and no
                      explanation, because three characters looked like an unfinished word.

                      A selection is not an unfinished word. It carries the id of a row already in
                      the directory, so there is nothing left to guess at. Typed text of three
                      characters or fewer with nothing selected still gates, exactly as before.

                      Verified rather than trusted: `companyId` comes from the request body, so the
                      claim is checked against `companies` and against the text that accompanied it.
                      An id that names no row, or names a different company than the text does, is
                      treated as no selection at all and gates.
                    */
                    Future<Boolean> companyWasSelected = shortCompany
                        ? companyRepo.isSelectedCompany(companyIdSafe, company)
                        : Future.succeededFuture(false);

                    return companyWasSelected.compose(selected -> {
                    if (shortPerson || (shortCompany && !selected)) {
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
                        return companyRepo.resolve(companyIdSafe, company, null, resolvedLogoUrl)
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
                        return companyRepo.resolve(companyIdSafe, company, null, resolvedLogoUrl)
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
                            return companyRepo.resolve(companyIdSafe, company, null, resolvedLogoUrl)
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
                        return companyRepo.resolve(companyIdSafe, company, null, resolvedLogoUrl)
                            .compose(companyRow -> managerRepo.createAutoApprovedOrExisting(fullName, company, title, country,
                                trimmedState, trimmedCity, userId, resolvedLogoUrl, companyRow.getLong("id")))
                            .compose(insert -> {
                                /*
                                  LOSING THE RACE IS NOT AN ERROR.

                                  Two people searching the same unknown manager at the same
                                  instant both build the same slug, and the unique index rejects
                                  the second INSERT. That used to reach this searcher as a raw
                                  PgException: the manager they asked for now existed, and they
                                  were shown a failure. Eight rounds of six simultaneous searches
                                  hit it on the first round.

                                  createAutoApprovedOrExisting hands back the published row
                                  instead, and the post-insert work is then skipped entirely. It
                                  must be: the winner already seeded the review, and seeding a
                                  second one would count this search as a rating nobody gave.

                                  The slot goes back, because this searcher published nothing.
                                  Section 15 gives each person one auto-creation, and spending it
                                  on a row somebody else wrote would deny them the next manager
                                  that really is new.
                                */
                                if (!insert.created()) {
                                    return userRepo.resetAutoCreatedManagerSlot(userId)
                                        .map(v -> insert.row());
                                }
                                final Row row = insert.row();
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

                                        The company projection needs nothing from here: recalculate() writes
                                        managers.reviews_count and overall_rating, which the managers trigger fires
                                        on, so company_stats_live follows inside that same statement (V89).
                                    */
                                    /*
                                      A ghost is LIVE the moment it is created, so it replaces a
                                      seeded stand-in exactly as an approved manager does. Only
                                      the add form retired one before, which is why the admin's
                                      "Fake Profiles Left" never moved for managers arriving by
                                      search.
                                    */
                                    .compose(ignored -> {
                                        managerRepo.deleteFakeManagerInBackground();
                                        return managerRepo.recalculate(newId);
                                    })
                                    .compose(recalced -> managerRepo.findById(newId))
                                    .map(fresh -> fresh.orElse(row))
                                    .recover(err -> {
                                        System.err.println("Seed review creation failed for auto-approved manager " + newId + ": " + err.getMessage());
                                        err.printStackTrace(System.err);
                                        return Future.succeededFuture(row);
                                    });
                            })
                            .recover(err -> {
                                // Ghost insert failed — release slot so user gets another chance
                                return userRepo.resetAutoCreatedManagerSlot(userId)
                                    .compose(v -> Future.failedFuture(err));
                            })
                            .map(row -> {
                                /*
                                  THE EXISTING TILES COME TOO.

                                  This built a fresh array holding only the new ghost, which threw
                                  away every same-name manager the matcher had just found. The
                                  decision to publish is reached precisely BECAUSE those people
                                  exist and are different (branch D: both company and title
                                  differ), so the reader has to see them - otherwise we tell
                                  somebody "no such manager, here is a new one" while holding three
                                  others with that name, and they pick the one we minted.
                                */
                                /*
                                  THE NEW GHOST LEADS, and the existing tiles follow.

                                  It was appended last, below every unrelated same-name manager.
                                  But a ghost is created FROM the search, so its company and title
                                  are exactly what was typed - rank 0 by definition - while this
                                  branch is only reached when no existing manager matched both. It
                                  is therefore the single best answer to the question asked, and
                                  burying it under four strangers is how somebody reviews the wrong
                                  person.
                                */
                                JsonArray data = new JsonArray().add(rowToManagerJson(row));
                                outcome.tiles().forEach(data::add);
                                return new JsonObject()
                                    .put("data", data)
                                    .put("created", true)
                                    .put("hasContributed", contributed);
                            });
                    });
                    }); // isListedName guard
                    }); // short-company dropdown waiver
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

        /*
            Which rows this endpoint may even see depends on who is calling.

            It serves two callers. The ADD FORM wants pending drafts: if somebody half-typed this
            manager before, reuse that draft rather than filling the admin queue with copies. The
            SEARCH must never see them. A pending draft is not live and not ghost, and the profile
            page refuses to serve it - so a search that matches one can only ever produce a tile
            leading to "Manager Not Found".

            That is the whole bug, and it had three faces: adopting a draft (404), failing to match
            a draft captured mid-typing (a duplicate with a company-suffixed slug), and racing the
            capture this same search had just written. All three are the search consulting a bucket
            it has no business linking to.

            So the search looks only at rows the profile page will actually serve, and creates its
            own when there are none. The invariant in CLAUDE.md section 41 - a tile is only ever
            rendered for a row the profile page will serve - then holds by construction, instead of
            being re-established by guarding each leak as it is found.

            A draft that turns out to duplicate a manager the search publishes is not this
            endpoint's problem to solve. It sits in the admin queue for review, and the merge tools
            exist for exactly that.
        */
        boolean callerIsSearch = Boolean.TRUE.equals(body.getBoolean("fromSearch"));

        /*
          ANONYMOUS SEARCH NOW USES THE SAME MATCHER AS THE LOGGED-IN PATH.

          It used findByNameAndCompany, where company is a hard AND, so an anonymous visitor
          searching somebody at a former employer could not find them and published a duplicate -
          precisely the defect fixed on the logged-in side. The two paths are required to behave
          identically, and until this they did not.

          THE ADD FORM IS NOT TOUCHED. Section 41's trap is that this one endpoint serves two
          intents: a deliberate search, and the drop-off capture from /add. Only the search intent
          is routed through the matcher; the capture keeps findCapturedByNameAndCompany, which
          deliberately matches pending drafts so a second visitor adopts a half-typed row instead
          of filling the queue with copies. Making those two consistent is what caused the
          "Manager Not Found" outage, so they stay different on purpose.

          A search that reaches the matcher either answers here or arrives below with an empty
          lookup, which sends it to the creation path exactly as before.
        */
        String ghostFirst = firstName, ghostLast = lastName;
        Long   rawCompanyId = body.getLong("companyId");
        Future<Long> ghostCompanyId = rawCompanyId == null
            ? Future.succeededFuture(null)
            : companyRepo.isSelectedCompany(rawCompanyId, company).map(ok -> ok ? rawCompanyId : null);

        Future<JsonObject> searchDecision = !callerIsSearch
            ? Future.succeededFuture(null)
            : ghostCompanyId.compose(safeId -> runMatcher(ghostFirst, ghostLast, company, title, safeId)
                .compose(outcome -> {
                    ManagerMatcher.Decision d = outcome.decision();

                    if (d.outcome() == ManagerMatcher.Outcome.RETURN_EXISTING
                            && !outcome.tiles().isEmpty()) {
                        JsonObject best = outcome.tiles().getJsonObject(0);
                        return Future.succeededFuture(new JsonObject()
                            .put("id", best.getLong("id"))
                            .put("name", best.getString("name"))
                            .put("created", false)
                            // only ever true for a row the profile page will serve - section 41
                            .put("published", true));
                    }

                    if (d.outcome() == ManagerMatcher.Outcome.QUEUE_REVIEW) {
                        /*
                          published=false, so the client renders NO tile. The anonymous search
                          records its request for review exactly as the logged-in path does, with
                          the same deduplication, and - as everywhere on this path - without a
                          searcher identity, so no notification is possible even in principle.
                        */
                        /*
                          published=false WHENEVER NOTHING WAS PUBLISHED, even when credible
                          candidates exist.

                          This flag drives two client behaviours at once: render a tile, and spend
                          the one-per-browser ghost slot. Reporting true because a plausible
                          candidate was found would burn a visitor's only ghost on a search that
                          created nothing - the same harm the listed-name rule exists to prevent.

                          Candidates are not lost: the anonymous flow reaches this endpoint only
                          after its own search call, which is what displays existing managers.
                          Surfacing fuzzy candidates through THIS response would need a second
                          field and a frontend change, and is noted rather than smuggled in here.
                        */
                        JsonObject answer = new JsonObject()
                            .put("created", false)
                            .put("published", false)
                            /*
                              CANDIDATES, SEPARATE FROM published.

                              Without this the visitor saw an empty page. The anonymous flow only
                              reaches this endpoint when its own substring search found nothing,
                              and that search is ILIKE '%First Last%': it happens to catch
                              "Aaron Yi" and "Aron Yip" inside "Aaron Yip", but NOT "Aar Yip" or
                              "Aaron Yap". For those the matcher correctly refuses to duplicate
                              him - and then nobody was shown the Aaron Yip we hold, which is the
                              original complaint wearing different clothes.

                              A separate field because the two facts are different: these are
                              existing public managers to display, while published stays false
                              because nothing was created and the one-per-browser ghost allowance
                              must not be spent. Every tile here is approved or ghost, so each one
                              resolves to a profile the server will serve.
                            */
                            .put("candidates", outcome.tiles());
                        return companyIdForCapture(safeId, company, resolvedLogoUrl, clientIp)
                            .compose(cid -> queueReview(ghostFirst, ghostLast, company, title,
                                                        cid, outcome.candidateIds(), d))
                            .map(v -> answer);
                    }
                    return Future.succeededFuture((JsonObject) null);   // CREATE_GHOST: fall through
                }));

        return searchDecision.compose(answered -> {
            if (answered != null) return Future.succeededFuture(answered);
            return (callerIsSearch
                    ? db.preparedQuery("SELECT 1 WHERE false").execute()
                    : managerRepo.findCapturedByNameAndCompany(name, company))
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
                    long existingId = row.getLong("id");
                    /*
                        The row may have been captured mid-typing, under a fragment of the company
                        name - "Lum" for "Lumenwerx". Now that the finished name is known, complete
                        it, so the manager is not left listed under half a word.

                        Resolving the company first gives the row the real company_id too; the
                        fragment had its own, pointing at a company that only exists because
                        somebody paused while typing.
                    */
                    return companyRepo.resolve(body.getLong("companyId"), company, body.getString("companyDomain"), pickedOrResolvedLogo(body, resolvedLogoUrl))
                        .compose(companyRow -> managerRepo.refineCapturedCompany(
                            existingId, company, companyRow.getLong("id")))
                        .recover(err -> {
                            // Cosmetic. A manager listed under a fragment is worse than one listed
                            // correctly, but neither is worth failing the search over.
                            System.err.println("Company refinement failed for manager " + existingId
                                               + ": " + err.getMessage());
                            return Future.succeededFuture();
                        })
                        .map(ignored -> new JsonObject()
                            .put("id", existingId)
                            .put("name", row.getString("name"))
                            .put("created", false)
                            .put("published", servable));
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
                boolean fromSearch = callerIsSearch;

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

                /*
                  The company is resolved differently depending on whether this will publish.

                  A published ghost always gets its company: it is live and must appear under that
                  company in the directory, and it is bounded to one per address per window
                  already. An unpublished capture goes through the daily cap instead, which can
                  hand back no company id at all - see companyIdForCapture. Both branches wrote a
                  new public company row before, so a caller whose ghost slot was long gone could
                  still mint companies indefinitely.
                */
                return mayPublish.compose(publish ->
                    (publish
                        ? companyRepo.resolve(body.getLong("companyId"), company, body.getString("companyDomain"), pickedOrResolvedLogo(body, resolvedLogoUrl))
                              .map(companyRow -> companyRow.getLong("id"))
                        : companyIdForCapture(body.getLong("companyId"), company, resolvedLogoUrl, clientIp))
                    .compose(resolvedCompanyId -> publish
                        /*
                          Same race as the /find path, and this is the side most people are on:
                          two anonymous visitors searching the same unknown manager at once. The
                          loser is handed the row the winner published rather than an exception,
                          and `created` tells the block below not to seed a second review onto it.
                          A capture is never racing anybody - it writes its own pending row - so it
                          reports created unconditionally.
                        */
                        ? managerRepo.createAutoApprovedOrExisting(name, company, title, country, fState, fCity,
                                                         null, resolvedLogoUrl, resolvedCompanyId)
                        : managerRepo.createCapturedDraft(name, company, title, country, fState, fCity,
                                                          resolvedLogoUrl, resolvedCompanyId)
                              .map(r -> new ManagerRepository.GhostInsert(r, true)))
                    .recover(err -> {
                        // The quota was spent before the insert was attempted, so a failure here
                        // would otherwise cost this address its window for nothing.
                        if (!publish) return Future.failedFuture(err);
                        return ghostSlots.release(clientIp).compose(released -> Future.failedFuture(err));
                    })
                    .map(row -> { publishedRef.set(publish); return row; }))
                    .compose(insert -> {
                        /*
                          A race loss is already published and already seeded, so it leaves here
                          with the winner's row untouched. The address quota is handed back for
                          the same reason the logged-in slot is: nothing was published here.
                        */
                        if (!insert.created()) {
                            return Boolean.TRUE.equals(publishedRef.get())
                                ? ghostSlots.release(clientIp).map(r -> insert.row())
                                : Future.succeededFuture(insert.row());
                        }
                        final Row row = insert.row();
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

                                The company projection needs nothing from here: recalculate() writes
                                managers.reviews_count and overall_rating, which the managers trigger fires
                                on, so company_stats_live follows inside that same statement (V89).
                            */
                            .compose(ignored -> managerRepo.recalculate(newId))
                            .compose(recalced -> managerRepo.findById(newId))
                            .map(fresh -> fresh.orElse(row))
                            .recover(err -> {
                                System.err.println("Seed review creation failed for ghost manager " + newId + ": " + err.getMessage());
                                return Future.succeededFuture(row);
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
        });   // end searchDecision.compose - the anonymous search either answered above or fell
              // through to the creation path with an empty lookup
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
                // Capped: this row is pending_approval and never public, so it must not be
                // able to mint public company rows without limit.
                return companyIdForCapture(body.getLong("companyId"), fCompany, resolvedLogoUrl,
                                           submission.clientIp())
                    .compose(resolvedCompanyId -> managerRepo.createPending(
                        name, fCompany, fTitle, "active", country, fState, resolvedLogoUrl, resolvedCompanyId))
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
                    // Capped, as in captureAnonymousSearch: a drop-off draft is pending and
                    // never public, and must not mint public companies without limit.
                    return companyIdForCapture(body.getLong("companyId"), company, resolvedLogoUrl,
                                               submission.clientIp())
                        .compose(resolvedCompanyId -> managerRepo.createPending(name, company, title, fStatus, country, fState, resolvedLogoUrl, resolvedCompanyId))
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
            // The company's resolved identity, for the logo chain. Never a guessed domain.
            .put("companyDomain", identityOrNull(row, "company_domain"))
            .put("companyBrandfetchIconUrl", identityOrNull(row, "company_brandfetch_icon_url"))
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

    /**
     * Attaches the company's resolved domain and Brandfetch icon to a manager payload.
     *
     * <p>One helper rather than four copies: every surface that renders a company logo needs the
     * same two fields, and the one time they were threaded through a single call site the other
     * seven kept showing letters.
     *
     * <p>Both are omitted when absent, which the client reads as "no identity established" and
     * renders its own letter tile.
     */
    private static void putCompanyIdentity(io.vertx.core.json.JsonObject target,
                                           io.vertx.sqlclient.Row row) {
        if (row.getColumnIndex("company_domain") >= 0) {
            String d = row.getString("company_domain");
            if (d != null && !d.isBlank()) target.put("companyDomain", d);
        }
        if (row.getColumnIndex("company_brandfetch_icon_url") >= 0) {
            String i = row.getString("company_brandfetch_icon_url");
            if (i != null && !i.isBlank()) target.put("companyBrandfetchIconUrl", i);
        }
    }

    /** A company-identity column when the query selected it, else null. */
    private static String identityOrNull(io.vertx.sqlclient.Row row, String column) {
        if (row.getColumnIndex(column) < 0) return null;
        String v = row.getString(column);
        return v == null || v.isBlank() ? null : v;
    }

    /**
     * Brandfetch icons we already hold, keyed by lowercase domain.
     *
     * <p>Used by the company picker so its suggestions can show a logo even while logo.dev's
     * monthly quota is exhausted. Reads our own table only.
     */
    public io.vertx.core.Future<java.util.Map<String, String>> iconsByDomain(java.util.List<String> domains) {
        if (companyRepo == null || domains == null || domains.isEmpty()) {
            return io.vertx.core.Future.succeededFuture(java.util.Map.of());
        }
        return companyRepo.findIconsByDomains(domains).map(rows -> {
            java.util.Map<String, String> out = new java.util.HashMap<>();
            for (io.vertx.sqlclient.Row r : rows) {
                String d = r.getString("domain");
                String i = r.getString("brandfetch_icon_url");
                if (d != null && i != null) out.put(d, i);
            }
            return out;
        }).otherwise(java.util.Map.of());
    }

    // ── The matching pipeline (see manager-search-flow.md section 5a) ─────────

    /**
     * How many candidates may be classified before the evidence is declared incomplete.
     *
     * <p>Measured: classification costs roughly 3 microseconds per candidate, so 20,000 is about
     * 60ms of CPU - the point at which it starts competing with the rest of the request on a
     * two-vCPU host. A realistic distribution produces about 4,000 candidates for the most common
     * given name AND surname combination at 200,000 managers, so this is headroom rather than a
     * routine limit.
     */
    static final int CANDIDATE_BUDGET = 20_000;

    /**
     * What the matcher concluded, and what may be shown.
     *
     * @param tiles            public managers that actually classified - never raw token overlap
     * @param incompleteEvidence true when more candidates existed than the budget allowed. The
     *                         caller must then never publish, and must say so rather than present
     *                         a truncated list as the whole answer.
     */
    record MatchOutcome(ManagerMatcher.Decision decision, JsonArray tiles,
                        java.util.List<Long> candidateIds, boolean incompleteEvidence) {}

    /**
     * Name-first discovery, classification, employment pairing and the decision.
     *
     * <p>Public display and existence detection are deliberately different questions. Only approved
     * and ghost managers may become tiles, but a pending or rejected row still proves the manager
     * EXISTS in some form, and publishing a ghost over one would duplicate a manager already in the
     * admin queue or silently overturn an admin's rejection. So non-public rows are consulted, never
     * rendered, and they downgrade a would-be ghost to a review rather than blocking the search.
     */
    private Future<MatchOutcome> runMatcher(String firstName, String lastName,
                                            String company, String title, Long companyId) {
        java.util.List<String> tokens = ManagerMatcher.candidateTokens(firstName, lastName);
        if (tokens.isEmpty()) {
            return Future.succeededFuture(new MatchOutcome(
                new ManagerMatcher.Decision(ManagerMatcher.Outcome.QUEUE_REVIEW,
                    ManagerMatcher.NameClass.NONE, null, null, "unusable_name"),
                new JsonArray(), java.util.List.of(), false));
        }

        return Future.all(managerRepo.findNameCandidates(tokens),
                          managerRepo.findNonPublicNameCandidates(tokens),
                          companyRepo.resolveVerifiedCompanyIds(companyId, company))
            .compose(fetched -> {
                RowSet<Row> publicRows  = fetched.resultAt(0);
                RowSet<Row> hiddenRows  = fetched.resultAt(1);
                java.util.Set<Long> verifiedCompanyIds = fetched.resultAt(2);

                /*
                  THE BUDGET IS DETERMINISTIC.

                  Classification stops at a fixed count in a fixed order, so the same search gives
                  the same answer every time. What is classified is returned; what is not is
                  declared, never silently dropped. And incomplete evidence can only ever produce a
                  review - publishing on the strength of a partial scan is exactly how a duplicate
                  gets created.
                */
                boolean incomplete = publicRows.size() > CANDIDATE_BUDGET;

                java.util.List<Long> confirmedIds = new java.util.ArrayList<>();
                java.util.List<Long> plausibleIds = new java.util.ArrayList<>();
                int examined = 0;
                for (Row row : publicRows) {
                    if (examined++ >= CANDIDATE_BUDGET) break;
                    ManagerMatcher.NameClass c =
                        ManagerMatcher.classifyName(firstName, lastName, row.getString("name"));
                    if (c == ManagerMatcher.NameClass.CONFIRMED) confirmedIds.add(row.getLong("id"));
                    else if (c == ManagerMatcher.NameClass.PLAUSIBLE) plausibleIds.add(row.getLong("id"));
                }

                /*
                  EXISTENCE ONLY. These never become tiles, and PENDING and REJECTED are judged
                  differently.

                  A plausible name alone is far too weak to block publication. "Daniel Pa" shares
                  an initial with every Daniel P-something we ever rejected, and treating any of
                  them as proof that this manager exists would refuse legitimate new people
                  forever - the mirror of the duplication problem, and just as damaging.

                  PENDING SPLITS IN TWO, and which one it is decides everything.

                  Both kinds are 'pending_approval' and neither is servable, but they mean opposite
                  things, and a single rule for both is wrong whichever way it is written. The
                  column that tells them apart is search_created_by_user_id.

                  A SEARCH-PENDING row (createSearchPending, the column set) is a person who
                  searched for this exact manager after their one ghost was spent. It is somebody
                  asserting the manager exists, and it is already queued for a decision. A second
                  searcher publishing over it is how one manager reached ten pending rows at a
                  single company. So a confirmed name at a verified-identical company BLOCKS, and
                  the search is recorded for review instead.

                  A CAPTURED DRAFT (createCapturedDraft, the column null) is somebody who started
                  typing into the add form and left. It must never block. Blocking on it means one
                  abandoned form makes that manager permanently unfindable by everybody who
                  searches afterwards - they are shown nothing, and there is nothing they could be
                  shown, because the profile page refuses a pending row to everybody. That is the
                  outage in section 41 read from the other end, and DropOffDraftIntegrationTest
                  exists to hold this behaviour down.

                  The duplicate a published search leaves beside a draft is real, and it is the
                  admin queue's job with the merge tools built for it. What must not happen is the
                  draft silently deleting the manager from everybody's search results.

                  A different employer is a different submission either way, and is never blocked.

                  REJECTED is different again, and still blocks. It is not evidence that a person
                  exists - an admin decided the record
                  should not. It therefore never blocks a distinct person. It only intervenes when
                  the search is the SAME submission again, name and company and title alike, which
                  is a request to reconsider rather than a licence to republish what was refused.
                */
                java.util.List<Long> hiddenIds = new java.util.ArrayList<>();
                java.util.Map<Long, String> hiddenStatus = new java.util.HashMap<>();
                // Which pending rows a SEARCH created, as opposed to an abandoned add form.
                java.util.Set<Long> hiddenFromSearch = new java.util.HashSet<>();
                for (Row row : hiddenRows) {
                    if (ManagerMatcher.classifyName(firstName, lastName, row.getString("name"))
                            == ManagerMatcher.NameClass.CONFIRMED) {
                        hiddenIds.add(row.getLong("id"));
                        hiddenStatus.put(row.getLong("id"), row.getString("approval_status"));
                        if (row.getUUID("search_created_by_user_id") != null) {
                            hiddenFromSearch.add(row.getLong("id"));
                        }
                    }
                }

                java.util.List<Long> all = new java.util.ArrayList<>(confirmedIds);
                all.addAll(plausibleIds);

                // Resolve the non-public policy against the employment each hidden row carries.
                Future<String> hiddenBlock = hiddenIds.isEmpty()
                    ? Future.succeededFuture(null)
                    : managerRepo.findEmployments(hiddenIds).map(rows2 -> {
                        String blockReason = null;
                        for (Row e : rows2) {
                            String status = hiddenStatus.get(e.getLong("manager_id"));
                            if (status == null) continue;
                            ManagerMatcher.CompanyClass cc = ManagerMatcher.classifyCompany(
                                verifiedCompanyIds, company, e.getLong("company_id"), e.getString("company"));
                            ManagerMatcher.TitleClass tc =
                                ManagerMatcher.classifyTitle(title, e.getString("title"));
                            if ("pending_approval".equals(status)
                                    && hiddenFromSearch.contains(e.getLong("manager_id"))
                                    && cc == ManagerMatcher.CompanyClass.VERIFIED) {
                                return "equivalent_pending";            // a queued search, not a draft
                            }
                            if ("rejected".equals(status)
                                    && cc == ManagerMatcher.CompanyClass.VERIFIED
                                    && tc == ManagerMatcher.TitleClass.EQUIVALENT) {
                                blockReason = "previously_rejected";    // the same submission again
                            }
                        }
                        return blockReason;
                    });

                if (all.isEmpty()) {
                    return hiddenBlock.map(block -> {
                        ManagerMatcher.Decision d = (block != null || incomplete)
                            ? new ManagerMatcher.Decision(ManagerMatcher.Outcome.QUEUE_REVIEW,
                                  ManagerMatcher.NameClass.NONE, null, null,
                                  block != null ? block : "incomplete_evidence")
                            : new ManagerMatcher.Decision(ManagerMatcher.Outcome.CREATE_GHOST,
                                  ManagerMatcher.NameClass.NONE, null, null, "no_name_match");
                        return new MatchOutcome(d, new JsonArray(), java.util.List.of(), incomplete);
                    });
                }

                // Employments and full rows are loaded ONLY for managers that actually classified.
                final boolean inc = incomplete;
                return Future.all(managerRepo.findEmployments(all), managerRepo.findByIdsOrdered(all), hiddenBlock)
                    .compose(loaded -> {
                        RowSet<Row> employmentRows = loaded.resultAt(0);
                        RowSet<Row> fullRows       = loaded.resultAt(1);
                        String      hiddenReason   = loaded.resultAt(2);

                        java.util.Map<Long, java.util.List<ManagerMatcher.Employment>> byManager =
                            new java.util.HashMap<>();
                        for (Row e : employmentRows) {
                            byManager.computeIfAbsent(e.getLong("manager_id"), k -> new java.util.ArrayList<>())
                                .add(new ManagerMatcher.Employment(
                                    e.getLong("company_id"), e.getString("company"), e.getString("title")));
                        }

                        java.util.Map<ManagerMatcher.NameClass, java.util.List<java.util.List<ManagerMatcher.Employment>>> classified =
                            new java.util.EnumMap<>(ManagerMatcher.NameClass.class);
                        for (Long id : confirmedIds)
                            classified.computeIfAbsent(ManagerMatcher.NameClass.CONFIRMED, k -> new java.util.ArrayList<>())
                                .add(byManager.getOrDefault(id, java.util.List.of()));
                        for (Long id : plausibleIds)
                            classified.computeIfAbsent(ManagerMatcher.NameClass.PLAUSIBLE, k -> new java.util.ArrayList<>())
                                .add(byManager.getOrDefault(id, java.util.List.of()));

                        ManagerMatcher.Decision d =
                            ManagerMatcher.decide(classified, verifiedCompanyIds, company, title);

                        // An equivalent queued submission, a repeat of a rejected one, or a
                        // partial scan can only ever DOWNGRADE a would-be ghost to a review.
                        // None of them can turn a review into a publication.
                        if ((hiddenReason != null || inc) && d.outcome() == ManagerMatcher.Outcome.CREATE_GHOST) {
                            d = new ManagerMatcher.Decision(ManagerMatcher.Outcome.QUEUE_REVIEW,
                                    d.nameClass(), d.bestCompany(), d.bestTitle(),
                                    hiddenReason != null ? hiddenReason : "incomplete_evidence");
                        }

                        /*
                          RANKED BY RELEVANCE TO WHAT WAS TYPED, not by name class alone.

                          A reader has to pick the right person before leaving a review, so the
                          manager who actually holds the searched job must lead. Ranking only by
                          confirmed-before-plausible would put four unrelated Marcus Webbs ahead of
                          the one at the company the reader named.

                            0  confirmed name, company VERIFIED and title EQUIVALENT
                            1  confirmed name, one of the two matches
                            2  confirmed name, neither
                            3  plausible name

                          Reviews only break ties WITHIN a band - never across one, or a popular
                          stranger would outrank the right person.
                        */
                        java.util.Map<Long, Integer> rank = new java.util.HashMap<>();
                        for (Long id : all) {
                            boolean confirmedName = confirmedIds.contains(id);
                            ManagerMatcher.CompanyClass bc = ManagerMatcher.CompanyClass.DIFFERENT;
                            ManagerMatcher.TitleClass   bt = ManagerMatcher.TitleClass.DIFFERENT;
                            boolean pair = false;
                            for (ManagerMatcher.Employment e : byManager.getOrDefault(id, java.util.List.of())) {
                                ManagerMatcher.CompanyClass cc = ManagerMatcher.classifyCompany(
                                    verifiedCompanyIds, company, e.companyId(), e.company());
                                ManagerMatcher.TitleClass tc = ManagerMatcher.classifyTitle(title, e.title());
                                if (cc.ordinal() < bc.ordinal()) bc = cc;
                                if (tc.ordinal() < bt.ordinal()) bt = tc;
                                if (cc == ManagerMatcher.CompanyClass.VERIFIED
                                        && tc == ManagerMatcher.TitleClass.EQUIVALENT) pair = true;
                            }
                            int r;
                            if (!confirmedName) r = 3;
                            else if (pair) r = 0;
                            else if (bc == ManagerMatcher.CompanyClass.VERIFIED
                                     || bt == ManagerMatcher.TitleClass.EQUIVALENT) r = 1;
                            else r = 2;
                            rank.put(id, r);
                        }

                        java.util.List<JsonObject> ordered = new java.util.ArrayList<>();
                        for (Row row : fullRows) ordered.add(rowToManagerJson(row));
                        ordered.sort(java.util.Comparator
                            .<JsonObject>comparingInt(o -> rank.getOrDefault(o.getLong("id"), 3))
                            .thenComparing(o -> -o.getInteger("reviewsCount", 0))
                            .thenComparing(o -> o.getLong("id")));

                        JsonArray tiles = new JsonArray();
                        ordered.forEach(tiles::add);
                        return Future.succeededFuture(new MatchOutcome(d, tiles, all, inc));
                    });
            });
    }


    /**
     * READ-ONLY discovery through the one matching policy. Creates nothing, decides nothing.
     *
     * <p>The anonymous flow used to discover with a substring query - {@code name ILIKE
     * '%First Last%'} - and only consulted the matcher afterwards, when that query found nothing.
     * Two definitions of "same person" in one journey, and the weaker one ran first: it caught
     * "Aaron Yi" inside "Aaron Yip" by luck, missed "Aar Yip" entirely, and - worse - RETURNED
     * EARLY whenever it found anything at all. With both "Aron Yip" and "Aaron Yip" on file, a
     * search for the first showed only the first and the second was never looked for.
     *
     * <p>So discovery is this method, for everybody. Eligibility and publication still differ
     * between a signed-in reader and an anonymous one; who exists does not.
     */
    public Future<JsonObject> discoverByName(String firstName, String lastName,
                                            String company, String title, Long companyId,
                                            int limit, int offset) {
        return discoverByName(firstName, lastName, company, title, companyId, limit, offset, false, null);
    }

    /**
     * @param completedSearch true when this is a SUBMITTED four-field search rather than discovery
     *        while typing. A submission resolves-or-creates its company; typing never does.
     *
     * <p>That write used to be fired from the browser and not awaited, which is not a delivery
     * guarantee: navigating away, closing the tab or a failed request lost it permanently, and
     * "somebody will search that employer again" is a hope rather than a mechanism. Doing it here
     * means the server completes the work whatever the browser does next.
     *
     * <p>It runs IN PARALLEL with the matcher rather than after it, so the company write costs the
     * reader nothing beyond whichever of the two is slower - the tiles do not queue behind it.
     * Existing resolution, normalisation, aliases, logos and the daily cap all still apply, because
     * this calls the same {@code companyIdForCapture} every other capture path uses.
     */
    public Future<JsonObject> discoverByName(String firstName, String lastName,
                                            String company, String title, Long companyId,
                                            int limit, int offset,
                                            boolean completedSearch, String clientIp) {
        if (isBlank(firstName) || isBlank(lastName))
            return Future.succeededFuture(new JsonObject()
                .put("data", new JsonArray()).put("total", 0).put("hasMore", false));

        Future<Long> safeId = companyId == null
            ? Future.succeededFuture(null)
            : companyRepo.isSelectedCompany(companyId, company).map(ok -> ok ? companyId : null);

        return safeId.compose(id -> {
            Future<MatchOutcome> matching = runMatcher(firstName, lastName,
                                                       company == null ? "" : company,
                                                       title   == null ? "" : title, id);
            // A submitted search resolves its company; discovery while typing does not.
            Future<Long> companyWrite = completedSearch && !isBlank(company)
                ? companyIdForCapture(id, company, null, clientIp)
                : Future.succeededFuture(id);
            return Future.all(matching, companyWrite).map(both -> (MatchOutcome) both.resultAt(0));
        })
            .map(outcome -> {
                /*
                  PAGINATION AFTER CLASSIFICATION AND RANKING, never before.

                  Cutting the list earlier is the mistake this whole area keeps making: a LIMIT
                  applied to raw candidates drops people the matcher would have classified, and a
                  LIMIT applied before ranking keeps whoever happened to sort first rather than
                  whoever the reader asked for. So the full classified set is ranked, and only then
                  does a page come off the front of it.

                  total is the real number of matching public managers, not the size of this page,
                  so the client can say "3 of 40" rather than implying it has seen everything.
                */
                JsonArray all = outcome.tiles();
                int from = Math.max(0, Math.min(offset, all.size()));
                int to   = Math.max(from, Math.min(from + Math.max(1, limit), all.size()));
                JsonArray page = new JsonArray();
                for (int i = from; i < to; i++) page.add(all.getJsonObject(i));
                return new JsonObject()
                    .put("data", page)
                    .put("total", all.size())
                    .put("hasMore", to < all.size())
                    // true when more candidates existed than could be classified within budget:
                    // the page is honest about being partial rather than presenting itself whole.
                    .put("moreMatchesMayExist", outcome.incompleteEvidence());
            });
    }

    /** Records a search that needs a human decision. Deduplicated by the normalised four fields. */
    private Future<Void> queueReview(String firstName, String lastName, String company, String title,
                                     Long resolvedCompanyId, java.util.List<Long> candidateIds,
                                     ManagerMatcher.Decision d) {
        return new org.werkpages.repository.ManagerSearchRequestRepository(db)
            .record(firstName.trim(), lastName.trim(), company.trim(), title.trim(),
                    ManagerMatcher.norm(firstName + " " + lastName),
                    ManagerMatcher.normCompany(company),
                    ManagerMatcher.normTitle(title),
                    resolvedCompanyId, candidateIds,
                    d.reason(), d.nameClass().name(),
                    d.bestCompany() == null ? null : d.bestCompany().name(),
                    d.bestTitle()   == null ? null : d.bestTitle().name())
            .mapEmpty();
    }
}

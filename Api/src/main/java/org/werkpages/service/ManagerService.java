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
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
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
        return managerRepo.findById(managerId)
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
        return managerRepo.findBySlug(slug)
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
                        .put("avgRating",    row.getBigDecimal("avg_rating"));
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
                String storedLogoUrl = companyRow.getString("logo_url");
                String logoUrl = (storedLogoUrl != null && storedLogoUrl.contains("logo.dev"))
                    ? storedLogoUrl : resolvedLogoUrl;
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
                        // Prefer a logo.dev URL from managers — it was set via autocomplete
                        // and uses the real domain (e.g. stchas.edu, not a guessed one).
                        String bestLogoUrl = logoUrl;
                        for (Row row : rows) {
                            String mgrLogo = row.getString("company_logo_url");
                            if (mgrLogo != null && mgrLogo.contains("logo.dev")) {
                                bestLogoUrl = mgrLogo;
                                break;
                            }
                        }
                        final String finalLogoUrl = bestLogoUrl;
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
                    .compose(result -> withCorporateStructure(result, companyId));
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
    private Future<JsonObject> withCorporateStructure(JsonObject profile, long companyId) {
        return companyRepo.findCompanyParent(companyId)
            .compose(parentOpt -> companyRepo.findCompanyChildren(companyId).map(childRows -> {
                parentOpt.ifPresent(p -> {
                    JsonObject parent = new JsonObject()
                        .put("id",   p.getLong("id"))
                        .put("name", p.getString("name"))
                        .put("slug", p.getString("slug"))
                        .put("relationshipType", p.getString("relationship_type"));
                    String logo = p.getString("logo_url");
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
                    String logo = c.getString("logo_url");
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
                String statsLogoUrl     = companyRow.getString("stats_logo_url");
                String storedLogoUrl    = companyRow.getString("logo_url");
                String resolvedLogoUrl  = logoResolver.apply(canonicalName);
                String logoUrl = statsLogoUrl != null && statsLogoUrl.contains("logo.dev") ? statsLogoUrl
                               : storedLogoUrl != null && storedLogoUrl.contains("logo.dev") ? storedLogoUrl
                               : resolvedLogoUrl;
                long companyId = companyRow.getLong("id");
                String companyIndustry = companyRow.getString("industry");
                return companyRepo.findManagersByCompanyId(companyId)
                    .map(rows -> buildCompanyProfileResponse(companyId, canonicalName, companySlug,
                                                             companyIndustry, logoUrl, rows))
                    // The slug route is the one people actually reach from a link, so it needs the
                    // corporate structure just as much as the by-name route.
                    .compose(result -> withCorporateStructure(result, companyId));
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
        String bestLogoUrl = logoUrl;
        if (bestLogoUrl == null || bestLogoUrl.isBlank()) {
            for (Row row : rows) {
                String mgrLogo = row.getString("company_logo_url");
                if (mgrLogo != null && mgrLogo.contains("logo.dev")) { bestLogoUrl = mgrLogo; break; }
            }
        }
        final String finalLogoUrl = bestLogoUrl;
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

    /** All validation and business logic for POST /api/managers. Returns the created manager row. */
    public Future<Row> createManager(String auth0Id, JsonObject body, String resolvedLogoUrl) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        String name    = toProperNameCase(body.getString("name"));
        String company = body.getString("company") != null ? body.getString("company").trim() : null;
        String title   = body.getString("title")   != null ? body.getString("title").trim()   : null;
        String image   = body.getString("image");
        if (isBlank(name) || isBlank(company) || isBlank(title) || isBlank(image)) {
            return Future.failedFuture(ServiceException.badRequest("Missing required fields"));
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

        if (overallRating == null || ratings == null || isBlank(managerCompany) || isBlank(managerTitle)) {
            return Future.failedFuture(ServiceException.badRequest("Review is missing required fields"));
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
                    .compose(todayCount -> {
                        if (todayCount >= 6) return Future.failedFuture(ServiceException.tooManyRequests("daily_limit_reached"));

                        // Check for an existing manager with the same company and a fuzzy-matching name
                        // (Levenshtein distance ≤ 1). If found, attach the review there instead of
                        // creating a duplicate pending_approval entry.
                        return managerRepo.findByCompanyExact(company)
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
                                    conn.preparedQuery("""
                                        INSERT INTO managers
                                        (name, company, title, image, bio, status, approval_status, country, state, city, linkedin_url,
                                         company_logo_url, company_id, slug, overall_rating, reviews_count, category_averages, created_at, submitted_by)
                                        VALUES ($1,$2,$3,$4,$5,$6,'pending_approval',$7,$8,$9,$10,$11,$12,$13,0,0,'{}'::jsonb,now(),$14)
                                        RETURNING *
                                        """)
                                        .execute(Tuple.of(name, fCompany, title, image, fBio, fStatus, fCountry, fState, fCity, fLinkedinUrl, resolvedLogoUrl, companyId, slug, userId))
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
                                                    .map(ignored -> managerRow)
                                            );
                                        })
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
            // approved or pending_approval — delete any legacy seed, then attach review
            return managerRepo.findByIdFlat(existingId)
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

    public Future<Row> createReview(String auth0Id, long managerId, JsonObject body, String resolvedLogoUrl) {
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
                    .compose(todayCount -> {
                        if (todayCount >= 6) return Future.failedFuture(ServiceException.tooManyRequests("daily_limit_reached"));
                        return reviewRepo.findRecentDeletion(userId, managerId);
                    })
                    .compose(recentDeletion -> {
                        if (recentDeletion.isPresent()) {
                            OffsetDateTime deletedAt = recentDeletion.get();
                            OffsetDateTime cooldownEnd = deletedAt.plusDays(30);
                            if (OffsetDateTime.now(ZoneOffset.UTC).isBefore(cooldownEnd)) {
                                String cooldownEndStr = cooldownEnd.toLocalDate().toString(); // YYYY-MM-DD
                                return Future.failedFuture(ServiceException.conflict("review_cooldown:" + cooldownEndStr));
                            }
                        }
                        UUID draftToken = null;
                        String draftTokenStr = body.getString("draftToken");
                        if (draftTokenStr != null && !draftTokenStr.isBlank()) {
                            try { draftToken = UUID.fromString(draftTokenStr); } catch (IllegalArgumentException ignored) {}
                        }
                        return validateAndInsertReview(body, managerId, userId, author, resolvedLogoUrl, draftToken);
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
        return validateAndInsertReview(body, managerId, userId, author, resolvedLogoUrl, draftToken, false);
    }

    /**
     * @param allowIncomplete true only for drop-off capture, where the point is to keep what
     *                        somebody had when they walked away. A real submission must still state
     *                        when they worked with the manager; a capture cannot, because the
     *                        person never reached that field. Requiring it there threw away exactly
     *                        the submissions the capture exists to save.
     */
    private Future<Row> validateAndInsertReview(JsonObject body, long managerId, UUID userId, String author, String resolvedLogoUrl, UUID draftToken, boolean allowIncomplete) {
        Double overallRating      = body.getDouble("overallRating");
        JsonObject ratings        = body.getJsonObject("ratings");
        String managerCompany     = body.getString("managerCompany") != null ? body.getString("managerCompany").trim() : null;
        String managerTitle       = body.getString("managerTitle")   != null ? body.getString("managerTitle").trim()   : null;
        String text               = body.getString("text")           != null ? body.getString("text").trim()           : null;
        LocalDate workedFrom      = parseYearMonth(body.getString("workedFrom"));
        LocalDate workedUntil     = parseYearMonth(body.getString("workedUntil"));
        LocalDate managerRoleStart = parseYearMonth(body.getString("managerRoleStart"));
        LocalDate managerRoleEnd   = parseYearMonth(body.getString("managerRoleEnd")); // null = still in role
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
                            workedFrom, workedUntil, null, null, resolvedLogoUrl, draftToken);
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
                                workedFrom, workedUntil, managerRoleStart, managerRoleEnd, resolvedLogoUrl, draftToken);
                    });  // closes allRoleRows compose
            })  // closes existingRows compose
        );  // closes deleteDraftFirst compose
    }

    /**
     * Inserts a review and, within the same transaction, updates the manager's
     * company/title/logo if this review is the most current one for that manager.
     * Either both succeed or both roll back — the caller gets a failed Future on error.
     */
    private Future<Row> insertReviewTransactionally(
            long managerId, UUID userId, String author, double overallRating,
            JsonObject ratings, String managerCompany, String managerTitle, String text,
            LocalDate workedFrom, LocalDate workedUntil,
            LocalDate managerRoleStart, LocalDate managerRoleEnd,
            String resolvedLogoUrl, UUID draftToken) {

        return ((Pool) db).withTransaction(conn -> {
            // Authenticated submit with a token: delete the matching anonymous drop-off draft first.
            Future<Void> deleteDraft = (userId != null && draftToken != null)
                ? conn.preparedQuery("DELETE FROM reviews WHERE draft_token = $1 AND user_id IS NULL")
                      .execute(Tuple.of(draftToken))
                      .mapEmpty()
                : Future.succeededFuture();

            // draft_token is stored only on anonymous drop-off inserts; authenticated reviews get null.
            UUID tokenToStore = (userId == null) ? draftToken : null;

            return deleteDraft.compose(v ->
                conn.preparedQuery("""
                        INSERT INTO reviews (
                            manager_id, user_id, author, overall_rating,
                            communication_style, perceived_approachability, perceived_clarity_of_expectations,
                            feedback_style, perceived_supportiveness, decision_making_style,
                            organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                            overall_working_experience, manager_company, manager_title, text,
                            worked_from, worked_until, manager_role_start, manager_role_end,
                            draft_token, verified, helpful_count, created_at, updated_at
                        )
                        VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,true,0,now(),now())
                        RETURNING *
                        """)
                    .execute(Tuple.of(
                        managerId, userId, author, overallRating,
                        getRating(ratings, 0), getRating(ratings, 1), getRating(ratings, 2),
                        getRating(ratings, 3), getRating(ratings, 4), getRating(ratings, 5),
                        getRating(ratings, 6), getRating(ratings, 7), getRating(ratings, 8),
                        getRating(ratings, 9), managerCompany, managerTitle, text,
                        workedFrom, workedUntil, managerRoleStart, managerRoleEnd, tokenToStore
                    ))
            )
                .compose(reviewResult -> {
                    Row reviewRow = reviewResult.iterator().next();
                    UUID newId = reviewRow.getUUID("id");
                    return conn.preparedQuery("""
                            SELECT id, manager_company, manager_title, worked_from, worked_until
                            FROM reviews
                            WHERE manager_id = $1 AND weight = FALSE AND deleted_at IS NULL
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
                                    return conn.preparedQuery(
                                            "UPDATE managers SET updated_at = now(), company = $1, title = $2, company_logo_url = $3, company_id = $4 WHERE id = $5")
                                        .execute(Tuple.of(currentCompany, currentTitle, currentLogo, cmpId, managerId));
                                })
                                .map(ignored -> reviewRow);
                        });
                });
        }).compose(row -> {
            managerRepo.recalculateInBackground(managerId);
            // .compose rather than .onSuccess: a void success handler cannot await, which is how
            // this write used to outlive the request that started it.
            return companyRepo.syncStatsForManager(managerId).map(statsDone -> row);
        });
    }

    // ── GET manager reviews ───────────────────────────────────────────────────

    public Future<JsonObject> getManagerReviews(long managerId, int limit, int offset,
                                                  String sortBy, UUID userIdFilter) {
        Future<Long>        totalFuture = reviewRepo.countByManager(managerId, userIdFilter);
        Future<RowSet<Row>> dataFuture  = reviewRepo.findByManager(managerId, limit, offset, sortBy, userIdFilter);

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
        int effectiveLimit  = Math.min(Math.max(limit, 1), 50);
        int effectiveOffset = Math.max(offset, 0);
        return Future.all(
            reviewRepo.countCareerSegmentsByManager(managerId),
            reviewRepo.findCareerSegmentsByManager(managerId, effectiveLimit, effectiveOffset)
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

        if (overallRating == null || ratings == null || isBlank(managerCompany) || isBlank(managerTitle)) return Future.failedFuture(ServiceException.badRequest("Missing required fields"));
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

                        return reviewRepo.update(reviewId, managerId, callerId, author, overallRating,
                                ratings.getDouble("Communication Style"), ratings.getDouble("Perceived Approachability"),
                                ratings.getDouble("Perceived Clarity of Expectations"), ratings.getDouble("Feedback Style"),
                                ratings.getDouble("Perceived Supportiveness"), ratings.getDouble("Decision Making Style"),
                                ratings.getDouble("Organization and Planning Style"), ratings.getDouble("Delegation Style"),
                                ratings.getDouble("Perceived Professional Demeanor"), ratings.getDouble("Overall Working Experience"),
                                managerCompany, managerTitle, text, workedFrom, workedUntil,
                                managerRoleStart, managerRoleEnd)
                            .compose(rowOpt -> {
                                if (rowOpt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Review not found"));
                                managerRepo.recalculateInBackground(managerId);
                                return Future.succeededFuture(rowOpt.get());
                            });
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
                        return reviewRepo.delete(reviewId, managerId)
                            .compose(v -> {
                                // Recalculate immediately after soft-delete, before recordDeletion,
                                // so stale stats are never left behind if recordDeletion fails.
                                managerRepo.recalculateInBackground(managerId);
                                return reviewRepo.recordDeletion(userId, managerId);
                            })
                            .map(v -> new JsonObject().put("success", true).put("message", "Review deleted"));
                    });
            });
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
        if (overallRating == null || ratings == null || isBlank(managerCompany) || isBlank(managerTitle)) return ServiceException.badRequest("Missing required fields");
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
                    .compose(todayEdits -> {
                        if (todayEdits >= 6) return Future.failedFuture(ServiceException.tooManyRequests("daily_limit_reached"));
                        return managerRepo.findById(managerId)
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
                            });
                    });
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
            .put("workedFrom",    row.getLocalDate("worked_from")  != null ? row.getLocalDate("worked_from").toString()  : null)
            .put("workedUntil",   row.getLocalDate("worked_until") != null ? row.getLocalDate("worked_until").toString() : null);
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

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

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
                            state, city, resolvedLogoUrl, null);
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
                                           String resolvedLogoUrl, Long companyId) {
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

                return Future.all(
                    userRepo.hasContributed(userId),
                    managerRepo.search(5, 0, "%" + fullName + "%", "%" + company.trim() + "%", "featured", userId)
                ).compose(cf -> {
                    boolean     contributed = cf.resultAt(0);
                    RowSet<Row> rows        = cf.resultAt(1);

                    List<Row> matched = new ArrayList<>();
                    for (Row row : rows) {
                        if (row.getString("name").equalsIgnoreCase(fullName.trim())) matched.add(row);
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
                    return managerRepo.findByCompanyExact(company.trim()).compose(candidates -> {
                        Row fuzzyMatch = findFuzzyNameMatch(candidates, fullName.trim());
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
                                return reviewRepo.createSeedReview(newId, company, title)
                                    .compose(ignored -> {
                                        managerRepo.recalculateInBackground(newId);
                                        return companyRepo.syncStatsForManager(newId)
                                            .compose(statsDone -> Future.succeededFuture(row));
                                    })
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
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));
        String name    = toProperNameCase(body.getString("name"));
        String company = body.getString("company") != null ? body.getString("company").trim() : null;
        String title   = body.getString("title")   != null ? body.getString("title").trim()   : null;
        String country = body.getString("country") != null ? body.getString("country").trim() : null;
        String state   = body.getString("state")   != null ? body.getString("state").trim()   : null;
        String city    = body.getString("city")    != null ? body.getString("city").trim()    : null;
        if (isBlank(name) || isBlank(company) || isBlank(title) || isBlank(country)) {
            return Future.failedFuture(ServiceException.badRequest("Missing required fields"));
        }
        if (name.length()    > 100) return Future.failedFuture(ServiceException.badRequest("Name too long"));
        if (company.length() > 100) return Future.failedFuture(ServiceException.badRequest("Company too long"));
        if (title.length()   > 100) return Future.failedFuture(ServiceException.badRequest("Title too long"));
        if (country.length() > 100) return Future.failedFuture(ServiceException.badRequest("Country too long"));
        if (isBlank(state)) state = null;
        if (isBlank(city))  city  = null;
        if (state != null && state.length() > 100) return Future.failedFuture(ServiceException.badRequest("State too long"));
        if (city  != null && city.length()  > 100) return Future.failedFuture(ServiceException.badRequest("City too long"));

        String[] nameParts = name.trim().split("\\s+", 2);
        String firstName = nameParts[0];
        String lastName  = nameParts.length > 1 ? nameParts[1] : "";
        NameValidator.ValidationResult nameValidation =
            NameValidator.validate(firstName, lastName, title, company, country);
        if (!nameValidation.valid())
            return Future.failedFuture(ServiceException.badRequest(nameValidation.reason()));

        final String fState = state;
        final String fCity  = city;

        return managerRepo.findByNameAndCompany(name, company)
            .compose(rows -> {
                if (rows.iterator().hasNext()) {
                    Row row = rows.iterator().next();
                    return Future.succeededFuture(
                        new JsonObject()
                            .put("id", row.getLong("id"))
                            .put("name", row.getString("name"))
                            .put("created", false)
                    );
                }
                return companyRepo.resolve(body.getLong("companyId"), company, null, resolvedLogoUrl)
                    .compose(companyRow -> managerRepo.createGhost(name, company, title, country, fState, fCity, resolvedLogoUrl, companyRow.getLong("id")))
                    .compose(row -> {
                        long newId = row.getLong("id");
                        return reviewRepo.createSeedReview(newId, company, title)
                            .compose(ignored -> {
                                managerRepo.recalculateInBackground(newId);
                                return companyRepo.syncStatsForManager(newId)
                                    .compose(statsDone -> Future.succeededFuture(row));
                            })
                            .recover(err -> {
                                System.err.println("Seed review creation failed for ghost manager " + newId + ": " + err.getMessage());
                                return companyRepo.syncStatsForManager(newId)
                                    .compose(statsDone -> Future.succeededFuture(row));
                            });
                    })
                    .map(row -> new JsonObject()
                        .put("id", row.getLong("id"))
                        .put("name", row.getString("name"))
                        .put("created", true));
            });
    }

    /**
     * Silently creates a pending_approval manager when an anonymous user searches for a
     * non-existent manager after their one-time ghost slot has already been used.
     * Returns nothing useful to the caller — the record goes straight to the admin queue.
     */
    public Future<Void> captureAnonymousSearch(JsonObject body, String resolvedLogoUrl) {
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
        if (name.length()    > 100) return Future.failedFuture(ServiceException.badRequest("Name too long"));
        if (company != null && company.length() > 100) return Future.failedFuture(ServiceException.badRequest("Company too long"));
        if (title   != null && title.length()   > 100) return Future.failedFuture(ServiceException.badRequest("Title too long"));
        if (country != null && country.length() > 100) return Future.failedFuture(ServiceException.badRequest("Country too long"));
        if (isBlank(state)) state = null;
        if (state != null && state.length() > 100) return Future.failedFuture(ServiceException.badRequest("State too long"));

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

        // If an approved/ghost manager already exists, skip — it's already visible.
        // If it's already pending (from a prior anonymous capture), skip — don't duplicate.
        return managerRepo.findByNameAndCompany(name, fCompany)
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
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        String name    = toProperNameCase(body.getString("name"));
        String company = body.getString("company") != null ? body.getString("company").trim() : null;
        String title   = body.getString("title")   != null ? body.getString("title").trim()   : null;
        String country = body.getString("country") != null ? body.getString("country").trim() : null;
        String state   = body.getString("state")   != null ? body.getString("state").trim()   : null;
        String status  = "retired".equals(body.getString("status")) ? "retired" : "active";

        if (isBlank(name) || isBlank(company) || isBlank(title) || isBlank(country))
            return Future.failedFuture(ServiceException.badRequest("Missing required fields: name, company, title, country"));
        if (name.length()    > 100) return Future.failedFuture(ServiceException.badRequest("Name too long"));
        if (company.length() > 100) return Future.failedFuture(ServiceException.badRequest("Company too long"));
        if (title.length()   > 100) return Future.failedFuture(ServiceException.badRequest("Title too long"));
        if (country.length() > 100) return Future.failedFuture(ServiceException.badRequest("Country too long"));
        if (isBlank(state)) state = null;
        if (state != null && state.length() > 100) return Future.failedFuture(ServiceException.badRequest("State too long"));

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

        return managerRepo.findByNameAndCompany(name, company)
            .compose(rows -> {
                if (rows.iterator().hasNext()) {
                    Row existing = rows.iterator().next();
                    long existingId = existing.getLong("id");
                    String approvalStatus = existing.getString("approval_status");

                    if ("ghost".equals(approvalStatus)) {
                        return validateAndInsertReview(review, existingId, null, fAuthor, resolvedLogoUrl, fDropOffToken)
                            .compose(ignored -> reviewRepo.scheduleSeedExpiry(existingId))
                            .map(ignored -> new JsonObject().put("id", existingId).put("created", false));
                    } else {
                        return validateAndInsertReview(review, existingId, null, fAuthor, resolvedLogoUrl, fDropOffToken)
                            .map(ignored -> new JsonObject().put("id", existingId).put("created", false));
                    }
                } else {
                    return companyRepo.resolve(body.getLong("companyId"), company, null, resolvedLogoUrl)
                        .compose(companyRow -> managerRepo.createPending(name, company, title, fStatus, country, fState, resolvedLogoUrl, companyRow.getLong("id")))
                        .compose(managerRow -> {
                            long managerId = managerRow.getLong("id");
                            return validateAndInsertReview(review, managerId, null, fAuthor, resolvedLogoUrl, fDropOffToken)
                                .map(ignored -> new JsonObject().put("id", managerId).put("created", true));
                        });
                }
            });
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

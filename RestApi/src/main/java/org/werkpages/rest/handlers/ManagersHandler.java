package org.werkpages.rest.handlers;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import org.werkpages.service.ManagerService;
import org.werkpages.service.ServiceException;

import java.util.UUID;

/**
 * Thin HTTP adapter for manager and review endpoints.
 * All business logic lives in {@link ManagerService}.
 */
public class ManagersHandler {

    private final ManagerService service;
    private final JWTAuth jwtAuth;
    private final WebClient      httpClient;

    public ManagersHandler(ManagerService service, Vertx vertx, JWTAuth jwtAuth) {
        this.jwtAuth = jwtAuth;
        this.service    = service;
        this.httpClient = WebClient.create(vertx);
    }

    // ── GET /api/managers ─────────────────────────────────────────────────────

    public void handleGetManagers(RoutingContext ctx) {
        int limit  = parseIntParam(ctx.queryParam("limit").stream().findFirst().orElse("20"),  20,  1, 100);
        int offset = parseIntParam(ctx.queryParam("offset").stream().findFirst().orElse("0"),  0,  0, Integer.MAX_VALUE);
        String search  = ctx.queryParam("search").stream().findFirst().orElse(null);
        String company = ctx.queryParam("company").stream().findFirst().orElse(null);
        String sortBy  = ctx.queryParam("sortBy").stream().findFirst().orElse(null);

        int effectiveLimit  = Math.min(limit, 100);
        int effectiveOffset = Math.max(offset, 0);

        if (search != null && !search.isBlank() && search.trim().length() > 100) {
            respond(ctx, 400, new JsonObject().put("error", "Search query too long"));
            return;
        }
        if (company != null && !company.isBlank() && company.trim().length() > 100) {
            respond(ctx, 400, new JsonObject().put("error", "Company filter too long"));
            return;
        }

        String searchPattern  = (search  != null && !search.isBlank())  ? "%" + search.trim()  + "%" : null;
        String companyPattern = (company != null && !company.isBlank()) ? "%" + company.trim() + "%" : null;

        Future<Long>         totalFuture = service.countManagers(search, company);
        Future<RowSet<Row>>  dataFuture  = service.getManagerRows(effectiveLimit, effectiveOffset, search, company, sortBy);

        Future.all(totalFuture, dataFuture).onComplete(ar -> {
            if (ar.failed()) { handleError(ctx, ar.cause()); return; }

            JsonArray data = new JsonArray();
            for (Row row : dataFuture.result()) {
                String rowCompany = row.getString("company");
                String logoUrl = row.getString("company_logo_url");
                if (logoUrl == null) logoUrl = CompanyLogoUtils.resolveLogoUrl(rowCompany);
                data.add(new JsonObject()
                    .put("id", row.getLong("id"))
                    .put("name", row.getString("name"))
                    .put("company", rowCompany)
                    .put("title", row.getString("title"))
                    .put("image", row.getString("image"))
                    .put("overallRating", row.getBigDecimal("overall_rating"))
                    .put("reviews", row.getInteger("reviews_count"))
                    .put("bio", row.getString("bio"))
                    .put("status", row.getString("status"))
                    .put("approvalStatus", row.getString("approval_status"))
                    .put("categoryAverages", row.getJsonObject("category_averages"))
                    .put("linkedinUrl", row.getString("linkedin_url"))
                    .put("country", row.getString("country"))
                    .put("companyLogoUrl", logoUrl)
                    .put("createdAt", row.getOffsetDateTime("created_at").toString())
                    .put("careerHistory", row.getJsonArray("career_history"))
                    // Industry of the manager's company — the third line on manager cards.
                    // Null until the AI classifier has run for that company.
                    .put("industry", row.getString("industry"))
                    .put("community", row.getValue("submitted_by") != null && row.getString("external_id") == null)
                );
            }
            ctx.response().putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("data", data)
                    .put("limit", effectiveLimit)
                    .put("offset", effectiveOffset)
                    .put("total", totalFuture.result())
                    .encode());
        });
    }

    // ── GET /api/managers/:id ─────────────────────────────────────────────────

    public void handleGetManagerById(RoutingContext ctx) {
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            respond(ctx, 400, new JsonObject().put("error", "Invalid manager ID")); return;
        }
        final long finalId = managerId;

        AuthTokenUtils.verifiedAuth0Id(ctx, jwtAuth)
            .compose(auth0Id -> service.getManagerById(finalId, auth0Id)
                .compose(row -> io.vertx.core.Future.all(
                    service.hasReported(finalId, auth0Id),
                    service.isContributor(auth0Id)
                ).map(cf -> buildManagerResponse(row, cf.resultAt(0), cf.resultAt(1)))))
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    private JsonObject buildManagerResponse(Row row, boolean hasReported, boolean contributed) {
        String company = row.getString("company");
        String logoUrl = row.getString("company_logo_url");
        if (logoUrl == null) logoUrl = CompanyLogoUtils.resolveLogoUrl(company);
        JsonObject categoryAverages = contributed
            ? row.getJsonObject("category_averages")
            : new JsonObject();
        return new JsonObject()
            .put("hasReported", hasReported)
            .put("id", row.getLong("id"))
            .put("name", row.getString("name"))
            .put("company", company)
            .put("title", row.getString("title"))
            .put("image", row.getString("image"))
            .put("overallRating", row.getBigDecimal("overall_rating"))
            .put("reviews", row.getJsonArray("reviews"))
            .put("bio", row.getString("bio"))
            .put("status", row.getString("status"))
            .put("approvalStatus", row.getString("approval_status"))
            .put("categoryAverages", categoryAverages)
            .put("linkedinUrl", row.getString("linkedin_url"))
            .put("country", row.getString("country"))
            .put("companyLogoUrl", logoUrl)
            .put("createdAt", row.getOffsetDateTime("created_at").toString())
            .put("careerHistory", row.getJsonArray("career_history"))
            .put("slug", row.getString("slug"))
            .put("companySlug", row.getString("company_slug"))
            // Industry of the manager's company. Null until the AI classifier has run for that
            // company; the frontend renders the link only when it is present.
            .put("industry", row.getString("industry"))
            .put("industrySlug", org.werkpages.service.IndustryTaxonomy.slug(row.getString("industry")));
    }

    // ── GET /api/managers/similar ─────────────────────────────────────────────

    public void handleGetSimilarManagers(RoutingContext ctx) {
        String name    = ctx.queryParam("name").stream().findFirst().orElse(null);
        String company = ctx.queryParam("company").stream().findFirst().orElse(null);
        service.getSimilarManagers(name, company)
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/companies/listing ────────────────────────────────────────────

    public void handleGetCompanyListing(RoutingContext ctx) {
        service.getCompanyListing()
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/companies/by-name ────────────────────────────────────────────

    public void handleGetCompanyProfile(RoutingContext ctx) {
        String company = ctx.queryParam("company").stream().findFirst().orElse(null);
        AuthTokenUtils.verifiedAuth0Id(ctx, jwtAuth).compose(auth0Id -> io.vertx.core.Future.all(
            service.getCompanyProfile(company),
            service.isContributor(auth0Id)
        )).onSuccess(cf -> {
            JsonObject json = cf.resultAt(0);
            boolean contributed = cf.resultAt(1);
            if (!contributed) json.put("categoryAverages", new JsonObject());
            ctx.response().putHeader("Content-Type", "application/json").end(json.encode());
        }).onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/companies/by-slug/{companySlug} ──────────────────────────────

    public void handleGetCompanyBySlug(RoutingContext ctx) {
        String slug = ctx.pathParam("companySlug");
        AuthTokenUtils.verifiedAuth0Id(ctx, jwtAuth).compose(auth0Id -> io.vertx.core.Future.all(
            service.getCompanyBySlug(slug),
            service.isContributor(auth0Id)
        )).onSuccess(cf -> {
            JsonObject json = cf.resultAt(0);
            boolean contributed = cf.resultAt(1);
            if (!contributed) json.put("categoryAverages", new JsonObject());
            ctx.response().putHeader("Content-Type", "application/json").end(json.encode());
        }).onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/managers/by-slug/{managerSlug} ───────────────────────────────

    public void handleGetManagerBySlug(RoutingContext ctx) {
        String managerSlug         = ctx.pathParam("managerSlug");
        String expectedCompanySlug = ctx.queryParam("expectedCompanySlug").stream().findFirst().orElse(null);

        AuthTokenUtils.verifiedAuth0Id(ctx, jwtAuth).compose(auth0Id ->
            service.getManagerBySlug(managerSlug, auth0Id)
            .compose(row -> io.vertx.core.Future.all(
                service.hasReported(row.getLong("id"), auth0Id),
                service.isContributor(auth0Id)
            ).map(cf -> {
                JsonObject response = buildManagerResponse(row, cf.resultAt(0), cf.resultAt(1));
                if (expectedCompanySlug != null && !expectedCompanySlug.isBlank()) {
                    String currentCompanySlug = row.getString("company_slug");
                    if (currentCompanySlug != null && !currentCompanySlug.equals(expectedCompanySlug)) {
                        response.put("canonicalPath", "/companies/" + currentCompanySlug + "/managers/" + row.getString("slug"));
                    }
                }
                return response;
            })))
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/companies ────────────────────────────────────────────────────

    public void handleGetCompanies(RoutingContext ctx) {
        service.getCompanies()
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/companies/suggest ────────────────────────────────────────────

    public void handleSuggestCompanies(RoutingContext ctx) {
        String query = ctx.queryParam("query").stream().findFirst().orElse("").trim();
        if (query.isBlank()) {
            ctx.response().putHeader("Content-Type", "application/json").end("[]");
            return;
        }

        // Proxy Clearbit autocomplete server-side to bypass browser CORS restrictions.
        // Returns { name, domain } pairs so the client can render logo.dev logos and
        // show the domain. Falls back to our own DB search if Clearbit is unavailable.
        httpClient.get(443, "autocomplete.clearbit.com", "/v1/companies/suggest")
            .ssl(true)
            .addQueryParam("query", query)
            .timeout(3000)
            .send()
            .compose(res -> {
                if (res.statusCode() == 200) {
                    JsonArray raw = res.bodyAsJsonArray();
                    JsonArray out = new JsonArray();
                    for (Object obj : raw) {
                        JsonObject item = (JsonObject) obj;
                        String name   = item.getString("name");
                        String domain = item.getString("domain");
                        if (name != null && !name.isBlank() && domain != null && !domain.isBlank()) {
                            out.add(new JsonObject().put("name", name).put("domain", domain));
                        }
                        if (out.size() >= 6) break;
                    }
                    if (!out.isEmpty()) return Future.succeededFuture(out);
                }
                return service.suggestCompanies(query);
            })
            .recover(err -> service.suggestCompanies(query))
            .onSuccess(arr -> ctx.response().putHeader("Content-Type", "application/json").end(arr.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/stats ────────────────────────────────────────────────────────

    public void handleGetStats(RoutingContext ctx) {
        service.getStats()
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/me/submitted-managers ───────────────────────────────────────

    public void handleGetMySubmittedManagers(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("error", "Unauthorized")); return; }
        service.getMySubmittedManagers(auth0Id)
            .onSuccess(rows -> {
                JsonArray data = new JsonArray();
                for (Row row : rows) {
                    String co = row.getString("company");
                    String logo = row.getString("company_logo_url");
                    if (logo == null) logo = CompanyLogoUtils.resolveLogoUrl(co);
                    data.add(new JsonObject()
                        .put("id", row.getLong("id"))
                        .put("name", row.getString("name"))
                        .put("company", co)
                        .put("title", row.getString("title"))
                        .put("image", row.getString("image"))
                        .put("overallRating", row.getBigDecimal("overall_rating"))
                        .put("reviews", row.getInteger("reviews_count"))
                        .put("bio", row.getString("bio"))
                        .put("status", row.getString("status"))
                        .put("approvalStatus", row.getString("approval_status"))
                        .put("linkedinUrl", row.getString("linkedin_url"))
                        .put("country", row.getString("country"))
                        .put("companyLogoUrl", logo)
                        .put("createdAt", row.getOffsetDateTime("created_at").toString())
                    );
                }
                ctx.response().putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("data", data).encode());
            })
            .onFailure(err -> handleError(ctx, err));
    }

    // ── POST /api/managers ────────────────────────────────────────────────────

    public void handleCreateManager(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("error", "Unauthorized")); return; }
        JsonObject body = ctx.getBodyAsJson();
        GeoUtils.stampGeo(ctx, body);
        String company = body != null ? body.getString("company") : null;
        String logoUrl = CompanyLogoUtils.resolveLogoUrl(company);

        service.createManager(auth0Id, body, logoUrl)
            .onSuccess(row -> {
                JsonObject response = new JsonObject()
                    .put("id", row.getLong("id"))
                    .put("name", row.getString("name"))
                    .put("company", row.getString("company"))
                    .put("title", row.getString("title"))
                    .put("image", row.getString("image"))
                    .put("overallRating", row.getBigDecimal("overall_rating"))
                    .put("reviews", row.getInteger("reviews_count"))
                    .put("bio", row.getString("bio"))
                    .put("status", row.getString("status"))
                    .put("approvalStatus", row.getString("approval_status"))
                    .put("categoryAverages", row.getJsonObject("category_averages"))
                    .put("linkedinUrl", row.getString("linkedin_url"))
                    .put("country", row.getString("country"))
                    .put("state", row.getString("state"))
                    .put("city", row.getString("city"))
                    .put("createdAt", row.getOffsetDateTime("created_at").toString())
                    .put("careerHistory", new JsonArray())
                    .put("slug", row.getString("slug"));
                ctx.response().setStatusCode(201).putHeader("Content-Type", "application/json").end(response.encode());
            })
            .onFailure(err -> handleError(ctx, err));
    }

    // ── PUT /api/managers/:id ─────────────────────────────────────────────────

    public void handleUpdateManager(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("error", "Unauthorized")); return; }
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            respond(ctx, 400, new JsonObject().put("error", "Invalid manager ID")); return;
        }
        JsonObject updateBody = ctx.getBodyAsJson();
        if (updateBody != null) {
            String newCompany = updateBody.getString("company");
            if (newCompany != null && !newCompany.isBlank()) {
                String resolvedLogo = CompanyLogoUtils.resolveLogoUrl(newCompany);
                if (resolvedLogo != null) updateBody.put("resolvedLogoUrl", resolvedLogo);
            }
        }
        service.updateManager(auth0Id, managerId, updateBody)
            .onSuccess(json -> ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── POST /api/managers/:id/reviews ────────────────────────────────────────

    public void handleCreateManagerReview(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("error", "Unauthorized")); return; }
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            respond(ctx, 400, new JsonObject().put("error", "Invalid manager ID format")); return;
        }
        JsonObject body = ctx.getBodyAsJson();
        String resolvedLogoUrl = CompanyLogoUtils.resolveLogoUrl(body != null ? body.getString("managerCompany") : null);
        service.createReview(auth0Id, managerId, body, resolvedLogoUrl)
            .onSuccess(row -> {
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
                JsonObject response = new JsonObject()
                    .put("id", row.getUUID("id").toString())
                    .put("managerId", row.getLong("manager_id"))
                    .put("author", row.getString("author"))
                    .put("overallRating", row.getBigDecimal("overall_rating"))
                    .put("ratings", ratings)
                    .put("managerCompany", row.getString("manager_company"))
                    .put("managerTitle", row.getString("manager_title"))
                    .put("text", row.getString("text"))
                    .put("verified", row.getBoolean("verified"))
                    .put("helpfulCount", row.getInteger("helpful_count"))
                    .put("createdAt", row.getOffsetDateTime("created_at").toString())
                    .put("workedFrom", row.getLocalDate("worked_from") != null ? row.getLocalDate("worked_from").toString() : null)
                    .put("workedUntil", row.getLocalDate("worked_until") != null ? row.getLocalDate("worked_until").toString() : null);
                ctx.response().setStatusCode(201).putHeader("Content-Type", "application/json").end(response.encode());
            })
            .onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/managers/:id/reviews ─────────────────────────────────────────

    public void handleGetManagerReviews(RoutingContext ctx) {
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            respond(ctx, 400, new JsonObject().put("error", "Invalid manager ID")); return;
        }
        String sortBy = ctx.queryParam("sortBy").stream().findFirst().orElse("recent");
        int limit  = parseIntParam(ctx.queryParam("limit").stream().findFirst().orElse("50"),  50, 1, 200);
        int offset = parseIntParam(ctx.queryParam("offset").stream().findFirst().orElse("0"),  0,  0, Integer.MAX_VALUE);
        String userIdParam = ctx.queryParam("userId").stream().findFirst().orElse(null);
        UUID userId = null;
        if (userIdParam != null) {
            try { userId = UUID.fromString(userIdParam); }
            catch (IllegalArgumentException e) { respond(ctx, 400, new JsonObject().put("error", "Invalid userId format")); return; }
        }
        service.getManagerReviews(managerId, limit, offset, sortBy, userId)
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/managers/:id/career-segments ─────────────────────────────────

    public void handleGetManagerCareerSegments(RoutingContext ctx) {
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            respond(ctx, 400, new JsonObject().put("error", "Invalid manager ID")); return;
        }
        int limit  = parseIntParam(ctx.queryParam("limit").stream().findFirst().orElse("20"),  20, 1, 50);
        int offset = parseIntParam(ctx.queryParam("offset").stream().findFirst().orElse("0"),   0, 0, Integer.MAX_VALUE);
        service.getManagerCareerSegments(managerId, limit, offset)
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── PUT /api/managers/:managerId/reviews/:reviewId ────────────────────────

    public void handleUpdateReview(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("error", "Unauthorized")); return; }
        long managerId;
        UUID reviewId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
            reviewId  = UUID.fromString(ctx.pathParam("reviewId"));
        } catch (Exception e) {
            respond(ctx, 400, new JsonObject().put("error", "Invalid managerId or reviewId")); return;
        }
        service.updateReview(auth0Id, managerId, reviewId, ctx.getBodyAsJson())
            .onSuccess(row -> ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json")
                .end(ManagerService.buildReviewJson(row).encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── DELETE /api/managers/:managerId/reviews/:reviewId ─────────────────────

    public void handleDeleteManagerReview(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("error", "Unauthorized")); return; }
        long managerId;
        UUID reviewId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
            reviewId  = UUID.fromString(ctx.pathParam("reviewId"));
        } catch (Exception e) {
            respond(ctx, 400, new JsonObject().put("error", "Invalid managerId or reviewId")); return;
        }
        service.deleteReview(auth0Id, managerId, reviewId)
            .onSuccess(json -> ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── POST /api/managers/:managerId/reviews/:reviewId/replace ──────────────

    public void handleReplaceManagerReview(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("error", "Unauthorized")); return; }
        long managerId;
        UUID reviewId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
            reviewId  = UUID.fromString(ctx.pathParam("reviewId"));
        } catch (Exception e) {
            respond(ctx, 400, new JsonObject().put("error", "Invalid managerId or reviewId")); return;
        }
        JsonObject body = ctx.body().asJsonObject();
        String resolvedLogoUrl = CompanyLogoUtils.resolveLogoUrl(body != null ? body.getString("managerCompany") : null);
        service.replaceReview(auth0Id, managerId, reviewId, body, resolvedLogoUrl)
            .onSuccess(row -> ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("success", true).encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/me/reviews ───────────────────────────────────────────────────

    public void handleGetMyReviews(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("error", "Unauthorized")); return; }
        int limit  = parseIntParam(ctx.queryParam("limit").stream().findFirst().orElse("50"),  50, 1, 50);
        int offset = parseIntParam(ctx.queryParam("offset").stream().findFirst().orElse("0"),   0, 0, Integer.MAX_VALUE);
        service.getMyReviews(auth0Id, limit, offset)
            .onSuccess(json -> ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/users/me/has-contributed ────────────────────────────────────

    public void handleHasContributed(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("error", "Unauthorized")); return; }
        service.hasContributed(auth0Id)
            .onSuccess(json -> ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── POST /api/managers/:id/edit-requests ──────────────────────────────────

    public void handleCreateEditRequest(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("error", "Unauthorized")); return; }
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            respond(ctx, 400, new JsonObject().put("error", "Invalid manager ID")); return;
        }
        service.createEditRequest(auth0Id, managerId, ctx.getBodyAsJson())
            .onSuccess(json -> ctx.response().setStatusCode(201).putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/managers/:id/pending-edits ───────────────────────────────────

    public void handleGetPendingEditsForManager(RoutingContext ctx) {
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            respond(ctx, 400, new JsonObject().put("error", "Invalid manager ID")); return;
        }
        final long finalManagerId = managerId;
        AuthTokenUtils.verifiedAuth0Id(ctx, jwtAuth)
            .compose(auth0Id -> service.getPendingEditsForManager(finalManagerId, auth0Id))
            .onSuccess(json -> ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── Ghost capture (no auth) ───────────────────────────────────────────────

    public void handleCreateGhostManager(RoutingContext ctx) {
        JsonObject body = ctx.getBodyAsJson();
        GeoUtils.stampGeo(ctx, body);
        String company = body != null ? body.getString("company") : null;
        String logoUrl = CompanyLogoUtils.resolveLogoUrl(company);
        service.createGhostManager(body, logoUrl)
            .onSuccess(json -> {
                int status = Boolean.TRUE.equals(json.getBoolean("created")) ? 201 : 200;
                ctx.response().setStatusCode(status).putHeader("Content-Type", "application/json").end(json.encode());
            })
            .onFailure(err -> handleError(ctx, err));
    }

    // ── Drop-off review for existing manager (no auth) ───────────────────────

    public void handleDropOffReview(RoutingContext ctx) {
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            respond(ctx, 400, new JsonObject().put("error", "Invalid manager ID format")); return;
        }
        JsonObject body = ctx.getBodyAsJson();
        String resolvedLogoUrl = CompanyLogoUtils.resolveLogoUrl(body != null ? body.getString("managerCompany") : null);
        service.createDropOffReview(managerId, body, resolvedLogoUrl)
            .onSuccess(v -> ctx.response().setStatusCode(200).end())
            .onFailure(err -> handleError(ctx, err));
    }

    // ── Drop-off draft (no auth) ─────────────────────────────────────────────

    public void handleDropOffDraft(RoutingContext ctx) {
        JsonObject body = ctx.getBodyAsJson();
        GeoUtils.stampGeo(ctx, body);
        String company = body != null ? body.getString("company") : null;
        String logoUrl = CompanyLogoUtils.resolveLogoUrl(company);
        service.createDropOffDraft(body, logoUrl)
            .onSuccess(json -> {
                int status = Boolean.TRUE.equals(json.getBoolean("created")) ? 201 : 200;
                ctx.response().setStatusCode(status).putHeader("Content-Type", "application/json").end(json.encode());
            })
            .onFailure(err -> handleError(ctx, err));
    }

    // ── Anonymous intent capture (no auth, no review) ───────────────────────

    public void handleAnonymousCapture(RoutingContext ctx) {
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) { respond(ctx, 400, new JsonObject().put("message", "Request body required")); return; }
        GeoUtils.stampGeo(ctx, body);
        String company = body.getString("company");
        String logoUrl = CompanyLogoUtils.resolveLogoUrl(company);
        service.captureAnonymousSearch(body, logoUrl)
            .onSuccess(v -> ctx.response().setStatusCode(202).end())
            .onFailure(err -> handleError(ctx, err));
    }

    // ── Find-or-create ────────────────────────────────────────────────────────

    /**
     * Null-safe trimmed string from a JSON body. Returns {@code ""} both when the key is
     * absent AND when it is explicitly {@code null}. This matters because Vert.x's
     * {@code getString(key, default)} only applies the default for an ABSENT key — a key
     * present with a JSON null returns {@code null}, so {@code .trim()} would NPE. The
     * frontend sends {@code state:null}/{@code city:null} whenever geolocation can't resolve
     * them, so this guard is required (regression: find-or-create 500'd on null geo fields).
     */
    static String bodyStr(JsonObject body, String key) {
        String v = body.getString(key, "");
        return v == null ? "" : v.trim();
    }

    public void handleFindOrCreate(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) {
            respond(ctx, 401, new JsonObject().put("message", "Unauthorized"));
            return;
        }
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) { respond(ctx, 400, new JsonObject().put("message", "Request body required")); return; }
        GeoUtils.stampGeo(ctx, body);

        String firstName = bodyStr(body, "firstName");
        String lastName  = bodyStr(body, "lastName");
        String title     = bodyStr(body, "title");
        String company   = bodyStr(body, "company");
        String country   = bodyStr(body, "country");
        String state     = bodyStr(body, "state");
        String city      = bodyStr(body, "city");

        String logoUrl = CompanyLogoUtils.resolveLogoUrl(company);
        // Present when the user picked a company from the typeahead rather than typing a name
        // nobody has stored yet. Identity comes from here; `company` is what gets displayed.
        Long companyId = body.getLong("companyId");
        service.findOrCreate(auth0Id, firstName, lastName, title, company, country, state, city, logoUrl, companyId)
            .onSuccess(json -> {
                // Back-fill logo on any rows that don't already have one (existing managers)
                io.vertx.core.json.JsonArray data = json.getJsonArray("data");
                if (data != null) {
                    for (int i = 0; i < data.size(); i++) {
                        JsonObject m = data.getJsonObject(i);
                        if (m.getString("companyLogoUrl") == null) {
                            String resolved = CompanyLogoUtils.resolveLogoUrl(m.getString("company"));
                            if (resolved != null) m.put("companyLogoUrl", resolved);
                        }
                    }
                }
                respond(ctx, 200, json);
            })
            .onFailure(err -> handleError(ctx, err));
    }

    // ── Visitor geo (no auth) ─────────────────────────────────────────────────

    /** Echoes the visitor's {country, state, city} as resolved from Cloudflare headers.
     *  Used by the frontend to pre-fill the Add Manager form. Values are null off Cloudflare. */
    public void handleGetGeo(RoutingContext ctx) {
        ctx.response().putHeader("Content-Type", "application/json").end(GeoUtils.geoJson(ctx).encode());
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    static void handleError(RoutingContext ctx, Throwable err) {
        if (err instanceof ServiceException se) {
            ctx.response()
                .setStatusCode(se.getStatusCode())
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("message", se.getMessage()).encode());
        } else {
            System.err.println("ERROR " + ctx.request().method() + " " + ctx.request().path() + ": " + err);
            ctx.response()
                .setStatusCode(500)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("message", "Internal server error").encode());
        }
    }

    private static void respond(RoutingContext ctx, int status, JsonObject body) {
        ctx.response().setStatusCode(status).putHeader("Content-Type", "application/json").end(body.encode());
    }

    private static int parseIntParam(String raw, int defaultVal, int min, int max) {
        if (raw == null) return defaultVal;
        try { return Math.min(max, Math.max(min, Integer.parseInt(raw))); }
        catch (NumberFormatException e) { return defaultVal; }
    }

}

package org.ratemymanager.rest.handlers;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import org.ratemymanager.service.ManagerService;
import org.ratemymanager.service.ServiceException;

import java.util.UUID;

/**
 * Thin HTTP adapter for manager and review endpoints.
 * All business logic lives in {@link ManagerService}.
 */
public class ManagersHandler {

    private final ManagerService service;

    public ManagersHandler(ManagerService service) {
        this.service = service;
    }

    // ── GET /api/managers ─────────────────────────────────────────────────────

    public void handleGetManagers(RoutingContext ctx) {
        int limit  = parseIntParam(ctx.queryParam("limit").stream().findFirst().orElse("20"),  20,  1, 100);
        int offset = parseIntParam(ctx.queryParam("offset").stream().findFirst().orElse("0"),  0,  0, Integer.MAX_VALUE);
        String search  = ctx.queryParam("search").stream().findFirst().orElse(null);
        String company = ctx.queryParam("company").stream().findFirst().orElse(null);

        int effectiveLimit  = Math.min(limit, 100);
        int effectiveOffset = Math.max(offset, 0);

        if (search != null && !search.isBlank() && search.trim().length() > 100) {
            respond(ctx, 400, new JsonObject().put("error", "Search query too long"));
            return;
        }

        String searchPattern  = (search  != null && !search.isBlank())  ? "%" + search.trim()  + "%" : null;
        String companyPattern = (company != null && !company.isBlank()) ? "%" + company.trim() + "%" : null;

        Future<Long>         totalFuture = service.countManagers(search, company);
        Future<RowSet<Row>>  dataFuture  = service.getManagerRows(effectiveLimit, effectiveOffset, search, company);

        Future.all(totalFuture, dataFuture).onComplete(ar -> {
            if (ar.failed()) { ctx.fail(ar.cause()); return; }

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
                    .put("companyLogoUrl", logoUrl)
                    .put("createdAt", row.getOffsetDateTime("created_at").toString())
                    .put("careerHistory", row.getJsonArray("career_history"))
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
        String auth0Id = extractAuth0IdFromRequest(ctx);
        final long finalId = managerId;

        service.getManagerById(managerId, auth0Id)
            .compose(row -> service.hasReported(finalId, auth0Id)
                .map(hasReported -> buildManagerResponse(row, hasReported))
            )
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    private JsonObject buildManagerResponse(Row row, boolean hasReported) {
        String company = row.getString("company");
        String logoUrl = row.getString("company_logo_url");
        if (logoUrl == null) logoUrl = CompanyLogoUtils.resolveLogoUrl(company);
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
            .put("categoryAverages", row.getJsonObject("category_averages"))
            .put("linkedinUrl", row.getString("linkedin_url"))
            .put("companyLogoUrl", logoUrl)
            .put("createdAt", row.getOffsetDateTime("created_at").toString())
            .put("careerHistory", row.getJsonArray("career_history"));
    }

    // ── GET /api/managers/similar ─────────────────────────────────────────────

    public void handleGetSimilarManagers(RoutingContext ctx) {
        String name    = ctx.queryParam("name").stream().findFirst().orElse(null);
        String company = ctx.queryParam("company").stream().findFirst().orElse(null);
        service.getSimilarManagers(name, company)
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── GET /api/companies ────────────────────────────────────────────────────

    public void handleGetCompanies(RoutingContext ctx) {
        service.getCompanies()
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
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
                    .put("createdAt", row.getOffsetDateTime("created_at").toString())
                    .put("careerHistory", new JsonArray());
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
        service.updateManager(auth0Id, managerId, ctx.getBodyAsJson())
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
        service.createReview(auth0Id, managerId, ctx.getBodyAsJson())
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

    // ── GET /api/me/reviews ───────────────────────────────────────────────────

    public void handleGetMyReviews(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("error", "Unauthorized")); return; }
        service.getMyReviews(auth0Id)
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
        String auth0Id = extractAuth0IdFromRequest(ctx);
        service.getPendingEditsForManager(managerId, auth0Id)
            .onSuccess(json -> ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> handleError(ctx, err));
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    static void handleError(RoutingContext ctx, Throwable err) {
        if (err instanceof ServiceException se) {
            ctx.response()
                .setStatusCode(se.getStatusCode())
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", se.getMessage()).encode());
        } else {
            ctx.fail(err);
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

    /** Extracts auth0Id from Authorization header or auth_token cookie (no signature check). */
    static String extractAuth0IdFromRequest(RoutingContext ctx) {
        String authHeader = ctx.request().getHeader("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring("Bearer ".length());
        } else {
            String cookieHeader = ctx.request().getHeader("Cookie");
            if (cookieHeader != null) {
                for (String part : cookieHeader.split(";")) {
                    String trimmed = part.trim();
                    if (trimmed.startsWith("auth_token=")) {
                        token = trimmed.substring("auth_token=".length());
                        break;
                    }
                }
            }
        }
        if (token == null) return null;
        try {
            DecodedJWT jwt = JWT.decode(token);
            return jwt.getSubject();
        } catch (JWTDecodeException e) {
            return null;
        }
    }
}

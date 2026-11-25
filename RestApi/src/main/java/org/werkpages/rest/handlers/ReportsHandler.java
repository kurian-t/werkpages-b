package org.werkpages.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.werkpages.service.ReportService;

import java.util.UUID;

/**
 * Thin HTTP adapter for the report endpoints.
 * All business logic lives in {@link ReportService}.
 */
public class ReportsHandler {

    private final ReportService service;

    public ReportsHandler(ReportService service) {
        this.service = service;
    }

    public void handleReportManager(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) {
            ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Unauthorized").encode()); return;
        }
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid manager ID").encode()); return;
        }
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Missing request body").encode()); return;
        }
        String reason  = body.getString("reason");
        String comment = body.getString("comment");

        service.reportManager(auth0Id, managerId, reason, comment)
            .onSuccess(json -> ctx.response().setStatusCode(201).putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/admin/reports ──────────────────────────────────────────────

    public void handleListReports(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        int limit  = parseIntParam(ctx.request().getParam("limit"),  50, 1, 200);
        int offset = parseIntParam(ctx.request().getParam("offset"), 0,  0, Integer.MAX_VALUE);

        service.listReports(auth0Id, ctx.request().getParam("status"), limit, offset)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/reports/:reportId/resolve ───────────────────────────

    public void handleResolveReport(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        UUID reportId;
        try {
            reportId = UUID.fromString(ctx.pathParam("reportId"));
        } catch (IllegalArgumentException e) {
            // The spec types this as a uuid, so the router refuses a malformed one first. Kept
            // because a handler that trusts the contract breaks the moment the contract moves.
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid report ID").encode()); return;
        }
        JsonObject body = ctx.body() == null ? null : ctx.body().asJsonObject();
        if (body == null) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Missing request body").encode()); return;
        }

        service.resolveReport(auth0Id, reportId, body.getString("action"), body.getString("note"))
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private static void ok(RoutingContext ctx, JsonObject json) {
        ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json")
            .end(json.encode());
    }

    private static int parseIntParam(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Math.min(Math.max(Integer.parseInt(raw.trim()), min), max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

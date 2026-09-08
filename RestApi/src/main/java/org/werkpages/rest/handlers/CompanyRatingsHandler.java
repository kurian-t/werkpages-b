package org.werkpages.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.werkpages.service.CompanyReviewService;

import java.util.UUID;

/**
 * Thin HTTP adapter for company ratings — what an employer is like to work for.
 *
 * <p>Validation and rules live in {@link CompanyReviewService}; error mapping is shared with
 * {@link ManagersHandler} so a 404 here looks like a 404 everywhere else.
 */
public class CompanyRatingsHandler {

    private final CompanyReviewService service;

    public CompanyRatingsHandler(CompanyReviewService service) {
        this.service = service;
    }

    // ── POST /api/companies/{companySlug}/rating ──────────────────────────────

    public void handleSubmit(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { unauthorized(ctx); return; }

        service.submit(auth0Id, ctx.pathParam("companySlug"), ctx.body().asJsonObject())
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/companies/{companySlug}/rating ───────────────────────────────

    public void handleGetMine(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { unauthorized(ctx); return; }

        service.findMine(auth0Id, ctx.pathParam("companySlug"))
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── DELETE /api/company-ratings/{ratingId} ────────────────────────────────

    public void handleDelete(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { unauthorized(ctx); return; }

        UUID ratingId;
        try {
            ratingId = UUID.fromString(ctx.pathParam("ratingId"));
        } catch (Exception e) {
            respond(ctx, 400, new JsonObject().put("message", "Invalid rating ID"));
            return;
        }

        service.delete(auth0Id, ratingId)
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    private static void unauthorized(RoutingContext ctx) {
        respond(ctx, 401, new JsonObject().put("message", "Unauthorized"));
    }

    private static void respond(RoutingContext ctx, int status, JsonObject body) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(body == null ? "{}" : body.encode());
    }
}

package org.werkpages.rest.handlers;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;
import org.werkpages.service.InterviewService;

/**
 * Thin HTTP adapter for interview experience reviews. Business logic and validation live in
 * {@link InterviewService}; error mapping is shared with {@link ManagersHandler}.
 */
public class InterviewsHandler {

    private final InterviewService service;
    private final JWTAuth jwtAuth;

    public InterviewsHandler(InterviewService service, JWTAuth jwtAuth) {
        this.service = service;
        this.jwtAuth = jwtAuth;
    }

    // ── POST /api/companies/{companySlug}/interviews ──────────────────────────

    public void handleCreateInterviewReview(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("message", "Unauthorized")); return; }

        String companySlug = ctx.pathParam("companySlug");
        JsonObject body    = ctx.body().asJsonObject();

        service.createReview(auth0Id, companySlug, body)
            .onSuccess(json -> respond(ctx, 201, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/companies/{companySlug}/interviews ───────────────────────────

    public void handleGetCompanyInterviews(RoutingContext ctx) {
        String companySlug = ctx.pathParam("companySlug");

        String outcome = blankToNull(ctx.queryParams().get("outcome"));
        String role    = blankToNull(ctx.queryParams().get("role"));

        final Integer sinceYear;
        try {
            sinceYear = parseOptionalInt(ctx.queryParams().get("sinceYear"));
        } catch (NumberFormatException e) {
            respond(ctx, 400, new JsonObject().put("message", "sinceYear must be a number"));
            return;
        }

        // Signed out is a valid state here — the headline count and rating are public, and only the
        // category breakdown is gated. So this resolves to null rather than 401.
        verifiedAuth0Id(ctx)
            .compose(auth0Id -> service.getCompanyInterviews(companySlug, outcome, role, sinceYear, auth0Id))
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── DELETE /api/interviews/{reviewId} ─────────────────────────────────────

    public void handleDeleteInterviewReview(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("message", "Unauthorized")); return; }

        service.deleteReview(auth0Id, ctx.pathParam("reviewId"))
            .onSuccess(v -> ctx.response().setStatusCode(204).end())
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/users/me/has-interview-contributed ───────────────────────────

    public void handleHasInterviewContributed(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { respond(ctx, 401, new JsonObject().put("message", "Unauthorized")); return; }

        service.hasContributed(auth0Id)
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/industries/interview-averages ────────────────────────────────

    public void handleGetIndustryInterviewAverages(RoutingContext ctx) {
        service.getIndustryAverages()
            .onSuccess(arr -> ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("data", arr).encode()))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    /**
     * Resolves the caller on this endpoint, which permits anonymous access. Verification (rather
     * than a bare decode) matters here because the value gates {@code categoryAverages}.
     */
    private Future<String> verifiedAuth0Id(RoutingContext ctx) {
        return AuthTokenUtils.verifiedAuth0Id(ctx, jwtAuth);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void respond(RoutingContext ctx, int status, JsonObject body) {
        ctx.response().setStatusCode(status).putHeader("Content-Type", "application/json").end(body.encode());
    }

    private static String blankToNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : raw.trim();
    }

    private static Integer parseOptionalInt(String raw) {
        return (raw == null || raw.isBlank()) ? null : Integer.valueOf(raw.trim());
    }
}

package org.werkpages.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.werkpages.service.AdminService;
import org.werkpages.service.ProofChallengeService;

import java.util.UUID;

/**
 * HTTP adapter for proof challenges — the author-facing half and the admin half.
 *
 * <p>They live in one file because they are one feature, but they call two different services on
 * purpose: deciding a held rating is not the same act as deciding whether a manager belongs in the
 * directory, and folding them together is how you end up with a single state machine that has to
 * mean both things.
 */
public class ProofChallengesHandler {

    private final ProofChallengeService service;
    private final AdminService adminService;

    public ProofChallengesHandler(ProofChallengeService service, AdminService adminService) {
        this.service      = service;
        this.adminService = adminService;
    }

    // ── Author-facing ─────────────────────────────────────────────────────────

    /** GET /api/managers/{managerId}/proof-challenge */
    public void handleGetMine(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { unauthorized(ctx); return; }

        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
        } catch (NumberFormatException e) {
            respond(ctx, 400, new JsonObject().put("message", "Invalid manager ID"));
            return;
        }

        service.findMine(auth0Id, managerId)
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    /** POST /api/proof-challenges/{challengeId}/evidence */
    public void handleSubmitEvidence(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { unauthorized(ctx); return; }

        UUID challengeId = parseUuid(ctx, "challengeId");
        if (challengeId == null) return;

        service.submitEvidence(auth0Id, challengeId, ctx.body().asJsonObject())
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── Admin-facing ──────────────────────────────────────────────────────────

    /** GET /api/admin/proof-challenges */
    public void handleAdminList(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        int limit  = intParam(ctx, "limit", 20);
        int offset = intParam(ctx, "offset", 0);

        adminService.getProofChallenges(auth0Id, limit, offset)
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    /** POST /api/admin/proof-challenges/{challengeId}/resolve */
    public void handleAdminResolve(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        UUID challengeId = parseUuid(ctx, "challengeId");
        if (challengeId == null) return;

        JsonObject body = ctx.body().asJsonObject();
        boolean approve = body != null && Boolean.TRUE.equals(body.getBoolean("approve"));
        String reason   = body == null ? null : body.getString("reason");

        adminService.resolveProofChallenge(auth0Id, challengeId, approve, reason)
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    /** GET /api/admin/high-profile-figures */
    public void handleListFigures(RoutingContext ctx) {
        adminService.listHighProfileFigures(ctx.get("auth0Id"))
            .onSuccess(arr -> ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json").end(arr.encode()))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    /** POST /api/admin/high-profile-figures */
    public void handleAddFigure(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        if (body == null) { respond(ctx, 400, new JsonObject().put("message", "Body required")); return; }

        adminService.addHighProfileFigure(ctx.get("auth0Id"),
                body.getLong("managerId"), body.getString("fullName"),
                body.getLong("companyId"), body.getString("note"))
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    /** DELETE /api/admin/high-profile-figures/{figureId} */
    public void handleRemoveFigure(RoutingContext ctx) {
        long figureId;
        try {
            figureId = Long.parseLong(ctx.pathParam("figureId"));
        } catch (NumberFormatException e) {
            respond(ctx, 400, new JsonObject().put("message", "Invalid figure ID"));
            return;
        }
        adminService.removeHighProfileFigure(ctx.get("auth0Id"), figureId)
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static UUID parseUuid(RoutingContext ctx, String param) {
        try {
            return UUID.fromString(ctx.pathParam(param));
        } catch (Exception e) {
            respond(ctx, 400, new JsonObject().put("message", "Invalid ID"));
            return null;
        }
    }

    private static int intParam(RoutingContext ctx, String name, int fallback) {
        String raw = ctx.request().getParam(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void unauthorized(RoutingContext ctx) {
        respond(ctx, 401, new JsonObject().put("message", "Unauthorized"));
    }

    private static void respond(RoutingContext ctx, int status, JsonObject body) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(body.encode());
    }
}

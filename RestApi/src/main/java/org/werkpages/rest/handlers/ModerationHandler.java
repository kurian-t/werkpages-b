package org.werkpages.rest.handlers;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.werkpages.service.ModerationService;

/**
 * Thin HTTP adapter for the role-aware moderation queues.
 *
 * <p>No decisions here. {@link ModerationService} resolves the caller to an admin before it reads
 * or writes anything, so this class parses, delegates and serialises - and an authorization bug
 * cannot be introduced by forgetting a check in a new method, because there is no check in any
 * method to forget.
 */
public class ModerationHandler {

    private final ModerationService service;

    public ModerationHandler(ModerationService service) {
        this.service = service;
    }

    // ── GET /api/admin/moderation/summary ────────────────────────────────────

    public void handleSummary(RoutingContext ctx) {
        service.queueSummary(ctx.get("auth0Id"))
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/admin/moderation/employments ────────────────────────────────

    public void handlePendingEmployments(RoutingContext ctx) {
        service.pendingEmployments(ctx.get("auth0Id"), limit(ctx), offset(ctx))
            .onSuccess(rows -> okList(ctx, rows))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/admin/moderation/employments/:roleId ────────────────────────

    public void handleEmploymentDetail(RoutingContext ctx) {
        Long roleId = pathId(ctx, "roleId");
        if (roleId == null) { bad(ctx, "Invalid employment ID"); return; }
        service.employmentDetail(ctx.get("auth0Id"), roleId)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/moderation/employments/:roleId/accept ────────────────

    public void handleAcceptEmployment(RoutingContext ctx) {
        Long roleId = pathId(ctx, "roleId");
        if (roleId == null) { bad(ctx, "Invalid employment ID"); return; }
        service.acceptEmployment(ctx.get("auth0Id"), roleId)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/moderation/employments/:roleId/reject ────────────────

    public void handleRejectEmployment(RoutingContext ctx) {
        Long roleId = pathId(ctx, "roleId");
        if (roleId == null) { bad(ctx, "Invalid employment ID"); return; }
        JsonObject body = bodyOrEmpty(ctx);
        service.rejectEmployment(ctx.get("auth0Id"), roleId, body.getString("reason"))
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/moderation/employments/:roleId/correct ───────────────

    public void handleCorrectEmployment(RoutingContext ctx) {
        Long roleId = pathId(ctx, "roleId");
        if (roleId == null) { bad(ctx, "Invalid employment ID"); return; }
        JsonObject body = bodyOrEmpty(ctx);
        ModerationService.Correction patch;
        try {
            patch = correctionFrom(body);
        } catch (IllegalArgumentException bogus) {
            bad(ctx, bogus.getMessage());
            return;
        }
        boolean accept = Boolean.TRUE.equals(body.getBoolean("thenAccept"));
        String reason = body.getString("reason");
        (accept ? service.correctAndAcceptEmployment(ctx.get("auth0Id"), roleId, patch, reason)
                : service.correctEmployment(ctx.get("auth0Id"), roleId, patch, reason))
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/moderation/employments/:roleId/reopen ────────────────

    public void handleReopenEmployment(RoutingContext ctx) {
        Long roleId = pathId(ctx, "roleId");
        if (roleId == null) { bad(ctx, "Invalid employment ID"); return; }
        service.reopenRejectedEmployment(ctx.get("auth0Id"), roleId,
                                         bodyOrEmpty(ctx).getString("reason"))
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/admin/moderation/employments/:roleId/history ────────────────

    public void handleEmploymentHistory(RoutingContext ctx) {
        Long roleId = pathId(ctx, "roleId");
        if (roleId == null) { bad(ctx, "Invalid employment ID"); return; }
        service.employmentHistory(ctx.get("auth0Id"), roleId,
                                  ctx.request().getParam("cursor"), limit(ctx))
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/admin/moderation/conflicts ──────────────────────────────────

    public void handleConflicts(RoutingContext ctx) {
        service.openConflicts(ctx.get("auth0Id"), limit(ctx), offset(ctx))
            .onSuccess(rows -> okList(ctx, rows))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/admin/moderation/conflicts/:conflictId ──────────────────────

    public void handleConflictDetail(RoutingContext ctx) {
        Long conflictId = pathId(ctx, "conflictId");
        if (conflictId == null) { bad(ctx, "Invalid conflict ID"); return; }
        service.conflictDetail(ctx.get("auth0Id"), conflictId)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/moderation/conflicts/:conflictId/resolve ─────────────

    public void handleResolveConflict(RoutingContext ctx) {
        Long conflictId = pathId(ctx, "conflictId");
        if (conflictId == null) { bad(ctx, "Invalid conflict ID"); return; }
        JsonObject body = bodyOrEmpty(ctx);
        String resolution = body.getString("resolution");
        if (resolution == null || resolution.isBlank()) {
            bad(ctx, "A resolution is required"); return;
        }
        service.resolveConflict(ctx.get("auth0Id"), conflictId, resolution,
                                body.getString("note"))
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/moderation/conflicts/:conflictId/determine-status ────

    /**
     * Answer an employment-status dispute.
     *
     * <p>Separate from handleResolveConflict deliberately. That endpoint records a decision ABOUT
     * a conflict; this one records a decision about the EMPLOYMENT, which needs the status and the
     * date its evidence speaks to, and which the generic resolution values cannot carry.
     */
    public void handleDetermineEmploymentStatus(RoutingContext ctx) {
        Long conflictId = pathId(ctx, "conflictId");
        if (conflictId == null) { bad(ctx, "Invalid conflict ID"); return; }
        JsonObject body = bodyOrEmpty(ctx);
        String status = body.getString("status");
        if (status == null || status.isBlank()) { bad(ctx, "A status is required"); return; }
        java.time.LocalDate asOf;
        try {
            String raw = body.getString("asOf");
            asOf = (raw == null || raw.isBlank()) ? null : java.time.LocalDate.parse(raw);
        } catch (java.time.format.DateTimeParseException invalid) {
            bad(ctx, "asOf must be a date, as YYYY-MM-DD"); return;
        }
        service.determineEmploymentStatus(ctx.get("auth0Id"), conflictId, status, asOf,
                                          body.getString("note"))
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/moderation/claims/:claimId/link ──────────────────────

    public void handleLinkClaim(RoutingContext ctx) {
        Long claimId = pathId(ctx, "claimId");
        if (claimId == null) { bad(ctx, "Invalid claim ID"); return; }
        JsonObject body = bodyOrEmpty(ctx);
        Long roleId = body.getLong("roleId");
        if (roleId == null) { bad(ctx, "A target employment is required"); return; }
        service.linkClaimToRole(ctx.get("auth0Id"), claimId, roleId)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/moderation/roles/merge ───────────────────────────────

    public void handleMergeRoles(RoutingContext ctx) {
        JsonObject body = bodyOrEmpty(ctx);
        Long managerId  = body.getLong("managerId");
        Long keep       = body.getLong("keepRoleId");
        Long superseded = body.getLong("supersededRoleId");
        if (managerId == null || keep == null || superseded == null) {
            bad(ctx, "managerId, keepRoleId and supersededRoleId are all required"); return;
        }
        service.mergeRoles(ctx.get("auth0Id"), managerId, keep, superseded)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/admin/moderation/held-ratings ───────────────────────────────

    public void handleHeldRatings(RoutingContext ctx) {
        // Optional. Present when the manager's own profile asks for its own held ratings, so the
        // inline section and the global queue are answered by one query and cannot disagree.
        String raw = ctx.queryParam("managerId").stream().findFirst().orElse(null);
        Long managerId;
        try {
            managerId = (raw == null || raw.isBlank()) ? null : Long.valueOf(raw);
        } catch (NumberFormatException notANumber) {
            bad(ctx, "Invalid manager ID"); return;
        }
        service.heldRatings(ctx.get("auth0Id"), limit(ctx), offset(ctx), managerId)
            .onSuccess(rows -> okList(ctx, rows))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/moderation/held-ratings/{reviewId}/publish ───────────

    public void handlePublishHeldRating(RoutingContext ctx) {
        java.util.UUID reviewId;
        try {
            reviewId = java.util.UUID.fromString(ctx.pathParam("reviewId"));
        } catch (IllegalArgumentException | NullPointerException notAUuid) {
            bad(ctx, "Invalid rating ID"); return;
        }
        service.publishHeldRating(ctx.get("auth0Id"), reviewId)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * PRESENCE, not just value.
     *
     * <p>{@code "endDate": null} means "no recorded end date", which is a real correction, while
     * omitting the key entirely means "leave it alone". A nullable field cannot carry both, so the
     * key's presence is read explicitly and passed on as a flag.
     *
     * <p>There is no "currently employed" input to read. The schema distinguishes an unknown
     * period from one with no end date, and it has no verified statement that anybody works
     * anywhere today.
     */
    private static ModerationService.Correction correctionFrom(JsonObject body) {
        boolean titleGiven = body.containsKey("title");
        boolean startGiven = body.containsKey("startDate");
        boolean endGiven   = body.containsKey("endDate");
        boolean knownGiven = body.containsKey("periodKnown");
        String title = titleGiven ? body.getString("title") : null;
        if (titleGiven && (title == null || title.isBlank())) {
            throw new IllegalArgumentException("A job title cannot be blank.");
        }
        return new ModerationService.Correction(
            titleGiven, title == null ? null : title.trim(),
            startGiven, date(body, "startDate"),
            endGiven,   date(body, "endDate"),
            knownGiven, knownGiven && Boolean.TRUE.equals(body.getBoolean("periodKnown")));
    }

    private static java.time.LocalDate date(JsonObject body, String key) {
        String raw = body.getString(key);
        if (raw == null || raw.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(raw.length() == 7 ? raw + "-01" : raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "\"" + raw + "\" is not a date. Use YYYY-MM-DD or YYYY-MM.");
        }
    }

    private static void ok(RoutingContext ctx, JsonObject body) {
        ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json")
            .end(body.encode());
    }

    /** Wrapped in {data: [...]}, the shape the rest of the admin surface returns lists in. */
    private static void okList(RoutingContext ctx, JsonArray rows) {
        ok(ctx, new JsonObject().put("data", rows));
    }

    private static void bad(RoutingContext ctx, String msg) {
        ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("message", msg).encode());
    }

    private static Long pathId(RoutingContext ctx, String name) {
        try { return Long.parseLong(ctx.pathParam(name)); }
        catch (Exception e) { return null; }
    }

    /**
     * An absent or unparseable body is an empty object, not a crash.
     *
     * <p>Reject takes an optional reason, so a POST with no body at all is legitimate there.
     */
    private static JsonObject bodyOrEmpty(RoutingContext ctx) {
        try {
            JsonObject b = ctx.body() == null ? null : ctx.body().asJsonObject();
            return b == null ? new JsonObject() : b;
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static int limit(RoutingContext ctx) {
        return parseIntParam(ctx.request().getParam("limit"), 50, 1, 200);
    }

    private static int offset(RoutingContext ctx) {
        return parseIntParam(ctx.request().getParam("offset"), 0, 0, Integer.MAX_VALUE);
    }

    private static int parseIntParam(String raw, int defaultVal, int min, int max) {
        if (raw == null) return defaultVal;
        try { return Math.min(max, Math.max(min, Integer.parseInt(raw))); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}

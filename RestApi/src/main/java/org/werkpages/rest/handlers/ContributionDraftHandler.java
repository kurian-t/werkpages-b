package org.werkpages.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.werkpages.service.ContributionDraftService;

/**
 * The contributor's own unfinished ratings.
 *
 * <h2>Every route is /users/me, and that is a security decision rather than a naming one</h2>
 *
 * <p>There is no {@code /drafts/:id} addressed by owner, so there is no object whose owner could
 * be checked wrongly. The identity comes from {@code auth0Id} on the routing context, which the
 * authentication handler put there, and is passed to the service which resolves it to a user and
 * filters every query on it. A draft token appears nowhere in a read: it is a write resolution key
 * and the service never accepts it as authorization.
 *
 * <p>CSRF is handled by controls that already exist and are not visible from here: the session
 * cookie is {@code SameSite=Lax} so a browser will not attach it to a cross-site PUT or DELETE,
 * the CORS handler refuses a disallowed {@code Origin} with 403 before any handler runs, and the
 * OpenAPI contract rejects every content type an HTML form can produce with 415. This endpoint
 * deliberately adds no exception of its own.
 */
public class ContributionDraftHandler {

    private final ContributionDraftService service;

    public ContributionDraftHandler(ContributionDraftService service) {
        this.service = service;
    }

    /** PUT /api/users/me/drafts */
    public void handleSaveDraft(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { unauthorized(ctx); return; }
        JsonObject body = ctx.body() == null ? null : ctx.body().asJsonObject();
        service.save(auth0Id, body)
            /*
              The status comes from the service, because 200 and 409 are both ordinary outcomes of
              a correct request: one accepted the write, the other found another device ahead and
              wrote nothing. Neither is an error, and turning the conflict into one would make the
              client treat a newer draft as a failure.
            */
            .onSuccess(written -> respond(ctx, written.status(), written.body()))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    /** GET /api/users/me/drafts */
    public void handleListDrafts(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { unauthorized(ctx); return; }
        service.list(auth0Id)
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    /** GET /api/users/me/drafts/{draftId} */
    public void handleGetDraft(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { unauthorized(ctx); return; }
        service.get(auth0Id, ctx.pathParam("draftId"))
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    /** DELETE /api/users/me/drafts/{draftId} */
    public void handleDiscardDraft(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) { unauthorized(ctx); return; }
        service.discard(auth0Id, ctx.pathParam("draftId"))
            .onSuccess(json -> respond(ctx, 200, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    private static void unauthorized(RoutingContext ctx) {
        respond(ctx, 401, new JsonObject().put("error", "Unauthorized"));
    }

    private static void respond(RoutingContext ctx, int status, JsonObject body) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(body.encode());
    }
}

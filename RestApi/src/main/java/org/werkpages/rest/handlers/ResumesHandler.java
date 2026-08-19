package org.werkpages.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.werkpages.service.ResumeService;

public class ResumesHandler {

    private final ResumeService service;

    public ResumesHandler(ResumeService service) {
        this.service = service;
    }

    // GET /api/resumes/mine
    public void handleGetResume(RoutingContext ctx) {
        String auth0Id = requireAuth0Id(ctx); if (auth0Id == null) return;
        service.getResume(auth0Id)
            .onSuccess(json -> {
                if (json == null) {
                    ctx.response().setStatusCode(204).end();
                } else {
                    ok(ctx, new JsonObject().put("data", json));
                }
            })
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // PUT /api/resumes/mine
    public void handleSaveResume(RoutingContext ctx) {
        String auth0Id = requireAuth0Id(ctx); if (auth0Id == null) return;
        JsonObject body;
        try {
            body = ctx.body().asJsonObject();
            if (body == null) throw new IllegalArgumentException("empty body");
        } catch (Exception e) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid JSON body").encode());
            return;
        }
        service.saveResume(auth0Id, body)
            .onSuccess(json -> ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("data", json).encode()))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // GET /api/resumes/mine/prefill
    public void handleGetPrefill(RoutingContext ctx) {
        String auth0Id = requireAuth0Id(ctx); if (auth0Id == null) return;
        service.getPrefill(auth0Id)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String requireAuth0Id(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) {
            ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Unauthorized").encode());
        }
        return auth0Id;
    }

    private static void ok(RoutingContext ctx, JsonObject body) {
        ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(body.encode());
    }
}

package org.werkpages.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.werkpages.service.NotificationService;

import java.util.UUID;

/**
 * Thin HTTP adapter for notification endpoints.
 * All business logic lives in {@link NotificationService}.
 */
public class NotificationsHandler {

    private final NotificationService service;

    public NotificationsHandler(NotificationService service) {
        this.service = service;
    }

    // GET /api/notifications
    public void handleGetNotifications(RoutingContext ctx) {
        String auth0Id = requireAuth0Id(ctx); if (auth0Id == null) return;
        service.getNotifications(auth0Id)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // GET /api/notifications/unread-count
    public void handleGetUnreadCount(RoutingContext ctx) {
        String auth0Id = requireAuth0Id(ctx); if (auth0Id == null) return;
        service.getUnreadCount(auth0Id)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // PUT /api/notifications/:id/read
    public void handleMarkAsRead(RoutingContext ctx) {
        String auth0Id = requireAuth0Id(ctx); if (auth0Id == null) return;
        UUID notifId;
        try {
            notifId = UUID.fromString(ctx.pathParam("id"));
        } catch (Exception e) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid notification ID").encode()); return;
        }
        service.markRead(auth0Id, notifId)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // PUT /api/notifications/read-all
    public void handleMarkAllAsRead(RoutingContext ctx) {
        String auth0Id = requireAuth0Id(ctx); if (auth0Id == null) return;
        service.markAllRead(auth0Id)
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

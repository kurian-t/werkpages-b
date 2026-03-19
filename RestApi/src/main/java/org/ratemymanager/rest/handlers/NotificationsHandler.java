package org.ratemymanager.rest.handlers;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;
import java.util.function.Consumer;

public class NotificationsHandler {

    private final SqlClient db;

    public NotificationsHandler(SqlClient db) {
        this.db = db;
    }

    private void requireAuth(RoutingContext ctx, Consumer<UUID> action) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) {
            ctx.response().setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Unauthorized").encode());
            return;
        }
        db.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id), ar -> {
                if (ar.failed()) { ctx.fail(ar.cause()); return; }
                if (!ar.result().iterator().hasNext()) {
                    ctx.response().setStatusCode(401)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "User not found").encode());
                    return;
                }
                action.accept(ar.result().iterator().next().getUUID("id"));
            });
    }

    // GET /api/notifications
    public void handleGetNotifications(RoutingContext ctx) {
        requireAuth(ctx, userId -> {
            db.preparedQuery(
                "SELECT id, type, title, message, read, created_at " +
                "FROM notifications WHERE user_id = $1 ORDER BY created_at DESC LIMIT 50")
                .execute(Tuple.of(userId), ar -> {
                    if (ar.failed()) { ctx.fail(ar.cause()); return; }
                    JsonArray result = new JsonArray();
                    for (Row row : ar.result()) {
                        result.add(new JsonObject()
                            .put("id", row.getUUID("id").toString())
                            .put("type", row.getString("type"))
                            .put("title", row.getString("title"))
                            .put("message", row.getString("message"))
                            .put("read", row.getBoolean("read"))
                            .put("createdAt", row.getOffsetDateTime("created_at").toString())
                        );
                    }
                    ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("data", result).encode());
                });
        });
    }

    // GET /api/notifications/unread-count
    public void handleGetUnreadCount(RoutingContext ctx) {
        requireAuth(ctx, userId -> {
            db.preparedQuery("SELECT COUNT(*) AS cnt FROM notifications WHERE user_id = $1 AND read = FALSE")
                .execute(Tuple.of(userId), ar -> {
                    if (ar.failed()) { ctx.fail(ar.cause()); return; }
                    long count = ar.result().iterator().next().getLong("cnt");
                    ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("unreadCount", count).encode());
                });
        });
    }

    // PUT /api/notifications/:id/read
    public void handleMarkAsRead(RoutingContext ctx) {
        requireAuth(ctx, userId -> {
            UUID notifId;
            try {
                notifId = UUID.fromString(ctx.pathParam("id"));
            } catch (Exception e) {
                ctx.response().setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "Invalid notification ID").encode());
                return;
            }
            db.preparedQuery("UPDATE notifications SET read = TRUE WHERE id = $1 AND user_id = $2")
                .execute(Tuple.of(notifId, userId), ar -> {
                    if (ar.failed()) { ctx.fail(ar.cause()); return; }
                    ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("success", true).encode());
                });
        });
    }

    // PUT /api/notifications/read-all
    public void handleMarkAllAsRead(RoutingContext ctx) {
        requireAuth(ctx, userId -> {
            db.preparedQuery("UPDATE notifications SET read = TRUE WHERE user_id = $1 AND read = FALSE")
                .execute(Tuple.of(userId), ar -> {
                    if (ar.failed()) { ctx.fail(ar.cause()); return; }
                    ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("success", true).encode());
                });
        });
    }
}

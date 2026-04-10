package org.ratemymanager.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.ratemymanager.repository.NotificationRepository;
import org.ratemymanager.repository.UserRepository;

import java.util.UUID;

/**
 * Business logic for notifications. Resolves the caller's UUID from their Auth0 ID
 * before delegating to the repository.
 */
public class NotificationService {

    private final UserRepository userRepo;
    private final NotificationRepository notifRepo;

    public NotificationService(UserRepository userRepo, NotificationRepository notifRepo) {
        this.userRepo  = userRepo;
        this.notifRepo = notifRepo;
    }

    public Future<JsonObject> getNotifications(String auth0Id) {
        return resolveUserId(auth0Id)
            .compose(userId -> notifRepo.findByUser(userId)
                .map(rows -> {
                    JsonArray result = new JsonArray();
                    for (Row row : rows) {
                        JsonObject obj = new JsonObject()
                            .put("id", row.getUUID("id").toString())
                            .put("type", row.getString("type"))
                            .put("title", row.getString("title"))
                            .put("message", row.getString("message"))
                            .put("read", row.getBoolean("read"))
                            .put("createdAt", row.getOffsetDateTime("created_at").toString());
                        Long managerId = row.getLong("manager_id");
                        if (managerId != null) obj.put("managerId", managerId);
                        result.add(obj);
                    }
                    return new JsonObject().put("data", result);
                })
            );
    }

    public Future<JsonObject> getUnreadCount(String auth0Id) {
        return resolveUserId(auth0Id)
            .compose(userId -> notifRepo.countUnread(userId)
                .map(count -> new JsonObject().put("unreadCount", count))
            );
    }

    public Future<JsonObject> markAllRead(String auth0Id) {
        return resolveUserId(auth0Id)
            .compose(userId -> notifRepo.markAllRead(userId)
                .map(v -> new JsonObject().put("success", true))
            );
    }

    public Future<JsonObject> markRead(String auth0Id, UUID notifId) {
        return resolveUserId(auth0Id)
            .compose(userId -> notifRepo.markRead(notifId, userId)
                .map(v -> new JsonObject().put("success", true))
            );
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private Future<UUID> resolveUserId(String auth0Id) {
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> opt.isPresent()
                ? Future.succeededFuture(opt.get())
                : Future.failedFuture(ServiceException.unauthorized("User not found"))
            );
    }
}

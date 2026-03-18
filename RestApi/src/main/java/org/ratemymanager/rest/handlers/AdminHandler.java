package org.ratemymanager.rest.handlers;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Consumer;

public class AdminHandler {

    private final SqlClient db;

    public AdminHandler(SqlClient db) {
        this.db = db;
    }

    // ── Helper: verify caller is admin, then run action with admin's UUID ────────
    private void requireAdmin(RoutingContext ctx, Consumer<UUID> action) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) {
            ctx.response().setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Unauthorized").encode());
            return;
        }
        db.preparedQuery("SELECT id, role FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id), ar -> {
                if (ar.failed()) { ctx.fail(ar.cause()); return; }
                if (!ar.result().iterator().hasNext()) {
                    ctx.response().setStatusCode(401)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "User not found").encode());
                    return;
                }
                Row row = ar.result().iterator().next();
                if (!"admin".equals(row.getString("role"))) {
                    ctx.response().setStatusCode(403)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "Forbidden").encode());
                    return;
                }
                action.accept(row.getUUID("id"));
            });
    }

    // ── GET /api/admin/pending-edits ─────────────────────────────────────────────
    public void handleGetPendingEdits(RoutingContext ctx) {
        requireAdmin(ctx, adminId -> {
            String sql = """
                SELECT
                    pe.id,
                    pe.manager_id,
                    m.name AS manager_name,
                    m.company AS current_company,
                    m.title AS current_title,
                    u.username AS requested_by,
                    pe.new_company,
                    pe.new_title,
                    pe.status,
                    pe.created_at
                FROM manager_edits pe
                JOIN managers m ON m.id = pe.manager_id
                JOIN users u ON u.id = pe.proposed_by
                WHERE pe.status = 'pending'
                ORDER BY pe.created_at ASC
                """;
            db.preparedQuery(sql).execute(ar -> {
                if (ar.failed()) { ctx.fail(ar.cause()); return; }
                JsonArray result = new JsonArray();
                for (Row row : ar.result()) {
                    result.add(new JsonObject()
                        .put("id", row.getUUID("id").toString())
                        .put("managerId", row.getLong("manager_id"))
                        .put("managerName", row.getString("manager_name"))
                        .put("currentCompany", row.getString("current_company"))
                        .put("currentTitle", row.getString("current_title"))
                        .put("requestedBy", row.getString("requested_by"))
                        .put("newCompany", row.getString("new_company"))
                        .put("newTitle", row.getString("new_title"))
                        .put("status", row.getString("status"))
                        .put("createdAt", row.getOffsetDateTime("created_at").toString())
                    );
                }
                ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("data", result).encode());
            });
        });
    }

    // ── POST /api/admin/pending-edits/:editId/approve ─────────────────────────────
    public void handleApproveEdit(RoutingContext ctx) {
        requireAdmin(ctx, adminId -> {
            UUID editId;
            try {
                editId = UUID.fromString(ctx.pathParam("editId"));
            } catch (Exception e) {
                ctx.response().setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "Invalid edit ID").encode());
                return;
            }

            // 1. Get the pending edit + current manager state
            String fetchSql = """
                SELECT
                    pe.id, pe.manager_id, pe.new_company, pe.new_title, pe.status,
                    m.company AS current_company, m.title AS current_title, m.created_at AS manager_created_at
                FROM manager_edits pe
                JOIN managers m ON m.id = pe.manager_id
                WHERE pe.id = $1
                """;
            db.preparedQuery(fetchSql).execute(Tuple.of(editId), ar -> {
                if (ar.failed()) { ctx.fail(ar.cause()); return; }
                if (!ar.result().iterator().hasNext()) {
                    ctx.response().setStatusCode(404)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "Edit request not found").encode());
                    return;
                }
                Row row = ar.result().iterator().next();
                if (!"pending".equals(row.getString("status"))) {
                    ctx.response().setStatusCode(409)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "Edit request is not pending").encode());
                    return;
                }

                long managerId = row.getLong("manager_id");
                String currentCompany = row.getString("current_company");
                String currentTitle   = row.getString("current_title");
                String newCompany = row.getString("new_company");
                String newTitle   = row.getString("new_title");
                String effectiveCo  = newCompany != null ? newCompany : currentCompany;
                String effectiveTit = newTitle   != null ? newTitle   : currentTitle;
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

                // 2. Close the existing open career_history entry
                db.preparedQuery("UPDATE career_history SET end_date = $1 WHERE manager_id = $2 AND end_date IS NULL")
                    .execute(Tuple.of(now, managerId), closeAr -> {
                        if (closeAr.failed()) { ctx.fail(closeAr.cause()); return; }
                        int closed = closeAr.result().rowCount();

                        Runnable insertNewAndFinish = () ->
                            db.preparedQuery("INSERT INTO career_history(manager_id, company, title, start_date, end_date) VALUES ($1, $2, $3, $4, NULL)")
                                .execute(Tuple.of(managerId, effectiveCo, effectiveTit, now), insertAr -> {
                                    if (insertAr.failed()) { ctx.fail(insertAr.cause()); return; }
                                    applyEditAndApprove(ctx, managerId, editId, newCompany, newTitle, effectiveCo, effectiveTit, adminId, now);
                                });

                        if (closed == 0) {
                            // No open entry — archive old position first
                            db.preparedQuery("INSERT INTO career_history(manager_id, company, title, start_date, end_date) VALUES ($1, $2, $3, $4, $5)")
                                .execute(Tuple.of(managerId, currentCompany, currentTitle, row.getOffsetDateTime("manager_created_at"), now),
                                    archiveAr -> {
                                        if (archiveAr.failed()) { ctx.fail(archiveAr.cause()); return; }
                                        insertNewAndFinish.run();
                                    });
                        } else {
                            insertNewAndFinish.run();
                        }
                    });
            });
        });
    }

    private void applyEditAndApprove(RoutingContext ctx, long managerId, UUID editId,
                                     String newCompany, String newTitle,
                                     String effectiveCo, String effectiveTit,
                                     UUID adminId, OffsetDateTime reviewedAt) {
        // 3. Update manager record
        String updateSql;
        Tuple updateParams;
        if (newCompany != null && newTitle != null) {
            updateSql = "UPDATE managers SET company = $1, title = $2, updated_at = now() WHERE id = $3";
            updateParams = Tuple.of(effectiveCo, effectiveTit, managerId);
        } else if (newCompany != null) {
            updateSql = "UPDATE managers SET company = $1, updated_at = now() WHERE id = $2";
            updateParams = Tuple.of(effectiveCo, managerId);
        } else {
            updateSql = "UPDATE managers SET title = $1, updated_at = now() WHERE id = $2";
            updateParams = Tuple.of(effectiveTit, managerId);
        }
        db.preparedQuery(updateSql).execute(updateParams, updateAr -> {
            if (updateAr.failed()) { ctx.fail(updateAr.cause()); return; }
            // 4. Mark edit as approved with audit trail
            db.preparedQuery("UPDATE manager_edits SET status = 'approved', reviewed_at = $1, reviewed_by = $2 WHERE id = $3")
                .execute(Tuple.of(reviewedAt, adminId, editId), approveAr -> {
                    if (approveAr.failed()) { ctx.fail(approveAr.cause()); return; }
                    ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("success", true).put("message", "Edit approved and applied").encode());
                });
        });
    }

    // ── POST /api/admin/pending-edits/:editId/reject ──────────────────────────────
    public void handleRejectEdit(RoutingContext ctx) {
        requireAdmin(ctx, adminId -> {
            UUID editId;
            try {
                editId = UUID.fromString(ctx.pathParam("editId"));
            } catch (Exception e) {
                ctx.response().setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "Invalid edit ID").encode());
                return;
            }
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            db.preparedQuery("UPDATE manager_edits SET status = 'rejected', reviewed_at = $1, reviewed_by = $2 WHERE id = $3 AND status = 'pending' RETURNING id")
                .execute(Tuple.of(now, adminId, editId), ar -> {
                    if (ar.failed()) { ctx.fail(ar.cause()); return; }
                    if (!ar.result().iterator().hasNext()) {
                        ctx.response().setStatusCode(404)
                            .putHeader("Content-Type", "application/json")
                            .end(new JsonObject().put("error", "Pending edit not found").encode());
                        return;
                    }
                    ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("success", true).encode());
                });
        });
    }

    // ── GET /api/admin/users ──────────────────────────────────────────────────────
    public void handleGetUsers(RoutingContext ctx) {
        requireAdmin(ctx, adminId -> {
            String sql = """
                SELECT u.id, u.username, u.first_name, u.last_name, u.role,
                       (SELECT b.id FROM banned_users b WHERE b.user_id = u.id LIMIT 1) AS ban_id
                FROM users u
                WHERE u.role != 'admin'
                ORDER BY u.username ASC
                """;
            db.preparedQuery(sql).execute(ar -> {
                if (ar.failed()) { ctx.fail(ar.cause()); return; }
                JsonArray result = new JsonArray();
                for (Row row : ar.result()) {
                    result.add(new JsonObject()
                        .put("id", row.getUUID("id").toString())
                        .put("username", row.getString("username"))
                        .put("firstName", row.getString("first_name"))
                        .put("lastName", row.getString("last_name"))
                        .put("isBanned", row.getUUID("ban_id") != null)
                    );
                }
                ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("data", result).encode());
            });
        });
    }

    // ── GET /api/admin/banned-users ───────────────────────────────────────────────
    public void handleGetBannedUsers(RoutingContext ctx) {
        requireAdmin(ctx, adminId -> {
            String sql = """
                SELECT b.id, b.user_id, u.username, b.reason, b.banned_by, b.banned_at
                FROM banned_users b
                JOIN users u ON u.id = b.user_id
                ORDER BY b.banned_at DESC
                """;
            db.preparedQuery(sql).execute(ar -> {
                if (ar.failed()) { ctx.fail(ar.cause()); return; }
                JsonArray result = new JsonArray();
                for (Row row : ar.result()) {
                    result.add(new JsonObject()
                        .put("id", row.getUUID("id").toString())
                        .put("userId", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("reason", row.getString("reason"))
                        .put("bannedBy", row.getString("banned_by"))
                        .put("bannedAt", row.getOffsetDateTime("banned_at").toString())
                    );
                }
                ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("data", result).encode());
            });
        });
    }

    // ── POST /api/admin/users/:userId/ban ─────────────────────────────────────────
    public void handleBanUser(RoutingContext ctx) {
        requireAdmin(ctx, adminId -> {
            String auth0Id = ctx.get("auth0Id");
            UUID targetUserId;
            try {
                targetUserId = UUID.fromString(ctx.pathParam("userId"));
            } catch (Exception e) {
                ctx.response().setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "Invalid user ID").encode());
                return;
            }

            JsonObject body = ctx.getBodyAsJson();
            String reason = body != null ? body.getString("reason") : null;
            if (reason == null || reason.isBlank()) {
                ctx.response().setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "Ban reason is required").encode());
                return;
            }
            if (reason.length() > 500) {
                ctx.response().setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "Reason must be at most 500 characters").encode());
                return;
            }

            // Get the admin's username for the audit trail
            db.preparedQuery("SELECT username FROM users WHERE auth0_id = $1")
                .execute(Tuple.of(auth0Id), adminAr -> {
                    if (adminAr.failed()) { ctx.fail(adminAr.cause()); return; }
                    String adminUsername = adminAr.result().iterator().hasNext()
                        ? adminAr.result().iterator().next().getString("username") : "admin";

                    db.preparedQuery("INSERT INTO banned_users(user_id, reason, banned_by) VALUES ($1, $2, $3) ON CONFLICT (user_id) DO NOTHING RETURNING id")
                        .execute(Tuple.of(targetUserId, reason.trim(), adminUsername), banAr -> {
                            if (banAr.failed()) { ctx.fail(banAr.cause()); return; }
                            if (!banAr.result().iterator().hasNext()) {
                                ctx.response().setStatusCode(409)
                                    .putHeader("Content-Type", "application/json")
                                    .end(new JsonObject().put("error", "User is already banned").encode());
                                return;
                            }
                            ctx.response().setStatusCode(201)
                                .putHeader("Content-Type", "application/json")
                                .end(new JsonObject().put("success", true).encode());
                        });
                });
        });
    }

    // ── DELETE /api/admin/users/:userId/ban ───────────────────────────────────────
    public void handleUnbanUser(RoutingContext ctx) {
        requireAdmin(ctx, adminId -> {
            UUID targetUserId;
            try {
                targetUserId = UUID.fromString(ctx.pathParam("userId"));
            } catch (Exception e) {
                ctx.response().setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "Invalid user ID").encode());
                return;
            }
            db.preparedQuery("DELETE FROM banned_users WHERE user_id = $1 RETURNING id")
                .execute(Tuple.of(targetUserId), ar -> {
                    if (ar.failed()) { ctx.fail(ar.cause()); return; }
                    if (!ar.result().iterator().hasNext()) {
                        ctx.response().setStatusCode(404)
                            .putHeader("Content-Type", "application/json")
                            .end(new JsonObject().put("error", "Ban not found").encode());
                        return;
                    }
                    ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("success", true).encode());
                });
        });
    }
}

package org.ratemymanager.rest.handlers;

import java.util.List;
import java.util.UUID;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

public class ReportsHandler {

    private final SqlClient db;

    public ReportsHandler(SqlClient db) {
        this.db = db;
    }
    
    
	public void handleReportManager(RoutingContext ctx) {
	    String auth0Id = ctx.get("auth0Id");
	    if (auth0Id == null) {
	        ctx.response()
	            .setStatusCode(401)
	            .putHeader("Content-Type", "application/json")
	            .end(new JsonObject().put("error", "Unauthorized").encode());
	        return;
	    }

	    long managerId;
	    try {
	        managerId = Long.parseLong(ctx.pathParam("id"));
	    } catch (NumberFormatException e) {
	        ctx.response()
	            .setStatusCode(400)
	            .putHeader("Content-Type", "application/json")
	            .end(new JsonObject().put("error", "Invalid manager ID").encode());
	        return;
	    }

	    JsonObject body = ctx.getBodyAsJson();
	    if (body == null) {
	        ctx.response()
	            .setStatusCode(400)
	            .putHeader("Content-Type", "application/json")
	            .end(new JsonObject().put("error", "Missing request body").encode());
	        return;
	    }

	    String reason  = body.getString("reason");
	    String comment = body.getString("comment");

	    if (reason == null || reason.isBlank()) {
	        ctx.response()
	            .setStatusCode(400)
	            .putHeader("Content-Type", "application/json")
	            .end(new JsonObject().put("error", "reason is required").encode());
	        return;
	    }

	    List<String> validReasons = List.of(
	        "incorrect_person",
	        "never_worked_here",
	        "duplicate_profile",
	        "incorrect_information",
	        "other"
	    );

	    if (!validReasons.contains(reason)) {
	        ctx.response()
	            .setStatusCode(400)
	            .putHeader("Content-Type", "application/json")
	            .end(new JsonObject().put("error", "Invalid reason").encode());
	        return;
	    }
	    if (comment != null && comment.length() > 500) {
	        ctx.response()
	            .setStatusCode(400)
	            .putHeader("Content-Type", "application/json")
	            .end(new JsonObject().put("error", "Comment must be at most 500 characters").encode());
	        return;
	    }

	    // Confirm manager exists
	    db.preparedQuery("SELECT id FROM managers WHERE id = $1")
	      .execute(Tuple.of(managerId), managerAr -> {
	        if (managerAr.failed()) {
	            ctx.fail(managerAr.cause());
	            return;
	        }
	        if (!managerAr.result().iterator().hasNext()) {
	            ctx.response()
	                .setStatusCode(404)
	                .putHeader("Content-Type", "application/json")
	                .end(new JsonObject().put("error", "Manager not found").encode());
	            return;
	        }

	        // Resolve internal user UUID from auth0_id
	        db.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
	          .execute(Tuple.of(auth0Id), userAr -> {
	            if (userAr.failed()) { ctx.fail(userAr.cause()); return; }

	            UUID userId = null;
	            if (userAr.succeeded() && userAr.result().iterator().hasNext()) {
	                userId = userAr.result().iterator().next().getUUID("id");
	            }
	            final UUID finalUserId = userId;

	            // If authenticated user, prevent duplicate reports
	            if (finalUserId != null) {
	                db.preparedQuery("SELECT id FROM reports WHERE manager_id = $1 AND user_id = $2")
	                  .execute(Tuple.of(managerId, finalUserId), dupAr -> {
	                    if (dupAr.failed()) { ctx.fail(dupAr.cause()); return; }
	                    if (dupAr.result().iterator().hasNext()) {
	                        ctx.response()
	                            .setStatusCode(409)
	                            .putHeader("Content-Type", "application/json")
	                            .end(new JsonObject().put("error", "already_reported").encode());
	                        return;
	                    }
	                    insertReport(ctx, managerId, finalUserId, reason, comment);
	                  });
	            } else {
	                insertReport(ctx, managerId, finalUserId, reason, comment);
	            }
	        });
	    });
	}

	private void insertReport(RoutingContext ctx, long managerId, UUID userId, String reason, String comment) {
	    String insertSql = """
	        INSERT INTO reports (manager_id, user_id, reason, comment)
	        VALUES ($1, $2, $3, $4)
	        RETURNING id, created_at
	        """;
	    db.preparedQuery(insertSql).execute(Tuple.of(managerId, userId, reason, comment), insertAr -> {
	        if (insertAr.failed()) {
	            ctx.fail(insertAr.cause());
	            return;
	        }
	        Row row = insertAr.result().iterator().next();
	        ctx.response()
	            .setStatusCode(201)
	            .putHeader("Content-Type", "application/json")
	            .end(new JsonObject()
	                .put("success", true)
	                .put("reportId", row.getUUID("id").toString())
	                .put("createdAt", row.getOffsetDateTime("created_at").toString())
	                .encode());
	    });
	}
}

package org.ratemymanager.rest.handlers;

import java.util.List;
import java.util.UUID;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;

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
	    String authHeader = ctx.request().getHeader("Authorization");
	    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
	        ctx.response()
	            .setStatusCode(401)
	            .putHeader("Content-Type", "application/json")
	            .end(new JsonObject().put("error", "Missing or invalid Authorization header").encode());
	        return;
	    }

	    String token = authHeader.substring("Bearer ".length());
	    DecodedJWT decoded;
	    try {
	        decoded = JWT.decode(token);
	    } catch (JWTDecodeException e) {
	        ctx.response()
	            .setStatusCode(401)
	            .putHeader("Content-Type", "application/json")
	            .end(new JsonObject().put("error", "Invalid token").encode());
	        return;
	    }

	    String auth0Id = decoded.getClaim("sub").asString();

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
	            UUID userId = null;
	            if (userAr.succeeded() && userAr.result().iterator().hasNext()) {
	                userId = userAr.result().iterator().next().getUUID("id");
	            }

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
	        });
	    });
	}
}

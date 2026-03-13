package org.ratemymanager.rest.handlers;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

public class ManagersHandler {
	private static final String GET_MANAGERS_SQL = """
			SELECT
			    m.id,
			    m.name,
			    m.company,
			    m.title,
			    m.image,
			    m.overall_rating,
			    m.reviews_count,
			    m.bio,
			    m.status,
			    m.category_averages,
			    m.linkedin_url,
			    m.created_at,

			    COALESCE(
			        json_agg(
			            json_build_object(
			                'company', ch.company,
			                'title', ch.title,
			                'startDate', ch.start_date,
			                'endDate', ch.end_date
			            )
			            ORDER BY ch.start_date DESC
			        ) FILTER (WHERE ch.id IS NOT NULL),
			        '[]'
			    ) AS career_history
			FROM managers m
			LEFT JOIN career_history ch ON ch.manager_id = m.id
			GROUP BY m.id
			ORDER BY m.overall_rating DESC
			LIMIT $1 OFFSET $2
			""";
	
	private static final String GET_MANAGER_BY_ID_SQL = """
			SELECT
			    m.id,
			    m.name,
			    m.company,
			    m.title,
			    m.image,
			    m.overall_rating,
			    m.reviews_count,
			    m.bio,
			    m.status,
			    m.category_averages,
			    m.linkedin_url,
			    m.created_at,
			
			    -- Career history (pre-aggregated)
			    COALESCE(ch.career_history, '[]') AS career_history,
			
			    -- Reviews (pre-aggregated and ordered)
			    COALESCE(r.reviews, '[]') AS reviews
			
			FROM managers m
			
			LEFT JOIN (
			    SELECT
			        manager_id,
			        json_agg(
			            jsonb_build_object(
			                'company', company,
			                'title', title,
			                'startDate', start_date,
			                'endDate', end_date
			            )
			            ORDER BY start_date DESC
			        ) AS career_history
			    FROM career_history
			    GROUP BY manager_id
			) ch ON ch.manager_id = m.id
			
			LEFT JOIN (
			    SELECT
			        manager_id,
			        json_agg(
			            jsonb_build_object(
			                'id', id,
			                'author', author,
			                'overallRating', overall_rating,
			                'ratings', jsonb_build_object(
			                    'communication_style', communication_style,
			                    'perceived_approachability', perceived_approachability,
			                    'perceived_clarity_of_expectations', perceived_clarity_of_expectations,
			                    'feedback_style', feedback_style,
			                    'perceived_supportiveness', perceived_supportiveness,
			                    'decision_making_style', decision_making_style,
			                    'organization_and_planning_style', organization_and_planning_style,
			                    'delegation_style', delegation_style,
			                    'perceived_professional_demeanor', perceived_professional_demeanor,
			                    'overall_working_experience', overall_working_experience
			                ),
			                'managerCompany', manager_company,
			                'managerTitle', manager_title,
			                'text', text,
			                'verified', verified,
			                'helpfulCount', helpful_count,
			                'createdAt', created_at
			            )
			            ORDER BY created_at DESC
			        ) AS reviews
			    FROM reviews
			    GROUP BY manager_id
			) r ON r.manager_id = m.id
			
			WHERE m.id = $1
			LIMIT 1;

				""";
	
    private final SqlClient db;

    public ManagersHandler(SqlClient db) {
        this.db = db;
    }
    
    public void handleGetManagers(RoutingContext ctx) {
        // Check Authorization header
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
            decoded = JWT.decode(token); // Auth0 JWT decode
        } catch (JWTDecodeException e) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid token").encode());
            return;
        }

        // Optional: You can also verify the token with Auth0 if you want more security
        // e.g., using jwks-rsa to check signature & expiration

        // Get query params
        int limit = Integer.parseInt(ctx.queryParam("limit").stream().findFirst().orElse("20"));
        int offset = Integer.parseInt(ctx.queryParam("offset").stream().findFirst().orElse("0"));
        Tuple params = Tuple.of(limit, offset);

        // Count future
        Future<Long> totalFuture = Future.future(promise ->
            db.query("SELECT COUNT(*) FROM managers").execute(ar -> {
                if (ar.succeeded()) {
                    long total = ar.result().iterator().next().getLong(0);
                    promise.complete(total);
                } else {
                    promise.fail(ar.cause());
                }
            })
        );

        // Data future
        Future<RowSet<Row>> dataFuture = Future.future(promise ->
            db.preparedQuery(GET_MANAGERS_SQL).execute(params, ar -> {
                if (ar.succeeded()) {
                    promise.complete(ar.result());
                } else {
                    promise.fail(ar.cause());
                }
            })
        );

        // Combine results
        Future.all((Future<?>) totalFuture, (Future<?>) dataFuture).onComplete(ar -> {
            if (ar.failed()) {
                ctx.fail(ar.cause());
                return;
            }

            long total = totalFuture.result();
            RowSet<Row> rows = dataFuture.result();
            JsonArray data = new JsonArray();

            for (Row row : rows) {
                String createdAt = row.getOffsetDateTime("created_at").toString();

                data.add(new JsonObject()
                        .put("id", row.getLong("id"))
                        .put("name", row.getString("name"))
                        .put("company", row.getString("company"))
                        .put("title", row.getString("title"))
                        .put("image", row.getString("image"))
                        .put("overallRating", row.getBigDecimal("overall_rating"))
                        .put("reviews", row.getInteger("reviews_count"))
                        .put("bio", row.getString("bio"))
                        .put("status", row.getString("status"))
                        .put("categoryAverages", row.getJsonObject("category_averages"))
                        .put("linkedinUrl", row.getString("linkedin_url"))
                        .put("createdAt", createdAt)
                        .put("careerHistory", row.getJsonArray("career_history"))
                        // You may also fetch reviews from the reviews table here if needed
                );
            }

            JsonObject response = new JsonObject()
                    .put("data", data)
                    .put("limit", limit)
                    .put("offset", offset)
                    .put("total", total);

            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(response.encode());
        });
    }

    
    public void handleGetManagerById(RoutingContext ctx) {
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
            decoded = JWT.decode(token); // Auth0 JWT decode
        } catch (JWTDecodeException e) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid token").encode());
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

        Tuple params = Tuple.of(managerId);

        db.preparedQuery(GET_MANAGER_BY_ID_SQL).execute(params, ar -> {
            if (ar.failed()) {
                ctx.fail(ar.cause());
                return;
            }

            RowSet<Row> rows = ar.result();
            Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;

            if (row == null) {
                ctx.response()
                   .setStatusCode(404)
                   .putHeader("Content-Type", "application/json")
                   .end(new JsonObject().put("error", "Manager not found").encode());
                return;
            }

            // Convert OffsetDateTime to ISO string
            String createdAt = row.getOffsetDateTime("created_at").toString();

            JsonObject response = new JsonObject()
                .put("id", row.getLong("id"))
                .put("name", row.getString("name"))
                .put("company", row.getString("company"))
                .put("title", row.getString("title"))
                .put("image", row.getString("image"))
                .put("overallRating", row.getBigDecimal("overall_rating"))
                .put("reviews", row.getInteger("reviews_count"))
                .put("bio", row.getString("bio"))
                .put("status", row.getString("status"))
                .put("categoryAverages", row.getJsonObject("category_averages"))
                .put("linkedinUrl", row.getString("linkedin_url"))
                .put("createdAt", createdAt)
                .put("careerHistory", row.getJsonArray("career_history"))
                .put("reviews", row.getJsonArray("reviews"));

            ctx.response()
               .putHeader("Content-Type", "application/json")
               .end(response.encode());
        });
    }


    public void handleCreateManager(RoutingContext ctx) {
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
            decoded = JWT.decode(token); // Auth0 JWT decode
        } catch (JWTDecodeException e) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid token").encode());
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

        // Required fields
        String name = body.getString("name");
        String company = body.getString("company");
        String title = body.getString("title");
        String image = body.getString("image");
        String status = body.getString("status");

        if (name == null || company == null || title == null || image == null || status == null) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Missing required fields").encode());
            return;
        }

        // Optional fields
        String bio = body.getString("bio");
        String linkedinUrl = body.getString("linkedinUrl");

        String insertSql = """
            INSERT INTO managers
            (name, company, title, image, bio, status, linkedin_url, overall_rating, reviews_count, category_averages, created_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, 0, 0, '{}'::jsonb, now())
            RETURNING *
            """;

        Tuple params = Tuple.of(name, company, title, image, bio, status, linkedinUrl);

        db.preparedQuery(insertSql).execute(params, ar -> {
            if (ar.failed()) {
                ctx.fail(ar.cause());
                return;
            }

            Row row = ar.result().iterator().next();

            JsonObject response = new JsonObject()
                .put("id", row.getLong("id"))
                .put("name", row.getString("name"))
                .put("company", row.getString("company"))
                .put("title", row.getString("title"))
                .put("image", row.getString("image"))
                .put("overallRating", row.getBigDecimal("overall_rating"))
                .put("reviews", row.getInteger("reviews_count"))
                .put("bio", row.getString("bio"))
                .put("status", row.getString("status"))
                .put("categoryAverages", row.getJsonObject("category_averages"))
                .put("linkedinUrl", row.getString("linkedin_url"))
                // <-- FIXED: convert OffsetDateTime to string
                .put("createdAt", row.getOffsetDateTime("created_at").toString())
                .put("careerHistory", new JsonArray()); // new manager has empty career history

            ctx.response()
               .setStatusCode(201)
               .putHeader("Content-Type", "application/json")
               .end(response.encode());
        });
    }

    public void handleUpdateManager(RoutingContext ctx) {
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
            decoded = JWT.decode(token); // Auth0 JWT decode
        } catch (JWTDecodeException e) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid token").encode());
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
        if (body == null || (body.getString("company") == null && body.getString("title") == null)) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Nothing to update").encode());
            return;
        }

        String newCompany = body.getString("company");
        String newTitle = body.getString("title");

        Tuple selectParams = Tuple.of(managerId);
        db.preparedQuery(GET_MANAGER_BY_ID_SQL).execute(selectParams, ar -> {
            if (ar.failed()) {
                ctx.fail(ar.cause());
                return;
            }

            RowSet<Row> rows = ar.result();
            Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;

            if (row == null) {
                ctx.response()
                   .setStatusCode(404)
                   .putHeader("Content-Type", "application/json")
                   .end(new JsonObject().put("error", "Manager not found").encode());
                return;
            }

            String currentCompany = row.getString("company");
            String currentTitle = row.getString("title");

            // Inline lambda for updating manager
            Runnable updateManager = () -> {
                StringBuilder sql = new StringBuilder("UPDATE managers SET ");
                List<Object> paramsList = new ArrayList<>();
                int idx = 1;

                if (newCompany != null) {
                    sql.append("company = $").append(idx++).append(", ");
                    paramsList.add(newCompany);
                }
                if (newTitle != null) {
                    sql.append("title = $").append(idx++).append(", ");
                    paramsList.add(newTitle);
                }

                sql.delete(sql.length() - 2, sql.length()); // remove last comma
                sql.append(" WHERE id = $").append(idx).append(" RETURNING *");
                paramsList.add(managerId);

                db.preparedQuery(sql.toString()).execute(Tuple.from(paramsList), updateAr -> {
                    if (updateAr.failed()) {
                        ctx.fail(updateAr.cause());
                        return;
                    }

                    Row updatedRow = updateAr.result().iterator().next();
                    JsonObject response = new JsonObject()
                        .put("id", updatedRow.getLong("id"))
                        .put("name", updatedRow.getString("name"))
                        .put("company", updatedRow.getString("company"))
                        .put("title", updatedRow.getString("title"))
                        .put("image", updatedRow.getString("image"))
                        .put("overallRating", updatedRow.getBigDecimal("overall_rating"))
                        .put("reviews", updatedRow.getInteger("reviews_count"))
                        .put("bio", updatedRow.getString("bio"))
                        .put("status", updatedRow.getString("status"))
                        .put("categoryAverages", updatedRow.getJsonObject("category_averages"))
                        .put("linkedinUrl", updatedRow.getString("linkedin_url"))
                        .put("createdAt", updatedRow.getOffsetDateTime("created_at").toString());

                    // Fetch career history
                    db.preparedQuery(
                        "SELECT company, title, start_date, end_date FROM career_history WHERE manager_id = $1 ORDER BY start_date DESC"
                    ).execute(Tuple.of(managerId), chAr -> {
                        JsonArray careerHistory = new JsonArray();
                        if (chAr.succeeded()) {
                            for (Row r : chAr.result()) {
                                careerHistory.add(new JsonObject()
                                    .put("company", r.getString("company"))
                                    .put("title", r.getString("title"))
                                    .put("startDate", r.getOffsetDateTime("start_date").toString())
                                    .put("endDate", r.getOffsetDateTime("end_date") != null ? r.getOffsetDateTime("end_date").toString() : null)
                                );
                            }
                        }
                        response.put("careerHistory", careerHistory);
                        ctx.response()
                           .setStatusCode(200)
                           .putHeader("Content-Type", "application/json")
                           .end(response.encode());
                    });
                });
            };

            // If either company or title changed, insert previous position into career_history first
            if ((newCompany != null && !newCompany.equals(currentCompany)) ||
                (newTitle != null && !newTitle.equals(currentTitle))) {

                String insertCareerSql = """
                    INSERT INTO career_history(manager_id, company, title, start_date, end_date)
                    VALUES ($1, $2, $3, $4, $5)
                    """;

                OffsetDateTime now = OffsetDateTime.now();
                Tuple careerParams = Tuple.of(
                        managerId,
                        currentCompany,
                        currentTitle,
                        row.getOffsetDateTime("created_at"),
                        now
                );

                db.preparedQuery(insertCareerSql).execute(careerParams, careerAr -> {
                    if (careerAr.failed()) {
                        ctx.fail(careerAr.cause());
                        return;
                    }
                    updateManager.run();
                });
            } else {
                updateManager.run();
            }
        });
    }

    public void handleCreateManagerReview(RoutingContext ctx) {
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
            decoded = JWT.decode(token); // Auth0 JWT decode
        } catch (JWTDecodeException e) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid token").encode());
            return;
        }
        
        // Parse manager ID from path
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

        // Parse body
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Missing request body").encode());
            return;
        }

        // Required fields
        String author = body.getString("author");
        Double overallRating = body.getDouble("overallRating");
        JsonObject ratings = body.getJsonObject("ratings");
        String managerCompany = body.getString("managerCompany");
        String managerTitle = body.getString("managerTitle");
        String text = body.getString("text"); // optional

        if (author == null || overallRating == null || ratings == null || managerCompany == null || managerTitle == null) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Missing required fields").encode());
            return;
        }

        // Map ratings fields individually
        Tuple params = Tuple.of(
            managerId,
            UUID.fromString("e7b8c3a4-1234-5678-9abc-def012345678"), // user_id placeholder; replace with actual authenticated user ID
            author,
            overallRating,
            ratings.getDouble("communication_style"),
            ratings.getDouble("perceived_approachability"),
            ratings.getDouble("perceived_clarity_of_expectations"),
            ratings.getDouble("feedback_style"),
            ratings.getDouble("perceived_supportiveness"),
            ratings.getDouble("decision_making_style"),
            ratings.getDouble("organization_and_planning_style"),
            ratings.getDouble("delegation_style"),
            ratings.getDouble("perceived_professional_demeanor"),
            ratings.getDouble("overall_working_experience"),
            managerCompany,
            managerTitle,
            text
        );

        String insertSql = """
            INSERT INTO reviews (
                manager_id, user_id, author, overall_rating,
                communication_style, perceived_approachability, perceived_clarity_of_expectations,
                feedback_style, perceived_supportiveness, decision_making_style,
                organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                overall_working_experience, manager_company, manager_title, text,
                verified, helpful_count, created_at, updated_at
            )
            VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,true,0,now(),now())
            RETURNING *
            """;

        db.preparedQuery(insertSql).execute(params, ar -> {
            if (ar.failed()) {
                ctx.fail(ar.cause());
                return;
            }

            Row row = ar.result().iterator().next();

            // Build ratings object for response
            JsonObject responseRatings = new JsonObject()
                .put("Communication Style", row.getBigDecimal("communication_style"))
                .put("Perceived Approachability", row.getBigDecimal("perceived_approachability"))
                .put("Perceived Clarity of Expectations", row.getBigDecimal("perceived_clarity_of_expectations"))
                .put("Feedback Style", row.getBigDecimal("feedback_style"))
                .put("Perceived Supportiveness", row.getBigDecimal("perceived_supportiveness"))
                .put("Decision Making Style", row.getBigDecimal("decision_making_style"))
                .put("Organization and Planning Style", row.getBigDecimal("organization_and_planning_style"))
                .put("Delegation Style", row.getBigDecimal("delegation_style"))
                .put("Perceived Professional Demeanor", row.getBigDecimal("perceived_professional_demeanor"))
                .put("Overall Working Experience", row.getBigDecimal("overall_working_experience"));

            JsonObject response = new JsonObject()
                .put("id", row.getLong("id"))
                .put("managerId", row.getLong("manager_id"))
                .put("author", row.getString("author"))
                .put("overallRating", row.getBigDecimal("overall_rating"))
                .put("ratings", responseRatings)
                .put("managerCompany", row.getString("manager_company"))
                .put("managerTitle", row.getString("manager_title"))
                .put("text", row.getString("text"))
                .put("verified", row.getBoolean("verified"))
                .put("helpfulCount", row.getInteger("helpful_count"))
                .put("createdAt", row.getOffsetDateTime("created_at").toString());

            ctx.response()
               .setStatusCode(201)
               .putHeader("Content-Type", "application/json")
               .end(response.encode());
        });
    }


    public void handleGetManagerReviews(RoutingContext ctx) {
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
            decoded = JWT.decode(token); // Auth0 JWT decode
        } catch (JWTDecodeException e) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid token").encode());
            return;
        }
        
        // Parse manager ID from path
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

        // Query parameters with defaults
        String sortBy = ctx.queryParam("sortBy").stream().findFirst().orElse("recent");
        int limit = Integer.parseInt(ctx.queryParam("limit").stream().findFirst().orElse("50"));
        int offset = Integer.parseInt(ctx.queryParam("offset").stream().findFirst().orElse("0"));

        // Map sortBy to SQL ORDER BY
        String orderBy;
        switch (sortBy) {
            case "helpful":
                orderBy = "helpful_count DESC";
                break;
            case "highest":
                orderBy = "overall_rating DESC";
                break;
            case "lowest":
                orderBy = "overall_rating ASC";
                break;
            case "recent":
            default:
                orderBy = "created_at DESC";
                break;
        }

        // Future for total count
        Future<Long> totalFuture = Future.future(promise ->
            db.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1")
              .execute(Tuple.of(managerId), ar -> {
                  if (ar.succeeded()) {
                      long total = ar.result().iterator().next().getLong(0);
                      promise.complete(total);
                  } else {
                      promise.fail(ar.cause());
                  }
              })
        );

        // Future for review data
        String selectSql = String.format("""
            SELECT *
            FROM reviews
            WHERE manager_id = $1
            ORDER BY %s
            LIMIT $2 OFFSET $3
            """, orderBy);

        Future<RowSet<Row>> dataFuture = Future.future(promise ->
            db.preparedQuery(selectSql).execute(Tuple.of(managerId, limit, offset), ar -> {
                if (ar.succeeded()) {
                    promise.complete(ar.result());
                } else {
                    promise.fail(ar.cause());
                }
            })
        );

        // Combine futures
        Future.all((Future<?>) totalFuture, (Future<?>) dataFuture).onComplete(ar -> {
            if (ar.failed()) {
                ctx.fail(ar.cause());
                return;
            }

            long total = totalFuture.result();
            RowSet<Row> rows = dataFuture.result();
            JsonArray data = new JsonArray();

            for (Row row : rows) {
                // Build ratings object from individual columns
                JsonObject ratings = new JsonObject()
                    .put("Communication Style", row.getBigDecimal("communication_style"))
                    .put("Perceived Approachability", row.getBigDecimal("perceived_approachability"))
                    .put("Perceived Clarity of Expectations", row.getBigDecimal("perceived_clarity_of_expectations"))
                    .put("Feedback Style", row.getBigDecimal("feedback_style"))
                    .put("Perceived Supportiveness", row.getBigDecimal("perceived_supportiveness"))
                    .put("Decision Making Style", row.getBigDecimal("decision_making_style"))
                    .put("Organization and Planning Style", row.getBigDecimal("organization_and_planning_style"))
                    .put("Delegation Style", row.getBigDecimal("delegation_style"))
                    .put("Perceived Professional Demeanor", row.getBigDecimal("perceived_professional_demeanor"))
                    .put("Overall Working Experience", row.getBigDecimal("overall_working_experience"));

                data.add(new JsonObject()
                    .put("id", row.getLong("id"))
                    .put("managerId", row.getLong("manager_id"))
                    .put("author", row.getString("author"))
                    .put("overallRating", row.getBigDecimal("overall_rating"))
                    .put("ratings", ratings)
                    .put("managerCompany", row.getString("manager_company"))
                    .put("managerTitle", row.getString("manager_title"))
                    .put("text", row.getString("text"))
                    .put("verified", row.getBoolean("verified"))
                    .put("helpfulCount", row.getInteger("helpful_count"))
                    .put("createdAt", row.getOffsetDateTime("created_at").toString())
                );
            }

            JsonObject response = new JsonObject()
                .put("data", data)
                .put("total", total)
                .put("limit", limit)
                .put("offset", offset);

            ctx.response()
               .putHeader("Content-Type", "application/json")
               .end(response.encode());
        });
    }
    
    public void handleUpdateReview(RoutingContext ctx) {
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
            decoded = JWT.decode(token); // Auth0 JWT decode
        } catch (JWTDecodeException e) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid token").encode());
            return;
        }
        
        long managerId;
        long reviewId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
            reviewId = Long.parseLong(ctx.pathParam("reviewId"));
        } catch (NumberFormatException e) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Invalid managerId or reviewId").encode());
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

        Double overallRating = body.getDouble("overallRating");
        JsonObject ratings = body.getJsonObject("ratings");
        String managerCompany = body.getString("managerCompany");
        String managerTitle = body.getString("managerTitle");
        String text = body.getString("text");

        if (overallRating == null || ratings == null || managerCompany == null || managerTitle == null) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Missing required fields").encode());
            return;
        }

        // Map JSON ratings to individual columns
        Double communicationStyle = ratings.getDouble("Communication Style");
        Double perceivedApproachability = ratings.getDouble("Perceived Approachability");
        Double perceivedClarityOfExpectations = ratings.getDouble("Perceived Clarity of Expectations");
        Double feedbackStyle = ratings.getDouble("Feedback Style");
        Double perceivedSupportiveness = ratings.getDouble("Perceived Supportiveness");
        Double decisionMakingStyle = ratings.getDouble("Decision Making Style");
        Double organizationAndPlanningStyle = ratings.getDouble("Organization and Planning Style");
        Double delegationStyle = ratings.getDouble("Delegation Style");
        Double perceivedProfessionalDemeanor = ratings.getDouble("Perceived Professional Demeanor");
        Double overallWorkingExperience = ratings.getDouble("Overall Working Experience");

        String updateSql = """
            UPDATE reviews
            SET overall_rating = $1,
                communication_style = $2,
                perceived_approachability = $3,
                perceived_clarity_of_expectations = $4,
                feedback_style = $5,
                perceived_supportiveness = $6,
                decision_making_style = $7,
                organization_and_planning_style = $8,
                delegation_style = $9,
                perceived_professional_demeanor = $10,
                overall_working_experience = $11,
                manager_company = $12,
                manager_title = $13,
                text = $14,
                updated_at = now()
            WHERE id = $15 AND manager_id = $16
            RETURNING *
        """;

        Tuple params = Tuple.of(
            overallRating,
            communicationStyle,
            perceivedApproachability,
            perceivedClarityOfExpectations,
            feedbackStyle,
            perceivedSupportiveness,
            decisionMakingStyle,
            organizationAndPlanningStyle,
            delegationStyle,
            perceivedProfessionalDemeanor,
            overallWorkingExperience,
            managerCompany,
            managerTitle,
            text,
            reviewId,
            managerId
        );

        db.preparedQuery(updateSql).execute(params, ar -> {
            if (ar.failed()) {
                ctx.fail(ar.cause());
                return;
            }

            RowSet<Row> rows = ar.result();
            Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;

            if (row == null) {
                ctx.response()
                   .setStatusCode(404)
                   .putHeader("Content-Type", "application/json")
                   .end(new JsonObject().put("error", "Review not found").encode());
                return;
            }

            // Build ratings object to return
            JsonObject ratingsResponse = new JsonObject()
                .put("Communication Style", row.getBigDecimal("communication_style"))
                .put("Perceived Approachability", row.getBigDecimal("perceived_approachability"))
                .put("Perceived Clarity of Expectations", row.getBigDecimal("perceived_clarity_of_expectations"))
                .put("Feedback Style", row.getBigDecimal("feedback_style"))
                .put("Perceived Supportiveness", row.getBigDecimal("perceived_supportiveness"))
                .put("Decision Making Style", row.getBigDecimal("decision_making_style"))
                .put("Organization and Planning Style", row.getBigDecimal("organization_and_planning_style"))
                .put("Delegation Style", row.getBigDecimal("delegation_style"))
                .put("Perceived Professional Demeanor", row.getBigDecimal("perceived_professional_demeanor"))
                .put("Overall Working Experience", row.getBigDecimal("overall_working_experience"));

            JsonObject response = new JsonObject()
                .put("id", row.getLong("id"))
                .put("managerId", row.getLong("manager_id"))
                .put("author", row.getString("author"))
                .put("overallRating", row.getBigDecimal("overall_rating"))
                .put("ratings", ratingsResponse)
                .put("managerCompany", row.getString("manager_company"))
                .put("managerTitle", row.getString("manager_title"))
                .put("text", row.getString("text"))
                .put("verified", row.getBoolean("verified"))
                .put("helpfulCount", row.getInteger("helpful_count"))
                .put("createdAt", row.getOffsetDateTime("created_at").toString())
                .put("updatedAt", row.getOffsetDateTime("updated_at").toString());

            ctx.response()
               .setStatusCode(200)
               .putHeader("Content-Type", "application/json")
               .end(response.encode());
        });
    }

    public void handleDeleteManagerReview(RoutingContext ctx) {
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
            decoded = JWT.decode(token); // Auth0 JWT decode
        } catch (JWTDecodeException e) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid token").encode());
            return;
        }
        
        // Parse path params
        long managerId;
        long reviewId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
            reviewId = Long.parseLong(ctx.pathParam("reviewId"));
        } catch (NumberFormatException e) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Invalid managerId or reviewId").encode());
            return;
        }

        // Assume userId is available from token / session
        UUID userId = ctx.get("userId");
        if (userId == null) {
            ctx.response()
               .setStatusCode(401)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Unauthorized").encode());
            return;
        }

        // Verify review exists and belongs to user
        String selectSql = "SELECT user_id FROM reviews WHERE id = $1 AND manager_id = $2";
        db.preparedQuery(selectSql).execute(Tuple.of(reviewId, managerId), ar -> {
            if (ar.failed()) {
                ctx.fail(ar.cause());
                return;
            }

            RowSet<Row> rows = ar.result();
            if (!rows.iterator().hasNext()) {
                ctx.response()
                   .setStatusCode(404)
                   .putHeader("Content-Type", "application/json")
                   .end(new JsonObject().put("error", "Review not found").encode());
                return;
            }

            UUID reviewOwner = rows.iterator().next().getUUID("user_id");
            if (!reviewOwner.equals(userId)) {
                ctx.response()
                   .setStatusCode(401)
                   .putHeader("Content-Type", "application/json")
                   .end(new JsonObject().put("error", "Unauthorized").encode());
                return;
            }

            // Delete review
            String deleteSql = "DELETE FROM reviews WHERE id = $1 AND manager_id = $2";
            db.preparedQuery(deleteSql).execute(Tuple.of(reviewId, managerId), delAr -> {
                if (delAr.failed()) {
                    ctx.fail(delAr.cause());
                    return;
                }

                JsonObject response = new JsonObject()
                    .put("success", true)
                    .put("message", "Review deleted");

                ctx.response()
                   .setStatusCode(200)
                   .putHeader("Content-Type", "application/json")
                   .end(response.encode());
            });
        });
    }


}
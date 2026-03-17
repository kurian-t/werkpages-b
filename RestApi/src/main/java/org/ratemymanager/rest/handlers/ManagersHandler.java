package org.ratemymanager.rest.handlers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

public class ManagersHandler {

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

    // ---------------- GET MANAGERS (with optional search) ----------------
    public void handleGetManagers(RoutingContext ctx) {
//        String authHeader = ctx.request().getHeader("Authorization");
//        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
//            ctx.response()
//                .setStatusCode(401)
//                .putHeader("Content-Type", "application/json")
//                .end(new JsonObject().put("error", "Missing or invalid Authorization header").encode());
//            return;
//        }
//        String token = authHeader.substring("Bearer ".length());
//        try {
//            JWT.decode(token);
//        } catch (JWTDecodeException e) {
//            ctx.response()
//                .setStatusCode(401)
//                .putHeader("Content-Type", "application/json")
//                .end(new JsonObject().put("error", "Invalid token").encode());
//            return;
//        }

        int limit = Integer.parseInt(ctx.queryParam("limit").stream().findFirst().orElse("20"));
        int offset = Integer.parseInt(ctx.queryParam("offset").stream().findFirst().orElse("0"));
        String search = ctx.queryParam("search").stream().findFirst().orElse(null);
        boolean hasSearch = search != null && !search.isBlank();
        String searchPattern = hasSearch ? "%" + search.trim() + "%" : null;

        // Build SQL and tuples depending on whether search is present
        final String dataSql;
        final String countSql;
        final Tuple dataTuple;

        if (hasSearch) {
            dataSql = """
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
                WHERE (m.name ILIKE $3 OR m.company ILIKE $3 OR m.title ILIKE $3)
                GROUP BY m.id
                ORDER BY m.overall_rating DESC NULLS LAST, m.id ASC
                LIMIT $1 OFFSET $2
                """;
            countSql = "SELECT COUNT(*) FROM managers WHERE (name ILIKE $1 OR company ILIKE $1 OR title ILIKE $1)";
            dataTuple = Tuple.of(limit, offset, searchPattern);
        } else {
            dataSql = """
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
                ORDER BY m.overall_rating DESC NULLS LAST, m.id ASC
                LIMIT $1 OFFSET $2
                """;
            countSql = "SELECT COUNT(*) FROM managers";
            dataTuple = Tuple.of(limit, offset);
        }

        // Count future
        Future<Long> totalFuture = Future.future(promise -> {
            if (hasSearch) {
                db.preparedQuery(countSql).execute(Tuple.of(searchPattern), ar -> {
                    if (ar.succeeded()) promise.complete(ar.result().iterator().next().getLong(0));
                    else promise.fail(ar.cause());
                });
            } else {
                db.query(countSql).execute(ar -> {
                    if (ar.succeeded()) promise.complete(ar.result().iterator().next().getLong(0));
                    else promise.fail(ar.cause());
                });
            }
        });

        // Data future
        Future<RowSet<Row>> dataFuture = Future.future(promise ->
            db.preparedQuery(dataSql).execute(dataTuple, ar -> {
                if (ar.succeeded()) promise.complete(ar.result());
                else promise.fail(ar.cause());
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
//        String authHeader = ctx.request().getHeader("Authorization");
//        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
//            ctx.response()
//                .setStatusCode(401)
//                .putHeader("Content-Type", "application/json")
//                .end(new JsonObject().put("error", "Missing or invalid Authorization header").encode());
//            return;
//        }
//        String token = authHeader.substring("Bearer ".length());
//        DecodedJWT decoded;
//        try {
//            decoded = JWT.decode(token);
//        } catch (JWTDecodeException e) {
//            ctx.response()
//                .setStatusCode(401)
//                .putHeader("Content-Type", "application/json")
//                .end(new JsonObject().put("error", "Invalid token").encode());
//            return;
//        }

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
            decoded = JWT.decode(token);
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
        String name = body.getString("name");
        String company = body.getString("company");
        String title = body.getString("title");
        String image = body.getString("image");
        String status = body.getString("status");
        if (ValidationUtils.isBlank(name) || ValidationUtils.isBlank(company) ||
            ValidationUtils.isBlank(title) || ValidationUtils.isBlank(image) ||
            ValidationUtils.isBlank(status)) {
            ValidationUtils.badRequest(ctx, "Missing required fields");
            return;
        }
        if (ValidationUtils.exceedsLength(name, 100)) {
            ValidationUtils.badRequest(ctx, "Manager name must be at most 100 characters");
            return;
        }
        if (ValidationUtils.exceedsLength(company, 100)) {
            ValidationUtils.badRequest(ctx, "Company must be at most 100 characters");
            return;
        }
        if (ValidationUtils.exceedsLength(title, 100)) {
            ValidationUtils.badRequest(ctx, "Title must be at most 100 characters");
            return;
        }
        if (!status.equals("active") && !status.equals("retired")) {
            ValidationUtils.badRequest(ctx, "Status must be 'active' or 'retired'");
            return;
        }
        String bio = body.getString("bio");
        String linkedinUrl = body.getString("linkedinUrl");
        if (bio != null && ValidationUtils.exceedsLength(bio, 1000)) {
            ValidationUtils.badRequest(ctx, "Bio must be at most 1000 characters");
            return;
        }
        if (linkedinUrl != null && !linkedinUrl.isBlank()) {
            if (ValidationUtils.exceedsLength(linkedinUrl, 500)) {
                ValidationUtils.badRequest(ctx, "LinkedIn URL must be at most 500 characters");
                return;
            }
            if (!ValidationUtils.isValidLinkedinUrl(linkedinUrl)) {
                ValidationUtils.badRequest(ctx, "LinkedIn URL must be a valid linkedin.com URL");
                return;
            }
        }
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
                .put("createdAt", row.getOffsetDateTime("created_at").toString())
                .put("careerHistory", new JsonArray());
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
            decoded = JWT.decode(token);
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
        if (body == null || body.isEmpty()) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Nothing to update").encode());
            return;
        }
        String newCompany         = body.getString("company");
        String newTitle           = body.getString("title");
        String newImage           = body.getString("image");
        String newBio             = body.getString("bio");
        String newStatus          = body.getString("status");
        JsonObject newCategoryAvg = body.getJsonObject("categoryAverages");
        String newLinkedinUrl     = body.getString("linkedinUrl");
        Number newOverallRating   = body.containsKey("overallRating") ? body.getNumber("overallRating") : null;
        Integer newReviewsCount   = body.containsKey("reviews") ? body.getInteger("reviews") : null;
        boolean hasAnyField = newCompany != null || newTitle != null || newImage != null
            || newBio != null || newStatus != null || newCategoryAvg != null
            || newLinkedinUrl != null || newOverallRating != null || newReviewsCount != null;
        if (!hasAnyField) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Nothing to update").encode());
            return;
        }
        if (newCompany != null && (newCompany.isBlank() || ValidationUtils.exceedsLength(newCompany, 100))) {
            ValidationUtils.badRequest(ctx, "Company must be between 1 and 100 characters");
            return;
        }
        if (newTitle != null && (newTitle.isBlank() || ValidationUtils.exceedsLength(newTitle, 100))) {
            ValidationUtils.badRequest(ctx, "Title must be between 1 and 100 characters");
            return;
        }
        if (newBio != null && ValidationUtils.exceedsLength(newBio, 1000)) {
            ValidationUtils.badRequest(ctx, "Bio must be at most 1000 characters");
            return;
        }
        if (newStatus != null && !newStatus.equals("active") && !newStatus.equals("retired")) {
            ValidationUtils.badRequest(ctx, "Status must be 'active' or 'retired'");
            return;
        }
        if (newLinkedinUrl != null && !newLinkedinUrl.isBlank()) {
            if (ValidationUtils.exceedsLength(newLinkedinUrl, 500)) {
                ValidationUtils.badRequest(ctx, "LinkedIn URL must be at most 500 characters");
                return;
            }
            if (!ValidationUtils.isValidLinkedinUrl(newLinkedinUrl)) {
                ValidationUtils.badRequest(ctx, "LinkedIn URL must be a valid linkedin.com URL");
                return;
            }
        }
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
            String currentTitle   = row.getString("title");
            Runnable updateManager = () -> {
                StringBuilder sql = new StringBuilder("UPDATE managers SET ");
                List<Object> paramsList = new ArrayList<>();
                int idx = 1;
                if (newCompany != null)      { sql.append("company = $").append(idx++).append(", ");           paramsList.add(newCompany); }
                if (newTitle != null)        { sql.append("title = $").append(idx++).append(", ");             paramsList.add(newTitle); }
                if (newImage != null)        { sql.append("image = $").append(idx++).append(", ");             paramsList.add(newImage); }
                if (newBio != null)          { sql.append("bio = $").append(idx++).append(", ");               paramsList.add(newBio); }
                if (newStatus != null)       { sql.append("status = $").append(idx++).append(", ");            paramsList.add(newStatus); }
                if (newCategoryAvg != null)  { sql.append("category_averages = $").append(idx++).append(", "); paramsList.add(newCategoryAvg); }
                if (newLinkedinUrl != null)  { sql.append("linkedin_url = $").append(idx++).append(", ");      paramsList.add(newLinkedinUrl); }
                if (newOverallRating != null){ sql.append("overall_rating = $").append(idx++).append(", ");    paramsList.add(newOverallRating); }
                if (newReviewsCount != null) { sql.append("reviews_count = $").append(idx++).append(", ");     paramsList.add(newReviewsCount); }
                sql.delete(sql.length() - 2, sql.length());
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
                                    .put("endDate", r.getOffsetDateTime("end_date") != null
                                        ? r.getOffsetDateTime("end_date").toString() : null)
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
            if ((newCompany != null && !newCompany.equals(currentCompany)) ||
                (newTitle != null && !newTitle.equals(currentTitle))) {
                String insertCareerSql = """
                    INSERT INTO career_history(manager_id, company, title, start_date, end_date)
                    VALUES ($1, $2, $3, $4, $5)
                    """;
                OffsetDateTime now = OffsetDateTime.now();
                Tuple careerParams = Tuple.of(managerId, currentCompany, currentTitle,
                    row.getOffsetDateTime("created_at"), now);
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
        String auth0Id = null;
        try {
            decoded = JWT.decode(token);
            auth0Id = decoded.getClaim("sub").asString();
        } catch (JWTDecodeException e) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid token").encode());
            return;
        }
        String userIdQuery = "SELECT id FROM users WHERE auth0_id = $1";
        db.preparedQuery(userIdQuery).execute(Tuple.of(auth0Id), userAr -> {
            if (userAr.failed()) {
                ctx.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "Database error during user lookup").encode());
                return;
            }
            if (!userAr.result().iterator().hasNext()) {
                ctx.response()
                    .setStatusCode(404)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "User not found").encode());
                return;
            }
            UUID userId = userAr.result().iterator().next().getUUID("id");
            long managerId;
            try {
                managerId = Long.parseLong(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "Invalid manager ID format").encode());
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
            String author = body.getString("author");
            Double overallRating = body.getDouble("overallRating");
            JsonObject ratings = body.getJsonObject("ratings");
            String managerCompany = body.getString("managerCompany");
            String managerTitle = body.getString("managerTitle");
            String text = body.getString("text");
            if (ValidationUtils.isBlank(author) || overallRating == null || ratings == null ||
                ValidationUtils.isBlank(managerCompany) || ValidationUtils.isBlank(managerTitle)) {
                ValidationUtils.badRequest(ctx, "Missing required fields");
                return;
            }
            if (ValidationUtils.exceedsLength(author, 50)) {
                ValidationUtils.badRequest(ctx, "Author name must be at most 50 characters");
                return;
            }
            if (ValidationUtils.exceedsLength(managerCompany, 100)) {
                ValidationUtils.badRequest(ctx, "Manager company must be at most 100 characters");
                return;
            }
            if (ValidationUtils.exceedsLength(managerTitle, 100)) {
                ValidationUtils.badRequest(ctx, "Manager title must be at most 100 characters");
                return;
            }
            if (text != null && ValidationUtils.exceedsLength(text, 2000)) {
                ValidationUtils.badRequest(ctx, "Review text must be at most 2000 characters");
                return;
            }
            if (!ValidationUtils.isValidRating(overallRating)) {
                ValidationUtils.badRequest(ctx, "Overall rating must be between 1 and 5");
                return;
            }
            String[] ratingKeys = {
                "Communication Style", "Perceived Approachability", "Perceived Clarity of Expectations",
                "Feedback Style", "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"
            };
            String[] ratingKeysFallback = {
                "communication_style", "perceived_approachability", "perceived_clarity_of_expectations",
                "feedback_style", "perceived_supportiveness", "decision_making_style",
                "organization_and_planning_style", "delegation_style",
                "perceived_professional_demeanor", "overall_working_experience"
            };
            for (int i = 0; i < ratingKeys.length; i++) {
                Double v = ratings.getDouble(ratingKeys[i]) != null
                    ? ratings.getDouble(ratingKeys[i])
                    : ratings.getDouble(ratingKeysFallback[i]);
                if (!ValidationUtils.isValidRating(v)) {
                    ValidationUtils.badRequest(ctx, "Rating for '" + ratingKeys[i] + "' must be between 1 and 5");
                    return;
                }
            }
            Tuple params = Tuple.of(
                managerId, userId, author, overallRating,
                ratings.getDouble("Communication Style") != null ? ratings.getDouble("Communication Style") : ratings.getDouble("communication_style"),
                ratings.getDouble("Perceived Approachability") != null ? ratings.getDouble("Perceived Approachability") : ratings.getDouble("perceived_approachability"),
                ratings.getDouble("Perceived Clarity of Expectations") != null ? ratings.getDouble("Perceived Clarity of Expectations") : ratings.getDouble("perceived_clarity_of_expectations"),
                ratings.getDouble("Feedback Style") != null ? ratings.getDouble("Feedback Style") : ratings.getDouble("feedback_style"),
                ratings.getDouble("Perceived Supportiveness") != null ? ratings.getDouble("Perceived Supportiveness") : ratings.getDouble("perceived_supportiveness"),
                ratings.getDouble("Decision Making Style") != null ? ratings.getDouble("Decision Making Style") : ratings.getDouble("decision_making_style"),
                ratings.getDouble("Organization and Planning Style") != null ? ratings.getDouble("Organization and Planning Style") : ratings.getDouble("organization_and_planning_style"),
                ratings.getDouble("Delegation Style") != null ? ratings.getDouble("Delegation Style") : ratings.getDouble("delegation_style"),
                ratings.getDouble("Perceived Professional Demeanor") != null ? ratings.getDouble("Perceived Professional Demeanor") : ratings.getDouble("perceived_professional_demeanor"),
                ratings.getDouble("Overall Working Experience") != null ? ratings.getDouble("Overall Working Experience") : ratings.getDouble("overall_working_experience"),
                managerCompany, managerTitle, text
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
                    .put("id", row.getUUID("id").toString())
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
        });
    }

    public void handleGetManagerReviews(RoutingContext ctx) {
//        String authHeader = ctx.request().getHeader("Authorization");
//        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
//            ctx.response()
//                .setStatusCode(401)
//                .putHeader("Content-Type", "application/json")
//                .end(new JsonObject().put("error", "Missing or invalid Authorization header").encode());
//            return;
//        }
//        String token = authHeader.substring("Bearer ".length());
//        DecodedJWT decoded;
//        try {
//            decoded = JWT.decode(token);
//        } catch (JWTDecodeException e) {
//            ctx.response()
//                .setStatusCode(401)
//                .putHeader("Content-Type", "application/json")
//                .end(new JsonObject().put("error", "Invalid token").encode());
//            return;
//        }
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
        String sortBy = ctx.queryParam("sortBy").stream().findFirst().orElse("recent");
        int limit = Integer.parseInt(ctx.queryParam("limit").stream().findFirst().orElse("50"));
        int offset = Integer.parseInt(ctx.queryParam("offset").stream().findFirst().orElse("0"));
        String userIdParam = ctx.queryParam("userId").stream().findFirst().orElse(null);
        UUID userId = null;
        if (userIdParam != null) {
            try {
                userId = UUID.fromString(userIdParam);
            } catch (IllegalArgumentException e) {
                ctx.response()
                   .setStatusCode(400)
                   .putHeader("Content-Type", "application/json")
                   .end(new JsonObject().put("error", "Invalid userId format").encode());
                return;
            }
        }
        String orderBy;
        switch (sortBy) {
            case "helpful": orderBy = "helpful_count DESC"; break;
            case "highest": orderBy = "overall_rating DESC"; break;
            case "lowest":  orderBy = "overall_rating ASC"; break;
            case "recent":
            default:        orderBy = "created_at DESC"; break;
        }
        String userIdFilter = userId != null ? "AND user_id = $4" : "";
        String countSql = userId != null
            ? "SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND user_id = $2"
            : "SELECT COUNT(*) FROM reviews WHERE manager_id = $1";
        Tuple countParams = userId != null ? Tuple.of(managerId, userId) : Tuple.of(managerId);
        Future<Long> totalFuture = Future.future(promise ->
            db.preparedQuery(countSql).execute(countParams, ar -> {
                if (ar.succeeded()) promise.complete(ar.result().iterator().next().getLong(0));
                else promise.fail(ar.cause());
            })
        );
        String selectSql = String.format("""
            SELECT *
            FROM reviews
            WHERE manager_id = $1 %s
            ORDER BY %s
            LIMIT $2 OFFSET $3
            """, userIdFilter, orderBy);
        Tuple dataParams = userId != null
            ? Tuple.of(managerId, limit, offset, userId)
            : Tuple.of(managerId, limit, offset);
        Future<RowSet<Row>> dataFuture = Future.future(promise ->
            db.preparedQuery(selectSql).execute(dataParams, ar -> {
                if (ar.succeeded()) promise.complete(ar.result());
                else promise.fail(ar.cause());
            })
        );
        Future.all((Future<?>) totalFuture, (Future<?>) dataFuture).onComplete(ar -> {
            if (ar.failed()) {
                ctx.fail(ar.cause());
                return;
            }
            long total = totalFuture.result();
            RowSet<Row> rows = dataFuture.result();
            JsonArray data = new JsonArray();
            for (Row row : rows) {
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
                    .put("id", row.getUUID("id"))
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
                    .put("updatedAt", row.getOffsetDateTime("updated_at").toString())
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
        String auth0Id;
        try {
            decoded = JWT.decode(token);
            auth0Id = decoded.getClaim("sub").asString();
        } catch (JWTDecodeException e) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid token").encode());
            return;
        }
        if (auth0Id == null) {
            ctx.response()
               .setStatusCode(401)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Unauthorized").encode());
            return;
        }
        long managerId;
        UUID reviewId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
            reviewId = UUID.fromString(ctx.pathParam("reviewId"));
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
        if (overallRating == null || ratings == null ||
            ValidationUtils.isBlank(managerCompany) || ValidationUtils.isBlank(managerTitle)) {
            ValidationUtils.badRequest(ctx, "Missing required fields");
            return;
        }
        if (!ValidationUtils.isValidRating(overallRating)) {
            ValidationUtils.badRequest(ctx, "Overall rating must be between 1 and 5");
            return;
        }
        if (ValidationUtils.exceedsLength(managerCompany, 100)) {
            ValidationUtils.badRequest(ctx, "Manager company must be at most 100 characters");
            return;
        }
        if (ValidationUtils.exceedsLength(managerTitle, 100)) {
            ValidationUtils.badRequest(ctx, "Manager title must be at most 100 characters");
            return;
        }
        if (text != null && ValidationUtils.exceedsLength(text, 2000)) {
            ValidationUtils.badRequest(ctx, "Review text must be at most 2000 characters");
            return;
        }
        String[] updateRatingKeys = {
            "Communication Style", "Perceived Approachability", "Perceived Clarity of Expectations",
            "Feedback Style", "Perceived Supportiveness", "Decision Making Style",
            "Organization and Planning Style", "Delegation Style",
            "Perceived Professional Demeanor", "Overall Working Experience"
        };
        for (String key : updateRatingKeys) {
            if (!ValidationUtils.isValidRating(ratings.getDouble(key))) {
                ValidationUtils.badRequest(ctx, "Rating for '" + key + "' must be between 1 and 5");
                return;
            }
        }
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
            WHERE id = $15 AND manager_id = $16 AND user_id = $17
            RETURNING *
            """;
        // Resolve caller's internal UUID for ownership verification
        db.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
          .execute(Tuple.of(auth0Id), userAr -> {
            if (userAr.failed()) {
                ctx.fail(userAr.cause());
                return;
            }
            if (!userAr.result().iterator().hasNext()) {
                ctx.response()
                   .setStatusCode(401)
                   .putHeader("Content-Type", "application/json")
                   .end(new JsonObject().put("error", "Unauthorized").encode());
                return;
            }
            UUID callerId = userAr.result().iterator().next().getUUID("id");
            Tuple params = Tuple.of(
                overallRating, communicationStyle, perceivedApproachability,
                perceivedClarityOfExpectations, feedbackStyle, perceivedSupportiveness,
                decisionMakingStyle, organizationAndPlanningStyle, delegationStyle,
                perceivedProfessionalDemeanor, overallWorkingExperience,
                managerCompany, managerTitle, text, reviewId, managerId, callerId
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
                .put("id", row.getUUID("id"))
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
            });  // end updateSql execute
        });  // end user lookup execute
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
            decoded = JWT.decode(token);
        } catch (JWTDecodeException e) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid token").encode());
            return;
        }
        String auth0Id = decoded.getClaim("sub").asString();
        if (auth0Id == null) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Unauthorized").encode());
            return;
        }
        long managerId;
        UUID reviewId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
            reviewId = UUID.fromString(ctx.pathParam("reviewId"));
        } catch (IllegalArgumentException e) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Invalid managerId or reviewId").encode());
            return;
        }
        db.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
          .execute(Tuple.of(auth0Id), userAr -> {
            if (userAr.failed()) {
                ctx.fail(userAr.cause());
                return;
            }
            if (!userAr.result().iterator().hasNext()) {
                ctx.response()
                   .setStatusCode(401)
                   .putHeader("Content-Type", "application/json")
                   .end(new JsonObject().put("error", "Unauthorized").encode());
                return;
            }
            UUID userId = userAr.result().iterator().next().getUUID("id");
            String selectSql = "SELECT user_id FROM reviews WHERE id = $1 AND manager_id = $2";
            db.preparedQuery(selectSql).execute(Tuple.of(reviewId, managerId), ar -> {
                if (ar.failed()) {
                    ctx.fail(ar.cause());
                    return;
                }
                if (!ar.result().iterator().hasNext()) {
                    ctx.response()
                       .setStatusCode(404)
                       .putHeader("Content-Type", "application/json")
                       .end(new JsonObject().put("error", "Review not found").encode());
                    return;
                }
                UUID reviewOwner = ar.result().iterator().next().getUUID("user_id");
                if (!reviewOwner.equals(userId)) {
                    ctx.response()
                       .setStatusCode(403)
                       .putHeader("Content-Type", "application/json")
                       .end(new JsonObject().put("error", "Forbidden").encode());
                    return;
                }
                String deleteSql = "DELETE FROM reviews WHERE id = $1 AND manager_id = $2";
                db.preparedQuery(deleteSql).execute(Tuple.of(reviewId, managerId), delAr -> {
                    if (delAr.failed()) {
                        ctx.fail(delAr.cause());
                        return;
                    }
                    ctx.response()
                       .setStatusCode(200)
                       .putHeader("Content-Type", "application/json")
                       .end(new JsonObject()
                           .put("success", true)
                           .put("message", "Review deleted")
                           .encode());
                });
            });
        });
    }

    public void handleRecalculateManagerStats(RoutingContext ctx) {
        String authHeader = ctx.request().getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Missing or invalid Authorization header").encode());
            return;
        }
        String token = authHeader.substring("Bearer ".length());
        try {
            JWT.decode(token);
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
        db.preparedQuery("SELECT id FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId), ar -> {
                if (ar.failed()) {
                    ctx.fail(ar.cause());
                    return;
                }
                if (!ar.result().iterator().hasNext()) {
                    ctx.response()
                        .setStatusCode(404)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "Manager not found").encode());
                    return;
                }
                recalculateAndPersist(managerId, ctx, updatedManager -> {
                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(updatedManager.encode());
                });
            });
    }

    public void recalculateAndPersist(long managerId, RoutingContext ctx, Handler<JsonObject> onComplete) {
        String recalcSql = """
            SELECT
                COUNT(*)::INTEGER                                           AS reviews_count,
                ROUND(AVG(overall_rating)::NUMERIC, 1)                     AS overall_rating,
                ROUND(AVG(communication_style)::NUMERIC, 1)                AS communication_style,
                ROUND(AVG(perceived_approachability)::NUMERIC, 1)          AS perceived_approachability,
                ROUND(AVG(perceived_clarity_of_expectations)::NUMERIC, 1)  AS perceived_clarity_of_expectations,
                ROUND(AVG(feedback_style)::NUMERIC, 1)                     AS feedback_style,
                ROUND(AVG(perceived_supportiveness)::NUMERIC, 1)           AS perceived_supportiveness,
                ROUND(AVG(decision_making_style)::NUMERIC, 1)              AS decision_making_style,
                ROUND(AVG(organization_and_planning_style)::NUMERIC, 1)    AS organization_and_planning_style,
                ROUND(AVG(delegation_style)::NUMERIC, 1)                   AS delegation_style,
                ROUND(AVG(perceived_professional_demeanor)::NUMERIC, 1)    AS perceived_professional_demeanor,
                ROUND(AVG(overall_working_experience)::NUMERIC, 1)         AS overall_working_experience
            FROM reviews
            WHERE manager_id = $1
            """;
        db.preparedQuery(recalcSql).execute(Tuple.of(managerId), recalcAr -> {
            if (recalcAr.failed()) {
                ctx.fail(recalcAr.cause());
                return;
            }
            Row stats = recalcAr.result().iterator().next();
            int reviewsCount         = stats.getInteger("reviews_count");
            BigDecimal overallRating = reviewsCount > 0 ? stats.getBigDecimal("overall_rating") : null;
            JsonObject categoryAverages = new JsonObject();
            if (reviewsCount > 0) {
                categoryAverages
                    .put("Communication Style",               nullSafeDecimal(stats.getBigDecimal("communication_style")))
                    .put("Perceived Approachability",         nullSafeDecimal(stats.getBigDecimal("perceived_approachability")))
                    .put("Perceived Clarity of Expectations", nullSafeDecimal(stats.getBigDecimal("perceived_clarity_of_expectations")))
                    .put("Feedback Style",                    nullSafeDecimal(stats.getBigDecimal("feedback_style")))
                    .put("Perceived Supportiveness",          nullSafeDecimal(stats.getBigDecimal("perceived_supportiveness")))
                    .put("Decision Making Style",             nullSafeDecimal(stats.getBigDecimal("decision_making_style")))
                    .put("Organization and Planning Style",   nullSafeDecimal(stats.getBigDecimal("organization_and_planning_style")))
                    .put("Delegation Style",                  nullSafeDecimal(stats.getBigDecimal("delegation_style")))
                    .put("Perceived Professional Demeanor",   nullSafeDecimal(stats.getBigDecimal("perceived_professional_demeanor")))
                    .put("Overall Working Experience",        nullSafeDecimal(stats.getBigDecimal("overall_working_experience")));
            }
            String updateSql = """
                UPDATE managers
                SET
                    overall_rating    = $1,
                    reviews_count     = $2,
                    category_averages = $3,
                    updated_at        = now()
                WHERE id = $4
                RETURNING *
                """;
            JsonObject categoryAvg = reviewsCount > 0 ? categoryAverages : null;
            Tuple updateParams = Tuple.of(overallRating, reviewsCount, categoryAvg, managerId);
            db.preparedQuery(updateSql).execute(updateParams, updateAr -> {
                if (updateAr.failed()) {
                    ctx.fail(updateAr.cause());
                    return;
                }
                Row updated = updateAr.result().iterator().next();
                JsonObject response = new JsonObject()
                    .put("id",             updated.getLong("id"))
                    .put("name",           updated.getString("name"))
                    .put("company",        updated.getString("company"))
                    .put("title",          updated.getString("title"))
                    .put("image",          updated.getString("image"))
                    .put("overallRating",  updated.getBigDecimal("overall_rating"))
                    .put("reviews",        updated.getInteger("reviews_count"))
                    .put("bio",            updated.getString("bio"))
                    .put("status",         updated.getString("status"))
                    .put("categoryAverages", updated.getJsonObject("category_averages"))
                    .put("linkedinUrl",    updated.getString("linkedin_url"))
                    .put("createdAt",      updated.getOffsetDateTime("created_at").toString());
                onComplete.handle(response);
            });
        });
    }

    public void handleGetMyReviews(RoutingContext ctx) {
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
        if (auth0Id == null) {
            ctx.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Unauthorized").encode());
            return;
        }
        db.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
          .execute(Tuple.of(auth0Id), userAr -> {
            if (userAr.failed()) {
                ctx.fail(userAr.cause());
                return;
            }
            if (!userAr.result().iterator().hasNext()) {
                ctx.response()
                   .setStatusCode(404)
                   .putHeader("Content-Type", "application/json")
                   .end(new JsonObject().put("error", "User not found").encode());
                return;
            }
            UUID userId = userAr.result().iterator().next().getUUID("id");
            String selectSql = """
                SELECT
                    r.id,
                    r.manager_id,
                    r.author,
                    r.overall_rating,
                    r.communication_style,
                    r.perceived_approachability,
                    r.perceived_clarity_of_expectations,
                    r.feedback_style,
                    r.perceived_supportiveness,
                    r.decision_making_style,
                    r.organization_and_planning_style,
                    r.delegation_style,
                    r.perceived_professional_demeanor,
                    r.overall_working_experience,
                    r.manager_company,
                    r.manager_title,
                    r.text,
                    r.verified,
                    r.helpful_count,
                    r.created_at,
                    r.updated_at,
                    m.name    AS manager_name,
                    m.image   AS manager_image,
                    m.status  AS manager_status
                FROM reviews r
                JOIN managers m ON m.id = r.manager_id
                WHERE r.user_id = $1
                ORDER BY r.created_at DESC
                """;
            db.preparedQuery(selectSql).execute(Tuple.of(userId), ar -> {
                if (ar.failed()) {
                    ctx.fail(ar.cause());
                    return;
                }
                JsonArray data = new JsonArray();
                for (Row row : ar.result()) {
                    JsonObject ratings = new JsonObject()
                        .put("Communication Style",               row.getBigDecimal("communication_style"))
                        .put("Perceived Approachability",         row.getBigDecimal("perceived_approachability"))
                        .put("Perceived Clarity of Expectations", row.getBigDecimal("perceived_clarity_of_expectations"))
                        .put("Feedback Style",                    row.getBigDecimal("feedback_style"))
                        .put("Perceived Supportiveness",          row.getBigDecimal("perceived_supportiveness"))
                        .put("Decision Making Style",             row.getBigDecimal("decision_making_style"))
                        .put("Organization and Planning Style",   row.getBigDecimal("organization_and_planning_style"))
                        .put("Delegation Style",                  row.getBigDecimal("delegation_style"))
                        .put("Perceived Professional Demeanor",   row.getBigDecimal("perceived_professional_demeanor"))
                        .put("Overall Working Experience",        row.getBigDecimal("overall_working_experience"));
                    data.add(new JsonObject()
                        .put("id",              row.getUUID("id").toString())
                        .put("managerId",       row.getLong("manager_id"))
                        .put("managerName",     row.getString("manager_name"))
                        .put("managerImage",    row.getString("manager_image"))
                        .put("managerStatus",   row.getString("manager_status"))
                        .put("author",          row.getString("author"))
                        .put("overallRating",   row.getBigDecimal("overall_rating"))
                        .put("ratings",         ratings)
                        .put("managerCompany",  row.getString("manager_company"))
                        .put("managerTitle",    row.getString("manager_title"))
                        .put("text",            row.getString("text"))
                        .put("verified",        row.getBoolean("verified"))
                        .put("helpfulCount",    row.getInteger("helpful_count"))
                        .put("createdAt",       row.getOffsetDateTime("created_at").toString())
                        .put("updatedAt",       row.getOffsetDateTime("updated_at").toString())
                    );
                }
                ctx.response()
                   .setStatusCode(200)
                   .putHeader("Content-Type", "application/json")
                   .end(new JsonObject().put("data", data).encode());
            });
        });
    }

    public void handleGetStats(RoutingContext ctx) {
        Future<Long> managersFuture = Future.future(promise ->
            db.query("SELECT COUNT(*) FROM managers").execute(ar -> {
                if (ar.succeeded()) promise.complete(ar.result().iterator().next().getLong(0));
                else promise.fail(ar.cause());
            })
        );

        Future<Long> reviewsFuture = Future.future(promise ->
            db.query("SELECT COUNT(*) FROM reviews").execute(ar -> {
                if (ar.succeeded()) promise.complete(ar.result().iterator().next().getLong(0));
                else promise.fail(ar.cause());
            })
        );

        Future.all((Future<?>) managersFuture, (Future<?>) reviewsFuture).onComplete(ar -> {
            if (ar.failed()) {
                ctx.fail(ar.cause());
                return;
            }

            ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("totalManagers", managersFuture.result())
                    .put("totalReviews",  reviewsFuture.result())
                    .encode());
        });
    }
    
    // Safely converts a nullable BigDecimal to double for JSON serialisation
    private double nullSafeDecimal(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }
}
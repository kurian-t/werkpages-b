package org.ratemymanager.rest.handlers;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject; 
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

public class AuthHandler {

    private final SqlClient db;
    private final String auth0Domain;
    private final String clientId;
    private final String clientSecret;
    private final String audience;
    private final WebClient webClient;

    public AuthHandler(SqlClient db, String auth0Domain, String clientId, String clientSecret, String audience, Vertx vertx) {
        this.db            = db;
        this.auth0Domain   = auth0Domain;
        this.clientId      = clientId;
        this.clientSecret  = clientSecret;
        this.audience      = audience;
        this.webClient     = WebClient.create(vertx);
    }

    // ---------------- SIGNUP ----------------
    public void handleSignup(RoutingContext ctx) {
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) {
            ctx.response().setStatusCode(400).end("{\"error\":\"Missing body\"}");
            return;
        }

        String email     = body.getString("email");
        String username  = body.getString("username");
        String firstName = body.getString("firstName");
        String lastName  = body.getString("lastName");
        String password  = body.getString("password");

        if (ValidationUtils.isBlank(email) || ValidationUtils.isBlank(username) ||
            ValidationUtils.isBlank(firstName) || ValidationUtils.isBlank(lastName) ||
            ValidationUtils.isBlank(password)) {
            ValidationUtils.badRequest(ctx, "Missing required fields");
            return;
        }
        if (!ValidationUtils.isValidEmail(email)) {
            ValidationUtils.badRequest(ctx, "Invalid email format");
            return;
        }
        if (ValidationUtils.exceedsLength(email, 254)) {
            ValidationUtils.badRequest(ctx, "Email must be at most 254 characters");
            return;
        }
        if (firstName.trim().length() > 50) {
            ValidationUtils.badRequest(ctx, "First name must be at most 50 characters");
            return;
        }
        if (lastName.trim().length() > 50) {
            ValidationUtils.badRequest(ctx, "Last name must be at most 50 characters");
            return;
        }
        if (username.trim().length() < 3 || username.trim().length() > 30) {
            ValidationUtils.badRequest(ctx, "Username must be between 3 and 30 characters");
            return;
        }
        if (password.length() < 8 || password.length() > 128) {
            ValidationUtils.badRequest(ctx, "Password must be between 8 and 128 characters");
            return;
        }

        JsonObject auth0Payload = new JsonObject()
            .put("client_id", this.clientId)
            .put("email", email)
            .put("password", password)
            .put("connection", "Username-Password-Authentication")
            .put("user_metadata", new JsonObject()
                .put("firstName", firstName)
                .put("lastName", lastName)
                .put("username", username)
            );

        this.webClient.post(443, auth0Domain, "/dbconnections/signup")
            .ssl(true)
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(auth0Payload, ar -> {
                if (ar.failed()) {
                    ctx.fail(ar.cause());
                    return;
                }

                HttpResponse<Buffer> response = ar.result();

                if (response.statusCode() != 200) {
                    System.err.println("Auth0 signup error: " + response.statusCode() + " - " + response.bodyAsString());
                    ctx.response()
                        .setStatusCode(400)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "registration_failed")
                            .put("message", "Registration failed. Please check your details and try again.").encode());
                    return;
                }

                JsonObject auth0User = response.bodyAsJsonObject();

                String rawId = auth0User.getString("_id");
                if (rawId == null) {
                    ctx.response()
                        .setStatusCode(500)
                        .end("{\"error\":\"Auth0 did not return a user ID\"}");
                    return;
                }

                String auth0Id = "auth0|" + rawId;

                db.preparedQuery("""
                    INSERT INTO users (auth0_id, email, username, first_name, last_name)
                    VALUES ($1, $2, $3, $4, $5)
                """).execute(
                    Tuple.of(auth0Id, email, username, firstName, lastName),
                    dbAr -> {
                        if (dbAr.failed()) {
                            System.err.println("Database Error: " + dbAr.cause().getMessage());
                            ctx.fail(dbAr.cause());
                            return;
                        }

                        ctx.response()
                            .setStatusCode(201)
                            .putHeader("Content-Type", "application/json")
                            .end(new JsonObject()
                                .put("id", auth0Id)
                                .put("email", email)
                                .put("username", username)
                                .put("firstName", firstName)
                                .put("lastName", lastName)
                                .put("createdAt", java.time.OffsetDateTime.now().toString())
                                .encode()
                            );
                    }
                );
            });
    }

    // ---------------- SIGNIN ----------------
    public void handleSignin(RoutingContext ctx) {
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) {
            ctx.response().setStatusCode(400).end("{\"error\":\"Missing body\"}");
            return;
        }

        String email    = body.getString("email");
        String password = body.getString("password");

        if (email == null || password == null) {
            ctx.response().setStatusCode(400).end("{\"error\":\"Missing credentials\"}");
            return;
        }

        JsonObject payload = new JsonObject()
            .put("grant_type", "password")
            .put("username", email)
            .put("password", password)
            .put("connection", "Username-Password-Authentication")
            .put("audience", this.audience)
            .put("client_id", this.clientId)
            .put("client_secret", this.clientSecret);

        this.webClient.post(443, auth0Domain, "/oauth/token")
            .ssl(true)
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload, ar -> {
                if (ar.failed()) {
                    System.err.println("Auth0 Connection Error: " + ar.cause().getMessage());
                    ctx.response().setStatusCode(500).end("{\"error\":\"Could not connect to Auth0\"}");
                    return;
                }

                HttpResponse<Buffer> response = ar.result();

                // Check BEFORE parsing JSON — Auth0 error responses may not be valid JSON
                if (response.statusCode() != 200) {
                    System.err.println("Auth0 signin error: " + response.statusCode() + " - " + response.bodyAsString());
                    // Check specifically for unverified email (need this UX signal, not an enumeration risk)
                    String responseBody = response.bodyAsString();
                    boolean isUnverified = responseBody != null && responseBody.toLowerCase().contains("verify");
                    if (isUnverified) {
                        ctx.response()
                            .setStatusCode(403)
                            .putHeader("Content-Type", "application/json")
                            .end(new JsonObject().put("error", "email_not_verified")
                                .put("message", "Please verify your email before signing in.").encode());
                    } else {
                        ctx.response()
                            .setStatusCode(401)
                            .putHeader("Content-Type", "application/json")
                            .end(new JsonObject().put("error", "authentication_failed")
                                .put("message", "Invalid email or password.").encode());
                    }
                    return;
                }

                JsonObject auth0Response = response.bodyAsJsonObject();
                String accessToken = auth0Response.getString("access_token");

                try {
                    com.auth0.jwt.interfaces.DecodedJWT decoded = com.auth0.jwt.JWT.decode(accessToken);
                    String auth0Id = decoded.getSubject();

                    db.preparedQuery("""
                            SELECT u.email, u.username, u.first_name, u.last_name, u.role,
                                   (b.id IS NOT NULL) AS is_banned
                            FROM users u
                            LEFT JOIN banned_users b ON b.user_id = u.id
                            WHERE u.auth0_id = $1
                        """)
                        .execute(Tuple.of(auth0Id), dbAr -> {
                            if (dbAr.failed()) {
                                ctx.fail(dbAr.cause());
                                return;
                            }

                            RowSet<Row> rows = dbAr.result();
                            if (!rows.iterator().hasNext()) {
                                ctx.response().setStatusCode(401).end("{\"error\":\"User record not found in local database\"}");
                                return;
                            }

                            Row row = rows.iterator().next();

                            boolean isProd = "true".equalsIgnoreCase(System.getenv("USE_AWS_SECRETS"));
                            String setCookie = "auth_token=" + accessToken
                                + "; HttpOnly; Path=/; Max-Age=86400; SameSite=Strict"
                                + (isProd ? "; Secure" : "");
                            ctx.response()
                                .putHeader("Set-Cookie", setCookie)
                                .putHeader("Content-Type", "application/json")
                                .end(new JsonObject()
                                    .put("user", new JsonObject()
                                        .put("email", row.getString("email"))
                                        .put("username", row.getString("username"))
                                        .put("firstName", row.getString("first_name"))
                                        .put("lastName", row.getString("last_name"))
                                        .put("role", row.getString("role"))
                                        .put("isBanned", row.getBoolean("is_banned"))
                                    ).encode()
                                );
                        });

                } catch (Exception e) {
                    System.err.println("JWT Decoding Error: " + e.getMessage());
                    ctx.response().setStatusCode(500).end("{\"error\":\"Failed to process authentication token\"}");
                }
            });
    }

    // ---------------- ME ----------------
    public void handleMe(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) {
            ctx.response()
               .setStatusCode(401)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Unauthorized").encode());
            return;
        }
        db.preparedQuery("""
                SELECT u.id, u.auth0_id, u.email, u.username, u.first_name, u.last_name, u.role, u.created_at,
                       (b.id IS NOT NULL) AS is_banned
                FROM users u
                LEFT JOIN banned_users b ON b.user_id = u.id
                WHERE u.auth0_id = $1
            """)
          .execute(Tuple.of(auth0Id), ar -> {
            if (ar.failed()) {
                ctx.fail(ar.cause());
                return;
            }

            RowSet<Row> rows = ar.result();
            if (!rows.iterator().hasNext()) {
                ctx.response()
                   .setStatusCode(404)
                   .putHeader("Content-Type", "application/json")
                   .end(new JsonObject().put("error", "User not found").encode());
                return;
            }

            Row row = rows.iterator().next();

            ctx.response()
               .setStatusCode(200)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject()
                   .put("id", row.getUUID("id").toString())
                   .put("auth0Id", row.getString("auth0_id"))
                   .put("email", row.getString("email"))
                   .put("username", row.getString("username"))
                   .put("firstName", row.getString("first_name"))
                   .put("lastName", row.getString("last_name"))
                   .put("role", row.getString("role"))
                   .put("isBanned", row.getBoolean("is_banned"))
                   .put("createdAt", row.getLocalDateTime("created_at").toString())
                   .encode()
               );
        });
    }

    // ---------------- SIGNOUT ----------------
    public void handleSignout(RoutingContext ctx) {
        ctx.response()
            .putHeader("Set-Cookie", "auth_token=; HttpOnly; Path=/; Max-Age=0")
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("success", true).encode());
    }
    
    public void handleDeleteMe(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) {
            ctx.response()
               .setStatusCode(401)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("error", "Unauthorized").encode());
            return;
        }
        // Step 1: Look up the user's internal UUID
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

            // Step 2: Anonymize all reviews by this user
            String anonymizeSql = """
                UPDATE reviews
                SET author = 'Anonymous User',
                    user_id = NULL,
                    updated_at = now()
                WHERE user_id = $1
                """;

            db.preparedQuery(anonymizeSql).execute(Tuple.of(userId), anonymizeAr -> {
                if (anonymizeAr.failed()) {
                    ctx.fail(anonymizeAr.cause());
                    return;
                }

                // Step 3: Delete the user record
                db.preparedQuery("DELETE FROM users WHERE id = $1")
                  .execute(Tuple.of(userId), deleteAr -> {
                    if (deleteAr.failed()) {
                        ctx.fail(deleteAr.cause());
                        return;
                    }

                    ctx.response()
                       .setStatusCode(200)
                       .putHeader("Content-Type", "application/json")
                       .end(new JsonObject()
                           .put("success", true)
                           .put("message", "Account deleted and reviews anonymized")
                           .encode());
                });
            });
        });
    }
}
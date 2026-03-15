package org.ratemymanager.rest.handlers;

import java.time.OffsetDateTime;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;


public class AuthHandler {
    
    private final SqlClient db;
    private final String auth0Domain;
    private final String clientId;
    private final String clientSecret;
    private final String audience;
    
    public AuthHandler(SqlClient db, String auth0Domain, String clientId, String clientSecret, String audience) {
        this.db           = db;
        this.auth0Domain  = auth0Domain;
        this.clientId     = clientId;
        this.clientSecret = clientSecret;
        this.audience     = audience;
    }
    
 // ---------------- SIGNUP ----------------
    public void handleSignup(RoutingContext ctx) {
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) {
            ctx.response().setStatusCode(400).end("{\"error\":\"Missing body\"}");
            return;
        }

        String email = body.getString("email");
        String username = body.getString("username");
        String firstName = body.getString("firstName");
        String lastName = body.getString("lastName");
        String password = body.getString("password");

        if (email == null || username == null || firstName == null ||
            lastName == null || password == null) {
            ctx.response().setStatusCode(400).end("{\"error\":\"Missing fields\"}");
            return;
        }

        WebClient client = WebClient.create(ctx.vertx());

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

        // Call Auth0 Signup endpoint
        client.post(443, auth0Domain, "/dbconnections/signup")
            .ssl(true)
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(auth0Payload, ar -> {
                if (ar.failed()) {
                    ctx.fail(ar.cause());
                    return;
                }

                HttpResponse<Buffer> response = ar.result();
                JsonObject auth0User = response.bodyAsJsonObject();

                // 1. Validate Auth0 Response Status
                if (response.statusCode() != 200) {
                    ctx.response()
                        .setStatusCode(response.statusCode())
                        .putHeader("Content-Type", "application/json")
                        .end(auth0User.encode());
                    return;
                }

                // 2. Extract the raw ID and add the 'auth0|' prefix
                // This ensures the DB ID matches the JWT 'sub' claim during sign-in
                String rawId = auth0User.getString("_id");
                if (rawId == null) {
                    ctx.response()
                        .setStatusCode(500)
                        .end("{\"error\":\"Auth0 did not return a user ID\"}");
                    return;
                }
                
                String auth0Id = "auth0|" + rawId;

                // 3. Save in your local DB
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

                        // 4. Return successful response with 201 Created
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

        String email = body.getString("email");
        String password = body.getString("password");

        if (email == null || password == null) {
            ctx.response().setStatusCode(400).end("{\"error\":\"Missing credentials\"}");
            return;
        }

        WebClient client = WebClient.create(ctx.vertx());

        // NOTE: Ensure AUTH0_AUDIENCE, AUTH0_CLIENT_ID, and AUTH0_CLIENT_SECRET 
        // are set in your environment variables.
        JsonObject payload = new JsonObject()
            .put("grant_type", "password")
            .put("username", email)
            .put("password", password)
            .put("connection", "Username-Password-Authentication")
            .put("audience", this.audience)
            .put("client_id", this.clientId)
            .put("client_secret", this.clientSecret);

        // Ensure 'auth0Domain' is the field passed into your constructor
        client.post(443, auth0Domain, "/oauth/token")
            .ssl(true) // Required for HTTPS
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload, ar -> {
                if (ar.failed()) {
                    System.err.println("Auth0 Connection Error: " + ar.cause().getMessage());
                    ctx.response().setStatusCode(500).end("{\"error\":\"Could not connect to Auth0\"}");
                    return;
                }

                HttpResponse<Buffer> response = ar.result();
                JsonObject auth0Response = response.bodyAsJsonObject();

                // 1. Check if login was successful (Auth0 returns 200 for success)
                if (response.statusCode() != 200) {
                    ctx.response()
                        .setStatusCode(response.statusCode())
                        .putHeader("Content-Type", "application/json")
                        .end(auth0Response.encode());
                    return;
                }

                String accessToken = auth0Response.getString("access_token");

                try {
                    // 2. Decode JWT to get Auth0 user_id (sub claim)
                    // Requires: com.auth0:java-jwt dependency
                    com.auth0.jwt.interfaces.DecodedJWT decoded = com.auth0.jwt.JWT.decode(accessToken);
                    String auth0Id = decoded.getSubject();

                    // 3. Look up user in local database using the Auth0 ID
                    db.preparedQuery("SELECT email, username, first_name, last_name FROM users WHERE auth0_id = $1")
                        .execute(io.vertx.sqlclient.Tuple.of(auth0Id), dbAr -> {
                            if (dbAr.failed()) {
                                ctx.fail(dbAr.cause());
                                return;
                            }

                            io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> rows = dbAr.result();
                            if (!rows.iterator().hasNext()) {
                                ctx.response().setStatusCode(401).end("{\"error\":\"User record not found in local database\"}");
                                return;
                            }

                            io.vertx.sqlclient.Row row = rows.iterator().next();

                            // 4. Return the token and user profile
                            ctx.response()
                                .putHeader("Content-Type", "application/json")
                                .end(new JsonObject()
                                    .put("token", accessToken)
                                    .put("user", new JsonObject()
                                        .put("email", row.getString("email"))
                                        .put("username", row.getString("username"))
                                        .put("firstName", row.getString("first_name"))
                                        .put("lastName", row.getString("last_name"))
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

        db.preparedQuery("SELECT * FROM users WHERE auth0_id = $1")
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
                   .put("id", row.getUUID("id").toString())  // internal DB UUID — used for userId filtering
                   .put("auth0Id", row.getString("auth0_id"))
                   .put("email", row.getString("email"))
                   .put("username", row.getString("username"))
                   .put("firstName", row.getString("first_name"))
                   .put("lastName", row.getString("last_name"))
                   .put("createdAt", row.getLocalDateTime("created_at").toString())
                   .encode()
               );
        });
    }
    
    // ---------------- SIGNOUT ----------------
    public void handleSignout(RoutingContext ctx) {
        // JWTs are stateless, just tell client to remove it
        ctx.response()
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject()
                .put("success", true)
                .encode());
    }

}

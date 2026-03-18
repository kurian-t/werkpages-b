package org.ratemymanager.rest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.ratemymanager.config.SecretsConfig;
import org.ratemymanager.db.Database;
import org.ratemymanager.rest.handlers.AdminHandler;
import org.ratemymanager.rest.handlers.AuthHandler;
import org.ratemymanager.rest.handlers.ManagersHandler;
import org.ratemymanager.rest.handlers.RateLimitHandler;
import org.ratemymanager.rest.handlers.ReportsHandler;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.api.contract.openapi3.OpenAPI3RouterFactory;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;

public class MainVerticle extends AbstractVerticle {

    // Loaded once at startup from AWS Secrets Manager
    private static SecretsConfig secrets;

    public static void main(String[] args) {
        // Load secrets BEFORE Vert.x starts — intentionally blocking
        secrets = SecretsConfig.load();

        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new MainVerticle());
    }

    @SuppressWarnings("deprecation")
    @Override
    public void start() {
        WebClient client = WebClient.create(vertx);
        String jwksUrl = "https://" + secrets.auth0Domain + "/.well-known/jwks.json";

        client.getAbs(jwksUrl).send(ar -> {
            if (ar.succeeded()) {
                JsonObject responseBody = ar.result().bodyAsJsonObject();

                List<JsonObject> keys = responseBody.getJsonArray("keys")
                    .stream()
                    .map(obj -> JsonObject.mapFrom(obj))
                    .toList();

                JWTAuth jwtAuth = JWTAuth.create(vertx, new JWTAuthOptions().setJwks(keys));

                OpenAPI3RouterFactory.create(vertx, "openapi.yaml", routerFactoryAr -> {
                    if (routerFactoryAr.succeeded()) {
                        OpenAPI3RouterFactory routerFactory = routerFactoryAr.result();

                        Database.init(vertx, secrets, () -> {

                        ManagersHandler managersHandler = new ManagersHandler(Database.getClient());
                        ReportsHandler reportsHandler   = new ReportsHandler(Database.getClient());
                        AdminHandler adminHandler       = new AdminHandler(Database.getClient());

                        routerFactory.addHandlerByOperationId("getManagers",           managersHandler::handleGetManagers);
                        routerFactory.addHandlerByOperationId("getManagerById",        managersHandler::handleGetManagerById);
                        routerFactory.addHandlerByOperationId("createManager",         managersHandler::handleCreateManager);
                        routerFactory.addHandlerByOperationId("updateManager",         managersHandler::handleUpdateManager);
                        routerFactory.addHandlerByOperationId("createManagerReview",   managersHandler::handleCreateManagerReview);
                        routerFactory.addHandlerByOperationId("getManagerReviews",     managersHandler::handleGetManagerReviews);
                        routerFactory.addHandlerByOperationId("updateManagerReview",   managersHandler::handleUpdateReview);
                        routerFactory.addHandlerByOperationId("deleteManagerReview",   managersHandler::handleDeleteManagerReview);
                        routerFactory.addHandlerByOperationId("getMyReviews",          managersHandler::handleGetMyReviews);
                        routerFactory.addHandlerByOperationId("reportManager",         reportsHandler::handleReportManager);
                        routerFactory.addHandlerByOperationId("getStats",               managersHandler::handleGetStats);
                        routerFactory.addHandlerByOperationId("createManagerEditRequest", managersHandler::handleCreateEditRequest);
                        routerFactory.addHandlerByOperationId("getManagerPendingEdits",   managersHandler::handleGetPendingEditsForManager);
                        routerFactory.addHandlerByOperationId("getAdminPendingEdits",     adminHandler::handleGetPendingEdits);
                        routerFactory.addHandlerByOperationId("approveManagerEdit",       adminHandler::handleApproveEdit);
                        routerFactory.addHandlerByOperationId("rejectManagerEdit",        adminHandler::handleRejectEdit);
                        routerFactory.addHandlerByOperationId("getAdminUsers",            adminHandler::handleGetUsers);
                        routerFactory.addHandlerByOperationId("getAdminBannedUsers",      adminHandler::handleGetBannedUsers);
                        routerFactory.addHandlerByOperationId("banUser",                  adminHandler::handleBanUser);
                        routerFactory.addHandlerByOperationId("unbanUser",                adminHandler::handleUnbanUser);
                        
                        
                        // Pass secrets into AuthHandler — no more hardcoded credentials
                        AuthHandler authHandler = new AuthHandler(
                            Database.getClient(),
                            secrets.auth0Domain,
                            secrets.auth0ClientId,
                            secrets.auth0ClientSecret,
                            secrets.auth0Audience,
                            vertx
                        );
                        routerFactory.addHandlerByOperationId("signup",  authHandler::handleSignup);
                        routerFactory.addHandlerByOperationId("signin",  authHandler::handleSignin);
                        routerFactory.addHandlerByOperationId("me",      authHandler::handleMe);
                        routerFactory.addHandlerByOperationId("signout", authHandler::handleSignout);
                        routerFactory.addHandlerByOperationId("deleteMe", authHandler::handleDeleteMe);
                        
                        routerFactory.addSecurityHandler("bearerAuth", routingContext -> {
                            // Accept token from Authorization header OR HttpOnly cookie
                            String token = null;
                            String authHeader = routingContext.request().getHeader("Authorization");
                            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                                token = authHeader.substring(7);
                            } else {
                                String cookieHeader = routingContext.request().getHeader("Cookie");
                                if (cookieHeader != null) {
                                    for (String part : cookieHeader.split(";")) {
                                        String trimmed = part.trim();
                                        if (trimmed.startsWith("auth_token=")) {
                                            token = trimmed.substring("auth_token=".length());
                                            break;
                                        }
                                    }
                                }
                            }
                            if (token == null) {
                                routingContext.fail(401);
                                return;
                            }
                            final String finalToken = token;
                            jwtAuth.authenticate(new JsonObject().put("token", finalToken), res -> {
                                if (res.succeeded()) {
                                    routingContext.setUser(res.result());
                                    try {
                                        com.auth0.jwt.interfaces.DecodedJWT decoded = com.auth0.jwt.JWT.decode(finalToken);
                                        routingContext.put("auth0Id", decoded.getSubject());
                                    } catch (Exception ignored) {}
                                    routingContext.next();
                                } else {
                                    routingContext.fail(401);
                                }
                            });
                        });

                        Router apiRouter = routerFactory.getRouter();
                        Router router    = Router.router(vertx);

                        Set<String> allowedHeaders = new HashSet<>();
                        allowedHeaders.add("Authorization");
                        allowedHeaders.add("Content-Type");
                        allowedHeaders.add("Accept");
                        allowedHeaders.add("Cookie");

                        Set<HttpMethod> allowedMethods = new HashSet<>();
                        allowedMethods.add(HttpMethod.GET);
                        allowedMethods.add(HttpMethod.POST);
                        allowedMethods.add(HttpMethod.PUT);
                        allowedMethods.add(HttpMethod.DELETE);
                        allowedMethods.add(HttpMethod.OPTIONS);

                        // CORS — updated to production domain
                        String allowedOrigin = "true".equalsIgnoreCase(System.getenv("USE_AWS_SECRETS"))
                        	    ? "https://ratemymanagers\\.ca|https://www\\.ratemymanagers\\.ca"
                        	    : "http://localhost:8080";
                        
                        router.route().handler(
                            CorsHandler.create(allowedOrigin)
                                .allowedHeaders(allowedHeaders)
                                .allowedMethods(allowedMethods)
                                .allowCredentials(true)
                        );

                        // Rate limiting — applied in order before the API sub-router
                        RateLimitHandler globalLimiter = new RateLimitHandler(200, 60_000); // 200 req/min per IP
                        RateLimitHandler authLimiter   = new RateLimitHandler(10,  60_000); // 10 req/min  per IP on auth mutations
                        RateLimitHandler writeLimiter  = new RateLimitHandler(30,  60_000); // 30 req/min  per IP on writes

                        router.route().handler(globalLimiter::handle);
                        // Only apply the strict auth limiter to mutation endpoints (signin/signup/signout).
                        // GET /api/auth/me is a lightweight session check — leave it on the global limiter only.
                        router.post("/api/auth/*").handler(authLimiter::handle);
                        router.post("/api/*").handler(writeLimiter::handle);
                        router.put("/api/*").handler(writeLimiter::handle);
                        router.delete("/api/*").handler(writeLimiter::handle);

                        // Security headers
                        router.route().handler(secCtx -> {
                            secCtx.response()
                                .putHeader("X-Content-Type-Options", "nosniff")
                                .putHeader("X-Frame-Options", "DENY")
                                .putHeader("X-XSS-Protection", "0")
                                .putHeader("Referrer-Policy", "strict-origin-when-cross-origin");
                            boolean isProd = "true".equalsIgnoreCase(System.getenv("USE_AWS_SECRETS"));
                            if (isProd) {
                                secCtx.response().putHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
                            }
                            secCtx.next();
                        });

                        router.mountSubRouter("/", apiRouter);

                        router.route("/swagger/*")
                            .handler(StaticHandler.create().setCachingEnabled(false).setWebRoot("swagger"));
                        router.route("/swagger/webjars/*")
                            .handler(StaticHandler.create().setCachingEnabled(false).setWebRoot("META-INF/resources/webjars"));
                        router.get("/")
                            .handler(ctx -> ctx.response().putHeader("Location", "/swagger/index.html").setStatusCode(302).end());
                        router.get("/openapi.yaml")
                            .handler(ctx -> ctx.response().putHeader("Content-Type", "application/yaml").sendFile("openapi.yaml"));

                        vertx.createHttpServer()
                            .requestHandler(router)
                            .listen(8888)
                            .onSuccess(server -> System.out.println("✓ HTTP server started on port 8888"))
                            .onFailure(err -> System.err.println("✗ Failed to start server: " + err.getMessage()));

                        }); // end Database.init onReady
                    } else {
                        System.err.println("✗ Failed to create RouterFactory: " + routerFactoryAr.cause());
                    }
                });
            } else {
                System.err.println("✗ Failed to fetch JWKS from Auth0: " + ar.cause());
            }
        });
    }
}
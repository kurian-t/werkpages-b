package org.ratemymanager.rest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.ratemymanager.db.Database;
import org.ratemymanager.rest.handlers.AuthHandler;
import org.ratemymanager.rest.handlers.ManagersHandler;

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

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new MainVerticle());
    }

    @SuppressWarnings("deprecation")
    @Override
    public void start() {
        String auth0Domain = "ratemymanager.ca.auth0.com";
        WebClient client = WebClient.create(vertx);
        String jwksUrl = "https://" + auth0Domain + "/.well-known/jwks.json";

        client.getAbs(jwksUrl).send(ar -> {
            if (ar.succeeded()) {
                JsonObject responseBody = ar.result().bodyAsJsonObject();

                List<JsonObject> keys = responseBody.getJsonArray("keys")
                        .stream()
                        .map(obj -> JsonObject.mapFrom(obj))
                        .toList();

                JWTAuth jwtAuth = JWTAuth.create(vertx, new JWTAuthOptions().setJwks(keys));

                Database.init(vertx);

                OpenAPI3RouterFactory.create(vertx, "openapi.yaml", routerFactoryAr -> {
                    if (routerFactoryAr.succeeded()) {
                        OpenAPI3RouterFactory routerFactory = routerFactoryAr.result();

                        ManagersHandler managersHandler = new ManagersHandler(Database.getClient());
                        routerFactory.addHandlerByOperationId("getManagers", managersHandler::handleGetManagers);
                        routerFactory.addHandlerByOperationId("getManagerById", managersHandler::handleGetManagerById);
                        routerFactory.addHandlerByOperationId("recalculateManagerStats", managersHandler::handleRecalculateManagerStats);
                        routerFactory.addHandlerByOperationId("createManager", managersHandler::handleCreateManager);
                        routerFactory.addHandlerByOperationId("updateManager", managersHandler::handleUpdateManager);
                        routerFactory.addHandlerByOperationId("createManagerReview", managersHandler::handleCreateManagerReview);
                        routerFactory.addHandlerByOperationId("getManagerReviews", managersHandler::handleGetManagerReviews);
                        routerFactory.addHandlerByOperationId("handleUpdateReview", managersHandler::handleUpdateReview);
                        routerFactory.addHandlerByOperationId("deleteManagerReview", managersHandler::handleDeleteManagerReview);
                        routerFactory.addHandlerByOperationId("getMyReviews", managersHandler::handleGetMyReviews);
                        
                        
                        AuthHandler authHandler = new AuthHandler(Database.getClient(), auth0Domain);
                        routerFactory.addHandlerByOperationId("signup", authHandler::handleSignup);
                        routerFactory.addHandlerByOperationId("signin", authHandler::handleSignin);
                        routerFactory.addHandlerByOperationId("me", authHandler::handleMe);
                        routerFactory.addHandlerByOperationId("signout", authHandler::handleSignout);

                        routerFactory.addSecurityHandler("bearerAuth", routingContext -> {
                            String authHeader = routingContext.request().getHeader("Authorization");
                            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                                routingContext.fail(401);
                                return;
                            }

                            String token = authHeader.substring(7);

                            jwtAuth.authenticate(new JsonObject().put("token", token), res -> {
                                if (res.succeeded()) {
                                    routingContext.setUser(res.result());
                                    routingContext.next();
                                } else {
                                    routingContext.fail(401);
                                }
                            });
                        });

                        Router apiRouter = routerFactory.getRouter();
                        Router router = Router.router(vertx);

                        Set<String> allowedHeaders = new HashSet<>();
                        allowedHeaders.add("Authorization");
                        allowedHeaders.add("Content-Type");
                        allowedHeaders.add("Accept");

                        Set<HttpMethod> allowedMethods = new HashSet<>();
                        allowedMethods.add(HttpMethod.GET);
                        allowedMethods.add(HttpMethod.POST);
                        allowedMethods.add(HttpMethod.PUT);
                        allowedMethods.add(HttpMethod.DELETE);
                        allowedMethods.add(HttpMethod.OPTIONS);

                        router.route().handler(
                            CorsHandler.create("http://localhost:8080")
                                .allowedHeaders(allowedHeaders)
                                .allowedMethods(allowedMethods)
                                .allowCredentials(true)
                        );

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
                            .onSuccess(server -> System.out.println("HTTP server started on port 8888"))
                            .onFailure(err -> System.err.println("Failed to start server: " + err.getMessage()));
                    } else {
                        System.err.println("Failed to create RouterFactory: " + routerFactoryAr.cause());
                    }
                });
            } else {
                System.err.println("Failed to fetch JWKS from Auth0: " + ar.cause());
            }
        });
    }
}
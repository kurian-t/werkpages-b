package org.werkpages.rest;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.api.contract.RouterFactoryOptions;
import io.vertx.ext.web.api.contract.openapi3.OpenAPI3RouterFactory;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Contract-level regression tests that exercise the REAL OpenAPI validation layer built from
 * {@code openapi.yaml} — the same {@link OpenAPI3RouterFactory} the app boots with in MainVerticle.
 *
 * <p><b>Why this test exists (it has regressed several times):</b> the manager auto-create flow
 * ({@code /find} → findOrCreate, plus the ghost / anonymous-capture / drop-off / createManager
 * endpoints) receives geolocation {@code state}/{@code city} that are {@code null} whenever the geo
 * lookup can't resolve them. If those properties are not declared {@code nullable: true} in the
 * spec, Vert.x rejects the request with HTTP 400 <i>before it ever reaches the handler/service</i>,
 * silently breaking manager creation (no ghost, no pending). Service and integration tests invoke
 * the service methods directly and therefore CANNOT catch this class of bug — only a test that runs
 * requests through the router built from the spec can.
 *
 * <p>Each endpoint is sent an otherwise-fully-valid body with {@code null} geo fields and must be
 * accepted (reaches the stub handler → 200). Two negative controls prove the validator is genuinely
 * active (a missing required field and a wrong-typed field still 400), so a future change that
 * accidentally disables validation entirely would also be caught.
 */
class GeoNullableValidationTest {

    static Vertx vertx;
    static HttpServer server;
    static WebClient client;
    static int port;

    /** Every operationId whose request body accepts geo state/city. */
    static final List<String> GEO_OPS = List.of(
        "findOrCreateManager", "createGhostManager", "captureAnonymousSearch",
        "createDropOffDraft", "createManager");

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();

        CompletableFuture<Router> routerCf = new CompletableFuture<>();
        OpenAPI3RouterFactory.create(vertx, "openapi.yaml", ar -> {
            if (ar.failed()) { routerCf.completeExceptionally(ar.cause()); return; }
            OpenAPI3RouterFactory rf = ar.result();
            // We only want to test request validation, not auth — skip security handlers.
            rf.setOptions(new RouterFactoryOptions().setRequireSecurityHandlers(false));
            // Stub each geo operation so a request that PASSES validation is observable as a 200.
            for (String op : GEO_OPS) {
                rf.addHandlerByOperationId(op, ctx ->
                    ctx.response().setStatusCode(200)
                        .putHeader("content-type", "application/json").end("{}"));
            }
            routerCf.complete(rf.getRouter());
        });
        Router router = routerCf.get(30, TimeUnit.SECONDS);

        server = vertx.createHttpServer().requestHandler(router).listen(0)
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        port = server.actualPort();
        client = WebClient.create(vertx,
            new WebClientOptions().setDefaultHost("localhost").setDefaultPort(port));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (client != null) client.close();
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private int post(String path, JsonObject body) throws Exception {
        HttpResponse<Buffer> resp = client.post(path)
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        return resp.statusCode();
    }

    private JsonObject validReview() {
        return new JsonObject()
            .put("author", "anon")
            .put("overallRating", 4)
            .put("ratings", new JsonObject())
            .put("workedFrom", "2020-01")
            .put("managerCompany", "Google")
            .put("managerTitle", "Developer");
    }

    // ── The regression: null geo fields must be accepted (nullable) ──────────────────────────

    @Test
    void findOrCreate_nullStateAndCity_passesValidation() throws Exception {
        JsonObject body = new JsonObject()
            .put("firstName", "James").put("lastName", "Snow").put("title", "Developer")
            .put("company", "Google").put("country", "Canada")
            .putNull("state").putNull("city");
        assertEquals(200, post("/api/managers/find-or-create", body),
            "null state/city must be accepted — a 400 here means the /find auto-create flow is broken");
    }

    @Test
    void ghost_nullStateAndCity_passesValidation() throws Exception {
        JsonObject body = new JsonObject()
            .put("name", "James Snow").put("company", "Google").put("title", "Developer")
            .put("country", "Canada").putNull("state").putNull("city");
        assertEquals(200, post("/api/managers/ghost", body));
    }

    @Test
    void anonymousCapture_nullStateAndCity_passesValidation() throws Exception {
        JsonObject body = new JsonObject()
            .put("name", "James Snow").put("company", "Google").put("title", "Developer")
            .put("country", "Canada").putNull("state").putNull("city");
        assertEquals(200, post("/api/managers/anonymous-capture", body));
    }

    @Test
    void dropOff_nullState_passesValidation() throws Exception {
        JsonObject body = new JsonObject()
            .put("name", "James Snow").put("company", "Google").put("title", "Developer")
            .put("country", "Canada").putNull("state")
            .put("review", validReview());
        assertEquals(200, post("/api/managers/drop-off", body));
    }

    @Test
    void createManager_nullStateAndCity_passesValidation() throws Exception {
        JsonObject body = new JsonObject()
            .put("name", "James Snow").put("company", "Google").put("title", "Developer")
            .put("image", "img").put("status", "active").put("country", "Canada")
            .putNull("state").putNull("city");
        assertEquals(200, post("/api/managers", body));
    }

    // Geo fields may also simply be omitted (they are optional) — must also pass.
    @Test
    void findOrCreate_omittedStateAndCity_passesValidation() throws Exception {
        JsonObject body = new JsonObject()
            .put("firstName", "James").put("lastName", "Snow").put("title", "Developer")
            .put("company", "Google").put("country", "Canada");
        assertEquals(200, post("/api/managers/find-or-create", body));
    }

    // ── Negative controls: validation must still be genuinely active ─────────────────────────

    @Test
    void findOrCreate_missingRequiredCompany_isRejected() throws Exception {
        JsonObject body = new JsonObject()
            .put("firstName", "James").put("lastName", "Snow").put("title", "Developer")
            .put("country", "Canada"); // company (required) missing
        assertEquals(400, post("/api/managers/find-or-create", body),
            "missing required field must still 400 — proves validation is active, not disabled");
    }

    @Test
    void findOrCreate_wrongTypeCity_isRejected() throws Exception {
        JsonObject body = new JsonObject()
            .put("firstName", "James").put("lastName", "Snow").put("title", "Developer")
            .put("company", "Google").put("country", "Canada")
            .put("city", 12345); // a number — neither string nor null
        assertEquals(400, post("/api/managers/find-or-create", body),
            "a non-string, non-null city must still 400 — nullable must not disable type checking");
    }
}

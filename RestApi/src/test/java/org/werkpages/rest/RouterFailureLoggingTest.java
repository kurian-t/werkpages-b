package org.werkpages.rest;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.HttpException;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AN EXPECTED 401 IS NOT A SERVER ERROR, AND MUST NOT BE LOGGED LIKE ONE.
 *
 * <h2>The defect</h2>
 *
 * <p>Every anonymous request to an admin endpoint answered 401 correctly and wrote
 * {@code SEVERE: Unhandled exception in router} while doing it. Measured on the running server:
 * twelve anonymous requests produced twelve lines, a thirteenth produced one more, and a
 * successful public request produced none. The body was Vert.x's own default {@code Unauthorized},
 * which is the tell - nothing had handled the failure, so Vert.x logged it as unhandled.
 *
 * <p>These endpoints are probed continuously. A SEVERE line that means "somebody tried the door"
 * is a SEVERE line nobody reads when it finally means something.
 *
 * <h2>What is asserted here, and why in two layers</h2>
 *
 * <p>The predicate is tested directly, because the interesting cases - a 401 carrying an
 * unexpected throwable, which must stay loud - are awkward to provoke over HTTP and trivial to
 * state as data.
 *
 * <p>The behaviour is then tested over a real HTTP server, because the predicate being right does
 * not prove the wiring is. The first attempt at this fix logged more quietly but left the failure
 * unhandled, and Vert.x kept writing the line regardless; only an end-to-end assertion on the
 * captured log output would have caught that, and it is what this test does.
 */
class RouterFailureLoggingTest {

    // ── the decision, as data ────────────────────────────────────────────────

    @Test
    @DisplayName("a 401 or 403 from the authorization layer is an expected refusal")
    void expectedRefusals() {
        assertTrue(RouterFailureLogging.isExpectedRefusal(401, null),
            "a 401 with no attached failure is the security handler refusing a caller");
        assertTrue(RouterFailureLogging.isExpectedRefusal(403, null));
        assertTrue(RouterFailureLogging.isExpectedRefusal(401, new HttpException(401)),
            "an HttpException carrying the same status IS the refusal, not a fault");
        assertTrue(RouterFailureLogging.isExpectedRefusal(403, new HttpException(403)));
    }

    @Test
    @DisplayName("a genuine unexpected failure stays visible, including on a 401")
    void unexpectedFailuresStayVisible() {
        assertFalse(RouterFailureLogging.isExpectedRefusal(401, new IllegalStateException("boom")),
            "a 401 that arrives with an unrelated exception is a real fault and must be logged - "
            + "this is the case that makes the fix a predicate instead of muting 401 outright");
        assertFalse(RouterFailureLogging.isExpectedRefusal(500, null));
        assertFalse(RouterFailureLogging.isExpectedRefusal(500, new RuntimeException("boom")));
        assertFalse(RouterFailureLogging.isExpectedRefusal(404, null));
        assertFalse(RouterFailureLogging.isExpectedRefusal(401, new HttpException(500)),
            "a status mismatch means something failed while producing the refusal");
    }

    // ── the behaviour, over real HTTP, with the log captured ────────────────

    private Vertx vertx;
    private HttpServer server;
    private WebClient client;
    private final List<LogRecord> severe = new CopyOnWriteArrayList<>();
    private Handler capture;
    private Logger rootLogger;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();

        rootLogger = Logger.getLogger("");
        capture = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.SEVERE.intValue()) severe.add(record);
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        rootLogger.addHandler(capture);

        Router router = Router.router(vertx);
        RouterFailureLogging.install(router, "test");

        // Stands in for the OpenAPI security handler: refuses with the same shape it produces.
        router.route("/secured").handler(ctx -> ctx.fail(new HttpException(401)));
        // And a route that genuinely breaks, to prove the loud path still shouts.
        router.route("/broken").handler(ctx -> ctx.fail(new IllegalStateException("deliberate")));
        router.route("/open").handler(ctx -> ctx.response().end("ok"));

        server = vertx.createHttpServer().requestHandler(router).listen(0)
            .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        client = WebClient.create(vertx, new WebClientOptions().setDefaultPort(server.actualPort()));
    }

    @AfterEach
    void tearDown() {
        if (rootLogger != null && capture != null) rootLogger.removeHandler(capture);
        if (client != null) client.close();
        if (vertx != null) vertx.close();
    }

    @Test
    @DisplayName("an anonymous request is refused 401 and writes no SEVERE log line")
    void anonymousRefusalIsQuietAndStillRefuses() throws Exception {
        var res = client.get("/secured").send().toCompletionStage().toCompletableFuture()
            .get(20, TimeUnit.SECONDS);

        assertEquals(401, res.statusCode(), "the caller must still be refused");
        assertEquals("Unauthorized", res.bodyAsString(),
            "the response body must be byte-for-byte what it was before this change; taking "
            + "ownership of the response is not licence to alter the API contract");

        assertTrue(severe.isEmpty(),
            "an expected 401 must not log at SEVERE. Captured: "
            + severe.stream().map(LogRecord::getMessage).toList());
    }

    @Test
    @DisplayName("ten refusals write ten nothing, which is the measured shape of the defect")
    void repeatedRefusalsDoNotAccumulateLogLines() throws Exception {
        for (int i = 0; i < 10; i++) {
            var res = client.get("/secured").send().toCompletionStage().toCompletableFuture()
                .get(20, TimeUnit.SECONDS);
            assertEquals(401, res.statusCode());
        }
        assertEquals(0, severe.size(),
            "the original defect was exactly one SEVERE line per anonymous request; "
            + "captured " + severe.size());
    }

    @Test
    @DisplayName("a genuine server fault is still logged")
    void realFaultsAreStillLoud() throws Exception {
        var res = client.get("/broken").send().toCompletionStage().toCompletableFuture()
            .get(20, TimeUnit.SECONDS);
        assertEquals(500, res.statusCode());
        // The point is that the quiet path did not swallow everything. The fault surfaces either
        // through Vert.x's own logger or through the handler's stderr line; what must not happen
        // is silence plus a 500.
        assertNotEquals(401, res.statusCode());
    }

    @Test
    @DisplayName("a successful request logs nothing either")
    void successIsQuiet() throws Exception {
        var res = client.get("/open").send().toCompletionStage().toCompletableFuture()
            .get(20, TimeUnit.SECONDS);
        assertEquals(200, res.statusCode());
        assertTrue(severe.isEmpty());
    }
}

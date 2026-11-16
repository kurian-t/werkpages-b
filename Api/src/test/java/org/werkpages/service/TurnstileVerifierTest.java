package org.werkpages.service;

import com.sun.net.httpserver.HttpServer;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnstileVerifier} against a stub that stands in for Cloudflare.
 *
 * <p><b>Why a stub and not the real endpoint.</b> No test in this repository may call a metered or
 * rate-limited third party: a Playwright suite once spent logo.dev's entire monthly allowance and
 * blanked production logos. Verifying a real token would also need a real secret, so the test
 * would be unrunnable on a developer machine and would skip itself in CI, which is how a test
 * comes to prove nothing. The stub is the whole protocol this class depends on - a form POST and a
 * {@code success} boolean - so there is nothing left for the real endpoint to teach it.
 *
 * <p>The fail-closed cases are most of this file on purpose. Getting {@code success: true} right is
 * easy; what decides whether a CAPTCHA is worth having is what happens when the verifier cannot
 * reach a verdict, and the tempting answer there - let the request through - makes the check a
 * formality exactly when it is under attack.
 */
class TurnstileVerifierTest {

    private HttpServer stub;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    /** Starts a stub that answers every request with the given status and body. */
    private String startStub(int status, String body) throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/siteverify", exchange -> {
            calls.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });
        stub.start();
        return "http://127.0.0.1:" + stub.getAddress().getPort() + "/siteverify";
    }

    @AfterEach
    void stopStub() {
        if (stub != null) stub.stop(0);
    }

    @BeforeEach
    void resetCounters() {
        calls.set(0);
        lastBody.set(null);
    }

    /** Pointed at the stub rather than Cloudflare. */
    private TurnstileVerifier verifierAgainst(String endpoint, String secret) {
        TurnstileVerifier verifier = new TurnstileVerifier(secret);
        verifier.setEndpoint(endpoint);
        return verifier;
    }

    // ── Unconfigured ──────────────────────────────────────────────────────────

    /**
     * No secret means no verification, and that is the developer-machine case.
     *
     * <p>Asserted for null, empty and whitespace together, because a secret store that returns ""
     * for an absent key is a normal thing for a secret store to do, and " " was what a stray
     * environment file produced once. All three must mean "off" rather than "on, with the key ' '",
     * which would refuse every request in production and look like Cloudflare being down.
     */
    @Test
    void anUnconfiguredVerifierPassesEverything() throws Exception {
        for (String secret : new String[]{null, "", "   "}) {
            TurnstileVerifier verifier = new TurnstileVerifier(secret);
            assertFalse(verifier.isEnabled(), "a blank secret must read as unconfigured: " + secret);
            await(verifier.verify(null, null));
            await(verifier.verify("anything", "203.0.113.1"));
        }
    }

    // ── Configured ────────────────────────────────────────────────────────────

    @Test
    void aGoodTokenPasses() throws Exception {
        String endpoint = startStub(200, "{\"success\":true}");
        await(verifierAgainst(endpoint, "sek").verify("good-token", "203.0.113.7"));
        assertEquals(1, calls.get(), "it must actually ask Cloudflare");
    }

    /**
     * The secret, the token and the address all reach Cloudflare, form-encoded.
     *
     * <p>Worth its own assertion because every failure mode of this request looks identical from
     * the outside: a verifier that posted an empty form would get {@code success: false} and refuse
     * every token, which reads exactly like a site under attack.
     */
    @Test
    void theRequestCarriesTheSecretTheTokenAndTheAddress() throws Exception {
        String endpoint = startStub(200, "{\"success\":true}");
        await(verifierAgainst(endpoint, "my-secret").verify("tok-123", "203.0.113.9"));

        String body = lastBody.get();
        assertTrue(body.contains("secret=my-secret"),   "the secret must be sent: " + body);
        assertTrue(body.contains("response=tok-123"),   "the token must be sent: " + body);
        assertTrue(body.contains("remoteip=203.0.113.9"), "the address must be sent: " + body);
    }

    @Test
    void aRefusedTokenIsForbidden() throws Exception {
        String endpoint = startStub(200,
            "{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}");
        assertEquals(403, statusOfRefusal(verifierAgainst(endpoint, "sek").verify("used", null)));
    }

    /**
     * Cloudflare's error codes do not reach the caller.
     *
     * <p>"timeout-or-duplicate" and "invalid-input-response" are different facts, and telling a
     * caller which one applies tells somebody probing the endpoint which of their attempts came
     * closer to working. The message is the same either way, and the codes go to the log.
     */
    @Test
    void theRefusalDoesNotEchoCloudflaresErrorCodes() throws Exception {
        String endpoint = startStub(200,
            "{\"success\":false,\"error-codes\":[\"invalid-input-secret\"]}");
        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> verifierAgainst(endpoint, "sek").verify("x", null)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertFalse(thrown.getCause().getMessage().contains("invalid-input-secret"),
            "an internal code must not travel to the caller: " + thrown.getCause().getMessage());
    }

    // ── Fails closed ──────────────────────────────────────────────────────────

    /** A missing token is refused without a round trip: there is nothing to verify. */
    @Test
    void aMissingTokenIsRefusedWithoutCallingOut() throws Exception {
        String endpoint = startStub(200, "{\"success\":true}");
        TurnstileVerifier verifier = verifierAgainst(endpoint, "sek");
        for (String token : new String[]{null, "", "  "}) {
            assertEquals(403, statusOfRefusal(verifier.verify(token, null)));
        }
        assertEquals(0, calls.get(), "no token means no request to make");
    }

    @Test
    void aNon200FromCloudflareIsRefused() throws Exception {
        String endpoint = startStub(502, "upstream is unwell");
        assertEquals(403, statusOfRefusal(verifierAgainst(endpoint, "sek").verify("tok", null)));
    }

    @Test
    void anUnparseableBodyIsRefused() throws Exception {
        String endpoint = startStub(200, "<html>not json at all</html>");
        assertEquals(403, statusOfRefusal(verifierAgainst(endpoint, "sek").verify("tok", null)));
    }

    /**
     * An unreachable verifier refuses rather than waving the request through.
     *
     * <p>The most important case in the file. Treating a network failure as a pass means the
     * CAPTCHA stops existing the moment anything goes wrong, and when something does go wrong an
     * attacker is a likely reason.
     */
    @Test
    void anUnreachableEndpointIsRefused() throws Exception {
        // Port 1 on loopback: nothing listens, and the connection is refused immediately rather
        // than hanging, which keeps this test fast and deterministic.
        assertEquals(403, statusOfRefusal(
            verifierAgainst("http://127.0.0.1:1/siteverify", "sek").verify("tok", null)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The HTTP status the verifier refused with, asserting that it refused at all. */
    private int statusOfRefusal(Future<Void> attempt) {
        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> attempt.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS),
            "this must be a refusal, not a pass");
        Throwable cause = thrown.getCause();
        assertTrue(cause instanceof ServiceException, "expected a ServiceException, got " + cause);
        return ((ServiceException) cause).getStatusCode();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
}

package org.werkpages.rest.handlers;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

/**
 * WHERE Auth0 is, and nothing else.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The callback's HTTP calls were written as {@code webClient.post(443, auth0Domain, path).ssl(true)}
 * inline. That is correct in production and untestable anywhere else: a test cannot stand up a
 * stub on port 443 of a real tenant domain, so the only proof available for the whole callback was
 * structural, {@code AuthCallbackBoundaryTest} reading the source and asserting the ORDER of the
 * calls. Order is worth having and it is not behaviour. It cannot show that a valid callback
 * issues a session, that a forged token issues nothing, or that a 500 from Auth0 creates no
 * account.
 *
 * <p>So the destination moved into one object that can be pointed at a local stub, and the calls
 * themselves did not change: same paths, same headers, same timeout, same {@link HttpResponse}
 * handed back to the same lambdas. This is deliberately NOT an abstraction over "authentication"
 * or over Auth0's API. It knows a host, a port and whether to use TLS. Anything richer would have
 * moved decisions out of the handler, and the handler is where they are reviewed.
 *
 * <h2>Why the test factory is package-private</h2>
 *
 * <p>{@link #https} is the only way to build one from outside this package, and it always uses
 * port 443 with TLS. {@link #plaintext} exists for tests and {@code MainVerticle} lives in the
 * parent package, so production wiring <b>cannot</b> reach it even by mistake. A seam that can be
 * repointed from a config value would be a way to send client credentials somewhere else.
 */
final class Auth0Http {

    /**
     * Ten seconds, unchanged from the inline calls this replaced.
     *
     * <p>It bounds a login attempt rather than optimising one: without it a stalled connection to
     * Auth0 holds the request open indefinitely and the person sees a page that never resolves.
     */
    private static final long TIMEOUT_MS = 10_000;

    private final WebClient webClient;
    private final String host;
    private final int port;
    private final boolean ssl;

    private Auth0Http(WebClient webClient, String host, int port, boolean ssl) {
        this.webClient = webClient;
        this.host      = host;
        this.port      = port;
        this.ssl       = ssl;
    }

    /** The real tenant: TLS, port 443, exactly as the inline calls did. */
    static Auth0Http https(Vertx vertx, String auth0Domain) {
        return new Auth0Http(WebClient.create(vertx), auth0Domain, 443, true);
    }

    /**
     * A local stub, for tests only.
     *
     * <p>Plaintext because a test server with a self-signed certificate proves nothing about TLS
     * and costs a trust-store. TLS to the real tenant is asserted on {@link #https} instead, by
     * {@link #ssl()} and {@link #port()}, so no test can pass while production has quietly stopped
     * using it.
     */
    static Auth0Http plaintext(Vertx vertx, String host, int port) {
        return new Auth0Http(WebClient.create(vertx), host, port, false);
    }

    Future<HttpResponse<Buffer>> postJson(String path, JsonObject body) {
        return webClient.post(port, host, path)
            .ssl(ssl).timeout(TIMEOUT_MS)
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(body);
    }

    Future<HttpResponse<Buffer>> getWithBearer(String path, String bearerToken) {
        return webClient.get(port, host, path)
            .ssl(ssl).timeout(TIMEOUT_MS)
            .putHeader("Authorization", "Bearer " + bearerToken)
            .send();
    }

    /**
     * The response body as JSON, or NULL when it is not JSON at all.
     *
     * <p>{@code bodyAsJsonObject()} throws {@link DecodeException} on anything that is not an
     * object, and it was being called straight inside the response lambda. A throw there is not
     * caught by anything that can answer the browser: Vert.x logs it on the context and the
     * request is simply never completed, so the person's login hangs until their client gives up.
     *
     * <p>A proxy error page, an HTML maintenance notice or a truncated body is not a theoretical
     * shape for this to receive. Returning null makes the caller decide, and both callers refuse.
     */
    static JsonObject asJsonOrNull(HttpResponse<Buffer> response) {
        try {
            JsonObject body = response.bodyAsJsonObject();
            return body;
        } catch (DecodeException | ClassCastException malformed) {
            // No body content in the message. A malformed body from the token endpoint may still
            // contain a code or a token fragment, and this string reaches a log file.
            return null;
        }
    }

    String host() { return host; }
    int port()    { return port; }
    boolean ssl() { return ssl; }
}

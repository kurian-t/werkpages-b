package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Verifies a Cloudflare Turnstile token against {@code siteverify}.
 *
 * <p><b>This is the half that was missing.</b> {@code TURNSTILE_SECRET_KEY} has been loaded by
 * {@code SecretsConfig} and carried into {@code AuthHandler} as a field for a long time, and
 * nothing ever read it: there was no {@code siteverify} call anywhere in the Java. A configured
 * secret therefore bought exactly nothing, which is worse than no secret at all, because the
 * configuration is what somebody reads when asking whether this product checks CAPTCHAs.
 *
 * <p><b>Read this before wiring it to an endpoint.</b> No endpoint currently accepts a Turnstile
 * token, and that is deliberate rather than an oversight. The two forms it protected -
 * {@code POST /api/auth/signup} and {@code POST /api/auth/signin} - were deleted on 2026-11-11
 * when Universal Login replaced them, and {@code AuthCallbackBoundaryTest} asserts structurally
 * that neither returns. Account creation, password policy and bot protection now belong to Auth0's
 * hosted page. The frontend widget went with them: {@code SignUp.tsx} records that "a CAPTCHA in
 * front of a button that only performs a redirect protects nothing", which is correct.
 *
 * <p>So this class is the enforcement, ready, and choosing what to enforce is a product decision
 * with a frontend half to it. Two notes for whoever makes it:
 *
 * <ul>
 *   <li><b>A check is not a gate.</b> Verifying a token when the request happens to carry one
 *       protects nothing, because an attacker omits the field. A gate means the token is REQUIRED,
 *       and that needs a widget on the form first or the form stops working.</li>
 *   <li><b>{@code POST /api/auth/recover} looks like the obvious target and is not.</b> It answers
 *       202 before doing any work, deliberately, so that the reply is identical whether or not the
 *       username exists. A verification before that reply reintroduces the distinction it exists to
 *       remove, and one after it cannot refuse anything.</li>
 * </ul>
 *
 * <p><b>Fails closed.</b> A network failure, a non-200, an unparseable body and
 * {@code success: false} are all refusals. The alternative - treating an unreachable Cloudflare as
 * a pass - turns a CAPTCHA into a formality the moment anything goes wrong, and an outage there is
 * precisely when an attacker is likely to be the cause.
 *
 * <p>Unconfigured is the one exception: {@link #isEnabled()} is false and {@link #verify} passes,
 * which is what lets a developer machine with no secret run the product. A boot log line names
 * which of the two states the process is in, so this cannot be a silent surprise.
 */
public class TurnstileVerifier {

    /** Cloudflare's verification endpoint. */
    static final String SITEVERIFY_URL =
        "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final String secretKey;

    /**
     * Where to POST. Overridable for tests ONLY, and package-private deliberately.
     *
     * <p>The same reasoning as {@code AuthHandler.auth0}: a destination production wiring could
     * override would be a way to send this secret somewhere else. It is also what keeps the test
     * off Cloudflare - no suite in this repository may call a metered third party, and a verifier
     * whose test called the real endpoint would need a real secret to do it.
     */
    private String endpoint = SITEVERIFY_URL;

    /** Tests only; see the field. */
    void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public TurnstileVerifier(String secretKey) {
        this.secretKey = secretKey == null || secretKey.isBlank() ? null : secretKey;
    }

    /** Whether a secret is configured. False means {@link #verify} passes everything. */
    public boolean isEnabled() { return secretKey != null; }

    /**
     * Succeeds when the token is good, fails with a 403 otherwise.
     *
     * @param token    the {@code cf-turnstile-response} the browser widget produced
     * @param remoteIp the caller's address, or null; Cloudflare treats it as optional
     */
    public Future<Void> verify(String token, String remoteIp) {
        if (!isEnabled()) return Future.succeededFuture();
        if (token == null || token.isBlank()) {
            return Future.failedFuture(ServiceException.forbidden("CAPTCHA verification required"));
        }

        StringBuilder form = new StringBuilder()
            .append("secret=").append(enc(secretKey))
            .append("&response=").append(enc(token));
        if (remoteIp != null && !remoteIp.isBlank()) {
            form.append("&remoteip=").append(enc(remoteIp));
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
            .build();

        return Future.fromCompletionStage(
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
            .otherwise(err -> null)
            .compose(response -> {
                if (response == null || response.statusCode() != 200) {
                    // Fails closed. See the class javadoc: an unreachable verifier is not a pass.
                    return Future.failedFuture(
                        ServiceException.forbidden("CAPTCHA verification unavailable"));
                }
                JsonObject parsed;
                try {
                    parsed = new JsonObject(response.body());
                } catch (RuntimeException notJson) {
                    return Future.failedFuture(
                        ServiceException.forbidden("CAPTCHA verification unavailable"));
                }
                if (Boolean.TRUE.equals(parsed.getBoolean("success"))) {
                    return Future.succeededFuture();
                }
                /*
                   The error codes are logged and NOT returned. Cloudflare's codes distinguish
                   "this token was already used" from "this token is for another site", and handing
                   that distinction to the caller tells somebody probing the endpoint which of their
                   attempts is closer to working.
                */
                System.err.println("Turnstile refused a token: "
                    + parsed.getJsonArray("error-codes", new io.vertx.core.json.JsonArray()));
                return Future.failedFuture(ServiceException.forbidden("CAPTCHA verification failed"));
            });
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

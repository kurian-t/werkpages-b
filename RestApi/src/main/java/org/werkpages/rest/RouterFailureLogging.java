package org.werkpages.rest;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;

/**
 * WHICH ROUTER FAILURES DESERVE A LOG LINE.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>An anonymous request to any admin endpoint is answered correctly with 401 - and used to write
 * a {@code SEVERE: Unhandled exception in router} line while doing it. Measured exactly: twelve
 * anonymous requests produced twelve lines; one more produced one more; a successful public
 * request produced none.
 *
 * <p>The 401 itself was never wrong. What was wrong is that refusing an unauthenticated caller is
 * the most ordinary thing an authorization layer does, and it was being reported at the severity
 * reserved for a broken server. These endpoints are probed continuously by scanners, so the log
 * fills with SEVERE entries that mean "someone tried the door" - and a SEVERE that usually means
 * nothing is a SEVERE nobody reads when it finally means something.
 *
 * <h2>Why it happened, which matters for not reintroducing it</h2>
 *
 * <p>The security handler runs on the sub-router returned by {@code routerFactory.getRouter()},
 * mounted at {@code /} on the main router. The main router's own failure handler - the one written
 * specifically to log something useful - therefore never saw these failures at all: 0 lines from
 * it against 13 from Vert.x's internal logger. A failure handler on the main router cannot cover
 * a sub-router's failures, so the handler has to be installed on <b>both</b>.
 *
 * <h2>What stays visible</h2>
 *
 * <p>Only an expected refusal is quiet, and "expected" is defined narrowly: a 401 or 403 whose
 * failure is absent or is an {@link HttpException} carrying that same status, which is exactly the
 * shape the security handler produces. A 401 arriving with any other throwable is a genuine
 * unexpected authentication failure and is logged in full - that case is the whole reason this is
 * a predicate rather than a blanket mute on 401.
 */
public final class RouterFailureLogging {

    private RouterFailureLogging() {}

    /**
     * True when this failure is the authorization layer doing its ordinary job.
     *
     * @param statusCode the status the router has settled on
     * @param failure    the failure attached to the context, which may be null
     */
    public static boolean isExpectedRefusal(int statusCode, Throwable failure) {
        if (statusCode != 401 && statusCode != 403) return false;
        if (failure == null) return true;
        // An HttpException whose status matches is the refusal itself, not something that broke
        // while producing it.
        return failure instanceof HttpException http && http.getStatusCode() == statusCode;
    }

    /**
     * Installs the failure logger on a router.
     *
     * <p>Never terminates the response: it calls {@link RoutingContext#next()} so whatever already
     * owns the response keeps owning it. This handler decides what is said, not what is sent - a
     * failure handler that ended the response here would change every error body in the
     * application.
     *
     * @param label names the router in the log, because "which router" was the missing detail that
     *              made the original bare line undebuggable
     */
    public static void install(Router router, String label) {
        router.route().failureHandler(ctx -> {
            int status = ctx.statusCode();
            Throwable failure = ctx.failure();

            if (!isExpectedRefusal(status, failure)) {
                System.err.println("⚠ Router failure [" + label + "] on "
                    + ctx.request().method() + " " + ctx.request().path()
                    + " (status " + status
                    + ", responseEnded=" + ctx.response().ended() + ")");
                if (failure != null) failure.printStackTrace(System.err);
            }

            ctx.next();
        });

        /*
          AND THE REFUSAL HAS TO BE *HANDLED*, not merely logged more quietly.

          Vert.x writes "SEVERE: Unhandled exception in router" precisely because nothing handled
          the failure: the 401 body was Vert.x's own default. A failure handler that logs and calls
          next() does not change that - the failure still reaches the end of the chain unhandled,
          and the line is still written. Registering an errorHandler for the status is what makes
          it handled, and it is the same mechanism this application already uses for 500.

          THE BODY IS REPRODUCED EXACTLY. It was measured off the running server before this
          change: status 401, body `Unauthorized`, 12 bytes, no Content-Type. Taking ownership of
          the response means owning that too, and an authorization refusal is not the place to
          quietly change an API contract the frontend may branch on.
        */
        router.errorHandler(401, ctx -> endWithDefault(ctx, 401, "Unauthorized"));
        router.errorHandler(403, ctx -> endWithDefault(ctx, 403, "Forbidden"));
    }

    private static void endWithDefault(RoutingContext ctx, int status, String body) {
        if (ctx.response().ended()) return;
        ctx.response().setStatusCode(status).end(body);
    }
}

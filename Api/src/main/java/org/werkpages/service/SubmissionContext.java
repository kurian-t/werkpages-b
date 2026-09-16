package org.werkpages.service;

import io.vertx.core.json.JsonObject;
import org.werkpages.repository.GeoObservation;

/**
 * What a write path knows about the submission that caused it, beyond the submission's own fields.
 *
 * <p>Two values that always travel together and must never be confused: what we observed about the
 * request, which is private, and what the person declared on the form, which can be public. Passing
 * them as one parameter keeps the pair intact through the several layers between a handler and an
 * INSERT — and keeps the signatures from growing a new argument every time the pair does.
 *
 * <p>They are separate <em>types</em> for the reason described on {@link DeclaredLocation}: there
 * is no conversion between them, so no amount of plumbing can turn a Cloudflare header into a
 * displayed value.
 */
public record SubmissionContext(GeoObservation observed, DeclaredLocation declared) {

    /** No request behind the call — tests, and anything invoked outside an HTTP context. */
    public static final SubmissionContext NONE =
        new SubmissionContext(GeoObservation.NONE, DeclaredLocation.NONE);

    /** Observed geo from the request, declared location from its body. */
    public static SubmissionContext of(GeoObservation observed, JsonObject body) {
        return new SubmissionContext(
            observed == null ? GeoObservation.NONE : observed,
            DeclaredLocation.fromBody(body));
    }

    /**
     * Keeps the observation and drops the declaration.
     *
     * <p>For the search paths. {@code /find} and anonymous capture must record where the request
     * came from and must never publish it, so they take the context and strip the half that could
     * become public — belt and braces behind the frontend simply not sending those keys.
     */
    public SubmissionContext withoutDeclared() {
        return new SubmissionContext(observed, DeclaredLocation.NONE);
    }
}

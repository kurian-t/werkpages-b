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
 *
 * <p>A third value rides along when the declaration names a building that is not in the database
 * yet: see {@link CorpusPlace}. It travels here rather than inside {@link DeclaredLocation} because
 * it is not a declaration — it is the raw material for one, consumed and discarded the moment the
 * location row exists.
 */
public record SubmissionContext(GeoObservation observed, DeclaredLocation declared,
                                CorpusPlace corpusPlace, String clientIp) {

    /** No request behind the call — tests, and anything invoked outside an HTTP context. */
    public static final SubmissionContext NONE =
        new SubmissionContext(GeoObservation.NONE, DeclaredLocation.NONE, null, null);

    /** Kept so existing callers that build a context directly do not all have to change. */
    public SubmissionContext(GeoObservation observed, DeclaredLocation declared) {
        this(observed, declared, null, null);
    }

    public SubmissionContext(GeoObservation observed, DeclaredLocation declared,
                             CorpusPlace corpusPlace) {
        this(observed, declared, corpusPlace, null);
    }

    /**
     * Observed geo, declared location, any chosen building, and the address behind the request.
     */
    public static SubmissionContext of(GeoObservation observed, JsonObject body, String clientIp) {
        return new SubmissionContext(
            observed == null ? GeoObservation.NONE : observed,
            DeclaredLocation.fromBody(body),
            CorpusPlace.fromBody(body),
            clientIp);
    }

    /** As above, for callers with no address to hand. */
    public static SubmissionContext of(GeoObservation observed, JsonObject body) {
        return new SubmissionContext(
            observed == null ? GeoObservation.NONE : observed,
            DeclaredLocation.fromBody(body),
            CorpusPlace.fromBody(body),
            null);
    }

    /**
     * Keeps the observation and drops the declaration.
     *
     * <p>For the search paths. {@code /find} and anonymous capture must record where the request
     * came from and must never publish it, so they take the context and strip the half that could
     * become public — belt and braces behind the frontend simply not sending those keys.
     *
     * <p>The chosen building goes too. It is part of the declaration in everything but type, and
     * leaving it behind would let a search path create a location row.
     */
    public SubmissionContext withoutDeclared() {
        return new SubmissionContext(observed, DeclaredLocation.NONE, null, clientIp);
    }
}

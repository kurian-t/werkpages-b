package org.werkpages.repository;

/**
 * Where a request appeared to come from, as Cloudflare reported it.
 *
 * <p>This is the <em>observed</em> half of the location model and is never shown to anybody. What a
 * person states on a form is the <em>declared</em> half, lives in the {@code declared_*} columns on
 * the contribution, and is a separate value even when the two happen to agree — a declared value
 * that started life as a prefill is still something the person saw and submitted.
 *
 * <p>Every field is nullable. Outside Cloudflare the headers are simply absent, and an observation
 * where all three are null is still worth recording: a request that reached the origin without them
 * is itself a thing worth being able to see later.
 */
public record GeoObservation(String country, String region, String city) {

    /** Nothing was observed — no Cloudflare headers on the request. */
    public static final GeoObservation NONE = new GeoObservation(null, null, null);

    public boolean isEmpty() {
        return country == null && region == null && city == null;
    }
}

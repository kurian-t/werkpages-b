package org.werkpages.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.werkpages.repository.GeoObservation;

import java.util.Locale;
import java.util.Set;

/**
 * Resolves visitor geo (country / state / city) from Cloudflare request headers.
 *
 * Requires Cloudflare "IP Geolocation" (CF-IPCountry) and the "Add visitor location
 * headers" managed transform (cf-region, cf-ipcity) to be enabled. Outside Cloudflare
 * (e.g. local dev) the headers are absent and every resolver returns null.
 */
public final class GeoUtils {

    private GeoUtils() {}

    static final String H_COUNTRY = "CF-IPCountry"; // ISO 3166-1 alpha-2 code, e.g. "US"
    static final String H_REGION  = "cf-region";    // full region/state name, e.g. "Ontario"
    static final String H_CITY    = "cf-ipcity";    // city name, e.g. "Toronto"

    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    /**
     * Maps a Cloudflare CF-IPCountry alpha-2 code to a display country name matching the
     * app's stored values (e.g. "US" → "United States", "CA" → "Canada"). Returns null for
     * blank input or any code that isn't a real ISO country — this covers Cloudflare's
     * pseudo-codes (XX = unknown, T1 = Tor) and anything else unrecognised.
     */
    static String countryName(String code) {
        if (code == null) return null;
        String c = code.trim().toUpperCase(Locale.ROOT);
        if (c.length() != 2 || !ISO_COUNTRIES.contains(c)) return null;
        String name = Locale.of("", c).getDisplayCountry(Locale.ENGLISH);
        if (name == null || name.isBlank() || name.equals(c)) return null;
        return name;
    }

    private static String header(RoutingContext ctx, String name) {
        String v = ctx.request().getHeader(name);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    static String country(RoutingContext ctx) { return countryName(header(ctx, H_COUNTRY)); }
    static String state(RoutingContext ctx)   { return header(ctx, H_REGION); }
    static String city(RoutingContext ctx)    { return header(ctx, H_CITY); }

    /**
     * What this request appeared to come from, for the private audit trail.
     *
     * <p>The one way observed location leaves this class. It is deliberately a distinct type from
     * anything on a request body: a value that reaches {@code geo_observations} must never also be
     * able to reach a {@code declared_*} column, and the only thing standing between those two is
     * that nothing converts between them.
     */
    static GeoObservation observed(RoutingContext ctx) {
        return new GeoObservation(country(ctx), state(ctx), city(ctx));
    }

    /** A {country, state, city} object built purely from the request's Cloudflare headers. */
    static JsonObject geoJson(RoutingContext ctx) {
        GeoObservation geo = observed(ctx);
        return new JsonObject()
            .put("country", geo.country())
            .put("state",   geo.region())
            .put("city",    geo.city());
    }

    /**
     * Fills country/state/city on the request body from Cloudflare headers, but only where
     * the client did not already supply a value (client-provided values — e.g. a user-edited
     * country/state in the Add Manager form — always win).
     */
    /**
     * Defaults the COUNTRY from the request, and nothing else.
     *
     * <h2>Why city and region are no longer filled</h2>
     *
     * <p>They were, and the result was a privacy failure. /find asks for no location, so a search
     * that created a ghost manager had its location invented from the SEARCHER: this method read
     * Cloudflare's city and region headers and wrote them into the body, where they were stored as
     * the manager's declared location and shown on the profile. Somebody looked up their boss and
     * saw their own city on the resulting page - which reads as the site tracking them, and is the
     * kind of thing that destroys trust in a product like this one.
     *
     * <p>A country is a defensible default: it is coarse, it is what the form now shows and lets
     * the person change, and getting it wrong is visible and correctable. A city is not - nobody
     * asked for it, nobody confirmed it, and it describes the reader rather than the manager.
     *
     * <p><b>The observed geography is not lost.</b> It still travels, in full, through
     * {@link #observed} into the submission context, which is where abuse and admin signals
     * belong. What changes is that it stops masquerading as something a human declared.
     *
     * <p>Fixing the caller alone could not work: with the body's city blank, this method put the
     * searcher's city straight back.
     */
    static void stampGeo(RoutingContext ctx, JsonObject body) {
        if (body == null) return;
        if (isBlank(body.getString("country"))) {
            String v = country(ctx);
            if (v != null) body.put("country", v);
        }
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}

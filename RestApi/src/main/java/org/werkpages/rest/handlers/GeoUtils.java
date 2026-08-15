package org.werkpages.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

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

    /** A {country, state, city} object built purely from the request's Cloudflare headers. */
    static JsonObject geoJson(RoutingContext ctx) {
        return new JsonObject()
            .put("country", country(ctx))
            .put("state",   state(ctx))
            .put("city",    city(ctx));
    }

    /**
     * Fills country/state/city on the request body from Cloudflare headers, but only where
     * the client did not already supply a value (client-provided values — e.g. a user-edited
     * country/state in the Add Manager form — always win).
     */
    static void stampGeo(RoutingContext ctx, JsonObject body) {
        if (body == null) return;
        if (isBlank(body.getString("country"))) {
            String v = country(ctx);
            if (v != null) body.put("country", v);
        }
        if (isBlank(body.getString("state"))) {
            String v = state(ctx);
            if (v != null) body.put("state", v);
        }
        if (isBlank(body.getString("city"))) {
            String v = city(ctx);
            if (v != null) body.put("city", v);
        }
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}

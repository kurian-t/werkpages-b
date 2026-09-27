package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Turns a company NAME into its real domain and logo, once.
 *
 * <p>Every company logo on this site was previously found by GUESSING a domain from the name -
 * "Zehrs Markets" becomes zehrsmarkets.com - and handing that guess to a logo provider. For any
 * company whose name is not its domain the guess is not merely a miss, it is a different
 * business: {@code companyLogoDomain("Lime")} yields {@code lime.com}, while Lime is
 * {@code li.me}. Users reported picking Lime from the dropdown and getting a stranger's mark,
 * repeatedly, because nothing in the system knew the real domain.
 *
 * <p>Brandfetch search answers exactly that question, and it answers it once. The result belongs
 * on the {@code companies} row - {@code domain} and {@code logo_url} are already there and mostly
 * empty - so that rendering a page makes NO third-party call at all. That is the other half of
 * why this exists: logo.dev's monthly allowance was spent by ordinary page views, because the
 * lookup happened in every visitor's browser on every render.
 *
 * <p>The client id is publishable by design - it is the same credential the browser bundle
 * carries - so it is read from the environment rather than the secret store.
 */
public class BrandfetchClient {

    private static final String SEARCH_URL = "https://api.brandfetch.io/v2/search/";

    /**
     * How good a match has to be before we store it.
     *
     * <p>Search always returns something; for an unknown company it returns the nearest famous
     * brand, and storing that would attach a real company's logo to somebody else's page - a
     * worse outcome than the letter initial we would otherwise show. Brandfetch's own
     * {@code qualityScore} is the filter, and the bar is deliberately high.
     */
    private static final double MIN_QUALITY = 0.70;

    /** What a resolved company actually is: a real domain, and a logo that loads. */
    public record Brand(String domain, String brandId, String iconUrl,
                        java.time.OffsetDateTime iconExpiresAt,
                        boolean verified, double quality) {}

    /**
     * Makes a Brandfetch icon URL FAIL when Brandfetch has no real logo.
     *
     * <p>Search hands back URLs ending {@code /fallback/lettermark/icon.webp}. For a brand it has
     * no mark for, that returns 200 and an image of a letter - Brandfetch's own, in its own font
     * and size. The page cannot tell that from a logo, so it renders happily and sits beside the
     * real ones looking conspicuously different. A company called "Ju" showed a large grey J next
     * to Discord's actual icon.
     *
     * <p>Asking for {@code /fallback/404} instead makes the miss a real miss: 404, zero bytes,
     * the image errors, and the chain falls through to OUR letter tile - one font, one size,
     * every time. Verified not to affect brands that do have a logo.
     */
    static String failOnMissing(String iconUrl) {
        if (iconUrl == null) return null;
        return iconUrl.replace("/fallback/lettermark/", "/fallback/404/");
    }

    /**
     * Brandfetch signs icon URLs with an expiry embedded in the {@code c=} token.
     *
     * <p>Measured rather than assumed: a URL captured at 02:00 carried {@code 1790560714158},
     * which is 2026-09-28T01:58Z - about 24 hours out - and the URL still served a 200 well
     * inside that window. So the asset is a cache with a shelf life, and the expiry has to
     * travel with it or a refresh cannot know when to run.
     *
     * <p>Returns null when no timestamp can be read, which callers treat as "refresh next pass"
     * rather than "never expires" - the safe direction to be wrong in.
     */
    static java.time.OffsetDateTime expiryOf(String iconUrl) {
        if (iconUrl == null) return null;
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("(\\d{13})").matcher(iconUrl);
        if (!m.find()) return null;
        try {
            long ms = Long.parseLong(m.group(1));
            return java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private final HttpClient httpClient;
    private final String     clientId;
    private final Vertx      vertx;

    public BrandfetchClient(Vertx vertx, String clientId) {
        this.vertx      = vertx;
        this.clientId   = clientId;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Whether this client can do anything.
     *
     * <p>False without a client id, and false whenever {@code resolver.network=off} - the same
     * kill switch DomainResolver honours, set by surefire and failsafe. Brandfetch Search is
     * METERED, and a test that spends a search hit is how logo.dev's monthly allowance was
     * exhausted in a single day, taking every logo on production down to letter tiles.
     */
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && DomainResolver.networkEnabled();
    }

    /**
     * The best brand match for a company name, or empty when there is no confident one.
     *
     * <p>Never fails the caller: a logo is cosmetic, and a company must still be created when
     * Brandfetch is down, rate-limited or misconfigured. Every failure resolves to empty.
     */
    public Future<Optional<Brand>> resolve(String companyName) {
        if (!isConfigured() || companyName == null || companyName.isBlank()) {
            return Future.succeededFuture(Optional.empty());
        }

        String url = SEARCH_URL
            + URLEncoder.encode(companyName.trim(), StandardCharsets.UTF_8)
            + "?c=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("accept", "application/json")
            .GET()
            .build();

        Promise<Optional<Brand>> promise = Promise.promise();
        vertx.executeBlocking(() -> {
            HttpResponse<String> res =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return Optional.<Brand>empty();
            return bestMatch(new JsonArray(res.body()));
        }).onSuccess(promise::complete)
          .onFailure(err -> {
              // Cosmetic data. Log and carry on rather than failing a company write.
              System.err.println("Brandfetch lookup failed for '" + companyName + "': "
                                 + err.getMessage());
              promise.complete(Optional.empty());
          });
        return promise.future();
    }

    /**
     * The first result good enough to trust.
     *
     * <p>Results arrive best-first, but "best" is relative to the query - so the score is checked
     * rather than assumed. A verified brand clears the bar on its own; an unverified one has to
     * earn it.
     */
    private static Optional<Brand> bestMatch(JsonArray results) {
        for (int i = 0; i < results.size(); i++) {
            JsonObject r = results.getJsonObject(i);
            String domain = r.getString("domain");
            String icon   = r.getString("icon");
            if (domain == null || domain.isBlank()) continue;

            boolean verified = Boolean.TRUE.equals(r.getBoolean("verified"));
            double quality   = r.getDouble("qualityScore", 0.0);
            if (!verified && quality < MIN_QUALITY) continue;

            return Optional.of(new Brand(domain, r.getString("brandId"), failOnMissing(icon),
                                         expiryOf(icon), verified, quality));
        }
        return Optional.empty();
    }
}

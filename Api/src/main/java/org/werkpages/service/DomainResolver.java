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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Establishes what a company actually IS, once, so that no logo provider is ever handed a guess.
 *
 * <p>Logos used to be found by guessing a domain from the company name - "Zehrs Markets" becomes
 * zehrsmarkets.com - in every visitor's browser, on every render. Measured across twelve
 * companies, seven guesses were wrong. The 404s were harmless; the successes were not. "Lime"
 * guesses lime.com, a real and unrelated company, so the page served a polished authoritative
 * logo for the wrong business.
 *
 * <h2>Why three resolvers and not the best one</h2>
 *
 * There is no best one. Measured on the same companies:
 *
 * <pre>
 *   Lime            Clearbit limelush.com  WRONG   Brandfetch li.me            RIGHT
 *   Zehrs Markets   Clearbit zehrs.ca      RIGHT   Brandfetch zehrsauto.com    WRONG
 *   Revvity         Clearbit chemagen.com  WRONG   Brandfetch revvity.com      RIGHT
 * </pre>
 *
 * Where two agreed - University of Waterloo, Real Canadian Superstore - both were right. So
 * AGREEMENT is the signal, not any single provider's self-reported confidence.
 *
 * <p>Confidence in particular must not be mistaken for correctness of IDENTITY: Brandfetch
 * returns {@code google.com} for "Google DeepMind" with a score of 1.00. That is a real domain,
 * correctly matched, and the wrong entity. No threshold catches it; only a human does.
 *
 * <p>So disagreement is never settled by majority or by score. It goes to review.
 */
public class DomainResolver {

    /** Which resolver said what. Names match the {@code company_domain_candidates.resolver} check. */
    public enum Source { LOGODEV, BRANDFETCH, CLEARBIT, GUESS_VERIFIED }

    /** One resolver's answer for one company. */
    public record Candidate(Source source, String domain, String brandId,
                            String iconUrl, Double confidence) {}

    /** What the whole panel concluded. */
    public record Resolution(Outcome outcome, String domain, String domainSource,
                             List<Candidate> candidates) {

        /** The Brandfetch candidate, when there is one - the only source of a usable icon URL. */
        public Optional<Candidate> brandfetch() {
            return candidates.stream().filter(c -> c.source() == Source.BRANDFETCH).findFirst();
        }

        /** Whether this resolution should be written without a human looking at it. */
        public boolean isAutomatic() {
            return outcome == Outcome.CONSENSUS;
        }
    }

    /**
     * How long before a Brandfetch icon URL expires we stop trusting it.
     *
     * <p>Their signature carries roughly a 24h expiry - measured, not assumed. Handing a browser
     * a URL that dies in four minutes wastes the render and poisons any cache that stored it, so
     * a URL inside this margin is treated as already stale and refreshed instead.
     */
    public static final Duration ICON_REFRESH_MARGIN = Duration.ofHours(1);

    /**
     * Kill switch for every outbound resolver call. Set {@code -Dresolver.network=off} and this
     * class will not touch the network, whatever it is asked to do.
     *
     * <p>Not a convenience. Logo.dev Search and Brandfetch Search are METERED, and Logo.dev's
     * monthly allowance was once exhausted in a single day by test runs hitting a live logo
     * endpoint - which took every logo on the production site down to letter tiles for days,
     * silently, because nothing failed.
     *
     * <p>Clearbit is the reason a credential check is not enough on its own: it needs no
     * credential, so "no key configured" would still have let a test call it.
     *
     * <p>The build sets this off for surefire and failsafe, so a test cannot spend a search hit
     * even by constructing a fully configured resolver and calling it.
     */
    public static boolean networkEnabled() {
        return !"off".equalsIgnoreCase(System.getProperty("resolver.network", "on"));
    }

    /**
     * How sure Brandfetch must be about a guessed domain for it to resolve on its own.
     *
     * <p>Short names are the danger: "sd" echoes sd.com at 0.33 and "un" echoes un.com at 0.29,
     * because those are real registered domains somebody has heard of - not because they are the
     * company being described.
     */
    static final double GUESS_MIN_CONFIDENCE = 0.70;

    public enum Outcome {
        /** Two or more resolvers returned the same domain. Stored automatically. */
        CONSENSUS,
        /** Exactly one answer, or several that disagree. A human decides. */
        NEEDS_REVIEW,
        /** Nobody recognised this company. */
        UNRESOLVED
    }

    private static final String LOGODEV_SEARCH    = "https://api.logo.dev/search?q=";
    private static final String BRANDFETCH_SEARCH = "https://api.brandfetch.io/v2/search/";
    private static final String CLEARBIT_SEARCH   =
        "https://autocomplete.clearbit.com/v1/companies/suggest?query=";

    private final HttpClient httpClient;
    private final Vertx      vertx;
    private final String     logoDevSecretKey;
    private final String     brandfetchClientId;

    /**
     * @param logoDevSecretKey   Logo.dev SEARCH requires the secret key, not the publishable
     *                           token the browser bundle carries - so this resolver is
     *                           server-side by necessity as well as by design. Null disables
     *                           that resolver; the others still vote.
     * @param brandfetchClientId publishable, the same value the bundle holds.
     */
    public DomainResolver(Vertx vertx, String logoDevSecretKey, String brandfetchClientId) {
        this.vertx              = vertx;
        this.logoDevSecretKey   = logoDevSecretKey;
        this.brandfetchClientId = brandfetchClientId;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /** True when at least two resolvers can run - below that, consensus is impossible. */
    public boolean canReachConsensus() {
        int available = 1; // Clearbit needs no credential.
        if (logoDevSecretKey   != null && !logoDevSecretKey.isBlank())   available++;
        if (brandfetchClientId != null && !brandfetchClientId.isBlank()) available++;
        return available >= 2;
    }

    /**
     * Asks every configured resolver, then compares.
     *
     * <p>Never fails: a resolver that errors, times out or is rate-limited simply casts no vote.
     * Identity resolution is a background improvement, and it must not be able to break the
     * caller that triggered it.
     */
    public Future<Resolution> resolve(String companyName) {
        return resolve(companyName, null);
    }

    /**
     * As above, counting a domain we already hold as one of the observations.
     *
     * <p>Every existing {@code companies.domain} is traceable to the Clearbit-backed company
     * picker, so it is not a guess to be discarded - it is a Clearbit answer we already paid for,
     * and it votes. University of Waterloo is already {@code uwaterloo.ca} from Clearbit; with
     * Logo.dev and Brandfetch both returning the same, that is a three-way agreement rather than
     * a two-way one. Lime is already {@code limelush.com} from Clearbit, and is outvoted 2-1 by
     * {@code li.me} - which is the correct outcome, arrived at without anybody special-casing it.
     *
     * <p>Passing it also saves the live Clearbit call: it is the same resolver answering the same
     * question, so asking twice would double-count one opinion into a false consensus.
     *
     * @param existingClearbitDomain a stored domain whose source is CLEARBIT, or null
     */
    public Future<Resolution> resolve(String companyName, String existingClearbitDomain) {
        if (companyName == null || companyName.isBlank()) {
            return Future.succeededFuture(
                new Resolution(Outcome.UNRESOLVED, null, null, List.of()));
        }
        if (!networkEnabled()) {
            // Explicitly disabled - see networkEnabled(). Never silently "resolves" to something.
            return Future.succeededFuture(
                new Resolution(Outcome.UNRESOLVED, null, null, List.of()));
        }
        String stored = normalize(existingClearbitDomain);
        Future<Optional<Candidate>> clearbitVote = stored != null
            ? Future.succeededFuture(Optional.of(
                  new Candidate(Source.CLEARBIT, stored, null, null, null)))
            : clearbit(companyName);

        return Future.join(
                logoDev(companyName), brandfetch(companyName), clearbitVote,
                verifiedGuess(companyName))
            .map(cf -> {
                List<Candidate> found = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    Optional<Candidate> c = cf.resultAt(i);
                    c.ifPresent(found::add);
                }
                return decide(found);
            })
            // Future.join fails if ANY future fails; each already recovers, but belt and braces.
            .otherwise(err -> new Resolution(Outcome.UNRESOLVED, null, null, List.of()));
    }

    /**
     * Consensus, or a human.
     *
     * <p>Domains are compared after normalisation so {@code WWW.UWaterloo.CA} and
     * {@code uwaterloo.ca} are recognised as the same answer rather than as a disagreement.
     */
    public static Resolution decide(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return new Resolution(Outcome.UNRESOLVED, null, null, List.of());
        }
        Map<String, List<Candidate>> byDomain = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            String key = normalize(c.domain());
            if (key == null) continue;
            byDomain.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }
        if (byDomain.isEmpty()) {
            return new Resolution(Outcome.UNRESOLVED, null, null, candidates);
        }
        for (Map.Entry<String, List<Candidate>> e : byDomain.entrySet()) {
            if (e.getValue().size() >= 2) {
                return new Resolution(Outcome.CONSENSUS, e.getKey(),
                                      "RESOLVER_CONSENSUS", candidates);
            }
        }
        /*
          A verified guess stands alone, because it is not an opinion about a name - it is the
          provider confirming that a specific domain is a real brand's CANONICAL domain.

          Searching the name "Mi" returns Microsoft; searching the domain mi.com returns Xiaomi,
          whose canonical domain is mi.com. The second is checkable and the first is a guess about
          intent, which is why this is allowed to resolve on its own while a lone name-match is
          not.

          It is still narrow: the echo has to be exact. lime.com returns Lime with a canonical
          domain of lime.bike, so it does NOT verify - which is the bug this whole effort began
          with, correctly rejected.
        */
        for (Candidate c : candidates) {
            if (c.source() != Source.GUESS_VERIFIED) continue;
            String guess = normalize(c.domain());
            if (guess == null) continue;

            /*
              A verified guess stands alone only when nothing CONTRADICTS it.

              Measured: "Li" guesses li.com, which is a real domain Brandfetch will describe - but
              Brandfetch searching the NAME returns lixiang.com and Clearbit returns linkedin.com.
              Both disagree, and an earlier version let the guess win anyway on a confidence of
              0.69. "un" won on 0.29 against underarmour.com and united.com. Two real answers
              disagreeing with a guess is evidence against the guess, not for it.

              So: uncontradicted, and confident enough to be worth trusting on its own.
            */
            boolean contradicted = candidates.stream()
                .filter(o -> o.source() != Source.GUESS_VERIFIED)
                .map(o -> normalize(o.domain()))
                .filter(java.util.Objects::nonNull)
                .anyMatch(d -> !d.equals(guess));
            boolean confident = c.confidence() != null && c.confidence() >= GUESS_MIN_CONFIDENCE;

            if (!contradicted && confident) {
                return new Resolution(Outcome.CONSENSUS, guess, "GUESS_VERIFIED", candidates);
            }
        }
        // One answer, or several that disagree. Both are a question for a person: a lone answer
        // has nothing corroborating it, and a disagreement has no honest tie-break.
        return new Resolution(Outcome.NEEDS_REVIEW, null, null, candidates);
    }

    /** Lowercase, trimmed, no leading www. Null for anything that is not a domain. */
    public static String normalize(String domain) {
        if (domain == null) return null;
        String d = domain.trim().toLowerCase();
        if (d.startsWith("www.")) d = d.substring(4);
        return d.isBlank() || !d.contains(".") ? null : d;
    }

    // ── The three resolvers ───────────────────────────────────────────────────

    private Future<Optional<Candidate>> logoDev(String name) {
        if (logoDevSecretKey == null || logoDevSecretKey.isBlank()) {
            return Future.succeededFuture(Optional.empty());
        }
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(LOGODEV_SEARCH + enc(name)))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + logoDevSecretKey)
            .header("accept", "application/json")
            .GET().build();

        return send(req, body -> {
            JsonArray arr = new JsonArray(body);
            if (arr.isEmpty()) return Optional.empty();
            JsonObject top = arr.getJsonObject(0);
            String domain = top.getString("domain");
            if (domain == null || domain.isBlank()) return Optional.empty();
            return Optional.of(new Candidate(Source.LOGODEV, domain, null,
                                             top.getString("logo_url"), null));
        }, "Logo.dev", name);
    }

    private Future<Optional<Candidate>> brandfetch(String name) {
        if (brandfetchClientId == null || brandfetchClientId.isBlank()) {
            return Future.succeededFuture(Optional.empty());
        }
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BRANDFETCH_SEARCH + enc(name) + "?c=" + enc(brandfetchClientId)))
            .timeout(Duration.ofSeconds(10))
            .header("accept", "application/json")
            .GET().build();

        return send(req, body -> {
            JsonArray arr = new JsonArray(body);
            if (arr.isEmpty()) return Optional.empty();
            JsonObject top = arr.getJsonObject(0);
            String domain = top.getString("domain");
            if (domain == null || domain.isBlank()) return Optional.empty();
            return Optional.of(new Candidate(Source.BRANDFETCH, domain,
                                             top.getString("brandId"),
                                             BrandfetchClient.failOnMissing(top.getString("icon")),
                                             top.getDouble("qualityScore")));
        }, "Brandfetch", name);
    }

    /**
     * Asks whether the domain we would have GUESSED is a real brand's canonical domain.
     *
     * <p>Only an exact echo counts. Searching {@code mi.com} returns Xiaomi whose canonical
     * domain is {@code mi.com} - verified. Searching {@code lime.com} returns Lime whose
     * canonical domain is {@code lime.bike} - not verified, and correctly refused, since
     * lime.com belongs to somebody else entirely.
     */
    private Future<Optional<Candidate>> verifiedGuess(String name) {
        if (brandfetchClientId == null || brandfetchClientId.isBlank()) {
            return Future.succeededFuture(Optional.empty());
        }
        String guess = guessDomain(name);
        if (guess == null) return Future.succeededFuture(Optional.empty());

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BRANDFETCH_SEARCH + enc(guess) + "?c=" + enc(brandfetchClientId)))
            .timeout(Duration.ofSeconds(10))
            .header("accept", "application/json")
            .GET().build();

        return send(req, body -> {
            JsonArray arr = new JsonArray(body);
            if (arr.isEmpty()) return Optional.empty();
            JsonObject top = arr.getJsonObject(0);
            String returned = normalize(top.getString("domain"));
            if (returned == null || !returned.equals(guess)) return Optional.empty();
            return Optional.of(new Candidate(Source.GUESS_VERIFIED, returned,
                                             top.getString("brandId"), BrandfetchClient.failOnMissing(top.getString("icon")),
                                             top.getDouble("qualityScore")));
        }, "Brandfetch(guess)", guess);
    }

    /** The domain the old renderer would have invented, mirroring client/lib/utils.ts. */
    static String guessDomain(String company) {
        if (company == null || company.isBlank()) return null;
        String cleaned = company.trim(), prev;
        java.util.regex.Pattern suffix = java.util.regex.Pattern.compile(
            "[\\s,]+(?:inc\\.?|incorporated|corp\\.?|corporation|llc\\.?|ltd\\.?|limited|co\\.?"
          + "|plc\\.?|lp\\.?|companies|company|group|holdings|enterprises|international"
          + "|worldwide)\\.?$", java.util.regex.Pattern.CASE_INSENSITIVE);
        do { prev = cleaned; cleaned = suffix.matcher(cleaned).replaceAll("").trim(); }
        while (!cleaned.equals(prev));

        String low = cleaned.toLowerCase();
        if (low.matches("^[a-z0-9][a-z0-9-]*\\.[a-z]{2,}(\\.[a-z]{2,})?$")) return low;
        String stem = low.replaceAll("\\s+", "").replaceAll("[^a-z0-9]", "");
        return stem.isEmpty() ? null : stem + ".com";
    }

    private Future<Optional<Candidate>> clearbit(String name) {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(CLEARBIT_SEARCH + enc(name)))
            .timeout(Duration.ofSeconds(10))
            .header("accept", "application/json")
            .GET().build();

        return send(req, body -> {
            JsonArray arr = new JsonArray(body);
            if (arr.isEmpty()) return Optional.empty();
            JsonObject top = arr.getJsonObject(0);
            String domain = top.getString("domain");
            if (domain == null || domain.isBlank()) return Optional.empty();
            return Optional.of(new Candidate(Source.CLEARBIT, domain, null,
                                             top.getString("logo"), null));
        }, "Clearbit", name);
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────

    private interface Parser {
        Optional<Candidate> parse(String body) throws Exception;
    }

    /** Sends on a worker thread and turns every failure into "no vote". */
    private Future<Optional<Candidate>> send(HttpRequest req, Parser parser,
                                             String who, String name) {
        Promise<Optional<Candidate>> promise = Promise.promise();
        vertx.executeBlocking(() -> {
            HttpResponse<String> res =
                httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                System.err.println(who + " resolve '" + name + "' -> HTTP " + res.statusCode());
                return Optional.<Candidate>empty();
            }
            return parser.parse(res.body());
        }).onSuccess(promise::complete)
          .onFailure(err -> {
              System.err.println(who + " resolve '" + name + "' failed: " + err.getMessage());
              promise.complete(Optional.empty());
          });
        return promise.future();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s.trim(), StandardCharsets.UTF_8);
    }
}

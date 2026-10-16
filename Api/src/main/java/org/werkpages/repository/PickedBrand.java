package org.werkpages.repository;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A company identity a CLIENT claims was chosen from the picker, after validation.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The company dropdown resolves a real domain and shows the logo built from it. That pick used
 * to be thrown away in the browser - the payload carried only the name and the id - so the logo
 * somebody chose never reached the server, and the profile went on showing whatever the first
 * person to type that company name produced. Carrying the pick fixes that, and immediately raises
 * the question this class answers: the pick now arrives in a request body, and a request body is
 * not evidence (CLAUDE.md section 24).
 *
 * <h2>What is trusted, and what is rebuilt</h2>
 *
 * <p>The DOMAIN is the only thing taken from the client, and only after it is checked to be a
 * syntactically real hostname. The logo URL is never trusted as a string: it is kept only when it
 * structurally encodes that same validated domain, which is what makes it impossible to store a
 * logo belonging to one company beside the domain of another. A URL that does not match is
 * dropped rather than rejected - the domain alone renders correctly, because the reader derives
 * the logo from the domain when no stored URL is usable.
 *
 * <p>So the two fields can never disagree. That mattered enough to the company that wrote
 * {@code storeResolvedBrand} to say so in its comment, and then that method was never called.
 */
public record PickedBrand(String domain, String logoUrl) {

    /**
     * A hostname, conservatively.
     *
     * <p>At least one dot, labels of letters, digits and hyphens that neither start nor end with a
     * hyphen. "li.me" passes, which is the point: the real Lime domain is the case that proves a
     * guessed "lime.com" was wrong, and a validator that assumed .com would have rejected it.
     */
    private static final Pattern HOST = Pattern.compile(
        "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");

    /** logo.dev, exactly as the picker builds it: the domain is the whole path. */
    private static final Pattern LOGO_DEV = Pattern.compile(
        "^https://img\\.logo\\.dev/([^/?#]+)(\\?.*)?$");

    /** Brandfetch's CDN form, where the domain is the first path segment. */
    private static final Pattern BRANDFETCH = Pattern.compile(
        "^https://cdn\\.brandfetch\\.io/([^/?#]+)/.*$");

    /**
     * The validated pick, or empty when the client sent nothing usable.
     *
     * <p>Empty is not an error and must not fail a submission: somebody typing a company name
     * freely has no pick to send, and that is the ordinary case.
     */
    public static Optional<PickedBrand> of(String domain, String logoUrl) {
        if (domain == null) return Optional.empty();
        String host = domain.trim().toLowerCase();
        if (host.isEmpty() || host.length() > 253 || !HOST.matcher(host).matches()) {
            return Optional.empty();
        }
        return Optional.of(new PickedBrand(host, urlForOrNull(host, logoUrl)));
    }

    /** The client's URL when it demonstrably belongs to this domain, otherwise null. */
    private static String urlForOrNull(String host, String logoUrl) {
        if (logoUrl == null || logoUrl.isBlank() || logoUrl.length() > 500) return null;
        String url = logoUrl.trim();
        String embedded = embeddedDomain(url);
        return embedded != null && embedded.equals(host) ? url : null;
    }

    /** The domain a logo URL is built from, or null when it is not a form we recognise. */
    public static String embeddedDomain(String url) {
        var logoDev = LOGO_DEV.matcher(url);
        if (logoDev.matches()) return logoDev.group(1).toLowerCase();
        var brandfetch = BRANDFETCH.matcher(url);
        if (brandfetch.matches()) return brandfetch.group(1).toLowerCase();
        return null;
    }
}

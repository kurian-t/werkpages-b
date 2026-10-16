package org.werkpages.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A company identity arriving from a client is evidence of nothing until it is checked.
 *
 * <p>The picker resolves a real domain and shows the logo built from it, and that pick now travels
 * to the server so the company's identity can be corrected. Which means a request body can now
 * propose a company's logo, and CLAUDE.md section 24 is explicit that a client's claim is not
 * authority.
 */
class PickedBrandTest {

    @Test
    @DisplayName("a real domain is accepted, including one that is not a .com")
    void realDomainsPass() {
        /*
          li.me is the case the whole logo effort turns on: "Lime" GUESSES lime.com, a real and
          unrelated company, so a validator that assumed .com would reject the correct answer and
          keep the wrong one.
        */
        assertTrue(PickedBrand.of("li.me", null).isPresent());
        assertTrue(PickedBrand.of("amazon.com", null).isPresent());
        assertTrue(PickedBrand.of("sub.domain.co.uk", null).isPresent());
        assertEquals("li.me", PickedBrand.of("  LI.ME  ", null).orElseThrow().domain());
    }

    @Test
    @DisplayName("nonsense is refused rather than stored")
    void nonsenseIsRefused() {
        assertTrue(PickedBrand.of(null, null).isEmpty());
        assertTrue(PickedBrand.of("", null).isEmpty());
        assertTrue(PickedBrand.of("not a domain", null).isEmpty());
        assertTrue(PickedBrand.of("nodot", null).isEmpty());
        assertTrue(PickedBrand.of("-leading.hyphen.com", null).isEmpty());
        assertTrue(PickedBrand.of("javascript:alert(1)", null).isEmpty());
        assertTrue(PickedBrand.of("a".repeat(300) + ".com", null).isEmpty());
    }

    @Test
    @DisplayName("a logo url is kept only when it encodes the SAME domain")
    void logoMustMatchItsDomain() {
        /*
          The defect this prevents: a logo belonging to one company stored beside the domain of
          another. That is precisely the shape of the original bug - a guessed domain handed to a
          logo provider, returning a stranger's mark - and it must not be reachable by posting it.
        */
        var ok = PickedBrand.of("li.me", "https://img.logo.dev/li.me?token=pk_x").orElseThrow();
        assertEquals("https://img.logo.dev/li.me?token=pk_x", ok.logoUrl());

        var mismatched = PickedBrand.of("li.me", "https://img.logo.dev/lime.com?token=pk_x")
                                    .orElseThrow();
        assertNull(mismatched.logoUrl(), "a url for a different company is dropped, not stored");
        assertEquals("li.me", mismatched.domain(), "the domain still stands on its own");
    }

    @Test
    @DisplayName("a url from somewhere we do not recognise is never stored")
    void foreignUrlsAreDropped() {
        // Without this, any string could be written into a column that every page renders.
        assertNull(PickedBrand.of("li.me", "https://evil.example/pwn.png").orElseThrow().logoUrl());
        assertNull(PickedBrand.of("li.me", "javascript:alert(1)").orElseThrow().logoUrl());
        assertNull(PickedBrand.of("li.me", "data:image/png;base64,AAA").orElseThrow().logoUrl());
    }

    @Test
    @DisplayName("brandfetch urls are recognised, with the domain in the path")
    void brandfetchIsUnderstood() {
        var p = PickedBrand.of("li.me",
            "https://cdn.brandfetch.io/li.me/w/128/h/128/fallback/404/icon.webp").orElseThrow();
        assertNotNull(p.logoUrl());
        assertNull(PickedBrand.of("li.me",
            "https://cdn.brandfetch.io/lime.com/w/128/h/128/icon.webp").orElseThrow().logoUrl());
    }
}

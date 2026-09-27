package org.werkpages.unit;

import org.junit.jupiter.api.Test;
import org.werkpages.service.DomainResolver;
import org.werkpages.service.DomainResolver.Candidate;
import org.werkpages.service.DomainResolver.Outcome;
import org.werkpages.service.DomainResolver.Resolution;
import org.werkpages.service.DomainResolver.Source;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When resolvers agree, and what happens when they do not.
 *
 * <p>Logos were found by guessing a domain from the company name. Seven of twelve guesses were
 * wrong, and the wrong ones that SUCCEEDED were the dangerous kind: "Lime" guesses lime.com, a
 * real and unrelated company, so the site rendered an authoritative logo for the wrong business.
 *
 * <p>These are the rules that replace the guess. No network, no database - the decision is pure
 * so it can be pinned exactly.
 */
class DomainResolverTest {

    private static Candidate c(Source s, String domain) {
        return new Candidate(s, domain, null, null, null);
    }

    private static Candidate c(Source s, String domain, double confidence) {
        return new Candidate(s, domain, null, null, confidence);
    }

    // ── Agreement ─────────────────────────────────────────────────────────────

    @Test
    void twoResolversAgreeing_isAccepted() {
        Resolution r = DomainResolver.decide(List.of(
            c(Source.LOGODEV,    "uwaterloo.ca"),
            c(Source.BRANDFETCH, "uwaterloo.ca")));

        assertEquals(Outcome.CONSENSUS, r.outcome());
        assertEquals("uwaterloo.ca", r.domain());
        assertEquals("RESOLVER_CONSENSUS", r.domainSource());
        assertTrue(r.isAutomatic());
    }

    @Test
    void theStoredClearbitDomain_countsAsAVote() {
        /*
          University of Waterloo already holds uwaterloo.ca from the Clearbit-backed picker. That
          is not a guess to discard - it is an observation, and with the other two agreeing it
          makes a three-way agreement.
        */
        Resolution r = DomainResolver.decide(List.of(
            c(Source.CLEARBIT,   "uwaterloo.ca"),
            c(Source.LOGODEV,    "uwaterloo.ca"),
            c(Source.BRANDFETCH, "uwaterloo.ca")));

        assertEquals(Outcome.CONSENSUS, r.outcome());
        assertEquals("uwaterloo.ca", r.domain());
    }

    @Test
    void aWrongStoredDomain_isOutvoted() {
        /*
          The Lime case, end to end. Clearbit stored limelush.com - a real company, and not this
          one. Logo.dev and Brandfetch both say li.me, so the stored value loses 2-1 and is
          replaced, without anybody special-casing Lime.
        */
        Resolution r = DomainResolver.decide(List.of(
            c(Source.CLEARBIT,   "limelush.com"),
            c(Source.LOGODEV,    "li.me"),
            c(Source.BRANDFETCH, "li.me")));

        assertEquals(Outcome.CONSENSUS, r.outcome());
        assertEquals("li.me", r.domain(), "the agreed domain wins, not the stored one");
    }

    @Test
    void agreementSurvivesFormatting() {
        /* WWW and casing are not a disagreement. */
        Resolution r = DomainResolver.decide(List.of(
            c(Source.LOGODEV,    "WWW.UWaterloo.CA"),
            c(Source.BRANDFETCH, "uwaterloo.ca")));

        assertEquals(Outcome.CONSENSUS, r.outcome());
        assertEquals("uwaterloo.ca", r.domain());
    }

    // ── Disagreement goes to a human ──────────────────────────────────────────

    @Test
    void aLoneAnswer_isNotEnough() {
        /* Nothing corroborates it. One resolver being confident is not agreement. */
        Resolution r = DomainResolver.decide(List.of(c(Source.BRANDFETCH, "revvity.com")));

        assertEquals(Outcome.NEEDS_REVIEW, r.outcome());
        assertNull(r.domain(), "nothing is stored until somebody agrees or a human decides");
        assertFalse(r.isAutomatic());
    }

    @Test
    void highConfidenceOnItsOwn_neverAutoAccepts() {
        /*
          THE case this rule exists for. Brandfetch returns google.com for "Google DeepMind" with
          a quality score of 1.00. The domain is real, the match is confident, and the IDENTITY is
          wrong - DeepMind is not Google's homepage. No threshold can catch that, so a single
          answer never writes itself in however sure it sounds.
        */
        Resolution r = DomainResolver.decide(List.of(c(Source.BRANDFETCH, "google.com", 1.00)));

        assertEquals(Outcome.NEEDS_REVIEW, r.outcome(),
            "a perfect score is not agreement, and is not a human");
        assertNull(r.domain());
    }

    @Test
    void threeDifferentAnswers_goToReview() {
        /* CENX: nobody agrees with anybody. Picking one would be arbitrary. */
        Resolution r = DomainResolver.decide(List.of(
            c(Source.CLEARBIT,   "cenxus.co.jp"),
            c(Source.LOGODEV,    "cenx.io"),
            c(Source.BRANDFETCH, "globalcenx.com")));

        assertEquals(Outcome.NEEDS_REVIEW, r.outcome());
        assertNull(r.domain());
        assertEquals(3, r.candidates().size(), "all three are kept as evidence for the reviewer");
    }

    // ── Nothing at all ────────────────────────────────────────────────────────

    @Test
    void noAnswers_isUnresolved() {
        Resolution r = DomainResolver.decide(List.of());
        assertEquals(Outcome.UNRESOLVED, r.outcome());
        assertNull(r.domain());
    }

    @Test
    void answersWithoutADomain_areNotAnswers() {
        Resolution r = DomainResolver.decide(List.of(
            c(Source.CLEARBIT, null), c(Source.LOGODEV, "  "), c(Source.BRANDFETCH, "notadomain")));
        assertEquals(Outcome.UNRESOLVED, r.outcome());
    }

    // ── Normalisation ─────────────────────────────────────────────────────────

    @Test
    void normalisationStripsWwwAndCase_andRejectsNonDomains() {
        assertEquals("uwaterloo.ca", DomainResolver.normalize(" WWW.UWaterloo.CA "));
        assertEquals("li.me",        DomainResolver.normalize("li.me"));
        assertNull(DomainResolver.normalize("no-dot-here"));
        assertNull(DomainResolver.normalize(null));
        assertNull(DomainResolver.normalize("   "));
    }

    @Test
    void theRefreshMarginIsGenerous() {
        /*
          Brandfetch signs icon URLs with a ~24h expiry. Handing a browser one that dies in four
          minutes wastes the render and poisons whatever cached it, so anything inside the margin
          is treated as already stale.
        */
        assertTrue(DomainResolver.ICON_REFRESH_MARGIN.toMinutes() >= 60,
            "too tight a margin serves URLs that expire mid-cache");
    }

    // ── No test may spend a metered search hit ────────────────────────────────

    @Test
    void theResolverCannotReachTheNetworkFromATest() {
        /*
          Logo.dev's 500k monthly allowance was exhausted in a single day by test runs hitting a
          live logo endpoint, taking every logo on production down to letter tiles for days.
          Nothing failed; nothing alerted.

          A credential check alone would not have stopped it - Clearbit needs no credential, so a
          resolver with no keys configured would still have called out. The build sets
          resolver.network=off for surefire and failsafe, and this asserts the switch is actually
          in force rather than merely written down.
        */
        assertFalse(DomainResolver.networkEnabled(),
            "resolver.network must be 'off' during tests - check the surefire/failsafe "
          + "systemPropertyVariables in Api/pom.xml");
    }

    @Test
    void aFullyConfiguredResolver_stillMakesNoCallWhenDisabled() throws Exception {
        /* Even handed real-looking credentials, it must refuse rather than resolve. */
        io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        try {
            DomainResolver resolver = new DomainResolver(vertx, "sk_looks_real", "1idLooksReal");
            Resolution r = resolver.resolve("University of Waterloo", "uwaterloo.ca")
                .toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals(Outcome.UNRESOLVED, r.outcome(), "disabled must mean no call, not a result");
            assertTrue(r.candidates().isEmpty(), "no resolver may have answered");
        } finally {
            vertx.close();
        }
    }
}

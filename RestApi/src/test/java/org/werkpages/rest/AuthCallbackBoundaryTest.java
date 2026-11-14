package org.werkpages.rest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE STAGE 2 SECURITY BOUNDARY, asserted structurally.
 *
 * <h2>Why a test that reads source code</h2>
 *
 * <p>The question this answers is not "does the validator work" — {@code IdTokenValidationTest}
 * answers that with thirteen adversarial cases. It is the different and harder question: <b>can
 * any path through the social callback reach an account write without going through the
 * validator?</b> A correct validator that something can route around is worth nothing, and that is
 * exactly the shape of the defect being fixed: the old code had a perfectly good JWKS validator
 * for sessions and simply did not use it at login.
 *
 * <p>Behavioural proof of the whole callback would mean stubbing Auth0's {@code /oauth/token} and
 * {@code /userinfo}. Both are called on a hardcoded port 443 with {@code ssl(true)}, so standing
 * up a local stub needs a code seam in {@code AuthHandler} that does not exist and has not been
 * authorised. Until it does, these assertions hold the boundary, and they hold it permanently
 * rather than at one moment: they fail the build the day somebody reintroduces a decode.
 *
 * <h2>What each assertion is defending</h2>
 *
 * <p>The original defect was one line, {@code JWT.decode(...).getSubject()}, feeding the account
 * lookup. It was not malicious or careless; it is simply what you write when you believe the token
 * came from a trusted place. The point of these tests is that believing it again should break the
 * build.
 */
class AuthCallbackBoundaryTest {

    private static final Path HANDLER =
        Path.of("src/main/java/org/werkpages/rest/handlers/AuthHandler.java");

    private static String source() throws IOException {
        assertTrue(Files.exists(HANDLER),
            "AuthHandler moved; this test must be pointed at its new home rather than deleted");
        return Files.readString(HANDLER, StandardCharsets.UTF_8);
    }

    /**
     * The social callback, from its signature to the end of the method that completes it, with
     * COMMENTS STRIPPED.
     *
     * <p>Stripping matters and was learned the hard way: the first version of this test failed
     * against correct code, because the comment explaining what {@code JWT.decode} used to do
     * contains the words {@code JWT.decode}. A test that cannot tell code from prose would either
     * be deleted or, worse, "fixed" by removing the explanation that stops somebody reintroducing
     * the defect.
     */
    private static String socialCallbackPath(String src) {
        int from = src.indexOf("public void handleCallback(RoutingContext ctx)");
        assertTrue(from > 0, "handleCallback not found");
        int to = src.indexOf("// ── SIGNOUT", from);
        assertTrue(to > from, "could not find the end of the callback region");
        return stripComments(src.substring(from, to));
    }

    /** Block and line comments out, everything else left exactly where it was. */
    static String stripComments(String java) {
        StringBuilder out = new StringBuilder(java.length());
        boolean inBlock = false, inLine = false, inString = false;
        for (int i = 0; i < java.length(); i++) {
            char c = java.charAt(i);
            char next = i + 1 < java.length() ? java.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') { inLine = false; out.append(c); }
            } else if (inBlock) {
                if (c == '*' && next == '/') { inBlock = false; i++; }
            } else if (inString) {
                if (c == '\\') { i++; } else if (c == '"') { inString = false; }
                out.append(c);
            } else if (c == '/' && next == '/') {
                inLine = true; i++;
            } else if (c == '/' && next == '*') {
                inBlock = true; i++;
            } else {
                if (c == '"') inString = true;
                out.append(c);
            }
        }
        return out.toString();
    }

    @Test
    void theSocialCallbackContainsNoUnverifiedTokenDecode() throws IOException {
        /*
          THE REGRESSION TEST FOR THE ORIGINAL DEFECT.

          auth0Id used to come from com.auth0.jwt.JWT.decode(...).getSubject(), which parses and
          verifies nothing. Account lookup, account creation and the session cookie all hung off
          it. If that call ever returns to this region, identity is once again being taken on
          trust and this fails.
        */
        String region = socialCallbackPath(source());
        assertFalse(region.contains("JWT.decode"),
            "the social callback must never decode a token without verifying it; "
          + "use IdTokenValidator.verifiedSubject instead");
    }

    @Test
    void theCallbackValidatesTheIdTokenBeforeItReadsUserInfo() throws IOException {
        /*
          ORDER MATTERS, not merely presence.

          Reading /userinfo first and validating afterwards would still have fetched a profile for
          an identity nobody had checked, and the natural next edit is to use that profile. The
          validator has to gate the request, not follow it.
        */
        String region = socialCallbackPath(source());
        int validate = region.indexOf("verifiedClaims(");
        int userInfo = region.indexOf("\"/userinfo\"");
        assertTrue(validate > 0,
            "the callback must validate through IdTokenValidator, which is verifiedClaims since "
          + "the email_verified check needed more than the subject. If this moved again, point it "
          + "at the new single entry point rather than loosening the assertion");
        assertTrue(userInfo > 0, "the callback must still read /userinfo");
        assertTrue(validate < userInfo,
            "id_token validation must happen BEFORE /userinfo is requested");
    }

    @Test
    void theEmailVerifiedCheckPrecedesTheProfileAndTheAccount() throws IOException {
        /*
          THE REGRESSION TEST FOR THE ENFORCEMENT, and for its POSITION rather than its presence.

          Before this existed there was no occurrence of `email_verified` anywhere in either
          backend: enforcement was Auth0 refusing the password grant, and the only trace of it in
          our code was a line reading the word "verify" out of an error body, which Stage 4
          deletes.

          Position is what makes it a refusal rather than a late discovery, and there are now two
          positions because there are two cases:

            * a token that POSITIVELY says unverified is refused before /userinfo is requested.
              An unverified identity's profile is not ours to fetch;
            * a token that is SILENT defers to the profile's own email_verified, which can only be
              answered after /userinfo - but still before any account work, or the check would
              create the account it exists to prevent.
        */
        String region = socialCallbackPath(source());
        int saysUnverified = region.indexOf("assertsUnverified(");
        int profileCheck   = region.indexOf("profileSaysVerified(");
        int tokenCheck     = region.indexOf("emailIsVerified(");
        int userInfo       = region.indexOf("\"/userinfo\"");
        int account        = region.indexOf("userRepo.");

        assertTrue(saysUnverified > 0,
            "a token that says the address is unverified must be refused; the claim is on a token "
          + "whose signature is already verified, so checking it costs nothing");
        assertTrue(saysUnverified < userInfo,
            "an unverified identity's profile must not be fetched before it is refused");

        assertTrue(profileCheck > 0,
            "when the token names no verified address, /userinfo must verify the one being used: "
          + "reconciling the SUBJECT says the two responses concern the same identity, not that "
          + "this mailbox was ever verified");
        assertTrue(tokenCheck > 0 && tokenCheck < account,
            "the token's own verification must be settled before any account work");
        assertTrue(profileCheck < account,
            "the fallback must be settled before any account lookup, creation or link");
    }

    @Test
    void theSubjectsAreReconciledBeforeAnyAccountWork() throws IOException {
        /*
          The subject comes from the token and the email from /userinfo, and nothing used to
          establish that the two described the same person. The comparison has to come before the
          first thing that touches an account, or a mismatch is discovered after the damage.

          userRepo is the first account write or read in this region; the reconciliation must
          precede it.
        */
        String region = socialCallbackPath(source());
        int reconcile = region.indexOf("requireSameSubject(");
        int account   = region.indexOf("userRepo.");
        assertTrue(reconcile > 0, "the callback must reconcile the /userinfo subject");
        assertTrue(account > 0, "expected the callback to touch userRepo somewhere");
        assertTrue(reconcile < account,
            "the /userinfo subject must be reconciled BEFORE any account lookup, creation or link");
    }

    @Test
    void theCallbackFailsClosedWhenNoValidatorIsConfigured() throws IOException {
        /*
          The validator is set after the JWKS fetch at boot. If that fetch fails the field is null,
          and the tempting thing to write is a fallback to a decode "just for now". The guard has
          to refuse instead, so a transient network failure at boot cannot silently downgrade every
          login for the lifetime of the process.
        */
        String region = socialCallbackPath(source());
        assertTrue(region.contains("if (idTokens == null)"),
            "the callback must refuse when no id_token validator is configured");
    }

    @Test
    void exactlyOnePlaceInTheCallbackStartsATokenExchange() throws IOException {
        /*
          Every refusal in this flow is written as an early return. Early returns are only safe
          when there is ONE thing they are returning before. A second exchange call site added
          later would be reachable by a path that skipped the binding, and nothing else here would
          notice.
        */
        String region = socialCallbackPath(source());
        Matcher m = Pattern.compile("\"/oauth/token\"").matcher(region);
        int exchanges = 0;
        while (m.find()) exchanges++;
        assertEquals(1, exchanges,
            "the social callback must have exactly one token-exchange call site; "
          + "a second one is a path that can skip the binding");
    }

    @Test
    void noCredentialIsInterpolatedIntoALogLine() throws IOException {
        /*
          A print of a token, code or verifier writes a live credential into a file read by more
          people, and kept in more places, than anything here should be. Two such lines have
          already been found and fixed, by two different routes: the callback's decode failure and
          the password path's. This fails if a third appears.

          Matches the shape of the mistake, string-concatenating a sensitive variable into a print,
          rather than the words themselves.
        */
        String src = source();
        Pattern leak = Pattern.compile(
            "println\\([^;]*\\+\\s*(accessToken|idToken|codeVerifier|browserSecret|clientSecret|rawToken)\\b");
        Matcher m = leak.matcher(src);
        assertFalse(m.find(),
            "a credential is being concatenated into a log line: " + (m.hitEnd() ? "" : m.group()));
    }

    @Test
    void noAuth0ResponseBodyIsInterpolatedIntoALogLine() throws IOException {
        /*
          THE OTHER WAY A SECRET REACHES A LOG, and the one the test above does not catch.

          noCredentialIsInterpolatedIntoALogLine matches a NAMED credential variable being
          concatenated into a print. It therefore said nothing about:

              System.err.println("Auth0 signup error: " + status + " - " + rawBody);
              System.err.println("Auth0 signin error: " + status + " - " + response.bodyAsString());

          An Auth0 error body echoes request detail back. The signup body can carry the email
          address; the signin body is the /oauth/token error for a grant_type=password request,
          whose `username` IS the email. Neither is a password, which is exactly why they survived
          a review that was looking for passwords.

          The social callback had this cleaned to status-only deliberately, for the stated reason
          that a log is read by more people, and kept in more places, than anything here should be.
          The password paths did not get the same treatment for another nine months.

          Matches the SHAPE of the mistake, an upstream response body reaching a print, rather than
          any particular variable name, so renaming the variable does not evade it. Stage 4 will
          delete both of those paths; until it does, this holds them.
        */
        String src = stripComments(source());
        for (Pattern leak : List.of(
                Pattern.compile("println\\([^;]*\\+\\s*\\w*[Bb]ody\\b"),
                Pattern.compile("println\\([^;]*bodyAsString\\s*\\("),
                Pattern.compile("println\\([^;]*bodyAsJsonObject\\s*\\("))) {
            Matcher m = leak.matcher(src);
            assertFalse(m.find(),
                "an upstream response body is being written to a log: "
              + (m.hitEnd() ? leak.pattern() : m.group())
              + " - log the status, and Auth0's own error CODE if it helps, never the body");
        }
    }

    @Test
    void thereIsNoFlagThatCanReopenTheUnboundCallback() throws IOException {
        /*
          STAGE 3's REGRESSION TEST.

          A flag let the callback through without a transaction while the frontends still posted
          the old shape. It was deleted rather than switched on, because a flag is a vulnerability
          one environment variable away from returning, and whoever sets that variable in two
          years will not have read this file.

          If a rollout flag is ever needed again it should be a new, named, documented thing with
          its own removal date, not this one quietly restored.
        */
        String src = stripComments(source());
        assertFalse(src.contains("AUTH_CODE_BINDING_REQUIRED"),
            "the rollout flag was deleted; reopening the unbound callback behind an env var "
          + "puts the vulnerability one misconfiguration away");
        assertFalse(src.contains("codeBindingRequired"),
            "the rollout flag field was deleted and must not return");
    }

    @Test
    void theExchangeIsUnreachableWithoutAConsumedTransaction() throws IOException {
        /*
          The only call that begins an exchange lives in completeCallback, and the only call to
          completeCallback is inside the success branch of oauthTx.consume(...). That is what makes
          the binding unskippable rather than merely present.

          Counting the call sites is crude and it is exactly the regression worth catching: a
          second completeCallback(...) added for some new case would be a path that never consumed
          anything, and nothing else in the suite would notice.
        */
        String region = socialCallbackPath(source());
        int calls = region.split("completeCallback\\(", -1).length - 1;
        assertEquals(2, calls,
            "expected exactly one call to completeCallback plus its declaration; a second caller "
          + "is a route to the token exchange that skipped the transaction");
        assertTrue(region.indexOf("oauthTx.consume(") < region.lastIndexOf("completeCallback("),
            "completeCallback must be reached from inside the consume result, not before it");
    }

    // ── The wiring that the behavioural suites cannot see ────────────────────

    /**
     * The per-source rate limit on account recovery, asserted structurally.
     *
     * <p>Recovery's per-ACCOUNT cooldown is tested behaviourally in
     * {@code AccountRecoveryIntegrationTest}, because it is this product's own code. The
     * per-SOURCE limit is not: it comes from {@code router.post("/api/auth/*")} being mounted on
     * the outer router before the OpenAPI sub-router, so {@code POST /api/auth/recover} inherits
     * the 10-per-minute auth limiter without the endpoint knowing it exists.
     *
     * <p>That is the right arrangement and it is also invisible. Nothing in the recovery code
     * mentions a rate limit, so somebody narrowing that route pattern - to {@code /api/auth/sign*}
     * while tidying, say - would remove the protection with no test turning red and no reviewer
     * reading a file that changed. The two halves of the abuse protection answer different attacks
     * and losing either one silently is the failure worth pinning.
     */
    @Test
    void recoveryInheritsThePerSourceAuthRateLimit() throws IOException {
        String main = Files.readString(
            Path.of("src/main/java/org/werkpages/rest/MainVerticle.java"),
            StandardCharsets.UTF_8);

        int limiter = main.indexOf("router.post(\"/api/auth/*\").handler(authLimiter::handle)");
        assertTrue(limiter > 0,
            "the auth rate limiter must still be mounted on POST /api/auth/*, which is what gives "
          + "POST /api/auth/recover its per-source limit");

        int subRouter = main.indexOf("router.mountSubRouter(\"/\", apiRouter)");
        assertTrue(subRouter > 0, "the API sub-router mount moved; this assertion needs updating");
        assertTrue(limiter < subRouter,
            "the limiter must be mounted BEFORE the sub-router or it never runs for API routes");
    }

    /**
     * Recovery must never answer differently for an account that exists.
     *
     * <p>The behavioural test asserts the two responses are byte-identical. This asserts the
     * handler has no branch that could make them differ: it writes exactly one response, and it
     * writes it before the recovery service is called at all.
     *
     * <p>Structural because the behavioural test can only compare the cases it thinks to try. A
     * handler that grew an {@code if (accountExists)} would still pass it for every username the
     * test happens to use.
     */
    @Test
    void theRecoveryHandlerWritesOneResponseBeforeDoingAnyWork() throws IOException {
        String src = source();
        int start = src.indexOf("public void handleRecoverAccount(");
        assertTrue(start > 0, "handleRecoverAccount is gone; recovery is the Stage 4 cutover blocker");
        int end = src.indexOf("\n    }", start);
        String body = src.substring(start, end);

        assertEquals(1, countOccurrences(body, ".end("),
            "exactly one response may be written, or there is a branch that can differ: " + body);
        assertTrue(body.indexOf(".end(") < body.indexOf("recoveryService.recover("),
            "the response must be written BEFORE the recovery work starts, or an existing username "
          + "and a missing one differ by a mail round trip - the timing oracle");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) { count++; from += needle.length(); }
        return count;
    }

    // ── The password grant must stay gone ────────────────────────────────────

    /**
     * NO CODE IN THIS FILE USES THE PASSWORD GRANT, AND NONE MAY RETURN TO IT.
     *
     * <p>`grant_type=password` was removed on 2026-11-11 along with the two handlers that used it.
     * RFC 9700 section 2.4 forbids it: the password passes through this process, and the grant
     * cannot carry PKCE because there is no redirect to bind it to.
     *
     * <p>Asserted on the CODE form rather than the bare string, because the file now carries a
     * long comment explaining the removal and that comment names the grant repeatedly. A test that
     * grepped for the words would fail on its own documentation, and the fix for that failure
     * would be to delete the explanation - which is the opposite of what anybody wants.
     *
     * <p>This is the test that must still exist in two years when somebody is "just adding a quick
     * programmatic login for a test". Add it to the test harness, not to this file.
     */
    @Test
    void thePasswordGrantIsNotUsedAnywhereInTheAuthHandler() throws IOException {
        String src = source();
        assertFalse(src.contains("\"grant_type\", \"password\""),
            "grant_type=password is forbidden by RFC 9700 section 2.4 and was removed deliberately");
        assertFalse(src.contains("/dbconnections/signup\""),
            "account creation belongs to Auth0's hosted page, which owns password policy, "
          + "duplicate-email handling and bot protection - all three of which this file owned "
          + "while it called /dbconnections/signup");
        // The code-exchange grant is the legitimate one and must still be here, or the social
        // callback has stopped working and this test would otherwise pass in silence.
        assertTrue(src.contains("\"grant_type\", \"authorization_code\""),
            "the authorization-code grant is the replacement and must remain");
    }

    /**
     * Neither retired operation may reappear in the contract.
     *
     * <p>The handler and the route are gone; this guards the third place the endpoint could come
     * back from. An operation declared in {@code openapi.yaml} with no handler fails the router at
     * BOOT, so a half-restoration here does not fail quietly - but a full restoration would be a
     * working password endpoint nobody reviewed.
     */
    @Test
    void theRetiredPasswordOperationsAreAbsentFromTheContract() throws IOException {
        String spec = Files.readString(
            Path.of("src/main/resources/openapi.yaml"), StandardCharsets.UTF_8);
        assertFalse(spec.contains("  /api/auth/signin:"),
            "POST /api/auth/signin was retired with the password grant");
        assertFalse(spec.contains("  /api/auth/signup:"),
            "POST /api/auth/signup was retired with /dbconnections/signup");
        // The replacements, asserted positively, so this test notices if the migration is undone
        // wholesale rather than endpoint by endpoint.
        assertTrue(spec.contains("  /api/auth/complete-profile:"), "profile completion must exist");
        assertTrue(spec.contains("  /api/auth/recover:"),
            "username recovery must exist: without it, somebody who remembers only their username "
          + "has no way back in now that the password grant is gone");
    }
}

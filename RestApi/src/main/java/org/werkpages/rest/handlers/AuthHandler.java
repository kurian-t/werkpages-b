package org.werkpages.rest.handlers;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.core.Future;
import org.werkpages.repository.OAuthTransactionRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.rest.ApiOrigins;
import org.werkpages.rest.IdTokenValidator;

import java.util.Set;
import java.util.UUID;

/**
 * HTTP adapter for authentication endpoints.
 * Auth0 HTTP calls remain here because they require {@link WebClient} (a RestApi-only dependency).
 * All database operations delegate to {@link UserRepository}.
 */
public class AuthHandler {

    // ── Disposable / temporary email domain blocklist ──────────────────────────
    private static final Set<String> DISPOSABLE_DOMAINS = Set.of(
        "10minutemail.com","10minutemail.net","10minutemail.org","10minutemail.de",
        "20minutemail.com","33mail.com","altmails.com","anonbox.net","antispam24.de",
        "armyspy.com","binkmail.com","blow-up.net","boxformail.in","brefmail.com",
        "burnermail.io","byom.de","chacuo.net","chammy.info","cheatmail.de",
        "chinatmail.com","clrmail.com","correotemporal.org","crapmail.org",
        "dayrep.com","deadaddress.com","deadletter.ga","despam.it","discard.email",
        "discardmail.com","discardmail.de","dispostable.com","dodgit.com",
        "drdrb.net","dump-email.info","dumpmail.de","dumpyemail.com",
        "e4ward.com","einrot.com","emaildrop.io","emailinfive.com","emailmiser.com",
        "emailondeck.com","emailsensei.com","emailtemporario.com.br","emailthe.net",
        "emailto.de","emailwarden.com","emkei.cz","fakeinbox.com","fakeinbox.info",
        "fakeinbox.net","fakeinbox.org","fakemail.net","fakemailgenerator.com",
        "filzmail.com","fleckens.hu","flyspam.com","freemail.ms","front14.org",
        "getairmail.com","getonemail.com","getnada.com","getonemail.net","gishpuppy.com",
        "grr.la","guerillamail.biz","guerillamail.com","guerillamail.de",
        "guerillamail.info","guerillamail.net","guerillamail.org","guerrillamail.biz",
        "guerrillamail.com","guerrillamail.de","guerrillamail.info","guerrillamail.net",
        "guerrillamail.org","guerrillamailblock.com","gustr.com","h8s.org","haltospam.com",
        "hatespam.org","hidemail.de","hochsitze.com","hotpop.com","hulapla.de",
        "ieatspam.eu","ieatspam.info","ihasasecret.com","imails.info","inbax.tk",
        "inbox2.info","inoutmail.de","inoutmail.eu","inoutmail.info","inoutmail.net",
        "insorg.org","instantemailaddress.com","ipoo.org","irish2me.com",
        "iwi.net","jetable.com","jetable.fr.nf","jetable.net","jetable.org",
        "jnxjn.com","junk1.tk","kasmail.com","keepmymail.com","killmail.com",
        "killmail.net","klassmaster.com","klzlk.com","kurzepost.de","lawlita.com",
        "letthemeatspam.com","lhsdv.com","lifebyfood.com","lindenbaumjapan.com",
        "litedrop.com","lol.ovpn.to","lookugly.com","lr78.com","lroid.com",
        "maildrop.cc","mailexpire.com","mailfree.ga","mailguard.me","mailimate.com",
        "mailinator.com","mailinator.net","mailinator.org","mailinator2.com",
        "mailincubator.com","mailme.ir","mailme24.com","mailmetrash.com","mailmoat.com",
        "mailnew.com","mailnull.com","mailquack.com","mailscrap.com","mailseal.de",
        "mailshell.com","mailsiphon.com","mailslite.com","mailspam.me","mailspam.xyz",
        "mailspam.club","mailsponge.com","mailtemp.net","mailzilla.com","mailzilla.org",
        "marumo.ne.jp","mbx.cc","mega.zik.dj","meltmail.com","messagebeamer.de",
        "mierdamail.com","mintemail.com","moncourrier.fr.nf","monemail.fr.nf",
        "monmail.fr.nf","mt2009.com","mt2014.com","mypartyclip.de","myphantomemail.com",
        "mytempemail.com","mytrashmail.com","neomailbox.com","nepwk.com","nervmich.net",
        "nervtmich.net","netmails.com","netmails.net","netzidiot.de","neverbox.com",
        "no-spam.ws","noblepioneer.com","nobulk.com","noclickemail.com","nogmailspam.info",
        "nomail.pw","nomail.xl.cx","nomail2me.com","nomorespamemails.com","nonspam.eu",
        "nonspammer.de","noref.in","nospam.ze.tc","nospam4.us","nospamfor.us",
        "nospammail.net","nospamthanks.info","notmailinator.com","null.net",
        "nowmymail.com","nwldx.com","nwytg.com","nwytg.net",
        "odaymail.com","onewaymail.com","online.ms","oopi.org","outgun.com",
        "pecinan.net","pecinan.org","pepbot.com","pfui.ru","pimpedupmyspace.com",
        "pjjkp.com","plexolan.de","pookmail.com","postalmail.biz","postinbox.com",
        "ppetw.com","privacy.net","proxymail.eu","prtnx.com","punkmail.com",
        "putthisinyourspamdatabase.com","pwrby.com","quickinbox.com",
        "rcpt.at","recode.me","recursor.net","regbypass.comsafe-mail.net",
        "rklips.com","rmqkr.net","rppkn.com","rtrtr.com","s0ny.net","safetymail.info",
        "safetypost.de","sandelf.de","santikasari.com","sast.ro","saynotospams.com",
        "secretseries.biz","selfdestructingmail.com","sendspamhere.com","senseless-entertainment.com",
        "sexical.com","sharedmailbox.org","sharklasers.com","shieldemail.com",
        "shiftmail.com","shitmail.me","shortmail.net","sibmail.com","smellfear.com",
        "snakemail.com","sneakemail.com","sofort-mail.de","sogetthis.com","soodonims.com",
        "spam.la","spam.lt","spam.su","spam4.me","spamavert.com","spambob.com",
        "spambob.net","spambob.org","spambog.com","spambog.de","spambog.ru",
        "spambox.info","spambox.us","spamcannon.com","spamcannon.net","spamcero.com",
        "spamcon.org","spamcorptastic.com","spamcowboy.com","spamcowboy.net",
        "spamcowboy.org","spamday.com","spamdecoy.net","spameater.com","spameater.org",
        "spamex.com","spamfree24.com","spamfree24.de","spamfree24.eu","spamfree24.info",
        "spamfree24.net","spamfree24.org","spamgourmet.com","spamgourmet.net",
        "spamgourmet.org","spamgrab.com","spamgram.net","spamherelots.com",
        "spamhereplease.com","spamhole.com","spamify.com","spaminator.de",
        "spamkill.info","spaml.com","spaml.de","spammotel.com","spamobox.com",
        "spamsalad.in","spamslicer.com","spamspot.com","spamthis.co.uk",
        "spamthisplease.com","spamtrail.com","spamtroll.net","speed.1s.fr",
        "splyc.com","ssoia.com","startkeys.com","stinkefinger.net","stuffmail.de",
        "super-auswahl.de","supergreatmail.com","supermailer.jp","superstachel.de",
        "suremail.info","svk.jp","sweetxxx.de","tafmail.com","tagyourself.com",
        "temp-mail.org","temp.email","tempalias.com","tempe-mail.com","tempinbox.com",
        "tempmail.de","tempmail.net","tempmail2.com","tempomail.fr","temporamail.com",
        "temporarioemail.com.br","temporaryemail.net","temporaryemail.us",
        "temporaryforwarding.com","temporaryinbox.com","tempr.email","tempymail.com",
        "thankyou2010.com","thecloudindex.com","thisisnotmyrealemail.com","throwam.com",
        "throwam.net","throwam.org","throwmail.me","throwmea.com","tilien.com",
        "tmbx.de","tmailinator.com","toiea.com","toomail.biz","top101.de",
        "trashdevil.com","trashdevil.de","trashemail.de","trashmail.at","trashmail.com",
        "trashmail.de","trashmail.io","trashmail.me","trashmail.net","trashmail.org",
        "trashmail.xyz","trashmailer.com","trashme.nl","trashy.io","trbvm.com",
        "trbvn.com","treatme.ro","trickmail.net","trillianpro.com","ttirv.net",
        "twinmail.de","tyldd.com","umail.net","uroid.com","uuf.me","velocity.es",
        "veryrealemail.com","viditag.com","viewcastmedia.com","viewcastmedia.net",
        "viewcastmedia.org","vkcode.ru","vomoto.com","vpn.st","vsimcard.com",
        "vubby.com","walala.org","walkmail.net","webemail.me","wegwerfmail.de",
        "wegwerfmail.net","wegwerfmail.org","wh4f.org","whyspam.me","willselfdestruct.com",
        "wimsg.com","wMailer.com","wronghead.com","wuzupmail.net","www.e4ward.com",
        "xagloo.com","xemaps.com","xents.com","xmaily.com","xoxy.net","xyzfree.net",
        "yanet.me","yep.it","yogamaven.com","yopmail.com","yopmail.fr","yopmail.info",
        "you-spam.com","yourdomain.com","yuurok.com","z1p.biz","za.com","zehnminuten.de",
        "zehnminutenmail.de","zippymail.info","zoaxe.com","zoemail.net","zoemail.org",
        "zomg.info","zxcv.com","zxcvbnm.com","zzrgg.com"
    );

    private final UserRepository userRepo;
    private final String auth0Domain;
    private final String clientId;
    private final String clientSecret;
    private final String audience;
    private final WebClient webClient;
    /**
     * CAPTCHA verification, built from {@code TURNSTILE_SECRET_KEY}.
     *
     * <p>This was a bare {@code String} that nothing read: the secret was loaded, carried here
     * and never used, so a configured key bought no protection at all. It is now a verifier that
     * can actually refuse a token, and {@link org.werkpages.service.TurnstileVerifier} explains
     * why no endpoint calls it yet and what choosing one involves. Holding the verifier rather
     * than the key is the point: a reader can see whether verification exists without grepping
     * for the string.
     */
    private final org.werkpages.service.TurnstileVerifier turnstile;

    /**
     * The HMAC key that signs the pre-auth cookie, and whether the code binding is MANDATORY.
     *
     * <p>{@code hmacKey} is the one {@code SecretsConfig} already loads for the email blind index.
     * Reusing it adds no secret and no dependency. It is not the primary control: tampering with
     * the cookie gains nothing, because changing the verifier makes Auth0 refuse the exchange and
     * changing the state makes it disagree with what Auth0 echoes back. The signature is defence
     * in depth.
     *
     * <p>There is no rollout flag any more. One existed while the frontends still posted
     * callbacks with no transaction; it was deleted rather than left switched on, because a flag
     * is a vulnerability one environment variable away from returning and the person who sets it
     * will not be the person who read this file.
     */
    private final OAuthTransactionRepository oauthTx;

    /**
     * Validates the ID token and yields the subject. NULL means the JWKS fetch at boot failed, in
     * which case the callback refuses rather than falling back to an unverified decode.
     */
    private IdTokenValidator idTokens;

    /** Set once the JWKS are in hand; see MainVerticle. */
    public void setIdTokenValidator(IdTokenValidator validator) { this.idTokens = validator; }

    /**
     * WHERE Auth0 is. Built for the real tenant in the constructor; see {@link Auth0Http}.
     *
     * <p>Not final, and the setter is package-private, so the callback's HTTP path can be pointed
     * at a local stub by a test in this package and by nothing else. {@code MainVerticle} is in
     * the parent package and cannot reach it, which is the point: a destination that production
     * wiring could override would be a way to send this client's credentials elsewhere.
     */
    private Auth0Http auth0;

    /** Tests only. Package-private deliberately; see the field. */
    void setAuth0Http(Auth0Http auth0) { this.auth0 = auth0; }

    public AuthHandler(UserRepository userRepo, OAuthTransactionRepository oauthTx,
                       String auth0Domain, String clientId,
                       String clientSecret, String audience, String turnstileSecretKey,
                       Vertx vertx) {
        this.userRepo          = userRepo;
        this.oauthTx           = oauthTx;
        this.auth0Domain       = auth0Domain;
        this.clientId          = clientId;
        this.clientSecret      = clientSecret;
        this.audience          = audience;
        this.turnstile         = new org.werkpages.service.TurnstileVerifier(turnstileSecretKey);
        this.webClient         = WebClient.create(vertx);
        this.auth0             = Auth0Http.https(vertx, auth0Domain);
    }

    /** Whether CAPTCHA verification is configured, for the boot log and for tests. */
    public boolean captchaVerificationEnabled() { return turnstile.isEnabled(); }

    // ── START: the server begins the login transaction ────────────────────────

    /**
     * {@code GET /api/auth/start} — begins a login transaction and redirects to Auth0.
     *
     * <p>This endpoint is the fix. Previously the browser built the {@code /authorize} URL itself
     * and kept {@code state} in {@code sessionStorage}, where an attacker's browser holds whatever
     * value makes its own check pass. Both values now exist only on the server, in a row this
     * browser alone can consume.
     *
     * <h2>The cookie identifies the BROWSER, not the transaction</h2>
     *
     * <p>It is reused while the browser still has a live pending login. Minting a fresh secret on
     * every start would overwrite the cookie and strand every other tab's transaction, which is
     * exactly the multi-tab flaw that sank the signed-cookie design this replaces. One browser,
     * one secret, as many concurrent rows as it has tabs.
     *
     * <p>{@code __Host-} is not decoration. A browser refuses to store such a cookie unless it is
     * {@code Secure}, {@code Path=/} and carries NO {@code Domain}, which is what stops a sibling
     * subdomain planting one the apex will receive, and stops a second cookie of the same name
     * being created at a narrower path. It costs sending a ten-minute cookie on every request,
     * which is worth far less than the only browser-enforced defence against planting.
     *
     * <p>Three things are deliberately NOT taken from the caller: {@code redirect_uri}, built from
     * {@link ApiOrigins#appOrigin}; the connection, checked against a fixed set; and
     * {@code returnTo}, accepted only as a site-relative path.
     */
    public void handleStart(RoutingContext ctx) {
        boolean isProduction = org.werkpages.config.AppEnv.current().isProduction();

        String connection = ctx.queryParam("connection").stream().findFirst().orElse(null);
        if (connection == null || !ALLOWED_CONNECTIONS.contains(connection)) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "unsupported_connection").encode());
            return;
        }

        String returnTo = AuthTokenUtils.safeReturnTo(
            ctx.queryParam("returnTo").stream().findFirst().orElse(null), DEFAULT_RETURN_TO);

        /*
          screen_hint, ALLOWLISTED TO 'signup' AND NOTHING ELSE.

          Auth0 reads this to decide which panel of Universal Login to open. Registration needs it
          or somebody who pressed "Create an account" lands on the sign-in panel and has to find
          the link themselves, which is the drop-off this whole flow exists to avoid.

          An allowlist rather than a passthrough because this value is interpolated into a URL the
          browser is then redirected to. Reflecting arbitrary caller input into that URL is how a
          parameter-injection bug gets written: a value containing '&prompt=none' would change the
          semantics of the authorization request itself. One permitted value, compared exactly.
        */
        /*
          login_hint: THE EMAIL THE PERSON TYPED, AND ONLY THAT.

          Auth0 prefills its email field from this, so somebody who typed their address in our
          modal does not type it again on the hosted page. Ordinary, and ordinary is the point:
          it is a value they just entered about themselves.

          It must NEVER be populated from a username lookup. Resolving a username to an email and
          prefilling it here is an account enumeration oracle - anybody could type usernames and
          read private addresses off the hosted page - and it is the design option that was
          explicitly rejected. AuthCallbackBoundaryTest asserts structurally that no lookup feeds
          this parameter.

          Accepted only if it looks like an email, and refused if it contains anything that could
          change the authorize URL's shape. A caller can already put whatever it likes in its own
          browser, so this is not protecting a secret; it is keeping a reflected value from
          altering the request.
        */
        String loginHint = ctx.queryParam("login_hint").stream().findFirst().orElse(null);
        final String safeLoginHint =
            (loginHint != null && loginHint.length() <= 254
             && loginHint.matches("^[^\\s&?=#/\\\\]+@[^\\s&?=#/\\\\]+\\.[A-Za-z]{2,}$"))
                ? loginHint : null;

        boolean wantsSignupScreen =
            "signup".equals(ctx.queryParam("screen_hint").stream().findFirst().orElse(null));

        // A duplicate cookie name is a REFUSAL, never a pick-the-first.
        //
        // Cookie shadowing works precisely because servers take the first match: an attacker who
        // can get a second cookie of the same name into the jar then chooses which value the
        // server reads. __Host- is supposed to prevent that, but a prefix is a browser control and
        // the browser is the attacker's machine, so this does not rely on it alone.
        String existing;
        try {
            existing = AuthTokenUtils.soleCookieValue(ctx, AuthTokenUtils.BROWSER_COOKIE);
        } catch (IllegalStateException duplicated) {
            refuseBinding(ctx, isProduction);
            return;
        }

        Future<String> secret = existing == null
            ? Future.succeededFuture(null)
            : oauthTx.hasLivePendingLogin(existing).map(live -> live ? existing : null);

        secret.compose(reusable -> {
            String browserSecret = reusable != null
                ? reusable
                : OAuthTransactionRepository.randomToken();
            String state         = OAuthTransactionRepository.randomToken();
            String codeVerifier  = OAuthTransactionRepository.randomToken();
            String challenge     = OAuthTransactionRepository.pkceChallengeFor(codeVerifier);

            return oauthTx.start(state, browserSecret, codeVerifier, connection, returnTo)
                .map(ignored -> {
                    String redirectUri = ApiOrigins.appOrigin(isProduction) + "/auth/callback";

                    // The SAME host the token exchange uses. Auth0 records the challenge against
                    // the authorize request, so authorize and token must reach the same tenant or
                    // the verifier is checked against nothing.
                    String authorize = "https://" + auth0Domain + "/authorize"
                        + "?response_type=code"
                        + "&client_id="      + enc(clientId)
                        + "&audience="       + enc(audience)
                        + "&redirect_uri="   + enc(redirectUri)
                        + "&scope="          + enc("openid profile email")
                        + "&connection="     + enc(connection)
                        + "&state="          + enc(state)
                        + "&code_challenge=" + enc(challenge)
                        + "&code_challenge_method=S256"
                        + "&prompt=select_account"
                        + (wantsSignupScreen ? "&screen_hint=signup" : "")
                        + (safeLoginHint != null ? "&login_hint=" + enc(safeLoginHint) : "");

                    ctx.response()
                        .setStatusCode(302)
                        .putHeader("Set-Cookie",
                            AuthTokenUtils.browserCookie(browserSecret, isProduction))
                        .putHeader("Location", authorize)
                        .end();
                    return (Void) null;
                });
        }).onFailure(err -> {
            // Fail closed. A login we cannot record is a login we cannot verify, and proceeding
            // without the row would mean exchanging a code with no binding at all.
            System.err.println("REFUSING /api/auth/start: could not record the login transaction");
            ctx.response().setStatusCode(503).putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("error", "login_unavailable")
                    .put("message", "Sign-in is temporarily unavailable. Please try again later.")
                    .encode());
        });
    }

    /**
     * Exactly the connections the sign-in surfaces offer. Anything else is refused.
     *
     * <p>{@code Username-Password-Authentication} is in here because email and password is not a
     * different KIND of login any more, it is a different connection on the same
     * authorization-code flow. That is what retires {@code grant_type=password}, which RFC 9700
     * section 2.4 says must not be used: the application stops handling the password at all, so
     * credential stuffing, MFA and anomaly detection become Auth0's to enforce rather than ours
     * to reimplement badly.
     */
    private static final Set<String> ALLOWED_CONNECTIONS =
        Set.of("google-oauth2", "windowslive", "linkedin", "facebook", "apple",
               "Username-Password-Authentication");

    // ── CHECK USERNAME ────────────────────────────────────────────────────────

    public void handleCheckUsername(RoutingContext ctx) {
        String username = ctx.queryParam("username").stream().findFirst().orElse(null);
        if (username == null || username.isBlank()) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Missing username").encode());
            return;
        }
        userRepo.findEmailByUsername(username)
            .onSuccess(opt ->
                ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("available", opt.isEmpty()).encode())
            )
            .onFailure(ctx::fail);
    }

    /**
     * Where a login goes when the caller named nowhere.
     *
     * <p>RECONSTRUCTED on 2026-11-11. This constant, and {@code enc} below, sat between the two
     * password-grant handlers and were deleted with them by a removal whose boundaries were drawn
     * too wide. They were part of uncommitted work, so there was no committed copy to restore from.
     *
     * <p>The value is taken from the frontend's own fallback - the same path {@code SignIn.tsx}
     * and {@code AuthCallback.tsx} default to when no {@code returnTo} survives - because the two
     * must agree: a different default here would send somebody somewhere the frontend never sends
     * them, and only on the path where nothing else specified a destination, which is the path
     * least likely to be noticed.
     */
    private static final String DEFAULT_RETURN_TO = "/explore";

    /**
     * URL-encodes one query parameter value for the authorize URL.
     *
     * <p>Also reconstructed; see above. Every value interpolated into that URL goes through this,
     * which is what stops a connection name or a state token containing an ampersand from
     * changing the meaning of the authorization request.
     */
    private static String enc(String raw) {
        return java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    /*
      ── THE PASSWORD GRANT IS GONE ──────────────────────────────────────────────
      Removed 2026-11-11, after Universal Login replaced every path that reached it.

      What stood here:

        handleSignup / doAuth0Signup   POST /api/auth/signup  -> Auth0 /dbconnections/signup
        handleSignin / doSignin        POST /api/auth/signin   -> Auth0 /oauth/token with
                                                                  grant_type=password

      Why they are not coming back:

        * RFC 9700 section 2.4 forbids the password grant. The password passed through this
          process, and the grant cannot carry PKCE because there is no redirect to bind.
        * Every protection built for the social flow - the browser-bound login transaction, PKCE
          S256, enforced state, ID-token validation, the /userinfo reconciliation - covered the
          redirect path and NONE of it covered these two. Keeping them meant maintaining a second,
          weaker front door.
        * /dbconnections/signup created the account from here, which meant this code owned password
          policy, duplicate-email handling and bot protection. Auth0's hosted page owns all three
          now, and does it better.

      What replaced them, and where to look:

        sign in     client/lib/auth.ts  startPasswordLogin  -> GET /api/auth/start
        register    client/lib/auth.ts  startRegistration   -> GET /api/auth/start&screen_hint=signup
        username    POST /api/auth/recover        (recovery, never a login_hint lookup)
        profile     POST /api/auth/complete-profile

      AuthCallbackBoundaryTest asserts structurally that no `grant_type=password` and no
      `/dbconnections/signup` string returns to this file, and that neither operation returns to
      openapi.yaml. If you are here to add a programmatic login for a test, add it to the test
      harness, not to this file.
    */

    public void handleMe(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) {
            ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Unauthorized").encode()); return;
        }
        userRepo.findByAuth0IdWithBan(auth0Id)
            .onSuccess(opt -> {
                if (opt.isEmpty()) {
                    ctx.response().setStatusCode(404).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "User not found").encode()); return;
                }
                io.vertx.sqlclient.Row row = opt.get();
                java.util.UUID userId = row.getUUID("id");
                io.vertx.core.Future.all(userRepo.hasContributed(userId), userRepo.hasRatedCompany(userId))
                    .onSuccess(flags -> { boolean contributed = flags.resultAt(0); boolean ratedCompany = flags.resultAt(1);
                      ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject()
                            .put("id",             userId.toString())
                            .put("auth0Id",        row.getString("auth0_id"))
                            .put("email",          userRepo.decryptField(row.getString("email")))
                            .put("username",       row.getString("username"))
                            .put("firstName",      userRepo.decryptField(row.getString("first_name")))
                            .put("lastName",       userRepo.decryptField(row.getString("last_name")))
                            .put("role",           row.getString("role"))
                            .put("isBanned",       row.getBoolean("is_banned"))
                            .put("hasContributed", contributed)
                            // One gate per dataset. Rating a manager buys the manager numbers and
                            // nothing else; the workplace numbers are bought by rating a workplace.
                            .put("hasRatedCompany", ratedCompany)
                            .put("createdAt",      row.getLocalDateTime("created_at").toString())
                            .encode()); })
                    .onFailure(ctx::fail);
            })
            .onFailure(ctx::fail);
    }

    // ── SOCIAL LOGIN CALLBACK ─────────────────────────────────────────────────

    /**
     * Exchanges an Auth0 authorization code (from Google/Facebook/Apple OAuth) for a session.
     * Creates the user in the local DB on first login with an auto-generated username.
     */
    public void handleCallback(RoutingContext ctx) {
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) { ctx.response().setStatusCode(400).end("{\"error\":\"Missing body\"}"); return; }
        String code        = body.getString("code");
        String redirectUri = body.getString("redirectUri");
        if (code == null || redirectUri == null) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Missing code or redirectUri").encode()); return;
        }

        // ── THE CODE BINDING ──────────────────────────────────────────────────
        //
        // Everything above this point is what the endpoint always did: accept a code and exchange
        // it. That is the defect. Without a verifier bound to the browser that began the login, a
        // stolen code redeemed from anywhere bought a session for the code's owner.
        //
        // The transaction is consumed BEFORE the exchange, so a refused callback never reaches
        // Auth0 at all. Exchanging first and validating afterwards would still have spent the
        // victim's code and still have been a takeover.
        //
        // Two things are checked in ONE atomic DELETE: the state, and the hash of this browser's
        // cookie. State alone would not do: an attacker who starts their own login and induces a
        // victim's browser to finish it consumes the attacker's OWN row perfectly legitimately,
        // and the victim ends up signed in as the attacker.
        boolean isProduction = org.werkpages.config.AppEnv.current().isProduction();

        String browserSecret;
        try {
            browserSecret = AuthTokenUtils.soleCookieValue(ctx, AuthTokenUtils.BROWSER_COOKIE);
        } catch (IllegalStateException duplicated) {
            // Two cookies of that name. See handleStart: shadowing works by the server picking one.
            refuseBinding(ctx, isProduction); return;
        }
        String presentedState = body.getString("state");

        // NO COOKIE OR NO STATE IS A REFUSAL. There is no longer any other branch.
        //
        // A flag used to let a callback through without either, so an old bundle could still sign
        // people in during the rollout. That window is closed and the branch is DELETED rather
        // than disabled: a flag left in place is a vulnerability one environment variable away
        // from returning, and the person who sets it will not be the person who read this file.
        if (browserSecret == null || presentedState == null) {
            refuseBinding(ctx, isProduction); return;
        }

        oauthTx.consume(presentedState, browserSecret)
            .onFailure(err -> {
                // A database failure must not become an unbound exchange. Fail closed.
                System.err.println("REFUSING /api/auth/callback: login transaction lookup failed");
                refuseBinding(ctx, isProduction);
            })
            .onSuccess(found -> {
                if (found.isEmpty()) {
                    // Unknown state, wrong browser, already consumed, or expired. All four are
                    // refused identically: distinguishing them would tell an attacker which half
                    // of their forgery was wrong, and the person can do nothing different anyway.
                    refuseBinding(ctx, isProduction);
                    return;
                }
                completeCallback(ctx, code, redirectUri, found.get(), isProduction);
            });
    }

    /**
     * The exchange itself, reached only once the transaction has been consumed.
     *
     * <p>Split out so that there is exactly ONE place a token exchange can begin, and reaching it
     * is proof the binding passed. When the refusal and the exchange lived in one method it was
     * possible to read the code and believe an early return covered a path it did not.
     *
     * @param consumed the consumed transaction, or null only in the rollout window with the flag
     *                 off, where there is no transaction and no verifier to send.
     */
    private void completeCallback(RoutingContext ctx, String code, String redirectUri,
                                  OAuthTransactionRepository.Consumed consumed,
                                  boolean isProduction) {
        String codeVerifier = consumed == null ? null : consumed.codeVerifier();
        /*
          WHERE THE PERSON WAS GOING, handed back to the browser.

          /api/auth/start validated this and stored it on the transaction, and consume() has just
          returned it. Nothing then read it, so every login landed on the default: the frontend was
          still reading an `oauth_return_to` sessionStorage slot that auth.ts stopped writing when
          the browser gave up building the authorize URL. A contributor bounced to sign in at submit
          came back to the directory with a finished rating stranded in a draft.

          It travels in the response body rather than a redirect because this endpoint is called by
          XHR: the callback page holds the router, and it is the router that must navigate.
        */
        final String txReturnTo = consumed == null ? null : consumed.returnTo();

        // ── REDIRECT URI: derived or allowlisted, never taken on trust ────────
        //
        // redirect_uri tells Auth0 where the code was delivered, and the exchange must present
        // the SAME value the authorize request used. This endpoint previously forwarded whatever
        // the body contained, so the backend would exchange a code against any URI a caller
        // named, with Auth0's own allowlist the only thing behind it. That makes a security
        // property of this API depend entirely on configuration held somewhere else.
        //
        // It cannot simply be overridden in every case, because the two flows authorized against
        // different values:
        //   new flow (cookie present) -> the server built it at /api/auth/start, so rebuild
        //                                exactly the same value and ignore the body.
        //   old flow (rollout window) -> the browser built it, so check it against this
        //                                product's registered callbacks and refuse anything else.
        // DERIVED, never taken from the request.
        //
        // There was an allowlist branch here for the rollout window, when an older bundle still
        // sent its own redirect_uri built from window.location.origin. Nothing reaches the
        // exchange without a consumed transaction now, and every one of those was authorized
        // against this exact value at /api/auth/start, so the body's copy is not consulted at all.
        String exchangeRedirectUri = ApiOrigins.appOrigin(isProduction) + "/auth/callback";

        JsonObject tokenPayload = new JsonObject()
            .put("grant_type", "authorization_code")
            .put("client_id", this.clientId)
            .put("client_secret", this.clientSecret)
            .put("code", code)
            .put("redirect_uri", exchangeRedirectUri)
            .put("audience", this.audience);
        if (codeVerifier != null) {
            // The binding itself. Auth0 compares SHA256 of this against the challenge it recorded
            // for THIS authorization request and refuses the exchange on a mismatch.
            tokenPayload.put("code_verifier", codeVerifier);
        }

        // WHAT ACTUALLY PREVENTS REPLAY, stated precisely.
        //
        // The row is gone: consume() deleted it, atomically, and a second callback presenting the
        // same state gets nothing back. That is the single-use guarantee and it belongs to the
        // database, not to any cookie.
        //
        // The browser cookie is deliberately NOT cleared here. Clearing it would strand every
        // other tab's pending login, and it buys nothing: the cookie is not a credential, grants
        // nothing on its own, and expires in ten minutes regardless.
        //
        // Beyond that, Auth0's own authorization codes are single-use and short-lived, so a
        // replayed anything is worthless without an unused code.

        // Through Auth0Http, which holds the host, the port and the TLS decision and nothing
        // else. The path, the payload, the timeout and the lambda below are unchanged; what moved
        // is only WHERE the request goes, so that a test can put a stub there and prove what this
        // callback actually does with each answer rather than merely what order it calls things in.
        this.auth0.postJson("/oauth/token", tokenPayload)
            .onComplete(ar -> {
                if (ar.failed()) {
                    // Auth0 unreachable, TLS failure, or the ten-second timeout. Nothing has been
                    // written and no cookie has been issued, and that must stay true on this path.
                    System.err.println("Auth0 code exchange did not complete");
                    internalError(ctx); return;
                }
                if (ar.result().statusCode() != 200) {
                    // STATUS ONLY. The response body of a failed token exchange echoes request
                    // detail back, and this line previously put all of it in the log. A log is
                    // read by more people, and kept in more places, than anything here should be.
                    System.err.println("Auth0 code exchange failed with status " + ar.result().statusCode());
                    ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "token_exchange_failed")
                            .put("message", "Authentication failed. Please try again.").encode()); return;
                }
                // A 200 WHOSE BODY IS NOT JSON IS A REFUSAL, not an exception.
                //
                // bodyAsJsonObject() throws on anything that is not a JSON object, and it was
                // called straight inside this lambda. A throw here is caught by nothing that can
                // answer the browser: Vert.x logs it on the context and the response is never
                // completed, so the login hangs until the client gives up. A proxy error page or a
                // truncated body in front of Auth0 is enough to cause it.
                JsonObject tokens = Auth0Http.asJsonOrNull(ar.result());
                if (tokens == null) {
                    System.err.println("REFUSING callback: the token response was not JSON");
                    internalError(ctx); return;
                }
                String accessToken   = tokens.getString("access_token");
                String idToken       = tokens.getString("id_token");
                // ── IDENTITY IS ESTABLISHED BY CHECKING, NOT BY DECODING ──────────
                //
                // This used to be JWT.decode(...).getSubject(), which parses and verifies nothing:
                // no signature, no issuer, no audience, no expiry. The account lookup, the account
                // creation and the session cookie all hung off a value nobody had checked. The
                // mitigation was that the token came from Auth0 over TLS, which is an argument
                // about transport rather than a check, on the path that creates accounts.
                //
                // Everything below now runs AFTER full OIDC validation and AFTER the /userinfo
                // subject is confirmed to be the same person. Nothing is written and no cookie is
                // issued until both pass.
                if (idTokens == null) {
                    // Fail closed. Without a validator there is no way to establish identity, and
                    // falling back to a decode is the defect this replaced.
                    System.err.println("REFUSING callback: the id_token validator is not configured");
                    internalError(ctx); return;
                }
                final String fAccessToken = accessToken;
                // The session cookie must carry the access token: its `aud` is the API identifier,
                // which is what the router's audience check validates. An id_token's `aud` is the
                // client_id instead, so it would be rejected there.
                // Auth0 only returns a JWT access token when /authorize named an audience; older
                // frontend builds omit it and get an opaque string back, so fall back to the
                // id_token rather than handing the router something it cannot decode.
                final String fTokenForCookie = looksLikeJwt(accessToken) ? accessToken : idToken;

                idTokens.verifiedClaims(idToken).onFailure(err -> {
                    // One refusal for every defect: bad signature, wrong issuer, wrong audience,
                    // expired, future-dated, no subject. Naming which one rejected a forgery helps
                    // only the forger, and a real user can do nothing different about any of them.
                    //
                    // No token, code or claim value is logged. The reason is a fixed string.
                    System.err.println("REFUSING callback: id_token failed validation");
                    ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "invalid_identity_token")
                            .put("message", "Authentication failed. Please try again.").encode());
                }).onSuccess(validatedClaims -> {
                final String fAuth0Id = validatedClaims.getString("sub");

                /*
                  THE ADDRESS MUST BE VERIFIED, and nothing in this product has ever checked.

                  Not this handler, not the validator, not a single handler downstream: there was
                  no occurrence of `email_verified` anywhere in either backend. Enforcement was
                  entirely Auth0's, applied by the database connection when it refused the password
                  grant, and our only trace of it was a line in the password path checking whether
                  Auth0's error BODY contained the word "verify".

                  Stage 4 deletes that line. Leaving the requirement to a connection setting means
                  a console toggle silently decides whether unverified people can contribute, with
                  nothing in the codebase recording that it was ever required.

                  REFUSED BEFORE /userinfo and before any account work: an unverified identity's
                  profile is not ours to fetch, and this must not be able to create, link or log
                  into an account.

                  NAMED, unlike the identity failures above. Those are deliberately
                  indistinguishable because telling a forger which check rejected them helps only
                  the forger. This one is different: it is a real person who can do something
                  about it, and the frontend has to be able to say what.
                */
                if (IdTokenValidator.assertsUnverified(validatedClaims)) {
                    System.err.println("REFUSING callback: the id_token says email_verified is "
                        + "not true, connection " + IdTokenValidator.connectionOf(fAuth0Id));
                    ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "email_unverified")
                            .put("message",
                                 "Please verify your email address, then sign in again.").encode());
                    return;
                }

                this.auth0.getWithBearer("/userinfo", accessToken)
                    .onComplete(uiAr -> {
                        if (uiAr.failed()) {
                            System.err.println("Auth0 /userinfo did not complete");
                            internalError(ctx); return;
                        }
                        if (uiAr.result().statusCode() != 200) {
                            // STATUS ONLY, and a refusal rather than a parse.
                            //
                            // This was not checked at all: a 401 or a 429 from /userinfo fell
                            // through to bodyAsJsonObject() on an error body, which either threw
                            // and hung the request or produced an object with no `sub`. The second
                            // is worse, because requireSameSubject would then refuse for the wrong
                            // reason and the real cause would never appear in a log.
                            System.err.println("Auth0 /userinfo failed with status "
                                + uiAr.result().statusCode());
                            internalError(ctx); return;
                        }
                        JsonObject info = Auth0Http.asJsonOrNull(uiAr.result());
                        if (info == null) {
                            System.err.println("REFUSING callback: the /userinfo response was not JSON");
                            internalError(ctx); return;
                        }

                        // THE TWO SOURCES OF IDENTITY MUST AGREE.
                        //
                        // The subject came from the validated token; the email comes from here.
                        // They were never compared, so nothing established that the profile being
                        // read described the person the token was issued for. Checked before any
                        // account is found, created or linked, and before any cookie is issued.
                        if (IdTokenValidator.requireSameSubject(
                                fAuth0Id, info.getString("sub")).failed()) {
                            System.err.println("REFUSING callback: /userinfo subject disagrees "
                                + "with the validated id_token subject");
                            ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                                .end(new JsonObject().put("error", "identity_mismatch")
                                    .put("message", "Authentication failed. Please try again.").encode());
                            return;
                        }

                        String email         = info.getString("email");

                        String givenName     = info.getString("given_name", "");
                        String familyName    = info.getString("family_name", "");
                        if (givenName.isBlank() && familyName.isBlank()) {
                            String[] parts = info.getString("name", "Social User").split(" ", 2);
                            givenName  = parts[0];
                            familyName = parts.length > 1 ? parts[1] : "";
                        }
                        if (email == null || email.isBlank()) {
                            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                                .end(new JsonObject().put("error", "no_email")
                                    .put("message", "Your social account did not share an email address. Please use email sign-up.").encode()); return;
                        }

                        /*
                          THE VERIFIED FLAG MUST APPLY TO THE ADDRESS ACTUALLY USED.

                          `email_verified` was read off the id_token above; the address the account
                          is created or updated with comes from /userinfo, here. Nothing tied the
                          two together, so the flag could have been trusted for one mailbox while
                          the account was opened on another. Same defect shape as the subject
                          reconciliation immediately above, and the same answer: compare them.

                          Placed AFTER the no_email guard deliberately. Run before it, a provider
                          that shares no address at all was refused as an identity mismatch rather
                          than told the one useful thing: that its account has no email to use. A
                          test caught that, and the generic refusal would have been a worse answer
                          to a real person.

                          When the token asserts no `email` claim at all the comparison cannot be
                          made. That is NOT treated as a mismatch, because refusing would lock out
                          any connection that omits it, and the subject has already been
                          reconciled. It is logged with the connection so the gap is visible rather
                          than assumed, and it is on the list of things the per-connection test
                          tenant run has to establish.
                        */
                        String assertedEmail = IdTokenValidator.assertedEmail(validatedClaims);
                        boolean tokenVerifiedThisAddress =
                            IdTokenValidator.emailIsVerified(validatedClaims) && assertedEmail != null;

                        if (!tokenVerifiedThisAddress) {
                            /*
                              THE TOKEN DID NOT VERIFY A NAMED ADDRESS, SO THE PROFILE MUST.

                              Either it carried no `email` claim, so its `email_verified` was true
                              of something it never identified, or it carried no verification claim
                              at all. In both cases reconciling the SUBJECT establishes only that
                              the two responses concern the same Auth0 identity. It does not
                              establish that THIS mailbox was ever verified, and this is the
                              address the account is about to be created or updated with.

                              An earlier version let this through on the subject alone and logged a
                              note. That was the gap: a note is not a control.

                              /userinfo is asked instead. It is fetched with the access token from
                              the same client-authenticated exchange, from the configured tenant,
                              and its subject is already reconciled, which is what makes it a
                              trustworthy answer rather than another unverified input. If it cannot
                              say the address is verified either, the login is refused, with the
                              same recoverable outcome as any unverified address.
                            */
                            if (!IdTokenValidator.profileSaysVerified(info)) {
                                System.err.println("REFUSING callback: no trusted source verifies "
                                    + "the address being used, connection "
                                    + IdTokenValidator.connectionOf(fAuth0Id));
                                ctx.response().setStatusCode(401)
                                    .putHeader("Content-Type", "application/json")
                                    .end(new JsonObject().put("error", "email_unverified")
                                        .put("message",
                                             "Please verify your email address, then sign in again.")
                                        .encode());
                                return;
                            }
                        } else if (!IdTokenValidator.sameEmail(assertedEmail, email)) {
                            System.err.println("REFUSING callback: the id_token's email claim "
                                + "disagrees with /userinfo, connection "
                                + IdTokenValidator.connectionOf(fAuth0Id));
                            ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                                .end(new JsonObject().put("error", "identity_mismatch")
                                    .put("message", "Authentication failed. Please try again.").encode());
                            return;
                        }

                        final String fEmail      = email;
                        final String fFirstName  = givenName.isBlank()  ? "User" : givenName;
                        final String fLastName   = familyName;

                        final boolean[] isNewUserHolder = {false};
                        userRepo.findByAuth0IdWithBan(fAuth0Id)
                            .compose(opt -> {
                                if (opt.isPresent()) {
                                    return io.vertx.core.Future.succeededFuture(opt.get());
                                }
                                // New social user — auto-generate a unique username
                                isNewUserHolder[0] = true;
                                /*
                                  A PLACEHOLDER, FLAGGED AS ONE.

                                  Universal Login asks for an email and a password; it never asks
                                  for a username, because Auth0's own username field holds a
                                  different value that nothing in this product reads. So the
                                  account is created with a generated name and
                                  `username_is_provisional` set, and profile completion asks the
                                  person to choose one.

                                  The flag is the whole point of V99. Without it this generated
                                  name is indistinguishable from a chosen one, and nothing can tell
                                  who still needs to complete their profile.
                                */
                                String username = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                                return userRepo.create(fAuth0Id, fEmail, username, fFirstName, fLastName, true)
                                    .compose(ignored -> userRepo.findByAuth0IdWithBan(fAuth0Id))
                                    .map(java.util.Optional::get);
                            })
                            .onSuccess(row -> {
                                if (row.getBoolean("is_banned")) {
                                    ctx.response().setStatusCode(403).putHeader("Content-Type", "application/json")
                                        .end(new JsonObject().put("error", "account_suspended").encode()); return;
                                }
                                boolean isProd = org.werkpages.config.AppEnv.current().isProduction();
                                String setCookie = AuthTokenUtils.sessionCookie(fTokenForCookie, isProd);
                                java.util.UUID socialUserId = row.getUUID("id");
                                io.vertx.core.Future.all(userRepo.hasContributed(socialUserId), userRepo.hasRatedCompany(socialUserId),
                                                         mintSession(ctx, socialUserId))
                                    .onSuccess(flags -> { boolean contributed = flags.resultAt(0); boolean ratedCompany = flags.resultAt(1);
                                      addSessionCookie(ctx, flags.resultAt(2));
                                      ctx.response()
                                        .putHeader("Set-Cookie", setCookie)
                                        .putHeader("Content-Type", "application/json")
                                        .end(new JsonObject().put("user", new JsonObject()
                                            .put("id",            socialUserId.toString())
                                            .put("email",         userRepo.decryptField(row.getString("email")))
                                            .put("username",      row.getString("username"))
                                        // Whether profile completion is still owed. The frontend
                                        // cannot use isNewUser for this: somebody who abandoned
                                        // completion and signed in again is not new and still
                                        // needs it.
                                        .put("profileComplete",
                                             !Boolean.TRUE.equals(row.getBoolean("username_is_provisional")))
                                            .put("firstName",     userRepo.decryptField(row.getString("first_name")))
                                            .put("lastName",      userRepo.decryptField(row.getString("last_name")))
                                            .put("role",          row.getString("role"))
                                            .put("isBanned",      row.getBoolean("is_banned"))
                                            .put("hasContributed", contributed)
                                            .put("hasRatedCompany", ratedCompany)
                                        ).put("isNewUser", isNewUserHolder[0])
                                         // Never null in practice: safeReturnTo already defaulted
                                         // it at /start. Defaulted again so a null can never reach
                                         // the router as the string "null".
                                         .put("returnTo", txReturnTo == null ? DEFAULT_RETURN_TO : txReturnTo)
                                         .encode()); })
                                    .onFailure(ctx::fail);
                            })
                            .onFailure(err -> {
                                String msg = err.getMessage() != null ? err.getMessage().toLowerCase() : "";
                                if (msg.contains("unique") || msg.contains("duplicate") || msg.contains("23505")) {
                                    // Email already exists under a different auth0Id (email/password account)
                                    ctx.response().setStatusCode(409).putHeader("Content-Type", "application/json")
                                        .end(new JsonObject().put("error", "email_already_registered")
                                            .put("message", "An account with this email already exists. Please sign in with your password instead.").encode());
                                } else {
                                    internalError(ctx);
                                }
                            });
                    });
                });  // closes idTokens.verifiedSubject(...).onSuccess
            });
    }

    // ── SIGNOUT ───────────────────────────────────────────────────────────────

    // ── Application sessions ─────────────────────────────────────────────────

    /**
     * Null until {@link #setSessionRepository} is called, which is how the id-token validator is
     * wired too. Null means no session is ever issued, so a deployment that forgets the wiring
     * keeps working on the legacy credential rather than handing people a cookie nothing mints.
     */
    private org.werkpages.repository.SessionRepository sessionRepo;

    public void setSessionRepository(org.werkpages.repository.SessionRepository repo) {
        this.sessionRepo = repo;
    }

    /**
     * Mints the application session for a login that has just succeeded.
     *
     * <p>Returns empty when issuance is switched off, which is the default; see
     * {@link AuthTokenUtils#appSessionsEnabled}.
     *
     * <p><b>A mint failure fails the login.</b> The tempting alternative is to log it and let the
     * person in on the legacy token alone, and that is exactly the weakening to avoid: the session
     * is the credential that can be revoked, so silently falling back to the one that cannot would
     * turn a database blip into an account that no administrator can sign out. Better to refuse a
     * login than to issue an unrevocable one.
     */
    private io.vertx.core.Future<java.util.Optional<String>> mintSession(
            RoutingContext ctx, java.util.UUID userId) {
        if (sessionRepo == null || !AuthTokenUtils.appSessionsEnabled()) {
            return io.vertx.core.Future.succeededFuture(java.util.Optional.empty());
        }
        return sessionRepo.mint(
                userId,
                ctx.request().getHeader("User-Agent"),
                org.werkpages.repository.SessionRepository.hashAddress(
                    RateLimitHandler.clientIpOf(ctx)))
            .map(java.util.Optional::of);
    }

    /**
     * Adds the session cookie beside the ones already on the response.
     *
     * <p>{@code headers().add} rather than {@code putHeader}: putHeader REPLACES, and these
     * responses already carry an {@code auth_token} cookie. Using putHeader here would silently
     * drop that one, which during the period when both credentials exist would sign the person
     * out of every endpoint the session has not reached yet.
     */
    private static void addSessionCookie(RoutingContext ctx, java.util.Optional<String> minted) {
        minted.ifPresent(id ->
            ctx.response().headers().add("Set-Cookie", AuthTokenUtils.appSessionCookie(id)));
    }

    /**
     * Signing out, which now ENDS the session rather than asking the browser to forget it.
     *
     * <p>What this used to do was clear a cookie. That is not a sign-out: an id token stays valid
     * until it expires whatever the browser does with it, so anybody holding a copy - a shared
     * machine's cache, a proxy log, an attacker - kept the account for up to a day. Revoking the
     * row makes the very next request fail, everywhere, immediately.
     *
     * <p>It succeeds even when there is nothing to revoke. Signing out must never leave somebody
     * unsure whether it worked, so an absent cookie, an unknown session and an already-revoked one
     * are all a success; {@code revoke} is idempotent for exactly this reason.
     *
     * <p>Note that the endpoint is reachable without authentication. That is deliberate: a person
     * whose session has just expired still needs the cookies cleared, and refusing them a sign-out
     * because they are already signed out would be absurd. Revocation keys on the presented
     * cookie, so the worst a caller can do with somebody else's value is end a session they
     * already held.
     */
    public void handleSignout(RoutingContext ctx) {
        String sessionId;
        try {
            sessionId = AuthTokenUtils.sessionCookieValue(ctx);
        } catch (IllegalStateException duplicated) {
            // Two session cookies: end neither, clear both. Picking one to revoke would leave the
            // other live, and the other is the one an attacker planted.
            sessionId = null;
        }

        io.vertx.core.Future<Void> revoked = (sessionRepo == null || sessionId == null)
            ? io.vertx.core.Future.succeededFuture()
            : sessionRepo.revoke(sessionId);

        revoked
            .onComplete(ignored -> {
                /*
                  Cleared whatever happened, including on a database failure.

                  The alternative - refuse the sign-out so the person can retry - leaves them
                  holding a live cookie in a browser they are walking away from. Clearing on
                  failure can at worst leave a row that expires on its own schedule, which is
                  strictly the safer of the two.
                */
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .putHeader("Set-Cookie", "auth_token=; HttpOnly; Path=/; Max-Age=0");
                ctx.response().headers().add("Set-Cookie", AuthTokenUtils.clearedSessionCookie());
                if (ignored.failed()) {
                    System.err.println("signout: revocation failed, cookies cleared anyway: "
                        + ignored.cause().getMessage());
                }
                ctx.response().end(new JsonObject()
                    .put("success", true)
                    // Where the browser should send the person to end the Auth0 session too. The
                    // product session is already dead by this point; this only stops Auth0
                    // silently signing them straight back in on the next /authorize.
                    .put("auth0LogoutUrl", auth0LogoutUrl())
                    .encode());
            });
    }

    /**
     * Auth0's own logout, which is a SEPARATE session from the product's.
     *
     * <p>Revoking the row above ends the session with this product. It does nothing to the session
     * the person has with Auth0, so the next Universal Login would recognise them and return a
     * fresh token without asking for anything - which looks, to the person who just pressed sign
     * out, exactly like it did not work.
     *
     * <p>Returned for the browser to navigate to rather than fetched here: ending a cookie-backed
     * session requires the browser's own cookie jar, and a server-side call would end nothing.
     *
     * <p>Built from the tenant domain, not the custom domain, for the same reason the token
     * endpoints are: the two have separate session cookies, and logging out of one leaves the
     * other signed in.
     */
    private String auth0LogoutUrl() {
        return "https://" + auth0Domain + "/v2/logout?client_id=" + clientId;
    }

    // ── PROFILE COMPLETION ───────────────────────────────────────────────────

    /**
     * Claims a username for an account that was created without one.
     *
     * <p>Universal Login asks for an email and a password. It does not ask for a username, and
     * enabling Auth0's own "Requires Username" would create a second username field that nothing
     * in this product reads - so the account is created with a generated placeholder and this
     * finishes the job.
     *
     * <p>Validation is the SAME rule the old signup form used. A username the old path would have
     * refused must not become reachable through the new one; that is the shape of regression a
     * flow replacement produces, and it is the reason this calls the shared validator rather than
     * writing its own check.
     */
    public void handleCompleteProfile(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) {
            ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Unauthorized").encode());
            return;
        }
        JsonObject body = ctx.body() == null ? null : ctx.body().asJsonObject();
        String requested = body == null ? null : body.getString("username");
        String reason = usernameRefusalReason(requested);
        if (reason != null) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "invalid_username").put("message", reason).encode());
            return;
        }
        final String username = requested.trim();
        /*
          Names are optional here and were REQUIRED on the old signup form.

          That is a deliberate relaxation, not an oversight. The social path already fills them
          from the identity provider, so for most people arriving at this page they are already
          set; and a person who declines to give a surname should not be blocked from finishing
          an account, because the username is the only one of the three the product actually
          needs. A blank value leaves whatever is stored alone rather than erasing it.
        */
        final String firstName = trimmedOrNull(body.getString("firstName"));
        final String lastName  = trimmedOrNull(body.getString("lastName"));

        userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> opt.isEmpty()
                ? io.vertx.core.Future.<Boolean>failedFuture("not_found")
                : userRepo.claimUsername(opt.get(), username, firstName, lastName))
            .onSuccess(claimed -> {
                if (claimed) {
                    ctx.response().putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("success", true).put("username", username).encode());
                } else {
                    /*
                      The UPDATE matched nothing, which means the flag was already false. Reported
                      as a conflict rather than a success: a page that got 200 here would show
                      "username changed" while the username had not changed.
                    */
                    ctx.response().setStatusCode(409).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "profile_already_complete").encode());
                }
            })
            .onFailure(err -> {
                if ("not_found".equals(err.getMessage())) {
                    ctx.response().setStatusCode(404).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "User not found").encode());
                    return;
                }
                /*
                  A unique violation on users_username_ci means somebody else holds that name.

                  Detected from the constraint rather than by asking first: a check followed by a
                  write is a race two people hit at the same moment, and the index is what actually
                  decides. 23505 is PostgreSQL's unique_violation.
                */
                String sqlState = err instanceof io.vertx.pgclient.PgException pg ? pg.getSqlState() : null;
                if ("23505".equals(sqlState)) {
                    ctx.response().setStatusCode(409).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "username_taken").encode());
                } else {
                    internalError(ctx);
                }
            });
    }

    /**
     * Why a username is refused, or null when it is acceptable.
     *
     * <p>Length and character set only. Reserved-word and profanity policy, if either is ever
     * wanted, belongs in one shared place rather than here - and the shape check below is what
     * stops somebody claiming a name that looks generated, which would make
     * {@code looksProvisional} suppress their own display name on every review they write.
     */
    private static String trimmedOrNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : (t.length() > 100 ? t.substring(0, 100) : t);
    }

    private static String usernameRefusalReason(String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) return "A username is required.";
        String u = candidate.trim();
        if (u.length() < 3)  return "Usernames must be at least 3 characters.";
        if (u.length() > 30) return "Usernames must be at most 30 characters.";
        if (!u.matches("^[A-Za-z0-9._-]+$")) {
            return "Usernames may contain letters, numbers, dots, underscores and hyphens.";
        }
        if (u.matches("^user_[0-9a-f]{8}$")) {
            // The shape this system generates for placeholders. Allowing somebody to claim it
            // would have their own reviews published under a pseudonym for ever, because the
            // publication guard matches on shape.
            return "That username is not available.";
        }
        return null;
    }

    // ── ACCOUNT RECOVERY ─────────────────────────────────────────────────────

    /**
     * Null until wired, in which case recovery answers exactly as it does for a username that
     * matches nothing. A deployment that forgets the wiring must not announce the fact.
     */
    private org.werkpages.service.AccountRecoveryService recoveryService;

    public void setAccountRecoveryService(org.werkpages.service.AccountRecoveryService service) {
        this.recoveryService = service;
    }

    /**
     * Recovery by username.
     *
     * <p><b>202, always, immediately.</b> The response is written BEFORE the recovery work is
     * started, and that ordering is the timing defence: an existing username and a missing one
     * differ by a row fetched rather than by an SES round trip, because neither is on the response
     * path at all.
     *
     * <p>It is also why there is nothing to report. The handler cannot tell the caller whether an
     * account matched, because by the time anybody knows, the reply has gone.
     */
    public void handleRecoverAccount(RoutingContext ctx) {
        JsonObject body = ctx.body() == null ? null : ctx.body().asJsonObject();
        String username = body == null ? null : body.getString("username");

        // The same words either way. Deliberately not "we have emailed you", which would be a
        // claim the server cannot make yet and would be false for a username nobody holds.
        ctx.response().setStatusCode(202).putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("message",
                "If an account matches that username, we have sent a message to its email address.")
                .encode());

        if (recoveryService == null || username == null) return;
        recoveryService.recover(username,
                org.werkpages.repository.SessionRepository.hashAddress(
                    RateLimitHandler.clientIpOf(ctx)))
            // recover() already swallows its own failures; this is belt and braces so an
            // unexpected one cannot become an unhandled rejection after the response is gone.
            .onFailure(err -> System.err.println(
                "recovery failed after responding: " + err.getClass().getSimpleName()));
    }

    // ── SESSIONS ──────────────────────────────────────────────────────────────

    /**
     * The sessions this account has open, so somebody can see where they are signed in.
     *
     * <p>Shows the hashed address to nobody: the stored hash is not reversible and would mean
     * nothing on screen anyway. What is useful is the browser string and when it was last used,
     * which is enough to recognise "that is my phone" or "that is not me".
     *
     * <p>{@code current} marks the session making the request, so the person can tell which one
     * they would be ending.
     */
    /** The presented session, or null, never throwing: a duplicate cookie is simply "none". */
    private static String safeSessionCookie(RoutingContext ctx) {
        try {
            return AuthTokenUtils.sessionCookieValue(ctx);
        } catch (IllegalStateException duplicated) {
            return null;
        }
    }

    public void handleListMySessions(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null || sessionRepo == null) {
            ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Unauthorized").encode());
            return;
        }
        // May be null: an account signed in on the legacy token alone has no session to mark.
        final String presented = safeSessionCookie(ctx);
        userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> opt.isEmpty()
                ? io.vertx.core.Future.failedFuture("not_found")
                : sessionRepo.liveSessionsFor(opt.get(), presented))
            .onSuccess(rows -> {
                io.vertx.core.json.JsonArray out = new io.vertx.core.json.JsonArray();
                rows.forEach(row -> out.add(new JsonObject()
                    .put("product",    row.getString("product"))
                    .put("createdAt",  String.valueOf(row.getOffsetDateTime("created_at")))
                    .put("lastSeenAt", String.valueOf(row.getOffsetDateTime("last_seen_at")))
                    .put("expiresAt",  String.valueOf(row.getOffsetDateTime("expires_at")))
                    .put("userAgent",  row.getString("user_agent"))
                    .put("current",    Boolean.TRUE.equals(row.getBoolean("current")))));
                ctx.response().putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("sessions", out).encode());
            })
            .onFailure(err -> {
                if ("not_found".equals(err.getMessage())) {
                    ctx.response().setStatusCode(404).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "User not found").encode());
                } else {
                    internalError(ctx);
                }
            });
    }

    /**
     * Sign out everywhere, including the session making the request.
     *
     * <p>This is the control a person needs after "was that login from Brazil me?", and it is the
     * reason sessions are a table rather than a token. It crosses the product boundary
     * deliberately: somebody who believes their account is compromised means every session, and
     * being signed out of WerkPages while still signed in to RateMyManagers would be a surprise of
     * the worst kind.
     *
     * <p>No actor is recorded, because the actor is the account holder; the audit row exists to
     * answer "which administrator did this", and nobody did.
     */
    public void handleRevokeMySessions(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null || sessionRepo == null) {
            ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Unauthorized").encode());
            return;
        }
        userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> opt.isEmpty()
                ? io.vertx.core.Future.<Integer>failedFuture("not_found")
                : sessionRepo.revokeAllForUser(opt.get(), "signout_everywhere", null))
            .onSuccess(count -> {
                // The request's own session is among the revoked, so clear the cookie too.
                ctx.response().putHeader("Content-Type", "application/json")
                    .putHeader("Set-Cookie", "auth_token=; HttpOnly; Path=/; Max-Age=0");
                ctx.response().headers().add("Set-Cookie", AuthTokenUtils.clearedSessionCookie());
                ctx.response().end(new JsonObject()
                    .put("success", true)
                    .put("sessionsRevoked", count)
                    .put("auth0LogoutUrl", auth0LogoutUrl())
                    .encode());
            })
            .onFailure(err -> {
                if ("not_found".equals(err.getMessage())) {
                    ctx.response().setStatusCode(404).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "User not found").encode());
                } else {
                    internalError(ctx);
                }
            });
    }

    // ── DELETE ME ─────────────────────────────────────────────────────────────

    public void handleDeleteMe(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) {
            ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Unauthorized").encode()); return;
        }
        userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) {
                    return io.vertx.core.Future.failedFuture("not_found");
                }
                return userRepo.deleteWithReviewAnonymization(opt.get());
            })
            .onSuccess(v ->
                ctx.response().setStatusCode(200)
                    .putHeader("Set-Cookie", "auth_token=; HttpOnly; Path=/; Max-Age=0")
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("success", true).put("message", "Account deleted and reviews anonymized").encode())
            )
            .onFailure(err -> {
                if ("not_found".equals(err.getMessage())) {
                    ctx.response().setStatusCode(404).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "User not found").encode());
                } else {
                    internalError(ctx);
                }
            });
    }

    /**
     * Auth0 returns an opaque access token when the authorization request named no audience.
     * A JWT is three dot-separated segments; anything else cannot be decoded or validated.
     */
    static boolean looksLikeJwt(String token) {
        return token != null && !token.isBlank() && token.chars().filter(c -> c == '.').count() == 2;
    }

    private static void internalError(RoutingContext ctx) {
        ctx.response().setStatusCode(500).putHeader("Content-Type", "application/json")
            .end("{\"error\":\"internal_error\",\"message\":\"An unexpected error occurred. Please try again.\"}");
    }

    /**
     * One refusal for every binding failure: absent cookie, bad signature, expired, mismatched
     * state. Distinguishing them in the response would tell an attacker which half of their
     * forgery was wrong, and the user can do nothing different about any of them.
     *
     * <p>The message is the string AuthCallback.tsx already renders, so a refused login lands on
     * the sign-in page asking for a retry rather than on a blank screen.
     */
    private void refuseBinding(RoutingContext ctx, boolean isProduction) {
        // The browser cookie is NOT cleared on a refusal. It is not a credential, and clearing it
        // would strand this browser's other tabs mid-login because of one bad callback - which an
        // attacker could trigger deliberately.
        ctx.response().setStatusCode(400)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject()
                .put("error", "invalid_login_transaction")
                .put("message", "Sign-in was not completed. Please try again.").encode());
    }
}

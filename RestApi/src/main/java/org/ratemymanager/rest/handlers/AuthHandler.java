package org.ratemymanager.rest.handlers;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.ratemymanager.repository.UserRepository;

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
    private final String turnstileSecretKey;

    public AuthHandler(UserRepository userRepo, String auth0Domain, String clientId,
                       String clientSecret, String audience, String turnstileSecretKey, Vertx vertx) {
        this.userRepo          = userRepo;
        this.auth0Domain       = auth0Domain;
        this.clientId          = clientId;
        this.clientSecret      = clientSecret;
        this.audience          = audience;
        this.turnstileSecretKey= turnstileSecretKey;
        this.webClient         = WebClient.create(vertx);
    }

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

    // ── SIGNUP ────────────────────────────────────────────────────────────────

    public void handleSignup(RoutingContext ctx) {
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) { ctx.response().setStatusCode(400).end("{\"error\":\"Missing body\"}"); return; }

        String email     = body.getString("email");
        String username  = body.getString("username");
        String firstName = body.getString("firstName");
        String lastName  = body.getString("lastName");
        String password  = body.getString("password");

        if (ValidationUtils.isBlank(email) || ValidationUtils.isBlank(username) ||
            ValidationUtils.isBlank(firstName) || ValidationUtils.isBlank(lastName) ||
            ValidationUtils.isBlank(password)) {
            ValidationUtils.badRequest(ctx, "Missing required fields"); return;
        }
        if (!ValidationUtils.isValidEmail(email)) { ValidationUtils.badRequest(ctx, "Invalid email format"); return; }
        if (ValidationUtils.exceedsLength(email, 254)) { ValidationUtils.badRequest(ctx, "Email must be at most 254 characters"); return; }
        if (firstName.trim().length() > 50) { ValidationUtils.badRequest(ctx, "First name must be at most 50 characters"); return; }
        if (lastName.trim().length()  > 50) { ValidationUtils.badRequest(ctx, "Last name must be at most 50 characters"); return; }
        if (username.trim().length() < 3 || username.trim().length() > 30) { ValidationUtils.badRequest(ctx, "Username must be between 3 and 30 characters"); return; }
        if (password.length() < 8 || password.length() > 128) { ValidationUtils.badRequest(ctx, "Password must be between 8 and 128 characters"); return; }

        String emailDomain = email.substring(email.lastIndexOf('@') + 1).toLowerCase();
        if (DISPOSABLE_DOMAINS.contains(emailDomain)) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "disposable_email")
                    .put("message", "Disposable or temporary email addresses are not allowed. Please use a permanent email address.").encode());
            return;
        }

        String turnstileToken = body.getString("turnstileToken");
        verifyTurnstile(turnstileToken, ctx, () ->
            proceedWithAuth0Signup(ctx, email, username, firstName, lastName, password));
    }

    private void proceedWithAuth0Signup(RoutingContext ctx, String email, String username,
                                         String firstName, String lastName, String password) {
        userRepo.findEmailByUsername(username)
            .onSuccess(existing -> {
                if (existing.isPresent()) {
                    ctx.response().setStatusCode(409).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "username_taken")
                            .put("message", "That username is already taken. Please choose another.").encode());
                    return;
                }
                doAuth0Signup(ctx, email, username, firstName, lastName, password);
            })
            .onFailure(ctx::fail);
    }

    private void doAuth0Signup(RoutingContext ctx, String email, String username,
                                String firstName, String lastName, String password) {
        JsonObject auth0Payload = new JsonObject()
            .put("client_id", this.clientId)
            .put("email", email)
            .put("password", password)
            .put("connection", "Username-Password-Authentication")
            .put("user_metadata", new JsonObject()
                .put("firstName", firstName).put("lastName", lastName).put("username", username));

        this.webClient.post(443, auth0Domain, "/dbconnections/signup")
            .ssl(true).timeout(10_000).putHeader("Content-Type", "application/json")
            .sendJsonObject(auth0Payload, ar -> {
                if (ar.failed()) { internalError(ctx); return; }
                HttpResponse<Buffer> response = ar.result();
                if (response.statusCode() != 200) {
                    String rawBody = response.bodyAsString();
                    System.err.println("Auth0 signup error: " + response.statusCode() + " - " + rawBody);
                    String userMessage = "Registration failed. Please check your details and try again.";
                    String errorCode   = "registration_failed";
                    try {
                        JsonObject auth0Err = response.bodyAsJsonObject();
                        String code = auth0Err.getString("code", "");
                        String desc = auth0Err.getString("description", auth0Err.getString("message", ""));
                        if ("user_exists".equals(code) || "invalid_signup".equals(code)) {
                            errorCode   = "email_already_registered";
                            userMessage = "An account with this email already exists. Please sign in instead.";
                        } else if ("invalid_password".equals(code)) {
                            errorCode   = "invalid_password";
                            userMessage = "Password does not meet the requirements. Please choose a stronger password.";
                        } else if (desc != null && !desc.isBlank()) {
                            userMessage = desc;
                        }
                    } catch (Exception ignored) {}
                    ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", errorCode).put("message", userMessage).encode());
                    return;
                }
                JsonObject auth0User = response.bodyAsJsonObject();
                String rawId = auth0User.getString("_id");
                if (rawId == null) { ctx.response().setStatusCode(500).end("{\"error\":\"Auth0 did not return a user ID\"}"); return; }
                String auth0Id = "auth0|" + rawId;

                userRepo.create(auth0Id, email, username, firstName, lastName)
                    .onSuccess(ignored ->
                        ctx.response().setStatusCode(201).putHeader("Content-Type", "application/json")
                            .end(new JsonObject()
                                .put("id", auth0Id).put("email", email).put("username", username)
                                .put("firstName", firstName).put("lastName", lastName)
                                .put("createdAt", java.time.OffsetDateTime.now().toString()).encode())
                    )
                    .onFailure(err -> {
                        System.err.println("Database Error: " + err.getMessage());
                        String msg = err.getMessage() != null ? err.getMessage().toLowerCase() : "";
                        if (msg.contains("unique") || msg.contains("duplicate") || msg.contains("23505")) {
                            if (msg.contains("username")) {
                                ctx.response().setStatusCode(409).putHeader("Content-Type", "application/json")
                                    .end(new JsonObject().put("error", "username_taken")
                                        .put("message", "That username is already taken. Please choose another.").encode());
                            } else {
                                ctx.response().setStatusCode(409).putHeader("Content-Type", "application/json")
                                    .end(new JsonObject().put("error", "email_already_registered")
                                        .put("message", "An account with this email already exists. Please sign in instead.").encode());
                            }
                        } else {
                            internalError(ctx);
                        }
                    });
            });
    }

    // ── TURNSTILE ─────────────────────────────────────────────────────────────

    private void verifyTurnstile(String token, RoutingContext ctx, Runnable onVerified) {
        if (turnstileSecretKey == null || turnstileSecretKey.isBlank()) { onVerified.run(); return; }
        if (token == null || token.isBlank()) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "captcha_required")
                    .put("message", "Please complete the CAPTCHA to continue.").encode());
            return;
        }
        JsonObject payload = new JsonObject().put("secret", turnstileSecretKey).put("response", token);
        this.webClient.post(443, "challenges.cloudflare.com", "/turnstile/v0/siteverify")
            .ssl(true).timeout(10_000).sendJsonObject(payload, ar -> {
                if (ar.failed()) { internalError(ctx); return; }
                if (!ar.result().bodyAsJsonObject().getBoolean("success", false)) {
                    ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "captcha_failed")
                            .put("message", "CAPTCHA verification failed. Please refresh and try again.").encode());
                    return;
                }
                onVerified.run();
            });
    }

    // ── SIGNIN ────────────────────────────────────────────────────────────────

    public void handleSignin(RoutingContext ctx) {
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) { ctx.response().setStatusCode(400).end("{\"error\":\"Missing body\"}"); return; }
        // Accept either "identifier" (new) or "email" (legacy) field name
        String identifier = body.getString("identifier", body.getString("email"));
        String password   = body.getString("password");
        if (identifier == null || identifier.isBlank() || password == null) {
            ctx.response().setStatusCode(400).end("{\"error\":\"Missing credentials\"}"); return;
        }

        // If the identifier looks like an email, use it directly; otherwise resolve via username lookup
        boolean looksLikeEmail = identifier.contains("@");
        if (!looksLikeEmail) {
            userRepo.findEmailByUsername(identifier)
                .onSuccess(opt -> {
                    // Always make the Auth0 call regardless — even with a dummy email when the
                    // username is unknown — so timing is identical whether the username exists or not.
                    String email = opt.orElse("no-such-user@timing-equalizer.invalid");
                    doSignin(ctx, email, password, opt.isPresent());
                })
                .onFailure(err -> ctx.response().setStatusCode(500).end("{\"error\":\"Internal error\"}"));
            return;
        }

        doSignin(ctx, identifier, password, true);
    }

    private void doSignin(RoutingContext ctx, String email, String password) {
        doSignin(ctx, email, password, true);
    }

    private void doSignin(RoutingContext ctx, String email, String password, boolean knownUser) {
        JsonObject payload = new JsonObject()
            .put("grant_type", "password").put("username", email).put("password", password)
            .put("connection", "Username-Password-Authentication")
            .put("audience", this.audience).put("client_id", this.clientId).put("client_secret", this.clientSecret);

        this.webClient.post(443, auth0Domain, "/oauth/token")
            .ssl(true).timeout(10_000).putHeader("Content-Type", "application/json")
            .sendJsonObject(payload, ar -> {
                // Username was unknown — the Auth0 call was made only for timing parity.
                // Always return a generic 401; never process the response.
                if (!knownUser) {
                    ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "authentication_failed").put("message", "Invalid credentials.").encode());
                    return;
                }
                if (ar.failed()) {
                    System.err.println("Auth0 Connection Error: " + ar.cause().getMessage());
                    ctx.response().setStatusCode(500).end("{\"error\":\"Could not connect to Auth0\"}"); return;
                }
                HttpResponse<Buffer> response = ar.result();
                if (response.statusCode() != 200) {
                    System.err.println("Auth0 signin error: " + response.statusCode() + " - " + response.bodyAsString());
                    String responseBody = response.bodyAsString();
                    boolean isUnverified = responseBody != null && responseBody.toLowerCase().contains("verify");
                    if (isUnverified) {
                        ctx.response().setStatusCode(403).putHeader("Content-Type", "application/json")
                            .end(new JsonObject().put("error", "email_not_verified").put("message", "Please verify your email before signing in.").encode());
                    } else {
                        ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                            .end(new JsonObject().put("error", "authentication_failed").put("message", "Invalid email or password.").encode());
                    }
                    return;
                }
                String accessToken = response.bodyAsJsonObject().getString("access_token");
                try {
                    String auth0Id = com.auth0.jwt.JWT.decode(accessToken).getSubject();
                    userRepo.findByAuth0IdForSignin(auth0Id)
                        .onSuccess(opt -> {
                            if (opt.isEmpty()) {
                                ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                                    .end(new JsonObject().put("error", "authentication_failed").put("message", "Invalid credentials.").encode());
                                return;
                            }
                            io.vertx.sqlclient.Row row = opt.get();
                            java.util.UUID userId = row.getUUID("id");
                            boolean isProd = "true".equalsIgnoreCase(System.getenv("USE_AWS_SECRETS"));
                            String setCookie = "auth_token=" + accessToken
                                + "; HttpOnly; Path=/; Max-Age=86400; SameSite=Lax" + (isProd ? "; Secure" : "");
                            userRepo.hasContributed(userId)
                                .onSuccess(contributed -> ctx.response()
                                    .putHeader("Set-Cookie", setCookie)
                                    .putHeader("Content-Type", "application/json")
                                    .end(new JsonObject().put("user", new JsonObject()
                                        .put("id",            userId.toString())
                                        .put("email",         userRepo.decryptField(row.getString("email")))
                                        .put("username",      row.getString("username"))
                                        .put("firstName",     userRepo.decryptField(row.getString("first_name")))
                                        .put("lastName",      userRepo.decryptField(row.getString("last_name")))
                                        .put("role",          row.getString("role"))
                                        .put("isBanned",      row.getBoolean("is_banned"))
                                        .put("hasContributed", contributed)
                                    ).encode()))
                                .onFailure(ctx::fail);
                        })
                        .onFailure(ctx::fail);
                } catch (Exception e) {
                    System.err.println("JWT Decoding Error: " + e.getMessage());
                    ctx.response().setStatusCode(500).end("{\"error\":\"Failed to process authentication token\"}");
                }
            });
    }

    // ── ME ────────────────────────────────────────────────────────────────────

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
                userRepo.hasContributed(userId)
                    .onSuccess(contributed -> ctx.response().setStatusCode(200)
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
                            .put("createdAt",      row.getLocalDateTime("created_at").toString())
                            .encode()))
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

        JsonObject tokenPayload = new JsonObject()
            .put("grant_type", "authorization_code")
            .put("client_id", this.clientId)
            .put("client_secret", this.clientSecret)
            .put("code", code)
            .put("redirect_uri", redirectUri)
            .put("audience", this.audience);

        this.webClient.post(443, auth0Domain, "/oauth/token")
            .ssl(true).timeout(10_000).putHeader("Content-Type", "application/json")
            .sendJsonObject(tokenPayload, ar -> {
                if (ar.failed()) { internalError(ctx); return; }
                if (ar.result().statusCode() != 200) {
                    System.err.println("Auth0 code exchange error: " + ar.result().statusCode() + " - " + ar.result().bodyAsString());
                    ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "token_exchange_failed")
                            .put("message", "Authentication failed. Please try again.").encode()); return;
                }
                JsonObject tokens    = ar.result().bodyAsJsonObject();
                String accessToken   = tokens.getString("access_token");
                String idToken       = tokens.getString("id_token");
                String auth0Id;
                try {
                    // id_token is always a JWT — use it for identity; fall back to access_token
                    String tokenToDecode = idToken != null ? idToken : accessToken;
                    auth0Id = com.auth0.jwt.JWT.decode(tokenToDecode).getSubject();
                } catch (Exception e) {
                    System.err.println("Failed to decode token. id_token=" + idToken + " access_token=" + accessToken);
                    ctx.response().setStatusCode(500).end("{\"error\":\"Failed to decode token\"}"); return;
                }

                // Fetch user profile from Auth0 /userinfo
                final String fAuth0Id     = auth0Id;
                final String fAccessToken = accessToken;
                // id_token is always a JWT (OIDC); access_token may be opaque when no audience is
                // included in the authorization request, causing JWT validation to fail on subsequent
                // API calls. Prefer id_token for the session cookie.
                final String fTokenForCookie = (idToken != null && !idToken.isBlank()) ? idToken : accessToken;
                this.webClient.get(443, auth0Domain, "/userinfo")
                    .ssl(true).timeout(10_000)
                    .putHeader("Authorization", "Bearer " + accessToken)
                    .send(uiAr -> {
                        if (uiAr.failed()) { internalError(ctx); return; }
                        JsonObject info      = uiAr.result().bodyAsJsonObject();
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
                                String username = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                                return userRepo.create(fAuth0Id, fEmail, username, fFirstName, fLastName)
                                    .compose(ignored -> userRepo.findByAuth0IdWithBan(fAuth0Id))
                                    .map(java.util.Optional::get);
                            })
                            .onSuccess(row -> {
                                if (row.getBoolean("is_banned")) {
                                    ctx.response().setStatusCode(403).putHeader("Content-Type", "application/json")
                                        .end(new JsonObject().put("error", "account_suspended").encode()); return;
                                }
                                boolean isProd = "true".equalsIgnoreCase(System.getenv("USE_AWS_SECRETS"));
                                String setCookie = "auth_token=" + fTokenForCookie
                                    + "; HttpOnly; Path=/; Max-Age=86400; SameSite=Lax" + (isProd ? "; Secure" : "");
                                java.util.UUID socialUserId = row.getUUID("id");
                                userRepo.hasContributed(socialUserId)
                                    .onSuccess(contributed -> ctx.response()
                                        .putHeader("Set-Cookie", setCookie)
                                        .putHeader("Content-Type", "application/json")
                                        .end(new JsonObject().put("user", new JsonObject()
                                            .put("id",            socialUserId.toString())
                                            .put("email",         userRepo.decryptField(row.getString("email")))
                                            .put("username",      row.getString("username"))
                                            .put("firstName",     userRepo.decryptField(row.getString("first_name")))
                                            .put("lastName",      userRepo.decryptField(row.getString("last_name")))
                                            .put("role",          row.getString("role"))
                                            .put("isBanned",      row.getBoolean("is_banned"))
                                            .put("hasContributed", contributed)
                                        ).put("isNewUser", isNewUserHolder[0]).encode()))
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
            });
    }

    // ── SIGNOUT ───────────────────────────────────────────────────────────────

    public void handleSignout(RoutingContext ctx) {
        ctx.response()
            .putHeader("Set-Cookie", "auth_token=; HttpOnly; Path=/; Max-Age=0")
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("success", true).encode());
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

    private static void internalError(RoutingContext ctx) {
        ctx.response().setStatusCode(500).putHeader("Content-Type", "application/json")
            .end("{\"error\":\"internal_error\",\"message\":\"An unexpected error occurred. Please try again.\"}");
    }
}

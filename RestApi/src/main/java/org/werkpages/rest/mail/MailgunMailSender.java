package org.werkpages.rest.mail;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.werkpages.mail.MailSender;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Mailgun, through its HTTP messages API.
 *
 * <h2>Why Mailgun and not something else</h2>
 *
 * <p>Because it is already the provider. Auth0 sends this product's verification and
 * password-reset messages through a Mailgun SMTP configuration, and has for as long as those
 * messages have existed. Introducing a second provider for the mail the application originates
 * itself would mean two sending reputations, two sets of DNS records, two dashboards to look at
 * when somebody says a message never arrived, and two places a sending domain can fall out of
 * step - for no benefit, since the existing one already delivers for this domain.
 *
 * <p>It also costs nothing to add. The HTTP API needs a {@link WebClient}, which this module
 * already has; an SDK would have been a new dependency.
 *
 * <h2>The HTTP API rather than SMTP</h2>
 *
 * <p>SMTP from an event loop means either a blocking client on the wrong thread or a second async
 * mail library. The messages API is a form POST, which the WebClient already does, and it returns
 * a message id in its response that is worth having in a log.
 *
 * <h2>It refuses unless outbound mail is explicitly switched on</h2>
 *
 * <p>Every send checks {@code mail.network}, the same shape as {@code resolver.network} in Api's
 * Failsafe configuration, and refuses when it is not {@code on}. "No credential is configured" is
 * NOT the protection - a machine that has the production key in its environment has it whether or
 * not anybody intended a send - and this project has already burned a metered API's monthly quota
 * from a test suite and blanked production logos doing it. The switch is off by default and no
 * test turns it on.
 */
public final class MailgunMailSender implements MailSender {

    /** US by default. Mailgun's EU region is a different host, and sending to the wrong one 404s. */
    public static final String US_BASE_URL = "https://api.mailgun.net";

    private final WebClient http;
    private final String baseUrl;
    private final String domain;
    private final String authorization;
    private final String fromAddress;

    /**
     * @param domain      the Mailgun sending domain, which is the one in the API path
     * @param apiKey      the private API key. Never logged, and only ever used to build the
     *                    Authorization header below.
     * @param fromAddress the From address. Must belong to {@code domain} or Mailgun refuses.
     * @param baseUrl     {@link #US_BASE_URL}, or Mailgun's EU host
     */
    public MailgunMailSender(Vertx vertx, String domain, String apiKey, String fromAddress,
                             String baseUrl) {
        if (domain == null || domain.isBlank())      throw new IllegalArgumentException("no Mailgun domain");
        if (apiKey == null || apiKey.isBlank())      throw new IllegalArgumentException("no Mailgun API key");
        if (fromAddress == null || fromAddress.isBlank()) throw new IllegalArgumentException("no From address");
        this.http = WebClient.create(vertx, new WebClientOptions().setSsl(true));
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? US_BASE_URL : baseUrl;
        this.domain = domain;
        this.fromAddress = fromAddress;
        // Basic auth, username literally "api". Built once rather than per send, so the key is
        // encoded in one place and never appears in a request-building expression.
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
            ("api:" + apiKey).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Future<String> send(MailMessage message) {
        if (!outboundMailEnabled()) {
            return Future.failedFuture(
                "outbound mail is off (mail.network is not 'on'); nothing was sent");
        }
        MultiMap form = MultiMap.caseInsensitiveMultiMap()
            .set("from", fromAddress)
            .set("to", message.to())
            .set("subject", message.subject())
            .set("text", message.body());

        return http.postAbs(baseUrl + "/v3/" + domain + "/messages")
            .putHeader("Authorization", authorization)
            .sendForm(form)
            .compose(response -> {
                if (response.statusCode() / 100 != 2) {
                    /*
                      The status, never the body.

                      Mailgun echoes the recipient in its error responses, and a recovery message's
                      recipient is the private address the whole design keeps out of logs. The
                      status code is enough to act on: 401 is the key, 400 is the message, 404 is
                      the domain or the wrong region.
                    */
                    return Future.failedFuture(
                        "Mailgun refused the message, HTTP " + response.statusCode());
                }
                io.vertx.core.json.JsonObject body = response.bodyAsJsonObject();
                return Future.succeededFuture(body == null ? "accepted" : body.getString("id"));
            })
            .recover(err -> Future.failedFuture(
                "Mailgun send failed: " + err.getClass().getSimpleName()));
    }

    public static boolean outboundMailEnabled() {
        return "on".equalsIgnoreCase(System.getProperty("mail.network"))
            || "on".equalsIgnoreCase(System.getenv("MAIL_NETWORK"));
    }
}

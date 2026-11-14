package org.werkpages.rest.mail;

import io.vertx.core.Vertx;
import org.werkpages.mail.MailSender;
import org.werkpages.mail.RecordingMailSender;

/**
 * THE ONE PLACE THE MAIL PROVIDER IS CHOSEN.
 *
 * <p>Nothing else constructs a {@link MailgunMailSender}. A second place that could would be a
 * second place where a test, or a local run, could reach a billed API that mails real people.
 *
 * <p>Mailgun is used only when ALL of these hold:
 *
 * <ul>
 *   <li>{@code mail.network} (or {@code MAIL_NETWORK}) is {@code on};
 *   <li>{@code MAILGUN_DOMAIN} names the sending domain;
 *   <li>{@code MAILGUN_API_KEY} holds the private API key;
 *   <li>{@code MAIL_FROM_ADDRESS} names the From address, which must belong to that domain.
 * </ul>
 *
 * <p>{@code MAILGUN_BASE_URL} overrides the host, for Mailgun's EU region. Sending to the wrong
 * region's host is a 404, not a misdelivery.
 *
 * <p>Otherwise the recording sender, which sends nothing. There is no partial state: a deployment
 * that sets the flag but forgets the key gets the recording sender and a line on stderr saying so,
 * rather than an exception at the moment somebody tries to recover their account.
 *
 * <p>These are the same Mailgun credentials Auth0's own SMTP configuration uses the account for.
 * Auth0 keeps sending its verification and password-reset mail exactly as it does today; this is
 * only for the mail the application originates itself, which previously had no provider at all.
 */
public final class MailSenders {

    private MailSenders() {}

    public static MailSender fromEnvironment(Vertx vertx) {
        if (!MailgunMailSender.outboundMailEnabled()) {
            return new RecordingMailSender();
        }
        String domain = System.getenv("MAILGUN_DOMAIN");
        String apiKey = System.getenv("MAILGUN_API_KEY");
        String from   = System.getenv("MAIL_FROM_ADDRESS");
        if (isBlank(domain) || isBlank(apiKey) || isBlank(from)) {
            System.err.println("outbound mail is enabled but MAILGUN_DOMAIN, MAILGUN_API_KEY or "
                + "MAIL_FROM_ADDRESS is unset; falling back to the recording sender, so NOTHING "
                + "WILL BE SENT");
            return new RecordingMailSender();
        }
        return new MailgunMailSender(vertx, domain, apiKey, from,
                                     System.getenv("MAILGUN_BASE_URL"));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}

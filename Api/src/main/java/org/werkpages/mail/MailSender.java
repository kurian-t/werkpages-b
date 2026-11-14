package org.werkpages.mail;

import io.vertx.core.Future;

/**
 * Mail the PRODUCT sends, as opposed to mail Auth0 sends.
 *
 * <p>Auth0 has always sent the verification and password-reset messages through its own Mailgun
 * SMTP configuration, and nothing here changes that. What had no provider at all was mail this
 * application originates: {@code ProofChallengeService} carried the comment
 * {@code // once a mail provider exists.} for exactly this reason, and username recovery could be
 * designed and approved but not built.
 *
 * <p>One interface, two implementations, and the choice is made in one place - {@code
 * MailSenders.fromEnvironment} in the RestApi module - so that no caller can accidentally reach
 * the metered one.
 *
 * <ul>
 *   <li>{@code MailgunMailSender} sends through Mailgun's HTTP messages API. It REFUSES unless
 *       outbound mail is explicitly switched on. It lives in RestApi rather than here because it
 *       needs a Vert.x {@code WebClient}, which that module already has - so the provider costs
 *       this module no dependency at all.
 *   <li>{@link RecordingMailSender} records the message and sends nothing. It is what local
 *       development and every test use, and it is the DEFAULT.
 * </ul>
 *
 * <p>Mailgun because it is already the provider: Auth0 sends this product's verification and
 * password-reset mail through a Mailgun SMTP configuration today. A second provider would mean two
 * sending reputations, two sets of DNS records and two dashboards, for mail to the same people
 * from the same domain.
 *
 * <p>The default is the recording one. That is not a convenience: sending real mail from a test is
 * both a billed call and a message to a real person, and this project has already had a metered
 * API's monthly quota burned by a test suite. The protection must be the default rather than a
 * setting somebody remembers.
 */
public interface MailSender {

    /**
     * Sends one message.
     *
     * <p>The returned future carries the provider's message id, which is worth having in a log
     * when somebody says a message never arrived. It fails when the message was not accepted;
     * callers that must not fail on account of mail - recovery is one, because its response has to
     * be identical whether or not an account exists - recover from it themselves.
     */
    Future<String> send(MailMessage message);

    /**
     * One message. A record, because a message is a value and nothing should be able to alter one
     * after a caller has handed it over.
     *
     * @param to      the single recipient. No multi-recipient send exists on purpose: every
     *                message this product originates goes to one account's own address, and a list
     *                is how one accidentally goes to several.
     * @param subject the subject line
     * @param body    the plain-text body. No HTML part: these are short transactional messages,
     *                and an HTML body is a second thing to keep in step and a place for an
     *                injected link to hide.
     */
    record MailMessage(String to, String subject, String body) {
        public MailMessage {
            if (to == null || to.isBlank())      throw new IllegalArgumentException("no recipient");
            if (subject == null || subject.isBlank()) throw new IllegalArgumentException("no subject");
            if (body == null || body.isBlank())  throw new IllegalArgumentException("no body");
            // A newline in a subject is header injection. SES would reject it, but failing here
            // means the attempt is visible in a stack trace rather than in an SES error code.
            if (subject.indexOf('\n') >= 0 || subject.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("newline in subject");
            }
        }
    }
}

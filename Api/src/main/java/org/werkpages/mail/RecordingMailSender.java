package org.werkpages.mail;

import io.vertx.core.Future;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The mail sender that sends nothing, and is the DEFAULT.
 *
 * <p>Used by every test and by local development. It keeps the messages so a test can assert what
 * would have been sent, and prints a one-line summary so a developer running the application can
 * see that a message was produced and to whom.
 *
 * <p><b>The address is printed, the body is not.</b> A recovery message's body names the account's
 * own email address, and the whole point of the recovery design is that the address never reaches a
 * log. Printing the body here would put it in {@code /tmp/werkpages-backend.log} on a machine where
 * somebody might paste the log into a bug report. The address IS the recipient, which a mail log
 * necessarily knows; what it must not also know is who asked for it.
 */
public final class RecordingMailSender implements MailSender {

    private final List<MailMessage> sent = new CopyOnWriteArrayList<>();
    private final boolean quiet;

    public RecordingMailSender() { this(false); }

    /** @param quiet true suppresses the console line, for suites that send many messages. */
    public RecordingMailSender(boolean quiet) { this.quiet = quiet; }

    @Override
    public Future<String> send(MailMessage message) {
        sent.add(message);
        if (!quiet) {
            System.out.println("[mail: not sent, no provider configured] to=" + message.to()
                + " subject=" + message.subject());
        }
        return Future.succeededFuture("recorded-" + UUID.randomUUID());
    }

    /** Every message handed over, oldest first. */
    public List<MailMessage> sent() { return List.copyOf(sent); }

    public void clear() { sent.clear(); }
}

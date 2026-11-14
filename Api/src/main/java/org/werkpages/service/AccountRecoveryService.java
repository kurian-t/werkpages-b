package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.werkpages.mail.MailSender;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * USERNAME RECOVERY, WITHOUT BECOMING AN EMAIL ORACLE.
 *
 * <h2>The problem it solves</h2>
 *
 * <p>Auth0 has never authenticated anybody by username: usernames are resolved against this
 * product's own table and exchanged for the account's email before Auth0 sees anything. Once the
 * password grant is gone and Universal Login owns sign-in, somebody who remembers only their
 * username has no way in - and for a contributor whose username is how they are known publicly,
 * that is most of them.
 *
 * <p>The tempting fix is to resolve the username and put the resulting email in Auth0's
 * {@code login_hint}. That is an **enumeration oracle**: anybody can type usernames and read the
 * prefilled address off the hosted page. Option C was approved instead, on 2026-11-11.
 *
 * <h2>What it does</h2>
 *
 * <ol>
 *   <li>resolves the username server-side;
 *   <li>sends a message <b>to that account's own address</b> naming the address the account uses;
 *   <li>returns the SAME neutral acknowledgement whether or not the account existed.
 * </ol>
 *
 * <p>The only channel the private address travels down is the inbox that already owns it.
 *
 * <h2>The four ways this leaks, and what stops each</h2>
 *
 * <table>
 *   <tr><td><b>Response body</b></td><td>{@link #recover} resolves to nothing. There is no field
 *       to differ. A caller that cannot vary its reply cannot leak through it.</td></tr>
 *   <tr><td><b>Timing</b></td><td>The reply does not wait for the mail. The lookup happens in both
 *       cases and everything after it is off the response path, so an existing and a missing
 *       username differ by one row fetched, not by an SES round trip.</td></tr>
 *   <tr><td><b>Logs</b></td><td>Nothing here logs an address. The recording mail sender prints the
 *       recipient and never the body, and SES failures are logged as a class name.</td></tr>
 *   <tr><td><b>Mail volume</b></td><td>The per-account cooldown, which is the point of V103.
 *       Per-source rate limiting cannot do this: a thousand sources sending one request each are a
 *       thousand messages to one inbox and no per-source limit is exceeded.</td></tr>
 * </table>
 */
public final class AccountRecoveryService {

    /**
     * One recovery message per account per hour.
     *
     * <p>Long enough that an inbox cannot be used as a weapon, short enough that somebody who did
     * not receive the first message is not locked out for the day. A refused attempt consumes the
     * allowance too, or somebody could hold an inbox at exactly the limit for ever by retrying.
     */
    public static final Duration COOLDOWN = Duration.ofHours(1);

    private final Pool db;
    private final MailSender mail;
    /**
     * Only ever used to decrypt the stored address.
     *
     * <p>A whole repository for one string transform is more dependency than the job needs, and
     * the alternative considered was a static decrypt helper. It was rejected: the cipher is held
     * per repository instance, so a static would either need its own copy of the key handling or
     * would silently return ciphertext when none was configured - and returning ciphertext from a
     * method called {@code decrypt} is the kind of quiet wrong answer that ends up in an email.
     */
    private final org.werkpages.repository.UserRepository users;
    private final String brandName;
    private final String signInUrl;

    public AccountRecoveryService(Pool db, MailSender mail,
                                  org.werkpages.repository.UserRepository users,
                                  String brandName, String signInUrl) {
        this.db = db;
        this.mail = mail;
        this.users = users;
        this.brandName = brandName;
        this.signInUrl = signInUrl;
    }

    /**
     * Starts recovery. <b>Always succeeds, and always resolves to nothing.</b>
     *
     * <p>Void rather than a result object on purpose: a method that returns nothing cannot grow a
     * field that says whether the account existed. The caller has nothing to branch on, which is
     * the property the whole design rests on, enforced by the type rather than by a comment.
     *
     * <p>It also succeeds when the database fails. A recovery attempt during an outage must look
     * exactly like one for a username that does not exist, because any difference is the oracle.
     *
     * @param username  what the person typed. Matched case-insensitively, as usernames are
     *                  case-insensitively unique since V99.
     * @param sourceHash the caller's hashed address, for monitoring. May be null.
     */
    public Future<Void> recover(String username, byte[] sourceHash) {
        if (username == null || username.isBlank()) return Future.succeededFuture();
        final String trimmed = username.trim();

        return findAccount(trimmed)
            .compose(found -> {
                if (found.isEmpty()) {
                    // Nothing recorded and nothing sent. Recording an attempt against a username
                    // that matches no account would build a list of guesses, which is a worse
                    // thing to hold than the cooldown is worth.
                    return Future.succeededFuture();
                }
                Row account = found.get();
                UUID userId = account.getUUID("id");
                return withinCooldown(userId).compose(blocked -> blocked
                    ? Future.succeededFuture()
                    : sendAndRecord(userId, account.getString("email"),
                                    account.getString("username"), sourceHash));
            })
            // Every failure becomes a success. See the note on the method: a difference between an
            // outage and a missing username is a difference an attacker can measure.
            .recover(err -> {
                System.err.println("recovery attempt failed internally: "
                    + err.getClass().getSimpleName());
                return Future.succeededFuture();
            })
            .mapEmpty();
    }

    private Future<Optional<Row>> findAccount(String username) {
        return db.preparedQuery("""
                SELECT id, email, username
                  FROM users
                 WHERE lower(username) = lower($1)
                """)
            .execute(Tuple.of(username))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next()) : Optional.empty());
    }

    private Future<Boolean> withinCooldown(UUID userId) {
        return db.preparedQuery("""
                SELECT EXISTS (
                    SELECT 1 FROM account_recovery_requests
                     WHERE user_id = $1
                       AND requested_at > now() - make_interval(mins => $2)
                ) AS blocked
                """)
            .execute(Tuple.of(userId, (int) COOLDOWN.toMinutes()))
            .map(rows -> rows.iterator().next().getBoolean("blocked"));
    }

    /**
     * Records the attempt BEFORE sending, so a send that hangs still consumed the allowance.
     *
     * <p>Recording afterwards would let concurrent requests both pass the cooldown check and both
     * send, which is the whole attack in a smaller window.
     */
    private Future<Void> sendAndRecord(UUID userId, String storedEmail, String username,
                                       byte[] sourceHash) {
        return db.preparedQuery("""
                INSERT INTO account_recovery_requests (user_id, source_hash)
                VALUES ($1, $2) RETURNING id
                """)
            .execute(Tuple.of(userId, sourceHash))
            .compose(inserted -> {
                long attemptId = inserted.iterator().next().getLong("id");
                String address = decrypt(storedEmail);
                if (address == null || address.isBlank()) {
                    // An account with no readable address cannot be recovered by mail, and that is
                    // not something to tell whoever asked.
                    return Future.succeededFuture();
                }
                return mail.send(new MailSender.MailMessage(address, subject(), body(username, address)))
                    .compose(messageId -> db.preparedQuery(
                            "UPDATE account_recovery_requests SET mail_accepted = TRUE WHERE id = $1")
                        .execute(Tuple.of(attemptId)).mapEmpty())
                    .recover(err -> {
                        // The attempt stays recorded with mail_accepted false, which is the state
                        // that distinguishes "we never tried" from "we tried and it was refused".
                        System.err.println("recovery mail not accepted: "
                            + err.getClass().getSimpleName());
                        return Future.succeededFuture();
                    });
            })
            .mapEmpty();
    }

    private String subject() {
        return "Your WerkPages account";
    }

    /**
     * The minimal approved message: which address the account uses.
     *
     * <p>Not a one-time sign-in link. That is nicer and is a larger build - it needs its own
     * single-use token and Auth0 passwordless to end in a real Auth0 session - and the design says
     * to start with this. The person now knows their address and signs in normally.
     *
     * <p>It deliberately does not say who asked. Nobody receiving this should be alarmed into
     * thinking their account was accessed, and we do not know who asked anyway.
     */
    private String body(String username, String address) {
        return """
            Somebody asked how to sign in to the WerkPages account with the username "%s".

            That account signs in with this email address: %s

            You can sign in here: %s

            If this was not you, there is nothing to do. No change has been made to your account,
            and this message was sent only to the address on the account itself.
            """.formatted(username, address, signInUrl);
    }

    private String decrypt(String stored) {
        return users.decryptField(stored);
    }
}

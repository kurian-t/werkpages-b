package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * How often an anonymous address may have a manager auto-created for it.
 *
 * <p>An anonymous first search creates a <em>live, publicly visible</em> manager. The only thing
 * holding "one per visitor" was a {@code localStorage} key — clear site data and you get another,
 * forever. The rate limiter caps requests per minute, not the total, so one address could publish
 * tens of thousands a day and a proxy pool multiplies that arbitrarily.
 *
 * <p>This is the backstop the browser cannot clear. It mirrors
 * {@link UserRepository#claimAutoCreatedManagerSlot}: a single write that either wins or does not,
 * so two concurrent searches from one address cannot both conclude the quota is free.
 *
 * <h2>A window, not a lifetime slot</h2>
 *
 * Carrier-grade NAT puts hundreds of users behind one address; so do offices and VPNs. A lifetime
 * slot would let the first person behind such an address burn it for everybody else permanently.
 * A window recovers on its own.
 *
 * <p>The cost is real and worth naming: on a shared address only one visitor per window gets an
 * auto-added manager, and the rest silently get a pending row and no tile. That is the deliberate
 * trade — shorter is kinder to shared addresses, longer is harsher on abuse.
 *
 * <h2>The address is hashed, never stored</h2>
 *
 * We only need to know whether an address has had its ghost recently, never which one it was.
 * Keeping the hash stops an abuse control from becoming a record of who searched for whom.
 */
public class AnonymousGhostSlotRepository {

    /** How long one address must wait between auto-created managers. */
    public static final int WINDOW_DAYS = intFromEnv("GHOST_WINDOW_DAYS", 7);

    /**
     * The site-wide ceiling: automatic public creation stops entirely above this many in an hour.
     *
     * <p>Per-address limits reduce volume; only a global cap bounds it. However many addresses an
     * attacker holds, the directory cannot be flooded, because past this the public path closes and
     * every search falls back to the admin queue. Users still get results; nothing public is
     * polluted.
     */
    public static final int MAX_PER_HOUR_SITE_WIDE = intFromEnv("GHOST_MAX_PER_HOUR", 50);

    /**
     * The other half of the ceiling: a daily cap.
     *
     * <p>An hourly limit stops a burst and does nothing about a slow grind. Without this, a large
     * proxy pool simply spends 50 an hour indefinitely - 1,200 a day - and the per-address window
     * never binds because every request comes from a fresh address.
     *
     * <p>Sized against real volume: roughly 70-100 managers a day arrive from ALL sources combined
     * (ghosts, search captures and deliberate submissions), so 200 is comfortably above anything
     * organic and still a hard stop. Tune it with the environment variable once the true
     * anonymous-ghost rate is known, rather than by shipping a new constant.
     */
    public static final int MAX_PER_DAY_SITE_WIDE = intFromEnv("GHOST_MAX_PER_DAY", 200);

    private static int intFromEnv(String key, int fallback) {
        String configured = System.getenv(key);
        if (configured == null || configured.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;   // a typo must not disable the ceiling
        }
    }

    private final SqlClient db;
    private final String salt;

    public AnonymousGhostSlotRepository(SqlClient db) {
        this(db, saltFromEnv());
    }

    public AnonymousGhostSlotRepository(SqlClient db, String salt) {
        this.db = db;
        this.salt = salt;
    }

    /**
     * The salt for the address hash.
     *
     * <p>A fixed constant rather than random-per-boot: a random one would empty the table's meaning
     * on every restart and hand out unlimited ghosts to anybody who waited for a deploy. The
     * fallback is for development; a deployment that cares sets {@code GHOST_SLOT_SALT}.
     */
    private static String saltFromEnv() {
        String configured = System.getenv("GHOST_SLOT_SALT");
        return (configured == null || configured.isBlank()) ? "werkpages-ghost-slot" : configured;
    }

    /**
     * Claims this address's auto-created manager for the current window.
     *
     * <p>One statement, so two concurrent searches from the same address cannot both succeed. The
     * {@code DO UPDATE ... WHERE} is what makes it a window rather than a one-off: an existing row
     * older than the window is refreshed and the claim wins; a fresher one matches no rows and
     * returns nothing.
     *
     * <p>A null or blank address claims nothing and returns false. An address we cannot identify
     * cannot be limited, and the safe reading of "unidentifiable" is "no free ghost", not "an
     * unlimited supply".
     */
    public Future<Boolean> claim(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return Future.succeededFuture(false);
        return db.preparedQuery("""
                INSERT INTO anonymous_ghost_quota (ip_hash)
                VALUES ($1)
                ON CONFLICT (ip_hash) DO UPDATE
                    SET claimed_at = now(), manager_id = NULL
                    WHERE anonymous_ghost_quota.claimed_at < now() - ($2 || ' days')::interval
                RETURNING ip_hash
                """)
            .execute(Tuple.of(hash(clientIp), String.valueOf(WINDOW_DAYS)))
            .map(rows -> rows.iterator().hasNext());
    }

    /**
     * Whether automatic public creation is currently allowed at all, site-wide.
     *
     * <p>Checked before the per-address claim, because a breaker that only trips after the quota is
     * spent would let an attacker consume quotas while the site is already under pressure.
     */
    public Future<Boolean> withinSiteWideCeiling() {
        return siteWideRates().map(rate -> rate.withinCeiling());
    }

    /** What the ceilings currently see. Also what the admin banner reports. */
    public Future<SiteWideRate> siteWideRates() {
        // Rolling windows, not calendar buckets. Fixed buckets have a boundary hole: the cap can be
        // spent at 10:59 and again at 11:01, for double the intended rate in two minutes.
        return db.preparedQuery("""
                SELECT count(*) FILTER (WHERE claimed_at > now() - interval '1 hour')  AS last_hour,
                       count(*) FILTER (WHERE claimed_at > now() - interval '24 hours') AS last_day
                FROM anonymous_ghost_quota
                """)
            .execute()
            .map(rows -> {
                var row = rows.iterator().next();
                return new SiteWideRate(row.getLong("last_hour"), row.getLong("last_day"));
            });
    }

    /**
     * Automatic public creation as it currently stands, site-wide.
     *
     * @param lastHour claims in the last rolling hour
     * @param lastDay  claims in the last rolling 24 hours
     */
    public record SiteWideRate(long lastHour, long lastDay) {
        public boolean withinCeiling() {
            return lastHour < MAX_PER_HOUR_SITE_WIDE && lastDay < MAX_PER_DAY_SITE_WIDE;
        }
        /** Which ceiling is holding, for the admin banner. Null when neither is. */
        public String trippedBy() {
            if (lastHour >= MAX_PER_HOUR_SITE_WIDE) return "hourly";
            if (lastDay  >= MAX_PER_DAY_SITE_WIDE)  return "daily";
            return null;
        }
    }

    /** Records which manager a claim produced, for auditing. Best-effort. */
    public Future<Void> recordManager(String clientIp, long managerId) {
        if (clientIp == null || clientIp.isBlank()) return Future.succeededFuture();
        return db.preparedQuery("UPDATE anonymous_ghost_quota SET manager_id = $2 WHERE ip_hash = $1")
            .execute(Tuple.of(hash(clientIp), managerId))
            .mapEmpty();
    }

    /**
     * Releases a claim.
     *
     * <p>For when the ghost insert fails after the claim succeeded — the same guarantee the
     * logged-in path gives: a failed creation must never leave somebody unable to trigger one.
     */
    public Future<Void> release(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return Future.succeededFuture();
        return db.preparedQuery("DELETE FROM anonymous_ghost_quota WHERE ip_hash = $1")
            .execute(Tuple.of(hash(clientIp)))
            .mapEmpty();
    }

    /**
     * Drops rows past their usefulness.
     *
     * <p>A hashed address is pseudonymised personal data, not anonymous — IPv4 is small enough to
     * brute-force with the salt — so it is not kept indefinitely.
     */
    public Future<Void> sweepExpired() {
        return db.preparedQuery(
                "DELETE FROM anonymous_ghost_quota WHERE claimed_at < now() - interval '30 days'")
            .execute()
            .mapEmpty();
    }

    String hash(String clientIp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(clientIp.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);  // required of every JVM
        }
    }
}

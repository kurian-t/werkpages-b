package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;
import org.werkpages.service.ServiceException;
import org.werkpages.service.SubmissionLimits;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The daily cap on creating companies.
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code POST /api/companies} mints a public directory row from an arbitrary string, and it was
 * the only write in the product with no per-account limit of any kind. Managers, reviews and edits
 * are each capped at ten a day. Companies had nothing but the shared 30-per-minute write limiter,
 * which permits 43,200 a day and counts addresses rather than accounts.
 *
 * <p>So one signed-in person could fill the Companies tab with as many invented names as they cared
 * to type. Nothing reversed it, and nothing recorded who had done it: {@code companies} stores
 * neither a creator nor an origin, so even after the fact the rows could not be attributed.
 *
 * <h2>Why these assertions
 *
 * <p>The cap has to hold on three axes at once, and each is a way it has been got around before in
 * other quotas:
 *
 * <ul>
 *   <li><b>per account</b> - the obvious one;
 *   <li><b>per address</b> - signing up is free, so twelve accounts from one machine would
 *       otherwise be twelve times the cap;
 *   <li><b>charged only for a new row</b> - resolving a company that already exists is the normal
 *       path through this endpoint and must never consume allowance, or ordinary use of the form
 *       locks people out of it.
 * </ul>
 */
@Testcontainers
class CompanyCreationQuotaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("company_quota_test")
        .withUsername("test")
        .withPassword("test");

    static Pool  pool;
    static Vertx vertx;
    static ManagerService   service;
    static CompanyRepository companyRepo;

    private static final String IP       = "198.51.100.7";
    private static final String OTHER_IP = "203.0.113.9";

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        vertx = Vertx.vertx();
        pool = PgPool.pool(vertx, new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()), new PoolOptions().setMaxSize(5));

        companyRepo = new CompanyRepository(pool);
        service = new ManagerService(
            new ManagerRepository(pool), new ReviewRepository(pool), new UserRepository(pool),
            new EditRepository(pool), new ReportRepository(pool), pool);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null)  pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE company_creation_quota, managers, companies, users CASCADE").execute());
    }

    // ── the cap itself ────────────────────────────────────────────────────────

    /**
     * The regression test. Twelve new companies are allowed; the thirteenth is refused.
     *
     * <p>Before the cap existed the thirteenth succeeded, as would the thirteen thousandth. The
     * assertion on the count in {@code companies} is the part that matters: a 429 that still wrote
     * the row would be worse than no limit at all, because it would look enforced.
     */
    @Test
    void thirteenthNewCompanyInADayIsRefused() throws Exception {
        String auth0Id = insertUser("auth0|quota-cap");

        for (int i = 1; i <= SubmissionLimits.DAILY_COMPANIES; i++) {
            JsonObject created = await(service.createCompany("Quota Co " + i, auth0Id, IP));
            assertTrue(created.getBoolean("created"), "company " + i + " should have been created");
        }

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(service.createCompany("Quota Co 13", auth0Id, IP)));
        assertEquals(429, refused.getStatusCode());

        assertEquals(SubmissionLimits.DAILY_COMPANIES, countCompanies(),
            "the refused request must not have written a company row");
    }

    /**
     * A second account from the same address does not get a fresh allowance.
     *
     * <p>This is the hole that makes a per-account cap decorative. Accounts are free, so a cap
     * counted on the account alone is a cap on how often somebody bothers to register.
     */
    @Test
    void aSecondAccountFromTheSameAddressSharesTheAllowance() throws Exception {
        String first  = insertUser("auth0|quota-ip-1");
        String second = insertUser("auth0|quota-ip-2");

        for (int i = 1; i <= SubmissionLimits.DAILY_COMPANIES; i++) {
            await(service.createCompany("Shared Addr Co " + i, first, IP));
        }

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(service.createCompany("Shared Addr Co 13", second, IP)));
        assertEquals(429, refused.getStatusCode());
    }

    /**
     * A different address is not punished for somebody else's twelve.
     *
     * <p>The counterpart to the test above, and the reason the count is not keyed on the address
     * alone. Shared addresses are ordinary - offices, universities, carrier NAT - and a cap that
     * cannot tell two accounts apart there would lock out everybody behind one.
     */
    @Test
    void aDifferentAccountOnADifferentAddressIsUnaffected() throws Exception {
        String heavy = insertUser("auth0|quota-heavy");
        String fresh = insertUser("auth0|quota-fresh");

        for (int i = 1; i <= SubmissionLimits.DAILY_COMPANIES; i++) {
            await(service.createCompany("Heavy Co " + i, heavy, IP));
        }

        JsonObject allowed = await(service.createCompany("Fresh Co", fresh, OTHER_IP));
        assertTrue(allowed.getBoolean("created"));
    }

    // ── what is not charged ───────────────────────────────────────────────────

    /**
     * Resolving a company that already exists costs nothing.
     *
     * <p>This is the common case: the form posts the typed name, and most names are already in the
     * directory. Charging for it would mean somebody rating thirteen workplaces that all already
     * exist gets locked out of the form, having created nothing.
     */
    @Test
    void resolvingAnExistingCompanyIsNotCharged() throws Exception {
        String auth0Id = insertUser("auth0|quota-existing");
        await(service.createCompany("Already Here Ltd", auth0Id, IP));

        for (int i = 0; i < 40; i++) {
            JsonObject resolved = await(service.createCompany("already here ltd", auth0Id, IP));
            assertFalse(resolved.getBoolean("created"), "an existing name must resolve, not create");
        }

        // One creation was charged; the forty lookups were not, so there is allowance left.
        JsonObject stillAllowed = await(service.createCompany("Genuinely New Inc", auth0Id, IP));
        assertTrue(stillAllowed.getBoolean("created"));
        assertEquals(2L, countQuotaRows(), "only the two real creations should be recorded");
    }

    // ── the window ────────────────────────────────────────────────────────────

    /**
     * The cap is a day, not a lifetime: yesterday's creations do not count.
     *
     * <p>Backdated directly, because the alternative is a test that waits twenty-four hours. The
     * window lives in {@code company_creation_count}, so this exercises the real predicate.
     */
    @Test
    void creationsOlderThanTheWindowDoNotCount() throws Exception {
        String auth0Id = insertUser("auth0|quota-window");

        for (int i = 1; i <= SubmissionLimits.DAILY_COMPANIES; i++) {
            await(service.createCompany("Window Co " + i, auth0Id, IP));
        }
        await(pool.query("UPDATE company_creation_quota SET created_at = now() - interval '25 hours'").execute());

        JsonObject allowedAgain = await(service.createCompany("Window Co Today", auth0Id, IP));
        assertTrue(allowedAgain.getBoolean("created"), "the window should have rolled over");
    }

    /**
     * The sweep prunes rows past their usefulness, and keeps the ones still in the window.
     *
     * <p>A hashed address is pseudonymised personal data, not anonymous, so this table is a rate
     * control with an expiry rather than a permanent record of who created what from where.
     */
    @Test
    void sweepDropsExpiredRowsAndKeepsCurrentOnes() throws Exception {
        String auth0Id = insertUser("auth0|quota-sweep");
        await(service.createCompany("Sweep Keep Co", auth0Id, IP));
        await(service.createCompany("Sweep Drop Co", auth0Id, IP));
        await(pool.query(
            "UPDATE company_creation_quota SET created_at = now() - interval '8 days' "
          + "WHERE id = (SELECT max(id) FROM company_creation_quota)").execute());

        Row swept = await(pool.query("SELECT company_creation_quota_sweep() AS removed").execute())
            .iterator().next();
        assertEquals(1L, swept.getLong("removed"));
        assertEquals(1L, countQuotaRows(), "the row still inside the window must survive");
    }

    // ── the count function, directly ──────────────────────────────────────────

    /**
     * An address we cannot identify is not counted as one shared bucket.
     *
     * <p>Null rather than a placeholder: bucketing every unidentifiable caller together would let
     * one person's twelve exhaust the allowance for everybody whose address did not resolve. The
     * account half of the count still applies to them.
     */
    @Test
    void anUnknownAddressDoesNotShareABucket() throws Exception {
        String first  = insertUser("auth0|quota-noip-1");
        String second = insertUser("auth0|quota-noip-2");

        for (int i = 1; i <= SubmissionLimits.DAILY_COMPANIES; i++) {
            await(service.createCompany("No Addr Co " + i, first, null));
        }

        JsonObject allowed = await(service.createCompany("No Addr Other", second, null));
        assertTrue(allowed.getBoolean("created"),
            "a second account should not inherit the first one's count through a null address");

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(service.createCompany("No Addr Co 13", first, null)));
        assertEquals(429, refused.getStatusCode(), "the account half of the cap still applies");
    }

    /**
     * The same address hashes the same way every time, and a different one differently.
     *
     * <p>The cap depends entirely on this. A salt that varied per call - or per boot - would make
     * every request look like a new address and the address half of the cap would silently do
     * nothing, which is exactly the kind of failure that looks like a working limit.
     */
    @Test
    void theAddressHashIsStableAcrossCalls() throws Exception {
        String auth0Id = insertUser("auth0|quota-hash");
        await(service.createCompany("Hash Co", auth0Id, IP));

        // Counted through the repository, which re-hashes the address on every call.
        assertEquals(1L, await(companyRepo.countRecentCompanyCreations(null, IP)));
        assertEquals(1L, await(companyRepo.countRecentCompanyCreations(null, "  " + IP + "  ")),
            "the address is trimmed before hashing");
        assertEquals(0L, await(companyRepo.countRecentCompanyCreations(null, OTHER_IP)));
    }


    // ── the anonymous paths ───────────────────────────────────────────────────

    /**
     * The regression test for the larger hole: an anonymous capture cannot mint public companies
     * without limit.
     *
     * <p>{@code captureAnonymousSearch} creates a {@code pending_approval} manager, which nobody
     * can see - but it resolved the company first, and a created company is {@code status =
     * 'ghost'}: publicly listed in the Companies tab the moment it exists. So repeating the capture
     * with a new company name each time filled a public directory with invented employers while
     * publishing no manager at all, bounded only by the shared 30-a-minute write limiter.
     *
     * <p>The capture itself still succeeds past the cap. That is deliberate and asserted below:
     * throwing the submission away is the one thing these paths exist to prevent.
     */
    @Test
    void anAnonymousCaptureStopsMintingCompaniesAtTheCap() throws Exception {
        for (int i = 1; i <= SubmissionLimits.DAILY_COMPANIES; i++) {
            await(service.captureAnonymousSearch(
                capture("Dana " + surname(i), "Anon Capture Co " + i), null, submissionFrom(IP)));
        }
        assertEquals(SubmissionLimits.DAILY_COMPANIES, countCompanies());

        // The thirteenth: captured, but no new company.
        await(service.captureAnonymousSearch(
            capture("Dana " + surname(13), "Anon Capture Co 13"), null, submissionFrom(IP)));

        assertEquals(SubmissionLimits.DAILY_COMPANIES, countCompanies(),
            "the cap must stop a new public company row being created");

        Row manager = await(pool.preparedQuery(
                "SELECT company, company_id, approval_status FROM managers WHERE name = $1")
            .execute(Tuple.of("Dana " + surname(13)))).iterator().next();
        assertNull(manager.getLong("company_id"),
            "over the cap the row is written with no company, which section 17 allows for pending");
        assertEquals("Anon Capture Co 13", manager.getString("company"),
            "the typed company name is kept, so an admin can resolve it later");
        assertEquals("pending_approval", manager.getString("approval_status"));
    }

    /**
     * A capture naming a company that already exists is never charged.
     *
     * <p>The ordinary case. If it were charged, thirteen people searching for managers at thirteen
     * existing employers from one office would exhaust that office's allowance, having created
     * nothing.
     */
    @Test
    void anAnonymousCaptureForAnExistingCompanyIsNotCharged() throws Exception {
        await(pool.preparedQuery("INSERT INTO companies(name, status) VALUES ($1, 'approved')")
            .execute(Tuple.of("Established Co")));

        for (int i = 1; i <= 30; i++) {
            await(service.captureAnonymousSearch(
                capture("Dana " + surname(i), "Established Co"), null, submissionFrom(IP)));
        }

        assertEquals(0L, countQuotaRows(), "resolving an existing company charges nothing");
        assertEquals(1L, countCompanies());
    }

    /**
     * A published ghost still gets its company, even past the cap.
     *
     * <p>The section 41 invariant. A ghost is live and must appear under its company in the
     * directory; a public manager with no company would be a tile whose profile renders an
     * employer the directory does not list. That path is bounded far more tightly anyway - one per
     * address per window, under a site-wide ceiling - so the company cap has nothing to add to it
     * and must not interfere.
     */
    @Test
    void aPublishedGhostStillGetsItsCompanyPastTheCap() throws Exception {
        // Exhaust the company allowance for this address first.
        for (int i = 1; i <= SubmissionLimits.DAILY_COMPANIES; i++) {
            await(service.captureAnonymousSearch(
                capture("Dana " + surname(i), "Filler Co " + i), null, submissionFrom(IP)));
        }

        JsonObject ghost = await(service.createGhostManager(
            ghostBody("Morgan", "Published", "Ghost Co Ltd"), null, submissionFrom(IP)));

        assertTrue(Boolean.TRUE.equals(ghost.getBoolean("published")),
            "the ghost slot is untouched by the company cap, so this publishes");
        Row manager = await(pool.preparedQuery(
                "SELECT company_id, approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(ghost.getLong("id")))).iterator().next();
        assertEquals("ghost", manager.getString("approval_status"));
        assertNotNull(manager.getLong("company_id"),
            "a live manager must be attached to its company whatever the company cap says");
    }

    /**
     * A distinct, letter-only surname for iteration {@code n}.
     *
     * <p>Not "Person 1": {@code NameValidator} refuses a name containing a digit, so numbering the
     * surnames made every one of these tests fail on validation before it reached the cap at all.
     */
    private static String surname(int n) {
        StringBuilder sb = new StringBuilder("Quill");
        int v = n;
        do { sb.append((char) ('a' + (v % 26))); v /= 26; } while (v > 0);
        return sb.toString();
    }

    private static JsonObject capture(String name, String company) {
        return new JsonObject().put("name", name).put("company", company).put("title", "Manager");
    }

    private static JsonObject ghostBody(String first, String last, String company) {
        return new JsonObject()
            .put("name", first + " " + last)
            .put("company", company)
            .put("title", "Operations Manager")
            .put("country", "US")
            .put("fromSearch", true);
    }

    /** The address, carried the way the real handlers carry it. */
    private static org.werkpages.service.SubmissionContext submissionFrom(String ip) {
        return org.werkpages.service.SubmissionContext.of(null, new io.vertx.core.json.JsonObject(), ip);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String insertUser(String auth0Id) throws Exception {
        String username = auth0Id.replaceAll("[^a-zA-Z0-9]", "") + "User";
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User")));
        return auth0Id;
    }

    private long countCompanies() throws Exception {
        return await(pool.query("SELECT count(*) AS c FROM companies").execute())
            .iterator().next().getLong("c");
    }

    private long countQuotaRows() throws Exception {
        return await(pool.query("SELECT count(*) AS c FROM company_creation_quota").execute())
            .iterator().next().getLong("c");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    /** Unwraps the {@link ExecutionException} so the test can assert on the service's own type. */
    private static void awaitFailure(Future<?> future) throws Throwable {
        try {
            future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }
}

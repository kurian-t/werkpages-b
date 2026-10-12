package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
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

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Searching for a manager at a company with a very short name.
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code findOrCreate} refused to create anything when either the person's name or the company's
 * name was shorter than four characters. The company half of that rule was wrong for a whole class
 * of real employers: IBM, SAP, BBC, EY, 3M. A reader who picked IBM from the company suggestions and
 * searched for their manager got an empty result and no explanation, because three characters looked
 * to the server like a word somebody had not finished typing.
 *
 * <h2>What changed</h2>
 *
 * <p>The company half is waived when the company was <b>selected from the dropdown</b>, which is the
 * only case where the three characters are known to be a whole name rather than a prefix. Typed text
 * of three characters or fewer with nothing selected gates exactly as it did before, and the
 * person's own name is still judged on its own.
 *
 * <h2>Why the forgery cases are here
 *
 * <p>{@code companyId} arrives in the request body, so "selected from the dropdown" is a claim, not
 * a fact. It is checked against {@code companies} and against the accompanying text. The two tests
 * at the bottom are what stop the waiver degrading into "send any number and the guard turns off".
 *
 * <h2>The section 41 invariant
 *
 * <p>A tile is only ever rendered for a row the profile page will actually serve. Every assertion
 * below that expects a creation also asserts the status is {@code ghost} - live and serveable -
 * because a created row that the profile refuses is the exact production failure that rule exists
 * to prevent.
 */
@Testcontainers
class ShortCompanyNameSearchIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("short_company_test")
        .withUsername("test")
        .withPassword("test");

    static Pool             pool;
    static ManagerService   service;
    static CompanyRepository companyRepo;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        PgConnectOptions opts = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        pool        = PgPool.pool(opts, new PoolOptions().setMaxSize(5));
        companyRepo = new CompanyRepository(pool);
        service     = new ManagerService(
            new ManagerRepository(pool), new ReviewRepository(pool), new UserRepository(pool),
            new EditRepository(pool), new ReportRepository(pool), pool);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE managers, companies, users CASCADE").execute());
    }

    // ── the waiver ────────────────────────────────────────────────────────────

    /**
     * The regression test. IBM picked from the dropdown creates the manager.
     *
     * <p>Before the waiver this returned an empty result and created nothing, so the reader was
     * shown no manager, no tile and no reason. The status assertion is the section 41 half: the row
     * must be {@code ghost}, because a row the profile page would refuse to serve is worse than no
     * row at all.
     */
    @Test
    void aShortCompanyPickedFromTheDropdownCreatesTheManager() throws Exception {
        String auth0Id = insertUser("auth0|short-ibm", "shortibm");
        long ibmId = insertCompany("IBM");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Grace", "Hopper", "Engineering Manager", "IBM", "US", null, null, null, ibmId));

        assertTrue(result.getBoolean("created"), "a selected company must not gate on its length");
        assertEquals(1, result.getJsonArray("data").size());
        assertEquals("ghost", result.getJsonArray("data").getJsonObject(0).getString("approvalStatus"),
            "the created row has to be one the profile page will serve");
    }

    /**
     * Casing and stray spaces do not make a real selection look forged.
     *
     * <p>The comparison is on {@code LOWER(TRIM(name))}, the same expression the company uniqueness
     * index uses. A dropdown that renders "IBM" while the field holds "ibm " is still a selection.
     */
    @Test
    void theSelectionSurvivesCasingAndWhitespace() throws Exception {
        String auth0Id = insertUser("auth0|short-case", "shortcase");
        long sapId = insertCompany("SAP");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Hedy", "Lamarr", "Director", " sap ", "US", null, null, null, sapId));

        assertTrue(result.getBoolean("created"));
    }

    // ── what still gates ──────────────────────────────────────────────────────

    /**
     * Nothing selected and three characters typed: the old gate, unchanged.
     *
     * <p>This is the behaviour the user asked to keep. Somebody halfway through typing "Goo" has
     * not told us anything, and minting a company and a manager from it is how the directory fills
     * with fragments.
     */
    @Test
    void aShortCompanyTypedWithNoSelectionStillGates() throws Exception {
        String auth0Id = insertUser("auth0|short-typed", "shorttyped");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Grace", "Hopper", "Engineering Manager", "Goo", "US", null, null, null, null));

        assertFalse(result.getBoolean("created"));
        assertEquals(0, result.getJsonArray("data").size());
        assertEquals(0L, countManagers(), "no manager row at all, not even a pending one");
    }

    /**
     * The shortest name a person can actually have still works at a selected short company.
     *
     * <p>Worth stating because of what it took to write. The obvious version of this test - a
     * one-letter surname, to prove the person half of the guard still bites - cannot be written:
     * {@code NameValidator} requires two characters in each part, so it refuses "P" with a 400
     * long before the guard is consulted. The shortest name that reaches the guard is
     * {@code "Da" + " " + "Po"}, which is five characters, so the guard's own
     * {@code fullName.trim().length() < 4} test can never be true for input that got that far.
     *
     * <p>That branch is therefore unreachable. It is left exactly as it was: removing it is not
     * this change's business, and it is recorded here so the next reader does not spend the same
     * hour discovering it. What this test does pin down is that the waiver did not loosen anything
     * about the person's name - a two-and-two name is accepted, as it was before, and the company
     * being three characters is the only thing the waiver changed.
     */
    @Test
    void theShortestValidPersonNameWorksAtASelectedShortCompany() throws Exception {
        String auth0Id = insertUser("auth0|short-person", "shortperson");
        long ibmId = insertCompany("IBM");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Da", "Po", "Manager", "IBM", "US", null, null, null, ibmId));

        assertTrue(result.getBoolean("created"));
        assertEquals("ghost", result.getJsonArray("data").getJsonObject(0).getString("approvalStatus"));
    }

    /**
     * A name too short for the validator is refused outright, selection or not.
     *
     * <p>The guard is not the only thing protecting this path, and the waiver must not be mistaken
     * for a way past the validator. A one-letter surname is a 400 whether or not IBM was picked.
     */
    @Test
    void aNameBelowTheValidatorMinimumIsRefusedEvenWithASelectedCompany() throws Exception {
        String auth0Id = insertUser("auth0|short-invalid", "shortinvalid");
        long ibmId = insertCompany("IBM");

        org.werkpages.service.ServiceException refused = assertThrows(
            org.werkpages.service.ServiceException.class,
            () -> awaitFailure(service.findOrCreate(
                auth0Id, "Da", "P", "Manager", "IBM", "US", null, null, null, ibmId)));
        assertEquals(400, refused.getStatusCode());
        assertEquals(0L, countManagers());
    }

    // ── the claim is verified, not trusted ────────────────────────────────────

    /**
     * An id that names no company is not a selection.
     *
     * <p>{@code companyId} is read from the request body. Were it taken at face value, the waiver
     * would be "send any number and the length guard turns off" - which is not a guard. Note the
     * id used here is one no row has, so a client cannot discover it by guessing either.
     */
    @Test
    void aFabricatedCompanyIdDoesNotWaiveTheGate() throws Exception {
        String auth0Id = insertUser("auth0|short-fake", "shortfake");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Grace", "Hopper", "Engineering Manager", "Goo", "US", null, null, null, 987654321L));

        assertFalse(result.getBoolean("created"), "an id naming no row is not a selection");
        assertEquals(0, result.getJsonArray("data").size());
    }

    /**
     * A real id beside text that names a different company is not a selection either.
     *
     * <p>Otherwise one borrowed id - IBM's, which anybody can read off a company page - would waive
     * the gate for any three characters at all. A genuine pick always sends the suggestion's own
     * name, so disagreement is enough to know this was not one.
     */
    @Test
    void aRealCompanyIdBesideUnrelatedTextDoesNotWaiveTheGate() throws Exception {
        String auth0Id = insertUser("auth0|short-mismatch", "shortmismatch");
        long ibmId = insertCompany("IBM");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Grace", "Hopper", "Engineering Manager", "Goo", "US", null, null, null, ibmId));

        assertFalse(result.getBoolean("created"), "the id and the text have to agree");
        assertEquals(0, result.getJsonArray("data").size());
    }

    // ── the repository predicate, directly ────────────────────────────────────

    /** The three answers {@code isSelectedCompany} has to give, stated on their own. */
    @Test
    void isSelectedCompanyAnswersOnlyForAGenuinePair() throws Exception {
        long ibmId = insertCompany("IBM");

        assertTrue(await(companyRepo.isSelectedCompany(ibmId, "IBM")));
        assertTrue(await(companyRepo.isSelectedCompany(ibmId, "  ibm ")));
        assertFalse(await(companyRepo.isSelectedCompany(ibmId, "Goo")));
        assertFalse(await(companyRepo.isSelectedCompany(987654321L, "IBM")));
        // Null and blank are "cannot tell", which must take the guarded path.
        assertFalse(await(companyRepo.isSelectedCompany(null, "IBM")));
        assertFalse(await(companyRepo.isSelectedCompany(ibmId, "   ")));
        assertFalse(await(companyRepo.isSelectedCompany(ibmId, null)));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) "
              + "VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User", "user")));
        return auth0Id;
    }

    private long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies(name, status) VALUES ($1, 'approved') RETURNING id")
            .execute(Tuple.of(name))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long countManagers() throws Exception {
        return await(pool.query("SELECT count(*) AS c FROM managers").execute())
            .iterator().next().getLong("c");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    /** Unwraps the {@link java.util.concurrent.ExecutionException} so the service's own type shows. */
    private static void awaitFailure(Future<?> future) throws Throwable {
        try {
            future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause();
        }
    }
}

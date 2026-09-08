package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.*;
import org.werkpages.service.CompanyReviewService;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rating an employer, as distinct from rating its managers.
 *
 * The rules worth protecting: every category is required, one rating per person per company, and
 * a company nobody has rated reports nothing rather than zero.
 */
@Testcontainers
class CompanyReviewIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test").withUsername("test").withPassword("test");

    static Pool pool;
    static CompanyReviewService    service;
    static CompanyReviewRepository reviewRepo;
    static CompanyRepository       companyRepo;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations").load().migrate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));

        reviewRepo  = new CompanyReviewRepository(pool);
        companyRepo = new CompanyRepository(pool);
        service     = new CompanyReviewService(reviewRepo, companyRepo, new UserRepository(pool));
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE company_reviews, companies, users CASCADE").execute().mapEmpty());
    }

    @AfterAll
    static void tearDownAll() { if (pool != null) pool.close(); }

    // ── Submitting ────────────────────────────────────────────────────────────

    @Test
    void submittingStoresEveryCategory() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-1", "CrUser1");

        JsonObject result = await(service.submit(auth, "red-hat", validBody(4.0)));

        assertNotNull(result.getString("id"));
        assertEquals(4.0, result.getDouble("overallRating"));
        JsonObject ratings = result.getJsonObject("ratings");
        for (String c : CompanyReviewRepository.CATEGORIES) {
            assertNotNull(ratings.getDouble(c), c + " was stored");
        }
    }

    @Test
    void everyCategoryIsRequired() throws Exception {
        // No N/A. A corpus where half the ratings skipped career growth cannot be sliced by it.
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-2", "CrUser2");

        JsonObject body = validBody(4.0);
        body.getJsonObject("ratings").remove("career_growth");

        Exception e = assertThrows(Exception.class, () -> await(service.submit(auth, "red-hat", body)));
        assertTrue(e.getMessage().contains("every category"), "got: " + e.getMessage());
    }

    @Test
    void theOverallRatingIsRequiredSeparately() throws Exception {
        // It is not the mean of the ten - someone can rate the parts poorly and the whole well.
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-3", "CrUser3");

        JsonObject body = validBody(4.0);
        body.remove("overallRating");

        assertThrows(Exception.class, () -> await(service.submit(auth, "red-hat", body)));
    }

    @Test
    void ratingACompanyThatDoesNotExistIsRefused() throws Exception {
        // A company rating never creates a company, for the same reason an interview review does
        // not: there would be no anchor at all behind it.
        String auth = insertUser("auth0|cr-4", "CrUser4");
        assertThrows(Exception.class, () -> await(service.submit(auth, "no-such-company", validBody(4.0))));
    }

    // ── One per person per company ───────────────────────────────────────────

    @Test
    void ratingTheSameCompanyTwiceReplacesTheFirst() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-5", "CrUser5");

        await(service.submit(auth, "red-hat", validBody(2.0)));
        await(service.submit(auth, "red-hat", validBody(5.0)));

        Long rows = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM company_reviews WHERE deleted_at IS NULL")
            .execute().map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, rows, "a second rating replaces the first rather than adding one");

        var agg = await(service.aggregateFor(companyId("red-hat")));
        assertEquals(5.0, agg.getDouble("overallRating"), "and it is the newer answer that stands");
    }

    @Test
    void oneRatingEachAtTwoCompaniesIsFine() throws Exception {
        // The rule is per company row, not per corporate group. Somebody who worked at Zehrs and
        // at Loblaw has two genuinely different experiences to report.
        insertCompany("Zehrs", "zehrs");
        insertCompany("Loblaw", "loblaw");
        String auth = insertUser("auth0|cr-6", "CrUser6");

        await(service.submit(auth, "zehrs",  validBody(4.0)));
        await(service.submit(auth, "loblaw", validBody(2.0)));

        Long rows = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM company_reviews WHERE deleted_at IS NULL")
            .execute().map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(2L, rows);
    }

    @Test
    void mergingTwoCompaniesOnePersonRatedBothIsBlocked() throws Exception {
        // One rating per person per company is a unique index, so this merge would put two of the
        // same person's rows onto one company - after the managers had already moved.
        long keep  = companyId(insertCompany("Zehrs Markets", "zehrs-markets"));
        long merge = companyId(insertCompany("Zehrs", "zehrs"));
        String auth = insertUser("auth0|cr-7", "CrUser7");
        await(service.submit(auth, "zehrs-markets", validBody(4.0)));
        await(service.submit(auth, "zehrs",         validBody(2.0)));

        var preview = await(companyRepo.previewMerge(keep, merge));

        assertTrue(preview.getBoolean("blocked"), "the merge is refused before anything moves");
        assertEquals(1L, preview.getLong("companyRatingConflicts"));
    }

    @Test
    void mergingMovesRatingsWhenThereIsNoCollision() throws Exception {
        long keep  = companyId(insertCompany("Zehrs Markets", "zehrs-markets"));
        long merge = companyId(insertCompany("Zehrs", "zehrs"));
        String auth = insertUser("auth0|cr-8", "CrUser8");
        await(service.submit(auth, "zehrs", validBody(3.0)));

        await(companyRepo.mergeCompanies(keep, merge, adminId()));

        var agg = await(service.aggregateFor(keep));
        assertNotNull(agg, "the rating followed the company it was about");
        assertEquals(1L, agg.getLong("ratingCount"));
    }

    // ── Aggregates ───────────────────────────────────────────────────────────

    @Test
    void aCompanyNobodyRatedReportsNothingRatherThanZero() throws Exception {
        // A page printing 0.0 says the workplace is terrible; printing nothing says nobody has
        // told us yet. They are not the same claim.
        insertCompany("Quiet Co", "quiet-co");
        assertNull(await(service.aggregateFor(companyId("quiet-co"))));
    }

    @Test
    void theAggregateAveragesEveryCategoryOverTheSameRows() throws Exception {
        insertCompany("Red Hat", "red-hat");
        await(service.submit(insertUser("auth0|cr-9",  "CrUser9"),  "red-hat", validBody(2.0)));
        await(service.submit(insertUser("auth0|cr-10", "CrUser10"), "red-hat", validBody(4.0)));

        JsonObject agg = await(service.aggregateFor(companyId("red-hat")));

        assertEquals(2L, agg.getLong("ratingCount"));
        assertEquals(3.0, agg.getDouble("overallRating"), "mean of 2.0 and 4.0");
        // Every category is NOT NULL, so all ten share one denominator and one "based on N".
        for (String c : CompanyReviewRepository.CATEGORIES) {
            assertNotNull(agg.getJsonObject("categories").getDouble(c), c);
        }
    }

    @Test
    void aWithdrawnRatingLeavesTheAggregate() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-11", "CrUser11");
        JsonObject review = await(service.submit(auth, "red-hat", validBody(1.0)));

        await(service.delete(auth, UUID.fromString(review.getString("id"))));

        assertNull(await(service.aggregateFor(companyId("red-hat"))),
            "the only rating was withdrawn, so there is nothing to report");
    }

    @Test
    void withdrawingSomebodyElsesRatingIsRefused() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String owner   = insertUser("auth0|cr-12", "CrUser12");
        String bystander = insertUser("auth0|cr-13", "CrUser13");
        JsonObject review = await(service.submit(owner, "red-hat", validBody(3.0)));

        assertThrows(Exception.class,
            () -> await(service.delete(bystander, UUID.fromString(review.getString("id")))));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static JsonObject validBody(double overall) {
        JsonObject ratings = new JsonObject();
        for (String c : CompanyReviewRepository.CATEGORIES) ratings.put(c, overall);
        return new JsonObject()
            .put("overallRating", overall)
            .put("ratings", ratings)
            .put("workedFrom", "2021-04")
            .putNull("workedUntil");
    }

    private String insertCompany(String name, String slug) throws Exception {
        await(pool.preparedQuery("INSERT INTO companies(name, slug, status) VALUES ($1,$2,'approved')")
            .execute(Tuple.of(name, slug)).mapEmpty());
        return slug;
    }

    private long companyId(String slug) throws Exception {
        return await(pool.preparedQuery("SELECT id FROM companies WHERE slug = $1")
            .execute(Tuple.of(slug)).map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery("INSERT INTO users(auth0_id,email,username) VALUES ($1,$2,$3)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username)).mapEmpty());
        return auth0Id;
    }

    private UUID adminId() throws Exception {
        String auth = insertUser("auth0|cr-admin-" + UUID.randomUUID(), "CrAdmin" + System.nanoTime());
        return await(pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth)).map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}

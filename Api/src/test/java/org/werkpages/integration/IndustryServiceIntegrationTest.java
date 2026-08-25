package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
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
import org.werkpages.service.IndustryService;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage of the Industries read side (IndustryService + CompanyRepository industry
 * queries) against a real Postgres: listing aggregation/ordering, single-industry profile with
 * the 10-category breakdown, slug resolution, and the classification back-fill helpers.
 */
@Testcontainers
class IndustryServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test").withUsername("test").withPassword("test");

    static Pool              pool;
    static CompanyRepository companyRepo;
    static IndustryService   service;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations").load().migrate();

        PgConnectOptions opts = new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName()).setUser(postgres.getUsername()).setPassword(postgres.getPassword());
        pool = PgPool.pool(opts, new PoolOptions().setMaxSize(5));
        companyRepo = new CompanyRepository(pool);
        service = new IndustryService(companyRepo, c -> null);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, companies CASCADE").execute());
    }

    // ── listing ────────────────────────────────────────────────────────────────

    @Test
    void listing_emptyWhenNothingClassified() throws Exception {
        JsonArray data = await(service.getIndustryListing()).getJsonArray("data");
        assertEquals(0, data.size());
    }

    @Test
    void listing_aggregatesPerIndustryWithSlugAndStats() throws Exception {
        long acme = insertCompanyWithIndustry("Acme Tech", "acme-tech", "Technology");
        long beta = insertCompanyWithIndustry("Beta Tech", "beta-tech", "Technology");
        long shop = insertCompanyWithIndustry("Shopland",  "shopland",  "Retail");
        insertManager("Alice", "alice", "approved", acme, "Acme Tech", 4.0, 3, null);
        insertManager("Bob",   "bob",   "approved", beta, "Beta Tech", 2.0, 1, null);
        insertManager("Carol", "carol", "ghost",    shop, "Shopland",  5.0, 2, null);

        JsonArray data = await(service.getIndustryListing()).getJsonArray("data");
        JsonObject tech = find(data, "Technology");
        assertNotNull(tech);
        assertEquals("technology", tech.getString("slug"));
        assertEquals(2L, tech.getLong("companyCount"));
        assertEquals(2L, tech.getLong("managerCount"));
        assertEquals(4L, tech.getLong("totalReviews"));               // 3 + 1
        assertEquals(3.0, tech.getDouble("avgRating"), 0.05);         // (4.0 + 2.0) / 2

        JsonObject retail = find(data, "Retail");
        assertEquals(1L, retail.getLong("companyCount"));
        assertEquals(1L, retail.getLong("managerCount"));
    }

    @Test
    void listing_orderedByManagerCountDescending() throws Exception {
        long big  = insertCompanyWithIndustry("Big Co",   "big-co",   "Technology");
        long smallCo = insertCompanyWithIndustry("Small Co", "small-co", "Legal");
        insertManager("M1", "m1", "approved", big, "Big Co", 4.0, 1, null);
        insertManager("M2", "m2", "approved", big, "Big Co", 4.0, 1, null);
        insertManager("M3", "m3", "approved", smallCo, "Small Co", 4.0, 1, null);

        JsonArray data = await(service.getIndustryListing()).getJsonArray("data");
        assertEquals("Technology", data.getJsonObject(0).getString("industry")); // 2 managers first
        assertEquals("Legal",      data.getJsonObject(1).getString("industry")); // 1 manager
    }

    @Test
    void listing_ignoresNullIndustryAndReviewlessAndSeedManagers() throws Exception {
        long tech = insertCompanyWithIndustry("Acme Tech", "acme-tech", "Technology");
        long none = insertCompanyWithIndustry("Unclassified Co", "unclassified-co", null);
        insertManager("Real",     "real",     "approved", tech, "Acme Tech", 4.0, 2, null);
        insertManager("NoReview", "noreview", "approved", tech, "Acme Tech", 0.0, 0, null); // reviews_count 0 -> avg unaffected
        insertManager("Orphan",   "orphan",   "approved", none, "Unclassified Co", 5.0, 5, null); // null industry
        insertSeedManager("Seed", "seed", tech, "Acme Tech");

        JsonArray data = await(service.getIndustryListing()).getJsonArray("data");
        assertEquals(1, data.size(), "only Technology should appear (null-industry company excluded)");
        JsonObject tech0 = data.getJsonObject(0);
        assertEquals("Technology", tech0.getString("industry"));
        // Seed manager excluded from counts; the two real managers counted.
        assertEquals(2L, tech0.getLong("managerCount"));
    }

    // ── profile ────────────────────────────────────────────────────────────────

    @Test
    void profile_returnsStatsAndCompanies() throws Exception {
        long acme = insertCompanyWithIndustry("Acme Tech", "acme-tech", "Technology");
        long beta = insertCompanyWithIndustry("Beta Tech", "beta-tech", "Technology");
        insertManager("Alice", "alice", "approved", acme, "Acme Tech", 4.0, 3, null);
        insertManager("Bob",   "bob",   "approved", beta, "Beta Tech", 2.0, 1, null);
        await(companyRepo.refreshCompanyStats());

        JsonObject p = await(service.getIndustryProfile("technology"));
        assertEquals("Technology", p.getString("industry"));
        assertEquals("technology", p.getString("slug"));
        assertEquals(2L, p.getLong("companyCount"));
        assertEquals(2L, p.getLong("managerCount"));
        assertEquals(2, p.getJsonArray("companies").size());
    }

    @Test
    void profile_aggregatesTenCategoryAverages() throws Exception {
        long acme = insertCompanyWithIndustry("Acme Tech", "acme-tech", "Technology");
        // Two managers with category_averages — the profile should return the per-category mean.
        insertManager("Alice", "alice", "approved", acme, "Acme Tech", 4.0, 2,
            new JsonObject().put("Communication Style", 4.0).put("Feedback Style", 5.0));
        insertManager("Bob",   "bob",   "approved", acme, "Acme Tech", 3.0, 1,
            new JsonObject().put("Communication Style", 2.0).put("Feedback Style", 3.0));
        await(companyRepo.refreshCompanyStats());

        JsonObject cats = await(service.getIndustryProfile("technology")).getJsonObject("categoryAverages");
        assertNotNull(cats);
        assertEquals(3.0, cats.getDouble("Communication Style"), 0.05); // (4.0 + 2.0) / 2
        assertEquals(4.0, cats.getDouble("Feedback Style"), 0.05);      // (5.0 + 3.0) / 2
    }

    @Test
    void profile_unknownSlug_throwsNotFound() {
        Exception ex = assertThrows(Exception.class,
            () -> await(service.getIndustryProfile("not-a-real-industry")));
        assertTrue(ex.getMessage() == null || ex.getMessage().toLowerCase().contains("not found")
                   || ex.getCause() != null, "unknown industry slug must fail (not-found)");
    }

    @Test
    void profile_slugWithAmpersand_resolves() throws Exception {
        long c = insertCompanyWithIndustry("MediaCo", "mediaco", "Media & Entertainment");
        insertManager("Mel", "mel", "approved", c, "MediaCo", 4.0, 1, null);
        await(companyRepo.refreshCompanyStats());

        JsonObject p = await(service.getIndustryProfile("media-and-entertainment"));
        assertEquals("Media & Entertainment", p.getString("industry"));
        assertEquals(1, p.getJsonArray("companies").size());
    }

    // ── classification back-fill helpers ─────────────────────────────────────────

    @Test
    void unclassified_countsAndListsOnlyDirectoryVisibleNullIndustryCompanies() throws Exception {
        long a = insertCompanyWithIndustry("Needs Class", "needs-class", null);
        long b = insertCompanyWithIndustry("Already Tech", "already-tech", "Technology");
        long empty = insertCompanyWithIndustry("Empty Co", "empty-co", null); // no managers -> not visible
        insertManager("Ma", "ma", "approved", a, "Needs Class", 4.0, 2, null);
        insertManager("Mb", "mb", "approved", b, "Already Tech", 4.0, 2, null);
        await(companyRepo.refreshCompanyStats());

        assertEquals(1L, await(companyRepo.countUnclassified()),
            "only the null-industry company that has a manager should be counted");
        var rows = await(companyRepo.findUnclassified(50));
        assertEquals(1, rows.size());
        assertEquals("Needs Class", rows.iterator().next().getString("name"));

        // After classifying it, it leaves the unclassified set.
        await(companyRepo.updateIndustry(a, "Professional Services"));
        assertEquals(0L, await(companyRepo.countUnclassified()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private long insertCompanyWithIndustry(String name, String slug, String industry) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO companies (name, slug, status, industry, created_at, updated_at)
                VALUES ($1, $2, 'ghost', $3, now(), now()) RETURNING id
                """)
            .execute(Tuple.of(name, slug, industry))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private void insertManager(String name, String slug, String status, long companyId, String company,
                               double rating, int reviews, JsonObject categoryAverages) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO managers (name, slug, company, title, status, approval_status, company_id,
                                      overall_rating, reviews_count, category_averages, created_at, updated_at)
                VALUES ($1,$2,$3,'Manager','active',$4,$5,$6,$7,$8::jsonb, now(), now())
                """)
            .execute(Tuple.of(name, slug, company, status, companyId, rating, reviews,
                (categoryAverages != null ? categoryAverages : new JsonObject()).encode())));
    }

    private void insertSeedManager(String name, String slug, long companyId, String company) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO managers (name, slug, company, title, status, approval_status, company_id,
                                      overall_rating, reviews_count, category_averages, external_id, created_at, updated_at)
                VALUES ($1,$2,$3,'Manager','active','ghost',$4,4.0,3,'{}', 'seed_x', now(), now())
                """)
            .execute(Tuple.of(name, slug, company, companyId)));
    }

    private static JsonObject find(JsonArray data, String industry) {
        for (int i = 0; i < data.size(); i++) {
            JsonObject o = data.getJsonObject(i);
            if (industry.equals(o.getString("industry"))) return o;
        }
        return null;
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}

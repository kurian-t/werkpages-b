package org.werkpages.integration;

import io.vertx.core.Future;
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
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.ManagerRepository;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class SlugIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static ManagerRepository managerRepo;
    static CompanyRepository  companyRepo;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        pool = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));
        managerRepo = new ManagerRepository(pool);
        companyRepo  = new CompanyRepository(pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── Manager slug generation ───────────────────────────────────────────────

    @Test
    void createAutoApproved_generatesNonNullSlug() throws Exception {
        Row company = await(companyRepo.findOrCreate("SlugCorp", null, null));
        long companyId = company.getLong("id");

        Row manager = await(managerRepo.createAutoApproved(
            "Jane Smith", "SlugCorp", "Engineering Manager",
            "CA", "ON", "Toronto",
            null, null, companyId));

        String slug = manager.getString("slug");
        assertNotNull(slug, "slug must be generated");
        assertFalse(slug.isBlank(), "slug must not be blank");
        assertTrue(slug.matches("[a-z0-9-]+"), "slug must be lowercase alphanumeric-hyphen only");
    }

    @Test
    void createAutoApproved_slugDerivedFromName() throws Exception {
        Row company = await(companyRepo.findOrCreate("SlugCorp2", null, null));
        long companyId = company.getLong("id");

        Row manager = await(managerRepo.createAutoApproved(
            "Alice Wong", "SlugCorp2", "Director",
            "CA", "BC", "Vancouver",
            null, null, companyId));

        String slug = manager.getString("slug");
        assertTrue(slug.startsWith("alice-wong"),
            "slug should start with name-based value, got: " + slug);
    }

    @Test
    void generateUniqueSlug_deduplicatesWhenNameCollides() throws Exception {
        Row company = await(companyRepo.findOrCreate("DupeCorp", null, null));
        long companyId = company.getLong("id");

        // Two managers with the same name at the same company — must get different slugs.
        // Strategy: name → name-company → name-company-2 …
        Row first = await(managerRepo.createAutoApproved(
            "Bob Jones", "DupeCorp", "Manager",
            "US", "NY", "New York",
            null, null, companyId));

        Row second = await(managerRepo.createAutoApproved(
            "Bob Jones", "DupeCorp", "Director",
            "US", "CA", "Los Angeles",
            null, null, companyId));

        String slug1 = first.getString("slug");
        String slug2 = second.getString("slug");

        assertNotEquals(slug1, slug2,
            "two managers with the same name must receive different slugs");
        assertEquals("bob-jones", slug1, "first should get the bare name slug");
        assertEquals("bob-jones-dupecorp", slug2,
            "second should disambiguate with the company name, not a number");
    }

    @Test
    void generateUniqueSlug_differentCompanies_noNumberSuffix() throws Exception {
        // Two managers with the same name at different companies both get clean slugs.
        Row companyA = await(companyRepo.findOrCreate("AlphaCo", null, null));
        Row companyB = await(companyRepo.findOrCreate("BetaInc", null, null));

        Row first = await(managerRepo.createAutoApproved(
            "Dana Lee", "AlphaCo", "Engineer",
            "CA", null, null,
            null, null, companyA.getLong("id")));

        Row second = await(managerRepo.createAutoApproved(
            "Dana Lee", "BetaInc", "Engineer",
            "CA", null, null,
            null, null, companyB.getLong("id")));

        assertEquals("dana-lee", first.getString("slug"), "first should get the bare name slug");
        assertEquals("dana-lee-betainc", second.getString("slug"),
            "second should use company name, not a number");
    }

    @Test
    void findBySlug_returnsManagerForKnownSlug() throws Exception {
        Row company = await(companyRepo.findOrCreate("FindSlugCorp", null, null));
        long companyId = company.getLong("id");

        Row created = await(managerRepo.createAutoApproved(
            "Carlos Rivera", "FindSlugCorp", "VP Engineering",
            "US", "TX", "Austin",
            null, null, companyId));
        String slug = created.getString("slug");

        Optional<Row> found = await(managerRepo.findBySlug(slug));

        assertTrue(found.isPresent(), "findBySlug must return the manager for a known slug");
        assertEquals("Carlos Rivera", found.get().getString("name"));
    }

    @Test
    void findBySlug_returnsEmptyForUnknownSlug() throws Exception {
        Optional<Row> result = await(managerRepo.findBySlug("this-slug-does-not-exist"));
        assertTrue(result.isEmpty(), "findBySlug must return empty for an unknown slug");
    }

    // ── URL history ───────────────────────────────────────────────────────────

    @Test
    void recordUrlHistory_and_findByOldUrl_roundtrip() throws Exception {
        Row company = await(companyRepo.findOrCreate("HistoryCorp", null, null));
        long companyId = company.getLong("id");

        Row manager = await(managerRepo.createAutoApproved(
            "Dana Lee", "HistoryCorp", "Director",
            "CA", "AB", "Calgary",
            null, null, companyId));
        long managerId = manager.getLong("id");
        String managerSlug = manager.getString("slug");

        await(managerRepo.recordUrlHistory(managerId, "old-company", managerSlug));

        Optional<Long> resolved = await(managerRepo.findByOldUrl("old-company", managerSlug));

        assertTrue(resolved.isPresent(), "findByOldUrl must find the manager via historical URL");
        assertEquals(managerId, (long) resolved.get());
    }

    @Test
    void findByOldUrl_returnsEmptyForUnknownHistory() throws Exception {
        Optional<Long> result = await(managerRepo.findByOldUrl("unknown-company", "unknown-manager"));
        assertTrue(result.isEmpty(), "findByOldUrl must return empty when no history exists");
    }

    // ── Company slug ──────────────────────────────────────────────────────────

    @Test
    void findOrCreate_generatesCompanySlug() throws Exception {
        Row company = await(companyRepo.findOrCreate("Microsoft Corporation", null, null));

        String slug = company.getString("slug");
        assertNotNull(slug, "company slug must be generated");
        assertTrue(slug.matches("[a-z0-9-]+"), "company slug must be lowercase alphanumeric-hyphen");
        assertTrue(slug.startsWith("microsoft"), "company slug should start with 'microsoft'");
    }

    @Test
    void findBySlug_returnsCompanyForKnownSlug() throws Exception {
        Row company = await(companyRepo.findOrCreate("Amazon Web Services", null, null));
        String slug = company.getString("slug");

        Optional<Row> found = await(companyRepo.findBySlug(slug));

        assertTrue(found.isPresent(), "findBySlug must return company for a known slug");
        assertEquals("Amazon Web Services", found.get().getString("name"));
    }

    @Test
    void companyFindBySlug_returnsEmptyForUnknownSlug() throws Exception {
        Optional<Row> result = await(companyRepo.findBySlug("no-such-company-slug"));
        assertTrue(result.isEmpty(), "company findBySlug must return empty for unknown slug");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}

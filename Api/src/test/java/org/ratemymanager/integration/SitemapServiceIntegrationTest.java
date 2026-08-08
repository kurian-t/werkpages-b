package org.ratemymanager.integration;

import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ratemymanager.service.SitemapService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class SitemapServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool           pool;
    static SitemapService sitemapService;

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
        sitemapService = new SitemapService(pool);
    }

    @BeforeEach
    void cleanUp() throws Exception {
        pool.query("DELETE FROM reviews").execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        pool.query("DELETE FROM managers").execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        pool.query("DELETE FROM companies").execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── Helper: insert a company with a slug ──────────────────────────────────

    private long insertCompany(String name, String slug, String status) throws Exception {
        return pool.preparedQuery("""
                INSERT INTO companies (name, slug, status, created_at, updated_at)
                VALUES ($1, $2, $3, now(), now())
                RETURNING id
                """)
            .execute(Tuple.of(name, slug, status))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    private void insertManager(String name, String slug, String status, long companyId, String company) throws Exception {
        pool.preparedQuery("""
                INSERT INTO managers
                    (name, slug, company, title, status, approval_status, company_id,
                     overall_rating, reviews_count, category_averages, created_at, updated_at)
                VALUES ($1, $2, $3, 'Manager', 'active', $4, $5, 0, 0, '{}', now(), now())
                """)
            .execute(Tuple.of(name, slug, company, status, companyId))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void generate_emptyDatabase_returnsXmlWithOnlyStaticPages() throws Exception {
        String xml = sitemapService.generate()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertNotNull(xml);
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(xml.contains("<urlset"));
        assertTrue(xml.contains("</urlset>"));

        // Static pages should always be present
        assertTrue(xml.contains("https://ratemymanagers.ca/"));
        assertTrue(xml.contains("https://ratemymanagers.ca/directory"));
        assertTrue(xml.contains("https://ratemymanagers.ca/add"));
        assertTrue(xml.contains("https://ratemymanagers.ca/about"));
        assertTrue(xml.contains("https://ratemymanagers.ca/what-is-rate-my-managers"));
        assertTrue(xml.contains("https://ratemymanagers.ca/support"));
        assertTrue(xml.contains("https://ratemymanagers.ca/privacy"));
        assertTrue(xml.contains("https://ratemymanagers.ca/terms"));
    }

    @Test
    void generate_approvedCompany_includesCompanyUrl() throws Exception {
        insertCompany("Acme Corp", "acme-corp", "approved");

        String xml = sitemapService.generate()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(xml.contains("https://ratemymanagers.ca/companies/acme-corp"));
    }

    @Test
    void generate_ghostCompany_includesCompanyUrl() throws Exception {
        insertCompany("Ghost Inc", "ghost-inc", "ghost");

        String xml = sitemapService.generate()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(xml.contains("https://ratemymanagers.ca/companies/ghost-inc"));
    }

    @Test
    void generate_approvedManager_includesManagerUrl() throws Exception {
        long companyId = insertCompany("Acme Corp", "acme-corp", "approved");
        insertManager("Jane Doe", "jane-doe", "approved", companyId, "Acme Corp");

        String xml = sitemapService.generate()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(xml.contains("https://ratemymanagers.ca/companies/acme-corp/managers/jane-doe"));
    }

    @Test
    void generate_ghostManager_includesManagerUrl() throws Exception {
        long companyId = insertCompany("Globex", "globex", "ghost");
        insertManager("Bob Burns", "bob-burns", "ghost", companyId, "Globex");

        String xml = sitemapService.generate()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(xml.contains("https://ratemymanagers.ca/companies/globex/managers/bob-burns"));
    }

    @Test
    void generate_pendingManager_excludedFromSitemap() throws Exception {
        long companyId = insertCompany("Acme Corp", "acme-corp", "approved");
        insertManager("Hidden User", "hidden-user", "pending_approval", companyId, "Acme Corp");

        String xml = sitemapService.generate()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertFalse(xml.contains("hidden-user"));
    }

    @Test
    void generate_multipleCompaniesAndManagers_allIncluded() throws Exception {
        long acmeId   = insertCompany("Acme Corp", "acme-corp", "approved");
        long globexId = insertCompany("Globex", "globex", "ghost");
        insertManager("Alice Smith", "alice-smith", "approved", acmeId, "Acme Corp");
        insertManager("Bob Burns", "bob-burns", "ghost", globexId, "Globex");

        String xml = sitemapService.generate()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(xml.contains("companies/acme-corp"));
        assertTrue(xml.contains("companies/globex"));
        assertTrue(xml.contains("companies/acme-corp/managers/alice-smith"));
        assertTrue(xml.contains("companies/globex/managers/bob-burns"));
    }

    @Test
    void generate_xmlContainsChangefreqAndPriority() throws Exception {
        String xml = sitemapService.generate()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(xml.contains("<changefreq>"));
        assertTrue(xml.contains("<priority>"));
        assertTrue(xml.contains("weekly"));
        assertTrue(xml.contains("1.0")); // homepage priority
    }

    @Test
    void generate_homepageHasHighestPriority() throws Exception {
        String xml = sitemapService.generate()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // The homepage entry should have priority 1.0
        assertTrue(xml.contains("<loc>https://ratemymanagers.ca/</loc>"));
        // Find the first <url> block and verify it has 1.0 priority
        int homepageIdx = xml.indexOf("<loc>https://ratemymanagers.ca/</loc>");
        String homepageBlock = xml.substring(
            xml.lastIndexOf("<url>", homepageIdx),
            xml.indexOf("</url>", homepageIdx) + 6
        );
        assertTrue(homepageBlock.contains("1.0"));
    }

    @Test
    void generate_companyWithNullSlug_excluded() throws Exception {
        // Insert a company with a null slug — should not appear in sitemap
        pool.preparedQuery("""
                INSERT INTO companies (name, status, created_at, updated_at)
                VALUES ('No Slug Co', 'approved', now(), now())
                """)
            .execute(Tuple.tuple())
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        String xml = sitemapService.generate()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertFalse(xml.contains("No Slug Co"));
    }
}

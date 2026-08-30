package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
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
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CompanySuggestIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static ManagerService    service;
    static ManagerRepository managerRepo;

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
        ReviewRepository  reviewRepo  = new ReviewRepository(pool);
        UserRepository    userRepo    = new UserRepository(pool);
        EditRepository    editRepo    = new EditRepository(pool);
        ReportRepository  reportRepo  = new ReportRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void suggestCompanies_matchingQuery_returnsResults() throws Exception {
        insertManagerWithStatus("Alice A", "Acme Corp", "Manager", "approved");
        insertManagerWithStatus("Bob B",   "Acme Industries", "Director", "ghost");
        insertManagerWithStatus("Carol C", "Skynet Inc", "Engineer", "approved");

        JsonArray results = await(service.suggestCompanies("acme"));

        assertEquals(2, results.size());
        long acmeCount = StreamSupport.stream(results.spliterator(), false)
            .map(o -> ((io.vertx.core.json.JsonObject) o).getString("name"))
            .filter(n -> n.toLowerCase().contains("acme"))
            .count();
        assertEquals(2, acmeCount);
    }

    @Test
    void suggestCompanies_caseInsensitive_matchesRegardlessOfCase() throws Exception {
        insertManagerWithStatus("Dave D", "Globex Corporation", "VP", "approved");

        JsonArray results = await(service.suggestCompanies("GLOBEX"));

        assertEquals(1, results.size());
        assertEquals("Globex Corporation", results.getJsonObject(0).getString("name"));
    }

    @Test
    void suggestCompanies_pendingManagerNoLongerHidesItsCompany() throws Exception {
        // CHANGED DELIBERATELY. This test previously asserted the opposite: that a company whose
        // only manager was pending stayed out of the typeahead. That rule came from the picker
        // being sourced from managers. Companies are the authority now, so manager approval no
        // longer decides whether a company can be selected.
        //
        // Kept as an explicit assertion rather than deleted, because the change has a real cost:
        // a company name submitted by anyone becomes publicly suggestible before review.
        insertManagerWithStatus("Eve E", "Hidden Corp", "Manager", "pending_approval");

        JsonArray results = await(service.suggestCompanies("Hidden"));

        assertEquals(1, results.size());
        assertEquals("Hidden Corp", results.getJsonObject(0).getString("name"));
    }

    @Test
    void suggestCompanies_emptyQuery_returnsEmpty() throws Exception {
        insertManagerWithStatus("Frank F", "Some Corp", "Manager", "approved");

        JsonArray results = await(service.suggestCompanies(""));

        assertEquals(0, results.size());
    }

    @Test
    void suggestCompanies_noMatch_returnsEmpty() throws Exception {
        insertManagerWithStatus("Grace G", "Umbrella Corp", "Manager", "approved");

        JsonArray results = await(service.suggestCompanies("xyz123"));

        assertEquals(0, results.size());
    }

    @Test
    void suggestCompanies_includesLogoUrlWhenPresent() throws Exception {
        insertManager("Jane J", "Logoland", "Manager", "approved", "https://img.logo.dev/logoland.com?token=x");

        JsonArray results = await(service.suggestCompanies("logoland"));

        assertEquals(1, results.size());
        assertEquals("Logoland", results.getJsonObject(0).getString("name"));
        assertEquals("https://img.logo.dev/logoland.com?token=x", results.getJsonObject(0).getString("logoUrl"));
    }

    @Test
    void suggestCompanies_omitsLogoUrlWhenAbsent() throws Exception {
        insertManagerWithStatus("Kyle K", "Nologo Inc", "Manager", "approved");

        JsonArray results = await(service.suggestCompanies("nologo"));

        assertEquals(1, results.size());
        assertFalse(results.getJsonObject(0).containsKey("logoUrl"));
    }

    @Test
    void suggestCompanies_deduplicatesCompanyNames() throws Exception {
        insertManagerWithStatus("Hank H", "Initech", "Manager", "approved");
        insertManagerWithStatus("Iris I",  "Initech", "Director", "approved");

        JsonArray results = await(service.suggestCompanies("init"));

        assertEquals(1, results.size());
        assertEquals("Initech", results.getJsonObject(0).getString("name"));
    }

    // ── Phase 1: suggestions carry company identity ───────────────────────────

    @Test
    void suggestCompanies_returnsCompanyIdWhenCompanyRowExists() throws Exception {
        long companyId = insertCompany("Crumbl", "approved");
        insertManagerForCompany("Jane J", "Crumbl", companyId);

        JsonArray results = await(service.suggestCompanies("crumb"));

        assertEquals(1, results.size());
        assertEquals("Crumbl", results.getJsonObject(0).getString("name"));
        assertEquals(companyId, results.getJsonObject(0).getLong("id"));
    }

    @Test
    void suggestCompanies_returnsCanonicalNameNotTheManagerText() throws Exception {
        // The whole point of sourcing from `companies`: the picker offers one canonical spelling
        // even when manager rows disagree about casing.
        long companyId = insertCompany("Crumbl Bakery", "approved");
        insertManagerForCompany("Kim K", "CRUMBL BAKERY", companyId);

        JsonArray results = await(service.suggestCompanies("crumbl b"));

        assertEquals(1, results.size());
        assertEquals("Crumbl Bakery", results.getJsonObject(0).getString("name"));
    }

    @Test
    void suggestCompanies_findsACompanyWithNoManagersYet() throws Exception {
        // The picker reads companies, not managers. A canonical company that nobody has attached a
        // manager to is exactly the company someone is about to re-create by hand, so it must be
        // findable. Under the old manager-sourced picker it was invisible.
        long companyId = insertCompany("Empty Holdings Co", "approved");

        JsonArray results = await(service.suggestCompanies("Empty Holdings"));

        assertEquals(1, results.size());
        assertEquals(companyId, results.getJsonObject(0).getLong("id"));
    }

    @Test
    void suggestCompanies_companyIsVisibleEvenWhenItsOnlyManagerIsPending() throws Exception {
        // BEHAVIOUR CHANGE, made deliberately: manager approval status no longer decides whether a
        // company is searchable. Companies are the authority now, and a company whose only manager
        // is awaiting review is still a real company somebody may need to select.
        //
        // The trade is that a company row created by an unreviewed submission becomes visible in a
        // public typeahead before any human has looked at the name.
        long companyId = insertCompany("Pending Only Corp", "ghost");
        insertManagerForCompanyWithStatus("Mia M", "Pending Only Corp", companyId, "pending_approval");

        JsonArray results = await(service.suggestCompanies("Pending Only"));

        assertEquals(1, results.size());
        assertEquals(companyId, results.getJsonObject(0).getLong("id"));
    }

    @Test
    void suggestCompanies_includesIndustryWhenKnown() throws Exception {
        long companyId = insertCompany("Zehrs Markets", "approved");
        await(pool.preparedQuery("UPDATE companies SET industry = 'Retail' WHERE id = $1")
                  .execute(Tuple.of(companyId)));
        insertManagerForCompany("Ned N", "Zehrs Markets", companyId);

        JsonArray results = await(service.suggestCompanies("zehrs"));

        assertEquals(1, results.size());
        assertEquals("Retail", results.getJsonObject(0).getString("industry"));
    }

    /**
     * cleanDb truncates managers but not companies, so a company name used by one test is still
     * there for the next. Reuse rather than collide: the unique name index would otherwise make
     * test order significant.
     */
    private long insertCompany(String name, String status) throws Exception {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        await(pool
            .preparedQuery("INSERT INTO companies(name,status,slug) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING")
            .execute(Tuple.of(name, status, slug))
            .mapEmpty());
        return await(pool
            .preparedQuery("SELECT id FROM companies WHERE LOWER(TRIM(name)) = LOWER(TRIM($1))")
            .execute(Tuple.of(name))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManagerForCompany(String name, String company, long companyId) throws Exception {
        return insertManagerForCompanyWithStatus(name, company, companyId, "approved");
    }

    private long insertManagerForCompanyWithStatus(String name, String company, long companyId, String status) throws Exception {
        return await(pool
            .preparedQuery("INSERT INTO managers(name,company,company_id,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
                           "VALUES ($1,$2,$3,'Manager','img','active',$4,0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, companyId, status))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    // ── Search behaviour matrix ───────────────────────────────────────────────

    @Test
    void suggestCompanies_exactNameOutranksAPrefixMatch() throws Exception {
        // Typing "Apple" must not be buried under Apple Hospitality REIT. Lexical relevance
        // first; popularity only breaks ties.
        insertCompany("Apple", "approved");
        insertCompany("Apple Hospitality REIT", "approved");
        insertCompany("Apple Federal Credit Union", "approved");

        JsonArray results = await(service.suggestCompanies("Apple"));

        assertEquals("Apple", results.getJsonObject(0).getString("name"));
    }

    @Test
    void suggestCompanies_exactAliasOutranksAPrefixMatchOnAnotherCompany() throws Exception {
        long crumbl = insertCompany("Crumbl", "approved");
        insertAlias(crumbl, "Crumbl Cookies", "COMMON_NAME");
        insertCompany("Crumbl Cookies Supply Chain", "approved");

        JsonArray results = await(service.suggestCompanies("Crumbl Cookies"));

        assertEquals("Crumbl", results.getJsonObject(0).getString("name"));
    }

    @Test
    void suggestCompanies_matchesInTheMiddleOfAName() throws Exception {
        insertCompany("Great Northern Widgets", "approved");

        assertEquals("Great Northern Widgets",
            await(service.suggestCompanies("Northern")).getJsonObject(0).getString("name"));
    }

    @Test
    void suggestCompanies_twoCharacterQuerySkipsTheContainsTiers() throws Exception {
        // "co" appears inside a huge share of company names. Exact and prefix stay cheap and
        // useful; the contains tiers would return thousands of useless candidates.
        insertCompany("Zeta Cortex", "approved");   // contains "co", does not start with it
        insertCompany("Cobalt Systems", "approved"); // starts with "co"

        JsonArray results = await(service.suggestCompanies("co"));

        assertEquals(1, results.size());
        assertEquals("Cobalt Systems", results.getJsonObject(0).getString("name"));
    }

    @Test
    void suggestCompanies_anAliasSharedByTwoCompaniesReturnsBoth() throws Exception {
        // "Summit" is genuinely many companies. An alias narrows the candidates; it never picks.
        long a = insertCompany("Summit Roofing", "approved");
        long b = insertCompany("Summit Analytics", "approved");
        insertAlias(a, "Summit", "ABBREVIATION");
        insertAlias(b, "Summit", "ABBREVIATION");

        JsonArray results = await(service.suggestCompanies("Summit"));

        assertEquals(2, results.size());
        assertNotEquals(results.getJsonObject(0).getLong("id"), results.getJsonObject(1).getLong("id"));
    }

    @Test
    void suggestCompanies_neverReturnsMoreThanTheDisplayLimit() throws Exception {
        for (int i = 0; i < 12; i++) insertCompany("Bulk Test Co " + i, "approved");

        assertTrue(await(service.suggestCompanies("Bulk Test")).size() <= 8);
    }

    // ── Phase 2: aliases make a company findable under its other names ────────

    @Test
    void suggestCompanies_findsACompanyByItsAlias() throws Exception {
        // The exact duplicate-creating case: the canonical row is "Crumbl", the user types the
        // name they know it by, and without the alias they would be offered nothing and create
        // a second company.
        long companyId = insertCompany("Crumbl", "approved");
        insertAlias(companyId, "Crumbl Cookies", "COMMON_NAME");
        insertManagerForCompany("Jane J", "Crumbl", companyId);

        JsonArray results = await(service.suggestCompanies("Crumbl Cookies"));

        // Ranked first, not returned alone: other companies whose names contain "Crumbl" are
        // legitimate near matches and belong in the list, just below the exact alias hit.
        assertEquals("Crumbl", results.getJsonObject(0).getString("name"));
        assertEquals(companyId, results.getJsonObject(0).getLong("id"));
    }

    @Test
    void suggestCompanies_matchesThroughPunctuationAndLegalSuffix() throws Exception {
        // No alias needed: normalisation alone closes the gap between how people type a company
        // name and how it was stored.
        long companyId = insertCompany("Acme", "approved");
        insertManagerForCompany("Ann A", "Acme", companyId);

        assertEquals("Acme", await(service.suggestCompanies("Acme, Inc.")).getJsonObject(0).getString("name"));
        assertEquals("Acme", await(service.suggestCompanies("ACME LLC")).getJsonObject(0).getString("name"));
    }

    @Test
    void suggestCompanies_ampersandAndTheWordAndAreTheSame() throws Exception {
        long companyId = insertCompany("Johnson & Johnson", "approved");
        insertManagerForCompany("Jo J", "Johnson & Johnson", companyId);

        assertEquals("Johnson & Johnson",
            await(service.suggestCompanies("Johnson and Johnson")).getJsonObject(0).getString("name"));
    }

    @Test
    void suggestCompanies_aliasDoesNotMergeTwoCompanies() throws Exception {
        // An alias makes a company discoverable. It must never assert that two companies are one:
        // both stay separate rows with separate IDs, and the user picks.
        long crumbl = insertCompany("Crumbl", "approved");
        long cafe   = insertCompany("Crumbl Cafe", "approved");
        insertAlias(crumbl, "Crumbl Cookies", "COMMON_NAME");
        insertManagerForCompany("Jane J", "Crumbl", crumbl);
        insertManagerForCompany("Joe J",  "Crumbl Cafe", cafe);

        JsonArray results = await(service.suggestCompanies("crumbl"));

        // Both survive as separate rows with separate IDs. An alias narrows candidates; it never
        // collapses two companies into one.
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (int i = 0; i < results.size(); i++) ids.add(results.getJsonObject(i).getLong("id"));
        assertTrue(ids.contains(crumbl), "canonical company missing");
        assertTrue(ids.contains(cafe),   "the similarly named company was swallowed");
    }

    @Test
    void normalizeCompanyName_keepsAWordThatMerelyContainsASuffix() throws Exception {
        // "Coca Cola" must not lose its "co", and "Incoterms" must not lose its "inc" - suffixes
        // are stripped as whole trailing words only.
        assertEquals("coca cola", normalize("Coca Cola"));
        assertEquals("incoterms", normalize("Incoterms"));
        assertEquals("loblaw",    normalize("Loblaw Co"));
    }

    @Test
    void normalizeCompanyName_aNameThatIsOnlyASuffixSurvives() throws Exception {
        // Stripping would leave an empty string, which would match every other empty string.
        assertEquals("group", normalize("Group"));
        assertEquals("co",    normalize("Co"));
    }

    @Test
    void normalizeCompanyName_foldsAccents() throws Exception {
        assertEquals("nestle", normalize("Nestlé"));
    }

    private String normalize(String name) throws Exception {
        return await(pool.preparedQuery("SELECT normalize_company_name($1) AS n")
            .execute(Tuple.of(name))
            .map(rs -> rs.iterator().next().getString("n")));
    }

    private void insertAlias(long companyId, String alias, String type) throws Exception {
        // normalized_alias is a generated column as of V55 - the database derives it, nobody writes it.
        await(pool.preparedQuery("INSERT INTO company_aliases(company_id, alias, alias_type) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING")
            .execute(Tuple.of(companyId, alias, type))
            .mapEmpty());
    }

    /**
     * Creates the company row alongside the manager, which is what production does: every write
     * path calls findOrCreate first. These helpers predate the companies table and used to insert
     * a manager on its own, a state no live code can now produce.
     */
    private long insertManagerWithStatus(String name, String company, String title, String status) throws Exception {
        long companyId = insertCompany(company, "approved");
        return await(pool
            .preparedQuery("INSERT INTO managers(name,company,company_id,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
                           "VALUES ($1,$2,$3,$4,'img','active',$5,0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, companyId, title, status))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManager(String name, String company, String title, String status, String logoUrl) throws Exception {
        long companyId = insertCompanyWithLogo(company, logoUrl);
        return await(pool
            .preparedQuery("INSERT INTO managers(name,company,company_id,title,image,status,approval_status,overall_rating,reviews_count,category_averages,company_logo_url) " +
                           "VALUES ($1,$2,$3,$4,'img','active',$5,0,0,'{}',$6) RETURNING id")
            .execute(Tuple.of(name, company, companyId, title, status, logoUrl))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertCompanyWithLogo(String name, String logoUrl) throws Exception {
        long id = insertCompany(name, "approved");
        await(pool.preparedQuery("UPDATE companies SET logo_url = $2 WHERE id = $1")
            .execute(Tuple.of(id, logoUrl)).mapEmpty());
        return id;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}

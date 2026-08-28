package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.RoleAliasRepository;
import org.werkpages.service.RoleService;
import org.werkpages.service.ServiceException;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Role normalization end to end: the database trigger, the rule pass, and the review surface.
 *
 * <p>The string half is tested against the real {@code normalize_role_title()} rather than a Java
 * copy, because the database is the only implementation — managers are written from five code
 * paths across two applications, and a trigger is the one place that catches all of them.
 */
@Testcontainers
class RoleNormalizationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static RoleService service;
    static RoleAliasRepository roleAliasRepo;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));

        roleAliasRepo = new RoleAliasRepository(pool);
        service = new RoleService(roleAliasRepo);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE role_aliases").execute());
        await(pool.query("TRUNCATE managers, users, companies CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The normalizing function
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void collapsesTheSpellingsOfOneJobOntoOneString() throws Exception {
        // The whole point of the feature: these are one job typed six ways.
        String expected = normalize("Senior Manager");
        assertEquals("senior manager", expected);
        for (String variant : new String[] {
            "Sr. Manager", "sr manager", "SR MANAGER", "Snr. Mgr", "  Senior   Manager  ", "Sr/Manager",
        }) {
            assertEquals(expected, normalize(variant), variant + " should normalize like Senior Manager");
        }
    }

    @Test
    void expandsAbbreviationsOnWordBoundariesOnly() throws Exception {
        assertEquals("senior engineering manager", normalize("Sr. Eng. Mgr"));
        assertEquals("director of operations",     normalize("Dir. of Ops"));
        assertEquals("vice president of human resources", normalize("VP of HR"));
        assertEquals("chief technology officer",   normalize("CTO"));
        // "sr" inside a word must not become "senior".
        assertEquals("disregard",  normalize("disregard"));
        assertEquals("management", normalize("management"));
    }

    @Test
    void leavesPmAloneBecauseItIsAmbiguous() throws Exception {
        // PM means Product Manager to some people and Project Manager to others. Expanding it
        // either way would silently merge two different populations of managers.
        assertEquals("pm", normalize("PM"));
        assertEquals("senior pm", normalize("Sr. PM"));
    }

    @Test
    void stripsPunctuationAndFoldsCase() throws Exception {
        assertEquals("head of engineering", normalize("Head of Engineering"));
        assertEquals("manager engineering", normalize("Manager (Engineering)"));
        assertEquals("engineering manager", normalize("Engineering — Manager"));
        assertEquals("engineering manager", normalize("engineering/manager"));
    }

    @Test
    void returnsNullForNothingUsable() throws Exception {
        assertNull(normalize(null));
        assertNull(normalize(""));
        assertNull(normalize("   "));
        assertNull(normalize("!!!"), "punctuation alone carries no title");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The trigger
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void insertingAManagerNormalizesItsTitleWithoutApplicationCode() throws Exception {
        long id = insertManager("Ada L", "Sr. Eng. Mgr", "approved", null);
        assertEquals("senior engineering manager", titleNormalizedOf(id),
            "five insert paths across two apps — only the database catches all of them");
    }

    @Test
    void changingATitleRenormalizesIt() throws Exception {
        long id = insertManager("Grace H", "Sr. Manager", "approved", null);
        assertEquals("senior manager", titleNormalizedOf(id));

        await(pool.preparedQuery("UPDATE managers SET title = $1 WHERE id = $2")
            .execute(Tuple.of("Director of Engineering", id)));

        assertEquals("director of engineering", titleNormalizedOf(id),
            "an edited title must not keep the old normalization");
    }

    @Test
    void changingSomethingOtherThanTheTitleLeavesItAlone() throws Exception {
        long id = insertManager("Alan T", "Sr. Manager", "approved", null);
        await(pool.preparedQuery("UPDATE managers SET company = 'Elsewhere' WHERE id = $1")
            .execute(Tuple.of(id)));
        assertEquals("senior manager", titleNormalizedOf(id));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The rule pass
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void classifiesEveryDistinctTitleOnce() throws Exception {
        insertManager("A", "Sr. Eng. Mgr", "approved", null);
        insertManager("B", "Senior Engineering Manager", "approved", null);
        insertManager("C", "Director of Sales", "approved", null);

        JsonObject result = await(service.classifyPending());

        assertEquals(2, result.getInteger("classified"),
            "two distinct normalized titles behind three managers");
        assertEquals(0, result.getInteger("remaining"));

        Row engineering = alias("senior engineering manager");
        assertEquals("engineering", engineering.getString("role_family"));
        assertEquals("senior_manager", engineering.getString("seniority"));
        assertEquals("rule", engineering.getString("source"));

        Row sales = alias("director of sales");
        assertEquals("sales", sales.getString("role_family"));
        assertEquals("director", sales.getString("seniority"));
    }

    @Test
    void recordsTitlesItCannotReadRatherThanSkippingThem() throws Exception {
        insertManager("A", "Wizard of Light Bulb Moments", "approved", null);

        JsonObject result = await(service.classifyPending());

        assertEquals(1, result.getInteger("classified"));
        assertEquals(1, result.getInteger("unreadable"));

        Row row = alias("wizard of light bulb moments");
        assertNull(row.getString("role_family"));
        assertNull(row.getString("seniority"));
        assertEquals("rule", row.getString("source"),
            "recorded so the next pass does not re-examine it, and so an AI pass has a work list");
    }

    @Test
    void isIdempotent() throws Exception {
        insertManager("A", "Engineering Manager", "approved", null);
        assertEquals(1, await(service.classifyPending()).getInteger("classified"));
        assertEquals(0, await(service.classifyPending()).getInteger("classified"),
            "a second pass has nothing left to do");
    }

    @Test
    void ignoresSeedAndNonPublicManagers() throws Exception {
        insertManager("Seeded", "Seeded Title", "approved", "seed_1");
        insertManager("Pending", "Pending Title", "pending_approval", null);
        insertManager("Real", "Engineering Manager", "approved", null);

        assertEquals(1, await(service.classifyPending()).getInteger("classified"),
            "effort goes to titles that visible managers actually hold");
        assertTrue(aliasOptional("seeded title").isEmpty());
        assertTrue(aliasOptional("pending title").isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Suggestions — the half that stops new spellings being created
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void suggestsTheSpellingOtherPeopleActuallyUsed() throws Exception {
        insertManager("A", "Senior Engineering Manager", "approved", null);
        insertManager("B", "Senior Engineering Manager", "approved", null);
        insertManager("C", "Sr. Engineering Manager", "approved", null);

        JsonArray results = await(service.suggestTitles("engineering"));

        assertEquals(1, results.size(), "three managers, one normalized title");
        JsonObject top = results.getJsonObject(0);
        assertEquals("Senior Engineering Manager", top.getString("title"),
            "the most common real spelling, not the normalized string — nobody picks 'senior engineering manager'");
        assertEquals(3, top.getInteger("managerCount"));
    }

    @Test
    void expandsAbbreviationsInTheQuery() throws Exception {
        insertManager("A", "Senior Manager", "approved", null);
        assertEquals(1, await(service.suggestTitles("sr")).size(),
            "typing an abbreviation must find the expanded spelling");
    }

    @Test
    void matchesPartialWordsThatTheExpanderWouldHaveMangled() throws Exception {
        // "dev" normalizes to "development", which does not match "developer". Matching the
        // case-folded query as well as the normalized one is what keeps prefix search working.
        insertManager("A", "Developer", "approved", null);
        assertEquals(1, await(service.suggestTitles("dev")).size());
        assertEquals(1, await(service.suggestTitles("deve")).size());
    }

    @Test
    void ordersPrefixMatchesAheadOfMerelyContaining() throws Exception {
        insertManager("A", "Engineering Manager", "approved", null);
        insertManager("B", "Senior Engineering Manager", "approved", null);
        insertManager("C", "Senior Engineering Manager", "approved", null);

        JsonArray results = await(service.suggestTitles("engineering"));

        assertEquals("Engineering Manager", results.getJsonObject(0).getString("title"),
            "a prefix match wins even though the other spelling is more common");
    }

    @Test
    void staysSilentUntilTheQueryMeansSomething() throws Exception {
        insertManager("A", "Developer", "approved", null);
        assertTrue(await(service.suggestTitles("d")).isEmpty(), "one character matches nearly everything");
        assertTrue(await(service.suggestTitles("")).isEmpty());
        assertTrue(await(service.suggestTitles("  ")).isEmpty());
        assertTrue(await(service.suggestTitles(null)).isEmpty());
    }

    @Test
    void suggestsOnlyFromManagersPeopleCanSee() throws Exception {
        insertManager("Seeded", "Developer", "approved", "seed_1");
        insertManager("Pending", "Developer", "pending_approval", null);

        assertTrue(await(service.suggestTitles("developer")).isEmpty(),
            "seed and unapproved rows must not shape what the next person types");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Manual corrections
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void aManualCorrectionSurvivesLaterRulePasses() throws Exception {
        insertManager("A", "Engineering Manager", "approved", null);
        await(service.classifyPending());
        assertEquals("engineering", alias("engineering manager").getString("role_family"));

        await(service.classifyManually("engineering manager", "product", "director"));

        // Re-running the rules must not undo a human decision, or correcting anything is pointless.
        insertManager("B", "Some Other Title", "approved", null);
        await(service.classifyPending());

        Row row = alias("engineering manager");
        assertEquals("product", row.getString("role_family"));
        assertEquals("director", row.getString("seniority"));
        assertEquals("manual", row.getString("source"));
    }

    @Test
    void aManualCorrectionCanSetJustOneAxis() throws Exception {
        await(service.classifyManually("some title", "finance", null));
        Row row = alias("some title");
        assertEquals("finance", row.getString("role_family"));
        assertNull(row.getString("seniority"));
    }

    @Test
    void rejectsNonsenseCorrections() {
        assertEquals(400, statusOfFailure(service.classifyManually(null, "finance", null)));
        assertEquals(400, statusOfFailure(service.classifyManually("  ", "finance", null)));
        assertEquals(400, statusOfFailure(service.classifyManually("t", null, null)));
        assertEquals(400, statusOfFailure(service.classifyManually("t", "astrology", null)));
        assertEquals(400, statusOfFailure(service.classifyManually("t", null, "supreme leader")));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The review surface
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    class ReviewSurface {

        @Test
        void listsTitlesMostCommonFirst() throws Exception {
            insertManager("A", "Engineering Manager", "approved", null);
            insertManager("B", "Engineering Manager", "approved", null);
            insertManager("C", "Engineering Manager", "approved", null);
            insertManager("D", "Director of Sales", "approved", null);
            await(service.classifyPending());

            JsonArray data = await(service.listAliases(50, 0)).getJsonArray("data");

            assertEquals("engineering manager", data.getJsonObject(0).getString("titleNormalized"),
                "a wrong rule on a common title matters far more than one nobody holds");
            assertEquals(3, data.getJsonObject(0).getInteger("managerCount"));
            assertEquals(1, data.getJsonObject(1).getInteger("managerCount"));
        }

        @Test
        void showsARealTitleAlongsideTheNormalizedOne() throws Exception {
            insertManager("A", "Sr. Eng. Mgr", "approved", null);
            await(service.classifyPending());

            JsonObject row = await(service.listAliases(50, 0)).getJsonArray("data").getJsonObject(0);

            assertEquals("senior engineering manager", row.getString("titleNormalized"));
            assertEquals("Sr. Eng. Mgr", row.getString("sampleTitle"),
                "the mapping can only be judged against what someone actually typed");
        }

        @Test
        void paginates() throws Exception {
            for (int i = 0; i < 5; i++) insertManager("M" + i, "Title " + i + " Manager", "approved", null);
            await(service.classifyPending());

            assertEquals(2, await(service.listAliases(2, 0)).getJsonArray("data").size());
            assertEquals(3, await(service.listAliases(10, 2)).getJsonArray("data").size());
        }

        @Test
        void clampsAbsurdPageSizes() throws Exception {
            insertManager("A", "Engineering Manager", "approved", null);
            await(service.classifyPending());

            assertEquals(1, await(service.listAliases(0, 0)).getJsonArray("data").size(),
                "a limit of zero would return an empty page forever");
            assertEquals(1, await(service.listAliases(10_000, -5)).getJsonArray("data").size());
        }

        @Test
        void reportsCoverageByManagerNotByTitle() throws Exception {
            // One well-understood title held by three people, one unreadable title held by one.
            // By title that is 50% coverage; by manager it is 75%, which is the number that
            // actually describes how much of the corpus is usable.
            insertManager("A", "Engineering Manager", "approved", null);
            insertManager("B", "Engineering Manager", "approved", null);
            insertManager("C", "Engineering Manager", "approved", null);
            insertManager("D", "Wizard of Light Bulb Moments", "approved", null);
            await(service.classifyPending());

            JsonObject coverage = await(service.coverage());

            assertEquals(2, coverage.getInteger("distinctTitles"));
            assertEquals(2, coverage.getInteger("classifiedTitles"));
            assertEquals(0, coverage.getInteger("remaining"));
            assertEquals(4, coverage.getInteger("managersTotal"));
            assertEquals(3, coverage.getInteger("managersWithFamily"));
            assertEquals(75, coverage.getInteger("familyCoveragePct"));
        }

        @Test
        void reportsZeroCoverageOnAnEmptyCorpusRatherThanDividingByZero() throws Exception {
            JsonObject coverage = await(service.coverage());
            assertEquals(0, coverage.getInteger("managersTotal"));
            assertEquals(0, coverage.getInteger("familyCoveragePct"));
            assertEquals(0, coverage.getInteger("seniorityCoveragePct"));
        }

        @Test
        void reportsWhatIsStillOutstanding() throws Exception {
            insertManager("A", "Engineering Manager", "approved", null);
            insertManager("B", "Director of Sales", "approved", null);

            assertEquals(2, await(service.coverage()).getInteger("remaining"));
            await(service.classifyPending());
            assertEquals(0, await(service.coverage()).getInteger("remaining"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static String normalize(String title) throws Exception {
        return await(pool.preparedQuery("SELECT normalize_role_title($1) AS n")
            .execute(Tuple.of(title))
            .map(rs -> rs.iterator().next().getString("n")));
    }

    private static String titleNormalizedOf(long managerId) throws Exception {
        return await(pool.preparedQuery("SELECT title_normalized FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("title_normalized")));
    }

    private static Row alias(String titleNormalized) throws Exception {
        return aliasOptional(titleNormalized)
            .orElseThrow(() -> new AssertionError("no alias row for " + titleNormalized));
    }

    private static Optional<Row> aliasOptional(String titleNormalized) throws Exception {
        return await(roleAliasRepo.findByTitle(titleNormalized));
    }

    private static long insertManager(String name, String title, String approvalStatus, String externalId)
            throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers(name, company, title, image, status, approval_status,
                                     overall_rating, reviews_count, category_averages, external_id)
                VALUES ($1, 'Co', $2, 'img', 'active', $3, 0, 0, '{}', $4)
                RETURNING id
                """)
            .execute(Tuple.of(name + "-" + UUID.randomUUID().toString().substring(0, 6),
                              title, approvalStatus, externalId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static int statusOfFailure(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            return fail("expected the call to fail, but it succeeded");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ServiceException se) return se.getStatusCode();
            return fail("expected a ServiceException but got " + e.getCause());
        } catch (Exception e) {
            return fail(e);
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}

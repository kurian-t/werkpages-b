package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.*;
import org.werkpages.service.ManagerService;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The approved manager-search specification, as failing tests.
 *
 * <p>Every case here is written against the policy agreed on 2026-10-14 and recorded in
 * {@code manager-search-flow.md} section 5a. The code does NOT implement that policy yet, so most
 * of this class is expected to fail until the shared matcher exists. That is the point: these were
 * written first.
 *
 * <p>The scenario mirrors the production record that prompted the work. Aaron Yip (manager 8960)
 * is a live ghost at QuadReal Property Group carrying one review, with career history at Warrington
 * PCI Management where that review was earned. Searching his name at either company created more
 * rows instead of finding him - eight in total for one person - because every matching query keys
 * on the CURRENT employer and none consults career history.
 *
 * <p><b>No metered service is touched.</b> The six-argument {@link ManagerService} constructor
 * passes {@code company -> null} as its logo resolver, so nothing here can reach logo.dev, whose
 * monthly allowance was once spent by a test run.
 */
@Testcontainers
class ManagerMatchingSpecIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("wp_match_test").withUsername("test").withPassword("test");

    static Pool pool;
    static ManagerService service;
    static UserRepository userRepo;

    static final String QUADREAL   = "QuadReal Property Group";
    static final String WARRINGTON = "Warrington PCI Management";

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")   // Werkpages owns the migration stream
            .load().migrate();

        PgConnectOptions opts = new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword());

        pool     = PgPool.pool(opts, new PoolOptions().setMaxSize(5));
        userRepo = new UserRepository(pool);
        service  = new ManagerService(new ManagerRepository(pool), new ReviewRepository(pool),
                                      userRepo, new EditRepository(pool), new ReportRepository(pool),
                                      pool);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, companies, users, career_history, reviews CASCADE").execute());
    }

    // ── The reported defect ───────────────────────────────────────────────────

    @Test
    @DisplayName("searching a FORMER employer returns his tile and creates nothing")
    void formerEmployerFromCareerHistoryIsFound() throws Exception {
        long aaron = seedAaronYip();
        String searcher = insertUser("auth0|searcher-1", "searcher1");
        long before = countManagers();

        // Exactly the production search: his previous employer, a partial title.
        JsonObject result = await(service.findOrCreate(
            searcher, "Aaron", "Yip", "Controller", WARRINGTON, "Canada", null, null, null));

        JsonArray data = result.getJsonArray("data");

        // 1. his existing PUBLIC tile must come back
        assertFalse(data.isEmpty(),
            "Aaron Yip was not returned at all. His profile is public and the company page already "
            + "lists him at " + WARRINGTON + " via career_history - search must see what the "
            + "company page sees.");
        assertEquals(aaron, data.getJsonObject(0).getLong("id"),
            "the existing manager should be the first result, got: " + data.encode());
        assertTrue(java.util.Set.of("ghost", "approved")
                      .contains(data.getJsonObject(0).getString("approvalStatus")),
            "a tile may only be rendered for a row the profile page will serve");

        // 2. the association must be evaluated against CAREER HISTORY, not just the current role
        assertEquals(WARRINGTON, careerCompanyOf(aaron),
            "fixture sanity: the career entry under test must exist");

        // 3. nothing new may be created
        assertFalse(result.getBoolean("created", false), "nothing should have been created");
        assertEquals(before, countManagers(),
            "a duplicate manager row was created for somebody we already hold. This is the "
            + "production defect: eight rows existed for one Aaron Yip.");
    }

    @Test
    @DisplayName("a partial title must not be treated as a different job")
    void partialTitleDoesNotJustifyAGhost() throws Exception {
        seedAaronYip();
        String searcher = insertUser("auth0|searcher-2", "searcher2");

        // "Controller" is a partial version of "Divisional Controller" - PLAUSIBLE, not DIFFERENT.
        // Plausibility anywhere blocks ghost creation; it never confirms identity either.
        JsonObject result = await(service.findOrCreate(
            searcher, "Aaron", "Yip", "Controller", WARRINGTON, "Canada", null, null, null));

        assertEquals(0, countByStatus("ghost") - 1,
            "a second public ghost was published for Aaron Yip on the strength of a partial title");
    }

    @Test
    @DisplayName("the exact company and title pair returns him and creates nothing")
    void exactPairIsFound() throws Exception {
        long aaron = seedAaronYip();
        String searcher = insertUser("auth0|searcher-3", "searcher3");
        long before = countManagers();

        JsonObject result = await(service.findOrCreate(
            searcher, "Aaron", "Yip", "Divisional Controller", WARRINGTON, "Canada", null, null, null));

        assertEquals(aaron, result.getJsonArray("data").getJsonObject(0).getLong("id"));
        assertEquals(before, countManagers(), "nothing should have been created");
    }

    @Test
    @DisplayName("a genuinely different company AND title may still publish a ghost")
    void bothGenuinelyDifferentMayGhost() throws Exception {
        seedAaronYip();
        String searcher = insertUser("auth0|searcher-4", "searcher4");

        // Branch D. This is deliberate policy: two people may share a name, and the existing
        // tiles are returned alongside so the searcher can pick the right one.
        JsonObject result = await(service.findOrCreate(
            searcher, "Aaron", "Yip", "Software Engineer", "Acme Logistics", "Canada", null, null, null));

        assertTrue(result.getBoolean("created", false),
            "an eligible searcher naming a genuinely different company AND title should get a ghost");
        assertFalse(result.getJsonArray("data").isEmpty(),
            "the existing Aaron Yip tiles must be returned alongside the new ghost");
    }

    // ── Fuzzy names may never publish ─────────────────────────────────────────

    @Test
    @DisplayName("a partial first name never publishes a ghost")
    void partialFirstNameNeverGhosts() throws Exception {
        seedAaronYip();
        assertNoGhostPublished("auth0|fz-1", "Aar", "Yip", "Software Engineer", "Acme Logistics");
    }

    @Test
    @DisplayName("a partial surname never publishes a ghost")
    void partialSurnameNeverGhosts() throws Exception {
        seedAaronYip();
        assertNoGhostPublished("auth0|fz-2", "Aaron", "Yi", "Software Engineer", "Acme Logistics");
    }

    @Test
    @DisplayName("a misspelled first name never publishes a ghost")
    void misspelledNameNeverGhosts() throws Exception {
        seedAaronYip();
        assertNoGhostPublished("auth0|fz-3", "Aron", "Yip", "Software Engineer", "Acme Logistics");
    }

    /** Rule 7: a PLAUSIBLE name match may never create a public ghost, whatever the employment. */
    private void assertNoGhostPublished(String auth0, String first, String last,
                                        String title, String company) throws Exception {
        String searcher = insertUser(auth0, auth0.replace("auth0|", ""));
        long ghostsBefore = countByStatus("ghost");

        JsonObject result = await(service.findOrCreate(
            searcher, first, last, title, company, "Canada", null, null, null));

        assertEquals(ghostsBefore, countByStatus("ghost"),
            "\"" + first + " " + last + "\" only plausibly matches Aaron Yip, so no public ghost "
            + "may be published - even though the company and title are completely different. "
            + "A half-typed name must not become a second person.");
        assertFalse(result.getJsonArray("data").isEmpty(),
            "the plausible candidate should still be shown so the searcher can recognise them");
    }

    // ── Same name, genuinely different people ────────────────────────────────

    @Test
    @DisplayName("three public people sharing a name are all returned")
    void sameNameDifferentPeopleAreAllReturned() throws Exception {
        seedManager("Aaron Yip", QUADREAL, "Controller, Property Accounting", "ghost");
        seedManager("Aaron Yip", "Northwind Trading", "Head Chef",            "ghost");
        seedManager("Aaron Yip", "Baytree Dental",    "Practice Manager",     "ghost");
        String searcher = insertUser("auth0|multi", "multi");

        JsonObject result = await(service.findOrCreate(
            searcher, "Aaron", "Yip", "Controller", QUADREAL, "Canada", null, null, null));

        assertEquals(3, result.getJsonArray("data").size(),
            "all three public Aaron Yips must be returned; sharing a name is not evidence of being "
            + "the same person, and hiding two of them is how a duplicate gets created next");
    }

    // ── Company behaviour must NOT change ────────────────────────────────────

    @Test
    @DisplayName("an unknown company is still created and is immediately selectable")
    void unknownCompanyIsStillCreated() throws Exception {
        String searcher = insertUser("auth0|co-1", "co1");

        await(service.findOrCreate(
            searcher, "Nadia", "Oyelaran", "Operations Lead", "Fernhill Dairy", "Canada", null, null, null));

        RowSet<Row> rows = await(pool.preparedQuery(
                "SELECT id, status FROM companies WHERE lower(trim(name)) = lower(trim($1))")
            .execute(Tuple.of("Fernhill Dairy")));
        assertEquals(1, rows.size(),
            "company discovery must keep working during search: an unknown employer is created "
            + "through the ordinary mechanism, whether or not a manager is published");

        JsonArray picker = await(service.suggestCompanies("Fernhill"));
        assertFalse(picker.isEmpty(),
            "a newly created company must be selectable in the picker immediately");
    }

    @Test
    @DisplayName("a different business with a similar name can still be added")
    void similarlyNamedBusinessCanStillBeAdded() throws Exception {
        seedManager("Aaron Yip", QUADREAL, "Controller, Property Accounting", "ghost");
        String searcher = insertUser("auth0|co-2", "co2");

        await(service.findOrCreate(
            searcher, "Imogen", "Clarke", "Site Manager", "QuadReal Construction", "Canada", null, null, null));

        RowSet<Row> rows = await(pool.preparedQuery(
                "SELECT id FROM companies WHERE lower(trim(name)) = lower(trim($1))")
            .execute(Tuple.of("QuadReal Construction")));
        assertEquals(1, rows.size(),
            "a genuinely different business must not be blocked merely because its name resembles "
            + "an existing company");
    }


    @Test
    @DisplayName("publishing a ghost still returns the existing same-name tiles")
    void ghostIsReturnedALONGSIDEExistingTiles() throws Exception {
        // An existing public Marcus Webb at a different employer in a different job.
        long existing = seedManager("Marcus Webb", "Globex", "Controller", "ghost");
        String searcher = insertUser("auth0|both-1", "both1");

        // Branch D: both company AND title genuinely differ, so a ghost is permitted.
        JsonObject result = await(service.findOrCreate(
            searcher, "Marcus", "Webb", "Engineer", "Initech", "Canada", null, null, null));

        assertTrue(result.getBoolean("created", false), "branch D should publish");
        JsonArray data = result.getJsonArray("data");

        /*
          The decision to publish is reached BECAUSE an existing Marcus Webb was found and judged a
          different person. Returning only the new ghost would tell the reader we hold nobody by
          that name, moments after deciding that we do - and they would pick the row we just minted
          instead of the one that already had the reviews.
        */
        assertEquals(2, data.size(),
            "both the existing Marcus Webb and the newly created one must be returned, got: "
            + data.encode());

        boolean sawExisting = false, sawNew = false;
        for (int i = 0; i < data.size(); i++) {
            Long id = data.getJsonObject(i).getLong("id");
            if (id != null && id == existing) sawExisting = true;
            else sawNew = true;
        }
        assertTrue(sawExisting, "the existing manager's tile was dropped");
        assertTrue(sawNew, "the newly created ghost was not returned");
    }

    @Test
    @DisplayName("a company id that does not match the typed company establishes nothing")
    void fabricatedCompanyIdIsNotTrusted() throws Exception {
        long globex  = seedCompany("Globex");
        long initech = seedCompany("Initech");
        // Marcus Webb is a Controller at Globex.
        seedManager("Marcus Webb", "Globex", "Controller", "ghost");
        String searcher = insertUser("auth0|spoof-1", "spoof1");

        /*
          The client sends Globex's id while typing "Initech". If that id were trusted, the search
          would classify the company as VERIFIED against the Globex employment record, conclude
          this is the same job, and suppress a legitimate new manager. Company identity gates
          publication, so an id from the request body has to be checked against what was typed.
        */
        JsonObject result = await(service.findOrCreate(
            searcher, "Marcus", "Webb", "Engineer", "Initech", "Canada", null, null, null, globex));

        assertTrue(result.getBoolean("created", false),
            "a mismatched company id must not establish VERIFIED identity and block publication");

        // And the honest selection still works.
        String s2 = insertUser("auth0|spoof-2", "spoof2");
        JsonObject honest = await(service.findOrCreate(
            s2, "Marcus", "Webb", "Controller", "Globex", "Canada", null, null, null, globex));
        assertFalse(honest.getBoolean("created", false),
            "a correct id for the typed company must still resolve to the existing manager");
    }


    @Test
    @DisplayName("the tile matching the searched company and title leads the results")
    void resultsAreRankedByRelevanceToTheSearch() throws Exception {
        // Four same-name managers. The one at the searched company/title has NO reviews; two
        // unrelated ones have many, so review count alone would bury the right answer.
        long atGlobex   = seedManager("Marcus Webb", "Globex",   "Controller", "ghost");
        long atInitech  = seedManager("Marcus Webb", "Initech",  "Engineer",   "ghost");
        long atUmbrella = seedManager("Marcus Webb", "Umbrella", "Analyst",    "ghost");
        await(pool.preparedQuery("UPDATE managers SET reviews_count = 40 WHERE id = $1")
            .execute(Tuple.of(atGlobex)));
        await(pool.preparedQuery("UPDATE managers SET reviews_count = 25 WHERE id = $1")
            .execute(Tuple.of(atUmbrella)));

        String searcher = insertUser("auth0|rank-1", "rank1");
        JsonObject result = await(service.findOrCreate(
            searcher, "Marcus", "Webb", "Engineer", "Initech", "Canada", null, null, null));

        JsonArray data = result.getJsonArray("data");
        assertFalse(data.isEmpty(), "the existing managers must be returned");
        assertEquals(atInitech, data.getJsonObject(0).getLong("id"),
            "the Marcus Webb who actually holds the searched job at the searched company must lead, "
            + "even with zero reviews against rivals holding 40 and 25. Ordering by reviews across "
            + "relevance bands is how a reader ends up reviewing the wrong person. Got: "
            + data.encode());
    }

    @Test
    @DisplayName("a newly created ghost leads, ahead of unrelated same-name managers")
    void newGhostLeadsTheResults() throws Exception {
        long other = seedManager("Marcus Webb", "Globex", "Controller", "ghost");
        await(pool.preparedQuery("UPDATE managers SET reviews_count = 99 WHERE id = $1")
            .execute(Tuple.of(other)));
        String searcher = insertUser("auth0|rank-2", "rank2");

        JsonObject result = await(service.findOrCreate(
            searcher, "Marcus", "Webb", "Engineer", "Initech", "Canada", null, null, null));

        assertTrue(result.getBoolean("created", false));
        JsonArray data = result.getJsonArray("data");
        assertEquals(2, data.size());
        assertNotEquals(other, data.getJsonObject(0).getLong("id"),
            "the new ghost holds exactly the searched company and title, so it is the best answer "
            + "to the question asked and must not sit below a stranger with 99 reviews");
        assertEquals(other, data.getJsonObject(1).getLong("id"),
            "and the existing manager must still be present");
    }

    @Test
    @DisplayName("a mismatched company id is not trusted by ghost creation either")
    void mismatchedCompanyIdIsNotTrustedDownstream() throws Exception {
        long globex = seedCompany("Globex");
        String searcher = insertUser("auth0|spoof-3", "spoof3");

        // Globex's id, but "Initech" typed. Validation rejects it for matching; the created
        // manager must also NOT be attached to Globex.
        JsonObject result = await(service.findOrCreate(
            searcher, "Priya", "Raghavan", "Engineer", "Initech", "Canada", null, null, null, globex));
        assertTrue(result.getBoolean("created", false));

        Row created = await(pool.preparedQuery("""
                SELECT m.company, m.company_id, c.name AS company_name
                  FROM managers m LEFT JOIN companies c ON c.id = m.company_id
                 WHERE m.name = 'Priya Raghavan'
                """).execute()).iterator().next();
        assertEquals("Initech", created.getString("company"),
            "the manager must belong to the company that was typed");
        assertNotEquals(globex, created.getLong("company_id"),
            "a fabricated company id must not survive matching and then be trusted by creation - "
            + "that would attach the new manager to a company nobody named");
    }


    // ── The anonymous path must behave exactly like the logged-in one ─────────

    @Test
    @DisplayName("anonymous: a former employer finds him instead of creating a duplicate")
    void anonymousFormerEmployerIsFound() throws Exception {
        long aaron = seedAaronYip();
        long before = countManagers();

        JsonObject body = new JsonObject()
            .put("name", "Aaron Yip").put("company", WARRINGTON)
            .put("title", "Controller").put("country", "Canada")
            .put("fromSearch", true);

        JsonObject result = await(service.createGhostManager(body, null, submissionFor(body)));

        /*
          The anonymous endpoint used findByNameAndCompany, where company is a hard AND - so this
          search could not see the Aaron Yip whose career history is at this very company, and
          published a second one. The two entry points are required to behave identically.
        */
        assertFalse(result.getBoolean("created", false),
            "anonymous search created a duplicate of a manager we already hold");
        assertEquals(before, countManagers(), "no new manager row may be written");
    }

    @Test
    @DisplayName("anonymous: a fuzzy name never publishes")
    void anonymousFuzzyNameNeverPublishes() throws Exception {
        seedAaronYip();
        long ghostsBefore = countByStatus("ghost");

        JsonObject body = new JsonObject()
            .put("name", "Aaron Yi").put("company", "Totally Different Co")
            .put("title", "Chef").put("country", "Canada")
            .put("fromSearch", true);

        JsonObject result = await(service.createGhostManager(body, null, submissionFor(body)));

        assertEquals(ghostsBefore, countByStatus("ghost"),
            "a half-typed name must not publish anonymously either");
        assertFalse(result.getBoolean("published", true),
            "published=false is what stops the client rendering a tile");
    }

    @Test
    @DisplayName("anonymous: an unresolved search is captured for review, deduplicated")
    void anonymousQueuesReviewAndDeduplicates() throws Exception {
        seedAaronYip();
        JsonObject body = new JsonObject()
            .put("name", "Aaron Yip").put("company", WARRINGTON)
            .put("title", "Controller").put("country", "Canada")
            .put("fromSearch", true);

        await(service.createGhostManager(body, null, submissionFor(body)));
        await(service.createGhostManager(body, null, submissionFor(body)));
        await(service.createGhostManager(body, null, submissionFor(body)));

        RowSet<Row> reqs = await(pool.query(
            "SELECT seen_count, first_name, last_name, company_text, title_text FROM manager_search_requests").execute());
        assertEquals(1, reqs.size(),
            "three identical anonymous searches must fold into ONE request, not three");
        Row r = reqs.iterator().next();
        assertEquals(3, r.getInteger("seen_count"), "the repeats are counted as evidence");
        assertEquals("Aaron", r.getString("first_name"));
        assertEquals(WARRINGTON, r.getString("company_text"), "what was typed is preserved verbatim");
    }

    @Test
    @DisplayName("the Add Manager drop-off capture is NOT changed")
    void addFormCaptureIsUntouched() throws Exception {
        // No fromSearch: this is somebody half-typing into /add. It must still create a
        // pending_approval draft and publish nothing - section 41's trap.
        JsonObject body = new JsonObject()
            .put("name", "Imogen Clarke").put("company", "Fernhill Dairy")
            .put("title", "Operations Lead").put("country", "Canada");

        JsonObject result = await(service.createGhostManager(body, null, submissionFor(body)));

        assertFalse(result.getBoolean("published", true), "the add form never publishes");
        Row row = await(pool.preparedQuery(
                "SELECT approval_status FROM managers WHERE name = $1")
            .execute(Tuple.of("Imogen Clarke"))).iterator().next();
        assertEquals("pending_approval", row.getString("approval_status"),
            "the drop-off capture must still produce a pending draft, not a ghost");

        assertEquals(0, await(pool.query("SELECT count(*) AS n FROM manager_search_requests").execute())
            .iterator().next().getLong("n"),
            "a deliberate add-form draft is not a search request and must not enter that queue");
    }

    /** A submission context for the anonymous endpoint; no declared location, as a search must not publish geography. */
    private static org.werkpages.service.SubmissionContext submissionFor(JsonObject body) {
        return org.werkpages.service.SubmissionContext.of(null, body, "203.0.113.7");
    }


    @Test
    @DisplayName("anonymous: a half-typed name still SHOWS the existing manager")
    void anonymousFuzzySearchStillShowsCandidates() throws Exception {
        long aaron = seedAaronYip();

        /*
          "Aar Yip" is the case the anonymous flow could not see. Its own lookup is a substring
          match on the full name, and "aaron yip" does not contain "aar yip", so it found nobody
          and called this endpoint. The matcher then correctly refused to create a second Aaron Yip
          - and before this fix the visitor was shown an empty page, having searched for somebody
          we hold. Declining to duplicate him is only half the job; he has to be displayed.
        */
        JsonObject body = new JsonObject()
            .put("name", "Aar Yip").put("company", "Totally Different Co")
            .put("title", "Chef").put("country", "Canada")
            .put("fromSearch", true);

        JsonObject result = await(service.createGhostManager(body, null, submissionFor(body)));

        assertFalse(result.getBoolean("published", true),
            "nothing was published, so the browser's one-time ghost allowance must not be spent");

        JsonArray candidates = result.getJsonArray("candidates");
        assertNotNull(candidates, "candidates must be returned so the visitor sees who we hold");
        assertFalse(candidates.isEmpty(), "Aaron Yip exists and must be shown");
        assertEquals(aaron, candidates.getJsonObject(0).getLong("id"));
        assertTrue(java.util.Set.of("approved", "ghost")
                      .contains(candidates.getJsonObject(0).getString("approvalStatus")),
            "every candidate tile must resolve to a profile the server will actually serve");
    }

    @Test
    @DisplayName("anonymous: several same-name managers are all offered, whatever the employer")
    void anonymousSeesEverySameNamePerson() throws Exception {
        seedManager("Aaron Yip", "QuadReal Property Group", "Controller",      "ghost");
        seedManager("Aaron Yip", "Northwind Trading",       "Head Chef",       "ghost");
        seedManager("Aaron Yip", "Baytree Dental",          "Practice Manager","ghost");

        JsonObject body = new JsonObject()
            .put("name", "Aaron Yi").put("company", "Someplace Else")
            .put("title", "Analyst").put("country", "Canada")
            .put("fromSearch", true);

        JsonObject result = await(service.createGhostManager(body, null, submissionFor(body)));
        JsonArray candidates = result.getJsonArray("candidates");

        assertNotNull(candidates);
        assertEquals(3, candidates.size(),
            "all three public Aaron Yips must be selectable, regardless of employer - the reader "
            + "decides which one is theirs, and hiding two is how the third gets duplicated");
    }


    @Test
    @DisplayName("discovery returns BOTH the exact match and the credible fuzzy one")
    void exactMatchDoesNotSuppressFuzzyCandidate() throws Exception {
        /*
          THE SUPPRESSION BUG.

          Discovery used a substring query that returned early the moment it found anything. With
          both of these on file, searching "Aron Yip" matched "Aron Yip" and exited - so "Aaron
          Yip" was never looked for, and the reader never saw a manager we hold who may well be
          the person they meant. An exact match must never suppress a credible fuzzy one.
        */
        long aron  = seedManager("Aron Yip",  "Acme Logistics",          "Controller", "ghost");
        long aaron = seedManager("Aaron Yip", "QuadReal Property Group", "Controller", "ghost");

        JsonArray found = await(service.discoverByName("Aron", "Yip", "Acme Logistics", "Controller", null, 8, 0))
            .getJsonArray("data");

        assertEquals(2, found.size(),
            "both Yips must be discoverable; got: " + found.encode());
        assertEquals(aron, found.getJsonObject(0).getLong("id"),
            "the exact name at the searched company leads");
        assertEquals(aaron, found.getJsonObject(1).getLong("id"),
            "and the plausible one follows, rather than being suppressed");
    }

    @Test
    @DisplayName("discovery finds a manager a substring search cannot")
    void discoveryFindsWhatSubstringMisses() throws Exception {
        long aaron = seedManager("Aaron Yip", "QuadReal Property Group", "Controller", "ghost");

        // "aaron yip" does not contain "aar yip", so the old substring lookup returned nothing.
        JsonArray found = await(service.discoverByName("Aar", "Yip", "Somewhere Else", "Chef", null, 8, 0))
            .getJsonArray("data");
        assertEquals(1, found.size(), "a half-typed given name must still discover him");
        assertEquals(aaron, found.getJsonObject(0).getLong("id"));
    }

    @Test
    @DisplayName("discovery creates nothing and spends nothing")
    void discoveryIsReadOnly() throws Exception {
        seedAaronYip();
        long managersBefore = countManagers();
        long companiesBefore = await(pool.query("SELECT count(*) AS n FROM companies").execute())
            .iterator().next().getLong("n");

        await(service.discoverByName("Aaron", "Yip", "A Company That Does Not Exist", "Chef", null, 8, 0));

        assertEquals(managersBefore, countManagers(), "discovery must not create a manager");
        assertEquals(companiesBefore,
            await(pool.query("SELECT count(*) AS n FROM companies").execute()).iterator().next().getLong("n"),
            "and must not create a company either - resolving one can mint it, so discovery never resolves");
        assertEquals(0, await(pool.query("SELECT count(*) AS n FROM manager_search_requests").execute())
            .iterator().next().getLong("n"), "nor queue a review; discovery decides nothing");
    }

    @Test
    @DisplayName("discovery surfaces same-name managers across every employer")
    void discoverySpansEmployers() throws Exception {
        seedManager("Aaron Yip", "QuadReal Property Group", "Controller",       "ghost");
        seedManager("Aaron Yip", "Northwind Trading",       "Head Chef",        "ghost");
        seedManager("Aaron Yip", "Baytree Dental",          "Practice Manager", "ghost");

        JsonArray found = await(service.discoverByName("Aaron", "Yip", "Northwind Trading", "Head Chef", null, 8, 0))
            .getJsonArray("data");
        assertEquals(3, found.size(), "every public Aaron Yip must be offered, whatever the employer");
        assertEquals("Northwind Trading", found.getJsonObject(0).getString("company"),
            "the one matching the searched company AND title leads");
    }


    @Test
    @DisplayName("pagination happens after ranking, and total is the real count")
    void paginationIsAfterRanking() throws Exception {
        // Twelve same-name managers; only one holds the searched company AND title.
        long target = seedManager("Aaron Yip", "Northwind Trading", "Head Chef", "ghost");
        for (int i = 0; i < 11; i++) {
            long id = seedManager("Aaron Yip", "Filler Co " + i, "Analyst", "ghost");
            await(pool.preparedQuery("UPDATE managers SET reviews_count = $2 WHERE id = $1")
                .execute(Tuple.of(id, 50 + i)));   // all far more reviewed than the right answer
        }

        JsonObject page1 = await(service.discoverByName(
            "Aaron", "Yip", "Northwind Trading", "Head Chef", null, 5, 0));

        assertEquals(12, page1.getInteger("total"),
            "total must be the real number of matching managers, not the page size");
        assertEquals(5, page1.getJsonArray("data").size());
        assertTrue(page1.getBoolean("hasMore"), "the client must be able to tell more exist");

        /*
          The one manager holding the searched company and title must be on the FIRST page, even
          though eleven rivals have more reviews. Paginating before ranking would have pushed the
          right answer to page three and the reader would have reviewed the wrong person.
        */
        assertEquals(target, page1.getJsonArray("data").getJsonObject(0).getLong("id"),
            "ranking must happen before the page is cut");

        JsonObject page3 = await(service.discoverByName(
            "Aaron", "Yip", "Northwind Trading", "Head Chef", null, 5, 10));
        assertEquals(2, page3.getJsonArray("data").size(), "the last page holds the remainder");
        assertFalse(page3.getBoolean("hasMore"), "and reports that it is the end");
    }

    @Test
    @DisplayName("discovery still creates nothing, including the company that was typed")
    void discoveryRemainsReadOnly() throws Exception {
        seedManager("Aaron Yip", "QuadReal Property Group", "Controller", "ghost");
        long companiesBefore = await(pool.query("SELECT count(*) AS n FROM companies").execute())
            .iterator().next().getLong("n");

        await(service.discoverByName("Aaron", "Yip", "Example Technologies", "Engineer", null, 8, 0));

        assertEquals(companiesBefore,
            await(pool.query("SELECT count(*) AS n FROM companies").execute()).iterator().next().getLong("n"),
            "discovery may be called while typing, so it must never mint a company. The completed "
            + "search creates it through the ordinary /api/companies path instead.");
    }


    @Test
    @DisplayName("a completed search creates the new company EVEN WHEN existing managers are found")
    void completedSearchCreatesCompanyEvenWhenManagersFound() throws Exception {
        // Aaron Yip already exists at QuadReal. The reader searches him at an employer we have
        // never heard of - the case where the response returns tiles immediately.
        seedManager("Aaron Yip", "QuadReal Property Group", "Controller", "ghost");

        JsonObject result = await(service.discoverByName(
            "Aaron", "Yip", "Example Technologies", "Engineer", null, 8, 0, true, "203.0.113.9"));

        assertFalse(result.getJsonArray("data").isEmpty(),
            "the existing Aaron Yip is still returned - the company write must not delay or "
            + "replace the answer");

        /*
          And the employer now exists. This write used to be fired from the browser and not
          awaited, so closing the tab lost it permanently. Doing it on the server means the
          completed search finishes the job whatever the browser does next.
        */
        RowSet<Row> co = await(pool.preparedQuery(
                "SELECT id, status FROM companies WHERE lower(trim(name)) = lower(trim($1))")
            .execute(Tuple.of("Example Technologies")));
        assertEquals(1, co.size(),
            "a completed search for a genuinely new employer must create it, independently of "
            + "whether a manager was found or published");

        // And it is immediately selectable, which is what "created" has to mean to be useful.
        JsonArray picker = await(service.suggestCompanies("Example Tech"));
        assertFalse(picker.isEmpty(), "the new company must be offered by the picker straight away");
    }

    @Test
    @DisplayName("discovery while typing still creates nothing")
    void typingStillCreatesNothing() throws Exception {
        seedManager("Aaron Yip", "QuadReal Property Group", "Controller", "ghost");
        long before = await(pool.query("SELECT count(*) AS n FROM companies").execute())
            .iterator().next().getLong("n");

        // submitted = false: this is the keystroke path.
        await(service.discoverByName("Aaron", "Yip", "Example Technologies", "Engineer",
                                     null, 8, 0, false, "203.0.113.9"));

        assertEquals(before,
            await(pool.query("SELECT count(*) AS n FROM companies").execute()).iterator().next().getLong("n"),
            "typing must never mint a company, or every character becomes a directory entry");
    }


    @Test
    @DisplayName("two users searching the same unknown manager at once create only ONE")
    void concurrentSearchesCreateOneManager() throws Exception {
        String u1 = insertUser("auth0|race-1", "race1");
        String u2 = insertUser("auth0|race-2", "race2");

        /*
          THE RACE THE SLOT DOES NOT COVER.

          claimAutoCreatedManagerSlot is atomic, but it is per USER: two different people each hold
          an unspent slot, so both pass the eligibility gate and both reach creation. Nothing about
          one user's slot tells us the other is mid-flight for the same manager, and the matcher
          cannot see a row that has not been committed yet.
        */
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Future<JsonObject>> results = new java.util.ArrayList<>();
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();

        for (String auth : java.util.List.of(u1, u2)) {
            Thread t = new Thread(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    JsonObject r = await(managerServiceFor().findOrCreate(
                        auth, "Marcus", "Webb", "Engineer", "Initech", "Canada", null, null, null));
                    synchronized (results) { results.add(Future.succeededFuture(r)); }
                } catch (Throwable e) { failure.compareAndSet(null, e); }
            });
            t.setDaemon(true); threads.add(t); t.start();
        }
        go.countDown();
        for (Thread t : threads) t.join(60_000);

        long created = await(pool.preparedQuery(
                "SELECT count(*) AS n FROM managers WHERE name = $1 AND approval_status IN ('approved','ghost')")
            .execute(Tuple.of("Marcus Webb"))).iterator().next().getLong("n");

        assertEquals(1, created,
            "two simultaneous searches for the same unknown manager produced " + created
            + " public rows. Each user's ghost slot is atomic on its own, but neither sees the "
            + "other, so a per-user guard cannot prevent a shared duplicate.");
    }

    @Test
    @DisplayName("concurrent identical searches produce ONE review request")
    void concurrentSearchesCreateOneRequest() throws Exception {
        seedAaronYip();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Thread> threads = new java.util.ArrayList<>();

        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < 6; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    JsonObject body = new JsonObject()
                        .put("name", "Aaron Yip").put("company", WARRINGTON)
                        .put("title", "Controller").put("country", "Canada")
                        .put("fromSearch", true);
                    await(managerServiceFor().createGhostManager(body, null, submissionFor(body)));
                } catch (Throwable e) {
                    // NOT swallowed. Six searchers all getting an error would otherwise leave
                    // exactly one request row behind and read as a pass.
                    String m = String.valueOf(e.getMessage());
                    errors.add(e.getClass().getSimpleName() + ": "
                        + m.substring(0, Math.min(140, m.length())));
                }
            });
            t.setDaemon(true); threads.add(t); t.start();
        }
        go.countDown();
        for (Thread t : threads) t.join(60_000);

        assertTrue(errors.isEmpty(), "no searcher may be shown an error: " + errors.peek());

        Row req = await(pool.query("""
                SELECT count(*) AS n, min(seen_count) AS seen
                  FROM manager_search_requests
                """).execute()).iterator().next();

        assertEquals(1, req.getLong("n"),
            "the unique index on the normalised triple must make a duplicate request impossible "
            + "even when six searches arrive together; got " + req.getLong("n"));

        /*
          And every one of the six must be COUNTED.

          The row count alone cannot tell a correct upsert apart from five lost updates - both
          leave one row. seen_count is what an admin sorts the queue by, so a lost increment
          understates demand for exactly the managers most people are looking for. It starts at 1
          on the insert and the conflict branch increments it, so six searches is six.
        */
        assertEquals(6, req.getInteger("seen"),
            "six concurrent searches must count as six; a lower number means increments were lost");
    }

    /** The shared service; concurrency tests need it from several threads. */
    private static ManagerService managerServiceFor() { return service; }


    @Test
    @DisplayName("Simultaneous searches for one unknown manager: one row, and everybody sees it")
    void concurrentGhostStress() throws Exception {
        /*
          THE BUG THIS EXISTS FOR.

          generateUniqueSlug READS the slug namespace and the INSERT then WRITES to it, with
          nothing held between. Six people searching the same unknown manager at the same instant
          all find the plain-name slug free, all build the same slug, and the UNIQUE index
          managers_slug_idx rejects every insert after the first.

          Before the fix that index was the only thing preventing a duplicate, and it did the job
          by throwing a raw PgException at whichever searcher arrived second. Asked for a manager,
          the manager now exists, shown an error. This test reproduced it on the first round.

          Two properties are asserted, and the second is the one a row count alone would miss:
          exactly ONE public manager exists, and EVERY racer was handed that same manager. A test
          that only counted rows would pass while five of six people got a 500.

          The surnames are deliberately unrelated to each other. An earlier version used Racer0,
          RacerA, RacerB..., and from round 1 onward the matcher correctly read each as a
          one-character typo of the previous round's manager - PLAUSIBLE, which rule 7 forbids
          from publishing - so no ghost was created and the race was never exercised.
        */
        String[] surnames = { "Whitfield", "Brennan", "Castellano", "Okonkwo",
                              "Lindqvist", "Marchetti", "Thornbury", "Vasquez" };
        int racers = 6;
        List<String> problems = new ArrayList<>();

        for (int round = 0; round < surnames.length; round++) {
            String last = surnames[round];
            List<String> users = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                users.add(insertUser("auth0|st-" + round + "-" + i, "st" + round + i));
            }

            CountDownLatch go = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Long> servedIds = new ConcurrentLinkedQueue<>();

            for (String auth : users) {
                Thread t = new Thread(() -> {
                    try {
                        go.await(10, TimeUnit.SECONDS);
                        JsonObject res = await(managerServiceFor().findOrCreate(
                            auth, "Marcus", last, "Engineer", "Initech", "Canada", null, null, null));
                        JsonArray data = res.getJsonArray("data", new JsonArray());
                        if (data.isEmpty()) servedIds.add(-1L);
                        else servedIds.add(data.getJsonObject(0).getLong("id"));
                    } catch (Throwable e) {
                        String m = String.valueOf(e.getMessage());
                        errors.add(e.getClass().getSimpleName() + ": "
                            + m.substring(0, Math.min(140, m.length())));
                    }
                });
                t.setDaemon(true);
                threads.add(t);
                t.start();
            }
            go.countDown();
            for (Thread t : threads) t.join(60_000);

            RowSet<Row> rows = await(pool.preparedQuery("""
                    SELECT id FROM managers
                     WHERE name = $1 AND approval_status IN ('approved','ghost')
                    """).execute(Tuple.of("Marcus " + last)));
            List<Long> publicIds = new ArrayList<>();
            rows.forEach(r -> publicIds.add(r.getLong("id")));

            if (!errors.isEmpty()) {
                problems.add(last + ": a racer was shown an error - " + errors.peek());
            }
            if (publicIds.size() != 1) {
                problems.add(last + ": " + publicIds.size() + " public rows, expected 1");
            } else {
                long winner = publicIds.get(0);
                List<Long> wrong = servedIds.stream().filter(id -> id != winner).toList();
                if (!wrong.isEmpty()) {
                    problems.add(last + ": " + wrong.size() + " of " + racers
                        + " racers were not served the published manager " + winner
                        + " (got " + wrong + ")");
                }
            }
        }

        assertTrue(problems.isEmpty(),
            "concurrent ghost creation is not safe:\n  " + String.join("\n  ", problems));
    }


    @Test
    @DisplayName("a COMPLETED search resolves the company; a read never does")
    void onlyACompletedSearchWritesTheCompany() throws Exception {
        /*
          WHY THE VERB MATTERS.

          Resolving the company creates it when we do not hold it, which is a state change. It used
          to be asked for through `GET /api/managers?...&submitted=true` - a read verb that wrote -
          so anything replaying a URL could mint company rows: a crawler, a link prefetch, a
          browser retry, somebody refreshing the page. The completed search is
          POST /api/managers/search now, and the GET handler no longer reads the flag at all.

          This pins the behaviour underneath both: the same arguments, differing only in whether
          the search was completed, must differ in exactly one way - whether the company appears.
        */
        seedAaronYip();
        final String brandNew = "Thornwick Haulage Group";

        long before = companyCount();
        await(service.discoverByName("Aaron", "Yip", brandNew, "Controller", null, 8, 0));
        assertEquals(before, companyCount(),
            "a read must leave the companies table exactly as it found it");
        assertEquals(0, companyNamed(brandNew), "and must not have created this one");

        // The same search, completed.
        await(service.discoverByName("Aaron", "Yip", brandNew, "Controller", null, 8, 0,
                                     true, "203.0.113.9"));
        assertEquals(1, companyNamed(brandNew),
            "a completed search records the employer that was typed, so the directory holds it");
    }

    private static long companyCount() throws Exception {
        return await(pool.query("SELECT count(*) AS n FROM companies").execute())
            .iterator().next().getLong("n");
    }

    private static long companyNamed(String name) throws Exception {
        return await(pool.preparedQuery("SELECT count(*) AS n FROM companies WHERE name = $1")
            .execute(Tuple.of(name))).iterator().next().getLong("n");
    }

    // ── fixtures and helpers ─────────────────────────────────────────────────

    /** The production record: live ghost at QuadReal, career history at Warrington, one review. */
    private long seedAaronYip() throws Exception {
        long id = seedManager("Aaron Yip", QUADREAL, "Controller, Property Accounting", "ghost");
        long warringtonId = seedCompany(WARRINGTON);
        insertCareer(id, QUADREAL,   seedCompany(QUADREAL),  "Controller, Property Accounting",
                     java.time.OffsetDateTime.parse("2024-01-01T00:00:00Z"), null);
        insertCareer(id, WARRINGTON, warringtonId,           "Divisional Controller",
                     java.time.OffsetDateTime.parse("2023-01-01T00:00:00Z"),
                     java.time.OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        return id;
    }

    private long seedCompany(String name) throws Exception {
        Row r = await(pool.preparedQuery("""
                INSERT INTO companies (name, status) VALUES ($1, 'ghost')
                ON CONFLICT ((LOWER(TRIM(name)))) DO UPDATE SET updated_at = now()
                RETURNING id
                """).execute(Tuple.of(name))).iterator().next();
        return r.getLong("id");
    }

    private long seedManager(String name, String company, String title, String status) throws Exception {
        long companyId = seedCompany(company);
        Row r = await(pool.preparedQuery("""
                INSERT INTO managers (name, company, company_id, title, status, approval_status,
                                      country, slug, created_at, updated_at)
                VALUES ($1, $2, $3, $4, 'active', $5, 'Canada',
                        lower(regexp_replace($1 || '-' || $2, '[^a-zA-Z0-9]+', '-', 'g')), now(), now())
                RETURNING id
                """).execute(Tuple.of(name, company, companyId, title, status))).iterator().next();
        return r.getLong("id");
    }

    private void insertCareer(long managerId, String company, long companyId, String title,
                              java.time.OffsetDateTime from, java.time.OffsetDateTime to) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO career_history (manager_id, company, company_id, title, start_date, end_date)
                VALUES ($1, $2, $3, $4, $5, $6)
                """).execute(Tuple.of(managerId, company, companyId, title, from, to)));
    }

    private String careerCompanyOf(long managerId) throws Exception {
        RowSet<Row> rows = await(pool.preparedQuery(
                "SELECT company FROM career_history WHERE manager_id = $1 AND end_date IS NOT NULL")
            .execute(Tuple.of(managerId)));
        return rows.size() == 0 ? null : rows.iterator().next().getString("company");
    }

    private long countManagers() throws Exception {
        return await(pool.query("SELECT count(*) AS n FROM managers").execute())
            .iterator().next().getLong("n");
    }

    private long countByStatus(String status) throws Exception {
        return await(pool.preparedQuery("SELECT count(*) AS n FROM managers WHERE approval_status = $1")
            .execute(Tuple.of(status))).iterator().next().getLong("n");
    }

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery("INSERT INTO users(auth0_id, username, email) VALUES ($1,$2,$3)")
            .execute(Tuple.of(auth0Id, username, username + "@example.com")));
        return auth0Id;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}

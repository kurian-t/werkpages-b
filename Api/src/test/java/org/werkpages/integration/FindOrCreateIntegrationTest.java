package org.werkpages.integration;

import io.vertx.core.CompositeFuture;
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
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.repository.AnonymousGhostSlotRepository;
import org.werkpages.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class FindOrCreateIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool           pool;
    static ManagerService service;
    static UserRepository userRepo;

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

        pool     = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));
        userRepo = new UserRepository(pool);

        ManagerRepository managerRepo = new ManagerRepository(pool);
        ReviewRepository  reviewRepo  = new ReviewRepository(pool);
        EditRepository    editRepo    = new EditRepository(pool);
        ReportRepository  reportRepo  = new ReportRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE managers, companies, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── First search, long names → ghost ─────────────────────────────────────

    /**
     * The manager handed back from a first search already carries its seeded rating.
     *
     * <p>Reported in production on the sister product: a user searched, the profile was
     * auto-created, and the tile came back with an empty space where the rating goes. The seed
     * review was in the database the whole time - reloading showed the rating - which made it
     * look random.
     *
     * <p>The cause is in this response, not in the tile. {@code createAutoApproved} returns its
     * own RETURNING row, captured before the seed review exists, so reviews_count is 0 and
     * overall_rating null on it. {@code createSeedReview} only writes to {@code reviews}; the
     * counts reach the manager via {@code recalculate}, which ran fire-and-forget while the stale
     * row was serialised and returned regardless.
     *
     * <p>This asserts on the response object specifically. Querying the database afterwards would
     * pass either way - the row does get fixed a moment later - and would miss the bug entirely.
     * Verified to fail against the un-awaited version.
     *
     * <p><b>UPDATED for V106's provenance split, and the claim is unchanged.</b> The seed is a
     * platform example, and an example contributes nothing to {@code managers.overall_rating} by
     * design, so this used to assert {@code overallRating > 0} and began failing the day
     * {@code recalculate} started excluding examples - correctly, on a payload that had then
     * nothing for the tile to draw. The bug this test exists for had come back by a different
     * route, with the rating missing for a new reason.
     *
     * <p>So the assertion now names where the number actually lives: {@code exampleRating} and
     * {@code exampleReviews}, beside a genuine {@code overallRating} that is null because the
     * manager genuinely has no rating. Both halves are asserted deliberately. The example's number
     * must be present, or the tile draws nothing; and the genuine one must stay absent, or
     * fabricated numbers are back in whatever sorts and ranks managers, which is the thing
     * {@code reviews.origin} was added to prevent.
     */
    @Test
    void findOrCreate_firstSearch_returnedManagerCarriesItsSeededRating() throws Exception {
        String auth0Id = insertUser("auth0|seeded", "seededuser");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Rated", "Person", "Engineer", "Acme Corp", "US", null, null, null));

        JsonObject manager = result.getJsonArray("data").getJsonObject(0);

        assertEquals("ghost", manager.getString("approvalStatus"));

        // The example's own number, which is what the tile draws for a manager nobody has rated.
        assertNotNull(manager.getValue("exampleRating"),
            "the tile renders whatever this response says; a null rating draws nothing");
        assertTrue(manager.getDouble("exampleRating") > 0,
            "the seeded example gives the profile a number to show, so the response must carry it");
        assertNotNull(manager.getInteger("exampleReviews"),
            "the count the tile reads must be present on the returned manager");
        assertTrue(manager.getInteger("exampleReviews") >= 1,
            "the seeded example must be counted on the manager that is returned");

        /*
          And the GENUINE figures stay empty, which is the other half of the fix rather than an
          incidental detail. This manager has not been rated by anybody. If the example's number
          reached overallRating it would also reach sorting, the Top Rated pill and every
          aggregate computed from the manager cache, which is exactly what origin exists to stop.
        */
        assertNull(manager.getValue("overallRating"),
            "an example is not a rating: the genuine figure must stay null");
        assertEquals(0, manager.getInteger("reviews"),
            "and the genuine count stays zero");
    }

    /**
     * A manager auto-created by a signed-in visitor counts toward the site-wide rate.
     *
     * <p>Reported from the admin panel: "Automatic manager creation is active - 0 in the last
     * hour" while a profile had just been auto-created by a logged-in search. The number was not
     * wrong about its own query; it was counting the wrong thing. It read
     * {@code anonymous_ghost_quota}, which only the logged-OUT path writes to. A logged-in first
     * search claims {@code users.has_auto_created_manager} instead and never touches that table,
     * so signed-in creations were invisible.
     *
     * <p>That is worse than a wrong label. {@code withinSiteWideCeiling()} is the breaker meant to
     * stop runaway automatic creation, and traffic it cannot see can never trip it - so half the
     * creation paths had no ceiling at all.
     *
     * <p>Asserted through the rate the breaker itself reads, not through the managers table, so
     * the test fails if the breaker ever goes back to looking somewhere narrower.
     */
    @Test
    void findOrCreate_loggedInGhost_countsTowardTheSiteWideRate() throws Exception {
        AnonymousGhostSlotRepository ghostSlots = new AnonymousGhostSlotRepository(pool);
        long before = await(ghostSlots.siteWideRates()).lastHour();

        String auth0Id = insertUser("auth0|ratecount", "ratecountuser");
        await(service.findOrCreate(
            auth0Id, "Counted", "Creation", "Engineer", "Acme Corp", "US", null, null, null));

        assertEquals(before + 1, await(ghostSlots.siteWideRates()).lastHour(),
            "a signed-in auto-creation is still an automatic creation - the breaker must see it");
        assertEquals(before + 1, await(ghostSlots.siteWideRates()).lastDay(),
            "and it must appear in the 24-hour window the admin banner reports");
    }

    @Test
    void findOrCreate_firstSearch_longNames_createsGhost() throws Exception {
        String auth0Id = insertUser("auth0|u1", "user1");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Alice", "Smith", "Engineer", "Acme Corp", "US", null, null, null));

        assertTrue(result.getBoolean("created"));
        JsonArray data = result.getJsonArray("data");
        assertEquals(1, data.size());
        assertEquals("ghost", data.getJsonObject(0).getString("approvalStatus"));

        boolean flagSet = ghostSlotClaimed(findUserId(auth0Id));
        assertTrue(flagSet);
    }

    // ── Second search, long names → ghost slot taken, silently creates pending ──

    @Test
    void findOrCreate_secondSearch_longNames_createsPending() throws Exception {
        String auth0Id = insertUser("auth0|u2", "user2");

        // First search — creates ghost (user sees it)
        await(service.findOrCreate(
            auth0Id, "Alice", "Smith", "Engineer", "Acme Corp", "US", null, null, null));

        // Second search (different manager) — ghost slot taken, returns empty to user but silently creates pending
        JsonObject result = await(service.findOrCreate(
            auth0Id, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));

        assertFalse(result.getBoolean("created"));
        assertEquals(0, result.getJsonArray("data").size());

        // Pending created — goes to admin queue without user knowing
        long pendingCount = await(pool.preparedQuery(
            "SELECT COUNT(*) FROM managers WHERE name ILIKE 'David Lee' AND approval_status = 'pending_approval'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1, pendingCount);

        // Confirm it is search-created (no notification path)
        long searchCreatedCount = await(pool.preparedQuery(
            "SELECT COUNT(*) FROM managers WHERE name ILIKE 'David Lee' AND search_created_by_user_id IS NOT NULL")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1, searchCreatedCount);
    }

    // ── Short company name → returns empty, no pending created, ghost slot untouched ──

    @Test
    void findOrCreate_shortCompanyName_returnsEmptyWithoutCreating() throws Exception {
        String auth0Id = insertUser("auth0|u3", "user3");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Carol", "Lee", "Engineer", "Go", "US", null, null, null));

        // Nothing returned to user — short name can't safely ghost
        assertFalse(result.getBoolean("created"));
        assertEquals(0, result.getJsonArray("data").size());

        // No pending created — user searched, not explicitly submitted
        long count = await(pool.preparedQuery("SELECT COUNT(*) FROM managers WHERE name ILIKE 'Carol Lee'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0, count);

        // Ghost slot should NOT be consumed for short-name searches
        boolean flagSet = ghostSlotClaimed(findUserId(auth0Id));
        assertFalse(flagSet);
    }

    // ── Second search with short name → pending, ghost slot still free ────────

    @Test
    void findOrCreate_shortName_doesNotConsumeGhostSlot_allowsGhostOnLaterSearch() throws Exception {
        String auth0Id = insertUser("auth0|u4", "user4");

        // Short-name search → pending queued silently (ghost slot not consumed)
        JsonObject shortResult = await(service.findOrCreate(
            auth0Id, "Dan", "Park", "Engineer", "Go", "US", null, null, null));
        assertFalse(shortResult.getBoolean("created"));
        assertEquals(0, shortResult.getJsonArray("data").size());

        // Long-name search next → should still create ghost (slot was not consumed)
        JsonObject result = await(service.findOrCreate(
            auth0Id, "Eve", "Walker", "Manager", "Microsoft", "US", null, null, null));

        assertTrue(result.getBoolean("created"));
        assertEquals("ghost", result.getJsonArray("data").getJsonObject(0).getString("approvalStatus"));
    }

    // ── Ghost slot taken: subsequent searches silently create pending ─────────

    @Test
    void findOrCreate_ghostSlotTaken_subsequentSearchesCreatePending() throws Exception {
        String auth0Id = insertUser("auth0|u5", "user5");

        // First search: creates ghost (consume slot)
        await(service.findOrCreate(
            auth0Id, "Alice", "Smith", "Engineer", "Acme Corp", "US", null, null, null));

        // Second search: different name → ghost slot taken, returns empty to user, pending created silently
        JsonObject second = await(service.findOrCreate(
            auth0Id, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));
        assertFalse(second.getBoolean("created"));
        assertEquals(0, second.getJsonArray("data").size());

        // Third search: different name again → another pending created
        JsonObject third = await(service.findOrCreate(
            auth0Id, "Carol", "Brown", "Director", "Gamma Corp", "US", null, null, null));
        assertFalse(third.getBoolean("created"));
        assertEquals(0, third.getJsonArray("data").size());

        // Both searches resulted in pending_approval managers in the admin queue
        long pendingCount = await(pool.preparedQuery(
            "SELECT COUNT(*) FROM managers WHERE approval_status = 'pending_approval'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(2, pendingCount);
    }

    // ── Ghost slot taken for user1, user2 can still create ghost for same manager ──

    @Test
    void findOrCreate_user2CanCreateGhostWhenUser1SlotWasExhausted() throws Exception {
        String auth0Id1 = insertUser("auth0|u6", "user6");
        String auth0Id2 = insertUser("auth0|u7", "user7");

        // user1 creates ghost (Alice at Acme) — consumes their slot
        await(service.findOrCreate(
            auth0Id1, "Alice", "Smith", "Engineer", "Acme Corp", "US", null, null, null));

        // user1's second search (David at Beta) — slot taken, returns empty, pending created silently
        JsonObject u1Result = await(service.findOrCreate(
            auth0Id1, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));
        assertFalse(u1Result.getBoolean("created"));
        assertEquals(0, u1Result.getJsonArray("data").size());

        /*
          POLICY CHANGED 2026-10-14. This used to assert that user2 DOES get a ghost.

          Pending rows were visible only to the user whose search created them, so a second person
          searching the same manager could not see the first submission and published over it. That
          is how one manager accumulated ten rows at a single company. Existence detection now spans
          non-public rows: user1's pending David Lee at Beta Corp is an EQUIVALENT submission - same
          name, same verified company - so user2's search is captured for review instead of
          publishing a duplicate of a record already awaiting a decision.

          Note what is NOT blocked. A David Lee at a different employer is a different submission
          and may still be published, because distinct people share names. Nor does an abandoned
          add-form draft block anything: this row blocks because a SEARCH created it
          (search_created_by_user_id is set), which is somebody asserting the manager exists. A
          half-typed form is not that, and treating it as one makes the manager unfindable - see
          DropOffDraftIntegrationTest.
        */
        JsonObject u2Result = await(service.findOrCreate(
            auth0Id2, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));
        assertFalse(u2Result.getBoolean("created", false),
            "an equivalent submission is already queued; publishing now would duplicate it");

        // user1's second search created a pending; user2 created a ghost
        long pendingCount = await(pool.preparedQuery(
            "SELECT COUNT(*) FROM managers WHERE name ILIKE 'David Lee' AND approval_status = 'pending_approval'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1, pendingCount);
    }

    // ── findPendingByUser excludes search-created managers ───────────────────

    @Test
    void findPendingByUser_excludesSearchCreatedManagers() throws Exception {
        String auth0Id = insertUser("auth0|u8", "user8");
        UUID userId = findUserId(auth0Id);

        // Create a regular pending manager via direct DB insert (user explicitly submitted)
        await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,status,approval_status,overall_rating,reviews_count,category_averages,submitted_by) " +
            "VALUES ('Submitted Manager','Corp','Title','active','pending_approval',0,0,'{}',$1)")
            .execute(Tuple.of(userId)));

        // Create a search-created pending manager via DB insert with search_created_by_user_id
        await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,status,approval_status,overall_rating,reviews_count,category_averages,submitted_by,search_created_by_user_id) " +
            "VALUES ('Search Manager','Corp','Title','active','pending_approval',0,0,'{}',$1,$1)")
            .execute(Tuple.of(userId)));

        ManagerRepository managerRepo = new ManagerRepository(pool);
        var rows = await(managerRepo.findPendingByUser(userId));
        assertEquals(1, rows.size());
        assertEquals("Submitted Manager", rows.iterator().next().getString("name"));
    }

    // ── Concurrent searches by same user: only one ghost, loser gets empty ───

    @Test
    void findOrCreate_concurrentSearches_onlyOneGhostCreated() throws Exception {
        String auth0Id = insertUser("auth0|u9", "user9");

        // Fire two simultaneous searches for different manager names
        Future<JsonObject> f1 = service.findOrCreate(
            auth0Id, "Anna", "Brown", "Engineer", "Acme Corp", "US", null, null, null);
        Future<JsonObject> f2 = service.findOrCreate(
            auth0Id, "Ben", "Green", "Manager", "Beta Corp", "US", null, null, null);

        List<JsonObject> results = await(Future.all(f1, f2)
            .map(cf -> List.of((JsonObject) cf.resultAt(0), (JsonObject) cf.resultAt(1))));

        // Exactly one ghost (winner) and one pending (loser silently queued for admin review)
        long ghostsInDB = await(pool.query("SELECT COUNT(*) FROM managers WHERE approval_status = 'ghost'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        long pendingInDB = await(pool.query("SELECT COUNT(*) FROM managers WHERE approval_status = 'pending_approval'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));

        assertEquals(1, ghostsInDB, "Expected exactly 1 ghost; race condition may have created 2");
        assertEquals(1, pendingInDB, "Loser's search should silently create a pending_approval");

        // Only the winner returns data (the ghost); the loser's response is empty
        long dataWithResults = results.stream().filter(r -> r.getJsonArray("data").size() > 0).count();
        assertEquals(1, dataWithResults, "Only the ghost response should have data");

        long totalManagers = await(pool.query("SELECT COUNT(*) FROM managers")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(2, totalManagers);
    }

    // ── Vowel check tests ─────────────────────────────────────────────────────

    @Test
    void findOrCreate_noVowelFirstName_3chars_createsPending_flagNotSet() throws Exception {
        // "Lxm" has 3 chars and no vowels → should go to pending, slot stays unclaimed
        String auth0Id = insertUser("auth0|v1", "voweluser1");
        UUID userId = findUserId(auth0Id);

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Lxm", "Smith", "Engineer", "Acme Corp", "CA", null, null, null));

        assertFalse(result.getBoolean("created"));
        assertEquals(0, result.getJsonArray("data").size());

        // Flag must NOT be set — user can retry
        assertFalse(ghostSlotClaimed(userId));

        // Manager should be in pending_approval, not ghost
        long pendingCount = await(pool.preparedQuery(
            "SELECT COUNT(*) FROM managers WHERE name ILIKE 'Lxm Smith' AND approval_status = 'pending_approval'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1, pendingCount);

        long ghostCount = await(pool.preparedQuery(
            "SELECT COUNT(*) FROM managers WHERE name ILIKE 'Lxm Smith' AND approval_status = 'ghost'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0, ghostCount);
    }

    @Test
    void findOrCreate_exactlyTwoCharsNoVowel_createsGhost_flagSet() throws Exception {
        // "DJ" has exactly 2 chars and no vowels → exception, should ghost normally
        String auth0Id = insertUser("auth0|v2", "voweluser2");
        UUID userId = findUserId(auth0Id);

        JsonObject result = await(service.findOrCreate(
            auth0Id, "DJ", "Johnson", "Director", "Big Corp", "CA", null, null, null));

        assertTrue(result.getBoolean("created"));
        assertEquals(1, result.getJsonArray("data").size());
        assertEquals("ghost", result.getJsonArray("data").getJsonObject(0).getString("approvalStatus"));
        assertTrue(ghostSlotClaimed(userId));
    }

    @Test
    void findOrCreate_noVowelThenVowel_secondSearchCreatesGhost() throws Exception {
        // First search with no-vowel name → pending, flag stays unset
        // Second search with valid name → ghost created, flag set
        String auth0Id = insertUser("auth0|v3", "voweluser3");
        UUID userId = findUserId(auth0Id);

        JsonObject first = await(service.findOrCreate(
            auth0Id, "Lxmb", "Brown", "Analyst", "Corp One", "CA", null, null, null));

        assertFalse(first.getBoolean("created"));
        assertFalse(ghostSlotClaimed(userId));

        // Now search with a valid name at a different company
        JsonObject second = await(service.findOrCreate(
            auth0Id, "Alice", "Brown", "Analyst", "Corp Two", "CA", null, null, null));

        assertTrue(second.getBoolean("created"));
        assertEquals(1, second.getJsonArray("data").size());
        assertEquals("ghost", second.getJsonArray("data").getJsonObject(0).getString("approvalStatus"));
        assertTrue(ghostSlotClaimed(userId));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────


    // ── Eligibility is the search opportunity, not a lifetime contribution flag ──

    /**
     * Somebody who has already rated a manager can still publish a ghost from a search.
     *
     * <p><b>This is the rule the documented design states and the code briefly stopped following.</b>
     * {@code reference/features/manager-search-flow.md} section 9: "logged in, slot unspent, name
     * ordinary -> ghost created, tile, profile works", and {@code patterns/ghost-pattern.md}
     * reduces the decision to the same thing - a listed name goes to
     * {@code createSearchPending}, an ordinary one goes to {@code claimAutoCreatedManagerSlot}.
     * Neither document mentions contribution.
     *
     * <p>A guard added on 2026-11-14 refused a contributor outright, on the reasoning that an
     * auto-created profile carried only an unlabelled example and would be shown behind a gate the
     * reader had already passed. It changed the rule without updating either document or either of
     * the two tests that assert it, and left them failing. Both halves of its reasoning have since
     * stopped being true: the example carries its own rating and is labelled as a demonstration.
     *
     * <p>The effect of the guard was the opposite of its intent. The people who had contributed -
     * the most engaged users in the product - became the only ones who could not publish a profile
     * from a search, and got a silent pending row and no tile, which reads as "no such manager" for
     * a manager the product would happily have created for a stranger.
     */
    @Test
    void aContributorWithAnUnspentOpportunityStillCreatesAGhost() throws Exception {
        String auth0Id = insertUser("auth0|contrib-eligible", "contribeligible");
        makeContributor(auth0Id, "Prior Manager", "PriorCorp");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Patricia", "Chen", "Manager", "NonExistentCo999", "US", null, null, null));

        assertTrue(result.getBoolean("created"),
            "a previous rating elsewhere must not make this search ineligible");
        assertEquals(1, result.getJsonArray("data").size());
        assertEquals("ghost", result.getJsonArray("data").getJsonObject(0).getString("approvalStatus"));
        assertTrue(ghostSlotClaimed(findUserId(auth0Id)),
            "and the opportunity is spent, exactly as it is for anybody else");
    }

    /**
     * The ghost a contributor creates carries its example rating, and no paywall stands in the way.
     *
     * <p>Three claims, because the guard's stated reason was that this combination did not work.
     * The example's number is on the payload, so the tile draws something; the GENUINE figures stay
     * empty, so the example is not counted as an opinion; and {@code hasContributed} is true, so
     * the reader is past the contribution gate and sees the labelled example rather than a blur.
     * There is no second charge to make: the gate is passed once, by rating somebody, which this
     * person has already done.
     */
    @Test
    void theGhostAContributorCreatesCarriesALabelledExampleAndNoPaywall() throws Exception {
        String auth0Id = insertUser("auth0|contrib-sees", "contribsees");
        makeContributor(auth0Id, "Earlier Boss", "EarlierCorp");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Rowan", "Ashby", "Operations Lead", "Thornbury Freight", "US", null, null, null));
        JsonObject manager = result.getJsonArray("data").getJsonObject(0);

        assertTrue(result.getBoolean("hasContributed"),
            "they are past the contribution gate, so the rating is theirs to see unblurred");
        assertNotNull(manager.getValue("exampleRating"),
            "the example's own number travels with the manager, or the tile draws nothing");
        assertTrue(manager.getDouble("exampleRating") > 0);
        assertNull(manager.getValue("overallRating"),
            "and it is not a rating: the genuine figure stays null");
        assertEquals(0, manager.getInteger("reviews"), "nor is it counted");
    }

    /**
     * The opportunity is still once ever, for a contributor too.
     *
     * <p>Removing the contribution guard must not remove the rule that actually governs this. A
     * second search by the same person gets a silent pending row and no tile, which is what
     * section 9's "slot spent" row says and is the behaviour the product depends on to stop one
     * account minting profiles indefinitely.
     */
    @Test
    void aContributorsSecondSearchGetsNoTileOnceTheOpportunityIsSpent() throws Exception {
        String auth0Id = insertUser("auth0|contrib-spent", "contribspent");
        makeContributor(auth0Id, "First Boss", "FirstCorp");

        await(service.findOrCreate(
            auth0Id, "Imogen", "Fairweather", "Analyst", "Halloway Group", "US", null, null, null));

        JsonObject second = await(service.findOrCreate(
            auth0Id, "Desmond", "Ortega", "Planner", "Calder Freight", "US", null, null, null));

        assertFalse(second.getBoolean("created"), "the opportunity was spent on the first search");
        assertEquals(0, second.getJsonArray("data").size(), "and no tile is rendered");

        long pending = await(pool.preparedQuery(
                "SELECT COUNT(*) FROM managers WHERE name ILIKE 'Desmond Ortega' "
              + "AND approval_status = 'pending_approval'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1, pending, "it is queued for an admin instead, silently");
    }

    /**
     * Searching the same person twice returns the row the first search made.
     *
     * <p>Deduplication, asserted for a contributor specifically, because that is the path this
     * change opened. A second ghost for one person is the failure the slot and the name matcher
     * exist to prevent, and it would be invisible on the tile: two identical cards, and whichever
     * one somebody rates is a coin toss.
     */
    @Test
    void aContributorSearchingTheSamePersonTwiceGetsOneManager() throws Exception {
        String auth0Id = insertUser("auth0|contrib-dedup", "contribdedup");
        makeContributor(auth0Id, "Old Boss", "OldCorp");

        JsonObject first = await(service.findOrCreate(
            auth0Id, "Cressida", "Vance", "Director", "Marlowe Shipping", "US", null, null, null));
        long firstId = first.getJsonArray("data").getJsonObject(0).getLong("id");

        JsonObject again = await(service.findOrCreate(
            auth0Id, "Cressida", "Vance", "Director", "Marlowe Shipping", "US", null, null, null));

        assertFalse(again.getBoolean("created"), "nothing is created the second time");
        assertEquals(1, again.getJsonArray("data").size());
        assertEquals(firstId, (long) again.getJsonArray("data").getJsonObject(0).getLong("id"),
            "the same manager comes back, not a second one");

        long rows = await(pool.preparedQuery(
                "SELECT COUNT(*) FROM managers WHERE name ILIKE 'Cressida Vance'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1, rows, "one person, one row");
    }

    /** Gives this user a rating on some other manager, so hasContributed is true. */
    private void makeContributor(String auth0Id, String managerName, String company)
            throws Exception {
        long companyId = await(pool.preparedQuery(
                "INSERT INTO companies(name,status,slug) VALUES ($1,'ghost',$2) RETURNING id")
            .execute(Tuple.of(company, company.toLowerCase()))
            .map(rs -> rs.iterator().next().getLong("id")));
        long managerId = await(pool.preparedQuery(
                "INSERT INTO managers(name, company, company_id, title, image, status, "
              + "approval_status, overall_rating, reviews_count, category_averages, slug) "
              + "VALUES ($1,$2,$3,'Manager','img','active','approved',4.0,1,'{}',$4) RETURNING id")
            .execute(Tuple.of(managerName, company, companyId,
                              managerName.toLowerCase().replaceAll("[^a-z0-9]+", "-")))
            .map(rs -> rs.iterator().next().getLong("id")));
        await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, user_id, author, overall_rating,
                                     communication_style, perceived_approachability,
                                     perceived_clarity_of_expectations, feedback_style,
                                     perceived_supportiveness, decision_making_style,
                                     organization_and_planning_style, delegation_style,
                                     perceived_professional_demeanor, overall_working_experience,
                                     manager_company, manager_title, worked_from, weight)
                VALUES ($1, (SELECT id FROM users WHERE auth0_id = $2), 'PriorAuthor', 4.0,
                        4,4,4,4,4,4,4,4,4,4, $3, 'Manager', CURRENT_DATE - 100, FALSE)
                """).execute(Tuple.of(managerId, auth0Id, company)).mapEmpty());
    }

    private boolean ghostSlotClaimed(UUID userId) throws Exception {
        return await(pool.preparedQuery("SELECT has_auto_created_manager FROM users WHERE id = $1")
            .execute(Tuple.of(userId))
            .map(rs -> rs.iterator().next().getBoolean("has_auto_created_manager")));
    }


    // ── Company identity, and names that nearly match ─────────────────────────

    /*
      Reported from the running site: somebody searched "Daniel Pa" at
      "Revvity Chemagen Technologie" and got nothing, while Daniel Perovic was on file at
      "Revvity" - the same employer under the name its subsidiary is recorded as.

      Two independent failures produced that, and both are covered here.

        1. The company half matched on TEXT. "Revvity" does not contain
           "Revvity Chemagen Technologie", so a manager at the child was invisible to a search
           of the parent no matter how the name was spelled.

        2. The name half accepted only an exact, case-insensitive match on the whole string, which
           made the search that preceded it decorative - anything it returned was discarded unless
           the reader had typed the manager's name character for character.

      The cost was not merely an empty result. A logged-in searcher who matches nothing goes on to
      CREATE, so that search minted a second Daniel at the same employer.
    */

    @Test
    void searchingAParentSurfacesAManagerAtItsChild() throws Exception {
        String auth0Id = insertUser("auth0|hier-1", "hier1");
        long parent = insertCompanyRow("ParentCo Holdings", "parentco-holdings");
        long child  = insertCompanyRow("ChildCo Labs", "childco-labs");
        relate(child, parent);
        insertManagerAt(child, "Daniel Perovic", "Engineering Manager");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Daniel", "Perovic", "Engineering Manager", "ParentCo Holdings",
            "CA", null, null, null, parent));

        assertFalse(result.getBoolean("created"), "he already exists - nothing should be created");
        JsonArray data = result.getJsonArray("data");
        assertEquals(1, data.size(), "the child's manager is in the parent's scope");
        assertEquals("Daniel Perovic", data.getJsonObject(0).getString("name"));
    }

    @Test
    void searchingASiblingDoesNotSurfaceTheOtherChildsManager() throws Exception {
        /*
          The test that proves we traverse DOWNWARD from the company that was selected rather than
          treating the whole connected tree as one pool. Daniel works at ChildCo; somebody
          searching SiblingCo must not find him, and must not be told he is theirs.
        */
        String auth0Id = insertUser("auth0|hier-2", "hier2");
        long parent  = insertCompanyRow("ParentCo Holdings", "parentco-holdings");
        long child   = insertCompanyRow("ChildCo Labs", "childco-labs");
        long sibling = insertCompanyRow("SiblingCo Works", "siblingco-works");
        relate(child, parent);
        relate(sibling, parent);
        insertManagerAt(child, "Daniel Perovic", "Engineering Manager");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Daniel", "Perovic", "Engineering Manager", "SiblingCo Works",
            "CA", null, null, null, sibling));

        /*
          POLICY CHANGED 2026-10-14. This assertion used to be the opposite.

          It asserted that Daniel must NOT appear, because matching was scoped to the selected
          company and its descendants. That scoping is exactly what let a manager be duplicated:
          somebody at a company the search did not traverse was invisible, so the search created a
          second row for a person already on file. Under the name-first policy, company never
          filters discovery - it classifies and ranks.

          The original worry was that a sibling's manager would be presented as if he worked here.
          That is still guarded, by showing his real employer on the tile rather than by hiding
          him: the reader sees Daniel Perovic at ChildCo Labs and can tell he is not theirs.
        */
        JsonArray data = result.getJsonArray("data");
        assertFalse(data.isEmpty(), "Daniel Perovic exists and must be surfaced by name");
        assertEquals("Daniel Perovic", data.getJsonObject(0).getString("name"));
        assertEquals("ChildCo Labs", data.getJsonObject(0).getString("company"),
            "his ACTUAL employer must be shown, so searching at SiblingCo cannot imply he works there");
        assertFalse(result.getBoolean("created", false),
            "and above all, no second Daniel Perovic is minted");
    }

    @Test
    void aPartialSurnameSurfacesTheManagerRatherThanCreatingASecondOne() throws Exception {
        /*
          The reported case, in miniature. "Daniel Pa" is not a prefix of "Perovic" - it is two
          letters that merely share an initial - so this is the threshold the design deliberately
          tolerates: surface a candidate, let the reader decide, and above all do not create.
        */
        String auth0Id = insertUser("auth0|hier-3", "hier3");
        long parent = insertCompanyRow("ParentCo Holdings", "parentco-holdings");
        long child  = insertCompanyRow("ChildCo Labs", "childco-labs");
        relate(child, parent);
        insertManagerAt(child, "Daniel Perovic", "Engineering Manager");
        long before = managerCount();

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Daniel", "Pa", "Engineering Manager", "ParentCo Holdings",
            "CA", null, null, null, parent));

        assertFalse(result.getBoolean("created"));
        assertTrue(result.getBoolean("candidates", false),
            "surfaced as a candidate, not asserted to be the same person");
        assertEquals("Daniel Perovic",
            result.getJsonArray("data").getJsonObject(0).getString("name"));
        assertEquals(before, managerCount(), "and no second Daniel was minted");
        assertFalse(ghostSlotClaimed(findUserId(auth0Id)),
            "showing somebody who already exists must not spend the one-time ghost slot");
    }

    @Test
    void aDifferentFirstNameIsNotACandidate() throws Exception {
        // The rule is anchored on the first name. Without that anchor a two-letter surname
        // fragment would drag in every manager at the company.
        String auth0Id = insertUser("auth0|hier-4", "hier4");
        long parent = insertCompanyRow("ParentCo Holdings", "parentco-holdings");
        long child  = insertCompanyRow("ChildCo Labs", "childco-labs");
        relate(child, parent);
        insertManagerAt(child, "Daniel Perovic", "Engineering Manager");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Xavier", "Pa", "Engineering Manager", "ParentCo Holdings",
            "CA", null, null, null, parent));

        for (int i = 0; i < result.getJsonArray("data").size(); i++) {
            assertNotEquals("Daniel Perovic",
                result.getJsonArray("data").getJsonObject(i).getString("name"));
        }
    }

    @Test
    void aGenuinelyNewManagerStillBecomesAGhost() throws Exception {
        /*
          The behaviour the candidate branch must not have cost anybody: fall through every match
          with an unspent slot and the manager is still created, live and clickable. This is the
          invariant in CLAUDE.md section 15 and the corpus's ghost flow.
        */
        String auth0Id = insertUser("auth0|hier-5", "hier5");
        long parent = insertCompanyRow("ParentCo Holdings", "parentco-holdings");
        long child  = insertCompanyRow("ChildCo Labs", "childco-labs");
        relate(child, parent);
        insertManagerAt(child, "Daniel Perovic", "Engineering Manager");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Marguerite", "Ashworth", "Director", "ParentCo Holdings",
            "CA", null, null, null, parent));

        assertTrue(result.getBoolean("created"), "nobody close by, so it is created");
        assertEquals("ghost", result.getJsonArray("data").getJsonObject(0).getString("approvalStatus"));
        assertTrue(ghostSlotClaimed(findUserId(auth0Id)), "and that one does spend the slot");
    }

    // ── helpers for the hierarchy cases ───────────────────────────────────────

    private long insertCompanyRow(String name, String slug) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies(name, slug, status) VALUES ($1,$2,'approved') RETURNING id")
            .execute(Tuple.of(name, slug))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private void relate(long childId, long parentId) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO company_relationships(child_company_id, parent_company_id, relationship_type) "
                + "VALUES ($1,$2,'SUBSIDIARY_OF')")
            .execute(Tuple.of(childId, parentId)).mapEmpty());
    }

    private void insertManagerAt(long companyId, String name, String title) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO managers(name, title, company, company_id, status, approval_status, slug)
                SELECT $1, $2, c.name, c.id, 'active', 'approved',
                       lower(regexp_replace($1, '[^a-zA-Z0-9]+', '-', 'g')) || '-' || c.id
                FROM companies c WHERE c.id = $3
                """)
            .execute(Tuple.of(name, title, companyId)).mapEmpty());
    }

    private long managerCount() throws Exception {
        return await(pool.query("SELECT COUNT(*) AS c FROM managers")
            .execute().map(rs -> rs.iterator().next().getLong("c")));
    }

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) " +
            "VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User", "user")));
        return auth0Id;
    }

    private UUID findUserId(String auth0Id) throws Exception {
        return await(pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}

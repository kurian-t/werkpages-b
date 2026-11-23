package org.werkpages.integration;

import io.vertx.core.Future;
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

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PHASE 2 of the manager-identity redesign: the write paths this application already has, run
 * against the schema it now owns.
 *
 * <h2>What this asks that the migration tests do not</h2>
 *
 * <p>{@link Phase2FreshInstallIntegrationTest} proves the five migrations produce the specified
 * schema and {@link Phase2UpgradeIntegrationTest} proves existing rows survive them. Neither asks
 * the question that decides whether phase 2 can be deployed at all: does the CODE still work.
 *
 * <p>Phase 2 adds nine constraints, two composite foreign keys, seven constraint triggers and a
 * NOT NULL to three tables this application writes on its busiest paths, and it names none of the
 * new columns anywhere. The defaults have to make a pre-cutover write valid, or submitting a
 * rating starts failing on a constraint about a feature nobody has built yet.
 *
 * <p>Its twin in the RateMyManagers repository asks the same of that application, which matters
 * more there: it never runs these migrations and reads whatever schema this one has produced.
 *
 * <p>Everything here goes through the real services and repositories rather than hand-written SQL.
 * A test that wrote its own INSERT would prove the schema accepts a statement somebody composed
 * while looking at the schema, which is not the question.
 *
 * <h2>The one application change phase 2 required</h2>
 *
 * <p>V99 makes {@code career_history.company_id} NOT NULL. The admin edit-approval path used to
 * pass whatever id it happened to have, including none, so
 * {@code AdminService.companyIdForCareerEntry} now resolves one first. That is the lockstep half of
 * the migration, and {@link #aCareerEntryWithNoCompanyIsRefusedWhichIsWhyTheAdminPathResolvesOne}
 * holds down the reason it exists.
 */
@Testcontainers
class Phase2SchemaCompatibilityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_phase2_compat")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static ManagerRepository managerRepo;
    static CompanyRepository companyRepo;
    static ReviewRepository  reviewRepo;
    static ManagerService    service;

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

        managerRepo = new ManagerRepository(pool);
        companyRepo = new CompanyRepository(pool);
        reviewRepo  = new ReviewRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, new UserRepository(pool),
            new EditRepository(pool), new ReportRepository(pool), pool);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void theSchemaThisTestRunsAgainstReallyIsThePhase2One() throws Exception {
        // A guard, not a tautology. If the migration location ever silently resolved to the frozen
        // V43 folder, every other assertion below would pass for the wrong reason: they would be
        // proving that today's code works against today's schema, which nobody doubted.
        assertTrue(tableExists("role_claims"), "fixture: V101 must have run");
        assertTrue(tableExists("role_conflicts"), "fixture: V103 must have run");
        assertEquals("NO", await(pool.query("""
                SELECT is_nullable FROM information_schema.columns
                 WHERE table_name = 'career_history' AND column_name = 'company_id'
                """).execute().map(rs -> rs.iterator().next().getString("is_nullable"))),
            "fixture: V99 must have made company_id NOT NULL, which is the constraint this "
            + "application has to satisfy without knowing why");
    }

    @Test
    void awholeAnonymousSubmissionStillLandsAsItDidBefore() throws Exception {
        // The flagship path: one request creates a company, a manager, a career entry and a rating
        // in one transaction. It now passes through nine new CHECKs, two composite foreign keys and
        // a deferred constraint trigger, and it must be completely unaffected by all of them.
        Row created = await(service.createManager(
            user("auth0|phase2-anon"), submission("Nadia Okonjo", "Brightwater Labs", "Team Lead"),
            null));
        assertEquals("Nadia Okonjo", created.getString("name"));

        Row rating = await(pool.preparedQuery("""
                SELECT r.* FROM reviews r JOIN managers m ON m.id = r.manager_id
                 WHERE m.name = $1
                """).execute(Tuple.of("Nadia Okonjo")).map(rs -> rs.iterator().next()));

        assertEquals("legacy", rating.getString("role_basis"),
            "a rating written by the pre-cutover path is legacy provenance, and the column default "
            + "has to say so or reviews_basis_matches_claim refuses the insert outright");
        /*
          'inferred', not 'none', since V113.

          attribution_basis defaulted to 'none' and nothing in this path ever set it, so every
          rating the website wrote counted toward its manager and toward no company - which is
          what made a company profile average whichever subset V102's one-off backfill had
          reached. V113 attributes a legacy rating as it is written, by the employment evidence
          V102 used: this submission creates the career entry at the company in the same
          transaction, so the manager has exactly one employer and that settles it.

          Nothing else about the pre-cutover shape moved. The rating is still legacy, still
          carries no claim, and is still live.
        */
        assertEquals("inferred", rating.getString("attribution_basis"));
        assertNotNull(rating.getLong("attributed_company_id"),
            "an inferred basis that names no company would assert an employer is known while "
            + "counting toward nothing, which reviews_inferred_names_a_company refuses outright");
        assertFalse(rating.getBoolean("role_required"));
        assertNull(rating.getLong("claim_id"), "nothing is inferred into a claim");
        assertEquals("live", rating.getString("disposition"),
            "and the five publication triggers let it through: rating_role_is_publishable answers "
            + "TRUE on its first branch for a legacy rating, without reading another table");
        assertEquals("indexed", rating.getString("legacy_dedup_state"));
    }

    @Test
    void theCareerEntryThatSubmissionWritesCarriesItsCompanyAndIsAcceptedHistory() throws Exception {
        await(service.createManager(user("auth0|phase2-career"),
            submission("Tomas Reinholt", "Kestrel Freight", "Depot Manager"), null));

        Row role = await(pool.preparedQuery("""
                SELECT ch.* FROM career_history ch JOIN managers m ON m.id = ch.manager_id
                 WHERE m.name = $1
                """).execute(Tuple.of("Tomas Reinholt")).map(rs -> rs.iterator().next()));

        assertNotNull(role.getLong("company_id"),
            "this path already resolved a company through findOrCreate before inserting, which is "
            + "why V99's NOT NULL does not disturb it");
        assertEquals("accepted", role.getString("state"), "the default describes existing history");
        assertEquals("import", role.getString("source"));
        assertFalse(role.getBoolean("period_known"),
            "and period_known stays FALSE, which keeps every row this code writes outside "
            + "career_unique_known_employment. That is deliberate: the index guards canonical "
            + "roles, and nothing writes one until phase 4");
        assertEquals("depot manager", role.getString("title_key"),
            "the generated key is computed for rows this application writes without knowing it "
            + "exists, which is the whole point of putting it in the database");
        assertTrue(role.getBoolean("rateable"));
    }

    @Test
    void aCareerEntryWithNoCompanyIsRefusedWhichIsWhyTheAdminPathResolvesOne() throws Exception {
        long managerId = await(service.createManager(user("auth0|phase2-refuse"),
            submission("Ingrid Salveson", "Northwind Mills", "Shift Lead"), null)).getLong("id");

        // The repository statement is unchanged. What changed is that passing null here is now a
        // constraint violation rather than a role with no employer, so every caller must resolve
        // one first. If this ever stops throwing, the NOT NULL has been reverted and the whole
        // employment-identity chain is resting on nothing.
        Exception refused = assertThrows(Exception.class, () ->
            await(managerRepo.insertCareerEntry(managerId, "Northwind Mills", "Night Lead",
                OffsetDateTime.parse("2023-01-01T00:00:00Z"), null, null)));
        assertTrue(String.valueOf(refused.getMessage()).contains("company_id"),
            "and the refusal names the column, so the cause is findable. Got: "
            + refused.getMessage());

        // With one, the same statement is unaffected.
        long companyId = await(companyRepo.findOrCreate("Northwind Mills", null, null))
            .getLong("id");
        await(managerRepo.insertCareerEntry(managerId, "Northwind Mills", "Night Lead",
            OffsetDateTime.parse("2023-01-01T00:00:00Z"), null, companyId));
    }

    @Test
    void theGhostCaptureFromPhase1IsUndisturbed() throws Exception {
        // Phase 1 sits underneath phase 2 and the two have to coexist: the capture ledger is read
        // by a path that now also passes through every new reviews and career_history constraint.
        UUID token = UUID.randomUUID();
        JsonObject first = await(service.createGhostManager(new JsonObject()
            .put("name", "Halvard Brenn").put("company", "Tidewater Group")
            .put("title", "Operations Lead").put("country", "US")
            .put("captureToken", token.toString()), null));
        assertTrue(first.getBoolean("created"), "the first capture creates the draft");
        assertFalse(first.getBoolean("published"),
            "and it is a pending draft, not a tile: CLAUDE.md section 41");

        JsonObject second = await(service.createGhostManager(new JsonObject()
            .put("name", "Halvard Brenn").put("company", "Tidewater Group Holdings")
            .put("title", "Operations Lead").put("country", "US")
            .put("captureToken", token.toString()), null));
        assertFalse(second.getBoolean("created"), "the same token produces no second manager");
        assertEquals(first.getLong("id"), second.getLong("id"));
    }

    @Test
    void aRatingCanStillBeEditedAndSoftDeleted() throws Exception {
        // Every UPDATE of disposition or deleted_at now fires reviews_live_is_resolved at commit.
        // For a legacy rating it must be a no-op, which is the difference between a phase that is
        // inert and one that quietly blocks moderation.
        await(service.createManager(user("auth0|phase2-edit"),
            submission("Marguerite Vance", "Ashgrove Health", "Ward Manager"), null));
        UUID id = await(pool.preparedQuery("""
                SELECT r.id FROM reviews r JOIN managers m ON m.id = r.manager_id
                 WHERE m.name = $1
                """).execute(Tuple.of("Marguerite Vance"))
            .map(rs -> rs.iterator().next().getUUID("id")));

        await(pool.preparedQuery("UPDATE reviews SET text = $2 WHERE id = $1")
            .execute(Tuple.of(id, "edited")).mapEmpty());
        await(pool.preparedQuery("""
                UPDATE reviews SET disposition = 'held', gate_eligible = FALSE, live_since = NULL
                 WHERE id = $1
                """).execute(Tuple.of(id)).mapEmpty());
        await(pool.preparedQuery("UPDATE reviews SET deleted_at = now() WHERE id = $1")
            .execute(Tuple.of(id)).mapEmpty());

        assertEquals("held", await(pool.preparedQuery("SELECT disposition FROM reviews WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next().getString("disposition"))));
    }

    @Test
    void theCompanyProjectionStillAgreesWithItsSourceTables() throws Exception {
        // company_stats_live is maintained by statement-level triggers on reviews (V90) and V102
        // adds eleven columns, nine CHECKs and a deferred constraint trigger to that same table.
        // The projection has to be unaffected, because phase 2 deliberately does not touch the
        // attribution question rev 268 raises against it: that is phase 5, behind its own flag.
        //
        // Asserted through the projection's OWN reconciliation rather than by counting rows here.
        // company_stats_detect_drift() recomputes the expected figures from reviews and managers
        // and reports every disagreement, so an empty result is the strongest available statement
        // that writing through the expanded schema left the projection correct - and it does not
        // depend on this test knowing which managers the projection is supposed to count.
        await(service.createManager(user("auth0|phase2-stats"),
            submission("Ola Ferreira", "Juniper Dynamics", "Studio Lead"), null));

        String drift = await(pool.query("""
                SELECT COALESCE(string_agg(company_id || ' ' || field || ': expected ' || expected
                                           || ', actual ' || actual, '; '), '') AS d
                  FROM company_stats_detect_drift()
                """).execute().map(rs -> rs.iterator().next().getString("d")));
        assertEquals("", drift,
            "the projection must still agree with reviews and managers after a submission written "
            + "through the phase 2 schema. Got: " + drift);
    }

    @Test
    void readingAManagerProfileIsUnchanged() throws Exception {
        // The profile query aggregates career_history into JSON. It names its columns, so the four
        // generated columns V99 adds cannot appear in an API response by accident - but that is
        // worth asserting rather than assuming, because the way this breaks is a response growing
        // fields no client asked for.
        String submitter = user("auth0|phase2-read");
        await(service.createManager(submitter,
            submission("Yusuf Adeyemi", "Cobalt Systems", "Principal"), null));
        // Read as the SUBMITTER, because a submitted manager is pending_approval and
        // getManagerById deliberately refuses to serve a pending row to anyone else: the tile and
        // the profile page have to agree about what exists (CLAUDE.md section 41). That rule is
        // untouched by phase 2 and this test has to respect it rather than work around it.
        Row profile = await(service.getManagerById(managerIdOf("Yusuf Adeyemi"), submitter));

        assertEquals("Yusuf Adeyemi", profile.getString("name"));
        // The career history the profile serves is built by the query's own JSON aggregation, and
        // it names its columns. The four generated columns V99 adds are therefore invisible to the
        // response, which is worth asserting rather than assuming: the way this breaks is a
        // response quietly growing fields no client asked for, which CLAUDE.md section 25 forbids.
        String career = String.valueOf(profile.getValue("career_history"));
        assertFalse(career.contains("title_key"),
            "no generated column leaks into the career history the profile serves. Got: " + career);
        assertFalse(career.contains("period_known"), career);
        assertFalse(career.contains("rateable"), career);
    }

    // ------------------------------------------------------------------ fixture plumbing

    private static JsonObject submission(String name, String company, String title) {
        String[] parts = name.split(" ", 2);
        JsonObject ratings = new JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            ratings.put(key, 4.0);
        }
        JsonObject review = new JsonObject()
            .put("overallRating",  4.0)
            .put("ratings",        ratings)
            .put("managerCompany", company)
            .put("managerTitle",   title)
            .put("workedFrom",     "2022-01")
            .put("authorType",     "anonymous")
            .put("author",         "Anon" + Math.abs(name.hashCode()));
        return new JsonObject()
            .put("name",      name)
            .put("firstName", parts[0])
            .put("lastName",  parts.length > 1 ? parts[1] : "X")
            .put("company",   company)
            .put("title",     title)
            .put("image",     "img")
            .put("country",   "US")
            .put("status",    "active")
            .put("startDate", "2020-01")
            .put("review",    review);
    }

    private static String user(String auth0Id) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id,email,username,first_name,last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, auth0Id + "@test.com", "u" + Math.abs(auth0Id.hashCode()),
                              "Test", "User")));
        return auth0Id;
    }

    private static long managerIdOf(String name) throws Exception {
        return await(pool.preparedQuery("SELECT id FROM managers WHERE name = $1 ORDER BY id LIMIT 1")
            .execute(Tuple.of(name)).map(rs -> rs.iterator().next().getLong("id")));
    }

    private static boolean tableExists(String table) throws Exception {
        return Boolean.TRUE.equals(await(pool.preparedQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_name = $1")
            .execute(Tuple.of(table)).map(rs -> rs.iterator().hasNext())));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}

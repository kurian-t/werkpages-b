package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PHASE 0 of the manager-identity redesign: a rating submission may not rewrite who an existing
 * manager is.
 *
 * <h2>The bug these tests hold down</h2>
 *
 * <p>Submitting a review through the add-manager form looks for an existing manager at the same
 * company whose name is within Damerau-Levenshtein 1 of the one typed, and attaches the review to
 * it rather than creating a duplicate. That part is wanted and stays. What it ALSO did, for a
 * {@code ghost} manager, was call {@code updateForAttach} with the submitter's own form values and
 * then {@code syncSlugToName}:
 *
 * <pre>
 *   a public profile says      "Emma Davis",  Senior Manager,  /manager/emma-davis
 *   somebody rates             "Emma Davies", Principal Engineer
 *   the profile now says       "Emma Davies", Principal Engineer, /manager/emma-davies
 * </pre>
 *
 * <p>So one person's half-remembered spelling renamed a live, publicly visible human being,
 * retitled them, moved their URL, and filed the old address in {@code manager_url_history} as
 * though a rename had been decided. Nobody reviewed it and nothing recorded who did it. The design
 * document (rev 268) lists this first in its defect table: "the ghost branch renames the matched
 * person, retitles them, moves their slug - a submission rewrites who a published profile says it
 * is". The corpus says the same thing as a matching rule: a candidate "never has its name silently
 * corrected. Aaron Yi does not become Aaron Yip."
 *
 * <h2>Why the assertions are what they are</h2>
 *
 * <p>A test on the response would pass against the bug, because the response was always 200 and
 * carried the manager it had just corrupted. These read the stored row back.
 *
 * <p>{@code title} and {@code company} are deliberately NOT asserted unchanged here. They are also
 * written by {@code insertReviewTransactionally}, from whichever review is the most current BY
 * WORKED DATES rather than from whoever submitted last, which is documented product behaviour that
 * rev 268 retires later with the employment-attribution phases, not in Phase 0. The repository-level
 * guarantee that a SUBMISSION's own typed title cannot overwrite the row lives in
 * {@code ManagerRepositoryCoverageIntegrationTest.updateForAttach_*}.
 *
 * @see org.werkpages.repository.ManagerRepository#updateForAttach
 */
/* ROUTINE CI: tagged "critical" - security, erasure or data integrity. See the poms. */
@Tag("critical")
@Testcontainers
class ManagerIdentityMutationIntegrationTest {

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

        pool        = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));
        managerRepo = new ManagerRepository(pool);

        service = new ManagerService(managerRepo, new ReviewRepository(pool), new UserRepository(pool),
            new EditRepository(pool), new ReportRepository(pool), pool);
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

    // ── The forbidden mutations ───────────────────────────────────────────────

    @Test
    void ratingSubmission_doesNotRenameTheGhostManagerItAttachesTo() throws Exception {
        long ghostId = aPublicGhost("Emma", "Davis", "Senior Manager", "IdentityCo");
        String nameBefore = stringOf(ghostId, "name");
        assertEquals("Emma Davis", nameBefore, "fixture: the ghost starts as the name its creator searched");

        rateAs("auth0|phase0-rename", "Emma Davies", "IdentityCo", "Principal Engineer");

        assertEquals("Emma Davis", stringOf(ghostId, "name"),
            "a rating submission must never rename a live manager: 'Emma Davies' was one "
            + "submitter's spelling, not a decision to rename the person the profile is about");
    }

    @Test
    void ratingSubmission_doesNotMoveTheGhostManagersSlug() throws Exception {
        long ghostId = aPublicGhost("Emma", "Davis", "Senior Manager", "IdentityCo");
        String slugBefore = stringOf(ghostId, "slug");
        assertNotNull(slugBefore, "fixture: a ghost is created with a slug");

        rateAs("auth0|phase0-slug", "Emma Davies", "IdentityCo", "Principal Engineer");

        assertEquals(slugBefore, stringOf(ghostId, "slug"),
            "the public URL of a live profile must not move because somebody rated it");
        assertEquals(0, countUrlHistory(ghostId),
            "and no redirect may be filed, because no rename was decided: a manager_url_history "
            + "row is the record of a deliberate move");
    }

    @Test
    void ratingSubmission_doesNotWriteItsOwnLinkedinUrlOntoTheGhost() throws Exception {
        long ghostId = aPublicGhost("Emma", "Davis", "Senior Manager", "IdentityCo");
        assertNull(stringOf(ghostId, "linkedin_url"), "fixture: a searched ghost has no LinkedIn URL");

        JsonObject body = reviewSubmission("Emma Davies", "IdentityCo", "Principal Engineer")
            .put("linkedinUrl", "https://www.linkedin.com/in/somebody-else");
        await(service.createManager(insertUser("auth0|phase0-li"), body, null));

        assertNull(stringOf(ghostId, "linkedin_url"),
            "an unverified link typed by a reviewer must not become the profile's identity: "
            + "it asserts which real human the page is about");
    }

    // ── Positive controls: the feature still has to work ──────────────────────

    @Test
    void ratingSubmission_stillAttachesItsReviewToTheMatchedGhost() throws Exception {
        long ghostId = aPublicGhost("Emma", "Davis", "Senior Manager", "IdentityCo");

        rateAs("auth0|phase0-attach", "Emma Davies", "IdentityCo", "Principal Engineer");

        assertEquals(1, countRealReviews(ghostId),
            "the fix must stop the RENAME, not the attach: the review still belongs on the "
            + "manager the matcher found, or Phase 0 has created the duplicate profiles it exists "
            + "to prevent");
        assertEquals(1, countManagersNamed("Emma Davies") + countManagersNamed("Emma Davis"),
            "and exactly one manager exists afterwards, not a second one under the typed spelling");
    }

    @Test
    void ratingSubmission_forSomebodyWeDoNotHold_stillCreatesAManager() throws Exception {
        rateAs("auth0|phase0-create", "Nobody Weholdyet", "FreshCo", "Director");

        assertEquals(1, countManagersNamed("Nobody Weholdyet"),
            "legitimate creation is untouched: Phase 0 restricts updating an EXISTING manager");
    }

    @Test
    void anAdministratorCanStillRenameAManager() throws Exception {
        long ghostId = aPublicGhost("Emma", "Davis", "Senior Manager", "IdentityCo");

        await(managerRepo.adminEdit(ghostId, "Emma Davies", null, null, null, null,
                                    null, null, null, null, null));

        assertEquals("Emma Davies", stringOf(ghostId, "name"),
            "renaming is still possible, it is just no longer something a rating submission does "
            + "on its own: adminEdit is the reviewed path and Phase 0 must not take it away");
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    /** A live, publicly visible ghost, created the way a first-ever search creates one. */
    private long aPublicGhost(String first, String last, String title, String company) throws Exception {
        String creator = insertUserAuth0("auth0|ghost-creator-" + UUID.randomUUID());
        await(service.findOrCreate(creator, first, last, title, company, "US", null, null, null));
        RowSet<Row> rs = await(pool.preparedQuery(
                "SELECT id, approval_status FROM managers WHERE name = $1 AND company = $2")
            .execute(Tuple.of(first + " " + last, company)));
        assertTrue(rs.iterator().hasNext(), "fixture: the search was expected to create a ghost");
        Row row = rs.iterator().next();
        assertEquals("ghost", row.getString("approval_status"),
            "fixture: this test is about a LIVE, public row; a pending one is a different case");
        return row.getLong("id");
    }

    private void rateAs(String auth0Id, String name, String company, String title) throws Exception {
        await(service.createManager(insertUser(auth0Id), reviewSubmission(name, company, title), null));
    }

    private String insertUser(String auth0Id) throws Exception {
        return insertUserAuth0(auth0Id);
    }

    private String insertUserAuth0(String auth0Id) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id,email,username,first_name,last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, auth0Id + "@test.com", "u" + Math.abs(auth0Id.hashCode()),
                              "Test", "User")));
        return auth0Id;
    }

    private static JsonObject reviewSubmission(String name, String company, String title) {
        String[] parts   = name.split(" ", 2);
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
            .put("author",         "AnonTester99");
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

    private String stringOf(long managerId, String column) throws Exception {
        return await(pool.preparedQuery("SELECT " + column + " AS v FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("v")));
    }

    private int countUrlHistory(long managerId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM manager_url_history WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getInteger("c")));
    }

    /** Real opinions only: a ghost carries a weighted placeholder that is not one. */
    private int countRealReviews(long managerId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM reviews WHERE manager_id = $1 AND weight = FALSE "
                + "AND deleted_at IS NULL")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getInteger("c")));
    }

    private int countManagersNamed(String name) throws Exception {
        return await(pool.preparedQuery("SELECT COUNT(*) AS c FROM managers WHERE name = $1")
            .execute(Tuple.of(name))
            .map(rs -> rs.iterator().next().getInteger("c")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}

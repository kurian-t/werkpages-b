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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.CompanyLocationRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.GeoObservation;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.DeclaredLocation;
import org.werkpages.service.ManagerService;
import org.werkpages.service.SubmissionContext;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a contributor said about where the work happened, and where it is stored.
 *
 * <p>The load-bearing case is the last one. A manager moves between branches; the opinions people
 * already left about them did not move, because those happened somewhere specific and saying
 * otherwise would rewrite history every time somebody changed jobs.
 */
@Testcontainers
class DeclaredLocationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool           pool;
    static ManagerService service;

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
        service = new ManagerService(
            new ManagerRepository(pool), new ReviewRepository(pool), new UserRepository(pool),
            new EditRepository(pool), new ReportRepository(pool), pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, companies, users, geo_observations, company_locations CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── The coarse ladder ─────────────────────────────────────────────────────

    @Test
    void cityPrecision_isStoredAsDeclared() throws Exception {
        insertUser("auth0|dl-city", "DlCity");
        JsonObject body = validCreateManagerBody("Kim Declared", "DeclCo", "Manager")
            .put("declaredCountry", "Canada")
            .put("declaredState", "Ontario")
            .put("declaredCity", "Kitchener")
            .put("declaredPrecision", DeclaredLocation.CITY);

        Row created = await(service.createManager("auth0|dl-city", body, null, submission(body)));

        Row m = manager(created.getLong("id"));
        assertEquals("Canada",    m.getString("declared_country"));
        assertEquals("Ontario",   m.getString("declared_state"));
        assertEquals("Kitchener", m.getString("declared_city"));
        assertEquals("city",      m.getString("declared_precision"));
        assertNull(m.getLong("company_location_id"));
    }

    @Test
    void observedKeysAlone_declareNothing() throws Exception {
        /*
          country/state/city on a body are what GeoUtils.stampGeo fills from Cloudflare headers. If
          they could become declared values, every submission would publish the submitter's location
          without anybody having confirmed it.
        */
        insertUser("auth0|dl-obs", "DlObs");
        JsonObject body = validCreateManagerBody("Lee Inferred", "InferCo", "Manager")
            .put("country", "Canada").put("state", "Ontario").put("city", "Toronto");

        Row created = await(service.createManager("auth0|dl-obs", body, null, submission(body)));

        Row m = manager(created.getLong("id"));
        assertNull(m.getString("declared_country"));
        assertNull(m.getString("declared_city"));
        assertNull(m.getString("declared_precision"));
        assertEquals("Toronto", m.getString("city"), "the legacy inferred column is still written");
    }

    @Test
    void anInconsistentPrecision_isRejected() throws Exception {
        insertUser("auth0|dl-bad", "DlBad");
        JsonObject body = validCreateManagerBody("Bad Shape", "BadCo", "Manager")
            .put("declaredCountry", "Canada")
            .put("declaredPrecision", DeclaredLocation.CITY);   // claims city, supplies country

        Future<Row> attempt = service.createManager("auth0|dl-bad", body, null, submission(body));
        assertThrows(Exception.class, () -> await(attempt));
    }

    // ── Exact precision ───────────────────────────────────────────────────────

    @Test
    void exactPrecision_takesCoarseValuesFromTheLocationRow() throws Exception {
        insertUser("auth0|dl-exact", "DlExact");
        long companyId = insertCompany("Walmart");
        long locationId = insertLocation(companyId, "1005 Ottawa St N", "Kitchener", "Ontario", "Canada");

        JsonObject body = validCreateManagerBody("Jane Store", "Walmart", "Store Manager")
            .put("companyId", companyId)
            .put("companyLocationId", locationId)
            .put("declaredCity", "Waterloo")        // stale form - the building is in Kitchener
            .put("declaredPrecision", DeclaredLocation.EXACT);

        Row created = await(service.createManager("auth0|dl-exact", body, null, submission(body)));

        Row m = manager(created.getLong("id"));
        assertEquals(locationId, m.getLong("company_location_id"));
        assertEquals("Kitchener", m.getString("declared_city"),
            "the selected building decides the city, not whatever the form last held");
        assertEquals("Ontario", m.getString("declared_state"));
        assertEquals("exact",   m.getString("declared_precision"));
    }

    @Test
    void aLocationBelongingToAnotherCompany_isRejected() throws Exception {
        /*
          Accepting this would attach a Walmart address to a manager at Loblaws, and Walmart's
          company page would then list a branch nobody there has worked at. There is no honest
          request that does this.
        */
        insertUser("auth0|dl-cross", "DlCross");
        long walmart = insertCompany("Walmart");
        long loblaws = insertCompany("Loblaws");
        long walmartLocation = insertLocation(walmart, "1005 Ottawa St N", "Kitchener", "Ontario", "Canada");

        JsonObject body = validCreateManagerBody("Cross Boundary", "Loblaws", "Manager")
            .put("companyId", loblaws)
            .put("companyLocationId", walmartLocation)
            .put("declaredPrecision", DeclaredLocation.EXACT);

        Future<Row> attempt = service.createManager("auth0|dl-cross", body, null, submission(body));
        assertThrows(Exception.class, () -> await(attempt));
    }

    // ── The contribution keeps its own location ───────────────────────────────

    @Test
    void movingAManagerDoesNotMoveTheOpinionsLeftAboutThem() throws Exception {
        /*
          THE case this whole split exists for. Brenda manages the Gerrard Walmart, collects an
          opinion, then transfers to Dufferin. The manager row follows her. The opinion does not -
          it happened at Gerrard, and an aggregate that moved it would be a claim nobody made.
        */
        insertUser("auth0|dl-move", "DlMove");
        long companyId = insertCompany("Walmart");
        long gerrard  = insertLocation(companyId, "1000 Gerrard St E", "Toronto", "Ontario", "Canada");
        long dufferin = insertLocation(companyId, "900 Dufferin St",   "Toronto", "Ontario", "Canada");

        JsonObject ghost = await(service.createGhostManager(new JsonObject()
            .put("name", "Brenda Moved").put("company", "Walmart")
            .put("title", "Store Manager").put("country", "Canada")
            .put("companyId", companyId), null));
        long managerId = ghost.getLong("id");

        JsonObject reviewBody = validReviewBody("Walmart", "Store Manager")
            .put("companyLocationId", gerrard)
            .put("declaredPrecision", DeclaredLocation.EXACT);
        await(service.createReview("auth0|dl-move", managerId, reviewBody, null, submission(reviewBody)));

        // She transfers. Only the manager row is touched.
        await(pool.preparedQuery("UPDATE managers SET company_location_id = $1 WHERE id = $2")
            .execute(Tuple.of(dufferin, managerId)));

        // A ghost manager carries a seed review with no author, so select the one an actual person
        // left rather than whichever row comes back first.
        Row review = await(pool.preparedQuery("""
                SELECT company_location_id, declared_city FROM reviews
                WHERE manager_id = $1 AND user_id IS NOT NULL
                """)
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next()));

        assertEquals(gerrard, review.getLong("company_location_id"),
            "the opinion must still belong to the branch where it happened");
        assertEquals(dufferin, manager(managerId).getLong("company_location_id"),
            "while the manager herself has moved");
    }

    @Test
    void aGhostFromSearch_declaresNothing() throws Exception {
        // A /find ghost is created by a search, and a search never declares a location.
        JsonObject ghost = await(service.createGhostManager(new JsonObject()
            .put("name", "Ghost Nodeclare").put("company", "GhostCo")
            .put("title", "Manager").put("country", "Canada"), null));

        Row m = manager(ghost.getLong("id"));
        assertNull(m.getString("declared_precision"));
        assertNull(m.getLong("company_location_id"));
    }


    // ── The manager's own location follows the most current opinion ───────────

    @Test
    void ratingAManagerUpdatesWhereTheyWorkNow() throws Exception {
        /*
          Option (c): the manager row is derived, not directly written. It follows the most current
          opinion the same way their company and title already do - so one contributor cannot
          overwrite another's answer, and a relocation corrects itself as new ratings arrive.
        */
        insertUser("auth0|dl-sync", "DlSync");
        long companyId = insertCompany("Walmart");
        long gerrard = insertLocation(companyId, "1000 Gerrard St E", "Toronto", "Ontario", "Canada");

        JsonObject ghost = await(service.createGhostManager(new JsonObject()
            .put("name", "Sync Target").put("company", "Walmart")
            .put("title", "Store Manager").put("country", "Canada")
            .put("companyId", companyId), null));
        long managerId = ghost.getLong("id");
        assertNull(manager(managerId).getLong("company_location_id"), "a ghost starts with none");

        JsonObject reviewBody = validReviewBody("Walmart", "Store Manager")
            .put("companyLocationId", gerrard)
            .put("declaredPrecision", DeclaredLocation.EXACT);
        await(service.createReview("auth0|dl-sync", managerId, reviewBody, null, submission(reviewBody)));

        Row m = manager(managerId);
        assertEquals(gerrard, m.getLong("company_location_id"),
            "the rating decided where this manager works now");
        assertEquals("Toronto", m.getString("declared_city"));
        assertEquals("exact",   m.getString("declared_precision"));
    }

    @Test
    void anOpinionThatDeclaresNothingLeavesTheManagerAlone() throws Exception {
        /*
          Silence is not a claim that they work nowhere. Somebody who skips the optional location
          must not wipe a location an earlier contributor took the trouble to give.
        */
        insertUser("auth0|dl-silent", "DlSilent");
        long companyId = insertCompany("Walmart");
        long gerrard = insertLocation(companyId, "1000 Gerrard St E", "Toronto", "Ontario", "Canada");

        JsonObject ghost = await(service.createGhostManager(new JsonObject()
            .put("name", "Keep Location").put("company", "Walmart")
            .put("title", "Store Manager").put("country", "Canada")
            .put("companyId", companyId), null));
        long managerId = ghost.getLong("id");

        await(pool.preparedQuery("UPDATE managers SET company_location_id = $1, declared_precision = 'exact' WHERE id = $2")
            .execute(Tuple.of(gerrard, managerId)));

        JsonObject silent = validReviewBody("Walmart", "Store Manager");   // no location at all
        await(service.createReview("auth0|dl-silent", managerId, silent, null, submission(silent)));

        assertEquals(gerrard, manager(managerId).getLong("company_location_id"),
            "an opinion with no location must not clear the one already known");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static SubmissionContext submission(JsonObject body) {
        return SubmissionContext.of(new GeoObservation("Canada", "Ontario", "Toronto"), body);
    }


    // ── Editing an opinion ────────────────────────────────────────────────────

    /*
      The shared manager form lets somebody change company, title, dates and location while editing
      a review they already left. That makes "which edits move an opinion" a question the server has
      to answer, rather than one that never came up because the field was not on the form.

      The rule: a declaration that is not sent is not a declaration. An ordinary edit to the stars
      carries no declaredPrecision, and the stored location must survive it untouched - otherwise
      fixing a typo in a 2019 opinion would silently re-file it wherever the manager works today,
      which is the exact failure `movingAManagerDoesNotMoveTheOpinionsLeftAboutThem` exists to
      prevent, arriving through a different door.
    */

    @Test
    void editingTheRatingsLeavesTheOpinionWhereItHappened() throws Exception {
        insertUser("auth0|dl-edit-keep", "DlEditKeep");
        long companyId = insertCompany("Keepmart");
        long gerrard   = insertLocation(companyId, "1000 Gerrard St E", "Toronto", "Ontario", "Canada");

        long managerId = ghostManagerAt(companyId, "Nina Keep", "Keepmart");
        UUID reviewId = reviewIdOf(await(service.createReview("auth0|dl-edit-keep", managerId,
            reviewWithLocation("Keepmart", gerrard), null,
            submission(reviewWithLocation("Keepmart", gerrard)))));

        // The edit a person actually makes most often: same everything, different stars. No
        // location keys at all, because the form did not reopen that field.
        JsonObject edit = validReviewBody("Keepmart", "Store Manager").put("overallRating", 2.0);
        await(service.updateReview("auth0|dl-edit-keep", managerId, reviewId, edit));

        Row after = review(reviewId);
        assertEquals(gerrard, after.getLong("company_location_id"),
            "an edit that says nothing about location must not move the opinion");
        assertEquals("Toronto", after.getString("declared_city"));
        assertEquals(2.0, after.getBigDecimal("overall_rating").doubleValue(),
            "while the thing that WAS edited did change");
    }

    @Test
    void changingTheLocationOnAnEditMovesThatOpinionOnly() throws Exception {
        insertUser("auth0|dl-edit-move", "DlEditMove");
        long companyId = insertCompany("Movemart");
        long gerrard   = insertLocation(companyId, "1000 Gerrard St E", "Toronto", "Ontario", "Canada");
        long dufferin  = insertLocation(companyId, "900 Dufferin St",   "Toronto", "Ontario", "Canada");

        long managerId = ghostManagerAt(companyId, "Omar Move", "Movemart");
        UUID reviewId = reviewIdOf(await(service.createReview("auth0|dl-edit-move", managerId,
            reviewWithLocation("Movemart", gerrard), null,
            submission(reviewWithLocation("Movemart", gerrard)))));

        // They picked the wrong store and are correcting it. This one IS a declaration.
        JsonObject edit = validReviewBody("Movemart", "Store Manager")
            .put("companyLocationId", dufferin)
            .put("declaredPrecision", DeclaredLocation.EXACT);
        await(service.updateReview("auth0|dl-edit-move", managerId, reviewId, edit));

        assertEquals(dufferin, review(reviewId).getLong("company_location_id"));
        /*
          Writing the review set the manager's own location, because rating somebody is fresh
          evidence of where they work (see ratingAManagerUpdatesWhereTheyWorkNow). Correcting that
          opinion afterwards is not: it says where the WORK happened, which may be years ago. So
          the manager stays at Gerrard while the opinion moves to Dufferin.
        */
        assertEquals(gerrard, manager(managerId).getLong("company_location_id"),
            "editing a review must not restate where the manager works now");
    }

    @Test
    void anEditCanCoarsenAnExactLocation() throws Exception {
        insertUser("auth0|dl-edit-coarse", "DlEditCoarse");
        long companyId = insertCompany("Broadmart");
        long store     = insertLocation(companyId, "5 Yonge St", "Toronto", "Ontario", "Canada");

        long managerId = ghostManagerAt(companyId, "Pat Broad", "Broadmart");
        UUID reviewId = reviewIdOf(await(service.createReview("auth0|dl-edit-coarse", managerId,
            reviewWithLocation("Broadmart", store), null,
            submission(reviewWithLocation("Broadmart", store)))));

        // "I was at one of the Toronto ones, I could not tell you which." A downgrade is a real
        // answer, so it has to clear the building rather than leave a contradiction on the row.
        JsonObject edit = validReviewBody("Broadmart", "Store Manager")
            .put("declaredCountry", "Canada").put("declaredState", "Ontario")
            .put("declaredCity", "Toronto")
            .put("declaredPrecision", DeclaredLocation.CITY);
        await(service.updateReview("auth0|dl-edit-coarse", managerId, reviewId, edit));

        Row after = review(reviewId);
        assertEquals("city", after.getString("declared_precision"));
        assertEquals("Toronto", after.getString("declared_city"));
        assertNull(after.getLong("company_location_id"),
            "coarsening must clear the building, not leave a city that contradicts it");
    }

    @Test
    void anEditCannotAttachAnotherCompanysWorkplace() throws Exception {
        insertUser("auth0|dl-edit-wrong", "DlEditWrong");
        long mine   = insertCompany("Minemart");
        long theirs = insertCompany("Theirsmart");
        long myStore    = insertLocation(mine,   "1 Main St", "Toronto", "Ontario", "Canada");
        long theirStore = insertLocation(theirs, "2 Other St", "Toronto", "Ontario", "Canada");

        long managerId = ghostManagerAt(mine, "Quinn Wrong", "Minemart");
        UUID reviewId = reviewIdOf(await(service.createReview("auth0|dl-edit-wrong", managerId,
            reviewWithLocation("Minemart", myStore), null,
            submission(reviewWithLocation("Minemart", myStore)))));

        JsonObject edit = validReviewBody("Minemart", "Store Manager")
            .put("companyLocationId", theirStore)
            .put("declaredPrecision", DeclaredLocation.EXACT);

        assertThrows(Exception.class,
            () -> await(service.updateReview("auth0|dl-edit-wrong", managerId, reviewId, edit)),
            "the ownership check must hold on edit exactly as it does on create");
        assertEquals(myStore, review(reviewId).getLong("company_location_id"),
            "and the rejected edit must not have half-applied");
    }

    // ── Helpers for the edit cases ────────────────────────────────────────────

    /** A manager already attached to a company, so a workplace can belong to them. */
    private static long ghostManagerAt(long companyId, String name, String company) throws Exception {
        JsonObject ghost = await(service.createGhostManager(new JsonObject()
            .put("name", name).put("company", company)
            .put("title", "Store Manager").put("country", "Canada")
            .put("companyId", companyId), null));
        return ghost.getLong("id");
    }

    private static JsonObject reviewWithLocation(String company, long locationId) {
        return validReviewBody(company, "Store Manager")
            .put("companyLocationId", locationId)
            .put("declaredPrecision", DeclaredLocation.EXACT);
    }

    private static UUID reviewIdOf(Row created) {
        return created.getUUID("id");
    }

    private static Row review(UUID id) throws Exception {
        return await(pool.preparedQuery("""
                SELECT overall_rating, declared_country, declared_state, declared_city,
                       declared_precision, company_location_id
                FROM reviews WHERE id = $1
                """).execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private static Row manager(long id) throws Exception {
        return await(pool.preparedQuery("""
                SELECT city, status, declared_country, declared_state, declared_city,
                       declared_precision, company_location_id
                FROM managers WHERE id = $1
                """).execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private static long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies(name, slug, status) VALUES ($1,$2,'approved') RETURNING id")
            .execute(Tuple.of(name, name.toLowerCase().replace(" ", "-")))
            .map(rs -> rs.iterator().next().getLong("id")));
    }


    // ── What the picker offers, and in what order ─────────────────────────────

    /*
      Reported from the running site: the general location was at the bottom of the list, under
      eight street addresses. A city or a province is the answer most people actually have - the
      exact branch is the specialist case - so leading with buildings buried the usable answer
      beneath ones nobody had asked about.
    */

    @Test
    void confirmedGeographyIsOfferedAheadOfConfirmedBuildings() throws Exception {
        insertUser("auth0|sug-order", "SugOrder");
        long walmart = insertCompany("Walmart");
        insertLocation(walmart, "1005 Toronto St", "Toronto", "Ontario", "Canada");
        insertManagerWithDeclared(walmart, "Toronto", "Ontario", "Canada");

        var rows = await(new CompanyLocationRepository(pool)
            .suggest(walmart, "Walmart", "toronto", null, null));

        var kinds = new java.util.ArrayList<String>();
        rows.forEach(r -> kinds.add(r.getString("kind")));
        assertEquals("geo", kinds.get(0),
            "the coarse answer leads; the branch is the specialist case, not the default");
        assertTrue(kinds.contains("place"), "and the branch is still offered");
    }

    @Test
    void manyBranchesCannotCrowdOutTheCoarseAnswer() throws Exception {
        /*
          The list is capped at eight. While buildings led it, a company with eight matching
          branches pushed every coarse answer off the end - and the companies with the most
          locations are exactly the ones where somebody is least likely to know which branch they
          worked at.
        */
        insertUser("auth0|sug-crowd", "SugCrowd");
        long walmart = insertCompany("Walmart");
        for (int i = 1; i <= 10; i++) {
            insertLocation(walmart, i + " Toronto Ave", "Toronto", "Ontario", "Canada");
        }
        insertManagerWithDeclared(walmart, "Toronto", "Ontario", "Canada");

        var rows = await(new CompanyLocationRepository(pool)
            .suggest(walmart, "Walmart", "toronto", null, null));

        var kinds = new java.util.ArrayList<String>();
        rows.forEach(r -> kinds.add(r.getString("kind")));
        assertTrue(kinds.contains("geo"),
            "the one answer somebody who only knows the city can give was off the list entirely");
        assertEquals("geo", kinds.get(0));
    }

    /** A manager carrying confirmed geography, which is the second tier the picker reads. */
    private static void insertManagerWithDeclared(long companyId, String city, String state,
                                                  String country) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO managers (name, title, company, company_id, status,
                                      approval_status, declared_country, declared_state,
                                      declared_city, declared_precision)
                VALUES ('Coarse Answer', 'Manager', 'Walmart', $1, 'active', 'approved', $2, $3, $4, 'city')
                """)
            .execute(Tuple.of(companyId, country, state, city)).mapEmpty());
    }


    // ── The manager's status, told by a reviewer ──────────────────────────────

    /*
      The last field the add-manager form asked for that the review form could not: whether the
      manager is still in the role. It is snapshotted on the review like their company and title,
      and the manager's own status is derived from the most current opinion - so one contributor
      cannot overwrite another's answer, and somebody retiring corrects itself as ratings arrive.
    */

    @Test
    void aReviewCanSayTheManagerHasRetired() throws Exception {
        insertUser("auth0|ms-1", "MsOne");
        long companyId = insertCompany("Walmart");
        JsonObject ghost = await(service.createGhostManager(new JsonObject()
            .put("name", "Retired Soon").put("company", "Walmart")
            .put("title", "Store Manager").put("country", "Canada")
            .put("companyId", companyId), null));
        long managerId = ghost.getLong("id");
        assertEquals("active", manager(managerId).getString("status"), "a ghost starts active");

        JsonObject body = validReviewBody("Walmart", "Store Manager")
            .put("managerStatus", "retired");
        await(service.createReview("auth0|ms-1", managerId, body, null, submission(body)));

        assertEquals("retired", manager(managerId).getString("status"),
            "the manager follows the most current opinion, as their company and title already do");
    }

    @Test
    void aReviewThatSaysNothingLeavesTheStatusAlone() throws Exception {
        /*
          Silence is not a claim that somebody retired. A client that never asks the question - an
          older tab, another consumer of the API - must not be able to change a manager's status
          by omitting it.
        */
        insertUser("auth0|ms-2", "MsTwo");
        long companyId = insertCompany("Walmart");
        JsonObject ghost = await(service.createGhostManager(new JsonObject()
            .put("name", "Still Here").put("company", "Walmart")
            .put("title", "Store Manager").put("country", "Canada")
            .put("companyId", companyId), null));
        long managerId = ghost.getLong("id");
        await(pool.preparedQuery("UPDATE managers SET status = 'retired' WHERE id = $1")
            .execute(Tuple.of(managerId)).mapEmpty());

        JsonObject body = validReviewBody("Walmart", "Store Manager");
        await(service.createReview("auth0|ms-2", managerId, body, null, submission(body)));

        assertEquals("retired", manager(managerId).getString("status"),
            "a review that declared nothing about the status left it as it was");
    }

    @Test
    void anUnknownStatusIsRefusedRatherThanStored() throws Exception {
        // The vocabulary is active or retired. Anything else is a broken client, and storing it
        // would put a value on the manager row that no surface knows how to render.
        insertUser("auth0|ms-3", "MsThree");
        long companyId = insertCompany("Walmart");
        JsonObject ghost = await(service.createGhostManager(new JsonObject()
            .put("name", "Bad Status").put("company", "Walmart")
            .put("title", "Store Manager").put("country", "Canada")
            .put("companyId", companyId), null));
        long managerId = ghost.getLong("id");

        JsonObject body = validReviewBody("Walmart", "Store Manager")
            .put("managerStatus", "on sabbatical");

        assertThrows(Exception.class,
            () -> await(service.createReview("auth0|ms-3", managerId, body, null, submission(body))));
        assertEquals("active", manager(managerId).getString("status"), "and nothing was changed");
    }

    /** A locking caller has one string, not two - see ManagerFormFields. */
    private static long insertLocation(long companyId, String street, String city,
                                       String state, String country) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO company_locations
                    (company_id, source, source_place_id, display_name, street, city, state, country)
                VALUES ($1, 'manual', $2, $3, $4, $5, $6, $7)
                RETURNING id
                """)
            .execute(Tuple.of(companyId, street, "Store", street, city, state, country))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static void insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery("INSERT INTO users(auth0_id, username, email) VALUES ($1,$2,$3)")
            .execute(Tuple.of(auth0Id, username, username + "@example.com")));
    }

    private static JsonObject validReviewBody(String company, String title) {
        JsonObject ratings = new JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            ratings.put(key, 4.0);
        }
        return new JsonObject()
            .put("overallRating", 4.0).put("ratings", ratings)
            .put("managerCompany", company).put("managerTitle", title)
            .put("workedFrom", "2022-01")
            .put("authorType", "anonymous").put("author", "AnonRater42");
    }

    private static JsonObject validCreateManagerBody(String name, String company, String title) {
        return new JsonObject()
            .put("name", name).put("company", company).put("title", title)
            .put("image", "img").put("country", "US").put("status", "active")
            .put("startDate", "2020-01")
            .put("review", validReviewBody(company, title));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}

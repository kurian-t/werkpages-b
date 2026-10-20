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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.CorpusPlace;
import org.werkpages.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A street address, from the PUBLISHED index set, through the service, into the database.
 *
 * <h2>What this proves that nothing else does</h2>
 *
 * <p>{@link org.werkpages.repository.LocationIndexWiringTest} proves the address index is
 * invoked and returns rows. {@link LocationSuggestionContractIntegrationTest} proves the
 * suggestion pipeline merges and promotes corpus places, but from a small DuckDB fixture that
 * holds businesses and no addresses at all. Neither covers the join between them: that an address
 * row produced by the SQLite index survives the merge, arrives as a selectable suggestion, and
 * promotes to a {@code company_locations} row on submit.
 *
 * <p>That join is where the key names matter. {@code addCorpusPlaces} reads {@code stateCode} and
 * {@code CorpusPlace.fromBody} reads {@code sourcePlaceId}; the index originally emitted
 * {@code state} and {@code placeId}. Nothing throws on a missing key, so the tile rendered, the
 * region vanished from its detail line, and {@code fromBody} quietly returned null on submit so
 * the address was dropped while the form reported success.
 *
 * <h2>Why it is conditional</h2>
 *
 * <p>The index set is a 1.2 GB build artifact, not a fixture, and it is not present in CI. Skipped
 * without it rather than failing, and the wiring test covers the same path at the repository level
 * on any host that does have it. Point {@code LOCATION_INDEX_SET} at a published release to run.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "LOCATION_INDEX_SET", matches = ".+",
    disabledReason = "needs a published location index set; see the deployment runbook")
class PublishedAddressIndexIntegrationTest {

    /** A real Canadian address, in the published index and stable across releases. */
    private static final String ADDRESS = "55 Gould Street, Toronto";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static ManagerService service;

    @BeforeAll
    static void setUpAll() throws Exception {
        /*
          Set BEFORE the service is constructed: ManagerService builds its LocationCorpusRepository
          as an instance field, so the paths have to be resolvable by the time that runs.
        */
        Path set = Path.of(System.getenv("LOCATION_INDEX_SET"));
        System.setProperty("LOCATION_GEOGRAPHY_INDEX", set.resolve("geography.sqlite").toString());
        System.setProperty("LOCATION_ROUTING_INDEX",   set.resolve("routing.sqlite").toString());
        System.setProperty("LOCATION_ADDRESS_INDEX_DIR", set.resolve("addresses").toString());

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

    @AfterAll
    static void tearDownAll() throws Exception {
        System.clearProperty("LOCATION_GEOGRAPHY_INDEX");
        System.clearProperty("LOCATION_ROUTING_INDEX");
        System.clearProperty("LOCATION_ADDRESS_INDEX_DIR");
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, companies, users, company_locations CASCADE").execute());
    }

    @Test
    @DisplayName("an address reaches the suggestion response, selectable and promotable")
    void addressReachesTheResponse() throws Exception {
        long companyId = insertCompany("Gould Street Coffee");

        JsonArray out = await(service.suggestCompanyLocations(
            companyId, "Gould Street Coffee", ADDRESS, "Canada", null));

        assertFalse(out.isEmpty(), "the address query produced no suggestions at all");
        JsonObject address = firstAddress(out);
        assertNotNull(address,
            "no address arrived in the response. The index returns this row, so it is being lost "
            + "between the index and the merge: " + out.encode());

        assertEquals("place", address.getString("kind"));
        assertEquals("exact", address.getString("precision"));
        assertNull(address.getLong("companyLocationId"), "not in the database yet");

        JsonObject place = address.getJsonObject("corpusPlace");
        assertNotNull(place, "an address with no corpusPlace cannot be submitted: " + address.encode());
        /*
          The four fields promotion depends on. fromBody returns null without name and
          sourcePlaceId, and validate() refuses without city and countryCode - and every one of
          those failures happens AFTER the person has pressed submit.
        */
        assertNotNull(place.getString("name"));
        assertNotNull(place.getString("sourcePlaceId"));
        assertEquals("Toronto", place.getString("city"));
        assertEquals("CA", place.getString("countryCode"));
        assertNull(CorpusPlace.fromBody(new JsonObject().put("corpusPlace", place)).validate(),
            "the promoted address must pass its own validation or the submission is refused");
    }

    @Test
    @DisplayName("picking an address stores it once, and it is reused afterwards")
    void addressSelectionPersistsOnceAndIsReused() throws Exception {
        long companyId = insertCompany("Gould Street Coffee");

        JsonArray suggestions = await(service.suggestCompanyLocations(
            companyId, "Gould Street Coffee", ADDRESS, "Canada", null));
        JsonObject picked = firstAddress(suggestions);
        assertNotNull(picked, "the address was not offered: " + suggestions.encode());
        String sourcePlaceId = picked.getJsonObject("corpusPlace").getString("sourcePlaceId");

        insertUser("auth0|gould-1", "GouldOne");
        JsonObject body = validCreateManagerBody("Dana Gould", "Gould Street Coffee", "Store Manager")
            .put("declaredPrecision", "exact")
            .put("corpusPlace", picked.getJsonObject("corpusPlace"));
        assertNotNull(await(service.createManager("auth0|gould-1", body, null, submission(body))),
            "the submission carrying the address was rejected");

        RowSet<Row> rows = await(pool.preparedQuery("""
                SELECT id, city, country, source_place_id FROM company_locations WHERE company_id = $1
                """).execute(Tuple.of(companyId)));
        assertEquals(1, rows.size(), "expected exactly one stored address, got " + rows.size());
        Row stored = rows.iterator().next();
        assertEquals("Toronto", stored.getString("city"));
        assertEquals(sourcePlaceId, stored.getString("source_place_id"),
            "the source identity must be stored, or two people picking this address create two rows");
        long firstId = stored.getLong("id");

        /*
          A SECOND PERSON PICKS THE SAME ADDRESS: it must be reused, not duplicated.

          This is the property that protects the data. A corpus place is promoted by
          CompanyLocationRepository.findOrCreate, keyed on
          (company_id, source, source_place_id), so two people naming the same building reach one
          row rather than two that look identical on the page and split its ratings in half.

          It is also the reason the identity carried through the index has to be the source's own
          GERS id rather than a per-release row number: renumber the index and this key stops
          pointing at the same address.
        */
        insertUser("auth0|gould-2", "GouldTwo");
        JsonArray secondSearch = await(service.suggestCompanyLocations(
            companyId, "Gould Street Coffee", ADDRESS, "Canada", null));
        JsonObject pickedAgain = firstAddress(secondSearch);
        assertNotNull(pickedAgain, "the address stopped being offered once stored: "
            + secondSearch.encode());
        JsonObject second = validCreateManagerBody("Sam Gould", "Gould Street Coffee", "Barista")
            .put("declaredPrecision", "exact")
            .put("corpusPlace", pickedAgain.getJsonObject("corpusPlace"));
        await(service.createManager("auth0|gould-2", second, null, submission(second)));

        RowSet<Row> after = await(pool.preparedQuery(
                "SELECT id FROM company_locations WHERE company_id = $1").execute(Tuple.of(companyId)));
        assertEquals(1, after.size(),
            "the same address was stored twice: findOrCreate did not match on "
            + "(company_id, source, source_place_id), which splits one building's ratings in two");
        assertEquals(firstId, after.iterator().next().getLong("id"), "a different row was used");

        /*
          NOT ASSERTED HERE, deliberately: that the stored row comes back with its
          companyLocationId.

          It does not, and that is a pre-existing limit of the Postgres reuse tier rather than
          anything about addresses. That tier matches with a single
          LIKE '%<the whole query>%' against display_name, street and city, so it only ever fires
          when what was typed is a contiguous substring of ONE column. An address is naturally
          typed as "<street>, <city>", which is a substring of neither, and the same miss happens
          for a business typed as "<name>, <city>".

          The consequence is mild because of the assertion above: the suggestion arrives as a
          corpus place instead of an id, the submit promotes it, and findOrCreate reuses the
          existing row. One row either way. Widening that predicate touches every company-location
          suggestion, so it is noted rather than changed here.
        */
    }

    @Test
    @DisplayName("a query with no house number is answered with geography, not an invented address")
    void aStreetIsNotAnAddress() throws Exception {
        long companyId = insertCompany("Gould Street Coffee");
        JsonArray out = await(service.suggestCompanyLocations(
            companyId, "Gould Street Coffee", "Toronto", "Canada", null));
        /*
          Offering one address on a street as the answer to a street query is worse than offering
          none: picking it stores that exact building, so somebody at number 45 is recorded at
          number 142. A wrong precise answer is harder to notice, and to undo, than a coarse one.
        */
        assertNull(firstAddress(out),
            "a query with no house number was answered with a specific address: " + out.encode());
    }

    /** The first suggestion that came from the address index: exact, promotable, Canadian. */
    private static JsonObject firstAddress(JsonArray out) {
        for (int i = 0; i < out.size(); i++) {
            JsonObject item = out.getJsonObject(i);
            JsonObject place = item.getJsonObject("corpusPlace");
            if (place == null) continue;
            String name = place.getString("name");
            if (name != null && name.toLowerCase().contains("gould")
                && "CA".equals(place.getString("countryCode"))) {
                return item;
            }
        }
        return null;
    }

    private static JsonObject validCreateManagerBody(String name, String company, String title) {
        return new JsonObject()
            .put("name", name).put("company", company).put("title", title)
            .put("image", "img").put("country", "CA").put("status", "active")
            .put("startDate", "2020-01")
            .put("review", validReviewBody(company, title));
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
            .put("authorType", "anonymous").put("author", "AnonRater77");
    }

    private static org.werkpages.service.SubmissionContext submission(JsonObject body) {
        return org.werkpages.service.SubmissionContext.of(
            new org.werkpages.repository.GeoObservation("Canada", "Ontario", "Toronto"), body);
    }

    private static void insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery("INSERT INTO users(auth0_id, username, email) VALUES ($1,$2,$3)")
            .execute(Tuple.of(auth0Id, username, username + "@example.com")));
    }

    private static long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies (name, status) VALUES ($1, 'approved') RETURNING id")
            .execute(Tuple.of(name))).iterator().next().getLong("id");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}

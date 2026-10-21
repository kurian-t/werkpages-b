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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The location-suggestion response contract, with the LOCAL INDEX ACTUALLY ENABLED.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>The local corpus index was wired in at the top of {@code suggestCompanyLocations} and returned
 * its results INSTEAD of everything else. That silently dropped the two Postgres tiers: the
 * buildings this company already has, and the geography somebody already confirmed for it.
 *
 * <p>Dropping the first is the damaging one. A {@code company_locations} row carries the
 * {@code companyLocationId} the frontend sends back on selection, and it is how an existing
 * building gets REUSED. With it missing, the branch somebody already created stopped being offered,
 * so the next person to name it created a second row for the same address, splitting that store's
 * ratings across two entries that look identical on the page.
 *
 * <h2>Why this could not be caught before</h2>
 *
 * <p>The pool was a {@code static final} read from the environment during class initialisation, so
 * no test could switch the local path on. It is now resolved on first use and honours a system
 * property, which is what lets this test point it at a fixture release.
 */
@Testcontainers
class LocationSuggestionContractIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static ManagerService service;
    static Path corpusRoot;

    @BeforeAll
    static void setUpAll() throws Exception {
        /*
          Set BEFORE the first call that resolves the pool. The fixture release is a real DuckDB
          index over real Parquet, small enough to build per run.
        */
        corpusRoot = Files.createTempDirectory("contract-corpus");
        FixtureCorpus.publish(corpusRoot);
        System.setProperty("LOCATION_LOCAL_SEARCH", "true");
        System.setProperty("LOCAL_CORPUS_ROOT", corpusRoot.toString());
        System.setProperty("LOCAL_CORPUS_POOL", "2");
        System.setProperty("LOCAL_CORPUS_THREADS", "1");
        System.setProperty("LOCAL_CORPUS_MEMORY", "256MB");

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
        System.clearProperty("LOCATION_LOCAL_SEARCH");
        System.clearProperty("LOCAL_CORPUS_ROOT");
        System.clearProperty("LOCAL_CORPUS_POOL");
        System.clearProperty("LOCAL_CORPUS_THREADS");
        System.clearProperty("LOCAL_CORPUS_MEMORY");
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, companies, users, company_locations CASCADE").execute());
    }

    @Test
    @DisplayName("an existing company_locations row is still offered, with its companyLocationId")
    void postgresTiersSurviveTheLocalIndex() throws Exception {
        long companyId = insertCompany("Laguna Motors");
        long locationId = insertLocation(companyId, "Laguna Motors Elk Grove",
                                         "8581 Laguna Grove Dr", "Elk Grove", "CA", "United States");

        JsonArray out = await(service.suggestCompanyLocations(
            companyId, "Laguna Motors", "laguna grove", "United States", null));

        JsonObject existing = findWithLocationId(out, locationId);
        assertNotNull(existing,
            "the company's existing location was not offered. The local index has replaced the "
            + "Postgres tiers, so an already-created building can no longer be reused and the next "
            + "contributor will create a duplicate of it. Response was: " + out.encode());

        assertEquals("place", existing.getString("kind"));
        assertEquals(locationId, existing.getLong("companyLocationId"));
        assertNotNull(existing.getString("label"));

        // It leads: a building this company already has beats anything the corpus suggests.
        assertEquals(locationId, out.getJsonObject(0).getLong("companyLocationId"),
            "the existing location should rank first, got: " + out.encode());
    }

    @Test
    @DisplayName("every exact suggestion is selectable: a companyLocationId or a whole corpusPlace")
    void everyExactSuggestionIsSelectable() throws Exception {
        long companyId = insertCompany("Laguna Motors");
        insertLocation(companyId, "Laguna Motors Elk Grove", "8581 Laguna Grove Dr",
                       "Elk Grove", "CA", "United States");

        JsonArray out = await(service.suggestCompanyLocations(
            companyId, "Laguna Motors", "laguna grove", "United States", null));

        assertFalse(out.isEmpty(), "no suggestions at all");
        for (int i = 0; i < out.size(); i++) {
            JsonObject item = out.getJsonObject(i);
            if ("geo".equals(item.getString("kind"))) continue;    // coarse geography carries neither

            boolean persisted = item.getLong("companyLocationId") != null;
            JsonObject corpusPlace = item.getJsonObject("corpusPlace");

            /*
              THE DEFECT THIS GUARDS.

              The local index emitted a bare `sourcePlaceId` instead of a `corpusPlace` object. The
              client reads corpusPlace from the suggestion and sends it back on submit, where
              CorpusPlace.fromBody promotes it into a real location. With only an id present, the
              submission carried `exact` precision and nothing to resolve, so a person could search
              their exact address, pick it from the list, submit, and have the address silently
              dropped while the form looked like it had worked.

              So exactly one of the two must be present: an id for a building already in the
              database, or the whole place for one that is not yet.
            */
            assertTrue(persisted ^ (corpusPlace != null),
                "an exact suggestion must carry either a companyLocationId or a corpusPlace, "
                + "never both and never neither: " + item.encode());

            if (corpusPlace != null) {
                // fromBody returns null without these two, and validate() rejects without city
                // and countryCode, which would reject the submission rather than the suggestion.
                assertNotNull(corpusPlace.getString("sourcePlaceId"), item.encode());
                assertNotNull(corpusPlace.getString("name"), item.encode());
                assertNotNull(corpusPlace.getString("city"), item.encode());
                assertEquals("US", corpusPlace.getString("countryCode"), item.encode());
            }
        }
    }

    @Test
    @DisplayName("a company filter is still honoured: another company's location is not offered")
    void companyFilterStillApplies() throws Exception {
        long mine   = insertCompany("Laguna Motors");
        long theirs = insertCompany("Grove Dentistry");
        long otherLocation = insertLocation(theirs, "Grove Dentistry", "8581 Laguna Grove Dr",
                                            "Elk Grove", "CA", "United States");

        JsonArray out = await(service.suggestCompanyLocations(
            mine, "Laguna Motors", "laguna grove", "United States", null));

        assertNull(findWithLocationId(out, otherLocation),
            "another company's persisted location leaked into these suggestions: " + out.encode());
    }

    @Test
    @DisplayName("a short query is refused before any search runs")
    void shortQueriesAreRefused() throws Exception {
        long companyId = insertCompany("Laguna Motors");
        assertTrue(await(service.suggestCompanyLocations(companyId, "Laguna Motors", "l", "United States", null)).isEmpty());
        assertTrue(await(service.suggestCompanyLocations(companyId, "Laguna Motors", "", "United States", null)).isEmpty());
        assertTrue(await(service.suggestCompanyLocations(companyId, "Laguna Motors", null, "United States", null)).isEmpty());
    }


    @Test
    @DisplayName("a local-index place reaches the response and is selectable")
    void localIndexPlacesReachTheResponse() throws Exception {
        /*
          THE GAP THAT LET TWO DEFECTS THROUGH.

          Every other case here seeds a company_locations row, which dominates the list, so the
          assertions passed whether or not the local index contributed anything at all. Two separate
          faults hid in that gap: the local path emitted finished suggestion objects into a merge
          that reads raw rows, so every local result was silently skipped; and before that it
          emitted a bare sourcePlaceId instead of the corpusPlace that makes a suggestion
          selectable.

          So this company has NO stored locations. Anything in the response came from the index.
        */
        long companyId = insertCompany("Independent Motors");

        JsonArray out = await(service.suggestCompanyLocations(
            companyId, "Independent Motors", "laguna grove", "United States", null));

        assertFalse(out.isEmpty(),
            "the local index contributed nothing to the response. Its results are being dropped "
            + "somewhere between the index and the merge.");

        JsonObject subaru = null;
        for (int i = 0; i < out.size(); i++) {
            JsonObject place = out.getJsonObject(i).getJsonObject("corpusPlace");
            if (place != null && "p-subaru-eg".equals(place.getString("sourcePlaceId"))) {
                subaru = out.getJsonObject(i);
            }
        }
        assertNotNull(subaru,
            "the fixture's Elk Grove Subaru did not arrive with a corpusPlace, so picking it would "
            + "submit `exact` precision with nothing to resolve and the address would be dropped. "
            + "Response was: " + out.encode());

        // The shape the client and CorpusPlace.fromBody both rely on.
        assertEquals("place", subaru.getString("kind"));
        assertEquals("Elk Grove Subaru", subaru.getString("label"));
        assertEquals("exact", subaru.getString("precision"));
        assertNull(subaru.getLong("companyLocationId"), "not in the database yet");

        JsonObject place = subaru.getJsonObject("corpusPlace");
        assertEquals("Elk Grove Subaru", place.getString("name"));
        assertEquals("8581 Laguna Grove Dr", place.getString("street"));
        assertEquals("Elk Grove", place.getString("city"));
        assertEquals("US", place.getString("countryCode"));
        // fromBody needs name and sourcePlaceId; validate() needs city and countryCode.
        assertNull(org.werkpages.service.CorpusPlace.fromBody(new JsonObject().put("corpusPlace", place))
                       .validate(),
            "the promoted place must pass its own validation, or the submission is refused");
    }


    // ── Selection and persistence: the Elk Grove case, end to end ────────────

    @Test
    @DisplayName("picking the Elk Grove suggestion persists the address exactly once")
    void elkGroveSelectionPersistsOnceAndIsReused() throws Exception {
        /*
          THE WHOLE POINT OF THE FEATURE, end to end on a disposable database.

          The reported bug was that typing "8581 Laguna Grove Drive, Elk Grove, California"
          produced nothing. Finding it again is only half the job: the suggestion has to be
          SELECTABLE, and selecting it has to store the address - once.

          "Once" is the part worth testing. A corpus place is promoted by
          CompanyLocationRepository.findOrCreate, keyed on (company_id, source, source_place_id).
          If that key missed, two people naming the same building would create two rows, and that
          store's ratings would split across two entries that look identical on the page. So the
          second submission must REUSE the first row rather than add one.
        */
        long companyId = insertCompany("Elk Grove Subaru");

        // 1. Search, exactly as the form does.
        JsonArray suggestions = await(service.suggestCompanyLocations(
            companyId, "Elk Grove Subaru", "8581 Laguna Grove Drive, Elk Grove, California",
            "United States", null));

        JsonObject picked = null;
        for (int i = 0; i < suggestions.size(); i++) {
            JsonObject place = suggestions.getJsonObject(i).getJsonObject("corpusPlace");
            if (place != null && "p-subaru-eg".equals(place.getString("sourcePlaceId"))) {
                picked = suggestions.getJsonObject(i);
            }
        }
        assertNotNull(picked, "the address was not offered at all: " + suggestions.encode());

        // 2. Submit it, the way declaredPayload does for an exact pick with no companyLocationId:
        //    precision "exact" plus the whole corpusPlace object.
        insertUser("auth0|elk-1", "ElkOne");
        JsonObject body = validCreateManagerBody("Dana Grove", "Elk Grove Subaru", "Service Manager")
            .put("declaredPrecision", "exact")
            .put("corpusPlace", picked.getJsonObject("corpusPlace"));

        Row created = await(service.createManager("auth0|elk-1", body, null, submission(body)));
        assertNotNull(created, "the submission was rejected");

        // 3. The address is stored, once, against this company.
        RowSet<Row> rows = await(pool.preparedQuery("""
                SELECT id, street, city, state, country, source, source_place_id, status
                  FROM company_locations WHERE company_id = $1
                """).execute(Tuple.of(companyId)));
        assertEquals(1, rows.size(), "expected exactly one stored location, got " + rows.size());
        Row stored = rows.iterator().next();
        assertEquals("8581 Laguna Grove Dr", stored.getString("street"));
        assertEquals("Elk Grove", stored.getString("city"));
        assertEquals("p-subaru-eg", stored.getString("source_place_id"));
        long firstId = stored.getLong("id");

        // 4. A SECOND person picks the same building: it must be reused, not duplicated.
        insertUser("auth0|elk-2", "ElkTwo");
        JsonObject second = validCreateManagerBody("Sam Laguna", "Elk Grove Subaru", "Parts Manager")
            .put("declaredPrecision", "exact")
            .put("corpusPlace", picked.getJsonObject("corpusPlace"));
        await(service.createManager("auth0|elk-2", second, null, submission(second)));

        RowSet<Row> after = await(pool.preparedQuery(
                "SELECT id FROM company_locations WHERE company_id = $1").execute(Tuple.of(companyId)));
        assertEquals(1, after.size(),
            "the same building was stored twice - findOrCreate did not match on "
            + "(company_id, source, source_place_id), which splits one store's ratings in two");
        assertEquals(firstId, after.iterator().next().getLong("id"), "a different row was used");

        // 5. And now that it IS in the database, the search offers it by id rather than as a
        //    corpus place, which is what lets the next person reuse it without promotion.
        JsonArray again = await(service.suggestCompanyLocations(
            companyId, "Elk Grove Subaru", "laguna grove", "United States", null));
        boolean offeredById = false;
        for (int i = 0; i < again.size(); i++) {
            Long id = again.getJsonObject(i).getLong("companyLocationId");
            if (id != null && id == firstId) offeredById = true;
        }
        assertTrue(offeredById,
            "the stored location was not offered back by id, so the next contributor would be "
            + "promoted a fresh corpus place instead of reusing it: " + again.encode());
    }

    @Test
    @DisplayName("typing any country's own name offers that country, not the places inside it")
    void aCountryNameOffersTheCountry() throws Exception {
        /*
          REPORTED FROM THE BROWSER, with the country already selected on the form.

          Typing "United States" returned Alabama, Arizona, British Columbia, California, Colorado,
          Connecticut, England, Florida - alphabetical, eight of them, every one labelled
          "<state>, United States" - and the United States itself was nowhere in the list.

          Cause: this tier matched declared_country on EVERY row, so a country name matched every
          location confirmed inside that country. The tier ranks ahead of the corpus, so those rows
          filled the list and pushed the country off the end.

          Tested as a CLASS rather than for the one country reported: the defect is in the
          predicate, so every country with confirmed locations inside it had it.
        */
        long companyId = insertCompany("Continental Corp");
        /*
          NINE PER COUNTRY, deliberately more than the suggestion limit of eight.

          The list is scoped to the country being typed, so only that country's confirmed rows
          compete for the eight seats. With three or four of them the country row still fits and
          the test passes whether or not the bug is present - which an earlier version of this
          test did, silently. Filling the limit is what reproduces the report.
        */
        String[][] confirmed = {
            {"Canada", "Ontario", "Toronto"},
            {"Canada", "Ontario", "Montreal"},
            {"Canada", "Ontario", "Calgary"},
            {"Canada", "Ontario", "Halifax"},
            {"Canada", "Ontario", "Regina"},
            {"Canada", "Ontario", "Victoria"},
            {"Canada", "Ontario", "Windsor"},
            {"Canada", "Ontario", "Kelowna"},
            {"Canada", "Ontario", "Guelph"},
            {"United States", "Alabama", "Birmingham"},
            {"United States", "Alabama", "Phoenix"},
            {"United States", "Alabama", "Fresno"},
            {"United States", "Alabama", "Denver"},
            {"United States", "Alabama", "Tampa"},
            {"United States", "Alabama", "Atlanta"},
            {"United States", "Alabama", "Boise"},
            {"United States", "Alabama", "Peoria"},
            {"United States", "Alabama", "Akron"},
            {"United Kingdom", "England", "Leeds"},
            {"United Kingdom", "England", "Glasgow"},
            {"United Kingdom", "England", "Cardiff"},
            {"United Kingdom", "England", "Bristol"},
            {"United Kingdom", "England", "Norwich"},
            {"United Kingdom", "England", "Dundee"},
            {"United Kingdom", "England", "Swansea"},
            {"United Kingdom", "England", "Exeter"},
            {"United Kingdom", "England", "Hull"},
            {"India", "Maharashtra", "Pune"},
            {"India", "Maharashtra", "Mysore"},
            {"India", "Maharashtra", "Surat"},
            {"India", "Maharashtra", "Indore"},
            {"India", "Maharashtra", "Kochi"},
            {"India", "Maharashtra", "Nagpur"},
            {"India", "Maharashtra", "Patna"},
            {"India", "Maharashtra", "Bhopal"},
            {"India", "Maharashtra", "Rajkot"},
            {"Australia", "Victoria", "Geelong"},
            {"Australia", "Victoria", "Cairns"},
            {"Australia", "Victoria", "Hobart"},
            {"Australia", "Victoria", "Darwin"},
            {"Australia", "Victoria", "Newcastle"},
            {"Australia", "Victoria", "Ballarat"},
            {"Australia", "Victoria", "Bendigo"},
            {"Australia", "Victoria", "Mackay"},
            {"Australia", "Victoria", "Albury"},
            {"Germany", "Bavaria", "Augsburg"},
            {"Germany", "Bavaria", "Darmstadt"},
            {"Germany", "Bavaria", "Leipzig"},
            {"Germany", "Bavaria", "Bonn"},
            {"Germany", "Bavaria", "Kassel"},
            {"Germany", "Bavaria", "Erfurt"},
            {"Germany", "Bavaria", "Mainz"},
            {"Germany", "Bavaria", "Trier"},
            {"Germany", "Bavaria", "Jena"},
        };
        for (String[] where : confirmed) insertManagerDeclaring(companyId, where[0], where[1], where[2]);

        for (String country : new String[]{
                "Canada", "United States", "United Kingdom", "India", "Australia", "Germany"}) {
            JsonArray out = await(service.suggestCompanyLocations(
                companyId, "Continental Corp", country, country, null));
            boolean offered = false;
            for (int i = 0; i < out.size(); i++) {
                if (country.equalsIgnoreCase(out.getJsonObject(i).getString("label"))) offered = true;
            }
            assertTrue(offered,
                "typing \"" + country + "\" did not offer " + country + " itself; the confirmed "
                + "locations inside it crowded the list out: " + out.encode());
        }
    }

    @Test
    @DisplayName("a country-precision row with EMPTY STRING city and state still matches")
    void emptyStringsCountAsAbsent() throws Exception {
        /*
          The same bug in the opposite direction, and the reason the predicate does not test
          IS NULL.

          At country precision the validator requires a country and checks nothing about state and
          city, and no constraint forbids an empty string, so a client sending "" stores "". Such a
          row IS a country-level answer somebody confirmed, and an IS NULL test would silently stop
          offering it.
        */
        long companyId = insertCompany("Blankfield Ltd");
        await(pool.preparedQuery("""
                INSERT INTO managers (name, company, title, image, country, status, approval_status,
                                      company_id, slug, declared_country, declared_state,
                                      declared_city, declared_precision)
                VALUES ('Blank Coarse', 'Blankfield Ltd', 'Manager', 'img', 'Japan', 'active',
                        'approved', $1, 'm-blank-coarse', 'Japan', '', '   ', 'country')
                """).execute(Tuple.of(companyId)));

        JsonArray out = await(service.suggestCompanyLocations(
            companyId, "Blankfield Ltd", "Japan", "Japan", null));
        boolean offered = false;
        for (int i = 0; i < out.size(); i++) {
            if ("Japan".equalsIgnoreCase(out.getJsonObject(i).getString("label"))) offered = true;
        }
        assertTrue(offered,
            "a country-precision row whose city and state are empty strings rather than NULL "
            + "stopped being offered: " + out.encode());
    }

    @Test
    @DisplayName("and searching a state or a city still finds the confirmed rows inside it")
    void stateAndCitySearchStillWork() throws Exception {
        /*
          The guard against over-correcting. Narrowing the country match must not narrow the city
          and state matches, which are the ones this tier exists for - they are how a location
          somebody already confirmed gets reused instead of recreated.
        */
        long companyId = insertCompany("Continental Corp");
        insertManagerDeclaring(companyId, "Canada", "Ontario", "Toronto");
        insertManagerDeclaring(companyId, "Canada", "Quebec", "Montreal");

        JsonArray byCity = await(service.suggestCompanyLocations(
            companyId, "Continental Corp", "Toronto", "Canada", null));
        assertTrue(countryLabels(byCity).stream().anyMatch(l -> l != null && l.startsWith("Toronto, Ontario")),
            "a city search no longer finds the confirmed row: " + byCity.encode());

        JsonArray byState = await(service.suggestCompanyLocations(
            companyId, "Continental Corp", "Quebec", "Canada", null));
        assertTrue(countryLabels(byState).stream().anyMatch(l -> l != null && l.contains("Quebec")),
            "a state search no longer finds the confirmed row: " + byState.encode());
    }

    private static java.util.List<String> countryLabels(JsonArray out) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < out.size(); i++) names.add(out.getJsonObject(i).getString("label"));
        return names;
    }

    private static void insertManagerDeclaring(long companyId, String country, String state,
                                               String city) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO managers (name, company, title, image, country, status, approval_status,
                                      company_id, slug, declared_country, declared_state,
                                      declared_city, declared_precision)
                VALUES ($1, 'Continental Corp', 'Manager', 'img', $2, 'active', 'approved',
                        $3, $4, $5, $6, $7, 'city')
                """).execute(Tuple.of("M " + city, country, companyId,
                    "m-" + city.toLowerCase() + "-" + country.hashCode(), country, state, city)));
    }

    private static JsonObject validCreateManagerBody(String name, String company, String title) {
        return new JsonObject()
            .put("name", name).put("company", company).put("title", title)
            .put("image", "img").put("country", "US").put("status", "active")
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
            .put("authorType", "anonymous").put("author", "AnonRater42");
    }

    private static void insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery("INSERT INTO users(auth0_id, username, email) VALUES ($1,$2,$3)")
            .execute(Tuple.of(auth0Id, username, username + "@example.com")));
    }

    private static org.werkpages.service.SubmissionContext submission(JsonObject body) {
        return org.werkpages.service.SubmissionContext.of(
            new org.werkpages.repository.GeoObservation("United States", "California", "Elk Grove"), body);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static JsonObject findWithLocationId(JsonArray out, long id) {
        for (int i = 0; i < out.size(); i++) {
            JsonObject item = out.getJsonObject(i);
            Long got = item.getLong("companyLocationId");
            if (got != null && got == id) return item;
        }
        return null;
    }

    private static long insertCompany(String name) throws Exception {
        Row r = await(pool.preparedQuery(
                "INSERT INTO companies (name, status) VALUES ($1, 'approved') RETURNING id")
            .execute(Tuple.of(name))).iterator().next();
        return r.getLong("id");
    }

    private static long insertLocation(long companyId, String displayName, String street,
                                       String city, String state, String country) throws Exception {
        Row r = await(pool.preparedQuery("""
                INSERT INTO company_locations
                       (company_id, display_name, street, city, state, country, status)
                VALUES ($1, $2, $3, $4, $5, $6, 'active')
                RETURNING id
                """).execute(Tuple.of(companyId, displayName, street, city, state, country)))
            .iterator().next();
        return r.getLong("id");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}

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
import org.junit.jupiter.api.Test;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.MergeSuggestionsRepository;
import org.werkpages.repository.NotificationRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.AdminService;
import org.werkpages.service.ServiceException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class AdminServiceCoverageIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool                    pool;
    static AdminService            service;
    static UserRepository          userRepo;
    static ManagerRepository       managerRepo;
    static CompanyRepository       companyRepo;
    static MergeSuggestionsRepository mergeSuggestionsRepo;

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

        pool                 = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));
        userRepo             = new UserRepository(pool);
        managerRepo          = new ManagerRepository(pool);
        companyRepo          = new CompanyRepository(pool);
        mergeSuggestionsRepo = new MergeSuggestionsRepository(pool);
        ReviewRepository       reviewRepo = new ReviewRepository(pool);
        EditRepository         editRepo   = new EditRepository(pool);
        NotificationRepository notifRepo  = new NotificationRepository(pool);
        service = new AdminService(userRepo, managerRepo, reviewRepo, editRepo, notifRepo,
                                   companyRepo, mergeSuggestionsRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE managers, users CASCADE").execute());
        await(pool.query("TRUNCATE merge_suggestions CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getGhostManagers — lines 82-100
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getGhostManagers_nonAdmin_returns403() throws Exception {
        String userAuth = insertUser("auth0|ghost-user01", "GhostUser01", "user");
        ServiceException ex = assertServiceException(service.getGhostManagers(userAuth, 10, 0));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void getGhostManagers_noGhosts_returnsEmpty() throws Exception {
        String adminAuth = insertUser("auth0|ghost-admin01", "GhostAdmin01", "admin");
        insertApprovedManager("Approved Manager", "Corp", "Title");

        JsonObject result = await(service.getGhostManagers(adminAuth, 10, 0));
        assertEquals(0, result.getJsonArray("data").size());
        assertEquals(10, result.getInteger("limit"));
        assertEquals(0, result.getInteger("offset"));
    }

    @Test
    void getGhostManagers_withGhosts_returnsGhostManagers() throws Exception {
        String adminAuth = insertUser("auth0|ghost-admin02", "GhostAdmin02", "admin");
        insertGhostManager("Ghost Alice", "GhostCorp", "Director");

        JsonObject result = await(service.getGhostManagers(adminAuth, 10, 0));
        assertEquals(1, result.getJsonArray("data").size());
        JsonObject m = result.getJsonArray("data").getJsonObject(0);
        assertEquals("Ghost Alice", m.getString("name"));
        assertEquals("GhostCorp", m.getString("company"));
        assertNotNull(m.getString("createdAt"));
    }

    @Test
    void getGhostManagers_paginationRespected() throws Exception {
        String adminAuth = insertUser("auth0|ghost-admin03", "GhostAdmin03", "admin");
        insertGhostManager("Ghost One", "Corp", "Title");
        insertGhostManager("Ghost Two", "Corp", "Title");
        insertGhostManager("Ghost Three", "Corp", "Title");

        JsonObject page1 = await(service.getGhostManagers(adminAuth, 2, 0));
        JsonObject page2 = await(service.getGhostManagers(adminAuth, 2, 2));
        assertEquals(2, page1.getJsonArray("data").size());
        assertEquals(1, page2.getJsonArray("data").size());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // markGhostReviewed — lines 103-113
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void markGhostReviewed_notFound_returnsSuccessFalse() throws Exception {
        String adminAuth = insertUser("auth0|ghostrev-admin01", "GhostRevAdmin01", "admin");

        JsonObject result = await(service.markGhostReviewed(adminAuth, 999999L));
        assertFalse(result.getBoolean("success"));
        assertEquals("Ghost manager not found", result.getString("message"));
    }

    @Test
    void markGhostReviewed_ghostManager_setsApproved() throws Exception {
        String adminAuth = insertUser("auth0|ghostrev-admin02", "GhostRevAdmin02", "admin");
        long managerId = insertGhostManager("Ghost Bob", "Corp", "Engineer");

        JsonObject result = await(service.markGhostReviewed(adminAuth, managerId));
        assertTrue(result.getBoolean("success"));

        String status = await(pool
            .preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("approval_status")));
        assertEquals("approved", status);
    }

    @Test
    void markGhostReviewed_nonGhostManager_returnsSuccessFalse() throws Exception {
        String adminAuth = insertUser("auth0|ghostrev-admin03", "GhostRevAdmin03", "admin");
        long managerId = insertApprovedManager("Already Approved", "Corp", "Title");

        JsonObject result = await(service.markGhostReviewed(adminAuth, managerId));
        assertFalse(result.getBoolean("success"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // approvePendingManager with search_created_by — line 249 area
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void approvePendingManager_searchCreatedManager_doesNotSendNotification() throws Exception {
        String adminAuth   = insertUser("auth0|scb-admin01", "ScbAdmin01", "admin");
        String userAuth    = insertUser("auth0|scb-user01",  "ScbUser01",  "user");
        UUID   userId      = findUserId(userAuth);

        long managerId = insertSearchCreatedPendingManager("Search Created", "Corp", "Title", userId);

        await(service.approvePendingManager(adminAuth, managerId, null));
        Thread.sleep(300);

        long notifCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND type = $2")
            .execute(Tuple.of(userId, "manager_approved"))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0L, notifCount, "Search-created managers must NOT send approval notification");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // updateManagerLogo — line 331-332
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void updateManagerLogo_updatesLogoUrl() throws Exception {
        long managerId = insertApprovedManager("Logo Manager", "Corp", "Title");

        await(service.updateManagerLogo(managerId, "https://cdn.example.com/logo.png"));

        String logoUrl = await(pool
            .preparedQuery("SELECT company_logo_url FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("company_logo_url")));
        assertEquals("https://cdn.example.com/logo.png", logoUrl);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // banUser — reason too long — line 399
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void banUser_reasonTooLong_returns400() throws Exception {
        String adminAuth = insertUser("auth0|ban-admin01", "BanAdmin01", "admin");
        String reason    = "x".repeat(501);
        ServiceException ex = assertServiceException(service.banUser(adminAuth, UUID.randomUUID(), reason));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void banUser_blankReason_returns400() throws Exception {
        String adminAuth = insertUser("auth0|ban-admin02", "BanAdmin02", "admin");
        ServiceException ex = assertServiceException(service.banUser(adminAuth, UUID.randomUUID(), "   "));
        assertEquals(400, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getMergeSuggestions — lines 525-556
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getMergeSuggestions_nonAdmin_returns403() throws Exception {
        String userAuth = insertUser("auth0|merge-sug-user01", "MergeSugUser01", "user");
        ServiceException ex = assertServiceException(service.getMergeSuggestions(userAuth, 10, 0));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void getMergeSuggestions_noSuggestions_returnsEmptyWithTotal0() throws Exception {
        String adminAuth = insertUser("auth0|merge-sug-admin01", "MergeSugAdmin01", "admin");

        JsonObject result = await(service.getMergeSuggestions(adminAuth, 10, 0));
        assertEquals(0, result.getJsonArray("data").size());
        assertEquals(0, result.getInteger("total"));
    }

    @Test
    void getMergeSuggestions_withPendingSuggestion_returnsIt() throws Exception {
        String adminAuth = insertUser("auth0|merge-sug-admin02", "MergeSugAdmin02", "admin");
        long managerA = insertApprovedManager("John Smith", "Corp", "Engineer");
        long managerB = insertApprovedManager("Jon Smith",  "Corp", "Engineer");

        await(mergeSuggestionsRepo.upsert(managerA, managerB, "LIKELY_SAME",
                "Very similar names", 0, 0));

        JsonObject result = await(service.getMergeSuggestions(adminAuth, 10, 0));
        assertEquals(1, result.getJsonArray("data").size());
        assertEquals(1, result.getInteger("total"));

        JsonObject suggestion = result.getJsonArray("data").getJsonObject(0);
        assertEquals("LIKELY_SAME", suggestion.getString("confidence"));
        assertEquals("pending", suggestion.getString("status"));
        assertNotNull(suggestion.getJsonObject("managerA"));
        assertNotNull(suggestion.getJsonObject("managerB"));
        assertEquals(managerA, suggestion.getJsonObject("managerA").getLong("id"));
        assertEquals(managerB, suggestion.getJsonObject("managerB").getLong("id"));
    }

    @Test
    void getMergeSuggestions_paginationRespected() throws Exception {
        String adminAuth = insertUser("auth0|merge-sug-admin03", "MergeSugAdmin03", "admin");
        long m1 = insertApprovedManager("Alice A", "Corp", "E");
        long m2 = insertApprovedManager("Alice B", "Corp", "E");
        long m3 = insertApprovedManager("Alice C", "Corp", "E");
        long m4 = insertApprovedManager("Alice D", "Corp", "E");

        await(mergeSuggestionsRepo.upsert(m1, m2, "SAME", "r1", 0, 0));
        await(mergeSuggestionsRepo.upsert(m1, m3, "SAME", "r2", 0, 0));
        await(mergeSuggestionsRepo.upsert(m1, m4, "SAME", "r3", 0, 0));

        JsonObject page1 = await(service.getMergeSuggestions(adminAuth, 2, 0));
        JsonObject page2 = await(service.getMergeSuggestions(adminAuth, 2, 2));

        assertEquals(2, page1.getJsonArray("data").size());
        assertEquals(1, page2.getJsonArray("data").size());
        assertEquals(3, page1.getInteger("total"));
    }

    @Test
    void getMergeSuggestions_dismissedSuggestion_notReturnedInPending() throws Exception {
        String adminAuth = insertUser("auth0|merge-sug-admin04", "MergeSugAdmin04", "admin");
        long managerA = insertApprovedManager("Sam Smith", "Corp", "Engineer");
        long managerB = insertApprovedManager("Sam Smyth", "Corp", "Engineer");

        await(mergeSuggestionsRepo.upsert(managerA, managerB, "LIKELY_SAME", "reason", 0, 0));

        JsonObject before = await(service.getMergeSuggestions(adminAuth, 10, 0));
        assertEquals(1, before.getJsonArray("data").size());
        long suggestionId = before.getJsonArray("data").getJsonObject(0).getLong("id");

        await(service.dismissMergeSuggestion(adminAuth, suggestionId));

        JsonObject after = await(service.getMergeSuggestions(adminAuth, 10, 0));
        assertEquals(0, after.getJsonArray("data").size());
        assertEquals(0, after.getInteger("total"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // dismissMergeSuggestion — lines 560-563
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void dismissMergeSuggestion_nonAdmin_returns403() throws Exception {
        String userAuth = insertUser("auth0|dismiss-user01", "DismissUser01", "user");
        ServiceException ex = assertServiceException(service.dismissMergeSuggestion(userAuth, 1L));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void dismissMergeSuggestion_success_updatesStatusToDismissed() throws Exception {
        String adminAuth = insertUser("auth0|dismiss-admin01", "DismissAdmin01", "admin");
        long managerA = insertApprovedManager("Tom A", "Corp", "E");
        long managerB = insertApprovedManager("Tom B", "Corp", "E");

        await(mergeSuggestionsRepo.upsert(managerA, managerB, "LIKELY_SAME", "reason", 0, 0));

        JsonObject list = await(service.getMergeSuggestions(adminAuth, 10, 0));
        long suggestionId = list.getJsonArray("data").getJsonObject(0).getLong("id");

        JsonObject result = await(service.dismissMergeSuggestion(adminAuth, suggestionId));
        assertTrue(result.getBoolean("success"));

        String status = await(pool
            .preparedQuery("SELECT status FROM merge_suggestions WHERE id = $1")
            .execute(Tuple.of(suggestionId))
            .map(rs -> rs.iterator().next().getString("status")));
        assertEquals("dismissed", status);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // requireAdminPublic — line 67
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void requireAdminPublic_nullAuth0Id_returns401() {
        ServiceException ex = assertServiceException(service.requireAdminPublic(null));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void requireAdminPublic_unknownAuth0Id_returns401() {
        ServiceException ex = assertServiceException(service.requireAdminPublic("auth0|nobody"));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void requireAdminPublic_adminUser_returnsAdminUuid() throws Exception {
        String adminAuth = insertUser("auth0|pub-admin01", "PubAdmin01", "admin");
        UUID adminId = await(service.requireAdminPublic(adminAuth));
        assertNotNull(adminId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getCountryStats: every live manager, and only genuine ratings
    // ══════════════════════════════════════════════════════════════════════════

    /*
      THE COUNTRY BREAKDOWN COUNTS MANAGERS, NOT PROVENANCE.

      These tests asserted the opposite until 2026-11-24, under a banner reading "bot filter": a
      manager carrying an external_id was dropped from "Managers by Country" so that the table
      agreed with the "User Submitted" tile.

      What that cost in production: the table read 878 American managers while 5,605 American
      profiles were live, public and reachable from search. 4,727 of 7,090 live managers carry an
      external_id, and nothing a visitor touches filters on it. countApproved() and every search
      query select on approval_status alone, so the only country breakdown in the product
      disagreed with every page anybody can open, by a factor of six.

      The scraped against people-added split is still wanted, and getStats still reports it
      through realManagers and scrapedManagers. It is simply not the question a country chart
      answers.

      The ratings half changed for a different reason. It hand-rolled its own idea of a countable
      rating as "weight = FALSE AND deleted_at IS NULL", which counts held and rejected ones.
      ReviewSql.genuine exists so that predicate is written once; this query now uses it.
    */

    /** The count for one country out of one of the two arrays, or 0 when absent. */
    private static long countryCount(JsonObject stats, String arrayKey, String country) {
        return stats.getJsonArray(arrayKey).stream()
            .map(o -> (JsonObject) o)
            .filter(e -> country.equals(e.getString("country")))
            .mapToLong(e -> e.getLong("count"))
            .findFirst().orElse(0L);
    }

    @Test
    void getCountryStats_countsEveryLiveManagerWhateverItsOrigin() throws Exception {
        String adminAuth = insertUser("auth0|cs-admin01", "CsAdmin01", "admin");

        // Three from the import. The public directory serves these like any other profile.
        for (int i = 0; i < 3; i++) {
            await(pool.preparedQuery("""
                    INSERT INTO managers(name,company,title,image,status,approval_status,
                                        overall_rating,reviews_count,category_averages,country,external_id)
                    VALUES ($1,$2,$3,'img','active','approved',0,0,'{}','United States',$4)
                    """)
                .execute(Tuple.of("Scraped " + i, "ScrapedCo", "Role", "scraper_" + i)));
        }

        // One a person added.
        await(pool.preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages,country)
                VALUES ($1,$2,$3,'img','active','approved',0,0,'{}','United States')
                """)
            .execute(Tuple.of("Real Manager", "RealCo", "Director")));

        assertEquals(4L, countryCount(await(service.getCountryStats(adminAuth)), "managers", "United States"),
            "every live manager belongs in the breakdown: an external_id is provenance, not visibility");
    }

    @Test
    void getCountryStats_countsGhostAndSeededManagersToo() throws Exception {
        String adminAuth = insertUser("auth0|cs-admin02", "CsAdmin02", "admin");

        // A seeded profile. None exist in production, but the filter that hid them was the same one.
        await(pool.preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages,country,external_id)
                VALUES ($1,$2,$3,'img','active','ghost',0,0,'{}','Canada','seed_abc')
                """)
            .execute(Tuple.of("Ghost Manager", "GhostCo", "Manager")));

        await(pool.preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages,country,external_id)
                VALUES ($1,$2,$3,'img','active','approved',0,0,'{}','Canada','scraper_x')
                """)
            .execute(Tuple.of("Scraped Manager", "ScrapedCo", "Analyst")));

        await(pool.preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages,country)
                VALUES ($1,$2,$3,'img','active','approved',0,0,'{}','Canada')
                """)
            .execute(Tuple.of("Organic Manager", "OrganicCo", "Manager")));

        // Ghost is live and publicly visible, which is the whole point of the status.
        assertEquals(3L, countryCount(await(service.getCountryStats(adminAuth)), "managers", "Canada"),
            "approved and ghost are both live, whatever created them");
    }

    @Test
    void getCountryStats_ratingsCountGenuineRatingsOnAnyLiveManager() throws Exception {
        String adminAuth = insertUser("auth0|cs-admin03", "CsAdmin03", "admin");

        long scrapedId = insertLiveManager("Scraped With Rating", "ScrapedCo", "United States", "scraper_y");
        insertRating(scrapedId, "ScrapedCo", 3.0);

        long organicId = insertLiveManager("Organic With Rating", "OrganicCo", "United States", null);
        insertRating(organicId, "OrganicCo", 4.0);

        /*
          A rating left on an imported profile was written by a person about their manager. The
          profile's origin says nothing about the rating's, and excluding it understated every
          genuine opinion the product had collected on two thirds of its profiles.
        */
        assertEquals(2L, countryCount(await(service.getCountryStats(adminAuth)), "reviews", "United States"),
            "a genuine rating counts wherever it was left");
    }

    @Test
    void getCountryStats_ratingsExcludeExamplesHeldAndDeleted() throws Exception {
        String adminAuth = insertUser("auth0|cs-admin04", "CsAdmin04", "admin");

        long id = insertLiveManager("Mixed Bag", "MixedCo", "Malta", null);

        insertRating(id, "MixedCo", 4.0);                       // the only one that counts

        // A platform example. Shown on the profile as a demonstration, counted nowhere.
        await(pool.preparedQuery("""
                INSERT INTO reviews(manager_id,user_id,author,overall_rating,manager_company,manager_title,
                                    weight,origin)
                VALUES ($1,NULL,'Example',5.0,$2,'Manager',TRUE,'platform_example')
                """)
            .execute(Tuple.of(id, "MixedCo")));

        /*
          Held: the rating left when somebody adds a manager, withheld until the profile is
          approved. gate_eligible and live_since move with disposition, enforced by a CHECK.
        */
        await(pool.preparedQuery("""
                INSERT INTO reviews(manager_id,user_id,author,overall_rating,manager_company,manager_title,
                                    weight,origin,disposition,gate_eligible,live_since)
                VALUES ($1,NULL,'Pending',2.0,$2,'Manager',FALSE,'contributor','held',FALSE,NULL)
                """)
            .execute(Tuple.of(id, "MixedCo")));

        // Withdrawn by its author. Gone as far as every reader is concerned.
        await(pool.preparedQuery("""
                INSERT INTO reviews(manager_id,user_id,author,overall_rating,manager_company,manager_title,
                                    weight,origin,deleted_at)
                VALUES ($1,NULL,'Withdrawn',1.0,$2,'Manager',FALSE,'contributor',now())
                """)
            .execute(Tuple.of(id, "MixedCo")));

        assertEquals(1L, countryCount(await(service.getCountryStats(adminAuth)), "reviews", "Malta"),
            "only live, undeleted, person-written ratings count: ReviewSql.genuine, not a retyped predicate");
    }

    /** A live manager in one country, with {@code externalId} null for one a person added. */
    private long insertLiveManager(String name, String company, String country, String externalId)
            throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages,country,external_id)
                VALUES ($1,$2,'Manager','img','active','approved',0,0,'{}',$3,$4)
                RETURNING id
                """)
            .execute(Tuple.of(name, company, country, externalId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /** A genuine, live, person-written rating. Authorless but unweighted, so it stays a contributor row. */
    private void insertRating(long managerId, String company, double overall) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO reviews(manager_id,user_id,author,overall_rating,manager_company,manager_title,weight)
                VALUES ($1,NULL,'Anon',$2,$3,'Manager',FALSE)
                """)
            .execute(Tuple.of(managerId, overall, company)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * A merge suggestion says which URL each manager is on, and which one the merge produces.
     *
     * <p>The merge reclaims the plain name slug for the survivor and parks the retired duplicate's,
     * so the resulting address is settled before an admin clicks anything - and the payload carried
     * neither slug. The one visible consequence of a merge, the profile's URL changing, was
     * invisible at the moment of deciding.
     *
     * <p>Lives here rather than in AdminServiceIntegrationTest because this is the class that
     * wires a real MergeSuggestionsRepository; the other builds the service without one.
     */
    @Test
    void mergeSuggestions_reportTheSlugsAndTheResultingUrl() throws Exception {
        String adminAuth = insertUser("auth0|admin-slugview", "AdminSlugView", "admin");
        long first  = insertApprovedManager("Emma Davis", "Slugview Corp", "Engineering Manager");
        long second = insertApprovedManager("Emma Davis", "Other Corp", "Engineering Manager");
        long low = Math.min(first, second), high = Math.max(first, second);
        await(pool.preparedQuery("UPDATE managers SET slug = 'emma-davis-slugview-corp' WHERE id = $1")
            .execute(Tuple.of(low)).mapEmpty());
        await(pool.preparedQuery("UPDATE managers SET slug = 'emma-davis' WHERE id = $1")
            .execute(Tuple.of(high)).mapEmpty());
        await(pool.preparedQuery(
                "INSERT INTO merge_suggestions(manager_id_a, manager_id_b, confidence, reason, status) "
              + "VALUES ($1, $2, 'SAME', 'same person', 'pending')")
            .execute(Tuple.of(low, high)).mapEmpty());

        JsonObject out = await(service.getMergeSuggestions(adminAuth, 20, 0));
        JsonObject suggestion = out.getJsonArray("data").getJsonObject(0);

        assertNotNull(suggestion.getJsonObject("managerA").getString("slug"),
            "each side must show the URL it is on today");
        assertNotNull(suggestion.getJsonObject("managerB").getString("slug"));
        assertEquals("emma-davis", suggestion.getString("resultingSlug"),
            "and the payload must say where the survivor ends up, since the merge decides it");
    }

    private String insertUser(String auth0Id, String username, String role) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) " +
            "VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User", role)));
        return auth0Id;
    }

    private UUID findUserId(String auth0Id) throws Exception {
        return await(pool
            .preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private long insertApprovedManager(String name, String company, String title) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ($1,$2,$3,'img','active','approved',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertGhostManager(String name, String company, String title) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ($1,$2,$3,'img','active','ghost',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertSearchCreatedPendingManager(String name, String company, String title,
                                                    UUID searchCreatedByUserId) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,status,approval_status,overall_rating,reviews_count,category_averages," +
            "submitted_by,search_created_by_user_id) " +
            "VALUES ($1,$2,$3,'active','pending_approval',0,0,'{}',$4,$4) RETURNING id")
            .execute(Tuple.of(name, company, title, searchCreatedByUserId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static ServiceException assertServiceException(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            fail("Expected future to fail");
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ServiceException se) return se;
            fail("Expected ServiceException but got: " + e.getCause());
            return null;
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
            return null;
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}

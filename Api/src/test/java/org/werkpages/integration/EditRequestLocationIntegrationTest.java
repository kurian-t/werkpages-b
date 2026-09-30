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
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.MergeSuggestionsRepository;
import org.werkpages.repository.NotificationRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.AdminService;
import org.werkpages.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An edit request must be able to carry a whole location, and approving it must apply one.
 *
 * <h2>The bug</h2>
 *
 * <p>V70 added {@code new_declared_state}, {@code new_declared_city},
 * {@code new_declared_precision} and {@code new_company_location_id} to {@code manager_edits} so
 * that a location could be corrected the way a title can. <b>Nothing ever wrote or read them.</b>
 * The edit form asked only for a country, from a {@code <select>}, so there was nothing else to
 * send; the INSERT did not mention the columns, and approval applied {@code new_country} alone.
 *
 * <p>The user-visible consequence, once the form was changed to the shared LocationField: somebody
 * picks "Kitchener, Ontario", the form reports success, an admin approves it — and the manager
 * keeps whatever city it had. The answer was accepted, stored nowhere, and silently discarded.
 * That is worse than the dropdown it replaced, because it looks like it worked.
 *
 * <p>These tests run against a real database precisely because the failure was that columns went
 * unwritten. A service test with a mocked repository asserts the call was made and would have
 * passed throughout.
 */
@Testcontainers
class EditRequestLocationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool           pool;
    static ManagerService managerService;
    static AdminService   adminService;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        PgConnectOptions opts = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        pool = PgPool.pool(opts, new PoolOptions().setMaxSize(5));

        ManagerRepository          managerRepo = new ManagerRepository(pool);
        ReviewRepository           reviewRepo  = new ReviewRepository(pool);
        UserRepository             userRepo    = new UserRepository(pool);
        EditRepository             editRepo    = new EditRepository(pool);
        ReportRepository           reportRepo  = new ReportRepository(pool);
        NotificationRepository     notifRepo   = new NotificationRepository(pool);
        CompanyRepository          companyRepo = new CompanyRepository(pool);
        MergeSuggestionsRepository mergeRepo   = new MergeSuggestionsRepository(pool);

        managerService = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, pool);
        adminService   = new AdminService(userRepo, managerRepo, reviewRepo, editRepo, notifRepo,
                                          companyRepo, mergeRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE managers, users, companies, manager_edits CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Storing the request
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void submittingALocation_storesEveryPartOfIt() throws Exception {
        String user     = insertUser("auth0|loc-store", "LocStore", "user");
        long   managerId = insertManager("Stored Mgr", "StoreCo", "Lead", "United Kingdom", "England", "London");

        await(managerService.createEditRequest(user, managerId, new JsonObject()
            .put("country", "Canada").put("state", "Ontario").put("city", "Kitchener")
            .put("precision", "city")));

        Row edit = editRow(managerId);
        // Each of these was dropped on the floor before: the INSERT did not name the column.
        assertEquals("Canada",    edit.getString("new_country"));
        assertEquals("Ontario",   edit.getString("new_declared_state"));
        assertEquals("Kitchener", edit.getString("new_declared_city"));
        assertEquals("city",      edit.getString("new_declared_precision"));
    }

    @Test
    void aLocationAloneIsEnoughToRequestAnEdit() throws Exception {
        String user      = insertUser("auth0|loc-only", "LocOnly", "user");
        long   managerId = insertManager("City Only", "CityCo", "Lead", "Canada", "Ontario", "Toronto");

        // "At least one field is required" counted country but not the rest, so an edit that moved
        // a manager between two cities in one country was rejected as empty.
        await(managerService.createEditRequest(user, managerId,
            new JsonObject().put("city", "Kitchener").put("state", "Ontario")));

        assertEquals("Kitchener", editRow(managerId).getString("new_declared_city"));
    }

    @Test
    void anInvalidPrecisionIsRefused() throws Exception {
        String user      = insertUser("auth0|loc-prec", "LocPrec", "user");
        long   managerId = insertManager("Prec Mgr", "PrecCo", "Lead", "Canada", null, null);

        Exception ex = assertThrows(Exception.class, () -> await(managerService.createEditRequest(
            user, managerId, new JsonObject().put("country", "Canada").put("precision", "galaxy"))));
        assertTrue(ex.getMessage() != null && ex.getMessage().toLowerCase().contains("precision"),
            "Expected a precision complaint, got: " + ex.getMessage());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Approving the request
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void approvingALocationEditMovesTheManager() throws Exception {
        String user      = insertUser("auth0|loc-appr", "LocAppr", "user");
        String admin     = insertUser("auth0|loc-admin", "LocAdmin", "admin");
        long   managerId = insertManager("Moving Mgr", "MoveCo", "Lead", "United Kingdom", "England", "London");

        await(managerService.createEditRequest(user, managerId, new JsonObject()
            .put("country", "Canada").put("state", "Ontario").put("city", "Kitchener")
            .put("precision", "city")));
        await(adminService.approveEdit(admin, editRow(managerId).getUUID("id")));

        Row mgr = managerRow(managerId);
        assertEquals("Canada",    mgr.getString("country"));
        // The heart of it. Approval applied new_country and nothing else, so the manager landed in
        // Canada while still recorded as being in London, England.
        assertEquals("Ontario",   mgr.getString("state"));
        assertEquals("Kitchener", mgr.getString("city"));
    }

    @Test
    void approvingATitleEditLeavesTheLocationAlone() throws Exception {
        String user      = insertUser("auth0|loc-title", "LocTitle", "user");
        String admin     = insertUser("auth0|loc-tadmin", "LocTAdmin", "admin");
        long   managerId = insertManager("Kept Mgr", "KeepCo", "Lead", "Canada", "Ontario", "Kitchener");

        await(managerService.createEditRequest(user, managerId, new JsonObject().put("title", "Director")));
        await(adminService.approveEdit(admin, editRow(managerId).getUUID("id")));

        Row mgr = managerRow(managerId);
        // Each location part is independent and optional. An edit about a job title must never
        // blank a location it did not ask about.
        assertEquals("Canada",    mgr.getString("country"));
        assertEquals("Ontario",   mgr.getString("state"));
        assertEquals("Kitchener", mgr.getString("city"));
    }

    @Test
    void anEmptyStringClearsAPartOfTheLocation() throws Exception {
        String user      = insertUser("auth0|loc-clear", "LocClear", "user");
        String admin     = insertUser("auth0|loc-cadmin", "LocCAdmin", "admin");
        long   managerId = insertManager("Clear Mgr", "ClearCo", "Lead", "Canada", "Ontario", "Kitchener");

        // Null means "leave it alone", so removing a wrong city has to be expressible as something
        // else. The form sends "" for a part the editor cleared.
        await(managerService.createEditRequest(user, managerId, new JsonObject()
            .put("country", "Canada").put("state", "Ontario").put("city", "")));
        await(adminService.approveEdit(admin, editRow(managerId).getUUID("id")));

        assertNull(managerRow(managerId).getString("city"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Provenance - what makes a corrected location visible
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void approvingALocationEditOnAGhostMarksItDeclared() throws Exception {
        /*
          Asked directly: "so a ghost location CAN be updated to be the state + city if a user or
          myself edits the location, right?" With the first version of this change, no - and
          silently. The reads publish sub-country detail only for a CONFIRMED location, that was
          keyed on approval_status = 'ghost', and editing a location does not stop a row being a
          ghost. So the correction saved and could never appear.

          The fix is provenance: an edit records location_source = 'contributor_declared', which is
          what the projection tests for. This asserts the stored consequence; the projection side
          is GhostLocationPrivacyTest.
        */
        String user      = insertUser("auth0|loc-ghost", "LocGhost", "user");
        String admin     = insertUser("auth0|loc-gadmin", "LocGAdmin", "admin");
        long   managerId = insertGhostWithInferredLocation("Searched Person", "SearchCo", "Canada");

        assertEquals("legacy_visitor_inferred", managerRow(managerId).getString("location_source"),
            "precondition: a search-created manager carries the searcher's geography");

        await(managerService.createEditRequest(user, managerId, new JsonObject()
            .put("country", "Canada").put("state", "Ontario").put("city", "Kitchener")
            .put("precision", "city")));
        await(adminService.approveEdit(admin, editRow(managerId).getUUID("id")));

        Row mgr = managerRow(managerId);
        assertEquals("Ontario",   mgr.getString("state"));
        assertEquals("Kitchener", mgr.getString("city"));
        // The part that makes it publishable. Still a ghost - approval status is untouched by an
        // edit to a location, which is precisely why it cannot be the discriminator.
        assertEquals("contributor_declared", mgr.getString("location_source"));
        assertEquals("ghost", mgr.getString("approval_status"));
    }

    @Test
    void approvingATitleEditDoesNotMarkTheLocationDeclared() throws Exception {
        /*
          The other side of it. Recording a location as declared because some unrelated field
          changed would publish the searcher's geography on the strength of a job-title fix.
        */
        String user      = insertUser("auth0|loc-gt", "LocGt", "user");
        String admin     = insertUser("auth0|loc-gtadmin", "LocGtAdmin", "admin");
        long   managerId = insertGhostWithInferredLocation("Untouched Person", "SearchCo", "Canada");

        await(managerService.createEditRequest(user, managerId, new JsonObject().put("title", "Director")));
        await(adminService.approveEdit(admin, editRow(managerId).getUUID("id")));

        assertEquals("legacy_visitor_inferred", managerRow(managerId).getString("location_source"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Row editRow(long managerId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT * FROM manager_edits WHERE manager_id = $1 ORDER BY created_at DESC LIMIT 1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
    }

    private Row managerRow(long managerId) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
    }

    /** A manager as createAutoApproved leaves one: live, with the SEARCHER's geography on it. */
    private long insertGhostWithInferredLocation(String name, String company, String country) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     country,state,city,location_source,declared_country,declared_precision,
                                     overall_rating,reviews_count,category_averages)
                VALUES ($1,$2,'Manager','img','active','ghost',
                        $3,'Somewhere Else','Some Other City','legacy_visitor_inferred',$3,'country',
                        0,0,'{}') RETURNING id
                """)
            .execute(Tuple.of(name, company, country))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManager(String name, String company, String title,
                               String country, String state, String city) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     country,state,city,overall_rating,reviews_count,category_averages)
                VALUES ($1,$2,$3,'img','active','approved',$4,$5,$6,0,0,'{}') RETURNING id
                """)
            .execute(Tuple.of(name, company, title, country, state, city))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertUser(String auth0Id, String username, String role) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id,email,username,first_name,last_name,role) VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User", role)));
        return auth0Id;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}

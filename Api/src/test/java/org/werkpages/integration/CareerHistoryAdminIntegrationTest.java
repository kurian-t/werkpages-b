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
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.AdminService;
import org.werkpages.service.ServiceException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CareerHistoryAdminIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static AdminService      service;
    static ManagerRepository managerRepo;

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

        pool        = PgPool.pool(opts, new PoolOptions().setMaxSize(5));
        managerRepo = new ManagerRepository(pool);
        UserRepository              userRepo    = new UserRepository(pool);
        ReviewRepository            reviewRepo  = new ReviewRepository(pool);
        EditRepository              editRepo    = new EditRepository(pool);
        NotificationRepository      notifRepo   = new NotificationRepository(pool);
        CompanyRepository           companyRepo = new CompanyRepository(pool);
        MergeSuggestionsRepository  mergeRepo   = new MergeSuggestionsRepository(pool);
        service = new AdminService(userRepo, managerRepo, reviewRepo, editRepo, notifRepo,
                                   companyRepo, mergeRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE managers, users, companies CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminUpdateCareerEntry — auth checks
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void update_nonAdmin_returns403() throws Exception {
        String userAuth  = insertUser("auth0|ch-user01", "ChUser01", "user");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminUpdateCareerEntry(userAuth, managerId, entryId, "Acme", "Director", "2020", null));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void update_nullAuth_returns401() throws Exception {
        long managerId = insertManager("Alice", "Acme", "Manager");
        long entryId   = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminUpdateCareerEntry(null, managerId, entryId, "Acme", "Director", "2020", null));
        assertEquals(401, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminUpdateCareerEntry — validation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void update_blankCompany_returns400() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin01", "ChAdmin01", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminUpdateCareerEntry(adminAuth, managerId, entryId, "  ", "Manager", "2020", null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void update_blankTitle_returns400() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin02", "ChAdmin02", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminUpdateCareerEntry(adminAuth, managerId, entryId, "Acme", "", "2020", null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void update_blankStartDate_returns400() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin03", "ChAdmin03", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminUpdateCareerEntry(adminAuth, managerId, entryId, "Acme", "Manager", "  ", null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void update_invalidDateFormat_returns400() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin04", "ChAdmin04", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminUpdateCareerEntry(adminAuth, managerId, entryId, "Acme", "Manager", "not-a-date", null));
        assertEquals(400, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The headline follows the roles
    //
    // This page has been repaired repeatedly and kept coming back, because three tables each owned
    // part of one truth: the header from the `managers` row, the role cards from career_history,
    // the trajectory's dates from reviews. Editing one surface left the others behind. These pin
    // the rule that career history owns the headline, so the drift cannot return unnoticed.
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Moving a manager to a newer role moves the header with them.
     *
     * <p>Reported from production: a manager given a newer role at a different company went on
     * showing the old company's name, logo and title at the top of their profile, with a Retired
     * badge, no matter how many times the change was approved. Nothing derived the header from the
     * roles beneath it.
     */
    @Test
    void editingTheCurrentRole_movesTheHeaderCompanyTitleAndStatus() throws Exception {
        String adminAuth = insertUser("auth0|ch-headline01", "ChHeadline01", "admin");
        long managerId   = insertManager("Ketti Ciarniello", "Lime", "Assistant Treasurer");
        long entryId     = insertCareerEntry(managerId, "Lime", "Assistant Treasurer", "2024");

        await(service.adminUpdateCareerEntry(
            adminAuth, managerId, entryId, "ICAT Logistics", "Director of Treasury", "2026", null));

        Row m = managerRow(managerId);
        assertEquals("ICAT Logistics", m.getString("company"),
            "the header must name the company the manager is actually at");
        assertEquals("Director of Treasury", m.getString("title"));
        assertEquals("active", m.getString("status"),
            "an open role means they are active - the Retired badge was left over from the old row");
    }

    /**
     * Closing the only role retires the manager.
     *
     * <p>Nobody is "actively leading" a role that has an end date, so status is derived rather
     * than left to whatever was set last.
     */
    @Test
    void closingTheOnlyRole_marksTheManagerRetired() throws Exception {
        String adminAuth = insertUser("auth0|ch-headline02", "ChHeadline02", "admin");
        long managerId   = insertManager("Ketti Ciarniello", "Lime", "Assistant Treasurer");
        long entryId     = insertCareerEntry(managerId, "Lime", "Assistant Treasurer", "2024");

        await(service.adminUpdateCareerEntry(
            adminAuth, managerId, entryId, "Lime", "Assistant Treasurer", "2024", "2026"));

        assertEquals("retired", managerRow(managerId).getString("status"),
            "every role has ended, so the manager cannot still be active");
    }

    /**
     * With several roles, the open one decides the header - not the newest row written.
     *
     * <p>The trajectory shows roles in both directions, and an admin correcting an old one must
     * not drag the header backwards onto a company the manager left years ago.
     */
    @Test
    void withSeveralRoles_theOpenOneDecidesTheHeader() throws Exception {
        String adminAuth = insertUser("auth0|ch-headline03", "ChHeadline03", "admin");
        long managerId   = insertManager("Ketti Ciarniello", "ICAT Logistics", "Director of Treasury");
        long oldEntry    = insertCareerEntry(managerId, "Lime", "Assistant Treasurer", "2024");
        await(pool.preparedQuery("UPDATE career_history SET end_date = '2026-01-01T00:00:00Z' WHERE id = $1")
            .execute(Tuple.of(oldEntry)).mapEmpty());
        insertCareerEntry(managerId, "ICAT Logistics", "Director of Treasury", "2026");

        // Correcting the OLD, closed role.
        await(service.adminUpdateCareerEntry(
            adminAuth, managerId, oldEntry, "Lime", "Assistant Treasurer", "2023", "2026-01"));

        Row m = managerRow(managerId);
        assertEquals("ICAT Logistics", m.getString("company"),
            "the current role still decides the header, whichever row was edited");
        assertEquals("active", m.getString("status"));
    }

    /**
     * Deleting the current role falls back to the most recent remaining one.
     *
     * <p>The Delete button on the trajectory wrote career_history and stopped, leaving the header
     * advertising a role that no longer existed anywhere.
     */
    @Test
    void deletingTheCurrentRole_fallsBackToTheMostRecentRemainingOne() throws Exception {
        String adminAuth = insertUser("auth0|ch-headline04", "ChHeadline04", "admin");
        long managerId   = insertManager("Ketti Ciarniello", "ICAT Logistics", "Director of Treasury");
        long oldEntry    = insertCareerEntry(managerId, "Lime", "Assistant Treasurer", "2024");
        await(pool.preparedQuery("UPDATE career_history SET end_date = '2026-01-01T00:00:00Z' WHERE id = $1")
            .execute(Tuple.of(oldEntry)).mapEmpty());
        long currentEntry = insertCareerEntry(managerId, "ICAT Logistics", "Director of Treasury", "2026");

        await(service.adminDeleteCareerEntry(adminAuth, managerId, currentEntry));

        Row m = managerRow(managerId);
        assertEquals("Lime", m.getString("company"),
            "with the current role gone the header falls back to the last one that remains");
        assertEquals("retired", m.getString("status"),
            "and that role has ended, so the manager is retired");
    }

    /** A manager with no career history is left exactly as they are. */
    @Test
    void aManagerWithNoCareerHistory_keepsTheHeadlineTheyHave() throws Exception {
        long managerId = insertManager("No History", "Solo Corp", "Founder");

        await(managerRepo.syncHeadlineFromCareerHistory(managerId));

        Row m = managerRow(managerId);
        assertEquals("Solo Corp", m.getString("company"),
            "there is nothing to derive from, and inventing a headline is worse than keeping it");
        assertEquals("Founder", m.getString("title"));
    }

    /**
     * The trajectory's dates come from career history, not from whoever reviewed the role.
     *
     * <p>Reported from production: a manager shown as "2024 – Present" at a company they had left,
     * because the reviewer who rated them was still employed there. {@code is_current} was
     * {@code BOOL_OR(worked_until IS NULL)} over <em>reviews</em> - the reviewer's own employment
     * dates - so no amount of editing the manager's career history could change it.
     */
    @Test
    void trajectoryDates_comeFromCareerHistory_notFromTheReviewersDates() throws Exception {
        long managerId = insertManager("Ketti Ciarniello", "Lime", "Assistant Treasurer");
        long entryId   = insertCareerEntry(managerId, "Lime", "Assistant Treasurer", "2024");
        await(pool.preparedQuery("UPDATE career_history SET end_date = '2026-01-01T00:00:00Z' WHERE id = $1")
            .execute(Tuple.of(entryId)).mapEmpty());

        // A reviewer who is still at Lime: worked_until IS NULL. The manager has still left.
        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, author, overall_rating, manager_company, manager_title, "
              + "worked_from, worked_until, created_at, updated_at) "
              + "VALUES ($1,'Someone',4.3,'Lime','Assistant Treasurer','2024-01-01',NULL,now(),now())")
            .execute(Tuple.of(managerId)).mapEmpty());

        Row seg = segmentFor(managerId, "Lime");
        assertFalse(seg.getBoolean("is_current"),
            "the manager's role ended, whatever their reviewer's own dates say");
        assertNotNull(seg.getLocalDate("end_date"),
            "and the end date is the one recorded against the role");
    }

    /**
     * A role with no reviews still appears, and can be the current one.
     *
     * <p>The panel was built by grouping reviews, so a role nobody had rated did not exist to it -
     * and could never be shown as current, however it was recorded.
     */
    @Test
    void aRoleWithNoReviews_stillAppearsAndCanBeCurrent() throws Exception {
        long managerId = insertManager("Ketti Ciarniello", "ICAT Logistics", "Director of Treasury");
        insertCareerEntry(managerId, "ICAT Logistics", "Director of Treasury", "2026");

        Row seg = segmentFor(managerId, "ICAT Logistics");
        assertTrue(seg.getBoolean("is_current"),
            "an open role is current even with nothing rated against it");
        assertEquals(0L, seg.getLong("review_count"));
    }

    /**
     * Reviews naming a role that was never recorded are not dropped.
     *
     * <p>Career history owns the dates, but it must not become a filter: a review referring to a
     * company and title nobody entered as a role is still somebody's rating of this manager.
     */
    @Test
    void reviewsForAnUnrecordedRole_stillAppear() throws Exception {
        long managerId = insertManager("Ketti Ciarniello", "Lime", "Assistant Treasurer");
        insertCareerEntry(managerId, "Lime", "Assistant Treasurer", "2024");
        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, author, overall_rating, manager_company, manager_title, "
              + "worked_from, created_at, updated_at) "
              + "VALUES ($1,'Someone',5.0,'Ghost Employer','Some Role','2019-01-01',now(),now())")
            .execute(Tuple.of(managerId)).mapEmpty());

        assertNotNull(segmentFor(managerId, "Ghost Employer"),
            "a rated role nobody recorded must not vanish from the trajectory");
    }

    /**
     * A role with no career_history row can be recorded, and that is what ends "Present".
     *
     * <p>Only update and delete existed. A trajectory card can come from a career_history row,
     * from reviews grouped into a segment, or from the manager record - and only the first had an
     * id, so for the other two there was nowhere to write dates at all. The panel therefore kept
     * falling back to the reviewer's own {@code worked_until}, and a manager who had plainly left
     * read "Present" however many times an admin edited it.
     */
    @Test
    void recordingARoleThatHadNoRow_endsThePresentFallback() throws Exception {
        String adminAuth = insertUser("auth0|ch-create01", "ChCreate01", "admin");
        long managerId   = insertManager("Ketti Ciarniello", "Lime", "Assistant Treasurer");

        // Reviewed, but never recorded as a role - and the reviewer is still there.
        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, author, overall_rating, manager_company, manager_title, "
              + "worked_from, worked_until, created_at, updated_at) "
              + "VALUES ($1,'Someone',4.3,'Lime','Assistant Treasurer','2024-01-01',NULL,now(),now())")
            .execute(Tuple.of(managerId)).mapEmpty());
        assertTrue(segmentFor(managerId, "Lime").getBoolean("is_current"),
            "precondition: with no row recorded, the reviewer's dates are all there is");

        await(service.adminCreateCareerEntry(
            adminAuth, managerId, "Lime", "Assistant Treasurer", "2024", "2026"));

        Row seg = segmentFor(managerId, "Lime");
        assertFalse(seg.getBoolean("is_current"),
            "once the role is recorded with an end date it is no longer current");
        assertNotNull(seg.getLocalDate("end_date"), "and the panel shows that end date");
        assertEquals("retired", managerRow(managerId).getString("status"),
            "and the headline follows, because career history owns it");
    }

    /**
     * The company an admin picked is the company that is stored - by id, not by re-resolving text.
     *
     * <p>The editor sent only the company's name, so the server resolved it again. Company names
     * are unique case-insensitively, so the danger is not two rows sharing a name - it is that a
     * name typed even slightly differently is a <em>new</em> company. Picking "Lime" from the list
     * and having the text read anything else minted a second row and moved the manager onto its
     * logo, with no way to correct it from the panel that caused it.
     */
    @Test
    void careerEntry_storesThePickedCompanyId_ratherThanResolvingTheNameAgain() throws Exception {
        String adminAuth = insertUser("auth0|ch-pick01", "ChPick01", "admin");
        long managerId   = insertManager("Ketti Ciarniello", "Lime", "Assistant Treasurer");

        long realLime = await(pool.preparedQuery(
                "INSERT INTO companies(name, slug, status, logo_url, created_at, updated_at) "
              + "VALUES ('Lime','lime-real','approved','https://logo.test/lime.png',now(),now()) RETURNING id")
            .execute().map(rs -> rs.iterator().next().getLong("id")));
        long companiesBefore = countCompanies();

        // Picked Lime from the list, but the text differs - which is exactly when re-resolving
        // by name would invent a second company.
        await(service.adminCreateCareerEntry(adminAuth, managerId,
            "Lime Micromobility", "Assistant Treasurer", "2024", null,
            realLime, "https://logo.test/lime.png"));

        Long stored = await(pool
            .preparedQuery("SELECT company_id FROM career_history WHERE manager_id = $1 ORDER BY id DESC LIMIT 1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong("company_id")));
        assertEquals(realLime, stored,
            "the id the admin picked must be stored, whatever the display text says");
        assertEquals(companiesBefore, countCompanies(),
            "and no second company is invented behind their back");
        assertEquals("https://logo.test/lime.png", managerRowLogo(managerId),
            "so the logo they chose is the one that sticks");
    }

    private long countCompanies() throws Exception {
        return await(pool.preparedQuery("SELECT count(*) AS c FROM companies").execute()
            .map(rs -> rs.iterator().next().getLong("c")));
    }

    /**
     * A review's period is bounded by the manager's role, not just the reviewer's own dates.
     *
     * <p>A card read "Jan 2024 - Present" for a manager who had left, because
     * {@code worked_until} belongs to the REVIEWER - who is still at the company. Nobody can
     * still be working with someone who has gone.
     */
    @Test
    void reviewPeriod_isCappedAtTheManagersRoleEnd() throws Exception {
        long managerId = insertManager("Ketti Ciarniello", "Lime", "Assistant Treasurer");
        long entryId   = insertCareerEntry(managerId, "Lime", "Assistant Treasurer", "2024");
        await(pool.preparedQuery("UPDATE career_history SET end_date = '2026-01-01T00:00:00Z' WHERE id = $1")
            .execute(Tuple.of(entryId)).mapEmpty());
        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, author, overall_rating, manager_company, manager_title, "
              + "worked_from, worked_until, created_at, updated_at) "
              + "VALUES ($1,'LoyalPanda80',4.3,'Lime','Assistant Treasurer','2024-01-01',NULL,now(),now())")
            .execute(Tuple.of(managerId)).mapEmpty());

        Row review = await(new org.werkpages.repository.ReviewRepository(pool)
            .findByManager(managerId, 10, 0, "recent", null, null, true)
            .map(rs -> rs.iterator().next()));
        assertNotNull(review.getLocalDate("effective_worked_until"),
            "the reviewer is still there, but the manager is not - the card cannot say Present");
        assertEquals("2026-01-01", review.getLocalDate("effective_worked_until").toString());
    }

    /** Two roles held at once, both open: neither review is capped. */
    @Test
    void reviewPeriod_isNotCapped_whileTheRoleIsStillOpen() throws Exception {
        long managerId = insertManager("Ketti Ciarniello", "Lime", "Assistant Treasurer");
        insertCareerEntry(managerId, "Lime", "Assistant Treasurer", "2024");
        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, author, overall_rating, manager_company, manager_title, "
              + "worked_from, worked_until, created_at, updated_at) "
              + "VALUES ($1,'Someone',4.0,'Lime','Assistant Treasurer','2024-01-01',NULL,now(),now())")
            .execute(Tuple.of(managerId)).mapEmpty());

        Row review = await(new org.werkpages.repository.ReviewRepository(pool)
            .findByManager(managerId, 10, 0, "recent", null, null, true)
            .map(rs -> rs.iterator().next()));
        assertNull(review.getLocalDate("effective_worked_until"),
            "an open role stays Present - a manager may hold two roles at two companies at once");
    }

    private String managerRowLogo(long managerId) throws Exception {
        return await(pool.preparedQuery("SELECT company_logo_url FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("company_logo_url")));
    }

    /**
     * The trajectory tile shows the logo of the company the role was AT - including past ones.
     *
     * <p>Reported three times from production. The admin opened the trajectory card's editor,
     * picked the right company from the dropdown, saved - and the tile went on showing an
     * unrelated brand's mark. The pick WAS stored: {@code career_history.company_id} held the
     * right row all along. The segments query simply returned no logo column at all, so the
     * frontend fell through to guessing a domain from the company NAME and rendered whatever
     * came back.
     *
     * <p>Past companies were the whole of the bug: the only logo that ever appeared was the
     * manager's CURRENT one, which the page patched in by name-matching after the fetch.
     */
    @Test
    void careerSegment_carriesTheLogoOfThePickedCompany_evenForAPastRole() throws Exception {
        long lime = await(pool.preparedQuery(
                "INSERT INTO companies(name, logo_url, status, created_at, updated_at) "
              + "VALUES ('Lime Logo Co', 'https://logo.test/lime-picked.png', 'approved', now(), now()) "
              + "RETURNING id")
            .execute().map(rs -> rs.iterator().next().getLong("id")));

        // The manager is at a DIFFERENT company now - the old one is strictly in the past.
        long managerId = insertManager("Trajectory Logo Mgr", "ICAT Logo Co", "Director");
        long entryId   = insertCareerEntry(managerId, "Lime Logo Co", "Assistant Treasurer", "2024");
        await(pool.preparedQuery(
                "UPDATE career_history SET company_id = $1, end_date = '2026-01-01T00:00:00Z' WHERE id = $2")
            .execute(Tuple.of(lime, entryId)).mapEmpty());

        Row segment = segmentFor(managerId, "Lime Logo Co");
        assertNotNull(segment, "the past role must still appear on the trajectory");
        assertEquals("https://logo.test/lime-picked.png", segment.getString("logo_url"),
            "the logo the admin picked must reach the tile - a past company is not an exception");
    }

    private Row segmentFor(long managerId, String company) throws Exception {
        var rows = await(new org.werkpages.repository.ReviewRepository(pool)
            .findCareerSegmentsByManager(managerId, 50, 0));
        for (Row r : rows) {
            if (company.equalsIgnoreCase(r.getString("company"))) return r;
        }
        return null;
    }

    private Row managerRow(long managerId) throws Exception {
        return await(pool
            .preparedQuery("SELECT company, title, status, company_id FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminUpdateCareerEntry — success paths
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void update_validYearStartDate_updatesEntry() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin05", "ChAdmin05", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        JsonObject result = await(service.adminUpdateCareerEntry(
            adminAuth, managerId, entryId, "Globex", "Director", "2019", null));

        assertTrue(result.getBoolean("success"));
        assertEquals(1, result.getInteger("updated"));

        Row row = await(pool
            .preparedQuery("SELECT company, title, start_date, end_date FROM career_history WHERE id = $1")
            .execute(Tuple.of(entryId))
            .map(rs -> rs.iterator().next()));

        assertEquals("Globex", row.getString("company"));
        assertEquals("Director", row.getString("title"));
        assertNotNull(row.getOffsetDateTime("start_date"));
        assertNull(row.getOffsetDateTime("end_date"), "end_date must be null when endDate not provided");
    }

    @Test
    void update_withEndDate_setsEndDate() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin06", "ChAdmin06", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2018");

        await(service.adminUpdateCareerEntry(
            adminAuth, managerId, entryId, "Acme", "Manager", "2018", "2022"));

        Row row = await(pool
            .preparedQuery("SELECT end_date FROM career_history WHERE id = $1")
            .execute(Tuple.of(entryId))
            .map(rs -> rs.iterator().next()));

        assertNotNull(row.getOffsetDateTime("end_date"), "end_date must be set when endDate provided");
    }

    @Test
    void update_wrongManagerId_updatesZeroRows() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin07", "ChAdmin07", "admin");
        long managerId1  = insertManager("Alice", "Acme", "Manager");
        long managerId2  = insertManager("Bob",   "Globex", "Lead");
        long entryId     = insertCareerEntry(managerId1, "Acme", "Manager", "2020");

        // Attempt to update an entry that belongs to managerId1 but using managerId2
        JsonObject result = await(service.adminUpdateCareerEntry(
            adminAuth, managerId2, entryId, "Globex", "Director", "2020", null));

        assertEquals(0, result.getInteger("updated"), "update must not affect entries belonging to other managers");
    }

    @Test
    void update_yearMonthStartDate_accepted() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin08", "ChAdmin08", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        JsonObject result = await(service.adminUpdateCareerEntry(
            adminAuth, managerId, entryId, "Acme", "Manager", "2021-06", null));

        assertTrue(result.getBoolean("success"));
        assertEquals(1, result.getInteger("updated"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminDeleteCareerEntry — auth checks
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void delete_nonAdmin_returns403() throws Exception {
        String userAuth  = insertUser("auth0|ch-user02", "ChUser02", "user");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminDeleteCareerEntry(userAuth, managerId, entryId));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void delete_nullAuth_returns401() throws Exception {
        long managerId = insertManager("Alice", "Acme", "Manager");
        long entryId   = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminDeleteCareerEntry(null, managerId, entryId));
        assertEquals(401, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminDeleteCareerEntry — success paths
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void delete_removesEntry() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin09", "ChAdmin09", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        JsonObject result = await(service.adminDeleteCareerEntry(adminAuth, managerId, entryId));
        assertTrue(result.getBoolean("success"));
        assertEquals(1, result.getInteger("deleted"));

        long count = await(pool
            .preparedQuery("SELECT COUNT(*) FROM career_history WHERE id = $1")
            .execute(Tuple.of(entryId))
            .map(rs -> rs.iterator().next().getLong(0)));

        assertEquals(0L, count, "entry must be removed from career_history table");
    }

    @Test
    void delete_wrongManagerId_deletesZeroRows() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin10", "ChAdmin10", "admin");
        long managerId1  = insertManager("Alice", "Acme", "Manager");
        long managerId2  = insertManager("Bob",   "Globex", "Lead");
        long entryId     = insertCareerEntry(managerId1, "Acme", "Manager", "2020");

        JsonObject result = await(service.adminDeleteCareerEntry(adminAuth, managerId2, entryId));
        assertEquals(0, result.getInteger("deleted"), "must not delete entries belonging to other managers");

        // Confirm entry is still there
        long count = await(pool
            .preparedQuery("SELECT COUNT(*) FROM career_history WHERE id = $1")
            .execute(Tuple.of(entryId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, count, "entry must still exist after incorrect managerId delete");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // careerHistory id exposed in manager response
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getManagerById_careerHistoryIncludesId() throws Exception {
        long managerId = insertManager("Alice", "Acme", "Manager");
        long entryId   = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        Row managerRow = await(pool
            .preparedQuery(ManagerRepository.GET_BY_ID_SQL)
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));

        JsonArray history = managerRow.getJsonArray("career_history");
        assertNotNull(history, "career_history must not be null");
        assertEquals(1, history.size());
        JsonObject entry = history.getJsonObject(0);
        assertEquals(entryId, entry.getLong("id"), "career_history entry must expose id");
        assertEquals("Acme",    entry.getString("company"));
        assertEquals("Manager", entry.getString("title"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private String insertUser(String auth0Id, String username, String role) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id,email,username,first_name,last_name,role) VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User", role)));
        return auth0Id;
    }

    @Test
    void update_movingCompanyRepointsTheForeignKey() throws Exception {
        // The bug this guards. The edit wrote the new company NAME and left company_id pointing at
        // the old company, so the row said one thing and referenced another. Every query deciding
        // which managers appear on a company page matches the id, not the text - so the manager
        // stayed listed under the old company with nothing on screen explaining why, and editing
        // them again did nothing either.
        String adminAuth = insertUser("auth0|ch-admin-fk", "ChAdminFk", "admin");
        long oldCo     = insertCompany("Loblaw Companies Limited");
        long newCo     = insertCompany("Zehrs Markets");
        long managerId = insertManager("Danielle", "Loblaw Companies Limited", "Assistant Store Manager");
        long entryId   = insertCareerEntry(managerId, "Loblaw Companies Limited", "Assistant Store Manager", "2020");
        await(pool.preparedQuery("UPDATE career_history SET company_id = $1 WHERE id = $2")
            .execute(Tuple.of(oldCo, entryId)).mapEmpty());

        await(service.adminUpdateCareerEntry(
            adminAuth, managerId, entryId, "Zehrs Markets", "Assistant Store Manager", "2020", null));

        Row row = await(pool.preparedQuery("SELECT company, company_id FROM career_history WHERE id = $1")
            .execute(Tuple.of(entryId)).map(rs -> rs.iterator().next()));
        assertEquals("Zehrs Markets", row.getString("company"));
        assertNotEquals(oldCo, row.getLong("company_id"), "the entry no longer points at the old company");
        assertEquals(newCo, row.getLong("company_id"),
            "the id follows the name, or the manager keeps appearing under the old company");
    }
    private long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies(name,status,slug) VALUES ($1,'approved',$2) RETURNING id")
            .execute(Tuple.of(name, name.toLowerCase().replaceAll("[^a-z0-9]+", "-")))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManager(String name, String company, String title) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages)
                VALUES ($1,$2,$3,'img','active','approved',0,0,'{}') RETURNING id
                """)
            .execute(Tuple.of(name, company, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertCareerEntry(long managerId, String company, String title, String startYear) throws Exception {
        java.time.OffsetDateTime startDt = java.time.OffsetDateTime.parse(startYear + "-01-01T00:00:00Z");
        return await(pool.preparedQuery("""
                INSERT INTO career_history(manager_id, company, title, start_date)
                VALUES ($1, $2, $3, $4) RETURNING id
                """)
            .execute(Tuple.of(managerId, company, title, startDt))
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

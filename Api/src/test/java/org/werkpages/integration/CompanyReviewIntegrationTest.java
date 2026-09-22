package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.*;
import org.werkpages.service.CompanyReviewService;
import org.werkpages.service.DeclaredLocation;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rating an employer, as distinct from rating its managers.
 *
 * The rules worth protecting: every category is required, one rating per person per company, and
 * a company nobody has rated reports nothing rather than zero.
 */
@Testcontainers
class CompanyReviewIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test").withUsername("test").withPassword("test");

    static Pool pool;
    static CompanyReviewService    service;
    static CompanyReviewRepository reviewRepo;
    static CompanyRepository       companyRepo;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations").load().migrate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));

        reviewRepo  = new CompanyReviewRepository(pool);
        companyRepo = new CompanyRepository(pool);
        service     = new CompanyReviewService(reviewRepo, companyRepo, new UserRepository(pool));
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE captured_drafts, company_reviews, companies, users CASCADE").execute().mapEmpty());
    }

    @AfterAll
    static void tearDownAll() { if (pool != null) pool.close(); }

    // ── Submitting ────────────────────────────────────────────────────────────

    @Test
    void submittingStoresEveryCategory() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-1", "CrUser1");

        JsonObject result = await(service.submit(auth, "red-hat", validBody(4.0)));

        assertNotNull(result.getString("id"));
        assertEquals(4.0, result.getDouble("overallRating"));
        JsonObject ratings = result.getJsonObject("ratings");
        for (String c : CompanyReviewRepository.CATEGORIES) {
            assertNotNull(ratings.getDouble(c), c + " was stored");
        }
    }

    @Test
    void everyCategoryIsRequired() throws Exception {
        // No N/A. A corpus where half the ratings skipped career growth cannot be sliced by it.
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-2", "CrUser2");

        JsonObject body = validBody(4.0);
        body.getJsonObject("ratings").remove("career_growth");

        Exception e = assertThrows(Exception.class, () -> await(service.submit(auth, "red-hat", body)));
        assertTrue(e.getMessage().contains("every category"), "got: " + e.getMessage());
    }

    @Test
    void theOverallRatingIsRequiredSeparately() throws Exception {
        // It is not the mean of the ten - someone can rate the parts poorly and the whole well.
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-3", "CrUser3");

        JsonObject body = validBody(4.0);
        body.remove("overallRating");

        assertThrows(Exception.class, () -> await(service.submit(auth, "red-hat", body)));
    }

    @Test
    void ratingACompanyThatDoesNotExistIsRefused() throws Exception {
        // A company rating never creates a company, for the same reason an interview review does
        // not: there would be no anchor at all behind it.
        String auth = insertUser("auth0|cr-4", "CrUser4");
        assertThrows(Exception.class, () -> await(service.submit(auth, "no-such-company", validBody(4.0))));
    }

    // ── One per person per company ───────────────────────────────────────────

    @Test
    void ratingTheSameCompanyTwiceReplacesTheFirst() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-5", "CrUser5");

        await(service.submit(auth, "red-hat", validBody(2.0)));
        await(service.submit(auth, "red-hat", validBody(5.0)));

        Long rows = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM company_reviews WHERE deleted_at IS NULL")
            .execute().map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, rows, "a second rating replaces the first rather than adding one");

        var agg = await(service.aggregateFor(companyId("red-hat")));
        assertEquals(5.0, agg.getDouble("overallRating"), "and it is the newer answer that stands");
    }

    @Test
    void oneRatingEachAtTwoCompaniesIsFine() throws Exception {
        // The rule is per company row, not per corporate group. Somebody who worked at Zehrs and
        // at Loblaw has two genuinely different experiences to report.
        insertCompany("Zehrs", "zehrs");
        insertCompany("Loblaw", "loblaw");
        String auth = insertUser("auth0|cr-6", "CrUser6");

        await(service.submit(auth, "zehrs",  validBody(4.0)));
        await(service.submit(auth, "loblaw", validBody(2.0)));

        Long rows = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM company_reviews WHERE deleted_at IS NULL")
            .execute().map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(2L, rows);
    }

    @Test
    void mergingTwoCompaniesOnePersonRatedBothIsBlocked() throws Exception {
        // One rating per person per company is a unique index, so this merge would put two of the
        // same person's rows onto one company - after the managers had already moved.
        long keep  = companyId(insertCompany("Zehrs Markets", "zehrs-markets"));
        long merge = companyId(insertCompany("Zehrs", "zehrs"));
        String auth = insertUser("auth0|cr-7", "CrUser7");
        await(service.submit(auth, "zehrs-markets", validBody(4.0)));
        await(service.submit(auth, "zehrs",         validBody(2.0)));

        var preview = await(companyRepo.previewMerge(keep, merge));

        assertTrue(preview.getBoolean("blocked"), "the merge is refused before anything moves");
        assertEquals(1L, preview.getLong("companyRatingConflicts"));
    }

    @Test
    void mergingMovesRatingsWhenThereIsNoCollision() throws Exception {
        long keep  = companyId(insertCompany("Zehrs Markets", "zehrs-markets"));
        long merge = companyId(insertCompany("Zehrs", "zehrs"));
        String auth = insertUser("auth0|cr-8", "CrUser8");
        await(service.submit(auth, "zehrs", validBody(3.0)));

        await(companyRepo.mergeCompanies(keep, merge, adminId()));

        var agg = await(service.aggregateFor(keep));
        assertNotNull(agg, "the rating followed the company it was about");
        assertEquals(1L, agg.getLong("ratingCount"));
    }

    // ── Aggregates ───────────────────────────────────────────────────────────

    @Test
    void aCompanyNobodyRatedReportsNothingRatherThanZero() throws Exception {
        // A page printing 0.0 says the workplace is terrible; printing nothing says nobody has
        // told us yet. They are not the same claim.
        insertCompany("Quiet Co", "quiet-co");
        assertNull(await(service.aggregateFor(companyId("quiet-co"))));
    }

    @Test
    void theAggregateAveragesEveryCategoryOverTheSameRows() throws Exception {
        insertCompany("Red Hat", "red-hat");
        await(service.submit(insertUser("auth0|cr-9",  "CrUser9"),  "red-hat", validBody(2.0)));
        await(service.submit(insertUser("auth0|cr-10", "CrUser10"), "red-hat", validBody(4.0)));

        JsonObject agg = await(service.aggregateFor(companyId("red-hat")));

        assertEquals(2L, agg.getLong("ratingCount"));
        assertEquals(3.0, agg.getDouble("overallRating"), "mean of 2.0 and 4.0");
        // Every category is NOT NULL, so all ten share one denominator and one "based on N".
        for (String c : CompanyReviewRepository.CATEGORIES) {
            assertNotNull(agg.getJsonObject("categories").getDouble(c), c);
        }
    }

    @Test
    void aWithdrawnRatingLeavesTheAggregate() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-11", "CrUser11");
        JsonObject review = await(service.submit(auth, "red-hat", validBody(1.0)));

        await(service.delete(auth, UUID.fromString(review.getString("id"))));

        assertNull(await(service.aggregateFor(companyId("red-hat"))),
            "the only rating was withdrawn, so there is nothing to report");
    }

    @Test
    void withdrawingSomebodyElsesRatingIsRefused() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String owner   = insertUser("auth0|cr-12", "CrUser12");
        String bystander = insertUser("auth0|cr-13", "CrUser13");
        JsonObject review = await(service.submit(owner, "red-hat", validBody(3.0)));

        assertThrows(Exception.class,
            () -> await(service.delete(bystander, UUID.fromString(review.getString("id")))));
    }

    // ── The period worked ─────────────────────────────────────────────────────

    /*
      When somebody worked there is what makes a rating readable - an employer in 2014 says little
      about the employer today - so the dates carry real validation, and none of it was covered.
    */

    @Test
    void aRatingWithNoStartDateIsRefused() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-20", "CrUser20");
        JsonObject body = validBody(4.0).putNull("workedFrom");

        assertThrows(Exception.class, () -> await(service.submit(auth, "red-hat", body)));
    }

    @Test
    void aStartDateInTheFutureIsRefused() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-21", "CrUser21");
        JsonObject body = validBody(4.0).put("workedFrom", "2999-01");

        assertThrows(Exception.class, () -> await(service.submit(auth, "red-hat", body)));
    }

    @Test
    void anEndDateBeforeTheStartIsRefused() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-22", "CrUser22");
        JsonObject body = validBody(4.0).put("workedFrom", "2022-06").put("workedUntil", "2021-01");

        assertThrows(Exception.class, () -> await(service.submit(auth, "red-hat", body)));
    }

    @Test
    void anEndDateInTheFutureIsRefused() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-23", "CrUser23");
        JsonObject body = validBody(4.0).put("workedUntil", "2999-01");

        assertThrows(Exception.class, () -> await(service.submit(auth, "red-hat", body)));
    }

    @Test
    void aMonthPickerValueAndAFullDateBothParse() throws Exception {
        // The form sends "2021-04"; an API client may send "2021-04-01". Both mean the same month.
        insertCompany("Red Hat", "red-hat");
        String monthly = insertUser("auth0|cr-24", "CrUser24");
        String dated   = insertUser("auth0|cr-25", "CrUser25");

        JsonObject a = await(service.submit(monthly, "red-hat", validBody(4.0)));
        JsonObject b = await(service.submit(dated, "red-hat",
            validBody(4.0).put("workedFrom", "2021-04-01")));

        assertEquals("2021-04-01", a.getString("workedFrom"));
        assertEquals(a.getString("workedFrom"), b.getString("workedFrom"));
    }

    @Test
    void anUnparseableStartDateIsRefusedRatherThanStoredAsNothing() throws Exception {
        /*
          parseDate returns null on a value it cannot read, which lands on the same branch as a
          missing date. That is the safe direction - the alternative is a rating silently stored
          with no period at all - and this is the test that says the branch is deliberate.
        */
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-26", "CrUser26");
        JsonObject body = validBody(4.0).put("workedFrom", "last April");

        assertThrows(Exception.class, () -> await(service.submit(auth, "red-hat", body)));
    }

    // ── The author handle ─────────────────────────────────────────────────────

    @Test
    void theAuthorHandleIsStoredWithTheRating() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-30", "CrUser30");

        JsonObject saved = await(service.submit(auth, "red-hat",
            validBody(4.0).put("author", "CoolLynx30")));

        assertEquals("CoolLynx30", saved.getString("author"));
    }

    @Test
    void editingWithoutSendingAHandleKeepsTheOneAReaderAlreadySaw() throws Exception {
        /*
          The COALESCE in the upsert. Without it, revisiting the form to change a star would blank
          the byline, and the same person's rating would read as a different account than the one
          somebody replied to yesterday.
        */
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-31", "CrUser31");
        await(service.submit(auth, "red-hat", validBody(2.0).put("author", "FirstName11")));

        JsonObject edited = await(service.submit(auth, "red-hat", validBody(5.0)));

        assertEquals("FirstName11", edited.getString("author"), "the handle survived the edit");
        assertEquals(5.0, edited.getDouble("overallRating"), 0.01, "and the new answer took");
    }

    @Test
    void aBlankHandleIsStoredAsNoneRatherThanAnEmptyByline() throws Exception {
        // An empty string would print a nameless byline, which is a different thing from anonymous.
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-32", "CrUser32");

        JsonObject saved = await(service.submit(auth, "red-hat",
            validBody(4.0).put("author", "   ")));

        assertNull(saved.getString("author"));
    }

    @Test
    void aHandleTooLongToRenderIsRefused() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-33", "CrUser33");
        JsonObject body = validBody(4.0).put("author", "x".repeat(61));

        assertThrows(Exception.class, () -> await(service.submit(auth, "red-hat", body)));
    }

    // ── Reading back your own rating ──────────────────────────────────────────

    @Test
    void findMineReturnsTheRatingForPreFillingTheForm() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-40", "CrUser40");
        await(service.submit(auth, "red-hat", validBody(3.5).put("author", "MineHandle40")));

        JsonObject mine = await(service.findMine(auth, "red-hat")).getJsonObject("review");

        assertEquals(3.5, mine.getDouble("overallRating"), 0.01);
        assertEquals("MineHandle40", mine.getString("author"));
        assertNotNull(mine.getJsonObject("ratings"), "the form pre-fills every category too");
    }

    @Test
    void findMineIsEmptyForSomebodyWhoHasNotRated() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-41", "CrUser41");

        assertNull(await(service.findMine(auth, "red-hat")).getValue("review"),
            "not having rated is a null review, not an error");
    }

    @Test
    void findMineIsEmptyForSomebodySignedOut() throws Exception {
        // The page asks this before it knows who is reading. It must answer, not throw.
        insertCompany("Red Hat", "red-hat");

        assertNull(await(service.findMine(null, "red-hat")).getValue("review"));
    }

    @Test
    void findMineIsEmptyAfterWithdrawing() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-42", "CrUser42");
        JsonObject saved = await(service.submit(auth, "red-hat", validBody(4.0)));

        await(service.delete(auth, UUID.fromString(saved.getString("id"))));

        assertNull(await(service.findMine(auth, "red-hat")).getValue("review"),
            "the form offers to rate again rather than showing a withdrawn rating back");
    }

    // ── The list behind the average ───────────────────────────────────────────

    @Test
    void theListMarksTheReadersOwnRatingAndNobodyElses() throws Exception {
        /*
          "mine" is derived from the token, never from anything the client sends. If it were not,
          anyone could ask which of a company's ratings belonged to a named person - which is the
          whole promise the anonymity is making.
        */
        insertCompany("Red Hat", "red-hat");
        String reader  = insertUser("auth0|cr-50", "CrUser50");
        String bystander  = insertUser("auth0|cr-51", "CrUser51");
        await(service.submit(reader, "red-hat", validBody(5.0).put("author", "MineHandle50")));
        await(service.submit(bystander, "red-hat", validBody(2.0).put("author", "TheirHandle51")));

        var rows = await(service.listFor(reader, "red-hat", 20, 0)).getJsonArray("data");

        assertEquals(2, rows.size());
        long mine = rows.stream().map(o -> (JsonObject) o)
            .filter(o -> Boolean.TRUE.equals(o.getBoolean("mine"))).count();
        assertEquals(1L, mine, "exactly one row is the reader's own");
    }

    @Test
    void theListMarksNothingAsMineForSomebodySignedOut() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String someone = insertUser("auth0|cr-52", "CrUser52");
        await(service.submit(someone, "red-hat", validBody(4.0)));

        var rows = await(service.listFor(null, "red-hat", 20, 0)).getJsonArray("data");

        assertEquals(1, rows.size());
        assertFalse(rows.getJsonObject(0).getBoolean("mine"),
            "a signed-out reader owns none of them");
    }

    @Test
    void aRatingWithNoEndDateReadsAsStillWorkingThere() throws Exception {
        // Said as a fact the card can print, rather than left as a missing field to interpret.
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-53", "CrUser53");
        await(service.submit(auth, "red-hat", validBody(4.0)));

        var row = await(service.listFor(auth, "red-hat", 20, 0)).getJsonArray("data")
            .getJsonObject(0);

        assertTrue(row.getBoolean("current"));
        assertNull(row.getString("workedUntil"));
    }

    @Test
    void aRatingWrittenBeforeAuthorsExistedStillLists() throws Exception {
        // V63 added the column nullable with no backfill, so these rows are real. They render
        // without a byline rather than breaking the list they appear in.
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-54", "CrUser54");
        await(service.submit(auth, "red-hat", validBody(4.0)));

        var row = await(service.listFor(auth, "red-hat", 20, 0)).getJsonArray("data")
            .getJsonObject(0);

        assertNull(row.getString("author"));
    }

    @Test
    void theListIsCappedSoNobodyCanAskForTheWholeTable() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-55", "CrUser55");
        await(service.submit(auth, "red-hat", validBody(4.0)));

        JsonObject page = await(service.listFor(auth, "red-hat", 5000, -10));

        assertEquals(50, page.getInteger("limit"), "an oversized page size is capped");
        assertEquals(0,  page.getInteger("offset"), "a negative offset is floored, not passed to SQL");
    }

    @Test
    void listingACompanyThatDoesNotExistIsRefused() throws Exception {
        String auth = insertUser("auth0|cr-56", "CrUser56");

        assertThrows(Exception.class, () -> await(service.listFor(auth, "no-such-company", 20, 0)));
    }

    // ── Rating an employer we do not have yet ────────────────────────────────

    @Test
    void ratingACompanyWeDoNotHaveCreatesIt() throws Exception {
        /*
          CHANGED DELIBERATELY. This used to be refused outright, on the reasoning that a company
          existing only because somebody claims to have worked there has no anchor. The refusal was
          the wrong end of that trade: the one moment a person is willing to write something is the
          worst moment to answer "we don't have a page for that".

          Created as ghost - the same status a search-created company gets - so it is publicly
          visible and reviewable rather than hidden.
        */
        String auth = insertUser("auth0|cr-new-1", "CrNew1");
        JsonObject body = validBody(4.0).put("companyName", "Brand New Employer Inc");

        JsonObject saved = await(service.submit(auth, "brand-new-employer-inc", body));

        assertNotNull(saved.getLong("companyId"), "the rating is attached to a real company row");
        String status = await(pool.preparedQuery(
                "SELECT status FROM companies WHERE LOWER(TRIM(name)) = LOWER(TRIM($1))")
            .execute(Tuple.of("Brand New Employer Inc"))
            .map(rs -> rs.iterator().next().getString("status")));
        assertEquals("pending_approval", status,
            "held for an admin, not live - a company nobody has vouched for is not the directory's");
    }

    @Test
    void ratingAnExistingCompanyStillUsesIt() throws Exception {
        // The new path must not create a duplicate alongside a company we already hold.
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-new-2", "CrNew2");

        await(service.submit(auth, "red-hat", validBody(4.0).put("companyName", "Red Hat")));

        Long rows = await(pool.preparedQuery(
                "SELECT COUNT(*) AS n FROM companies WHERE LOWER(TRIM(name)) = 'red hat'")
            .execute().map(rs -> rs.iterator().next().getLong("n")));
        assertEquals(1L, rows);
    }

    @Test
    void ratingWithNeitherASlugNorANameIsStillRefused() throws Exception {
        // Creating on demand is not the same as creating from nothing.
        String auth = insertUser("auth0|cr-new-3", "CrNew3");

        assertThrows(Exception.class, () -> await(service.submit(auth, null, validBody(4.0))));
    }

    @Test
    void readingACompanyWeDoNotHaveIsStillNotFound() throws Exception {
        /*
          Only the write path creates. A page view for a company nobody has rated must stay a 404 -
          otherwise every mistyped URL would mint a directory entry.
        */
        String auth = insertUser("auth0|cr-new-4", "CrNew4");

        assertThrows(Exception.class, () -> await(service.listFor(auth, "no-such-company", 20, 0)));
        assertThrows(Exception.class, () -> await(service.findMine(auth, "no-such-company")));
    }

    @Test
    void aCompanyBornFromARatingIsNotPubliclyVisible() throws Exception {
        /*
          The whole point of holding it. Every public surface filters on an allowlist of
          ('approved','ghost'), so pending is excluded by construction rather than by each query
          remembering to exclude it - which is the difference between a rule and a habit.
        */
        String auth = insertUser("auth0|cr-new-5", "CrNew5");
        await(service.submit(auth, "invented-co", validBody(4.0).put("companyName", "Invented Co")));

        Long listed = await(pool.preparedQuery(
                "SELECT COUNT(*) AS n FROM companies "
                + "WHERE LOWER(TRIM(name)) = 'invented co' AND status IN ('approved','ghost')")
            .execute().map(rs -> rs.iterator().next().getLong("n")));
        assertEquals(0L, listed, "it is not on any surface that lists companies");
    }

    @Test
    void anAdminSeesItWaitingAndCanLetItIn() throws Exception {
        String auth = insertUser("auth0|cr-new-6", "CrNew6");
        await(service.submit(auth, "waiting-co", validBody(4.0).put("companyName", "Waiting Co")));
        String admin = insertAdmin("auth0|cr-new-admin-1");

        JsonObject queue = await(newAdminService().getPendingCompanies(admin, 20, 0));
        assertEquals(1, queue.getInteger("total"));
        JsonObject row = queue.getJsonArray("data").getJsonObject(0);
        assertEquals("Waiting Co", row.getString("name"));
        assertEquals(1L, row.getLong("ratingCount"), "how much is actually behind it");

        await(newAdminService().decidePendingCompany(admin, row.getLong("id"), true));
        assertEquals("ghost", statusOf("Waiting Co"), "approved companies join the directory");
    }

    @Test
    void arefusedCompanyStaysOutButKeepsTheRatingSomebodyWrote() throws Exception {
        // The person did write it. Refusing the company hides it; it does not delete their work.
        String auth = insertUser("auth0|cr-new-7", "CrNew7");
        await(service.submit(auth, "bogus-co", validBody(4.0).put("companyName", "Bogus Co")));
        String admin = insertAdmin("auth0|cr-new-admin-2");
        long id = await(newAdminService().getPendingCompanies(admin, 20, 0))
            .getJsonArray("data").getJsonObject(0).getLong("id");

        await(newAdminService().decidePendingCompany(admin, id, false));

        assertEquals("rejected", statusOf("Bogus Co"));
        Long ratings = await(pool.preparedQuery(
                "SELECT COUNT(*) AS n FROM company_reviews WHERE company_id = $1 AND deleted_at IS NULL")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next().getLong("n")));
        assertEquals(1L, ratings, "the rating is kept, it simply has nowhere public to appear");
    }

    @Test
    void decidingTheSameCompanyTwiceIsRefused() throws Exception {
        String auth = insertUser("auth0|cr-new-8", "CrNew8");
        await(service.submit(auth, "twice-co", validBody(4.0).put("companyName", "Twice Co")));
        String admin = insertAdmin("auth0|cr-new-admin-3");
        long id = await(newAdminService().getPendingCompanies(admin, 20, 0))
            .getJsonArray("data").getJsonObject(0).getLong("id");
        await(newAdminService().decidePendingCompany(admin, id, true));

        assertThrows(Exception.class,
            () -> await(newAdminService().decidePendingCompany(admin, id, false)));
    }

    @Test
    void theQueueIsAdminOnly() throws Exception {
        String plain = insertUser("auth0|cr-new-9", "CrNew9");

        assertThrows(Exception.class, () -> await(newAdminService().getPendingCompanies(plain, 20, 0)));
        assertThrows(Exception.class, () -> await(newAdminService().decidePendingCompany(plain, 1L, true)));
    }

    @Test
    void theQueueCountsEverythingAttachedToAPendingCompany() throws Exception {
        /*
          A pending company is reachable, so the person who created it can also add a manager at it
          and file an interview experience for it. Counting only company_reviews reported "1
          rating" for a company carrying three different kinds of content - understating exactly
          what the admin is being asked to decide on.
        */
        String auth = insertUser("auth0|cr-foot-1", "CrFoot1");
        await(service.submit(auth, "footprint-co", validBody(4.0).put("companyName", "Footprint Co")));
        long companyId = await(pool.preparedQuery(
                "SELECT id FROM companies WHERE LOWER(TRIM(name)) = 'footprint co'")
            .execute().map(rs -> rs.iterator().next().getLong("id")));
        await(pool.preparedQuery(
                "INSERT INTO managers(name,company,company_id,title,status,approval_status,"
                + "overall_rating,reviews_count,category_averages) "
                + "VALUES ('Pat Pending','Footprint Co',$1,'Manager','active','pending_approval',"
                + "0,0,'{}'::jsonb)")
            .execute(Tuple.of(companyId)).mapEmpty());

        JsonObject row = await(newAdminService().getPendingCompanies(insertAdmin("auth0|cr-foot-admin"), 20, 0))
            .getJsonArray("data").getJsonObject(0);

        assertEquals(1L, row.getLong("ratingCount"));
        assertEquals(1L, row.getLong("managerCount"), "the manager attached to it is counted too");
        assertNotNull(row.getLong("interviewCount"), "and interview experiences are reported");
    }

    @Test
    void aRejectedCompanyIsNeverOfferedByThePickerAgain() throws Exception {
        /*
          The rejection has to stick. The picker had no status filter beyond 'merged', so a company
          an admin had just refused came straight back as a suggestion - the next person to type
          the name picked it out of the list and attached fresh content to the same row. There was
          no way to make a rejection hold short of deciding it again.
        */
        String auth = insertUser("auth0|cr-rej-1", "CrRej1");
        await(service.submit(auth, "refused-co", validBody(4.0).put("companyName", "Refused Co")));
        String admin = insertAdmin("auth0|cr-rej-admin");
        long id = await(newAdminService().getPendingCompanies(admin, 20, 0))
            .getJsonArray("data").getJsonObject(0).getLong("id");

        // While pending it is still offered, on purpose: a second person naming the same employer
        // should land on the existing row rather than minting a duplicate.
        assertTrue(pickerOffers("Refused Co"), "a pending company is suggestible");

        await(newAdminService().decidePendingCompany(admin, id, false));

        assertFalse(pickerOffers("Refused Co"), "once refused it is gone from the picker");
    }

    @Test
    void anApprovedCompanyIsStillOfferedByThePicker() throws Exception {
        // The other half of the same rule - approving must not remove it.
        String auth = insertUser("auth0|cr-app-1", "CrApp1");
        await(service.submit(auth, "allowed-co", validBody(4.0).put("companyName", "Allowed Co")));
        String admin = insertAdmin("auth0|cr-app-admin");
        long id = await(newAdminService().getPendingCompanies(admin, 20, 0))
            .getJsonArray("data").getJsonObject(0).getLong("id");

        await(newAdminService().decidePendingCompany(admin, id, true));

        assertTrue(pickerOffers("Allowed Co"));
    }

    private boolean pickerOffers(String name) throws Exception {
        var rows = await(companyRepo.searchForPicker(name));
        for (var r : rows) {
            if (name.equalsIgnoreCase(r.getString("name"))) return true;
        }
        return false;
    }

    private org.werkpages.service.AdminService newAdminService() {
        return new org.werkpages.service.AdminService(
            new UserRepository(pool), new ManagerRepository(pool), new ReviewRepository(pool), null,
            new NotificationRepository(pool), companyRepo, new MergeSuggestionsRepository(pool), pool);
    }

    private String insertAdmin(String auth0Id) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id,email,username,role) VALUES ($1,$1||'@t.com',$1,'admin')")
            .execute(Tuple.of(auth0Id)).mapEmpty());
        return auth0Id;
    }

    private String statusOf(String name) throws Exception {
        return await(pool.preparedQuery(
                "SELECT status FROM companies WHERE LOWER(TRIM(name)) = LOWER(TRIM($1))")
            .execute(Tuple.of(name)).map(rs -> rs.iterator().next().getString("status")));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static JsonObject validBody(double overall) {
        JsonObject ratings = new JsonObject();
        for (String c : CompanyReviewRepository.CATEGORIES) ratings.put(c, overall);
        return new JsonObject()
            .put("overallRating", overall)
            .put("ratings", ratings)
            .put("workedFrom", "2021-04")
            .putNull("workedUntil");
    }


    // ── Unfinished forms ──────────────────────────────────────────────────────

    /*
      The rating form lets somebody answer every question and only then sends them to sign in. Most
      do not come back. The manager forms have captured that moment for a long time; this one threw
      it away, so a complete workplace rating was lost every time a person rated before making an
      account. What is kept is never published and never aggregated - it goes to the admin queue
      and nowhere else.
    */

    @Test
    void anUnfinishedRatingIsKeptForAnAdmin() throws Exception {
        insertCompany("Red Hat", "red-hat");

        await(service.captureDraft("red-hat", validBody(4.0).put("author", "QuietOtter77")));

        Row draft = await(pool.query("SELECT kind, company_id, payload FROM captured_drafts")
            .execute().map(rs -> rs.iterator().next()));
        assertEquals("company_rating", draft.getString("kind"));
        assertNotNull(draft.getLong("company_id"), "it was about a company we already hold");
        assertEquals("QuietOtter77", draft.getJsonObject("payload").getString("author"),
            "the answers are kept verbatim, because every form carries different fields");
    }

    @Test
    void anUnfinishedRatingIsNotAPublishedOne() throws Exception {
        insertCompany("Red Hat", "red-hat");

        await(service.captureDraft("red-hat", validBody(4.0)));

        assertEquals(0L, count("SELECT count(*) AS c FROM company_reviews"),
            "a draft is one person's unfinished answer, not a rating - it must not reach the table "
            + "the public averages are computed from");
    }

    @Test
    void finishingTheFormRemovesTheDraftItCameFrom() throws Exception {
        /*
          Otherwise an admin spends their time reading drafts whose authors came back a minute later
          and completed the form - and the queue becomes noise nobody reads.
        */
        insertCompany("Red Hat", "red-hat");
        String auth  = insertUser("auth0|cr-draft", "CrDraft");
        String token = UUID.randomUUID().toString();

        await(service.captureDraft("red-hat", validBody(4.0).put("draftToken", token)));
        assertEquals(1L, count("SELECT count(*) AS c FROM captured_drafts"));

        await(service.submit(auth, "red-hat", validBody(4.0).put("draftToken", token)));

        assertEquals(0L, count("SELECT count(*) AS c FROM captured_drafts"),
            "the work is finished, so it is no longer waiting to be reviewed");
        assertEquals(1L, count("SELECT count(*) AS c FROM company_reviews"));
    }

    @Test
    void aDraftForACompanyWeDoNotHoldIsStillKept() throws Exception {
        // The company is created by the write path, and a draft is not a write path. Discarding the
        // capture because the employer is unknown would lose exactly the submissions worth reading.
        await(service.captureDraft("somewhere-new", validBody(3.0)));

        Row draft = await(pool.query("SELECT company_id, payload FROM captured_drafts")
            .execute().map(rs -> rs.iterator().next()));
        assertNull(draft.getLong("company_id"));
        assertEquals("somewhere-new", draft.getJsonObject("payload").getString("companySlug"));
    }

    private long count(String sql) throws Exception {
        return await(pool.query(sql).execute().map(rs -> rs.iterator().next().getLong("c")));
    }


    // ── Where the work happened ───────────────────────────────────────────────

    /*
      company_reviews has carried the declared ladder since V68 and nothing wrote to it, so a
      workplace rating could only ever be filed against the company as a whole - while a review of
      a manager at that same company, or an interview with it, could name the branch it happened
      at. Ten Walmarts in one city can be ten different places to work, which is the entire reason
      the ladder exists.
    */

    @Test
    void aRatingRecordsWhereTheWorkHappened() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-loc-1", "CrLoc1");

        await(service.submit(auth, "red-hat", validBody(4.0)
            .put("declaredCountry", "Canada")
            .put("declaredState",   "Ontario")
            .put("declaredCity",    "Kitchener")
            .put("declaredPrecision", DeclaredLocation.CITY)));

        Row stored = storedRating();
        assertEquals("Canada",    stored.getString("declared_country"));
        assertEquals("Ontario",   stored.getString("declared_state"));
        assertEquals("Kitchener", stored.getString("declared_city"));
        assertEquals("city",      stored.getString("declared_precision"),
            "precision is stated, not inferred from which columns happen to be filled");
    }

    @Test
    void aCoarseAnswerIsAWholeAnswer() throws Exception {
        // Nobody is made to find a street address to rate a workplace. A province on its own is a
        // real rung, and refusing it would cost the rating rather than improving the location.
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-loc-2", "CrLoc2");

        await(service.submit(auth, "red-hat", validBody(4.0)
            .put("declaredCountry", "Canada")
            .put("declaredState",   "Ontario")
            .put("declaredPrecision", DeclaredLocation.STATE)));

        Row stored = storedRating();
        assertEquals("state", stored.getString("declared_precision"));
        assertNull(stored.getString("declared_city"));
    }

    @Test
    void anExactPickTakesItsCoarseValuesFromTheLocationRow() throws Exception {
        /*
          The building already knows where it is. Trusting the form's coarse values instead would
          let one that had drifted out of sync publish a city the selected address is not in.
        */
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-loc-3", "CrLoc3");
        long location = insertLocation(companyId("red-hat"),
            "175 Bloor St E", "Toronto", "Ontario", "Canada");

        await(service.submit(auth, "red-hat", validBody(4.0)
            .put("companyLocationId", location)
            .put("declaredCity", "Waterloo")            // stale form - the building is in Toronto
            .put("declaredPrecision", DeclaredLocation.EXACT)));

        Row stored = storedRating();
        assertEquals(location,  stored.getLong("company_location_id"));
        assertEquals("exact",   stored.getString("declared_precision"));
        assertEquals("Toronto", stored.getString("declared_city"),
            "the selected building decides the city, not whatever the form last held");
        assertEquals("Ontario", stored.getString("declared_state"));
    }

    @Test
    void aWorkplaceBelongingToAnotherCompanyIsRefused() throws Exception {
        /*
          Accepting this would file a rating of Red Hat against a Canonical address, and Canonical's
          page would then show a branch nobody there has rated. There is no honest request that
          does this - only a stale form or a crafted one.
        */
        insertCompany("Red Hat", "red-hat");
        insertCompany("Canonical", "canonical");
        String auth = insertUser("auth0|cr-loc-4", "CrLoc4");
        long theirs = insertLocation(companyId("canonical"),
            "1 Circle Rd", "London", "England", "United Kingdom");

        JsonObject body = validBody(4.0)
            .put("companyLocationId", theirs)
            .put("declaredPrecision", DeclaredLocation.EXACT);

        assertThrows(Exception.class, () -> await(service.submit(auth, "red-hat", body)));
        assertEquals(0L, count("SELECT COUNT(*) AS c FROM company_reviews"),
            "the whole rating is refused, not stored with the location quietly dropped");
    }

    @Test
    void theRatingIsHandedBackWithItsLocationSoTheFormCanOpenOnIt() throws Exception {
        /*
          Revisiting the form means changing an answer, not starting again - so it opens on the
          location the rating was filed against. A field that came back empty over a stored answer
          reads as the answer having been thrown away, and re-deriving it from wherever the person
          happens to be today would quietly migrate an old rating to a place it did not come from.
        */
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-loc-5", "CrLoc5");
        await(service.submit(auth, "red-hat", validBody(4.0)
            .put("declaredCountry", "Canada")
            .put("declaredState",   "Ontario")
            .put("declaredCity",    "Kitchener")
            .put("declaredPrecision", DeclaredLocation.CITY)));

        JsonObject mine = await(service.findMine(auth, "red-hat")).getJsonObject("review");

        assertEquals("Kitchener", mine.getString("declaredCity"));
        assertEquals("Ontario",   mine.getString("declaredState"));
        assertEquals("Canada",    mine.getString("declaredCountry"));
        assertEquals("city",      mine.getString("declaredPrecision"));
    }

    @Test
    void anEditCanMoveTheRatingToWhereItActuallyHappened() throws Exception {
        // One rating per person per company, so an edit is an upsert. Correcting the location has
        // to reach the stored row, or the correction is accepted and discarded.
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-loc-6", "CrLoc6");
        await(service.submit(auth, "red-hat", validBody(4.0)
            .put("declaredCountry", "Canada")
            .put("declaredPrecision", DeclaredLocation.COUNTRY)));

        await(service.submit(auth, "red-hat", validBody(4.0)
            .put("declaredCountry", "Canada")
            .put("declaredState",   "Ontario")
            .put("declaredCity",    "Kitchener")
            .put("declaredPrecision", DeclaredLocation.CITY)));

        Row stored = storedRating();
        assertEquals("Kitchener", stored.getString("declared_city"));
        assertEquals("city",      stored.getString("declared_precision"));
        assertEquals(1L, count("SELECT COUNT(*) AS c FROM company_reviews"),
            "still one rating - an edit replaces, it does not add");
    }

    @Test
    void coarseningAnExactPickLetsGoOfTheBuilding() throws Exception {
        /*
          Somebody who picked the wrong branch and falls back to the city must not leave a row
          claiming 'city' while still pointing at a building. That is why the five columns move
          together rather than being kept individually: a per-column COALESCE would hold the old id
          against the new precision forever.
        */
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-loc-7", "CrLoc7");
        long location = insertLocation(companyId("red-hat"),
            "175 Bloor St E", "Toronto", "Ontario", "Canada");
        await(service.submit(auth, "red-hat", validBody(4.0)
            .put("companyLocationId", location)
            .put("declaredPrecision", DeclaredLocation.EXACT)));

        await(service.submit(auth, "red-hat", validBody(4.0)
            .put("declaredCountry", "Canada")
            .put("declaredState",   "Ontario")
            .put("declaredCity",    "Kitchener")
            .put("declaredPrecision", DeclaredLocation.CITY)));

        Row stored = storedRating();
        assertEquals("city", stored.getString("declared_precision"));
        assertNull(stored.getLong("company_location_id"),
            "the building went with the precision that required it");
    }

    @Test
    void anEditThatDeclaresNothingKeepsTheStoredLocation() throws Exception {
        /*
          A client that does not know about the location field - an older tab, another consumer of
          the API - sends a rating with no ladder at all. Treating that as "clear it" would let one
          silently erase an answer it never knew to send, so a submission that declares nothing
          leaves what is stored alone. It is the same rule the author handle already follows.
        */
        insertCompany("Red Hat", "red-hat");
        String auth = insertUser("auth0|cr-loc-8", "CrLoc8");
        await(service.submit(auth, "red-hat", validBody(4.0)
            .put("declaredCountry", "Canada")
            .put("declaredState",   "Ontario")
            .put("declaredCity",    "Kitchener")
            .put("declaredPrecision", DeclaredLocation.CITY)));

        await(service.submit(auth, "red-hat", validBody(2.0)));

        Row stored = storedRating();
        assertEquals(2.0, stored.getBigDecimal("overall_rating").doubleValue(),
            "the rating it did send was taken");
        assertEquals("Kitchener", stored.getString("declared_city"),
            "and the location it said nothing about was left alone");
        assertEquals("city", stored.getString("declared_precision"));
    }

    /** The single rating in the database, for asserting on what was actually stored. */
    private static Row storedRating() throws Exception {
        return await(pool.query("""
                SELECT overall_rating, declared_country, declared_state, declared_city,
                       declared_precision, company_location_id
                FROM company_reviews
                """).execute().map(rs -> rs.iterator().next()));
    }

    private static long insertLocation(long companyId, String street, String city,
                                       String state, String country) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO company_locations
                    (company_id, source, source_place_id, display_name, street, city, state, country)
                VALUES ($1, 'manual', $2, $3, $4, $5, $6, $7)
                RETURNING id
                """)
            .execute(Tuple.of(companyId, street, "Office", street, city, state, country))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertCompany(String name, String slug) throws Exception {
        await(pool.preparedQuery("INSERT INTO companies(name, slug, status) VALUES ($1,$2,'approved')")
            .execute(Tuple.of(name, slug)).mapEmpty());
        return slug;
    }

    private long companyId(String slug) throws Exception {
        return await(pool.preparedQuery("SELECT id FROM companies WHERE slug = $1")
            .execute(Tuple.of(slug)).map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery("INSERT INTO users(auth0_id,email,username) VALUES ($1,$2,$3)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username)).mapEmpty());
        return auth0Id;
    }

    private UUID adminId() throws Exception {
        String auth = insertUser("auth0|cr-admin-" + UUID.randomUUID(), "CrAdmin" + System.nanoTime());
        return await(pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth)).map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}

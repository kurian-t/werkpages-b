package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.InterviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.InterviewService;
import org.werkpages.service.ServiceException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage of interview experience reviews through {@link InterviewService}.
 *
 * <p>The behaviours worth guarding, and why:
 * <ul>
 *   <li><b>Outcome segmentation.</b> The whole feature rests on being able to separate what people
 *       who got the offer said from what people who were rejected said. If the filters leak, the
 *       numbers on the page are worse than having no numbers.
 *   <li><b>The contribution gate.</b> Category averages are withheld from non-contributors, and
 *       that must not be confusable with "too few reviews to average" — they are different
 *       messages to the reader and different flags on the wire.
 *   <li><b>One review per person per company per year.</b> Nothing about an interview is
 *       verifiable, so this constraint and the daily ceiling are the only brakes on fabrication.
 * </ul>
 */
@Testcontainers
class InterviewReviewIntegrationTest {

    private static final int YEAR = LocalDate.now().getYear();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static InterviewService service;
    static InterviewRepository interviewRepo;

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

        interviewRepo = new InterviewRepository(pool);
        service = new InterviewService(interviewRepo, new CompanyRepository(pool), new UserRepository(pool, null));
    }

    @BeforeEach
    void cleanDb() throws Exception {
        // interview_review_rounds references interview_reviews, so it has to go in the same
        // statement — TRUNCATE refuses to leave a referencing table behind.
        await(pool.query("TRUNCATE interview_review_rounds, interview_review_deletions, interview_reviews, company_interview_stats").execute());
        await(pool.query("TRUNCATE managers, users, companies CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Create
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void createReview_persistsEveryFieldAndEchoesItBack() throws Exception {
        String auth0Id = insertUser("creator");
        insertCompany("Northwind Labs", "northwind-labs");

        JsonObject created = await(service.createReview(auth0Id, "northwind-labs", fullBody()));

        assertNotNull(created.getString("id"));
        assertEquals(4.5, created.getDouble("overallRating"));
        assertEquals(4.0, created.getDouble("communication"));
        assertEquals(3.5, created.getDouble("respectForTime"));
        assertEquals(5.0, created.getDouble("roleClarity"));
        assertEquals(4.0, created.getDouble("processFairness"));
        assertEquals(2.5, created.getDouble("nextStepTransparency"));
        assertEquals(3, created.getInteger("difficulty"));
        assertEquals("offer", created.getString("outcome"));
        assertEquals(3, created.getInteger("rounds"), "count is derived from the round list");
        assertEquals("2_4_weeks", created.getString("processLength"));
        assertEquals("Engineering", created.getString("roleCategory"));
        assertEquals(YEAR, created.getInteger("interviewYear"));
    }

    @Test
    void createReview_acceptsMinimalBody_leavingOptionalFieldsNull() throws Exception {
        String auth0Id = insertUser("minimalist");
        insertCompany("Sparse Co", "sparse-co");

        JsonObject created = await(service.createReview(auth0Id, "sparse-co", new JsonObject()
            .put("overallRating", 3.0)
            .put("outcome", "no_offer")
            .put("interviewYear", YEAR)));

        assertEquals(3.0, created.getDouble("overallRating"));
        assertNull(created.getValue("communication"));
        assertNull(created.getValue("difficulty"));
        assertNull(created.getValue("roleCategory"));
    }

    @Test
    void createReview_roundsRatingToOneDecimal_soTheEchoMatchesWhatWasStored() throws Exception {
        String auth0Id = insertUser("rounder");
        insertCompany("Precision Inc", "precision-inc");

        JsonObject created = await(service.createReview(auth0Id, "precision-inc", new JsonObject()
            .put("overallRating", 4.26)
            .put("outcome", "offer")
            .put("interviewYear", YEAR)));

        assertEquals(4.3, created.getDouble("overallRating"),
            "the column is NUMERIC(2,1); the API must not claim to have stored 4.26");
    }

    @Test
    void createReview_trimsRoleCategory_andTreatsWhitespaceOnlyAsAbsent() throws Exception {
        String auth0Id = insertUser("trimmer");
        insertCompany("Trim Co", "trim-co");

        JsonObject padded = await(service.createReview(auth0Id, "trim-co", new JsonObject()
            .put("overallRating", 3.0).put("outcome", "offer").put("interviewYear", YEAR)
            .put("roleCategory", "  Design  ")));
        assertEquals("Design", padded.getString("roleCategory"));

        String other = insertUser("blanker");
        JsonObject blank = await(service.createReview(other, "trim-co", new JsonObject()
            .put("overallRating", 3.0).put("outcome", "offer").put("interviewYear", YEAR)
            .put("roleCategory", "   ")));
        assertNull(blank.getValue("roleCategory"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Validation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void createReview_rejectsMissingBody() {
        assertEquals(400, statusOfFailure(service.createReview("auth0|x", "any", null)));
    }

    @Test
    void createReview_rejectsMissingRequiredFields() throws Exception {
        String auth0Id = insertUser("incomplete");
        insertCompany("Required Co", "required-co");

        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "required-co",
            new JsonObject().put("outcome", "offer").put("interviewYear", YEAR))),
            "overallRating is required");

        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "required-co",
            new JsonObject().put("overallRating", 3.0).put("interviewYear", YEAR))),
            "outcome is required — it is what makes the averages interpretable");

        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "required-co",
            new JsonObject().put("overallRating", 3.0).put("outcome", "offer"))),
            "interviewYear is required");
    }

    @Test
    void createReview_rejectsOutOfRangeValues() throws Exception {
        String auth0Id = insertUser("outofrange");
        insertCompany("Range Co", "range-co");

        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "range-co",
            base().put("overallRating", 5.5))), "rating above 5");
        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "range-co",
            base().put("overallRating", -0.5))), "rating below 0");
        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "range-co",
            base().put("communication", 9.0))), "category rating above 5");
        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "range-co",
            base().put("difficulty", 0))), "difficulty below 1");
        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "range-co",
            base().put("difficulty", 6))), "difficulty above 5");
        JsonArray tooMany = new JsonArray();
        for (int i = 0; i < 11; i++) tooMany.add("phone");
        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "range-co",
            base().put("rounds", tooMany))), "more rounds than a process plausibly has");
        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "range-co",
            base().put("roleCategory", "x".repeat(101)))), "roleCategory over 100 chars");
    }

    @Test
    void createReview_rejectsUnknownEnumValues() throws Exception {
        String auth0Id = insertUser("enums");
        insertCompany("Enum Co", "enum-co");

        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "enum-co",
            base().put("outcome", "ghosted"))));
        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "enum-co",
            base().put("rounds", new JsonArray().add("telepathy")))));
        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "enum-co",
            base().put("processLength", "a_while"))));
    }

    @Test
    void createReview_rejectsFutureAndAncientYears() throws Exception {
        String auth0Id = insertUser("timetraveller");
        insertCompany("Year Co", "year-co");

        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "year-co",
            base().put("interviewYear", YEAR + 1))), "nobody has interviewed next year yet");
        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "year-co",
            base().put("interviewYear", 1999))), "before the supported range");
    }

    @Test
    void createReview_rejectsUnknownCompanyAndUnknownUser() throws Exception {
        String auth0Id = insertUser("known");
        insertCompany("Known Co", "known-co");

        assertEquals(404, statusOfFailure(service.createReview(auth0Id, "no-such-company", base())),
            "an interview review must never bring a company into existence");
        assertEquals(404, statusOfFailure(service.createReview("auth0|ghost", "known-co", base())));
        assertEquals(401, statusOfFailure(service.createReview(null, "known-co", base())));
        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "  ", base())));
    }

    @Test
    void createReview_rejectsBannedUser() throws Exception {
        String auth0Id = insertUser("banned");
        // is_banned is derived from a LEFT JOIN on banned_users, not a column on users.
        await(pool.preparedQuery("""
                INSERT INTO banned_users(user_id, reason, banned_by)
                SELECT id, 'test', 'test-suite' FROM users WHERE auth0_id = $1
                """)
            .execute(Tuple.of(auth0Id)));
        insertCompany("Ban Co", "ban-co");

        assertEquals(403, statusOfFailure(service.createReview(auth0Id, "ban-co", base())));
    }

    /**
     * A blank string is not the same as an absent value, and the two arrive by different routes:
     * a missing JSON key versus a form field the user cleared. Both must be handled, and for
     * optional fields both must mean "not provided" rather than "the empty string".
     */
    @Test
    void createReview_treatsBlankStringsAsAbsentForOptionalFieldsAndRejectsThemForRequiredOnes() throws Exception {
        String auth0Id = insertUser("blanks");
        insertCompany("Blank Co", "blank-co");

        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "blank-co",
            base().put("outcome", ""))), "outcome is required, so blank is a rejection");

        JsonObject created = await(service.createReview(auth0Id, "blank-co", base()
            .put("processLength", "")));
        assertNull(created.getValue("processLength"));
    }

    @Test
    void createReview_treatsBlankAuth0IdAsSignedOut() throws Exception {
        insertCompany("Anon Co", "anon-co");
        assertEquals(401, statusOfFailure(service.createReview("   ", "anon-co", base())));
    }

    @Test
    void createReview_rejectsNullCompanySlug() throws Exception {
        insertUser("nullslug");
        assertEquals(400, statusOfFailure(service.createReview(insertUser("ns2"), null, base())));
    }

    @Test
    void deleteReview_treatsBlankAuth0IdAsSignedOut() {
        assertEquals(401, statusOfFailure(service.deleteReview("   ", UUID.randomUUID().toString())));
    }

    @Test
    void gate_treatsBlankAuth0IdAsSignedOut() throws Exception {
        insertCompany("Blankgate Co", "blankgate-co");
        submit("bg1", "blankgate-co", 4.0, "offer", YEAR);
        submit("bg2", "blankgate-co", 4.0, "offer", YEAR);
        submit("bg3", "blankgate-co", 4.0, "offer", YEAR);

        JsonObject json = await(service.getCompanyInterviews("blankgate-co", null, null, "   "));

        assertTrue(json.getBoolean("gated"));
        assertFalse(json.getBoolean("hasContributed"));
        assertFalse(await(service.hasContributed("   ")).getBoolean("hasContributed"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Abuse limits
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void createReview_rejectsSecondReviewForSameCompanyAndYear() throws Exception {
        String auth0Id = insertUser("repeater");
        insertCompany("Repeat Co", "repeat-co");

        await(service.createReview(auth0Id, "repeat-co", base()));

        assertEquals(409, statusOfFailure(service.createReview(auth0Id, "repeat-co", base())),
            "one review per person per company per year");
    }

    @Test
    void createReview_allowsSameCompanyInADifferentYear() throws Exception {
        String auth0Id = insertUser("returner");
        insertCompany("Return Co", "return-co");

        await(service.createReview(auth0Id, "return-co", base().put("interviewYear", YEAR - 1)));
        JsonObject second = await(service.createReview(auth0Id, "return-co", base()));

        assertNotNull(second.getString("id"),
            "interviewing at the same company in a later year is a legitimate second data point");
    }

    @Test
    void createReview_enforcesDailyCeiling() throws Exception {
        String auth0Id = insertUser("spammer");
        for (int i = 1; i <= 4; i++) insertCompany("Target " + i, "target-" + i);

        for (int i = 1; i <= 3; i++) {
            await(service.createReview(auth0Id, "target-" + i, base()));
        }

        assertEquals(429, statusOfFailure(service.createReview(auth0Id, "target-4", base())));
    }

    @Test
    void softDeletedReviewsStillCountTowardTheDailyCeiling() throws Exception {
        // Deleting does not refund the day's allowance. Each company here is different, so the
        // per-company cooldown is not what is doing the work.
        String auth0Id = insertUser("churner");
        for (int i = 1; i <= 4; i++) insertCompany("Churn " + i, "churn-" + i);

        for (int i = 1; i <= 3; i++) {
            JsonObject created = await(service.createReview(auth0Id, "churn-" + i, base()));
            await(service.deleteReview(auth0Id, created.getString("id")));
        }

        assertEquals(429, statusOfFailure(service.createReview(auth0Id, "churn-4", base())),
            "delete-and-resubmit must not buy a fresh daily allowance");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Country, ownership and editing
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void countryNarrowsOnlyTheComparison() throws Exception {
        // Interviewing at a global company in Canada is not the same process as in the US.
        insertCompany("Global Co", "global-co");
        String viewer = null;
        for (String[] row : new String[][] { {"c1","Canada","5.0"}, {"c2","Canada","5.0"},
                                             {"c3","Canada","5.0"}, {"c4","United States","1.0"} }) {
            String auth0Id = insertUser(row[0]);
            await(service.createReview(auth0Id, "global-co", base()
                .put("overallRating", Double.parseDouble(row[2]))
                .put("country", row[1])));
            viewer = auth0Id;
        }

        JsonObject all = await(service.getCompanyInterviews("global-co", null, null, viewer));
        JsonObject canada = await(service.getCompanyInterviews("global-co", null, "Canada", viewer));

        assertEquals(all.getDouble("avgRating"), canada.getDouble("avgRating"),
            "the summary never moves when the chart is narrowed");
        assertEquals(4, all.getJsonObject("categoryComparison").getJsonObject("overall").getInteger("count"));
        assertEquals(3, canada.getJsonObject("categoryComparison").getJsonObject("overall").getInteger("count"));
        assertEquals("Canada", canada.getString("country"));
    }

    @Test
    void listsCountriesThatHaveInterviews() throws Exception {
        insertCompany("Countries Co", "countries-co");
        String viewer = insertUser("cc1");
        await(service.createReview(viewer, "countries-co", base().put("country", "Canada")));
        await(service.createReview(insertUser("cc2"), "countries-co", base().put("country", "Canada")));
        await(service.createReview(insertUser("cc3"), "countries-co", base()));

        JsonArray countries = await(service.getCompanyInterviews("countries-co", null, null, viewer))
            .getJsonArray("countries");

        assertEquals(1, countries.size(), "a review that did not say is not a country to filter to");
        assertEquals("Canada", countries.getJsonObject(0).getString("country"));
        assertEquals(2, countries.getJsonObject(0).getInteger("count"));
    }

    @Test
    void tellsYouWhenTheReviewOnScreenIsYourOwn() throws Exception {
        insertCompany("Mine Co", "mine-co");
        String mine = insertUser("owner");
        String stranger = insertUser("stranger");
        JsonObject created = await(service.createReview(mine, "mine-co", base()));

        JsonObject asOwner = await(service.getCompanyInterviews("mine-co", null, null, mine));
        assertEquals(created.getString("id"), asOwner.getJsonObject("myInterview").getString("id"));

        assertNull(await(service.getCompanyInterviews("mine-co", null, null, stranger)).getValue("myInterview"));
        assertNull(await(service.getCompanyInterviews("mine-co", null, null, null)).getValue("myInterview"));
    }

    @Test
    void editingReplacesTheContentAndTheRounds() throws Exception {
        insertCompany("Edit Co", "edit-co");
        String auth0Id = insertUser("editor");
        JsonObject created = await(service.createReview(auth0Id, "edit-co", base()
            .put("rounds", new JsonArray().add("phone").add("panel"))));

        JsonObject updated = await(service.updateReview(auth0Id, created.getString("id"), base()
            .put("overallRating", 2.0)
            .put("outcome", "no_offer")
            .put("country", "United States")
            .put("rounds", new JsonArray().add("take_home"))));

        assertEquals(2.0, updated.getDouble("overallRating"));
        assertEquals("no_offer", updated.getString("outcome"));
        assertEquals("United States", updated.getString("country"));
        assertEquals(1, updated.getInteger("rounds"));
        assertEquals(List.of("take_home"), roundTypesOf(created.getString("id")),
            "an edit supplies the whole list; merging would strand old rounds inside the new process");
    }

    @Test
    void editingRecalculatesTheCompanyAggregates() throws Exception {
        insertCompany("Recalc Co", "recalc-co");
        String auth0Id = insertUser("recalc");
        JsonObject created = await(service.createReview(auth0Id, "recalc-co", base().put("overallRating", 5.0)));
        assertEquals(5.0, await(service.getCompanyInterviews("recalc-co", null, null, null)).getDouble("avgRating"));

        await(service.updateReview(auth0Id, created.getString("id"), base().put("overallRating", 1.0)));

        assertEquals(1.0, await(service.getCompanyInterviews("recalc-co", null, null, null)).getDouble("avgRating"),
            "the cached stats follow an edit without an application call");
    }

    @Test
    void youCannotEditSomebodyElsesReview() throws Exception {
        insertCompany("Theirs Co", "theirs-co");
        String owner = insertUser("theowner");
        String outsider = insertUser("theoutsider");
        JsonObject created = await(service.createReview(owner, "theirs-co", base()));

        assertEquals(404, statusOfFailure(service.updateReview(outsider, created.getString("id"), base())));
        assertEquals(400, statusOfFailure(service.updateReview(owner, "not-a-uuid", base())));
        assertEquals(400, statusOfFailure(service.updateReview(owner, created.getString("id"), null)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Delete
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void deletingDetachesTheAuthorImmediately() throws Exception {
        // What someone wrote is theirs to take their name off, and that should not wait three days.
        String auth0Id = insertUser("detach");
        insertCompany("Detach Co", "detach-co");
        JsonObject created = await(service.createReview(auth0Id, "detach-co", base()));

        await(service.deleteReview(auth0Id, created.getString("id")));

        assertNull(await(pool.preparedQuery("SELECT user_id FROM interview_reviews WHERE id = $1")
            .execute(Tuple.of(UUID.fromString(created.getString("id"))))
            .map(rs -> rs.iterator().next().getValue("user_id"))));
    }

    @Test
    void aDeletedReviewComesBackAnonymouslyOnceTheWindowPasses() throws Exception {
        // A company should not be able to lose inconvenient feedback because one contributor was
        // talked into removing it.
        String auth0Id = insertUser("returner2");
        insertCompany("Return Co", "return-co2");
        JsonObject created = await(service.createReview(auth0Id, "return-co2", base()));
        await(service.deleteReview(auth0Id, created.getString("id")));
        assertEquals(0, await(service.getCompanyInterviews("return-co2", null, null, null))
            .getInteger("reviewCount"));

        // Age the deletion past the three-day window.
        await(pool.preparedQuery(
                "UPDATE interview_reviews SET deleted_at = now() - INTERVAL '4 days' WHERE id = $1")
            .execute(Tuple.of(UUID.fromString(created.getString("id")))));
        assertEquals(1, await(interviewRepo.restoreExpiredDeletions()));

        assertEquals(1, await(service.getCompanyInterviews("return-co2", null, null, null))
            .getInteger("reviewCount"), "back in the numbers, with nobody's name on it");
    }

    @Test
    void aFreshDeletionIsNotRestoredEarly() throws Exception {
        String auth0Id = insertUser("tooSoon");
        insertCompany("Soon Co", "soon-co");
        JsonObject created = await(service.createReview(auth0Id, "soon-co", base()));
        await(service.deleteReview(auth0Id, created.getString("id")));

        assertEquals(0, await(interviewRepo.restoreExpiredDeletions()));
        assertEquals(0, await(service.getCompanyInterviews("soon-co", null, null, null))
            .getInteger("reviewCount"));
    }

    @Test
    void youCannotReplaceAReviewThatIsComingBack() throws Exception {
        // Otherwise the restored anonymous copy and the replacement would count one person twice.
        String auth0Id = insertUser("replacer");
        insertCompany("Replace Co", "replace-co");
        JsonObject created = await(service.createReview(auth0Id, "replace-co", base()));
        await(service.deleteReview(auth0Id, created.getString("id")));

        assertEquals(409, statusOfFailure(service.createReview(auth0Id, "replace-co", base())));
    }

    @Test
    void deletingGivesUpTheAccessItBought() throws Exception {
        String auth0Id = insertUser("gaveup");
        insertCompany("Gave Co", "gave-co");
        JsonObject created = await(service.createReview(auth0Id, "gave-co", base()));
        assertTrue(await(service.hasContributed(auth0Id)).getBoolean("hasContributed"));

        await(service.deleteReview(auth0Id, created.getString("id")));

        assertFalse(await(service.hasContributed(auth0Id)).getBoolean("hasContributed"),
            "the row survives anonymously, but it is no longer theirs to be credited for");
    }

    @Test
    void deleteReview_removesItFromAggregatesButKeepsTheRow() throws Exception {
        String auth0Id = insertUser("deleter");
        long companyId = insertCompany("Delete Co", "delete-co");

        JsonObject created = await(service.createReview(auth0Id, "delete-co", base()));
        assertEquals(1, await(service.getCompanyInterviews("delete-co", null, null, null))
            .getInteger("reviewCount"));

        await(service.deleteReview(auth0Id, created.getString("id")));

        assertEquals(0, await(service.getCompanyInterviews("delete-co", null, null, null))
            .getInteger("reviewCount"));
        assertEquals(1L, rowCount("SELECT COUNT(*) AS c FROM interview_reviews WHERE company_id = " + companyId),
            "soft delete keeps the row for moderation history");
    }

    @Test
    void deleteReview_rejectsAnotherUsersReviewAndMalformedIds() throws Exception {
        String owner   = insertUser("owner");
        String outsider = insertUser("outsider");
        insertCompany("Owned Co", "owned-co");

        JsonObject created = await(service.createReview(owner, "owned-co", base()));

        assertEquals(404, statusOfFailure(service.deleteReview(outsider, created.getString("id"))),
            "a stranger must not be able to delete someone else's review");
        assertEquals(400, statusOfFailure(service.deleteReview(owner, "not-a-uuid")));
        assertEquals(404, statusOfFailure(service.deleteReview(owner, UUID.randomUUID().toString())));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Reads: outcome segmentation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void companyInterviews_reportsOutcomeSplitSeparately() throws Exception {
        insertCompany("Split Co", "split-co");
        submit("happy",  "split-co", 5.0, "offer",    YEAR);
        submit("bitter", "split-co", 1.0, "no_offer", YEAR);
        submit("gone",   "split-co", 3.0, "withdrew", YEAR);

        JsonObject json = await(service.getCompanyInterviews("split-co", null, null, null));
        JsonObject split = json.getJsonObject("outcomeSplit");

        assertEquals(1, split.getJsonObject("offer").getInteger("count"));
        assertEquals(5.0, split.getJsonObject("offer").getDouble("avgRating"));
        assertEquals(1, split.getJsonObject("noOffer").getInteger("count"));
        assertEquals(1.0, split.getJsonObject("noOffer").getDouble("avgRating"));
        assertEquals(1, split.getJsonObject("withdrew").getInteger("count"));
        assertEquals(3.0, json.getDouble("avgRating"), "the blended average is still reported");
    }

    @Test
    void companyInterviews_includesOutcomesNobodySelected_asZero() throws Exception {
        insertCompany("Offers Only", "offers-only");
        submit("a", "offers-only", 4.0, "offer", YEAR);

        JsonObject split = await(service.getCompanyInterviews("offers-only", null, null, null))
            .getJsonObject("outcomeSplit");

        assertEquals(0, split.getJsonObject("pending").getInteger("count"),
            "an absent key and a zero count mean different things to a reader");
        assertNull(split.getJsonObject("pending").getValue("avgRating"));
        assertEquals(0, split.getJsonObject("noOffer").getInteger("count"));
    }

    @Test
    void comparisonCarriesAllThreeSeriesAtOnce() throws Exception {
        // Showing offers and rejections side by side is the point. Making someone filter to one,
        // memorise a number, filter to the other and compare in their head is what this replaces.
        insertCompany("Filter Co", "filter-co");
        String contributor = submit("filterer", "filter-co", 5.0, "offer", YEAR);
        submit("b", "filter-co", 5.0, "offer",    YEAR);
        submit("c", "filter-co", 5.0, "offer",    YEAR);
        submit("d", "filter-co", 1.0, "no_offer", YEAR);
        submit("e", "filter-co", 1.0, "no_offer", YEAR);
        submit("f", "filter-co", 1.0, "no_offer", YEAR);

        JsonObject comparison = await(service.getCompanyInterviews("filter-co", null, null, contributor))
            .getJsonObject("categoryComparison");

        assertEquals(6, comparison.getJsonObject("overall").getInteger("count"));
        assertEquals(3, comparison.getJsonObject("offer").getInteger("count"));
        assertEquals(3, comparison.getJsonObject("noOffer").getInteger("count"));
        assertEquals(5.0, comparison.getJsonObject("offer").getDouble("overallRating"));
        assertEquals(1.0, comparison.getJsonObject("noOffer").getDouble("overallRating"),
            "rejected candidates rate lower; that is the signal, not noise to be averaged away");
        assertEquals(3.0, comparison.getJsonObject("overall").getDouble("overallRating"));
    }

    @Test
    void aSeriesWithNobodyInItStillAppears() throws Exception {
        // "Nobody who was rejected has reported here" is information. An absent key reads as zero.
        insertCompany("Offers Only Co", "offers-only-co");
        String viewer = submit("o1", "offers-only-co", 4.0, "offer", YEAR);
        submit("o2", "offers-only-co", 4.0, "offer", YEAR);
        submit("o3", "offers-only-co", 4.0, "offer", YEAR);

        JsonObject noOffer = await(service.getCompanyInterviews("offers-only-co", null, null, viewer))
            .getJsonObject("categoryComparison").getJsonObject("noOffer");

        assertEquals(0, noOffer.getInteger("count"));
        assertNull(noOffer.getValue("overallRating"));
    }

    @Test
    void theSummaryNeverMovesWhenTheChartIsNarrowedByRole() throws Exception {
        // A company's headline rating changing because someone explored the chart below it reads
        // as the page contradicting itself.
        insertCompany("Stable Co", "stable-co");
        String viewer = submit("s1", "stable-co", 5.0, "offer", YEAR, "Engineering");
        submit("s2", "stable-co", 5.0, "offer", YEAR, "Engineering");
        submit("s3", "stable-co", 5.0, "offer", YEAR, "Engineering");
        submit("s4", "stable-co", 1.0, "offer", YEAR, "Sales");

        JsonObject all = await(service.getCompanyInterviews("stable-co", null, null, viewer));
        JsonObject engineering = await(service.getCompanyInterviews("stable-co", "Engineering", null, viewer));

        assertEquals(all.getInteger("reviewCount"), engineering.getInteger("reviewCount"));
        assertEquals(all.getDouble("avgRating"), engineering.getDouble("avgRating"));
        assertEquals(all.getJsonObject("categoryAverages"), engineering.getJsonObject("categoryAverages"));

        assertEquals(4, all.getJsonObject("categoryComparison").getJsonObject("overall").getInteger("count"));
        assertEquals(3, engineering.getJsonObject("categoryComparison").getJsonObject("overall").getInteger("count"),
            "only the comparison narrows");
        assertEquals("Engineering", engineering.getString("role"));
    }

    @Test
    void companyInterviews_listsRoleCategoriesPresentInTheData() throws Exception {
        insertCompany("Roles Co", "roles-co");
        submit("r1", "roles-co", 4.0, "offer", YEAR, "Engineering");
        submit("r2", "roles-co", 4.0, "offer", YEAR, "Engineering");
        submit("r3", "roles-co", 4.0, "offer", YEAR, "Design");
        submit("r4", "roles-co", 4.0, "offer", YEAR, null);

        JsonArray roles = await(service.getCompanyInterviews("roles-co", null, null, null))
            .getJsonArray("roleCategories");

        assertEquals(2, roles.size(), "reviews with no role must not become a blank filter option");
        assertEquals("Engineering", roles.getJsonObject(0).getString("role"), "most common first");
        assertEquals(2, roles.getJsonObject(0).getInteger("count"));
    }

    @Test
    void companyInterviews_returnsEmptyShapeForCompanyWithNoReviews() throws Exception {
        insertCompany("Quiet Co", "quiet-co");

        JsonObject json = await(service.getCompanyInterviews("quiet-co", null, null, null));

        assertEquals(0, json.getInteger("reviewCount"));
        assertNull(json.getValue("avgRating"));
        assertTrue(json.getJsonArray("roleCategories").isEmpty());
        assertEquals(0, json.getJsonObject("outcomeSplit").getJsonObject("offer").getInteger("count"));
    }

    @Test
    void companyInterviews_rejectsUnknownCompany() {
        assertEquals(404, statusOfFailure(
            service.getCompanyInterviews("no-such-company", null, null, null)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Reads: the contribution gate
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void categoryAverages_areWithheldFromSignedOutViewers() throws Exception {
        insertCompany("Gated Co", "gated-co");
        submit("g1", "gated-co", 4.0, "offer", YEAR);
        submit("g2", "gated-co", 4.0, "offer", YEAR);
        submit("g3", "gated-co", 4.0, "offer", YEAR);

        JsonObject json = await(service.getCompanyInterviews("gated-co", null, null, null));

        assertNull(json.getValue("categoryAverages"));
        assertTrue(json.getBoolean("gated"));
        assertFalse(json.getBoolean("hasContributed"));
        assertEquals(3, json.getInteger("reviewCount"),
            "the headline count stays public so the page is still worth landing on");
    }

    @Test
    void categoryAverages_areWithheldFromSignedInNonContributors() throws Exception {
        insertCompany("Lurker Co", "lurker-co");
        submit("l1", "lurker-co", 4.0, "offer", YEAR);
        submit("l2", "lurker-co", 4.0, "offer", YEAR);
        submit("l3", "lurker-co", 4.0, "offer", YEAR);
        String lurker = insertUser("lurker");

        JsonObject json = await(service.getCompanyInterviews("lurker-co", null, null, lurker));

        assertNull(json.getValue("categoryAverages"));
        assertTrue(json.getBoolean("gated"));
        assertFalse(json.getBoolean("hasContributed"));
    }

    @Test
    void categoryAverages_areRevealedToContributors() throws Exception {
        insertCompany("Open Co", "open-co");
        String contributor = insertUser("contributor");
        insertCompany("Elsewhere", "elsewhere");
        await(service.createReview(contributor, "elsewhere", base()));

        submit("o1", "open-co", 4.0, "offer", YEAR);
        submit("o2", "open-co", 4.0, "offer", YEAR);
        submit("o3", "open-co", 4.0, "offer", YEAR);

        JsonObject json = await(service.getCompanyInterviews("open-co", null, null, contributor));

        assertTrue(json.getBoolean("hasContributed"));
        assertFalse(json.getBoolean("gated"));
        assertNotNull(json.getJsonObject("categoryAverages"),
            "contributing anywhere unlocks the breakdown everywhere — the gate rewards participation");
    }

    @Test
    void categoryAverages_areWithheldBelowThreshold_butNotReportedAsGated() throws Exception {
        insertCompany("Thin Co", "thin-co");
        String contributor = submit("t1", "thin-co", 4.0, "offer", YEAR);
        submit("t2", "thin-co", 4.0, "offer", YEAR);

        JsonObject json = await(service.getCompanyInterviews("thin-co", null, null, contributor));

        assertNull(json.getValue("categoryAverages"));
        assertFalse(json.getBoolean("gated"), "the viewer contributed — this is a data problem, not a gate");
        assertTrue(json.getBoolean("belowThreshold"));
        assertNull(json.getValue("categoryComparison"), "withheld on the same gate as the averages");
    }

    @Test
    void categoryAverages_reportEachCategorySeparately() throws Exception {
        insertCompany("Detail Co", "detail-co");
        String viewer = null;
        for (int i = 1; i <= 3; i++) {
            String auth0Id = insertUser("d" + i);
            await(service.createReview(auth0Id, "detail-co", new JsonObject()
                .put("overallRating", 4.0)
                .put("communication", 5.0)
                .put("respectForTime", 4.0)
                .put("roleClarity", 3.0)
                .put("processFairness", 2.0)
                .put("nextStepTransparency", 1.0)
                .put("outcome", "offer")
                .put("interviewYear", YEAR)));
            viewer = auth0Id;
        }

        JsonObject averages = await(service.getCompanyInterviews("detail-co", null, null, viewer))
            .getJsonObject("categoryAverages");

        assertEquals(5.0, averages.getDouble("communication"));
        assertEquals(4.0, averages.getDouble("respectForTime"));
        assertEquals(3.0, averages.getDouble("roleClarity"));
        assertEquals(2.0, averages.getDouble("processFairness"));
        assertEquals(1.0, averages.getDouble("nextStepTransparency"));
    }

    @Test
    void hasContributed_tracksInterviewReviewsOnly() throws Exception {
        String auth0Id = insertUser("checker");
        insertCompany("Check Co", "check-co");

        assertFalse(await(service.hasContributed(auth0Id)).getBoolean("hasContributed"));
        assertFalse(await(service.hasContributed(null)).getBoolean("hasContributed"));
        assertFalse(await(service.hasContributed("auth0|nobody")).getBoolean("hasContributed"));

        JsonObject created = await(service.createReview(auth0Id, "check-co", base()));
        assertTrue(await(service.hasContributed(auth0Id)).getBoolean("hasContributed"));

        await(service.deleteReview(auth0Id, created.getString("id")));
        assertFalse(await(service.hasContributed(auth0Id)).getBoolean("hasContributed"),
            "deleting your only review gives up the access it bought");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Difficulty is never a quality score
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void difficulty_isReportedSeparatelyAndNeverFoldedIntoRating() throws Exception {
        insertCompany("Hard Co", "hard-co");
        String viewer = null;
        for (int i = 1; i <= 3; i++) {
            String auth0Id = insertUser("h" + i);
            await(service.createReview(auth0Id, "hard-co", new JsonObject()
                .put("overallRating", 5.0)
                .put("difficulty", 5)
                .put("outcome", "offer")
                .put("interviewYear", YEAR)));
            viewer = auth0Id;
        }

        JsonObject json = await(service.getCompanyInterviews("hard-co", null, null, viewer));

        assertEquals(5.0, json.getDouble("avgRating"),
            "a hard interview is not a bad one — difficulty must not drag the rating down");
        assertEquals(5.0, json.getDouble("avgDifficulty"));
        assertNull(json.getJsonObject("categoryAverages").getValue("difficulty"),
            "difficulty is not one of the rating categories");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The shape of a process
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void recordsEachRoundInOrder() throws Exception {
        String auth0Id = insertUser("shape");
        insertCompany("Shape Co", "shape-co");

        JsonObject created = await(service.createReview(auth0Id, "shape-co", base()
            .put("rounds", new JsonArray().add("phone").add("panel").add("executive"))));

        assertEquals(3, created.getInteger("rounds"));
        assertEquals(List.of("phone", "panel", "executive"), roundTypesOf(created.getString("id")),
            "order is the point — a take-home before a phone screen is a different experience");
    }

    @Test
    void theRoundCountIsDerivedAndCannotContradictTheDetail() throws Exception {
        // There is no separate way to state the count, so the two cannot disagree. V50's trigger
        // keeps it true for any later change to the rounds.
        String auth0Id = insertUser("derived");
        long companyId = insertCompany("Derived Co", "derived-co");

        JsonObject created = await(service.createReview(auth0Id, "derived-co", base()
            .put("rounds", new JsonArray().add("phone").add("technical"))));
        assertEquals(2, storedRoundCount(created.getString("id")));

        await(pool.preparedQuery("""
                INSERT INTO interview_review_rounds (interview_review_id, round_number, round_type)
                VALUES ($1, 3, 'onsite')
                """).execute(Tuple.of(UUID.fromString(created.getString("id")))));

        assertEquals(3, storedRoundCount(created.getString("id")),
            "the count follows the detail without an application call");
        assertNotNull(companyId);
    }

    @Test
    void aReviewNeedNotDescribeItsRounds() throws Exception {
        String auth0Id = insertUser("noshape");
        insertCompany("Vague Co", "vague-co");

        JsonObject created = await(service.createReview(auth0Id, "vague-co", base()));

        assertNull(created.getValue("rounds"), "saying nothing about the shape is allowed");
        assertTrue(roundTypesOf(created.getString("id")).isEmpty());
    }

    @Test
    void rejectsAnUnknownRoundFormatRatherThanDroppingIt() throws Exception {
        // A process recorded with a round missing is worse than one recorded with none.
        String auth0Id = insertUser("badround");
        insertCompany("Bad Co", "bad-co");

        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "bad-co",
            base().put("rounds", new JsonArray().add("phone").add("telepathy")))));
        assertEquals(400, statusOfFailure(service.createReview(auth0Id, "bad-co",
            base().put("rounds", new JsonArray().add(42)))));
    }

    @Test
    void reportsTheUsualShapeOfACompanysProcess() throws Exception {
        insertCompany("Pattern Co", "pattern-co");
        String viewer = null;
        for (String name : new String[] { "p1", "p2", "p3" }) {
            String auth0Id = insertUser(name);
            await(service.createReview(auth0Id, "pattern-co", base()
                .put("rounds", new JsonArray().add("phone").add("technical").add("panel"))));
            viewer = auth0Id;
        }
        // One outlier must not change the typical shape.
        String odd = insertUser("p4");
        await(service.createReview(odd, "pattern-co", base()
            .put("rounds", new JsonArray().add("take_home").add("technical").add("onsite"))));

        JsonArray typical = await(service.getCompanyInterviews("pattern-co", null, null, viewer))
            .getJsonArray("typicalRounds");

        assertEquals(3, typical.size());
        assertEquals("phone",     typical.getJsonObject(0).getString("type"));
        assertEquals("technical", typical.getJsonObject(1).getString("type"));
        assertEquals("panel",     typical.getJsonObject(2).getString("type"),
            "mode per position — a single different process does not redefine the usual one");
        assertEquals(1, typical.getJsonObject(0).getInteger("round"));
        assertEquals(4, typical.getJsonObject(0).getInteger("reportedBy"));
    }

    @Test
    void medianRounds_isReportedFromTheCache() throws Exception {
        insertCompany("Rounds Co", "rounds-co");
        submitWithRounds("m1", "rounds-co", 2);
        submitWithRounds("m2", "rounds-co", 4);
        submitWithRounds("m3", "rounds-co", 9);

        JsonObject json = await(service.getCompanyInterviews("rounds-co", null, null, null));

        assertEquals(4, ((Number) json.getValue("medianRounds")).intValue(),
            "median resists the one company that ran nine rounds; a mean would not");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Industry rollup
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void industryAverages_groupCompaniesAndSkipUnclassifiedOnes() throws Exception {
        long techId = insertCompany("Tech Co", "tech-co");
        long unknownId = insertCompany("Mystery Co", "mystery-co");
        await(pool.preparedQuery("UPDATE companies SET industry = 'Technology' WHERE id = $1")
            .execute(Tuple.of(techId)));
        await(pool.preparedQuery("UPDATE companies SET industry = NULL WHERE id = $1")
            .execute(Tuple.of(unknownId)));

        submit("i1", "tech-co", 4.0, "offer", YEAR);
        submit("i2", "tech-co", 2.0, "offer", YEAR);
        submit("i3", "mystery-co", 5.0, "offer", YEAR);

        JsonArray averages = await(service.getIndustryAverages());

        assertEquals(1, averages.size(), "a company with no industry cannot be rolled up into one");
        assertEquals("Technology", averages.getJsonObject(0).getString("industry"));
        assertEquals(2, averages.getJsonObject(0).getInteger("reviewCount"));
        assertEquals(3.0, averages.getJsonObject(0).getDouble("avgRating"));
    }

    @Test
    void industryAverages_areEmptyWhenNothingHasBeenReviewed() throws Exception {
        assertTrue(await(service.getIndustryAverages()).isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static List<String> roundTypesOf(String reviewId) throws Exception {
        return await(interviewRepo.findRounds(UUID.fromString(reviewId)).map(rows -> {
            List<String> types = new java.util.ArrayList<>();
            for (Row r : rows) types.add(r.getString("round_type"));
            return types;
        }));
    }

    private static int storedRoundCount(String reviewId) throws Exception {
        return await(pool.preparedQuery("SELECT rounds FROM interview_reviews WHERE id = $1")
            .execute(Tuple.of(UUID.fromString(reviewId)))
            .map(rs -> {
                Short v = rs.iterator().next().getShort("rounds");
                return v == null ? 0 : (int) v;
            }));
    }

    private static JsonObject base() {
        return new JsonObject()
            .put("overallRating", 4.0)
            .put("outcome", "offer")
            .put("interviewYear", YEAR);
    }

    private static JsonObject fullBody() {
        return new JsonObject()
            .put("overallRating", 4.5)
            .put("communication", 4.0)
            .put("respectForTime", 3.5)
            .put("roleClarity", 5.0)
            .put("processFairness", 4.0)
            .put("nextStepTransparency", 2.5)
            .put("difficulty", 3)
            .put("outcome", "offer")
            .put("rounds", new JsonArray().add("phone").add("panel").add("executive"))
            .put("processLength", "2_4_weeks")
            .put("roleCategory", "Engineering")
            .put("interviewYear", YEAR);
    }

    /** Creates a fresh user and files one review, returning that user's auth0 id. */
    private static String submit(String username, String companySlug, double rating,
                                 String outcome, int year) throws Exception {
        return submit(username, companySlug, rating, outcome, year, null);
    }

    private static String submit(String username, String companySlug, double rating,
                                 String outcome, int year, String roleCategory) throws Exception {
        String auth0Id = insertUser(username);
        JsonObject body = new JsonObject()
            .put("overallRating", rating)
            .put("outcome", outcome)
            .put("interviewYear", year);
        if (roleCategory != null) body.put("roleCategory", roleCategory);
        await(service.createReview(auth0Id, companySlug, body));
        return auth0Id;
    }

    private static void submitWithRounds(String username, String companySlug, int rounds) throws Exception {
        String auth0Id = insertUser(username);
        JsonArray types = new JsonArray();
        for (int i = 0; i < rounds; i++) types.add("video");
        await(service.createReview(auth0Id, companySlug, base().put("rounds", types)));
    }

    private static String insertUser(String username) throws Exception {
        String auth0Id = "auth0|" + username + "-" + UUID.randomUUID();
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "-" + UUID.randomUUID() + "@test.com",
                              username + "-" + UUID.randomUUID().toString().substring(0, 8), "Test", "User")));
        return auth0Id;
    }

    private static long insertCompany(String name, String slug) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies(name, slug, status) VALUES ($1,$2,'approved') RETURNING id")
            .execute(Tuple.of(name, slug))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static long rowCount(String sql) throws Exception {
        return await(pool.query(sql).execute().map(rs -> rs.iterator().next().getLong("c")));
    }

    /** Runs a future expected to fail and returns the ServiceException status code. */
    private static int statusOfFailure(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            return fail("expected the call to fail, but it succeeded");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ServiceException se) return se.getStatusCode();
            return fail("expected a ServiceException but got " + cause);
        } catch (Exception e) {
            return fail(e);
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Database-level guarantees
    //
    // Nested rather than a separate class so both halves share one Postgres container.
    // Failsafe runs test CLASSES in parallel, and each additional container is real
    // contention for the rest of the suite.
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    class InterviewStatsTrigger {
        // ══════════════════════════════════════════════════════════════════════════
        // Population and maintenance
        // ══════════════════════════════════════════════════════════════════════════

        @Test
        void insertingReview_populatesCacheWithoutAnApplicationCall() throws Exception {
            long companyId = insertCompany("Trigger Co", "trigger-co");
            insertReview(companyId, insertUserId(), 4.0, 3, "offer", 2, 2024);

            Row row = stats(companyId).orElseThrow();
            assertEquals(1, row.getInteger("review_count"));
            assertEquals(4.0, numeric(row, "avg_rating"));
            assertEquals(3.0, numeric(row, "avg_difficulty"));
            assertEquals(1, row.getInteger("offer_count"));
            assertEquals(0, row.getInteger("no_offer_count"));
        }

        @Test
        void cacheSplitsOfferAndNoOfferAverages() throws Exception {
            long companyId = insertCompany("Split Co", "split-co");
            insertReview(companyId, insertUserId(), 5.0, 3, "offer",    2, 2024);
            insertReview(companyId, insertUserId(), 5.0, 3, "offer",    2, 2023);
            insertReview(companyId, insertUserId(), 1.0, 3, "no_offer", 2, 2024);

            Row row = stats(companyId).orElseThrow();
            assertEquals(3, row.getInteger("review_count"));
            assertEquals(3.7, numeric(row, "avg_rating"), "blended average");
            assertEquals(2, row.getInteger("offer_count"));
            assertEquals(5.0, numeric(row, "offer_avg"));
            assertEquals(1, row.getInteger("no_offer_count"));
            assertEquals(1.0, numeric(row, "no_offer_avg"),
                "the split is the whole point — a rejected candidate's 1.0 must stay visible as its own number");
        }

        @Test
        void cacheStoresMedianRoundsNotMean() throws Exception {
            long companyId = insertCompany("Rounds Co", "rounds-co");
            insertReview(companyId, insertUserId(), 4.0, 3, "offer", 2,  2024);
            insertReview(companyId, insertUserId(), 4.0, 3, "offer", 4,  2023);
            insertReview(companyId, insertUserId(), 4.0, 3, "offer", 10, 2022);

            assertEquals(4, (int) stats(companyId).orElseThrow().getShort("median_rounds"),
                "a mean would report 5.3 and misrepresent the typical process");
        }

        @Test
        void updatingRating_refreshesCache() throws Exception {
            long companyId = insertCompany("Edit Co", "edit-co");
            UUID reviewId = insertReview(companyId, insertUserId(), 2.0, 3, "offer", 2, 2024);
            assertEquals(2.0, numeric(stats(companyId).orElseThrow(), "avg_rating"));

            await(pool.preparedQuery("UPDATE interview_reviews SET overall_rating = 5.0 WHERE id = $1")
                .execute(Tuple.of(reviewId)));

            assertEquals(5.0, numeric(stats(companyId).orElseThrow(), "avg_rating"));
        }

        @Test
        void changingOutcome_movesTheReviewBetweenSplitBuckets() throws Exception {
            long companyId = insertCompany("Flip Co", "flip-co");
            UUID reviewId = insertReview(companyId, insertUserId(), 4.0, 3, "pending", 2, 2024);
            assertEquals(0, stats(companyId).orElseThrow().getInteger("offer_count"));

            await(pool.preparedQuery("UPDATE interview_reviews SET outcome = 'offer' WHERE id = $1")
                .execute(Tuple.of(reviewId)));

            assertEquals(1, stats(companyId).orElseThrow().getInteger("offer_count"),
                "a pending process that turns into an offer must land in the offer bucket");
        }

        // ══════════════════════════════════════════════════════════════════════════
        // The orphan case — the bug V47 had to fix for the other cache
        // ══════════════════════════════════════════════════════════════════════════

        @Test
        void softDeletingLastReview_removesCacheRowRatherThanLeavingStaleCounts() throws Exception {
            long companyId = insertCompany("Vanish Co", "vanish-co");
            UUID reviewId = insertReview(companyId, insertUserId(), 4.0, 3, "offer", 2, 2024);
            assertTrue(stats(companyId).isPresent());

            await(pool.preparedQuery("UPDATE interview_reviews SET deleted_at = now() WHERE id = $1")
                .execute(Tuple.of(reviewId)));

            assertTrue(stats(companyId).isEmpty(),
                "a company with no live reviews must not keep a row advertising that it has some");
        }

        @Test
        void hardDeletingLastReview_removesCacheRow() throws Exception {
            long companyId = insertCompany("Purge Co", "purge-co");
            UUID reviewId = insertReview(companyId, insertUserId(), 4.0, 3, "offer", 2, 2024);

            await(pool.preparedQuery("DELETE FROM interview_reviews WHERE id = $1").execute(Tuple.of(reviewId)));

            assertTrue(stats(companyId).isEmpty());
        }

        @Test
        void softDeletingOneOfTwo_leavesRowWithDecrementedCounts() throws Exception {
            long companyId = insertCompany("Duo Co", "duo-co");
            UUID first = insertReview(companyId, insertUserId(), 5.0, 3, "offer",    2, 2024);
            insertReview(companyId, insertUserId(), 1.0, 3, "no_offer", 2, 2024);
            assertEquals(2, stats(companyId).orElseThrow().getInteger("review_count"));

            await(pool.preparedQuery("UPDATE interview_reviews SET deleted_at = now() WHERE id = $1")
                .execute(Tuple.of(first)));

            Row row = stats(companyId).orElseThrow();
            assertEquals(1, row.getInteger("review_count"));
            assertEquals(0, row.getInteger("offer_count"));
            assertEquals(1.0, numeric(row, "avg_rating"));
        }

        @Test
        void movingReviewBetweenCompanies_refreshesBothSides() throws Exception {
            long fromId = insertCompany("Origin Co", "origin-co");
            long toId   = insertCompany("Target Co", "target-co");
            UUID reviewId = insertReview(fromId, insertUserId(), 4.0, 3, "offer", 2, 2024);

            assertTrue(stats(fromId).isPresent());

            await(pool.preparedQuery("UPDATE interview_reviews SET company_id = $1 WHERE id = $2")
                .execute(Tuple.of(toId, reviewId)));

            assertTrue(stats(fromId).isEmpty(), "the company left behind must be cleaned up");
            assertEquals(1, stats(toId).orElseThrow().getInteger("review_count"));
        }

        @Test
        void deletingCompany_cascadesToReviewsAndCache() throws Exception {
            long companyId = insertCompany("Doomed Co", "doomed-co");
            insertReview(companyId, insertUserId(), 4.0, 3, "offer", 2, 2024);

            await(pool.preparedQuery("DELETE FROM companies WHERE id = $1").execute(Tuple.of(companyId)));

            assertEquals(0L, tableCount("SELECT COUNT(*) AS c FROM interview_reviews"));
            assertEquals(0L, tableCount("SELECT COUNT(*) AS c FROM company_interview_stats"));
        }

        // ══════════════════════════════════════════════════════════════════════════
        // Constraints
        // ══════════════════════════════════════════════════════════════════════════

        @Test
        void uniqueIndex_blocksTwoLiveReviewsForSameUserCompanyYear() throws Exception {
            long companyId = insertCompany("Once Co", "once-co");
            UUID userId = insertUserId();
            insertReview(companyId, userId, 4.0, 3, "offer", 2, 2024);

            assertThrows(Exception.class,
                () -> insertReview(companyId, userId, 2.0, 3, "no_offer", 2, 2024));
        }

        @Test
        void uniqueIndex_ignoresSoftDeletedRows() throws Exception {
            long companyId = insertCompany("Redo Co", "redo-co");
            UUID userId = insertUserId();
            UUID first = insertReview(companyId, userId, 4.0, 3, "offer", 2, 2024);

            await(pool.preparedQuery("UPDATE interview_reviews SET deleted_at = now() WHERE id = $1")
                .execute(Tuple.of(first)));

            assertDoesNotThrow(() -> insertReview(companyId, userId, 2.0, 3, "no_offer", 2, 2024));
        }

        @Test
        void checkConstraints_rejectImpossibleValues() throws Exception {
            long companyId = insertCompany("Guard Co", "guard-co");
            UUID userId = insertUserId();

            assertThrows(Exception.class, () -> insertReview(companyId, userId, 6.0, 3, "offer", 2, 2024),
                "rating above 5");
            assertThrows(Exception.class, () -> insertReview(companyId, userId, 4.0, 9, "offer", 2, 2024),
                "difficulty above 5");
            assertThrows(Exception.class, () -> insertReview(companyId, userId, 4.0, 3, "maybe", 2, 2024),
                "outcome outside the allowed set");
            assertThrows(Exception.class, () -> insertReview(companyId, userId, 4.0, 3, "offer", 99, 2024),
                "rounds above 10");
            assertThrows(Exception.class, () -> insertReview(companyId, userId, 4.0, 3, "offer", 2, 1899),
                "interview year before the supported range");
        }

        // ══════════════════════════════════════════════════════════════════════════
        // Helpers
        // ══════════════════════════════════════════════════════════════════════════

        /** NUMERIC columns arrive as {@link io.vertx.sqlclient.data.Numeric}, not BigDecimal. */
        private static double numeric(Row row, String column) {
            Object value = row.getValue(column);
            assertNotNull(value, column + " should not be null");
            return ((Number) value).doubleValue();
        }

        private static Optional<Row> stats(long companyId) throws Exception {
            return await(pool.preparedQuery("SELECT * FROM company_interview_stats WHERE company_id = $1")
                .execute(Tuple.of(companyId))
                .map(rs -> rs.iterator().hasNext() ? Optional.of(rs.iterator().next()) : Optional.empty()));
        }

        private static long tableCount(String sql) throws Exception {
            return await(pool.query(sql).execute().map(rs -> rs.iterator().next().getLong("c")));
        }

        private static UUID insertUserId() throws Exception {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            return await(pool.preparedQuery("""
                    INSERT INTO users(auth0_id, email, username, first_name, last_name)
                    VALUES ($1,$2,$3,'Test','User') RETURNING id
                    """)
                .execute(Tuple.of("auth0|" + suffix, suffix + "@test.com", "user" + suffix))
                .map(rs -> rs.iterator().next().getUUID("id")));
        }

        private static UUID insertReview(long companyId, UUID userId, double rating, int difficulty,
                                         String outcome, int rounds, int year) throws Exception {
            return await(pool.preparedQuery("""
                    INSERT INTO interview_reviews
                        (company_id, user_id, overall_rating, difficulty, outcome, rounds, interview_year)
                    VALUES ($1,$2,$3,$4,$5,$6,$7)
                    RETURNING id
                    """)
                .execute(Tuple.of(companyId, userId, BigDecimal.valueOf(rating), difficulty, outcome, rounds, year))
                .map(rs -> rs.iterator().next().getUUID("id")));
        }

    }
}

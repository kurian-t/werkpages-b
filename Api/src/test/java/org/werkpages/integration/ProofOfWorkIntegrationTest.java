package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.ConfidenceRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ProofChallengeRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.NameValidator;
import org.werkpages.service.SubmissionTier;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Holding a rating until we know its author actually worked there.
 *
 * The thing being protected is the contribution gate. It used to open on any review at all, so a
 * junk rating written in ten seconds bought exactly the access an honest one bought — and the
 * cheapest key anyone could cut was a famous name we printed in our own form placeholder.
 *
 * The most important test here is not any of the abuse cases. It is
 * {@link #anOrdinaryRatingIsUntouched()}: this feature is only worth having if the overwhelming
 * majority of people never notice it exists.
 */
@Testcontainers
class ProofOfWorkIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test").withUsername("test").withPassword("test");

    static Pool pool;
    static ProofChallengeRepository challenges;
    static ConfidenceRepository     confidence;
    static UserRepository           users;
    static ManagerRepository        managers;

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

        challenges = new ProofChallengeRepository(pool);
        confidence = new ConfidenceRepository(pool);
        users      = new UserRepository(pool, null);
        managers   = new ManagerRepository(pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query(
            "TRUNCATE manager_proof_challenges, high_profile_figures, user_confidence_events, "
            + "reviews, managers, companies, users CASCADE").execute().mapEmpty());
    }

    @AfterAll
    static void tearDownAll() { if (pool != null) pool.close(); }

    // ── The invariant that matters most ───────────────────────────────────────

    @Test
    void anOrdinaryRatingIsUntouched() throws Exception {
        UUID user = insertUser("auth0|pow-ordinary");
        long mgr  = insertManager("Dana Whitfield", insertCompany("Bramworth Logistics"));

        SubmissionTier tier = await(SubmissionTier.classify(
            challenges, confidence, user, mgr, "Dana", "Whitfield", companyOf(mgr)));

        assertEquals(SubmissionTier.LIVE, tier, "a real manager must feel exactly like before");
        assertFalse(tier.isHeld());

        insertReview(mgr, user);
        assertTrue(await(users.hasContributed(user)), "the gate opens immediately");
    }

    @Test
    void existingRatingsKeepCountingAfterTheMigration() throws Exception {
        // The deploy must not re-lock anybody who already earned access. Every column added by
        // V60 defaults to today's behaviour, and this is the test that says so out loud.
        UUID user = insertUser("auth0|pow-existing");
        long mgr  = insertManager("Priya Raghunathan", insertCompany("Ashgrove Health"));
        insertReview(mgr, user);

        Row r = await(pool.preparedQuery("SELECT disposition, gate_eligible, live_since FROM reviews WHERE user_id = $1")
            .execute(Tuple.of(user)).map(rows -> rows.iterator().next()));

        assertEquals("live", r.getString("disposition"));
        assertTrue(r.getBoolean("gate_eligible"));
        assertNotNull(r.getLocalDateTime("live_since"), "a live rating always carries live_since");
        assertTrue(await(users.hasContributed(user)));
    }

    // ── Identity, not names ───────────────────────────────────────────────────

    @Test
    void anUnrelatedPersonSharingAFamousNameIsNeverChallenged() throws Exception {
        // The whole reason the list is identity-keyed. A site manager genuinely called Tim Cook,
        // at a construction firm with a team of eleven, must not be asked to prove he knows
        // himself — that would break the one rule this feature is built around.
        long apple  = insertCompany("Apple");
        long trades = insertCompany("Redgate Construction");
        long famous = insertManager("Tim Cook", apple);
        long ordinary = insertManager("Tim Cook", trades);
        await(challenges.addFigure(null, "Tim Cook", apple, "seeded"));

        UUID user = insertUser("auth0|pow-namesake");

        assertEquals(SubmissionTier.HIGH_PROFILE, await(SubmissionTier.classify(
            challenges, confidence, user, famous, "Tim", "Cook", apple)));

        assertEquals(SubmissionTier.LIVE_FLAGGED, await(SubmissionTier.classify(
            challenges, confidence, user, ordinary, "Tim", "Cook", trades)),
            "the builder publishes; the queue is told, and he is not");
    }

    @Test
    void aListedFigureIsHeldAndDoesNotOpenTheGate() throws Exception {
        long microsoft = insertCompany("Microsoft");
        long satya = insertManager("Satya Nadella", microsoft);
        await(challenges.addFigure(satya, null, null, "seeded: our own form placeholder"));

        UUID user = insertUser("auth0|pow-satya");
        UUID review = insertHeldReview(satya, user);
        await(challenges.open(user, satya, review, "high_profile"));

        assertFalse(await(users.hasContributed(user)),
            "rating a CEO nobody reported to must not buy access to everyone else's data");
    }

    // ── The guard: held means invisible everywhere, not merely uncounted ──────

    @Test
    void aHeldRatingIsAbsentFromEverySurface() throws Exception {
        // The one that would have caught the real bug. Changing hasContributed is a tenth of the
        // work: a held rating must also stay out of the cached rating and count, which is a WRITE
        // path — a leak there outlives the hold being lifted or the rating being rejected.
        long mgr = insertManager("Corinne Aliyev", insertCompany("Northvale Media"));
        UUID user = insertUser("auth0|pow-guard");
        insertHeldReview(mgr, user);

        await(managers.recalculate(mgr));

        Row m = await(pool.preparedQuery("SELECT overall_rating, reviews_count FROM managers WHERE id = $1")
            .execute(Tuple.of(mgr)).map(rows -> rows.iterator().next()));
        assertEquals(0, m.getInteger("reviews_count"), "held ratings must not reach the cached count");
        assertNull(m.getBigDecimal("overall_rating"), "nor the cached rating");

        Long visible = await(pool.preparedQuery("SELECT COUNT(*) AS c FROM published_reviews WHERE manager_id = $1")
            .execute(Tuple.of(mgr)).map(rows -> rows.iterator().next().getLong("c")));
        assertEquals(0L, visible, "nor any public listing");

        assertFalse(await(users.hasContributed(user)), "nor the gate");
    }

    @Test
    void aHeldRatingIsAbsentFromTheMethodsThatServeThePublic() throws Exception {
        /*
         * The first version of the guard test asserted against the view, the cached stats and the
         * gate — and missed the thing that actually mattered, because the endpoint people read
         * does not go through any of them. GET /api/managers/{id}/reviews served a held rating to
         * an anonymous caller while every one of those three assertions passed.
         *
         * So this asserts on the repository methods the handlers actually call. Asserting on a
         * view proves the view is right; it proves nothing about who reads it.
         */
        long mgr = insertManager("Rashid Karimov", insertCompany("Stellan Works"));
        UUID author = insertUser("auth0|pow-endpoints");
        insertHeldReview(mgr, author);

        org.werkpages.repository.ReviewRepository reviews =
            new org.werkpages.repository.ReviewRepository(pool);

        assertEquals(0, await(reviews.findByManager(mgr, 20, 0, "recent", null)).size(),
            "the public review list");
        assertEquals(0L, await(reviews.countByManager(mgr, null)),
            "the public count, which has to agree with the list");
        assertEquals(0, await(reviews.findCareerSegmentsByManager(mgr, 20, 0)).size(),
            "the career segments on the public profile");
        assertNull(await(reviews.findMostCurrentReviewForManager(mgr)),
            "and the query that decides the manager's displayed company and title");

        // The author still sees their own held rating. Withholding it from the person who wrote
        // it would leave them with no way to know it exists, let alone act on the challenge.
        assertEquals(1, await(reviews.findByManager(mgr, 20, 0, "recent", author)).size(),
            "but its author can still see it");
    }

    @Test
    void aSoftDeletedLiveRatingIsNotPublished() throws Exception {
        // The view has to carry the whole publication predicate. Encoding half of it leaves every
        // caller still holding the other half, which recreates the problem it exists to remove.
        long mgr = insertManager("Ola Berglund", insertCompany("Kestrel Foods"));
        UUID user = insertUser("auth0|pow-deleted");
        insertReview(mgr, user);
        await(pool.query("UPDATE reviews SET deleted_at = now()").execute().mapEmpty());

        Long visible = await(pool.preparedQuery("SELECT COUNT(*) AS c FROM published_reviews WHERE manager_id = $1")
            .execute(Tuple.of(mgr)).map(rows -> rows.iterator().next().getLong("c")));
        assertEquals(0L, visible);
    }

    // ── Behaviour, not string-matching ────────────────────────────────────────

    @Test
    void repetitionIsHeldRatherThanRefused() throws Exception {
        // "No No" passes every rule in NameValidator: two letters clears the minimum, it is
        // letters-only, and it is on no list. Holding rather than rejecting is deliberate —
        // Thomas Thomas is a real name, and telling someone their name is fake is worse than
        // asking a person to look.
        assertTrue(NameValidator.isSuspiciousName("No", "No"));
        assertTrue(NameValidator.isSuspiciousName("Thomas", "Thomas"));
        assertFalse(NameValidator.isSuspiciousName("Li", "Xu"), "short names are not suspicious");
        assertFalse(NameValidator.isSuspiciousName("Anne-Marie", "Doucet"));

        assertTrue(NameValidator.validateFullName("No", "No").valid(),
            "still accepted — held is not rejected");
    }

    @Test
    void anAuthorWithProofOutstandingIsHeldWhateverTheyType() throws Exception {
        // The case that actually closes the loop: quit a challenge, type something plausible
        // instead, and it is held because of who is asking rather than what they typed.
        long microsoft = insertCompany("Microsoft");
        long satya = insertManager("Satya Nadella", microsoft);
        await(challenges.addFigure(satya, null, null, "seeded"));

        UUID user = insertUser("auth0|pow-flagged");
        await(challenges.open(user, satya, insertHeldReview(satya, user), "high_profile"));

        long other = insertManager("Marcus Bell", insertCompany("Halden Rail"));
        assertEquals(SubmissionTier.FLAGGED_USER, await(SubmissionTier.classify(
            challenges, confidence, user, other, "Marcus", "Bell", companyOf(other))));
    }

    @Test
    void deletingTheRatingLeavesTheAuthorFlagged() throws Exception {
        // There is deliberately no self-clearing path. Letting somebody withdraw to lift their own
        // flag is a laundering step: challenge a famous name, withdraw to come out clean, then
        // submit the junk you actually wanted.
        long satya = insertManager("Satya Nadella", insertCompany("Microsoft"));
        UUID user = insertUser("auth0|pow-withdraw");
        UUID review = insertHeldReview(satya, user);
        await(challenges.open(user, satya, review, "high_profile"));

        await(pool.preparedQuery("UPDATE reviews SET deleted_at = now() WHERE id = $1")
            .execute(Tuple.of(review)).mapEmpty());

        assertTrue(await(challenges.hasUnresolvedChallenge(user)), "the flag stands");
    }

    // ── Confidence ────────────────────────────────────────────────────────────

    @Test
    void applyingTheSameEventTwiceMovesTheScoreOnce() throws Exception {
        // Every one of these can fire twice — a retry, a double-click, a rerun of the daily sweep.
        // Debiting somebody 15 twice for one abandonment silently destroys an account's standing,
        // so the guarantee is a constraint rather than a caller remembering to check a row count.
        UUID user = insertUser("auth0|pow-idem");
        String challengeId = UUID.randomUUID().toString();

        await(confidence.apply(user, ConfidenceRepository.CHALLENGE_ABANDONED, -15, "challenge", challengeId));
        await(confidence.apply(user, ConfidenceRepository.CHALLENGE_ABANDONED, -15, "challenge", challengeId));
        await(confidence.apply(user, ConfidenceRepository.CHALLENGE_ABANDONED, -15, "challenge", challengeId));

        assertEquals(55, await(confidence.current(user)), "70 - 15, once");
    }

    @Test
    void confidenceCannotLeaveItsRange() throws Exception {
        UUID user = insertUser("auth0|pow-clamp");
        for (int i = 0; i < 8; i++) {
            await(confidence.apply(user, ConfidenceRepository.CHALLENGE_REJECTED, -25,
                                   "challenge", "c" + i));
        }
        assertEquals(0, await(confidence.current(user)), "clamped at the floor, never negative");

        for (int i = 0; i < 12; i++) {
            await(confidence.apply(user, ConfidenceRepository.CHALLENGE_APPROVED, 15,
                                   "challenge", "a" + i));
        }
        assertEquals(100, await(confidence.current(user)), "and at the ceiling");
    }

    @Test
    void passiveCreditStopsAtSeventyNine() throws Exception {
        // Gating on the prior value leaks: from 70 the steps are 72, 74, 76, 78 — and at 78 a
        // "confidence < 79" guard still passes, so the next credit lands on 80 and the account is
        // trusted purely by waiting. The ceiling has to clamp the result.
        UUID user = insertUser("auth0|pow-ceiling");
        for (int i = 0; i < 20; i++) {
            await(confidence.apply(user, ConfidenceRepository.REVIEW_STOOD_30D, 2, "review", "r" + i));
        }
        assertEquals(79, await(confidence.current(user)),
            "trusted is granted by a person or a verified affiliation, never accumulated");
    }

    @Test
    void aRatingHeldForTwentyNineDaysEarnsNothingTheDayAfterApproval() throws Exception {
        // live_since, not created_at. Paying on age alone rewards exactly the behaviour this
        // feature exists to slow down.
        long mgr = insertManager("Yusuf Demirci", insertCompany("Alderline Group"));
        UUID user = insertUser("auth0|pow-standing");
        UUID review = insertReview(mgr, user);

        await(pool.preparedQuery(
                "UPDATE reviews SET created_at = now() - INTERVAL '29 days', live_since = now() "
                + "WHERE id = $1").execute(Tuple.of(review)).mapEmpty());

        assertEquals(0, await(confidence.findReviewsDueStandingCredit(10)).size(),
            "old, but only just published");

        await(pool.preparedQuery("UPDATE reviews SET live_since = now() - INTERVAL '31 days' WHERE id = $1")
            .execute(Tuple.of(review)).mapEmpty());
        assertEquals(1, await(confidence.findReviewsDueStandingCredit(10)).size());
    }

    @Test
    void abandonmentIsChargedOnceHoweverOftenTheSweepRuns() throws Exception {
        long satya = insertManager("Satya Nadella", insertCompany("Microsoft"));
        UUID user  = insertUser("auth0|pow-sweep");
        await(challenges.open(user, satya, insertHeldReview(satya, user), "high_profile"));
        await(pool.query("UPDATE manager_proof_challenges SET created_at = now() - INTERVAL '8 days'")
            .execute().mapEmpty());

        for (int run = 0; run < 3; run++) {
            for (Row r : await(challenges.markAbandoned())) {
                await(confidence.apply(r.getUUID("user_id"), ConfidenceRepository.CHALLENGE_ABANDONED,
                                       -15, "challenge", r.getUUID("id").toString()));
            }
        }
        assertEquals(55, await(confidence.current(user)), "one debit, three sweeps");
    }

    @Test
    void anAbandonedChallengeStillReachesTheQueue() throws Exception {
        // Without this the flag is a lock with no key: the author stays held forever while nothing
        // ever appears in front of anyone who could lift it.
        long satya = insertManager("Satya Nadella", insertCompany("Microsoft"));
        UUID user  = insertUser("auth0|pow-queue");
        await(challenges.open(user, satya, insertHeldReview(satya, user), "high_profile"));
        await(pool.query("UPDATE manager_proof_challenges SET created_at = now() - INTERVAL '8 days'")
            .execute().mapEmpty());
        await(challenges.markAbandoned());

        assertEquals(1L, await(challenges.countForAdmin()));
        assertTrue(await(challenges.hasUnresolvedChallenge(user)), "and still flags its author");
    }

    // ── State coherence ───────────────────────────────────────────────────────

    @Test
    void incoherentReviewStatesAreRefusedByTheDatabase() throws Exception {
        long mgr = insertManager("Nadia Sorokina", insertCompany("Pellworth Ltd"));
        UUID user = insertUser("auth0|pow-states");
        UUID review = insertReview(mgr, user);

        assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "UPDATE reviews SET disposition = 'held', gate_eligible = TRUE WHERE id = $1")
            .execute(Tuple.of(review)).mapEmpty()),
            "held must never credit a contribution nobody can read");

        assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "UPDATE reviews SET live_since = NULL WHERE id = $1")
            .execute(Tuple.of(review)).mapEmpty()),
            "live since when?");
    }

    @Test
    void theReviewPayloadSaysWhetherItIsPublished() throws Exception {
        /*
         * The contract the client now relies on. Before this, a client had to POST and then ask a
         * second endpoint what the write it had just made had done - a race the write does not
         * have, whose failure mode was silently telling somebody their rating was live.
         */
        // Two managers, because one rating per person per role is enforced by a unique index.
        long companyId = insertCompany("Vantage Rail");
        long mgrA = insertManager("Ines Fabre", companyId);
        long mgrB = insertManager("Tomas Lindqvist", companyId);
        UUID user = insertUser("auth0|pow-payload");

        UUID live = insertReview(mgrA, user);
        Row liveRow = await(pool.preparedQuery("SELECT * FROM reviews WHERE id = $1")
            .execute(Tuple.of(live)).map(rows -> rows.iterator().next()));
        assertEquals("live", org.werkpages.service.ManagerService.buildReviewJson(liveRow)
            .getString("disposition"));

        UUID held = insertHeldReview(mgrB, user);
        Row heldRow = await(pool.preparedQuery("SELECT * FROM reviews WHERE id = $1")
            .execute(Tuple.of(held)).map(rows -> rows.iterator().next()));
        assertEquals("held", org.werkpages.service.ManagerService.buildReviewJson(heldRow)
            .getString("disposition"),
            "so the client can tell saved apart from published without a second request");
    }

    @Test
    void aHeldRatingIsVisibleToItsAuthorAndToAdmins() throws Exception {
        /*
         * Withheld from the public, and from nobody else.
         *
         * Hiding it from its author leaves them staring at an empty page where the rating they
         * just wrote should be, which reads as data loss. Hiding it from an admin means moderating
         * by holding the queue and the profile side by side and matching them up by eye.
         */
        long mgr = insertManager("Halvard Nygaard", insertCompany("Brightmoor Care"));
        UUID author = insertUser("auth0|pow-visibility-author");
        UUID admin  = insertUser("auth0|pow-visibility-admin");
        await(pool.preparedQuery("UPDATE users SET role = 'admin' WHERE id = $1")
            .execute(Tuple.of(admin)).mapEmpty());
        insertHeldReview(mgr, author);

        org.werkpages.repository.ReviewRepository reviews =
            new org.werkpages.repository.ReviewRepository(pool);

        assertEquals(0, await(reviews.findByManager(mgr, 20, 0, "recent", null, null, false)).size(),
            "the public sees nothing");
        assertEquals(1, await(reviews.findByManager(mgr, 20, 0, "recent", null, author, false)).size(),
            "its author sees their own");
        assertEquals(1, await(reviews.findByManager(mgr, 20, 0, "recent", null, admin, true)).size(),
            "an admin sees it without opening the queue");

        UUID stranger = insertUser("auth0|pow-visibility-stranger");
        assertEquals(0, await(reviews.findByManager(mgr, 20, 0, "recent", null, stranger, false)).size(),
            "another signed-in person sees nothing");

        // The count has to agree with the list, or the heading says "1 review" above none.
        assertEquals(0L, await(reviews.countByManager(mgr, null, null, false)));
        assertEquals(1L, await(reviews.countByManager(mgr, null, admin, true)));
    }

    @Test
    void rejectingASubmissionLeavesTheLiveRecordItResemblesAlone() throws Exception {
        /*
         * Pending and live are separate states, and a rejection does not cross between them.
         *
         * This test used to assert the opposite: that rejecting "Satya Nadella at Microsoft" also
         * took out a live "Satya Nadella at Mi" left behind by the search box creating a record as
         * somebody typed. Scoping that sweep was never made safe - by name alone it destroyed
         * unrelated namesakes, and the company-prefix rule that replaced it was defeated by a blank
         * company. The premise was wrong: rejecting something that was never public must not change
         * something that is.
         *
         * The half-typed capture it was aimed at no longer goes live at all - createCapturedDraft
         * writes pending_approval - so it arrives in the admin queue and is rejected on its own row.
         */
        long full    = insertCompany("Microsoft");
        long partial = insertCompany("Mi");
        long submitted = insertManager("Satya Nadella", full);
        long live      = insertManager("Satya Nadella", partial);
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'pending_approval' WHERE id = $1")
            .execute(Tuple.of(submitted)).mapEmpty());
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'ghost' WHERE id = $1")
            .execute(Tuple.of(live)).mapEmpty());

        await(managers.reject(submitted));

        String liveStatus = await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(live)).map(rows -> rows.iterator().next().getString("approval_status")));
        assertEquals("ghost", liveStatus, "a live record is untouched by a decision about a pending one");

        String rejectedStatus = await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(submitted)).map(rows -> rows.iterator().next().getString("approval_status")));
        assertEquals("rejected", rejectedStatus, "and the row the admin actually clicked is rejected");
    }

    @Test
    void aGhostSomebodyActuallyRatedIsNeverSweptUp() throws Exception {
        // The limit on the rule above. A ghost carrying a review a real person wrote is somebody's
        // contribution, and taking it on a name match would destroy real data. The capture path's
        // placeholder reviews have no user_id, which is exactly what tells them apart.
        long companyId = insertCompany("Contoso");
        long submitted = insertManager("Jamie Okonkwo", companyId);
        long ghost     = insertManager("Jamie Okonkwo", insertCompany("Cont"));
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'pending_approval' WHERE id = $1")
            .execute(Tuple.of(submitted)).mapEmpty());
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'ghost' WHERE id = $1")
            .execute(Tuple.of(ghost)).mapEmpty());
        insertReview(ghost, insertUser("auth0|pow-real-contributor"));

        await(managers.reject(submitted));

        String ghostStatus = await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(ghost)).map(rows -> rows.iterator().next().getString("approval_status")));
        assertEquals("ghost", ghostStatus, "somebody rated this one, so it stays");
    }

    // ── What may reach the site without review ───────────────────────────────

    @Test
    void typingIntoTheAddFormNeverPublishesAManager() throws Exception {
        /*
         * The add-manager form posts a capture as soon as its first step is valid, so an abandoned
         * attempt is not lost. That capture used to be created live, which meant typing put a
         * manager on the public site - before any rating, any submit click, and any check - and
         * captured whatever was in the company box at that moment, which is how the directory
         * ended up with companies called "Mi" and "Ju".
         *
         * Capturing a drop-off and publishing it are different things. Only the first was ever the
         * point, and this test is what stops them being confused again.
         */
        long companyId = insertCompany("Mi");
        Row captured = await(managers.createCapturedDraft(
            "Satya Nadella", "Mi", "CEO", "US", null, null, null, companyId));

        assertEquals("pending_approval", captured.getString("approval_status"),
            "a capture waits for a person; it does not go live");

        assertEquals(0, await(managers.search(50, 0, "%Satya%", null, "recent")).size(),
            "and it is absent from the public directory");
    }

    @Test
    void theFindSearchPathIsStillAllowedToPublish() throws Exception {
        /*
         * The one exception, and it stays. A deliberate search on /find may create a live manager,
         * once per user ever, tracked by users.has_auto_created_manager - that is the documented
         * ghost behaviour and the whole reason the directory has anything in it for a new company.
         *
         * Pinned here because the fix above changes a neighbouring method, and quietly turning
         * this one off would empty the product.
         */
        long companyId = insertCompany("Contoso Global");
        UUID searcher = insertUser("auth0|pow-find-search");
        Row auto = await(managers.createAutoApproved(
            "Marguerite Vance", "Contoso Global", "Director", "US", null, null,
            searcher, null, companyId));

        assertEquals("ghost", auto.getString("approval_status"),
            "a deliberate search still publishes, by design");
    }

    @Test
    void theAuthorCanReadTheChallengeStandingAgainstTheirRating() throws Exception {
        /*
         * The page that asks somebody to verify a rating has to be able to find the thing it is
         * asking about. This was never covered, and the banner offering the link was driven by a
         * different source of truth to the page it opened - which is how a profile ended up saying
         * "not published yet, help us verify" above a page saying there was nothing to verify.
         */
        long mgr = insertManager("Eddie Junior", insertCompany("Halloway Foods"));
        UUID user = insertUser("auth0|pow-findmine");
        UUID review = insertHeldReview(mgr, user);
        await(challenges.open(user, mgr, review, "flagged_user"));

        org.werkpages.service.ProofChallengeService service =
            new org.werkpages.service.ProofChallengeService(challenges, users);

        io.vertx.core.json.JsonObject result = await(service.findMine("auth0|pow-findmine", mgr));
        io.vertx.core.json.JsonObject challenge = result.getJsonObject("challenge");

        assertNotNull(challenge, "the author must be able to see their own challenge");
        assertEquals("open", challenge.getString("status"));
        assertEquals("flagged_user", challenge.getString("reason"));

        // And somebody else's challenge is not theirs to read.
        insertUser("auth0|pow-findmine-other");
        assertNull(await(service.findMine("auth0|pow-findmine-other", mgr)).getJsonObject("challenge"));
    }

    // ── Merging managers ─────────────────────────────────────────────────────

    @Test
    void mergingCarriesEveryReviewAcrossIncludingSoftDeletedOnes() throws Exception {
        /*
         * A merge used to move what it could, discard the count of what it had moved, and then run
         * DELETE FROM reviews over the remainder. Anything the move skipped was destroyed - no
         * transaction, no manifest, no undo.
         *
         * Soft-deleted reviews were the worst of it: those are waiting out the three-day restore
         * window, so merging quietly deleted reviews that were about to come back.
         */
        long companyId = insertCompany("Ardent Systems");
        long keep  = insertManager("Eddie Junior", companyId);
        long dup   = insertManager("Eddie Jr", companyId);

        UUID a = insertUser("auth0|merge-a");
        UUID b = insertUser("auth0|merge-b");
        insertReview(dup, a);
        UUID softDeleted = insertReview(dup, b);
        await(pool.preparedQuery("UPDATE reviews SET deleted_at = now(), user_id = NULL WHERE id = $1")
            .execute(Tuple.of(softDeleted)).mapEmpty());

        assertEquals(2, await(managers.mergeInto(keep, dup)).getInteger("moved"), "both reviews move");

        Long onKeep = await(pool.preparedQuery("SELECT COUNT(*) AS c FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(keep)).map(rows -> rows.iterator().next().getLong("c")));
        assertEquals(2L, onKeep, "including the one awaiting restore");

        // The row is kept and marked, not deleted: reviews.manager_id cascades, so deleting it
        // is what used to destroy anything that could not move.
        Row merged = await(pool.preparedQuery(
                "SELECT approval_status, merged_into FROM managers WHERE id = $1")
            .execute(Tuple.of(dup)).map(rows -> rows.iterator().next()));
        assertEquals("rejected", merged.getString("approval_status"));
        assertEquals(keep, merged.getLong("merged_into"));
    }

    @Test
    void aTrueDuplicateIsDedupedRatherThanDuplicated() throws Exception {
        // The one case where dropping a review is right: the surviving manager already holds a
        // review of the same role by the same person, so nothing is said twice and the unique
        // role indexes forbid keeping both. Distinguished from "cannot move", which rolls back.
        long companyId = insertCompany("Thorne Manufacturing");
        long keep = insertManager("Nadia Petrov", companyId);
        long dup  = insertManager("Nadia Petrova", companyId);

        UUID author = insertUser("auth0|merge-collide");
        insertReview(keep, author);   // same author, same company and title
        insertReview(dup,  author);

        // The count is reported, so an admin can be told a review was set aside rather than
        // watching one disappear.
        io.vertx.core.json.JsonObject counts = await(managers.mergeInto(keep, dup));
        assertEquals(1, counts.getInteger("parked"), "the collision is reported, not silent");

        Long onKeep = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM reviews WHERE manager_id = $1 AND weight = FALSE")
            .execute(Tuple.of(keep)).map(rows -> rows.iterator().next().getLong("c")));
        assertEquals(1L, onKeep, "one opinion, stated once");

        // And the losing copy is soft-deleted, not destroyed. A review somebody wrote is only
        // ever marked deleted; the row survives, which is also why the merged-away manager is
        // kept rather than deleted - deleting it would cascade and take this with it.
        Long parked = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM reviews WHERE manager_id = $1 AND deleted_at IS NOT NULL")
            .execute(Tuple.of(dup)).map(rows -> rows.iterator().next().getLong("c")));
        assertEquals(1L, parked, "the duplicate is soft-deleted, and still there");

        Row merged = await(pool.preparedQuery(
                "SELECT approval_status, merged_into FROM managers WHERE id = $1")
            .execute(Tuple.of(dup)).map(rows -> rows.iterator().next()));
        assertEquals("rejected", merged.getString("approval_status"), "hidden everywhere");
        assertEquals(keep, merged.getLong("merged_into"), "and it records where it went");
    }

    @Test
    void deletingAManagerKeepsTheReviewsPeopleWrote() throws Exception {
        // The rule: a review written by a person is only ever soft-deleted. Seeded placeholders
        // may go outright, because nobody wrote them.
        long companyId = insertCompany("Larkfield Retail");
        long mgr = insertManager("Imelda Barros", companyId);
        UUID author = insertUser("auth0|delete-keeps");
        UUID review = insertReview(mgr, author);

        assertFalse(await(managers.deleteOrRetire(mgr)), "held reviews, so the row is retired");

        Row r = await(pool.preparedQuery("SELECT deleted_at, manager_id FROM reviews WHERE id = $1")
            .execute(Tuple.of(review)).map(rows -> rows.iterator().next()));
        assertNotNull(r.getLocalDateTime("deleted_at"), "soft-deleted, not destroyed");
        assertEquals(mgr, r.getLong("manager_id"), "and still attached to its manager");
    }

    @Test
    void deletingAManagerNobodyReviewedRemovesItOutright() throws Exception {
        // "Delete" still means delete for the empty rows this is mostly used on.
        long mgr = insertManager("Unrated Person", insertCompany("Kestrel Freight"));
        assertTrue(await(managers.deleteOrRetire(mgr)));
        assertEquals(0, await(pool.preparedQuery("SELECT id FROM managers WHERE id = $1")
            .execute(Tuple.of(mgr))).size());
    }

    @Test
    void adminChangingTheCompanyMovesTheManagerToIt() throws Exception {
        /*
         * Changing the company from the admin edit has to move two things: the company text on the
         * manager, and company_id, which is what every company surface actually joins on. Updating
         * only the text leaves the manager listed under the old company - the same shape of bug
         * that career-history edits had.
         */
        long oldCompany = insertCompany("Halden Rail");
        long mgr = insertManager("Priya Raghunathan", oldCompany);

        org.werkpages.repository.CompanyRepository companyRepo =
            new org.werkpages.repository.CompanyRepository(pool);
        org.werkpages.service.AdminService admin = new org.werkpages.service.AdminService(
            users, managers, new org.werkpages.repository.ReviewRepository(pool), null,
            new org.werkpages.repository.NotificationRepository(pool), companyRepo, null, pool);

        UUID adminId = insertUser("auth0|admin-company-edit");
        await(pool.preparedQuery("UPDATE users SET role = 'admin' WHERE id = $1")
            .execute(Tuple.of(adminId)).mapEmpty());

        // Typing a new name clears the selected id, so the service receives a null companyId and
        // has to resolve the name. That is the path an admin actually takes.
        await(admin.adminEditManager("auth0|admin-company-edit", mgr,
            "Priya Raghunathan", "Manager", "Northwind Freight", null, null));

        Row after = await(pool.preparedQuery(
                "SELECT m.company, m.company_id, c.name AS joined FROM managers m "
                + "LEFT JOIN companies c ON c.id = m.company_id WHERE m.id = $1")
            .execute(Tuple.of(mgr)).map(rows -> rows.iterator().next()));

        assertEquals("Northwind Freight", after.getString("company"), "the text moves");
        assertNotEquals(oldCompany, after.getLong("company_id"), "and so does the link");
        assertEquals("Northwind Freight", after.getString("joined"),
            "so the manager is listed under the company an admin chose");
    }

    @Test
    void submittingAdoptsTheCaptureLeftBehindInsteadOfFilingASecondPerson() throws Exception {
        /*
         * One attempt to add a manager produced two rows: the capture the form posts when step one
         * is valid, under a prefix of the company ("Mi"), and the submission that followed under
         * the finished name ("Microsoft"). Two managers, one person, differing only by how much had
         * been typed when the capture fired.
         */
        long partial = insertCompany("Mi");
        long full    = insertCompany("Microsoft");
        Row capture = await(managers.createCapturedDraft(
            "Eddie Junior", "Mi", "Engineer", "US", null, null, null, partial));
        UUID submitter = insertUser("auth0|adopt-capture");

        var adopted = await(managers.adoptCapture("Eddie Junior", "Microsoft", "Director", submitter, full));

        assertTrue(adopted.isPresent(), "the capture is adopted, not left behind");
        assertEquals(capture.getLong("id"), adopted.get().getLong("id"), "same row, updated");
        assertEquals("Microsoft", adopted.get().getString("company"), "under the finished name");
        assertEquals(submitter, adopted.get().getUUID("submitted_by"), "and now owned by its submitter");

        Long rows = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM managers WHERE LOWER(name) = 'eddie junior'")
            .execute().map(r -> r.iterator().next().getLong("c")));
        assertEquals(1L, rows, "one person, one row");
    }

    @Test
    void adoptionWillNotSwallowADifferentPersonSharingAName() throws Exception {
        // Tight on purpose: same name, captured company a prefix of the submitted one, still
        // unsubmitted, and recent. A capture at an unrelated company is somebody else.
        long other = insertCompany("Trellis Health");
        await(managers.createCapturedDraft(
            "Eddie Junior", "Trellis Health", "Nurse", "US", null, null, null, other));

        var adopted = await(managers.adoptCapture("Eddie Junior", "Microsoft", "Director",
            insertUser("auth0|adopt-nomatch"), insertCompany("Microsoft Corp")));
        assertTrue(adopted.isEmpty(), "different company, different person");
    }

    @Test
    void rejectingASubmissionLeavesAnUnrelatedNamesakeAlone() throws Exception {
        /*
         * The test that was missing, and the bug it would have caught.
         *
         * The twin sweep matched on name alone, so rejecting "John Smith at Acme" rejected every
         * ghost John Smith on the site - including a live, unrelated one at another company, whose
         * profile then 404'd. A shared name is not evidence of anything; the company being a prefix
         * of the submitted one is what makes a row the capture this attempt left behind.
         */
        long acme  = insertCompany("Acme Corp");
        long other = insertCompany("Zenith Logistics");
        long submitted = insertManager("John Smith", acme);
        long namesake  = insertManager("John Smith", other);
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'pending_approval' WHERE id = $1")
            .execute(Tuple.of(submitted)).mapEmpty());
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'ghost' WHERE id = $1")
            .execute(Tuple.of(namesake)).mapEmpty());

        await(managers.reject(submitted));

        String status = await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(namesake)).map(rows -> rows.iterator().next().getString("approval_status")));
        assertEquals("ghost", status, "a different person who happens to share a name stays live");
    }

    @Test
    void adminEditPersistsACaseOnlyCompanyChange() throws Exception {
        /*
         * "Central rock gym" -> "Central Rock Gym". Company names are unique case-insensitively,
         * so the companies row is found rather than created and its own name never changes - but
         * the manager's company text must still take the casing an admin typed, or the save looks
         * like it did nothing.
         */
        long companyId = insertCompany("Central rock gym");
        long mgr = insertManager("Kat Kaufman", companyId);

        org.werkpages.repository.CompanyRepository companyRepo =
            new org.werkpages.repository.CompanyRepository(pool);
        org.werkpages.service.AdminService admin = new org.werkpages.service.AdminService(
            users, managers, new org.werkpages.repository.ReviewRepository(pool), null,
            new org.werkpages.repository.NotificationRepository(pool), companyRepo, null, pool);

        UUID adminId = insertUser("auth0|admin-case-edit");
        await(pool.preparedQuery("UPDATE users SET role = 'admin' WHERE id = $1")
            .execute(Tuple.of(adminId)).mapEmpty());

        await(admin.adminEditManager("auth0|admin-case-edit", mgr,
            "Kat Kaufman", "VP", "Central Rock Gym", null, null));

        Row after = await(pool.preparedQuery(
                "SELECT m.company, m.company_id, c.name AS entity FROM managers m "
                + "LEFT JOIN companies c ON c.id = m.company_id WHERE m.id = $1")
            .execute(Tuple.of(mgr)).map(rows -> rows.iterator().next()));

        assertEquals("Central Rock Gym", after.getString("company"),
            "the manager takes the casing an admin typed");
        assertEquals(companyId, after.getLong("company_id"),
            "and stays linked to the same company, which is matched case-insensitively");
    }

    @Test
    void adminEditCascadesTheCompanyOntoThatManagersReviews() throws Exception {
        // The reviews carry their own copy of the company, and a rename that updates only the
        // manager leaves the page disagreeing with itself.
        long companyId = insertCompany("Central rock gym");
        long mgr = insertManager("Kat Kaufman", companyId);
        UUID author = insertUser("auth0|admin-cascade-edit");
        UUID review = insertReview(mgr, author);
        await(pool.preparedQuery("UPDATE reviews SET manager_company = 'Central rock gym' WHERE id = $1")
            .execute(Tuple.of(review)).mapEmpty());
        await(pool.preparedQuery("UPDATE managers SET company = 'Central rock gym' WHERE id = $1")
            .execute(Tuple.of(mgr)).mapEmpty());

        org.werkpages.service.AdminService admin = new org.werkpages.service.AdminService(
            users, managers, new org.werkpages.repository.ReviewRepository(pool), null,
            new org.werkpages.repository.NotificationRepository(pool),
            new org.werkpages.repository.CompanyRepository(pool), null, pool);
        UUID adminId = insertUser("auth0|admin-cascade-2");
        await(pool.preparedQuery("UPDATE users SET role = 'admin' WHERE id = $1")
            .execute(Tuple.of(adminId)).mapEmpty());

        await(admin.adminEditManager("auth0|admin-cascade-2", mgr,
            "Kat Kaufman", "Manager", "Central Rock Gym", null, null));

        String onReview = await(pool.preparedQuery("SELECT manager_company FROM reviews WHERE id = $1")
            .execute(Tuple.of(review)).map(rows -> rows.iterator().next().getString("manager_company")));
        assertEquals("Central Rock Gym", onReview, "the review's copy moves with it");
    }

    @Test
    void rejectingASubmissionLeavesANamesakeWithABlankCompanyAlone() throws Exception {
        /*
         * The prefix match's dangerous edge. `company` is NOT NULL but not non-empty, and
         * "anything" LIKE '' || '%' is true - so a ghost carrying a blank company would be swept up
         * by a rejection of any namesake at any company at all.
         */
        long acme = insertCompany("Acme Blank Co");
        long bystander = insertManager("Blank Namesake", acme);
        await(pool.preparedQuery("UPDATE managers SET company = '', approval_status = 'ghost' WHERE id = $1")
            .execute(Tuple.of(bystander)).mapEmpty());

        long other = insertCompany("Unrelated Corp");
        long submitted = insertManager("Blank Namesake", other);
        await(pool.preparedQuery(
                "UPDATE managers SET company = 'Unrelated Corp', approval_status = 'pending_approval' WHERE id = $1")
            .execute(Tuple.of(submitted)).mapEmpty());

        await(managers.reject(submitted));

        String status = await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(bystander))
            .map(rows -> rows.iterator().next().getString("approval_status")));
        assertEquals("ghost", status,
            "a blank company is not evidence that two managers are the same person");
    }

    @Test
    void aMergedManagersProfileLandsOnTheSurvivor() throws Exception {
        /*
         * The merge keeps the merged-away row and marks it rejected, pointing at the survivor.
         * Nothing followed that pointer, so merging made the profile 404 - to the admin who had
         * just merged them, the manager had vanished.
         */
        long companyId = insertCompany("Merge Follow Co wer");
        long keep = insertManager("Dana Keep", companyId);
        long gone = insertManager("Dana Dupe", companyId);
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'approved' WHERE id IN ($1, $2)")
            .execute(Tuple.of(keep, gone)).mapEmpty());

        await(managers.mergeInto(keep, gone));

        assertEquals(keep, await(managers.findByIdFollowingMerges(gone)).orElseThrow().getLong("id"),
            "the old id lands on the surviving manager");
    }

    @Test
    void aMergedManagersOldSlugStillLoadsAPage() throws Exception {
        long companyId = insertCompany("Merge Slug Co wer");
        long keep = insertManager("Robin Keep", companyId);
        long gone = insertManager("Robin Dupe", companyId);
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'approved' WHERE id IN ($1, $2)")
            .execute(Tuple.of(keep, gone)).mapEmpty());
        String oldSlug = await(pool.preparedQuery("SELECT slug FROM managers WHERE id = $1")
            .execute(Tuple.of(gone)).map(r -> r.iterator().next().getString("slug")));

        await(managers.mergeInto(keep, gone));

        assertEquals(keep, await(managers.findBySlugFollowingMerges(oldSlug)).orElseThrow().getLong("id"),
            "a bookmarked URL still reaches the person");
    }

    @Test
    void aChainOfManagerMergesLandsOnTheLastSurvivor() throws Exception {
        // A row merged twice points at a row that was itself merged.
        long companyId = insertCompany("Merge Chain Co wer");
        long finalKeep = insertManager("Sam Final", companyId);
        long middle    = insertManager("Sam Middle", companyId);
        long first     = insertManager("Sam First", companyId);
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'approved' WHERE id IN ($1,$2,$3)")
            .execute(Tuple.of(finalKeep, middle, first)).mapEmpty());

        await(managers.mergeInto(middle, first));
        await(managers.mergeInto(finalKeep, middle));

        assertEquals(finalKeep, await(managers.findByIdFollowingMerges(first)).orElseThrow().getLong("id"),
            "two merges deep still resolves");
    }

    @Test
    void aRejectedManagerThatWasNeverMergedResolvesToItself() throws Exception {
        // The limit of the rule above. Following merges must not resurrect a rejected submission:
        // it resolves to itself, and the service's rejected-is-not-found rule still applies.
        long companyId = insertCompany("Merge Limit Co wer");
        long rejected = insertManager("Nope Nobody", companyId);
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'rejected' WHERE id = $1")
            .execute(Tuple.of(rejected)).mapEmpty());

        Row row = await(managers.findByIdFollowingMerges(rejected)).orElseThrow();
        assertEquals(rejected, row.getLong("id"), "no merge to follow");
        assertEquals("rejected", row.getString("approval_status"), "and it is still rejected");
    }

    @Test
    void aGhostManagerFromFindAppearsUnderItsCompany() throws Exception {
        /*
         * The /find flow: a signed-in user searches for a manager nobody has added, so one is
         * created as a ghost - live, no review required. Going to that company's page must then
         * show them. Ghost is a live status, not a draft.
         */
        long companyId = insertCompany("Ghost Appears Co");
        UUID searcher = insertUser("auth0|ghost-appears");
        Row ghost = await(managers.createAutoApproved(
            "Priya Ramanathan", "Ghost Appears Co", "Director",
            "Canada", null, null, searcher, null, companyId));

        boolean found = false;
        for (Row r : await(new org.werkpages.repository.CompanyRepository(pool)
                .findManagersByCompanyId(companyId))) {
            if (r.getLong("id").equals(ghost.getLong("id"))) found = true;
        }
        assertTrue(found, "a ghost manager is live and belongs on its company page");
    }

    @Test
    void aGhostCompanyWithOnlyAGhostManagerStillListsInTheCompaniesTab() throws Exception {
        // The Companies tab reads company_stats_live, which is maintained by trigger. A company
        // whose only manager is a ghost still has a manager, so it has to be listed.
        long companyId = insertCompany("Ghost Listing Co");
        UUID searcher = insertUser("auth0|ghost-listing");
        await(managers.createAutoApproved("Omar Haddad", "Ghost Listing Co", "Lead",
            "Canada", null, null, searcher, null, companyId));

        Long count = await(pool.preparedQuery(
                "SELECT manager_count FROM company_stats_live WHERE company_id = $1")
            .execute(Tuple.of(companyId))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next().getLong("manager_count") : null));
        assertEquals(Long.valueOf(1L), count, "the ghost counts toward the company's manager count");
    }

    @Test
    void anAdminEditCorrectsTheCompanysOwnCapitalisation() throws Exception {
        /*
         * The manager page read managers.company and showed the correction; the company page reads
         * companies.name and did not, because resolving a name that already exists matches
         * case-insensitively and never rewrites it. One fix, two different answers on one site.
         */
        long companyId = insertCompany("central rock gym");

        boolean changed = await(new org.werkpages.repository.CompanyRepository(pool).recaseName(companyId, "Central Rock Gym"));

        assertTrue(changed, "the company row takes the corrected casing");
        assertEquals("Central Rock Gym", await(pool.preparedQuery("SELECT name FROM companies WHERE id = $1")
            .execute(Tuple.of(companyId)).map(r -> r.iterator().next().getString("name"))));
    }

    @Test
    void recasingCannotRenameACompanyToADifferentOne() throws Exception {
        // The guard that makes this safe to call from an edit form: it re-cases, it never renames.
        long companyId = insertCompany("Acme Recase Co");

        boolean changed = await(new org.werkpages.repository.CompanyRepository(pool).recaseName(companyId, "Totally Different Company"));

        assertFalse(changed, "a materially different name is refused");
        assertEquals("Acme Recase Co", await(pool.preparedQuery("SELECT name FROM companies WHERE id = $1")
            .execute(Tuple.of(companyId)).map(r -> r.iterator().next().getString("name"))));
    }

    @Test
    void mergingResolvesTheSuggestionSoItDoesNotComeBack() throws Exception {
        /*
         * Merging left the suggestion pending, so it returned on the next refresh and invited the
         * admin to run it again. Only "dismiss" ever wrote a status.
         */
        long companyId = insertCompany("Suggest Merge Co wer");
        long keep  = insertManager("Dana Dup Keep", companyId);
        long gone  = insertManager("Dana Dup Gone", companyId);
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'approved' WHERE id IN ($1,$2)")
            .execute(Tuple.of(keep, gone)).mapEmpty());
        await(new org.werkpages.repository.MergeSuggestionsRepository(pool).upsert(keep, gone, "HIGH", "same name and company", 0, 0));

        UUID adminId = insertUser("auth0|wer-merge-suggestion-admin");
        await(pool.preparedQuery("UPDATE users SET role = 'admin' WHERE id = $1")
            .execute(Tuple.of(adminId)).mapEmpty());
        await(newAdmin().mergeManagers("auth0|wer-merge-suggestion-admin", keep, gone));

        String status = await(pool.preparedQuery(
                "SELECT status FROM merge_suggestions WHERE manager_id_a = $1 AND manager_id_b = $2")
            .execute(Tuple.of(keep, gone))
            .map(rows -> rows.iterator().next().getString("status")));
        assertEquals("merged", status, "an acted-on suggestion does not return to the queue");
    }

    @Test
    void aManagerAlreadyMergedAwayCannotBeMergedAgain() throws Exception {
        /*
         * The dangerous one. A merge retires the row it absorbs rather than deleting it, and the
         * only guard was "does a row with this id exist" - which a retired row satisfies. So a
         * repeated suggestion, or a reciprocal pair, retired a second live manager each time and
         * the directory count fell with every click.
         */
        long companyId = insertCompany("Double Merge Co wer");
        long keep  = insertManager("Eli Twice Keep", companyId);
        long gone  = insertManager("Eli Twice Gone", companyId);
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'approved' WHERE id IN ($1,$2)")
            .execute(Tuple.of(keep, gone)).mapEmpty());
        UUID adminId = insertUser("auth0|wer-double-merge-admin");
        await(pool.preparedQuery("UPDATE users SET role = 'admin' WHERE id = $1")
            .execute(Tuple.of(adminId)).mapEmpty());

        await(newAdmin().mergeManagers("auth0|wer-double-merge-admin", keep, gone));

        try {
            await(newAdmin().mergeManagers("auth0|wer-double-merge-admin", keep, gone));
            org.junit.jupiter.api.Assertions.fail("a second merge of the same pair must be refused");
        } catch (Exception expected) {
            // refused, as it should be
        }

        String keepStatus = await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(keep)).map(r -> r.iterator().next().getString("approval_status")));
        assertEquals("approved", keepStatus, "the survivor is still live");
    }

    @Test
    void aReciprocalSuggestionCannotRetireTheSurvivor() throws Exception {
        // A into B, then B into A. Without the guard this took both out of the directory.
        long companyId = insertCompany("Reciprocal Co wer");
        long a = insertManager("Fay Mirror One", companyId);
        long b = insertManager("Fay Mirror Two", companyId);
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'approved' WHERE id IN ($1,$2)")
            .execute(Tuple.of(a, b)).mapEmpty());
        UUID adminId = insertUser("auth0|wer-reciprocal-admin");
        await(pool.preparedQuery("UPDATE users SET role = 'admin' WHERE id = $1")
            .execute(Tuple.of(adminId)).mapEmpty());

        await(newAdmin().mergeManagers("auth0|wer-reciprocal-admin", a, b));
        try {
            await(newAdmin().mergeManagers("auth0|wer-reciprocal-admin", b, a));
            org.junit.jupiter.api.Assertions.fail("the reciprocal merge must be refused");
        } catch (Exception expected) {
            // refused
        }

        String aStatus = await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(a)).map(r -> r.iterator().next().getString("approval_status")));
        assertEquals("approved", aStatus, "the survivor never leaves the directory");
    }

    @Test
    void aRepeatedMergeDoesNotRemoveAnotherManagerFromTheDirectory() throws Exception {
        /*
         * The question this answers: clicking merge, refreshing, and clicking merge again on the
         * same pair - does the directory lose a second manager?
         *
         * It must not. The first merge retires the duplicate; the second has nothing left to retire
         * and is refused outright. The count is asserted before and after, because "the number went
         * down again" is the only symptom an admin can actually see.
         */
        long companyId = insertCompany("Repeat Count Co wer");
        long keep = insertManager("Gwen Repeat Keep", companyId);
        long gone = insertManager("Gwen Repeat Gone", companyId);
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'approved' WHERE id IN ($1,$2)")
            .execute(Tuple.of(keep, gone)).mapEmpty());
        UUID adminId = insertUser("auth0|wer-repeat-count-admin");
        await(pool.preparedQuery("UPDATE users SET role = 'admin' WHERE id = $1")
            .execute(Tuple.of(adminId)).mapEmpty());

        await(newAdmin().mergeManagers("auth0|wer-repeat-count-admin", keep, gone));
        long afterFirst = directoryCount();

        try {
            await(newAdmin().mergeManagers("auth0|wer-repeat-count-admin", keep, gone));
        } catch (Exception refused) {
            // expected
        }
        assertEquals(afterFirst, directoryCount(),
            "a repeated merge must not cost the directory another manager");
    }

    private long directoryCount() throws Exception {
        return await(pool.preparedQuery(
                "SELECT COUNT(*) AS n FROM managers WHERE approval_status IN ('approved','ghost')")
            .execute().map(rows -> rows.iterator().next().getLong("n")));
    }

    /** Admin service wired with the merge-suggestions repo, which the merge path now resolves. */
    private org.werkpages.service.AdminService newAdmin() {
        return new org.werkpages.service.AdminService(
            users, managers, new org.werkpages.repository.ReviewRepository(pool), null,
            new org.werkpages.repository.NotificationRepository(pool),
            new org.werkpages.repository.CompanyRepository(pool),
            new org.werkpages.repository.MergeSuggestionsRepository(pool));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private UUID insertUser(String auth0Id) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, username, email) VALUES ($1, $2, $3) RETURNING id")
            .execute(Tuple.of(auth0Id, auth0Id, auth0Id + "@example.test"))
            .map(rows -> rows.iterator().next().getUUID("id")));
    }

    private long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies (name, slug, status) VALUES ($1, $2, 'ghost') RETURNING id")
            .execute(Tuple.of(name, name.toLowerCase().replace(' ', '-')))
            .map(rows -> rows.iterator().next().getLong("id")));
    }

    private long insertManager(String name, long companyId) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers (name, company, title, status, approval_status, company_id,
                                      slug, overall_rating, reviews_count, category_averages)
                SELECT $1, c.name, 'Manager', 'active', 'approved', $2, $3, 0, 0, '{}'::jsonb
                FROM companies c WHERE c.id = $2
                RETURNING id
                """)
            .execute(Tuple.of(name, companyId,
                name.toLowerCase().replace(' ', '-') + "-" + companyId))
            .map(rows -> rows.iterator().next().getLong("id")));
    }

    private Long companyOf(long managerId) throws Exception {
        return await(pool.preparedQuery("SELECT company_id FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)).map(rows -> rows.iterator().next().getLong("company_id")));
    }

    private UUID insertReview(long managerId, UUID userId) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, user_id, author, overall_rating,
                    communication_style, perceived_approachability, perceived_clarity_of_expectations,
                    feedback_style, perceived_supportiveness, decision_making_style,
                    organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                    overall_working_experience, manager_company, manager_title)
                VALUES ($1,$2,'Anon-' || substr($2::text, 1, 8),4.0,4,4,4,4,4,4,4,4,4,4,'Co','Manager')
                RETURNING id
                """)
            .execute(Tuple.of(managerId, userId))
            .map(rows -> rows.iterator().next().getUUID("id")));
    }

    private UUID insertHeldReview(long managerId, UUID userId) throws Exception {
        UUID id = insertReview(managerId, userId);
        await(pool.preparedQuery(
                "UPDATE reviews SET disposition = 'held', gate_eligible = FALSE, live_since = NULL "
                + "WHERE id = $1").execute(Tuple.of(id)).mapEmpty());
        return id;
    }
}

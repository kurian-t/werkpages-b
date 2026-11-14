package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 (V99 to V103) applied the way production will apply it: to a database that already
 * holds managers, career history, ratings and captured drafts.
 *
 * <h2>Why this exists separately from the rest of the suite</h2>
 *
 * <p>Every other integration test migrates a brand-new container from V1 to the head, which proves
 * these five migrations run on a CLEAN database and nothing else. Production is not clean, and the
 * questions that matter there are different ones:
 *
 * <ul>
 *   <li>does a schema with rows in it survive the upgrade with every rating intact;</li>
 *   <li>do the two gates in V99 actually refuse, with a count an operator can act on, and does a
 *       refusal leave NOTHING half-applied;</li>
 *   <li>are the backfills right, and do they claim only what they know;</li>
 *   <li>do the statements both backends write TODAY still work against the expanded schema.</li>
 * </ul>
 *
 * <p>The last one is the CLAUDE.md section 10 question. {@code reviews} and {@code career_history}
 * are shared tables, RateMyManagers writes both, and it runs whatever code is deployed at the
 * moment Werkpages applies these migrations. A constraint that only the new code satisfies is an
 * outage in the other product.
 *
 * <h2>The sequence this fixture walks</h2>
 *
 * <p>Deliberately the whole operator procedure, failures included, because the refusals are a
 * specified feature of V99 rather than an error path: migrate to V98, write rows of the shapes
 * production holds, watch V99 refuse on the company gate, resolve that straggler, watch it refuse
 * on the duplicate gate, resolve that one, then migrate for real. Flyway records a failed migration
 * and will not migrate again until the history is repaired, so {@code repair()} is part of the
 * procedure and is exercised here too.
 */
@Testcontainers
class Phase2UpgradeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_phase2_upgrade")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;

    static long lumenId;
    static long acmeId;
    static long survivorId;      // a manager another profile was merged INTO
    static long absorbedId;
    static long cleanId;         // a manager no merge ever touched
    static UUID signedReviewId;  // on the survivor, so its lineage is unknowable
    static UUID anonReviewId;    // on the clean manager
    static UUID heldReviewId;
    static long cleanRoleId;
    static long stragglerRoleId; // the row with no company_id
    static long collidingRoleId; // distinct today, identical under the normalised key
    static long draftId;

    // Captured while the database was still mid-procedure, because the evidence stops existing
    // the moment the migration finally succeeds.
    static String companyGateMessage;
    static String duplicateGateMessage;
    static boolean schemaUntouchedAfterCompanyGate;
    static boolean schemaUntouchedAfterDuplicateGate;

    @BeforeAll
    static void upgradeTheWayProductionWill() throws Exception {
        // 1. The schema as it stands today: phase 1 applied, phase 2 withheld.
        /*
          V96 IS THE "BEFORE" BOUNDARY NOW, and it is the one production actually has.

          This used to step to a version inside what is now V97. The sixteen migrations V97 to
          V112 were consolidated into one file, because none of them had ever run anywhere but
          disposable local databases, so there is no longer a boundary between them to stop at and
          Flyway refuses a target that names one.

          V96 is where production is. Stepping to it, writing the rows an upgrade has to cope
          with, and then migrating the rest is exactly the upgrade production will perform, which
          makes this a better rehearsal than the original rather than a weaker one.
        */
        flyway().target(MigrationVersion.fromVersion("96")).load().migrate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(4));

        assertFalse(tableExists("role_claims"), "fixture: phase 2 must not have run yet");
        assertFalse(columnExists("career_history", "title_key"),
            "fixture: or this test is checking nothing");

        // 2. Rows of the shapes production holds, written by the pre-V99 statements.
        lumenId = insertCompany("Lumen Health", "lumen-health");
        acmeId   = insertCompany("Acme Logistics", "acme-logistics");

        survivorId = insertManager("Priya Raghunathan", "Lumen Health", "lumen-priya", lumenId, null);
        absorbedId = insertManager("P. Raghunathan", "Lumen Health", "lumen-p-rag", lumenId, survivorId);
        cleanId    = insertManager("Dana Whitfield", "Acme Logistics", "acme-dana", acmeId, null);

        signedReviewId = insertSignedReview(survivorId, "Lumen Health", "Team Lead", "Ash B", 4.0);
        anonReviewId   = insertAnonReview(cleanId, "Acme Logistics", "Dispatch Manager", "Cal C", 5.0);
        heldReviewId   = insertHeldReview(cleanId, "Acme Logistics", "Dispatch Manager", "Dot D", 2.0);

        cleanRoleId     = insertCareerRow(survivorId, "Lumen Health", "Team Lead",
                                          "2022-03-01T00:00:00Z", lumenId);
        stragglerRoleId = insertCareerRow(cleanId, "Acme Logistics", "Dispatch Manager",
                                          "2021-06-01T00:00:00Z", null);
        // Distinct under the OLD representation and identical under the new one: the punctuation
        // and case differ, and the time of day differs while the UTC calendar day does not.
        collidingRoleId = insertCareerRow(survivorId, "Lumen Health", "  team lead!! ",
                                          "2022-03-01T18:00:00Z", lumenId);

        draftId = insertCapturedDraft();

        // 3. THE FIRST REFUSAL: one career row has no company_id.
        companyGateMessage = migrateExpectingFailure();
        schemaUntouchedAfterCompanyGate = !columnExists("career_history", "title_key")
                                       && !tableExists("role_claims");
        flyway().load().repair();

        // 4. The operator resolves it. findOrCreate already produced the company row; this links
        //    the role to it. Nothing is invented and no role is deleted.
        await(pool.preparedQuery("UPDATE career_history SET company_id = $1 WHERE id = $2")
            .execute(Tuple.of(acmeId, stragglerRoleId)).mapEmpty());

        // 5. THE SECOND REFUSAL, and it is not about company_id: two rows now collide under
        //    (manager, company, title_key, start_day).
        duplicateGateMessage = migrateExpectingFailure();
        schemaUntouchedAfterDuplicateGate = !columnExists("career_history", "title_key")
                                         && !tableExists("role_claims");
        flyway().load().repair();

        // 6. The operator corrects the title that was actually wrong. This is one of the two
        //    resolutions available BEFORE V99: marking a row superseded is not, because
        //    superseded_by does not exist until V99 has added it.
        await(pool.preparedQuery("UPDATE career_history SET title = $1 WHERE id = $2")
            .execute(Tuple.of("Interim Team Lead", collidingRoleId)).mapEmpty());

        // 7. The upgrade, for real.
        flyway().load().migrate();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- the gates

    @Test
    void theCompanyGateRefusesWithACountRatherThanDegrading() {
        // Rev 268 is explicit that there is ONE production path here. An earlier revision offered a
        // degraded alternative that kept the column nullable, and it was withdrawn because two
        // incompatible migration paths in one specification is how the wrong one gets run. So the
        // migration has to REFUSE, and the refusal has to say how much work is outstanding.
        assertNotNull(companyGateMessage, "V99 must refuse while any career row has no company_id");
        assertTrue(companyGateMessage.contains("V99 refuses"), companyGateMessage);
        assertTrue(companyGateMessage.contains("1 career_history rows have no company_id"),
            "the count is the point: an operator needs to know the size of the job. Got: "
            + companyGateMessage);
    }

    @Test
    void theDuplicateGateRefusesSeparatelyAndIsNotAboutCompanyIds() {
        // The second gate is the one an earlier revision missed entirely. Every company_id was
        // populated by this point and the migration still cannot proceed, because rows that are
        // distinct today collide under the normalised key.
        assertNotNull(duplicateGateMessage, "V99 must refuse while the normalised key collides");
        assertTrue(duplicateGateMessage.contains("employment keys hold more than one role"),
            duplicateGateMessage);
    }

    @Test
    void aRefusedMigrationLeavesNothingHalfApplied() {
        // This is what makes the refusals safe to run against production at all. The gates sit
        // AFTER several ALTERs, so without transactional DDL a refusal would leave the table
        // half-promoted and the next attempt would fail on an already-existing column instead of
        // on the real problem.
        assertTrue(schemaUntouchedAfterCompanyGate,
            "after the company gate refused, career_history must not carry title_key and "
            + "role_claims must not exist");
        assertTrue(schemaUntouchedAfterDuplicateGate,
            "and the same after the duplicate gate refused");
    }

    // ------------------------------------------------- the rows production already holds

    @Test
    void everyRatingSurvivesTheUpgradeWithItsNumbersUntouched() throws Exception {
        assertEquals(3, countReviews(), "no rating is dropped, merged or rewritten");
        assertEquals(4.0, ratingOf(signedReviewId), 0.001);
        assertEquals(5.0, ratingOf(anonReviewId), 0.001);
        assertEquals(2.0, ratingOf(heldReviewId), 0.001);
        assertEquals("live", dispositionOf(signedReviewId));
        assertEquals("held", dispositionOf(heldReviewId),
            "a held rating stays held: nothing in phase 2 publishes anything");
    }

    @Test
    void everyExistingRatingIsLegacyBasisAndCarriesNoClaim() throws Exception {
        /*
          The claim and role half is still inert, and that is what this test is for: claim_id stays
          null and role_required stays false for every existing rating, so none is back-filled into
          a claim or a role by descriptive matching and nothing is inferred from the title strings
          they carry.

          THE ATTRIBUTION HALF CHANGED IN V102, deliberately, and this assertion changed with it.

          Until V102 every legacy rating carried attribution_basis = 'none', because V97 introduced
          the column and would not guess. The populated migration rehearsal showed what that meant
          once Phase 5 read those figures: zero attributed ratings, and a company profile reading
          "No reviews yet" for every company on the site.

          V102 attributes them by THE EMPLOYMENT THE RATING DESCRIBES: the work period the
          contributor stated, matched against the manager's accepted employment record.

          It deliberately does NOT use reviews.manager_company, which looks like the contributor's
          own statement and is not - six code paths rewrite it, and a manager company EDIT rewrites
          it on every one of that manager's ratings. Attributing from it would move a 2018
          experience onto a 2021 employer, which is exactly the retroactive attribution Phase 5
          exists to prevent. An earlier version of this test asserted the attribution MATCHED that
          column, which could never have failed: the edit path keeps the text and the attribution
          agreeing precisely by falsifying the history.

          The basis is 'inferred', never 'submitted'. 'submitted' means the contributor named the
          employer; nothing V102 attributes was named by anybody, and conflating the two would
          destroy the distinction permanently.
        */
        for (UUID id : new UUID[]{signedReviewId, anonReviewId, heldReviewId}) {
            Row r = reviewRow(id);
            assertEquals("legacy", r.getString("role_basis"), "role_basis for " + id);
            assertFalse(r.getBoolean("role_required"));
            assertNull(r.getLong("claim_id"));
            assertNull(r.getLong("withdrawn_role_id"));
            assertEquals("indexed", r.getString("legacy_dedup_state"));
            assertNull(r.getUUID("merge_duplicate_of"));

            String basis = r.getString("attribution_basis");
            assertTrue("inferred".equals(basis) || "none".equals(basis),
                "a legacy rating is attributed from the employment it describes, or not at all. "
                + "'submitted' would claim the contributor named the employer, which V102 never "
                + "knows: " + basis);

            if ("inferred".equals(basis)) {
                Long attributed = r.getLong("attributed_company_id");
                assertNotNull(attributed,
                    "'inferred' asserts an employer was worked out, so one must be recorded");
                assertTrue(managerWasEmployedBy(r.getLong("manager_id"), attributed),
                    "every attribution must rest on an employment the manager actually held. "
                    + "This is the property that matters and it does not depend on which rule "
                    + "attributed the rating: an attribution with no matching accepted "
                    + "career_history row was guessed from somewhere it should not have been");
            } else {
                assertNull(r.getLong("attributed_company_id"),
                    "'none' means no employer is known, so none may be recorded");
            }
        }
    }

    /**
     * Whether this manager has an accepted employment at this company.
     *
     * <p>Replaces a helper that resolved a company from {@code reviews.manager_company}, which was
     * the old rule and was the wrong question. This one asks whether the attribution rests on
     * evidence that no application path rewrites.
     */
    private boolean managerWasEmployedBy(long managerId, long companyId) throws Exception {
        var rows = await(pool.preparedQuery("""
                SELECT 1 FROM career_history
                 WHERE manager_id = $1 AND company_id = $2 AND state = 'accepted'
                """)
            .execute(io.vertx.sqlclient.Tuple.of(managerId, companyId)));
        return rows.iterator().hasNext();
    }

    @Test
    void theLineageBackfillClaimsOnlyWhatItCanKnow() throws Exception {
        // original_manager_id is back-filled from manager_id for every row, and lineage_basis says
        // exactly what that is worth. Calling it 'submitted' would be a fabrication with a column
        // name attached: merges have already happened in production, managers.merged_into records
        // where a PROFILE went rather than where a RATING came from, and a rating sitting on a
        // survivor today may have arrived from a profile absorbed long before any of this existed.
        assertEquals(survivorId, (long) reviewRow(signedReviewId).getLong("original_manager_id"));
        assertEquals("unknown", reviewRow(signedReviewId).getString("lineage_basis"),
            "this manager IS a merge target, so nothing records whether this rating arrived in the "
            + "merge. 'unknown' is the honest answer and the column must not say more");

        assertEquals(cleanId, (long) reviewRow(anonReviewId).getLong("original_manager_id"));
        assertEquals("current_association", reviewRow(anonReviewId).getString("lineage_basis"),
            "no merge can have moved this one, so the current association is almost certainly the "
            + "submitted one. Still not 'submitted': only a claim's finalization records that");

        assertEquals(0, await(pool.query(
                "SELECT COUNT(*) AS c FROM reviews WHERE lineage_basis = 'submitted'").execute()
            .map(rs -> rs.iterator().next().getInteger("c"))).intValue(),
            "no legacy rating may be labelled 'submitted'");
    }

    @Test
    void existingCareerRowsBecomeAcceptedRolesWithAKnownPeriod() throws Exception {
        Row role = careerRow(cleanRoleId);
        assertEquals("accepted", role.getString("state"),
            "an existing employment record is accepted history, not a proposal awaiting review");
        assertTrue(role.getBoolean("period_known"),
            "every existing row has a start date, because the column was NOT NULL until V99. Left "
            + "at the default they would all fall outside career_unique_known_employment and "
            + "duplicate canonical roles could then be created for the employments it protects");
        assertTrue(role.getBoolean("rateable"));
        assertEquals("import", role.getString("source"));
        assertEquals("team lead", role.getString("title_key"));
        assertEquals("2022-03-01", role.getLocalDate("start_day").toString());
        assertNull(role.getLong("superseded_by"));
    }

    @Test
    void theStragglerKeepsTheCompanyTheOperatorLinkedItTo() throws Exception {
        assertEquals(acmeId, (long) careerRow(stragglerRoleId).getLong("company_id"),
            "the operator step is part of the procedure, and the migration does not undo it");
    }

    @Test
    void theCapturedDraftSurvives() throws Exception {
        assertEquals(1, await(pool.preparedQuery("SELECT COUNT(*) AS c FROM captured_drafts WHERE id = $1")
            .execute(Tuple.of(draftId)).map(rs -> rs.iterator().next().getInteger("c"))).intValue(),
            "review_supersessions takes a foreign key to captured_drafts; nothing about adding it "
            + "may disturb the drafts already there");
    }

    // ------------------------------------------- what the CURRENT applications still write

    @Test
    void theReviewInsertBothBackendsWriteTodayStillWorks() throws Exception {
        // Verbatim in shape: the column list a pre-phase-2 backend sends, naming none of the new
        // columns. If this fails, deploying these migrations takes RateMyManagers down with them.
        UUID id = await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                     manager_title, text, verified)
                VALUES ($1, $2, $3, $4, $5, $6, false) RETURNING id
                """)
            .execute(Tuple.of(cleanId, "Eve E", 3.5, "Acme Logistics", "Dispatch Manager", "ok"))
            .map(rs -> rs.iterator().next().getUUID("id")));

        Row r = reviewRow(id);
        assertEquals("legacy", r.getString("role_basis"),
            "the defaults have to describe what a pre-cutover rating IS, or the old write path "
            + "cannot satisfy reviews_basis_matches_claim and its siblings");
        assertEquals("none", r.getString("attribution_basis"));
        assertFalse(r.getBoolean("role_required"));
        assertEquals("live", r.getString("disposition"),
            "and the five publication triggers let it through, because a legacy rating needs no "
            + "role: rating_role_is_publishable returns true on its first branch");
    }

    @Test
    void theCareerInsertStillWorksWhenItCarriesACompanyAndIsREFUSEDWhenItDoesNot() throws Exception {
        // The lockstep half of this migration, and the reason AdminService.companyIdForCareerEntry
        // exists. The insert statement itself is unchanged in both backends; what changed is that
        // every caller must now resolve a company id first, because this column stopped tolerating
        // null the moment V99 ran.
        long ok = insertCareerRow(cleanId, "Acme Logistics", "Night Supervisor",
                                  "2024-02-01T00:00:00Z", acmeId);
        assertTrue(ok > 0, "with a company id, the old statement is unaffected");

        Exception refused = assertThrows(Exception.class, () ->
            insertCareerRow(cleanId, "Acme Logistics", "Day Supervisor",
                            "2024-03-01T00:00:00Z", null));
        assertTrue(String.valueOf(refused.getMessage()).contains("company_id"),
            "and without one it is refused rather than writing a role with no employer. This is "
            + "the failure the application change prevents, and it is asserted here so that "
            + "removing that change breaks a test rather than production. Got: "
            + refused.getMessage());
    }

    @Test
    void theV37UniquenessProtectionIsNarrowedRatherThanLost() throws Exception {
        assertFalse(indexExists("idx_reviews_user_manager_role"),
            "the string-keyed index is gone, because it cannot tell two employment periods apart");
        assertFalse(indexExists("idx_reviews_author_manager_role"));
        assertTrue(indexExists("idx_reviews_user_manager_role_legacy"));
        assertTrue(indexExists("idx_reviews_author_manager_role_legacy"));

        // Narrowed is not weakened for the rows it still covers. Every rating in the database
        // today is role_basis 'legacy' and legacy_dedup_state 'indexed', so the new indexes cover
        // exactly the same population the old ones did, and a second rating of one role by one
        // author is still refused.
        Exception dup = assertThrows(Exception.class, () -> insertAnonReview(
            cleanId, "Acme Logistics", "Dispatch Manager", "Cal C", 1.0));
        assertTrue(String.valueOf(dup.getMessage()).contains("idx_reviews_author_manager_role_legacy"),
            "the same duplicate the old index refused is still refused, by its narrowed "
            + "replacement. Got: " + dup.getMessage());
    }

    // ------------------------------------------------------- the invariants now enforced

    @Test
    void aLiveRatingMayNotNameAnUnresolvedClaim() throws Exception {
        long claim = insertClaim(survivorId, lumenId, "Team Lead");
        Exception e = assertThrows(Exception.class, () -> insertClaimedReview(survivorId, claim));
        assertTrue(String.valueOf(e.getMessage()).contains("its claim is unresolved"),
            "and the message says WHICH condition failed, through rating_publication_problem, so "
            + "an operator is sent to the right table. Got: " + e.getMessage());
    }

    @Test
    void aRatingMayNotNameAnotherManagersClaim() throws Exception {
        long claim = insertClaim(survivorId, lumenId, "Team Lead");
        resolveClaimTo(claim, cleanRoleId);
        Exception e = assertThrows(Exception.class, () -> insertClaimedReview(cleanId, claim));
        assertTrue(String.valueOf(e.getMessage()).contains("reviews_claim_is_the_managers"),
            "the composite foreign key makes the incoherent record unrepresentable rather than "
            + "merely discouraged. Got: " + e.getMessage());
    }

    @Test
    void rejectingARoleIsRefusedWhileALiveRatingDependsOnIt() throws Exception {
        long role = insertCareerRow(cleanId, "Acme Logistics", "Depot Lead",
                                    "2023-05-01T00:00:00Z", acmeId);
        long claim = insertClaim(cleanId, acmeId, "Depot Lead");
        resolveClaimTo(claim, role);
        UUID rating = insertClaimedReview(cleanId, claim);
        assertNotNull(rating, "with the role accepted and the claim linked, the rating publishes");

        Exception e = assertThrows(Exception.class, () ->
            await(pool.preparedQuery("UPDATE career_history SET state = 'rejected' WHERE id = $1")
                .execute(Tuple.of(role)).mapEmpty()));
        assertTrue(String.valueOf(e.getMessage()).contains("its role is rejected"),
            "the role row is reached from the rating through the claim, and the deferred trigger "
            + "walks it in the other direction too. Got: " + e.getMessage());
    }

    @Test
    void theResolutionAuditIsWrittenByTheDatabaseAndCannotBeEdited() throws Exception {
        long role = insertCareerRow(cleanId, "Acme Logistics", "Yard Lead",
                                    "2023-08-01T00:00:00Z", acmeId);
        long claim = insertClaim(cleanId, acmeId, "Yard Lead");
        resolveClaimTo(claim, role);

        Row event = await(pool.preparedQuery("""
                SELECT event, from_role_id, to_role_id, resolution, reason
                  FROM claim_resolution_events WHERE claim_id = $1 ORDER BY id DESC LIMIT 1
                """).execute(Tuple.of(claim)).map(rs -> rs.iterator().next()));
        assertEquals("resolved", event.getString("event"),
            "no code path can change a resolution without leaving the record, because the record "
            + "is written by a trigger rather than by whoever remembered to");
        assertNull(event.getLong("from_role_id"), "a first resolution has nothing before it");
        assertEquals(role, (long) event.getLong("to_role_id"));
        assertEquals("automatic", event.getString("reason"),
            "a null actor is not a gap in the record, it is the record: the platform resolved this "
            + "with nobody deciding");

        Exception upd = assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "UPDATE claim_resolution_events SET reason = 'tampered' WHERE claim_id = $1")
            .execute(Tuple.of(claim)).mapEmpty()));
        assertTrue(String.valueOf(upd.getMessage()).contains("append-only"), upd.getMessage());
        Exception del = assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "DELETE FROM claim_resolution_events WHERE claim_id = $1")
            .execute(Tuple.of(claim)).mapEmpty()));
        assertTrue(String.valueOf(del.getMessage()).contains("append-only"), del.getMessage());
    }

    @Test
    void oneEmploymentCannotHoldTwoLiveCanonicalRolesONCEThePeriodIsKnown() throws Exception {
        // Both halves of this matter, and the second is the one that surprises.
        //
        // WITH period_known, the index does its job: same manager, same employer, and a title and
        // a timestamp that are distinct as text and identical under (title_key, start_day). The
        // punctuation and case differ and the UTC calendar day does not. This is exactly the
        // collision the V99 gate looks for in existing data, now enforced for new rows.
        insertKnownPeriodRole(cleanId, "Fleet Manager", "2020-01-01T00:00:00Z");
        Exception e = assertThrows(Exception.class, () ->
            insertKnownPeriodRole(cleanId, " FLEET  manager!! ", "2020-01-01T09:30:00Z"));
        assertTrue(String.valueOf(e.getMessage()).contains("career_unique_known_employment"),
            e.getMessage());

        // WITHOUT it, the row is outside the index, and that is deliberate rather than an
        // oversight: an unknown date is not evidence of sameness. It also means the statement both
        // backends write TODAY, which names no period_known and gets the FALSE default, is
        // unaffected by this index in either direction. That is what makes the phase inert, and it
        // is also the reason the protection does not begin to apply to new roles until phase 4
        // starts writing period_known. Asserted so that nobody reads the index and assumes it is
        // already guarding the live write path.
        long a = insertCareerRow(cleanId, "Acme Logistics", "Hub Manager", "2019-01-01T00:00:00Z", acmeId);
        long b = insertCareerRow(cleanId, "Acme Logistics", "hub  manager", "2019-01-01T11:00:00Z", acmeId);
        assertNotEquals(a, b, "two rows, both unknown-period, both kept");
        assertFalse(careerRow(a).getBoolean("period_known"),
            "the old statement leaves period_known at its FALSE default");
    }

    @Test
    void theNormaliserKeepsItsContractUnderThisDatabasesCollation() throws Exception {
        // Rule 2, NEVER EMPTY for a non-empty input, and it is the rule an earlier expression
        // broke. [^a-z0-9]+ kept ASCII and nothing else: "Engineering Manager" survived while two
        // different Chinese titles both became '', so two different jobs at one employer on one day
        // were indistinguishable to career_unique_known_employment. Unacceptable anywhere, and
        // especially on Werkpages, which carries international data by design.
        //
        // Collation-dependent, which is why it is asserted against a real server rather than
        // reasoned about: both lower() and [[:alnum:]] can change behaviour across a collation or
        // ICU upgrade, and PostgreSQL does not recompute a stored generated column when they do.
        assertEquals("engineering manager", normalise("Engineering Manager"));
        assertEquals("senior manager ops", normalise("  Senior   Manager, Ops!! "));
        assertEquals("manager ii", normalise("Manager II"));
        assertNotEquals("", normalise("工程經理"));
        assertNotEquals(normalise("工程經理"), normalise("產品經理"),
            "two different titles must not share one key, whatever alphabet they are in");
        assertNotEquals("", normalise("Менеджер"));
        assertEquals("---", normalise("---"),
            "punctuation only falls through the COALESCE to the trimmed original rather than "
            + "collapsing to the empty key career_title_key_not_empty refuses");
    }

    @Test
    void theEnrichmentSweepCannotRaiseAConflictThatHoldsARating() throws Exception {
        // Defence in depth, and the document is careful not to claim more: a current_setting guard
        // catches a bug in the sweep, which is the realistic failure, and catches nothing from a
        // writer that does not set the label. The unconditional invariant is the five triggers.
        // withTRANSACTION, not withConnection: SET LOCAL outside a transaction block is a no-op,
        // so the guard would never see the label and this test would pass for the wrong reason.
        // It did, until the deferred subject check refused the insert instead and said so.
        Exception e = assertThrows(Exception.class, () -> await(pool.withTransaction(conn ->
            conn.query("SET LOCAL rmm.actor = 'sweep'").execute()
                .compose(v -> conn.preparedQuery("""
                        INSERT INTO role_conflicts (kind, manager_id, company_id, title_key,
                                                    period_key, manager_name, company_name, title)
                        VALUES ('possible_duplicate', $1, $2, 'team lead', 'unknown',
                                'Priya Raghunathan', 'Lumen Health', 'Team Lead')
                        """).execute(Tuple.of(survivorId, lumenId)))
                .mapEmpty())));
        assertTrue(String.valueOf(e.getMessage()).contains("may not raise a blocking conflict"),
            e.getMessage());

        // An enrichment kind, by the same writer, is allowed: the sweep's whole job is to add
        // context a moderator reads, and nothing it writes may hold a rating.
        await(pool.withTransaction(conn ->
            conn.query("SET LOCAL rmm.actor = 'sweep'").execute()
                .compose(v -> conn.preparedQuery("""
                        INSERT INTO role_conflicts (kind, manager_id, company_id, title_key,
                                                    period_key, manager_name, company_name, title)
                        VALUES ('overlapping_period', $1, $2, 'team lead', 'unknown',
                                'Priya Raghunathan', 'Lumen Health', 'Team Lead')
                        """).execute(Tuple.of(survivorId, lumenId)))
                .mapEmpty()));
        assertFalse(awaitBool(pool.query("""
                SELECT blocks_publication FROM role_conflicts
                 WHERE kind = 'overlapping_period' ORDER BY id DESC LIMIT 1
                """).execute().map(rs -> rs.iterator().next().getBoolean("blocks_publication"))),
            "and blocks_publication is DERIVED from the kind rather than set by whoever inserts "
            + "the row, so an enrichment task cannot be given a blocking flag by mistake");
    }

    @Test
    void aBlockingConflictWithNoSubjectIsRefused() {
        // One with only evidence holds nothing and is an enrichment task wearing a blocking kind,
        // which is a bug rather than a state to tolerate.
        Exception e = assertThrows(Exception.class, () -> await(pool.preparedQuery("""
                INSERT INTO role_conflicts (kind, manager_id, company_id, title_key, period_key,
                                            manager_name, company_name, title)
                VALUES ('ambiguous_resolution', $1, $2, 'team lead', '2022-03-01',
                        'Priya Raghunathan', 'Lumen Health', 'Team Lead')
                """).execute(Tuple.of(survivorId, lumenId)).mapEmpty()));
        assertTrue(String.valueOf(e.getMessage()).contains("has no subject claim"), e.getMessage());
    }

    // ------------------------------------------------------------------ fixture plumbing

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations");
    }

    /** Runs the remaining migrations, expecting a refusal, and returns the message chain. */
    private static String migrateExpectingFailure() {
        try {
            flyway().load().migrate();
            return null;
        } catch (Exception e) {
            StringBuilder sb = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) sb.append(t.getMessage()).append(" | ");
            return sb.toString();
        }
    }

    private static long insertCompany(String name, String slug) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies (name, slug, status) VALUES ($1,$2,'ghost') RETURNING id")
            .execute(Tuple.of(name, slug)).map(rs -> rs.iterator().next().getLong("id")));
    }

    private static long insertManager(String name, String company, String slug,
                                      Long companyId, Long mergedInto) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers (name, company, title, status, approval_status, country,
                                      company_id, slug, overall_rating, reviews_count,
                                      category_averages, merged_into)
                VALUES ($1,$2,'Manager','active','approved','CA',$3,$4,0,0,'{}'::jsonb,$5)
                RETURNING id
                """)
            .execute(Tuple.of(name, company, companyId, slug, mergedInto))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static UUID insertSignedReview(long managerId, String company, String title,
                                           String author, double rating) throws Exception {
        UUID user = await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, username) VALUES ($1,$2) RETURNING id")
            .execute(Tuple.of("auth0|" + author.hashCode(), "u" + Math.abs(author.hashCode())))
            .map(rs -> rs.iterator().next().getUUID("id")));
        return await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, user_id, author, overall_rating, manager_company,
                                     manager_title, text)
                VALUES ($1,$2,$3,$4,$5,$6,'legacy rating') RETURNING id
                """)
            .execute(Tuple.of(managerId, user, author, rating, company, title))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static UUID insertAnonReview(long managerId, String company, String title,
                                         String author, double rating) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                     manager_title, text)
                VALUES ($1,$2,$3,$4,$5,'legacy rating') RETURNING id
                """)
            .execute(Tuple.of(managerId, author, rating, company, title))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static UUID insertHeldReview(long managerId, String company, String title,
                                         String author, double rating) throws Exception {
        // reviews_state_coherent, from the existing schema: anything other than live carries no
        // live_since and is not gate eligible.
        return await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                     manager_title, text, disposition, gate_eligible, live_since)
                VALUES ($1,$2,$3,$4,$5,'legacy rating','held',FALSE,NULL) RETURNING id
                """)
            .execute(Tuple.of(managerId, author, rating, company, title))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    /** The statement both backends write today, column for column. */
    private static long insertCareerRow(long managerId, String company, String title,
                                        String startIso, Long companyId) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO career_history (manager_id, company, title, start_date, end_date,
                                            company_id)
                VALUES ($1,$2,$3,$4,NULL,$5) RETURNING id
                """)
            .execute(Tuple.of(managerId, company, title,
                              java.time.OffsetDateTime.parse(startIso), companyId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }


    /** A role written the way the phase-4 code will write one: with its period declared known. */
    private static long insertKnownPeriodRole(long managerId, String title, String startIso)
            throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO career_history (manager_id, company, title, start_date, company_id,
                                            period_known, state, source)
                VALUES ($1,'Acme Logistics',$2,$3,$4,TRUE,'accepted','contributor') RETURNING id
                """)
            .execute(Tuple.of(managerId, title, java.time.OffsetDateTime.parse(startIso), acmeId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static long insertCapturedDraft() throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO captured_drafts (kind, payload)
                VALUES ('manager', '{"name":"Half typed"}'::jsonb) RETURNING id
                """).execute().map(rs -> rs.iterator().next().getLong("id")));
    }

    private static long insertClaim(long managerId, long companyId, String title) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO role_claims (proposal_key, manager_id, company_id, title, source,
                                         finalized_at, original_manager_id)
                VALUES ($1,$2,$3,$4,'contributor',now(),$2) RETURNING id
                """)
            .execute(Tuple.of(UUID.randomUUID(), managerId, companyId, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static void resolveClaimTo(long claimId, long roleId) throws Exception {
        await(pool.preparedQuery("""
                UPDATE role_claims
                   SET resolved_role_id = $2, resolved_at = now(), resolution = 'linked'
                 WHERE id = $1
                """).execute(Tuple.of(claimId, roleId)).mapEmpty());
    }

    private static UUID insertClaimedReview(long managerId, long claimId) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                     manager_title, claim_id, role_basis, role_required,
                                     attribution_basis)
                VALUES ($1,$2,4.0,'Lumen Health','Team Lead',$3,'claimed',TRUE,'role') RETURNING id
                """)
            .execute(Tuple.of(managerId, "anon-" + UUID.randomUUID(), claimId))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static String normalise(String title) throws Exception {
        return await(pool.preparedQuery("SELECT normalise_title($1) AS k")
            .execute(Tuple.of(title)).map(rs -> rs.iterator().next().getString("k")));
    }

    private static Row reviewRow(UUID id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM reviews WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private static Row careerRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM career_history WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private static double ratingOf(UUID id) throws Exception {
        return reviewRow(id).getNumeric("overall_rating").doubleValue();
    }

    private static String dispositionOf(UUID id) throws Exception {
        return reviewRow(id).getString("disposition");
    }

    private static int countReviews() throws Exception {
        return await(pool.query("SELECT COUNT(*) AS c FROM reviews WHERE text = 'legacy rating'")
            .execute().map(rs -> rs.iterator().next().getInteger("c")));
    }

    private static boolean tableExists(String table) throws Exception {
        return await(pool.preparedQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_name = $1")
            .execute(Tuple.of(table)).map(rs -> rs.iterator().hasNext()));
    }

    private static boolean columnExists(String table, String column) throws Exception {
        return await(pool.preparedQuery("""
                SELECT 1 FROM information_schema.columns
                 WHERE table_name = $1 AND column_name = $2
                """).execute(Tuple.of(table, column)).map(rs -> rs.iterator().hasNext()));
    }

    private static boolean indexExists(String name) throws Exception {
        return await(pool.preparedQuery(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = $1")
            .execute(Tuple.of(name)).map(rs -> rs.iterator().hasNext()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /** Boolean, not boolean, is ambiguous against assertTrue(BooleanSupplier, String). */
    private static boolean awaitBool(Future<Boolean> future) throws Exception {
        return Boolean.TRUE.equals(await(future));
    }
}

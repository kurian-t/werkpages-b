package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 on a BRAND NEW database, through the complete Flyway chain from V1 to the head.
 *
 * <p>The companion to {@link Phase2UpgradeIntegrationTest}, which walks the production path over
 * existing rows. This one answers the other half: that a fresh install produces the same schema,
 * that Flyway considers the chain valid, and that every object rev 268 specifies is actually there
 * with the PROPERTIES that make it do its job.
 *
 * <p>The properties are the point. A deferrable constraint declared immediate, a unique index that
 * forgot its WHERE clause, or a constraint trigger created as an ordinary one all create
 * successfully and all break something later, in ways no test of ordinary behaviour would notice
 * until a merge or a withdrawal needed them. So they are read back out of the catalogue.
 */
/* ROUTINE CI: tagged "critical" - security, erasure or data integrity. See the poms. */
@Tag("critical")
@Testcontainers
class Phase2FreshInstallIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_phase2_fresh")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;

    @BeforeAll
    static void migrateFromNothing() {
        Flyway flyway = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load();
        flyway.migrate();
        // Checksums, ordering and the absence of gaps, asserted rather than assumed. One shared
        // flyway_schema_history serves both backends, and a checksum conflict here is the failure
        // the frozen RMM migration folder exists to prevent arriving by another route.
        flyway.validate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(2));
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void theChainReachesTheHeadAndEveryMigrationSucceeded() throws Exception {
        // The head moves when a migration is authorised, and this pin moves with it: 103 through
        // phases 2 and 3, then V104 (rating_opportunities), V105 (identity erasure), V106 (one
        // active seed per manager), V107 (the erased-author dedup exemption) and V108
        // (role_corrections, plus the claim-assertion immutability the design had never
        // enforced), V109 (an audit record names its actor when it is born) and V110
        // (contribution_drafts: a signed-in contributor's private unfinished rating, which
        // moderation never sees) and V111 (claim_testimony, role_claims.withdrawn_at, the
        // replaced_by_author deletion reason and the legacy dedup index narrowed so a
        // permanently dead rating leaves it), and V112 (the employment-status model: the
        // resolved status on career_history, its append-only event log, the
        // employment_status_dispute conflict kind and the three rules as functions).
        // Deliberately a literal and not "the highest file on disk", because the point
        // of the assertion is to notice an unexpected migration appearing in the shared stream,
        // which deriving it from the directory would hide.
        //
        // 112 until the consolidation, which folded V97 to V112 into one file. The head is now
        // V101, employment-attributed company statistics.
        //
        // V98 (oauth_login_transactions), V99 (username identity) and V100 (app_sessions) all sit
        // BELOW
        // this head, so the literal did not move. An earlier version of this comment predicted
        // it would become 100, which was wrong: the head is the greatest version, not the newest
        // file. V102 (legacy employment attribution) moved it to 102 on 2026-11-11,
        // which is this assertion doing exactly its job: a new migration in the shared
        // stream has to be noticed and acknowledged, not absorbed silently.
        //
        // V103 (account_recovery_requests, the per-account cooldown for username recovery) moved
        // it to 103 on 2026-11-11. Acknowledged: that migration was written deliberately, for the
        // option C recovery flow, and the cooldown it carries is what stops one person
        // mail-bombing another through an endpoint that must answer identically either way.
        //
        // V104 (rating_attribution_corrections) moved it to 104 on 2026-11-11. Acknowledged: V102
        // INFERS an employer for legacy ratings, and an inference can later be shown wrong by
        // evidence the backfill did not have - so there has to be a way to correct one that leaves
        // a record. Silently rewriting attribution is how a statistic stops being explainable.
        //
        // V105 (S4 post-cutover enforcement) moved it to 105 on 2026-11-11. Acknowledged, and the
        // acknowledgement matters more here than usual: V105 is the "point of no return" migration
        // and it is deliberately INERT. s4_enforced() is false, the trigger returns immediately,
        // and the frozen exemption set is empty, so applying it changes no behaviour at all.
        // Activation is a separate, authorized decision - see PHASE6_S4_RUNTIME.md.
        //
        // V106 (unlink rating, and platform-generated examples) moved it to 106 on 2026-11-13.
        // Acknowledged: it adds reviews.origin, reviews.unlinked_at and rating_participation.
        // `origin` is the one with reach, because it is what finally tells a contributor's rating
        // apart from a platform example without overloading the `user_id IS NULL AND weight` pair
        // that already meant two other things. Every genuine calculation keys on it now.
        //
        // V107 (retire stranded examples, relabel erased bylines) moved it to 107 on 2026-11-13.
        // Acknowledged, and unlike every entry above it this one is a DATA correction rather than
        // a structural change: it deletes examples left sitting beside a genuine rating, and it
        // replaces the 'Anonymous User' byline that account erasure used to write. That literal
        // was worn by no live account, so it disclosed which ratings belonged to somebody who had
        // deleted themselves, which is the one fact the erasure exists to remove. It is scoped by
        // the condition it removes, so re-running it changes nothing.
        //
        // V108 (classify placeholder ratings in the database) moved it to 108 on 2026-11-16.
        // Acknowledged, and this is the second DATA correction in a row rather than a structural
        // change. Production was found holding three weighted, authorless rows still classified
        // origin = 'contributor', which is the shape V106's backfill was written to catch: they
        // were therefore written AFTER it ran, by a deployment whose createSeedReview predated the
        // column. V108 backs those rows up, reclassifies them, recomputes every manager cache and
        // company projection they touched, and then installs a BEFORE INSERT trigger so that a
        // stale deployment writing one again has it classified rather than refused. Refusing would
        // have broken ghost creation on the older build, which is the one thing worse than the
        // defect.
        //
        // V109 (per-employment location, and a proposed change set) moved it to 109 on 2026-11-17.
        // Acknowledged, and this one IS structural: it gives career_history its own eight declared
        // location columns so an employment can say where it was held without moving the manager's
        // present-day location, adds manager_edits.new_name and .explanation, adds the
        // manager_edit_employments change set, and widens role_corrections.field by 'location'.
        // Every column is nullable, the one new table is new, and both CHECK changes are
        // widenings, so RateMyManagers keeps working unchanged against it. NOTHING is backfilled:
        // an employment nobody has located stays unlocated rather than inheriting a location
        // nobody stated.
        //
        // V110 (a contributor's status is binary) moved it to 110 on 2026-11-17.
        // Acknowledged, and it is the one shape that can fail on existing rows: a NARROWING
        // CHECK, on manager_edit_employments.new_employment_status. Safe because that column is
        // new in V109, V109 is not deployed anywhere, and the migration proves no offending row
        // exists rather than assuming it. career_history.employment_status is deliberately
        // untouched and still admits unknown and disputed.
        //
        // It exists as its own version rather than as an edit to V109 because V109 had already
        // been applied to a development database, and changing it produced exactly the checksum
        // mismatch that rule prevents.
        //
        // V111 (the career editor asks no explanation) moved it to 111 on 2026-11-18.
        // Acknowledged: it DROPS manager_edits_career_editor_explains, the CHECK V109 added to
        // make an explanation mandatory on a career-editor row. The "How do you know?" question
        // was removed from the form on request, so the constraint would have refused every row
        // the form now produces. Dropping a CHECK cannot invalidate stored data - every row that
        // satisfied the stricter rule satisfies its absence - and the column, the legacy form's
        // explanation and every explanation already recorded are untouched.
        //
        // Its own version for the third time running, and for the same reason as V110: V109 is
        // already applied to development databases.
        //
        // V112 (a correction needs no reason) moved it to 112 on 2026-11-19.
        // Acknowledged, and it is the other half of V111. V111 stopped the form collecting an
        // explanation; role_corrections.reason was still NOT NULL, and that column is written one
        // row per corrected field at approval. So approving an ADDITION kept working while
        // approving a CORRECTION of a title, location or dates raised 23502 and applied nothing.
        // Found by the test that approves all three in one submission. This DROPS the NOT NULL
        // and restates the non-blank CHECK as `reason IS NULL OR btrim(reason) <> ''`, which is
        // the same rule a NULL always satisfied, now said out loud.
        //
        // Relaxing, not narrowing: no stored row can be invalidated by dropping NOT NULL, nothing
        // is backfilled, and an RMM build from before today still sends a reason and is
        // unaffected. The moderator's own correction tool still requires one.
        //
        // V113 (company averages count every rating) moved it to 113 on 2026-11-22.
        // Acknowledged, and it is the only one of these that writes to existing rows. Two
        // corrections, both to figures that were wrong on screen:
        //
        //   - a BEFORE INSERT trigger attributes a legacy rating to an employer as it is written.
        //     attribution_basis defaulted to 'none' and only V102's one-off backfill ever set
        //     anything, so every rating submitted since counted toward no company and the
        //     company profile's "Manager opinions" average was computed from whatever subset
        //     V102 had reached;
        //   - company_stats_expected.avg_rating was AVG(m.overall_rating), the mean of each
        //     manager's mean, and is now weighted by reviews_count. Twelve ratings at one
        //     company - one 1.0, one 5.0 and ten 5.0 - reported 3.7 against a true 4.67.
        //
        // It re-runs V102's two rules for the ratings that arrived after it, and rebuilds both
        // projections so the stored figures are corrected rather than only future ones. No column
        // is added, dropped or narrowed, and no constraint becomes stricter, so an RMM build from
        // before today writes ratings exactly as it did and simply gets them attributed.
        //
        // V114 (a report can be read, and decided) moved it to 114 on 2026-11-23.
        // Acknowledged. Found while verifying that the Report button works as intended: it does
        // not. The button validated its reason, refused a duplicate and wrote to `reports`, and
        // the only SELECT against that table in either backend was the duplicate check, so no
        // admin had ever seen a report while the profile page promised each reporter a review.
        // It is the correction route the Terms and the Privacy Policy point a rated person at.
        //
        // This adds the state that lets a queue drain: status with a default of 'open',
        // resolved_at, resolved_by and resolution_note, two CHECKs keeping the state coherent
        // with the decision, a partial index for the open queue and the index the manager_id FK
        // never had. Purely additive, and the one NOT NULL column carries a DEFAULT, which is the
        // single form of NOT NULL an existing writer survives: RMM's INSERT names four columns
        // and omits status, so a report filed by a pre-V114 build arrives 'open' and shows up in
        // the queue. No row is updated, moved or deleted.
        //
        // No unique index on (manager_id, user_id), deliberately: production already holds
        // reports, a unique index would fail to build if any duplicate pair exists, and clearing
        // one first means deleting user-submitted rows. Raised separately rather than decided
        // inside a migration.
        assertEquals("114", await(pool.query("""
                SELECT version FROM flyway_schema_history
                 WHERE success AND version IS NOT NULL
                 ORDER BY installed_rank DESC LIMIT 1
                """).execute().map(rs -> rs.iterator().next().getString("version"))));
        assertEquals(0, await(pool.query(
                "SELECT COUNT(*) AS c FROM flyway_schema_history WHERE NOT success").execute()
            .map(rs -> rs.iterator().next().getInteger("c"))).intValue());
    }

    @Test
    void everyPhase2TableExists() throws Exception {
        for (String t : List.of("role_claims", "claim_corrections", "claim_resolution_events",
                                "manager_merge_events", "role_conflicts", "role_conflict_claims",
                                "role_conflict_reviews", "review_supersessions",
                                "conflict_sweep_runs", "employment_status_events")) {
            assertTrue(exists("SELECT 1 FROM information_schema.tables WHERE table_name = $1", t),
                "missing table: " + t);
        }
    }

    @Test
    void everyPhase2FunctionExists() throws Exception {
        for (String f : List.of("normalise_title", "kind_blocks_publication",
                                "rating_role_is_publishable", "rating_is_publishable",
                                "rating_publication_problem", "record_claim_resolution",
                                "refuse_history_change", "assert_this_rating",
                                "assert_claims_ratings", "assert_roles_ratings",
                                "assert_conflicts_ratings", "assert_blocking_has_a_subject",
                                "refuse_sweep_resolution", "refuse_sweep_blocking",
                                "conflict_sweep_cursor", "conflict_sweep_age",
                                // V112: the three employment-status rules, each with ONE
                                // definition, plus the two triggers that keep the log honest.
                                "employment_status_contradicts", "employment_status_presented",
                                "employment_label", "log_employment_status_change",
                                "refuse_status_event_change_except_erasure")) {
            assertTrue(exists("SELECT 1 FROM pg_proc WHERE proname = $1", f), "missing function: " + f);
        }
    }

    @Test
    void theGeneratedColumnsAreStoredAndImmutable() throws Exception {
        // A generated column will not accept a STABLE function, so normalise_title has to be
        // IMMUTABLE and the day columns have to spell out AT TIME ZONE 'UTC' rather than relying on
        // a bare ::date, which is only STABLE. If either were wrong the migration would not have
        // created at all; this pins that it is STORED rather than VIRTUAL, which is what makes the
        // value stable for the lifetime of the row.
        for (String[] c : new String[][]{{"career_history","title_key"},{"career_history","start_day"},
                                         {"career_history","end_day"},{"career_history","rateable"},
                                         {"role_claims","title_key"},{"role_conflicts","case_key"},
                                         {"role_conflicts","blocks_publication"}}) {
            assertEquals("ALWAYS", await(pool.preparedQuery("""
                    SELECT is_generated FROM information_schema.columns
                     WHERE table_name = $1 AND column_name = $2
                    """).execute(Tuple.of(c[0], c[1]))
                .map(rs -> rs.iterator().next().getString("is_generated"))),
                c[0] + "." + c[1] + " must be a generated column");
        }
        assertTrue(awaitBool(pool.query("""
                SELECT provolatile = 'i' AS immutable FROM pg_proc WHERE proname = 'normalise_title'
                """).execute().map(rs -> rs.iterator().next().getBoolean("immutable"))),
            "normalise_title feeds a stored generated column and must be IMMUTABLE");
    }

    @Test
    void theDeferredConstraintsAreActuallyDeferrable() throws Exception {
        // reviews_one_per_claim must be DEFERRABLE INITIALLY DEFERRED, because the author-reclaim
        // transaction inserts the signed rating before deleting the anonymous one and both
        // reference the adopted claim in between. Immediate enforcement would force
        // delete-before-insert, which is the hazard that path was built to avoid.
        assertEquals("deferred", deferral("reviews_one_per_claim"));

        // The two composite keys are DEFERRABLE but INITIALLY IMMEDIATE: ordinary writes get
        // immediate feedback, and only a merge, which moves manager_id across several statements,
        // sets them deferred for the length of its transaction.
        assertEquals("deferrable_immediate", deferral("reviews_claim_is_the_managers"));
        assertEquals("deferrable_immediate", deferral("reviews_withdrawn_role_is_the_managers"));
        assertEquals("deferrable_immediate", deferral("claim_resolved_role_matches"));

        // claim_selected_role_exists is deliberately NOT deferrable and references career_history
        // by id alone: a selection is a historical fact about a row that existed at a moment in
        // time, and after a merge the claim's manager is the survivor while the selected role may
        // still carry the absorbed profile.
        assertEquals("not_deferrable", deferral("claim_selected_role_exists"));
    }

    @Test
    void thePublicationTriggersAreConstraintTriggersAndDeferred() throws Exception {
        // An ordinary AFTER trigger fires per statement and cannot be deferred to commit, so a
        // transaction that legitimately passes through an invalid intermediate state - every merge,
        // and every admin acceptance that releases held ratings - would fail half way through.
        for (String t : List.of("reviews_live_is_resolved", "claims_live_is_resolved",
                                "roles_live_is_resolved", "conflicts_live_is_resolved",
                                "conflict_claims_live_is_resolved",
                                "conflicts_blocking_has_a_subject",
                                "conflict_claims_blocking_has_a_subject")) {
            assertTrue(awaitBool(pool.preparedQuery("""
                    SELECT t.tgdeferrable AND t.tginitdeferred AS ok
                      FROM pg_trigger t WHERE t.tgname = $1 AND NOT t.tgisinternal
                    """).execute(Tuple.of(t))
                .map(rs -> rs.iterator().hasNext() && rs.iterator().next().getBoolean("ok"))),
                t + " must be a deferrable, initially deferred constraint trigger");
        }
        // The sweep guards are the opposite: BEFORE row triggers that must refuse the write as it
        // happens, not at commit.
        for (String t : List.of("conflicts_sweep_cannot_resolve", "conflicts_sweep_is_enrichment_only",
                                "claim_resolution_events_append_only",
                                "review_supersessions_append_only")) {
            assertFalse(awaitBool(pool.preparedQuery(
                    "SELECT tgdeferrable AS d FROM pg_trigger WHERE tgname = $1 AND NOT tgisinternal")
                .execute(Tuple.of(t)).map(rs -> rs.iterator().next().getBoolean("d"))),
                t + " must fire immediately");
        }
    }

    @Test
    void thePartialIndexesKeepTheirPredicates() throws Exception {
        // Each of these is partial for a stated reason, and a missing WHERE clause would not fail
        // to create - it would quietly enforce something else.
        assertIndexPredicate("career_unique_known_employment", "period_known",
            "unknown periods are outside it: an unknown date is not evidence of sameness");
        assertIndexPredicate("career_unique_known_employment", "proposed",
            "and so are rejected and superseded rows, so a rejection cannot occupy the key and "
            + "silently block a fresh proposal for the same job");
        assertIndexPredicate("role_claims_one_per_contributor_per_role", "user_id IS NOT NULL",
            "anonymous claims sit outside it deliberately: there is no identity to key on");
        assertIndexPredicate("role_conflicts_one_open", "resolved_at IS NULL",
            "one OPEN task per dispute per kind, so a genuine recurrence opens a new one");
        assertIndexPredicate("idx_reviews_user_manager_role_legacy", "role_basis = 'legacy'",
            "NOT role_required = FALSE, which is also true of a withdrawn rating");
        assertIndexPredicate("idx_reviews_author_manager_role_legacy", "legacy_dedup_state",
            "marking a merge loser excludes it from the index while keeping the row");
        assertIndexPredicate("managers_capture_token", "capture_token IS NOT NULL",
            "every row that predates phase 1 carries a null token");
    }

    @Test
    void thePredicateIsTotalForAnIdThatMatchesNothing() throws Exception {
        // It has to be TRUE or FALSE for any input, never NULL. An early version was not, and the
        // hole was in exactly the case the predicate exists to catch: FALSE OR NULL is NULL, and
        // `IF NOT rating_is_publishable(...)` skips its RAISE because NOT NULL is not TRUE, so a
        // live rating with an unresolved claim passed every publication check in the database.
        assertTrue(awaitBool(pool.query("""
                SELECT rating_is_publishable('00000000-0000-0000-0000-000000000000'::uuid) AS p
                """).execute().map(rs -> rs.iterator().next().getBoolean("p"))),
            "no such review: nothing to enforce, and never NULL");
        assertTrue(awaitBool(pool.query("""
                SELECT rating_role_is_publishable('00000000-0000-0000-0000-000000000000'::uuid) AS p
                """).execute().map(rs -> rs.iterator().next().getBoolean("p"))));
        assertNull(await(pool.query("""
                SELECT rating_publication_problem('00000000-0000-0000-0000-000000000000'::uuid) AS p
                """).execute().map(rs -> rs.iterator().next().getString("p"))),
            "and the explanation is null when there is nothing to explain");
    }

    @Test
    void theBlockingKindListHasOneDefinition() throws Exception {
        // The generated column and the sweep guard must not drift into two lists. They read the
        // same function, which is why the guard tests NEW.kind rather than NEW.blocks_publication:
        // a stored generated column is computed AFTER before-triggers run, so its value is not
        // available to one.
        for (String k : List.of("ambiguous_resolution", "possible_duplicate",
                                "duplicate_rating_on_merge", "cross_era_possible_duplicate")) {
            assertTrue(awaitBool(pool.preparedQuery("SELECT kind_blocks_publication($1) AS b")
                .execute(Tuple.of(k)).map(rs -> rs.iterator().next().getBoolean("b"))), k);
        }
        for (String k : List.of("end_date_disagreement", "unknown_period_duplicate",
                                "overlapping_period", "correction_candidate",
                                "late_edit_to_accepted", "previously_rejected",
                                // V112. A dispute about WHEN somebody worked somewhere says
                                // nothing about whether a rating may be published, so adding this
                                // kind to the blocking list would withhold ratings over an
                                // unrelated question.
                                "employment_status_dispute")) {
            assertFalse(awaitBool(pool.preparedQuery("SELECT kind_blocks_publication($1) AS b")
                .execute(Tuple.of(k)).map(rs -> rs.iterator().next().getBoolean("b"))), k);
        }
    }

    @Test
    void theSweepAgeIsNullWhenNoSweepHasEverRun() throws Exception {
        // The company-stats version of this coalesced to -infinity, raised on the subtraction, and
        // the caller's fallback reported a database where maintenance had NEVER run as "0h ago".
        assertNull(await(pool.query("SELECT conflict_sweep_age() AS a").execute()
            .map(rs -> rs.iterator().next().getValue("a"))),
            "null means never, and the caller MUST treat that as stale rather than as fresh");
        assertFalse(awaitBool(pool.query("SELECT * FROM conflict_sweep_cursor()").execute()
            .map(rs -> rs.iterator().hasNext())),
            "and there is no cursor to resume from");
    }

    @Test
    void phase2WroteNoRowsOfItsOwn() throws Exception {
        // Inertness, stated as data. Nothing is backfilled into the new tables, because nothing
        // about an existing rating can be inferred into a claim without guessing which employment
        // it concerned.
        for (String t : List.of("role_claims", "claim_corrections", "claim_resolution_events",
                                "manager_merge_events", "role_conflicts", "role_conflict_claims",
                                "role_conflict_reviews", "review_supersessions",
                                // V112's backfill only ever touches EXISTING employment, and a
                                // fresh install has none, so the log starts empty here too.
                                "conflict_sweep_runs", "employment_status_events")) {
            assertEquals(0, await(pool.query("SELECT COUNT(*) AS c FROM " + t).execute()
                .map(rs -> rs.iterator().next().getInteger("c"))).intValue(),
                t + " must start empty");
        }
    }

    // ------------------------------------------------------------------ fixture plumbing

    /** 'deferred', 'deferrable_immediate' or 'not_deferrable', read from the catalogue. */
    private static String deferral(String constraint) throws Exception {
        return await(pool.preparedQuery("""
                SELECT CASE WHEN NOT condeferrable THEN 'not_deferrable'
                            WHEN condeferred      THEN 'deferred'
                            ELSE 'deferrable_immediate' END AS d
                  FROM pg_constraint WHERE conname = $1
                """).execute(Tuple.of(constraint))
            .map(rs -> rs.iterator().hasNext() ? rs.iterator().next().getString("d") : "missing"));
    }

    private static void assertIndexPredicate(String index, String fragment, String why)
            throws Exception {
        String def = await(pool.preparedQuery(
                "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND indexname = $1")
            .execute(Tuple.of(index))
            .map(rs -> rs.iterator().hasNext() ? rs.iterator().next().getString("indexdef") : null));
        assertNotNull(def, "missing index: " + index);
        assertTrue(def.contains(fragment),
            index + " must still be keyed on " + fragment + ", because " + why + ". Got: " + def);
    }

    private static boolean exists(String sql, String arg) throws Exception {
        return await(pool.preparedQuery(sql).execute(Tuple.of(arg))
            .map(rs -> rs.iterator().hasNext()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /** Boolean, not boolean, is ambiguous against assertTrue(BooleanSupplier, String). */
    private static boolean awaitBool(Future<Boolean> future) throws Exception {
        return Boolean.TRUE.equals(await(future));
    }
}

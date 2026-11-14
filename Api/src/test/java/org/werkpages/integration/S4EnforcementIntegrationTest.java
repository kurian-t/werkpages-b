package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S4 POST-CUTOVER ENFORCEMENT, IN BOTH STATES.
 *
 * <p>Rev 268 calls S4 "the point of no return": the moment a rating may no longer be submitted
 * without an employment to attach it to. V105 builds all of it and enforces none of it - the flag
 * defaults false, so applying the migration changes no behaviour at all.
 *
 * <p>Testing the DISABLED state matters as much as the enabled one, because "we shipped the
 * enforcement and it was inert" and "we shipped the enforcement and it refused every submission"
 * are both deployments, and only one of them is the intended one.
 *
 * <h2>The escape hatch is the part that strands real work</h2>
 *
 * <p>A draft captured before the cutover has no claim. Published afterwards it must still publish,
 * and the design went through three attempts to express that - the first tested the rating's own
 * {@code created_at}, which is wrong because such a draft gets {@code now()} when it publishes.
 * The settled answer is declared provenance verified against a frozen set.
 *
 * <p><b>That hatch was broken when this suite was first written</b>, and the enforcement looked
 * perfectly correct: {@code pre_cutover_drafts.draft_token} was declared TEXT while
 * {@code reviews.draft_token} is UUID, so a genuine pre-cutover draft raised 22P02 before the
 * exemption was ever consulted. Enforcement working and the exemption failing is the worst
 * combination available - it refuses the contributions it was written to protect - and only running
 * the enabled state found it.
 */
/* ROUTINE CI: tagged "critical" - security, erasure or data integrity. See the poms. */
@Tag("critical")
@Testcontainers
class S4EnforcementIntegrationTest {

    @org.testcontainers.junit.jupiter.Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("s4_enforcement").withUsername("test").withPassword("test");

    static Pool pool;
    static Vertx vertx;
    static long managerId;

    @BeforeAll
    static void setUpAll() throws Exception {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations").load().migrate();

        vertx = Vertx.vertx();
        pool = PgPool.pool(vertx, new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));

        long companyId = await(pool.preparedQuery(
                "INSERT INTO companies (name, slug, status) VALUES ($1,$2,'approved') RETURNING id")
            .execute(Tuple.of("S4 Co", "s4-co"))).iterator().next().getLong("id");
        managerId = await(pool.preparedQuery("""
                INSERT INTO managers (name, company, company_id, title, status, approval_status, slug)
                VALUES ($1, 'S4 Co', $2, 'Lead', 'active', 'approved', $3) RETURNING id
                """).execute(Tuple.of("S4 Boss", companyId, "s4-boss")))
            .iterator().next().getLong("id");
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool  != null) pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /**
     * Every test starts in the state the migration leaves: enforcement off, frozen set empty.
     *
     * <p>Clearing the set as well as the flag is necessary and is legitimate. Necessary because
     * these tests share one database, and a test that seeds a pre-cutover token left
     * {@code theMigrationLeavesEnforcementOff} asserting against another test's row - it failed
     * with {@code expected: <0> but was: <1>}, which is a test-isolation bug wearing the costume
     * of a migration bug.
     *
     * <p>Legitimate because the freeze only applies once enforcement is live: deleting from the
     * set while the flag is off is exactly what {@code refuse_pre_cutover_drafts_growth} permits,
     * so this does not reach around the guarantee it is testing.
     */
    @BeforeEach
    void resetToPostMigrationState() throws Exception {
        await(pool.query("SELECT deactivate_s4_enforcement()").execute());
        await(pool.query("DELETE FROM pre_cutover_drafts").execute());
    }

    // ── the disabled state, which is what a deployment of V105 produces ─────

    /**
     * APPLYING V105 CHANGES NOTHING. The assertion that makes it safe to deploy.
     */
    @Test
    void theMigrationLeavesEnforcementOff() throws Exception {
        assertFalse(enforced(), "V105 must be inert on arrival; activation is a product decision");
        assertEquals(0L, countExemptDrafts(),
            "the frozen set is filled at ACTIVATION, not by the migration");
    }

    @Test
    void whileDisabledALegacyClaimlessRatingIsAccepted() throws Exception {
        assertNull(insertLegacyRating("inert-rater", null, null),
            "the legacy write path must be untouched while the flag is off");
    }

    // ── the enabled state ───────────────────────────────────────────────────

    /**
     * The refusal carries a RECOGNISABLE error, not a constraint violation.
     *
     * <p>The design is explicit that an old client must get "a 410 with an explanatory body, not a
     * 500 from a CHECK". SQLSTATE {@code S4010} is what lets the API layer tell the difference
     * without matching on a message string, which is why it is asserted rather than just the
     * failure.
     */
    @Test
    void onceEnabledALegacyClaimlessRatingIsRefusedWithS4010() throws Exception {
        activate();
        String error = insertLegacyRating("refused-rater", null, null);
        assertNotNull(error, "a claimless rating must be refused once S4 is in force");
        assertTrue(error.contains("S4010") || error.contains("retired path"),
            "the refusal must be identifiable so the API can answer 410 rather than 500: " + error);
    }

    /**
     * THE ESCAPE HATCH. A draft captured before the cutover still publishes.
     *
     * <p>This is the test that found the UUID/TEXT mismatch described on the class. Without it the
     * suite would have been green while every pre-cutover contribution was unpublishable.
     */
    @Test
    void aGenuinePreCutoverDraftStillPublishes() throws Exception {
        UUID token = UUID.randomUUID();
        await(pool.preparedQuery("INSERT INTO pre_cutover_drafts (draft_token) VALUES ($1)")
            .execute(Tuple.of(token)));
        activate();

        assertNull(insertLegacyRating("exempt-rater", "pre_cutover_draft", token),
            "a draft captured before the cutover was valid when it arrived and must still publish");
    }

    /**
     * A declared exemption is VERIFIED, not believed.
     *
     * <p>The whole point of declared provenance: an application says why a rating may omit a role,
     * and the database checks the claim. A token that is not in the frozen set is a forged
     * exemption.
     */
    @Test
    void anExemptionForATokenNotInTheFrozenSetIsRefused() throws Exception {
        activate();
        String error = insertLegacyRating("forger", "pre_cutover_draft", UUID.randomUUID());
        assertNotNull(error, "an unverifiable exemption must be refused");
        assertTrue(error.contains("pre_cutover_drafts"), error);
    }

    @Test
    void anExemptionWithoutATokenToProveItIsRefused() throws Exception {
        activate();
        String error = insertLegacyRating("tokenless", "pre_cutover_draft", null);
        assertNotNull(error, "an exemption with nothing to verify is not an exemption");
    }

    /**
     * THE FROZEN SET CANNOT GROW once enforcement is live.
     *
     * <p>Without this the exemption is unbounded: anybody able to add a token could publish
     * claimless ratings for ever, and S4 would be decorative.
     */
    @Test
    void theFrozenSetRefusesGrowthOnceEnabled() throws Exception {
        activate();
        String error = null;
        try {
            await(pool.preparedQuery("INSERT INTO pre_cutover_drafts (draft_token) VALUES ($1)")
                .execute(Tuple.of(UUID.randomUUID())));
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
        }
        assertNotNull(error, "the set must be frozen at activation");
        assertTrue(error.contains("frozen"), error);
    }

    // ── what enforcement must NOT break ─────────────────────────────────────

    /**
     * EXISTING LEGACY RATINGS STAY MAINTAINABLE. History is not retrospectively illegal.
     *
     * <p>The trigger fires on INSERT only, deliberately. An edit, a withdrawal, a merge moving a
     * rating between managers, an attribution correction - all of these UPDATE legacy rows, and a
     * trigger that refused them would make every pre-cutover rating unmaintainable the moment the
     * flag flipped. That would be a far worse outage than the one S4 prevents.
     */
    @Test
    void existingLegacyRatingsCanStillBeEditedUnderEnforcement() throws Exception {
        assertNull(insertLegacyRating("historic", null, null), "seeded before enforcement");
        activate();

        int updated = await(pool.preparedQuery(
                "UPDATE reviews SET overall_working_experience = 5 WHERE author = 'historic'")
            .execute()).rowCount();
        assertEquals(1, updated,
            "an existing legacy rating must stay editable; S4 governs creation, not history");
    }

    /** And it must still be possible to soft-delete, withdraw and correct one. */
    @Test
    void legacyRatingsCanStillBeSoftDeletedAndAttributionCorrectedUnderEnforcement() throws Exception {
        assertNull(insertLegacyRating("correctable", null, null), "seeded before enforcement");
        UUID id = await(pool.preparedQuery("SELECT id FROM reviews WHERE author = 'correctable'")
            .execute()).iterator().next().getUUID("id");
        long otherCompany = await(pool.preparedQuery(
                "INSERT INTO companies (name, slug, status) VALUES ($1,$2,'approved') RETURNING id")
            .execute(Tuple.of("S4 Other " + id, "s4-other-" + id))).iterator().next().getLong("id");
        activate();

        // V104's correction path, under enforcement.
        await(pool.preparedQuery(
                "SELECT correct_rating_attribution($1, 'inferred', $2, 'under S4', NULL, TRUE)")
            .execute(Tuple.of(id, otherCompany)));
        await(pool.preparedQuery("UPDATE reviews SET deleted_at = now() WHERE id = $1")
            .execute(Tuple.of(id)));

        assertEquals("inferred", await(pool.preparedQuery(
                "SELECT attribution_basis FROM reviews WHERE id = $1").execute(Tuple.of(id)))
            .iterator().next().getString("attribution_basis"),
            "V104 corrections must keep working under S4, or an inference found wrong after the "
            + "cutover could never be put right");
    }

    @Test
    void deactivationRestoresTheLegacyPath() throws Exception {
        activate();
        assertNotNull(insertLegacyRating("blocked", null, null), "refused while on");
        await(pool.query("SELECT deactivate_s4_enforcement()").execute());
        assertNull(insertLegacyRating("allowed-again", null, null),
            "the switch must be reversible: a switch that cannot be thrown back is an outage with "
            + "no remedy");
    }

    @Test
    void activatingTwiceIsRefusedRatherThanSilentlyRepopulating() throws Exception {
        activate();
        String error = null;
        try {
            await(pool.query("SELECT drafts_exempted FROM activate_s4_enforcement(NULL)").execute());
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
        }
        assertNotNull(error, "a second activation must refuse, not re-fill the frozen set");
        assertTrue(error.contains("already active"), error);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** @return null when the insert succeeded, otherwise the failure message. */
    private String insertLegacyRating(String author, String exemption, UUID draftToken) {
        try {
            await(pool.preparedQuery("""
                    INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                         manager_title, overall_working_experience,
                                         role_exemption, draft_token)
                    VALUES ($1, $2, 4, 'S4 Co', 'Lead', 4, $3, $4)
                    """).execute(Tuple.of(managerId, author, exemption, draftToken)));
            return null;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    private void activate() throws Exception {
        await(pool.query("SELECT drafts_exempted FROM activate_s4_enforcement(NULL)").execute());
        assertTrue(enforced(), "activation must actually take effect");
    }

    private boolean enforced() throws Exception {
        return await(pool.query("SELECT s4_enforced() AS on").execute())
            .iterator().next().getBoolean("on");
    }

    private long countExemptDrafts() throws Exception {
        return await(pool.query("SELECT count(*) AS c FROM pre_cutover_drafts").execute())
            .iterator().next().getLong("c");
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}

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
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.CompanyRepository.BrandAuthority;
import org.werkpages.repository.PickedBrand;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which identity wins when somebody picks a company from the dropdown.
 *
 * <h2>The bug this set exists for</h2>
 *
 * <p>The logo shown in the picker did not match the logo on the manager's profile, and no amount
 * of re-picking changed it. {@code findOrCreate} wrote {@code logo_url} on INSERT only, and
 * {@code resolve} with a known id merely SELECTed, so for any company that already existed - which
 * is nearly all of them - the pick had nowhere to land.
 *
 * <h2>Why the fix is a ladder rather than an overwrite</h2>
 *
 * <p>A pick has to be able to correct a GUESSED identity, because guessing is where the wrong
 * logos come from: "Lime" guesses lime.com, a real and unrelated company. It must NOT be able to
 * flatten something better - a resolved domain, a consensus of resolvers, or an admin's decision.
 * V85 already grades exactly that, so the rule is read from the data instead of invented.
 */
@Testcontainers
class CompanyBrandPrecedenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test").withUsername("test").withPassword("test");

    static Pool pool;
    static CompanyRepository companyRepo;

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
        companyRepo = new CompanyRepository(pool);
    }

    @AfterAll
    static void tearDownAll() throws Exception { if (pool != null) await(pool.close()); }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, users, companies CASCADE").execute());
    }

    /** A company row with a given provenance, as the pipeline would have left it. */
    private long givenCompany(String name, String domain, String logo, String source,
                              boolean humanVerified) throws Exception {
        Row row = await(pool.preparedQuery("""
                INSERT INTO companies (name, domain, logo_url, status, slug, domain_source,
                                       domain_verified_at, created_at, updated_at)
                VALUES ($1, $2, $3, 'ghost', $4, $5,
                        CASE WHEN $6 THEN now() ELSE NULL END, now(), now())
                RETURNING id
                """)
            .execute(Tuple.of(name, domain, logo, name.toLowerCase().replace(' ', '-'),
                              source, humanVerified))).iterator().next();
        return row.getLong("id");
    }

    private Row reload(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM companies WHERE id = $1")
            .execute(Tuple.of(id))).iterator().next();
    }

    private static PickedBrand pick(String domain) {
        return PickedBrand.of(domain, "https://img.logo.dev/" + domain + "?token=pk_x&fallback=404")
                          .orElseThrow();
    }

    // ── what a pick MAY do ────────────────────────────────────────────────────

    @Test
    @DisplayName("fills a company that has no identity at all")
    void fillsAnEmptyIdentity() throws Exception {
        long id = givenCompany("Lime", null, null, null, false);
        assertTrue(await(companyRepo.applyPickedBrand(id, pick("li.me"), BrandAuthority.USER_PICK)));
        assertEquals("li.me", reload(id).getString("domain"));
        assertTrue(reload(id).getString("logo_url").contains("li.me"));
    }

    @Test
    @DisplayName("corrects a GUESSED identity - the Lime case, end to end")
    void correctsAGuess() throws Exception {
        /*
          The regression that matters most. lime.com is a real company that is not this one, so a
          test asserting merely "a logo is present" passes straight through the bug. The assertion
          has to be on WHICH domain, and on the guess being gone rather than merely outranked.
        */
        long id = givenCompany("Lime", "lime.com",
                               "https://img.logo.dev/lime.com?token=pk_x", "GUESSED", false);
        assertTrue(await(companyRepo.applyPickedBrand(id, pick("li.me"), BrandAuthority.USER_PICK)));
        Row after = reload(id);
        assertEquals("li.me", after.getString("domain"));
        assertFalse(after.getString("logo_url").contains("lime.com"),
                    "the wrong company's mark must not survive anywhere on the row");
        assertEquals("RESOLVED", after.getString("domain_resolution_state"));
    }

    // ── what a pick MAY NOT do ────────────────────────────────────────────────

    @Test
    @DisplayName("does not flatten a resolved identity")
    void leavesResolvedIdentityAlone() throws Exception {
        long id = givenCompany("Zehrs", "zehrs.ca", "https://img.logo.dev/zehrs.ca?token=pk_x",
                               "RESOLVER_CONSENSUS", false);
        assertFalse(await(companyRepo.applyPickedBrand(id, pick("zehrsmarkets.com"),
                                                       BrandAuthority.USER_PICK)));
        assertEquals("zehrs.ca", reload(id).getString("domain"));
    }

    @Test
    @DisplayName("does not flatten what an admin set")
    void leavesAdminIdentityAlone() throws Exception {
        long id = givenCompany("Acme", "acme.co", "https://img.logo.dev/acme.co?token=pk_x",
                               "ADMIN", false);
        assertFalse(await(companyRepo.applyPickedBrand(id, pick("acme-wrong.com"),
                                                       BrandAuthority.USER_PICK)));
        assertEquals("acme.co", reload(id).getString("domain"));
    }

    @Test
    @DisplayName("does not flatten what a HUMAN confirmed, even if the source was only a guess")
    void leavesHumanVerifiedAlone() throws Exception {
        // domain_verified_at is a separate fact from domain_source: somebody looked and agreed.
        long id = givenCompany("Verified Co", "verified.co",
                               "https://img.logo.dev/verified.co?token=pk_x", "GUESSED", true);
        assertFalse(await(companyRepo.applyPickedBrand(id, pick("other.com"),
                                                       BrandAuthority.USER_PICK)));
        assertEquals("verified.co", reload(id).getString("domain"));
    }

    // ── what an ADMIN may do ──────────────────────────────────────────────────

    @Test
    @DisplayName("an admin overrides anything, and that becomes human-confirmed")
    void adminOverridesEverything() throws Exception {
        long id = givenCompany("Acme", "acme.co", "https://img.logo.dev/acme.co?token=pk_x",
                               "RESOLVER_CONSENSUS", true);
        assertTrue(await(companyRepo.applyPickedBrand(id, pick("acme.com"), BrandAuthority.ADMIN)));
        Row after = reload(id);
        assertEquals("acme.com", after.getString("domain"));
        assertEquals("ADMIN", after.getString("domain_source"));
        assertNotNull(after.getValue("domain_verified_at"), "an admin deciding IS the confirmation");
    }

    // ── consistency of the pair, and of the write ─────────────────────────────

    @Test
    @DisplayName("domain and logo move together, never one without the other")
    void identityStaysConsistent() throws Exception {
        /*
          A logo the client could not prove belongs to the domain arrives as null, and clearing the
          old one is deliberate: leaving it would pair the new domain with the previous company's
          mark, which is the original bug wearing a different hat.
        */
        long id = givenCompany("Lime", "lime.com",
                               "https://img.logo.dev/lime.com?token=pk_x", "GUESSED", false);
        PickedBrand domainOnly = PickedBrand.of("li.me", "https://evil.example/not-a-logo.png")
                                            .orElseThrow();
        assertNull(domainOnly.logoUrl(), "an unverifiable url never reaches the database");
        assertTrue(await(companyRepo.applyPickedBrand(id, domainOnly, BrandAuthority.USER_PICK)));
        Row after = reload(id);
        assertEquals("li.me", after.getString("domain"));
        assertNull(after.getString("logo_url"),
                   "the stale mark is cleared rather than left to contradict the new domain");
    }

    @Test
    @DisplayName("writing the same identity twice changes nothing the second time")
    void repeatWriteIsANoOp() throws Exception {
        // The companies trigger recomputes company_stats_live whenever logo_url changes, so a
        // pointless write is not free.
        long id = givenCompany("Lime", null, null, null, false);
        assertTrue(await(companyRepo.applyPickedBrand(id, pick("li.me"), BrandAuthority.USER_PICK)));
        assertFalse(await(companyRepo.applyPickedBrand(id, pick("li.me"), BrandAuthority.USER_PICK)),
                    "nothing changed, so nothing was written");
    }

    @Test
    @DisplayName("a free-text submission carrying no pick changes nothing")
    void noPickChangesNothing() throws Exception {
        long id = givenCompany("Lime", "lime.com",
                               "https://img.logo.dev/lime.com?token=pk_x", "GUESSED", false);
        assertTrue(PickedBrand.of(null, null).isEmpty());
        assertFalse(await(companyRepo.applyPickedBrand(id, null, BrandAuthority.USER_PICK)));
        assertEquals("lime.com", reload(id).getString("domain"));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}

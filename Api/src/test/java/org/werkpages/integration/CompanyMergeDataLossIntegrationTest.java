package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.werkpages.repository.CompanyRepository;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a company merge must not destroy.
 *
 * The merge tool on the admin panel used to finish with {@code DELETE FROM companies}. Several
 * tables reference companies with ON DELETE CASCADE, so that one statement reached much further
 * than the merge intended: every interview review written about the merged company was deleted,
 * along with its aliases, and nothing recorded that any of it had existed.
 *
 * These tests are the guard on that. Each one describes a thing a merge is not allowed to do.
 */
class CompanyMergeDataLossIntegrationTest {

    static PostgreSQLContainer<?> pg;
    static Pool pool;
    static CompanyRepository companyRepo;
    static UUID adminId;

    @BeforeAll
    static void setUp() throws Exception {
        pg = new PostgreSQLContainer<>("postgres:16-alpine");
        pg.start();
        Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
              .locations("classpath:db/migrations").load().migrate();
        PgConnectOptions opts = new PgConnectOptions()
            .setHost(pg.getHost()).setPort(pg.getFirstMappedPort())
            .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword());
        pool = PgBuilder.pool().connectingTo(opts).with(new PoolOptions().setMaxSize(4))
                        .using(Vertx.vertx()).build();
        companyRepo = new CompanyRepository(pool);
        adminId = insertUser("admin@test.com");
    }

    @AfterAll
    static void tearDown() { if (pg != null) pg.stop(); }

    @Test
    void mergePreservesInterviewReviews() throws Exception {
        // The headline: these were being deleted outright by the cascade.
        long keep  = insertCompany("Crumbl");
        long merge = insertCompany("Crumbl Cookies");
        UUID user  = insertUser("interviewee1@test.com");
        insertInterviewReview(merge, user, 2024);

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        Long survived = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM interview_reviews WHERE company_id = $1 AND deleted_at IS NULL")
            .execute(Tuple.of(keep)).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, survived,
            "the interview review moved to the surviving company instead of being destroyed");
    }

    @Test
    void mergeMovesAliasesAndKeepsTheOldNameFindable() throws Exception {
        long keep  = insertCompany("Acme");
        long merge = insertCompany("Acme Industries");
        await(pool.preparedQuery("INSERT INTO company_aliases(company_id, alias) VALUES ($1,$2)")
            .execute(Tuple.of(merge, "Acme Ind")).mapEmpty());

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        Long moved = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM company_aliases WHERE company_id = $1 AND alias = $2")
            .execute(Tuple.of(keep, "Acme Ind")).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, moved, "the alias followed the data");

        Long oldName = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM company_aliases WHERE company_id = $1 AND alias = $2")
            .execute(Tuple.of(keep, "Acme Industries")).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, oldName,
            "and the merged company's own name became an alias, so searching it finds the survivor "
            + "rather than nothing - otherwise the next person simply recreates it");
    }

    @Test
    void mergeRetiresTheSourceRatherThanDeletingIt() throws Exception {
        long keep  = insertCompany("Globex");
        long merge = insertCompany("Globex Corporation");

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        String status = await(pool.preparedQuery("SELECT status FROM companies WHERE id = $1")
            .execute(Tuple.of(merge)).map(rs -> rs.iterator().hasNext()
                ? rs.iterator().next().getString("status") : null));
        assertEquals("merged", status, "the source is retired, so nothing cascades and history survives");
    }

    @Test
    void mergeRecordsEveryRowItMoved() throws Exception {
        // The manifest is what makes an undo possible: exact row ids, not a summary.
        long keep  = insertCompany("Initech");
        long merge = insertCompany("Initech Systems");
        long managerId = insertManager("Moved Manager", "Initech Systems", merge);

        UUID mergeUuid = await(companyRepo.mergeCompanies(keep, merge, adminId));

        var row = await(pool.preparedQuery("""
                SELECT record_id, old_company_id, new_company_id, old_company_text
                FROM company_merge_records WHERE merge_id = $1 AND entity_type = 'manager'
                """)
            .execute(Tuple.of(mergeUuid)).map(rs -> rs.iterator().next()));
        assertEquals(String.valueOf(managerId), row.getString("record_id"));
        assertEquals(merge, row.getLong("old_company_id"));
        assertEquals(keep,  row.getLong("new_company_id"));
        assertEquals("Initech Systems", row.getString("old_company_text"),
            "the denormalised name is recorded too, or an undo could not restore the picker");
    }

    @Test
    void mergeKeepsTheOldUrlWorking() throws Exception {
        long keep  = insertCompany("Umbrella");
        long merge = insertCompany("Umbrella Corp");

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        Long target = await(pool.preparedQuery(
                "SELECT company_id FROM company_redirects WHERE old_slug = $1")
            .execute(Tuple.of("umbrella-corp")).map(rs -> rs.iterator().hasNext()
                ? rs.iterator().next().getLong("company_id") : null));
        assertEquals(keep, target, "a shared link to the merged company must not become a 404");
    }

    @Test
    void mergeRefusesWhenInterviewReviewsWouldCollide() throws Exception {
        // Same person, same year, both companies. Both are genuine contributions and the unique
        // index cannot hold both, so there is no correct automatic answer - it stops and asks.
        long keep  = insertCompany("Wayne");
        long merge = insertCompany("Wayne Enterprises");
        UUID user  = insertUser("both@test.com");
        insertInterviewReview(keep,  user, 2024);
        insertInterviewReview(merge, user, 2024);

        Exception thrown = assertThrows(Exception.class,
            () -> await(companyRepo.mergeCompanies(keep, merge, adminId)));
        assertTrue(thrown.getMessage().contains("collide"),
            "expected a refusal explaining the collision, got: " + thrown.getMessage());

        // And it refused before touching anything.
        String status = await(pool.preparedQuery("SELECT status FROM companies WHERE id = $1")
            .execute(Tuple.of(merge)).map(rs -> rs.iterator().next().getString("status")));
        assertEquals("approved", status, "a refused merge leaves the source exactly as it was");
    }

    @Test
    void previewReportsWhatWouldMoveWithoutMovingIt() throws Exception {
        long keep  = insertCompany("Stark");
        long merge = insertCompany("Stark Industries");
        insertManager("Pepper Potts", "Stark Industries", merge);

        var preview = await(companyRepo.previewMerge(keep, merge));

        assertEquals(1L, preview.getLong("managers"));
        assertEquals("Stark", preview.getString("keepName"));
        assertFalse(preview.getBoolean("blocked"));

        String status = await(pool.preparedQuery("SELECT status FROM companies WHERE id = $1")
            .execute(Tuple.of(merge)).map(rs -> rs.iterator().next().getString("status")));
        assertEquals("approved", status, "preview must not write anything");
    }

    // ── After the merge: the retired company must behave like one ─────────────

    @Test
    void aMergedCompanyIsNoLongerOfferedInThePicker() throws Exception {
        // Selecting it would attach a new manager to a company that has been absorbed - the exact
        // thing the merge was for. Its name still reaches the survivor, via the alias the merge
        // left behind.
        long keep  = insertCompany("Vandelay");
        long merge = insertCompany("Vandelay Industries");
        insertManager("Art Vandelay", "Vandelay Industries", merge);

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        var results = await(companyRepo.searchForPicker("Vandelay Industries"));
        java.util.Set<Long> ids = new java.util.HashSet<>();
        results.forEach(r -> ids.add(r.getLong("id")));
        assertFalse(ids.contains(merge), "the retired company must not be selectable");
        assertTrue(ids.contains(keep), "and its old name must still find the survivor");
    }

    @Test
    void anIdPointingAtAMergedCompanyFollowsTheMerge() throws Exception {
        // Somebody had the form open when an admin merged the company underneath them. Their
        // submission belongs on the survivor, not on a headstone.
        long keep  = insertCompany("Tyrell");
        long merge = insertCompany("Tyrell Corporation");
        await(companyRepo.mergeCompanies(keep, merge, adminId));

        var resolved = await(companyRepo.resolve(merge, "Tyrell Corporation", null, null));
        assertEquals(keep, resolved.getLong("id"),
            "a stale ID resolves to the surviving company rather than the retired one");
    }

    @Test
    void aMergedCompanysLinkStillLeadsSomewhereUseful() throws Exception {
        long keep  = insertCompany("Cyberdyne");
        long merge = insertCompany("Cyberdyne Systems");
        await(companyRepo.mergeCompanies(keep, merge, adminId));

        var target = await(companyRepo.findRedirectTargetBySlug("cyberdyne-systems"));
        assertTrue(target.isPresent(), "the old slug resolves");
        assertEquals(keep, target.get().getLong("id"), "and it leads to the surviving company");
    }

    @Test
    void aChainOfMergesLeadsToTheLastSurvivor() throws Exception {
        // A into B, later B into C. Somebody holding A's link should reach C, not a retired B.
        long a = insertCompany("Chain One");
        long b = insertCompany("Chain Two");
        long c = insertCompany("Chain Three");

        await(companyRepo.mergeCompanies(b, a, adminId));   // A -> B
        await(companyRepo.mergeCompanies(c, b, adminId));   // B -> C

        var survivor = await(companyRepo.resolveMergeTarget(a));
        assertTrue(survivor.isPresent());
        assertEquals(c, survivor.get().getLong("id"),
            "following one hop would have landed on the retired middle company");
    }

    // ── Undo ──────────────────────────────────────────────────────────────────
    //
    // The manifest exists so a merge can be taken back. These prove it actually can, and that it
    // takes back only what the merge moved.

    @Test
    void undoPutsEveryMovedRowBack() throws Exception {
        long keep  = insertCompany("Weyland");
        long merge = insertCompany("Weyland Yutani");
        long managerId = insertManager("Ellen Ripley", "Weyland Yutani", merge);
        UUID user = insertUser("undo1@test.com");
        insertInterviewReview(merge, user, 2023);

        UUID mergeUuid = await(companyRepo.mergeCompanies(keep, merge, adminId));
        await(companyRepo.undoMerge(mergeUuid));

        var mgr = await(pool.preparedQuery("SELECT company_id, company FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next()));
        assertEquals(merge, mgr.getLong("company_id"), "the manager went back to its company");
        assertEquals("Weyland Yutani", mgr.getString("company"),
            "and so did the denormalised name, or the picker would still show the survivor's");

        Long interviews = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM interview_reviews WHERE company_id = $1")
            .execute(Tuple.of(merge)).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, interviews, "the interview review came back too");

        String status = await(pool.preparedQuery("SELECT status FROM companies WHERE id = $1")
            .execute(Tuple.of(merge)).map(rs -> rs.iterator().next().getString("status")));
        assertEquals("approved", status, "and the company is live again");
    }

    @Test
    void undoRestoresAGhostCompanyAsAGhost() throws Exception {
        // Being restored is not a promotion.
        long keep  = insertCompany("Real Corp");
        long merge = await(pool.preparedQuery(
                "INSERT INTO companies(name,status,slug) VALUES ('Ghosty Corp','ghost','ghosty-corp') RETURNING id")
            .execute().map(rs -> rs.iterator().next().getLong("id")));

        UUID mergeUuid = await(companyRepo.mergeCompanies(keep, merge, adminId));
        await(companyRepo.undoMerge(mergeUuid));

        String status = await(pool.preparedQuery("SELECT status FROM companies WHERE id = $1")
            .execute(Tuple.of(merge)).map(rs -> rs.iterator().next().getString("status")));
        assertEquals("ghost", status);
    }

    @Test
    void undoLeavesAloneWhatArrivedAfterTheMerge() throws Exception {
        // The manifest names the rows the merge moved. A manager added to the survivor afterwards
        // is not on that list and must stay where it is.
        long keep  = insertCompany("Survivor Ltd");
        long merge = insertCompany("Absorbed Ltd");
        long moved = insertManager("Moved Person", "Absorbed Ltd", merge);

        UUID mergeUuid = await(companyRepo.mergeCompanies(keep, merge, adminId));
        long arrivedLater = insertManager("Later Person", "Survivor Ltd", keep);

        await(companyRepo.undoMerge(mergeUuid));

        Long movedBack = await(pool.preparedQuery("SELECT company_id FROM managers WHERE id = $1")
            .execute(Tuple.of(moved)).map(rs -> rs.iterator().next().getLong("company_id")));
        assertEquals(merge, movedBack);

        Long stayed = await(pool.preparedQuery("SELECT company_id FROM managers WHERE id = $1")
            .execute(Tuple.of(arrivedLater)).map(rs -> rs.iterator().next().getLong("company_id")));
        assertEquals(keep, stayed, "someone who joined the survivor later is not swept back");
    }

    @Test
    void undoRemovesTheAliasTheMergeAddedAndReturnsTheOnesItMoved() throws Exception {
        long keep  = insertCompany("Kept Co");
        long merge = insertCompany("Gone Co");
        await(pool.preparedQuery("INSERT INTO company_aliases(company_id, alias) VALUES ($1,$2)")
            .execute(Tuple.of(merge, "Gone Company")).mapEmpty());

        UUID mergeUuid = await(companyRepo.mergeCompanies(keep, merge, adminId));
        await(companyRepo.undoMerge(mergeUuid));

        Long returned = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM company_aliases WHERE company_id = $1 AND alias = $2")
            .execute(Tuple.of(merge, "Gone Company")).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, returned, "the moved alias went home");

        Long addedName = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM company_aliases WHERE company_id = $1 AND alias = $2")
            .execute(Tuple.of(keep, "Gone Co")).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(0L, addedName,
            "and the survivor no longer claims the restored company's name, which is once again its own");
    }

    @Test
    void undoRemovesTheRedirectSoTheCompanyOwnsItsUrlAgain() throws Exception {
        long keep  = insertCompany("Alpha Group");
        long merge = insertCompany("Beta Group");

        UUID mergeUuid = await(companyRepo.mergeCompanies(keep, merge, adminId));
        await(companyRepo.undoMerge(mergeUuid));

        assertTrue(await(companyRepo.findRedirectTargetBySlug("beta-group")).isEmpty());
    }

    @Test
    void aMergeCannotBeUndoneTwice() throws Exception {
        long keep  = insertCompany("Once Co");
        long merge = insertCompany("Twice Co");
        UUID mergeUuid = await(companyRepo.mergeCompanies(keep, merge, adminId));

        await(companyRepo.undoMerge(mergeUuid));

        Exception thrown = assertThrows(Exception.class, () -> await(companyRepo.undoMerge(mergeUuid)));
        assertTrue(thrown.getMessage().contains("already been reverted"),
            "expected a clear refusal, got: " + thrown.getMessage());
    }

    @Test
    void aRestoredCompanyIsSelectableAgain() throws Exception {
        // The round trip that matters: merged out of the picker, undone back into it.
        long keep  = insertCompany("Pick Keep");
        long merge = insertCompany("Pick Gone");
        insertManager("Someone", "Pick Gone", merge);

        UUID mergeUuid = await(companyRepo.mergeCompanies(keep, merge, adminId));
        var during = await(companyRepo.searchForPicker("Pick Gone"));
        java.util.Set<Long> idsDuring = new java.util.HashSet<>();
        during.forEach(r -> idsDuring.add(r.getLong("id")));
        assertFalse(idsDuring.contains(merge), "not selectable while merged");

        await(companyRepo.undoMerge(mergeUuid));
        var after = await(companyRepo.searchForPicker("Pick Gone"));
        java.util.Set<Long> idsAfter = new java.util.HashSet<>();
        after.forEach(r -> idsAfter.add(r.getLong("id")));
        assertTrue(idsAfter.contains(merge), "selectable again once restored");
    }

    // ── corporate structure survives a merge ──────────────────────────────────
    //
    // Merging a duplicate must not quietly cost you a relationship. These are the cases that come
    // up cleaning real data: the same company entered four times, one of the copies being the one
    // somebody already linked into a group.

    @Test
    void theSurvivorInheritsWhatTheSourceWasPartOf() throws Exception {
        // "Zehrs Markets" is linked to Loblaw; it is then merged into "Zehrs". The link has to
        // follow, or the group silently loses a member and nothing says so.
        long loblaw = insertCompany("Structure Loblaw");
        long keep   = insertCompany("Structure Zehrs");
        long merge  = insertCompany("Structure Zehrs Markets");
        await(companyRepo.setCompanyParent(merge, loblaw, "BRAND_OF"));

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        var parent = await(companyRepo.findCompanyParent(keep));
        assertTrue(parent.isPresent(), "the survivor inherited the group membership");
        assertEquals(loblaw, parent.get().getLong("id"));
    }

    @Test
    void theSurvivorInheritsWhatBelongedToTheSource() throws Exception {
        // The other direction: the absorbed company was itself a parent.
        long keep  = insertCompany("Parent Keep");
        long merge = insertCompany("Parent Gone");
        long child = insertCompany("Parent Child");
        await(companyRepo.setCompanyParent(child, merge, "SUBSIDIARY_OF"));

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        var children = await(companyRepo.findCompanyChildren(keep));
        assertEquals(1, children.size(), "the child moved to the surviving parent");
        assertEquals(child, children.iterator().next().getLong("id"));
    }

    @Test
    void theSurvivorsOwnParentIsNotOverwritten() throws Exception {
        // One parent per company. When both companies have one, the survivor's own answer wins -
        // a merge tidies up a duplicate, it does not get to re-file the company that remains.
        long keepParent  = insertCompany("Owner Keep");
        long mergeParent = insertCompany("Owner Gone");
        long keep  = insertCompany("Child Keep");
        long merge = insertCompany("Child Gone");
        await(companyRepo.setCompanyParent(keep, keepParent, "SUBSIDIARY_OF"));
        await(companyRepo.setCompanyParent(merge, mergeParent, "SUBSIDIARY_OF"));

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        var parent = await(companyRepo.findCompanyParent(keep));
        assertTrue(parent.isPresent());
        assertEquals(keepParent, parent.get().getLong("id"),
            "the survivor kept its own parent rather than adopting the source's");
    }

    @Test
    void undoPutsCorporateStructureBack() throws Exception {
        long loblaw = insertCompany("Undo Loblaw");
        long keep   = insertCompany("Undo Keep");
        long merge  = insertCompany("Undo Gone");
        await(companyRepo.setCompanyParent(merge, loblaw, "BRAND_OF"));

        UUID mergeUuid = await(companyRepo.mergeCompanies(keep, merge, adminId));
        assertTrue(await(companyRepo.findCompanyParent(keep)).isPresent(), "carried by the merge");

        await(companyRepo.undoMerge(mergeUuid));

        assertTrue(await(companyRepo.findCompanyParent(keep)).isEmpty(),
            "the survivor gave the membership back");
        var restored = await(companyRepo.findCompanyParent(merge));
        assertTrue(restored.isPresent(), "the restored company is in the group again");
        assertEquals(loblaw, restored.get().getLong("id"));
    }

    @Test
    void mergingAParentIntoItsOwnChildDoesNotLoop() throws Exception {
        // The pathological case. Absorbing a parent into its own child would make the child its
        // own parent; the database would reject the loop and take the entire merge down with it.
        // The merge is expected to succeed and simply leave that one relationship behind.
        long child  = insertCompany("Loop Child");
        long parent = insertCompany("Loop Parent");
        await(companyRepo.setCompanyParent(child, parent, "SUBSIDIARY_OF"));

        await(companyRepo.mergeCompanies(child, parent, adminId));

        assertTrue(await(companyRepo.findCompanyParent(child)).isEmpty(),
            "the survivor is not its own parent");
        Long selfLoops = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM company_relationships WHERE child_company_id = parent_company_id")
            .execute().map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(0L, selfLoops, "no self-referencing relationship was written");
    }

    @Test
    void anAliasTheSurvivorAlreadyHasIsNotDestroyed() throws Exception {
        // Both companies answer to "Structure Twin". The duplicate cannot move onto the survivor,
        // so it stays on the retired company - invisible while merged, intact for an undo.
        long keep  = insertCompany("Alias Keep");
        long merge = insertCompany("Alias Gone");
        await(pool.preparedQuery(
                "INSERT INTO company_aliases(company_id, alias, alias_type) VALUES ($1,$2,'TRADE_NAME'),($3,$2,'TRADE_NAME')")
            .execute(Tuple.of(keep, "Structure Twin", merge)).mapEmpty());

        UUID mergeUuid = await(companyRepo.mergeCompanies(keep, merge, adminId));
        await(companyRepo.undoMerge(mergeUuid));

        Long stillThere = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM company_aliases WHERE company_id = $1 AND alias = $2")
            .execute(Tuple.of(merge, "Structure Twin")).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, stillThere, "the restored company kept the alias it arrived with");
    }

    // ── review snapshots ──────────────────────────────────────────────────────
    //
    // reviews.manager_company is a snapshot of the company a review was written about, and
    // findManagersByCompanyId matches on it - so it decides which managers appear on a company
    // page, not just what the page prints.

    @Test
    void aMergeDoesNotRewriteReviewsOfManagersItDidNotMove() throws Exception {
        // The survivor's own manager was never part of this merge. Their review really was written
        // under the name it records, and a merge of some other company does not change that.
        long keep  = insertCompany("Snapshot Keep");
        long merge = insertCompany("Snapshot Gone");
        long settled = insertManager("Already Here", "Snapshot Keep", keep);
        long moved   = insertManager("Coming Over", "Snapshot Gone", merge);
        insertReview(settled, "An Older Name");
        insertReview(moved, "Snapshot Gone");

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        assertEquals("An Older Name", reviewCompany(settled),
            "a manager who was already here kept their own review history");
        assertEquals("Snapshot Keep", reviewCompany(moved),
            "the manager who actually moved shows the surviving name");
    }

    @Test
    void undoRestoresReviewSnapshots() throws Exception {
        // Without this the restored company's managers keep naming the survivor in their reviews,
        // and go on appearing on the survivor's page for good.
        long keep  = insertCompany("Restore Keep");
        long merge = insertCompany("Restore Gone");
        long moved = insertManager("Goes Back", "Restore Gone", merge);
        insertReview(moved, "Restore Gone");

        UUID mergeUuid = await(companyRepo.mergeCompanies(keep, merge, adminId));
        assertEquals("Restore Keep", reviewCompany(moved), "rewritten by the merge");

        await(companyRepo.undoMerge(mergeUuid));

        assertEquals("Restore Gone", reviewCompany(moved),
            "the review names the company it was actually written about again");
    }

    @Test
    void aMergedCompanysUrlStillLoadsItsPage() throws Exception {
        // The promise the redirect table exists to keep, exercised end to end rather than by
        // checking that a redirect row was written.
        //
        // The row was always written correctly. What broke was the shape of what came back:
        // findRedirectTargetBySlug selected c.*, which carries no stats_logo_url - that is a
        // computed alias, not a column - so the page built from it threw and every merged
        // company's URL returned a 500. A test asserting the redirect row existed passed the
        // whole time.
        long keep  = insertCompany("Redirect Keep");
        long merge = insertCompany("Redirect Gone");
        insertManager("Someone There", "Redirect Gone", merge);

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        var target = await(companyRepo.findRedirectTargetBySlug("redirect-gone"));
        assertTrue(target.isPresent(), "the old slug still resolves");
        assertEquals(keep, target.get().getLong("id"), "and it resolves to the survivor");
        // Every column the company page reads must be present, not merely the ones a redirect
        // needs. Reading an absent column is what threw.
        assertDoesNotThrow(() -> {
            target.get().getString("name");
            target.get().getString("slug");
            target.get().getString("industry");
            target.get().getString("logo_url");
            target.get().getString("status");
            target.get().getString("stats_logo_url");
        }, "the redirect row carries the same columns as a direct slug lookup");
    }

    @Test
    void thePreviewDoesNotWarnAboutManagersNobodyCanSee() throws Exception {
        // A rejected manager appears on no public surface. Warning that one "appears under both
        // companies" is a false alarm about a row nobody will ever encounter, and a warning that
        // fires on every merge is one an admin learns to click past.
        long keep  = insertCompany("Warn Keep");
        long merge = insertCompany("Warn Gone");
        insertManager("Aaron Hack", "Warn Gone", merge);
        long hidden = insertManager("Aaron Hack", "Warn Keep", keep);
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'rejected' WHERE id = $1")
            .execute(Tuple.of(hidden)).mapEmpty());

        var preview = await(companyRepo.previewMerge(keep, merge));

        assertEquals(0L, preview.getLong("duplicateManagers"),
            "a rejected manager is not a collision anyone will see");
        assertFalse(preview.getBoolean("blocked"), "and it certainly does not block the merge");
    }

    @Test
    void thePreviewCountsNamesNotMatchingRows() throws Exception {
        // One person listed three times at the target is one name, not three. COUNT(*) over the
        // join counts pairs, which reported "3 manager names appear under both" for a single name.
        long keep  = insertCompany("Count Keep");
        long merge = insertCompany("Count Gone");
        insertManager("Aaron Hack", "Count Gone", merge);
        insertManager("Aaron Hack", "Count Keep", keep);
        insertManager("Aaron Hack", "Count Keep", keep);
        insertManager("Aaron Hack", "Count Keep", keep);

        var preview = await(companyRepo.previewMerge(keep, merge));

        assertEquals(1L, preview.getLong("duplicateManagers"), "one name, however many rows carry it");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies(name,status,slug) VALUES ($1,'approved',$2) RETURNING id")
            .execute(Tuple.of(name, name.toLowerCase().replaceAll("[^a-z0-9]+", "-")))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManager(String name, String company, long companyId) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers(name,company,company_id,title,image,status,approval_status," +
                "overall_rating,reviews_count,category_averages) " +
                "VALUES ($1,$2,$3,'VP','img','active','approved',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, companyId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static UUID insertUser(String email) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users(auth0_id,email,username) VALUES ($1,$2,$3) RETURNING id")
            .execute(Tuple.of("auth0|" + email, email, email.split("@")[0]))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private void insertInterviewReview(long companyId, UUID userId, int year) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO interview_reviews(company_id,user_id,interview_year,overall_rating," +
                "difficulty,outcome) VALUES ($1,$2,$3,4,3,'offer')")
            .execute(Tuple.of(companyId, userId, year)).mapEmpty());
    }

    private void insertReview(long managerId, String company) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO reviews(manager_id,user_id,author,overall_rating,manager_company,manager_title,weight)
                VALUES ($1,NULL,'Anon',3.0,$2,'Manager',FALSE)
                """)
            .execute(Tuple.of(managerId, company)).mapEmpty());
    }

    private String reviewCompany(long managerId) throws Exception {
        return await(pool.preparedQuery("SELECT manager_company FROM reviews WHERE manager_id = $1 LIMIT 1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next().getString("manager_company")));
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}

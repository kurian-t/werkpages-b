package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.werkpages.repository.RoleAliasRepository;

import java.util.List;
import java.util.Optional;

/**
 * Classifies distinct job titles into a role family and seniority.
 *
 * <p>Titles are free text, so the same job arrives spelled a dozen ways. The database normalizes
 * the string on write (V49); this service assigns meaning to the result and stores it in
 * {@code role_aliases}, keyed by the normalized title rather than by manager — a few thousand
 * distinct titles stand behind any number of managers, so improving a rule reclassifies everyone
 * who shares that title without touching a single manager row.
 */
public class RoleService {

    /**
     * How many titles one classification pass will take on.
     *
     * <p>Bounded so the job is a predictable unit of work that can be run repeatedly rather than
     * one sweep that either finishes or times out. Re-running until {@code remaining} reaches zero
     * is the intended way to work through a backlog.
     */
    private static final int BATCH_SIZE = 500;

    private static final int MAX_PAGE_SIZE = 200;

    /** Below this the query matches nearly everything, which is not a suggestion. */
    private static final int MIN_SUGGEST_CHARS = 2;

    /** Long enough to cover the real spellings, short enough to scan without scrolling. */
    private static final int SUGGESTION_LIMIT = 8;

    private final RoleAliasRepository roleAliasRepo;

    public RoleService(RoleAliasRepository roleAliasRepo) {
        this.roleAliasRepo = roleAliasRepo;
    }

    /**
     * Classifies every not-yet-seen title, up to one batch.
     *
     * <p>Titles the rules cannot read are still recorded, with null family and seniority. That is
     * deliberate: an unclassifiable title is a fact worth storing, it stops the job re-examining
     * the same string on every run, and the rows are exactly the work list for a future AI pass.
     */
    public Future<JsonObject> classifyPending() {
        return roleAliasRepo.findUnclassifiedTitles(BATCH_SIZE).compose(titles -> {
            if (titles.isEmpty()) {
                return coverage().map(c -> c.put("classified", 0).put("unreadable", 0));
            }

            Future<Void> chain = Future.succeededFuture();
            int unreadable = 0;
            for (String title : titles) {
                Optional<String> family = RoleClassifier.family(title);
                Optional<String> seniority = RoleClassifier.seniority(title);
                if (family.isEmpty() && seniority.isEmpty()) unreadable++;
                chain = chain.compose(ignored ->
                    roleAliasRepo.upsert(title, family.orElse(null), seniority.orElse(null), "rule"));
            }

            final int finalUnreadable = unreadable;
            return chain
                .compose(ignored -> coverage())
                .map(c -> c.put("classified", titles.size()).put("unreadable", finalUnreadable));
        });
    }

    /**
     * Sets a family and seniority by hand, overriding whatever the rules decided.
     *
     * <p>Marked {@code manual} so no later automated pass can undo it — a human correction is the
     * best signal available, and losing it to the next sweep would make correcting anything
     * pointless.
     */
    public Future<Void> classifyManually(String titleNormalized, String roleFamily, String seniority) {
        if (titleNormalized == null || titleNormalized.isBlank()) {
            return Future.failedFuture(ServiceException.badRequest("titleNormalized is required"));
        }
        if (roleFamily == null && seniority == null) {
            return Future.failedFuture(ServiceException.badRequest("Provide a role family, a seniority, or both"));
        }
        // Checked here so a bad value comes back as a readable 400 rather than a raw constraint
        // violation from the CHECK in V49.
        if (roleFamily != null && !ROLE_FAMILIES.contains(roleFamily)) {
            return Future.failedFuture(ServiceException.badRequest("Unknown role family: " + roleFamily));
        }
        if (seniority != null && !SENIORITIES.contains(seniority)) {
            return Future.failedFuture(ServiceException.badRequest("Unknown seniority: " + seniority));
        }
        return roleAliasRepo.upsertManual(titleNormalized.trim(), roleFamily, seniority);
    }

    /**
     * The alias table in frequency order — the surface for judging whether normalization works.
     *
     * <p>Ordered by how many managers hold each title, because a wrong rule on a common title
     * matters far more than a wrong rule on one nobody has.
     */
    public Future<JsonObject> listAliases(int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        int safeOffset = Math.max(offset, 0);

        return roleAliasRepo.findAllWithCounts(safeLimit, safeOffset).compose(rows -> {
            JsonArray data = new JsonArray();
            for (Row row : rows) {
                data.add(new JsonObject()
                    .put("titleNormalized", row.getString("title_normalized"))
                    .put("roleFamily",      row.getString("role_family"))
                    .put("seniority",       row.getString("seniority"))
                    .put("source",          row.getString("source"))
                    .put("managerCount",    row.getLong("manager_count").intValue())
                    .put("sampleTitle",     row.getString("sample_title")));
            }
            return coverage().map(c -> c.put("data", data));
        });
    }

    /**
     * Suggestions for the title field, as {name, managerCount} pairs.
     *
     * <p>Returns nothing for a query too short to be meaningful — one or two characters match
     * almost everything, and a list of everything is not a suggestion.
     */
    public Future<JsonArray> suggestTitles(String query) {
        if (query == null || query.trim().length() < MIN_SUGGEST_CHARS) {
            return Future.succeededFuture(new JsonArray());
        }
        return roleAliasRepo.suggestTitles(query.trim(), SUGGESTION_LIMIT).map(rows -> {
            JsonArray out = new JsonArray();
            for (Row row : rows) {
                out.add(new JsonObject()
                    .put("title",        row.getString("display_title"))
                    .put("normalized",   row.getString("title_normalized"))
                    .put("usageCount",   row.getLong("usage_count").intValue()));
            }
            return out;
        });
    }

    /** How much of the visible corpus currently resolves to a family and a seniority. */
    public Future<JsonObject> coverage() {
        return roleAliasRepo.findCoverage().map(row -> {
            int distinctTitles = row.getLong("distinct_titles").intValue();
            int classified     = row.getLong("classified_titles").intValue();
            int managersTotal  = row.getLong("managers_total").intValue();
            int withFamily     = row.getLong("managers_with_family").intValue();
            int withSeniority  = row.getLong("managers_with_seniority").intValue();

            return new JsonObject()
                .put("distinctTitles",    distinctTitles)
                .put("classifiedTitles",  classified)
                .put("remaining",         Math.max(0, distinctTitles - classified))
                .put("managersTotal",     managersTotal)
                .put("managersWithFamily",    withFamily)
                .put("managersWithSeniority", withSeniority)
                // Coverage is reported by MANAGER, not by title. Ten thousand people sharing one
                // well-understood title matter more than a hundred one-off titles nobody holds.
                .put("familyCoveragePct",    percentage(withFamily, managersTotal))
                .put("seniorityCoveragePct", percentage(withSeniority, managersTotal));
        });
    }

    private static int percentage(int part, int whole) {
        return whole == 0 ? 0 : (int) Math.round((part * 100.0) / whole);
    }

    /** Families the API accepts, matching the CHECK constraint in V49. */
    public static final List<String> ROLE_FAMILIES = List.of(
        "engineering", "product", "design", "data", "it",
        "sales", "marketing", "customer_support", "operations",
        "finance", "hr", "legal", "project_management",
        "research", "education", "healthcare", "general", "other");

    /** Seniority levels the API accepts, matching the CHECK constraint in V49. */
    public static final List<String> SENIORITIES = List.of(
        "lead", "manager", "senior_manager", "director", "vp", "executive");
}

package org.werkpages.service;

import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;

/**
 * The only permitted way to serialise a role claim, and the reason there are exactly two.
 *
 * <h2>The rule</h2>
 *
 * <p><b>A contributor's private employment period must never become publicly accessible merely
 * because a new endpoint exposes the claim underneath their rating.</b>
 *
 * <p>{@code role_claims.start_date} and {@code role_claims.end_date} are <b>the contributor's own
 * asserted employment period</b>, and the claim is reachable from a rating by {@code claim_id}.
 * Hiding a rating's dates achieves nothing if the claim behind it republishes the same fact.
 *
 * <p>Nothing exposed a claim before phase 4B, because no endpoint read the table at all. That was
 * never a safeguard, only an absence, and 4B and 4C are what remove it. This class exists so the
 * safeguard arrives before the readers do.
 *
 * <h2>Why two views and not one with a flag</h2>
 *
 * <p>A single serialiser with a {@code boolean includeDates} parameter is one mistaken call site
 * away from a leak, and the mistaken value is the convenient one. {@link #publicJson} <b>has no
 * code path that emits the period at all</b>: the fields are not written, so no argument, no
 * viewer, no admin check and no future edit to a caller can turn them on. Getting it wrong requires
 * calling the method whose name says admin.
 *
 * <p><b>Do not serialise a claim {@code Row} any other way.</b> Passing the raw entity to a
 * response is how the rating's own dates would have leaked, and that is the specific mistake this
 * type is here to make impossible.
 *
 * <h2>What is NOT hidden, deliberately</h2>
 *
 * <p>The <b>manager's</b> employment record is a different fact and stays public: {@code
 * career_history} says when the manager held the job, not when a contributor worked under them.
 * Conflating the two would hide the employment history the whole redesign exists to build.
 *
 * <p>Job titles and work locations are also identifying at small employers, and they are
 * <b>unchanged here</b>. Masking a title changes what a rating says about which job it concerns,
 * which is a product decision rather than a privacy switch, and it stays open rather than being
 * decided quietly by this class.
 */
public final class ClaimView {

    private ClaimView() {}

    /**
     * What anybody may see about a claim.
     *
     * <p>The contributor's asserted period is absent by construction, as is the contributor. What
     * remains is the employment the claim resolved to and the state of that resolution, which is
     * what a public surface legitimately needs in order to say which job a rating concerns.
     */
    public static JsonObject publicJson(Row claim) {
        return new JsonObject()
            .put("id",              claim.getLong("id"))
            .put("managerId",       claim.getLong("manager_id"))
            .put("companyId",       claim.getLong("company_id"))
            .put("title",           claim.getString("title"))
            .put("resolution",      claim.getString("resolution"))
            .put("resolvedRoleId",  claim.getLong("resolved_role_id"));
        // DELIBERATELY ABSENT, and absent rather than nulled so that no caller can reinstate them:
        //   start_date, end_date, period_known, still_there  - the contributor's period
        //   user_id                                          - the contributor
        //   selected_role_id, original_manager_id            - what they chose, and when
        //   proposal_key, draft state, revision              - their in-progress form
    }

    /**
     * The whole claim, for moderation.
     *
     * <p>Named so that using it is a decision. <b>It must never be reached by an unauthenticated or
     * non-moderator caller</b>, and it must not be reused to build a public response: that is the
     * accidental reuse of a privileged DTO that this split exists to prevent.
     *
     * <p>A moderator genuinely needs the period. Deciding whether two contributors describe one
     * employment, or whether an end date is wrong, is impossible without it. The requirement was
     * never that the dates stop existing, only that they stop being published.
     */
    public static JsonObject adminJson(Row claim) {
        return new JsonObject()
            .put("id",                 claim.getLong("id"))
            .put("managerId",          claim.getLong("manager_id"))
            .put("companyId",          claim.getLong("company_id"))
            .put("title",              claim.getString("title"))
            .put("resolution",         claim.getString("resolution"))
            .put("resolvedRoleId",     claim.getLong("resolved_role_id"))
            .put("selectedRoleId",     claim.getLong("selected_role_id"))
            .put("originalManagerId",  claim.getLong("original_manager_id"))
            .put("userId",             claim.getUUID("user_id"))
            .put("startDate",          dateOrNull(claim, "start_date"))
            .put("endDate",            dateOrNull(claim, "end_date"))
            .put("periodKnown",        claim.getBoolean("period_known"))
            .put("stillThere",         claim.getBoolean("still_there"))
            .put("finalizedAt",        claim.getOffsetDateTime("finalized_at") == null
                                       ? null : claim.getOffsetDateTime("finalized_at").toString())
            .put("revision",           claim.getLong("revision"));
    }

    /** The field names {@link #publicJson} must never contain. Asserted by its own tests. */
    public static final java.util.List<String> FIELDS_NEVER_PUBLIC = java.util.List.of(
        "startDate", "endDate", "periodKnown", "stillThere", "userId",
        "selectedRoleId", "originalManagerId", "proposalKey", "revision");

    private static String dateOrNull(Row row, String column) {
        java.time.LocalDate d = row.getLocalDate(column);
        return d == null ? null : d.toString();
    }
}

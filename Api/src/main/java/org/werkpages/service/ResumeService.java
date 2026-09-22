package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.ResumeRepository;
import org.werkpages.repository.UserRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ResumeService {

    private final UserRepository    userRepo;
    private final ResumeRepository  resumeRepo;
    private final CompanyRepository companyRepo;

    public ResumeService(UserRepository userRepo, ResumeRepository resumeRepo, CompanyRepository companyRepo) {
        this.userRepo    = userRepo;
        this.resumeRepo  = resumeRepo;
        this.companyRepo = companyRepo;
    }

    public Future<JsonObject> getResume(String auth0Id) {
        return resolveUserId(auth0Id)
            .compose(userId -> resumeRepo.findByUserId(userId)
                .compose(opt -> {
                    if (opt.isEmpty()) return Future.succeededFuture(null);
                    JsonObject base = rowToJson(opt.get());
                    JsonArray entries = base.getJsonArray("workEntries", new JsonArray());
                    // Collect company names that don't already have a logoUrl stored
                    List<String> needsLogo = new ArrayList<>();
                    for (int i = 0; i < entries.size(); i++) {
                        JsonObject e = entries.getJsonObject(i);
                        String company = e.getString("company");
                        if (company != null && !company.isBlank() && !e.containsKey("logoUrl")) {
                            needsLogo.add(company.trim());
                        }
                    }
                    if (needsLogo.isEmpty()) return Future.succeededFuture(base);
                    // Enrich entries with logos from the companies table
                    return resumeRepo.findLogosByCompanyNames(needsLogo)
                        .map(logoMap -> {
                            for (int i = 0; i < entries.size(); i++) {
                                JsonObject e = entries.getJsonObject(i);
                                String company = e.getString("company");
                                if (company != null && !e.containsKey("logoUrl")) {
                                    String logo = logoMap.get(company.trim().toLowerCase());
                                    if (logo != null) e.put("logoUrl", logo);
                                }
                            }
                            return base;
                        });
                })
            );
    }

    public Future<JsonObject> saveResume(String auth0Id, JsonObject body) {
        return resolveUserId(auth0Id).compose(userId -> {
            String     summary     = body.getString("summary", "");
            JsonArray  skills      = body.getJsonArray("skills",      new JsonArray());
            JsonArray  education   = body.getJsonArray("education",   new JsonArray());
            JsonArray  workEntries = body.getJsonArray("workEntries", new JsonArray());
            JsonArray  extraLinks  = body.getJsonArray("extraLinks",  new JsonArray());
            JsonObject design      = body.getJsonObject("design");

            return ensureCompaniesExist(workEntries, education)
                .compose(v -> resumeRepo.upsert(userId, summary, skills, education, workEntries, extraLinks, design))
                .map(this::rowToJson);
        });
    }

    public Future<JsonObject> getPrefill(String auth0Id) {
        return resolveUserId(auth0Id)
            .compose(userId -> resumeRepo.getPrefillEntries(userId)
                .map(rows -> {
                    JsonArray entries = new JsonArray();
                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM");
                    for (Row row : rows) {
                        JsonObject entry = new JsonObject()
                            .put("company", row.getString("company"))
                            .put("title",   row.getString("title"))
                            .put("current", false)
                            .put("description", "");

                        LocalDate from  = row.getLocalDate("worked_from");
                        LocalDate until = row.getLocalDate("worked_until");
                        entry.put("startDate", from  != null ? from.format(fmt)  : null);
                        entry.put("endDate",   until != null ? until.format(fmt) : null);
                        if (until == null && from != null) entry.put("current", true);

                        Long managerId = row.getLong("manager_id");
                        if (managerId != null) entry.put("managerId", managerId);

                        String logoUrl = row.getString("logo_url");
                        if (logoUrl != null) entry.put("logoUrl", logoUrl);

                        entries.add(entry);
                    }
                    return new JsonObject().put("data", entries);
                })
            );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the caller to a user id, and refuses anyone who is not an admin.
     *
     * <p>Every public method on this service starts here, which is why the check lives here rather
     * than in {@code ResumesHandler}: one choke point covers the three endpoints and anything
     * added later, and it cannot be bypassed by reaching the service another way.
     *
     * <p>The resume builder is unreleased. The client gates it in three places — both header links
     * and an {@code <AdminOnly>} wrapper on the route — but a route guard in a single-page app is
     * not access control: the endpoints previously answered any signed-in caller, so anybody who
     * found {@code /api/resumes/mine} could read and write their own row and put real data in the
     * production table. Backend authorization is the authoritative one.
     *
     * <p>Same shape as {@code AdminService.requireAdmin}: 401 when there is no caller or no such
     * user, 403 when there is one and they are not an admin.
     */
    private Future<UUID> resolveUserId(String auth0Id) {
        if (auth0Id == null) return Future.failedFuture(ServiceException.unauthorized("Unauthorized"));
        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("User not found"));
                Row row = opt.get();
                if (!"admin".equals(row.getString("role"))) {
                    return Future.failedFuture(ServiceException.forbidden("Forbidden"));
                }
                return Future.succeededFuture(row.getUUID("id"));
            });
    }

    private JsonObject rowToJson(Row row) {
        JsonObject result = new JsonObject()
            .put("summary",      row.getString("summary"))
            .put("skills",       new JsonArray(row.getString("skills")))
            .put("education",    new JsonArray(row.getString("education")))
            .put("workEntries",  new JsonArray(row.getString("work_entries")))
            .put("extraLinks",   new JsonArray(row.getString("extra_links")))
            .put("updatedAt",    row.getOffsetDateTime("updated_at").toString());
        String designJson = row.getString("design");
        if (designJson != null) result.put("design", new JsonObject(designJson));
        return result;
    }

    /** Calls companyRepo.findOrCreate for any company name appearing in work entries or education. */
    private Future<Void> ensureCompaniesExist(JsonArray workEntries, JsonArray education) {
        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < workEntries.size(); i++) {
            JsonObject entry = workEntries.getJsonObject(i);
            String company = entry.getString("company");
            if (company != null && !company.isBlank()) {
                chain = chain.compose(v -> companyRepo.findOrCreate(company.trim(), null, null).mapEmpty());
            }
        }
        for (int i = 0; i < education.size(); i++) {
            JsonObject entry = education.getJsonObject(i);
            String school = entry.getString("school");
            if (school != null && !school.isBlank()) {
                chain = chain.compose(v -> companyRepo.findOrCreate(school.trim(), null, null).mapEmpty());
            }
        }
        return chain;
    }
}

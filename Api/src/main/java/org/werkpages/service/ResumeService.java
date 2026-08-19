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

    private Future<UUID> resolveUserId(String auth0Id) {
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> opt.isPresent()
                ? Future.succeededFuture(opt.get())
                : Future.failedFuture(ServiceException.unauthorized("User not found"))
            );
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

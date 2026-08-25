package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;

import org.werkpages.repository.CompanyRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Read side of the Industries feature: the browse listing (one tile per industry with aggregate
 * stats) and a single-industry profile (stats + the companies within it). Classification/back-fill
 * lives in {@link IndustryClassificationJob}; this service only reads.
 */
public class IndustryService {

    private final CompanyRepository        companyRepo;
    private final Function<String, String> logoResolver;

    public IndustryService(CompanyRepository companyRepo, Function<String, String> logoResolver) {
        this.companyRepo  = companyRepo;
        this.logoResolver = logoResolver;
    }

    /** All industries that have at least one approved/ghost manager, with per-industry stats. */
    public Future<JsonObject> getIndustryListing() {
        return companyRepo.findIndustryListing().map(rows -> {
            JsonArray data = new JsonArray();
            for (Row row : rows) {
                String industry = row.getString("industry");
                if (industry == null || industry.isBlank()) continue;
                data.add(new JsonObject()
                    .put("industry",     industry)
                    .put("slug",         IndustryTaxonomy.slug(industry))
                    .put("companyCount", row.getLong("company_count"))
                    .put("managerCount", row.getLong("manager_count"))
                    .put("totalReviews", row.getLong("total_reviews"))
                    .put("avgRating",    row.getBigDecimal("avg_rating")));
            }
            return new JsonObject().put("data", data);
        });
    }

    /** One industry (resolved from its slug): headline stats plus the companies inside it. */
    public Future<JsonObject> getIndustryProfile(String slug) {
        String industry = IndustryTaxonomy.fromSlug(slug);
        if (industry == null) return Future.failedFuture(ServiceException.notFound("Industry not found"));

        return companyRepo.findIndustryStats(industry).compose(statsOpt ->
            companyRepo.findManagerCategoriesByIndustry(industry).compose(catRows ->
            companyRepo.findCompaniesByIndustry(industry).map(rows -> {
                JsonArray companies = new JsonArray();
                for (Row row : rows) {
                    String name = row.getString("name");
                    if (name == null || name.isBlank()) continue;
                    String storedLogo = row.getString("logo_url");
                    String logoUrl = (storedLogo != null && !storedLogo.isBlank())
                        ? storedLogo : logoResolver.apply(name);
                    JsonObject co = new JsonObject()
                        .put("name",         name)
                        .put("slug",         row.getString("slug"))
                        .put("managerCount", row.getLong("manager_count"))
                        .put("totalReviews", row.getLong("total_reviews"))
                        .put("avgRating",    row.getBigDecimal("avg_rating"));
                    if (logoUrl != null && !logoUrl.isBlank()) co.put("logoUrl", logoUrl);
                    companies.add(co);
                }
                JsonObject result = new JsonObject()
                    .put("industry",         industry)
                    .put("slug",             slug)
                    .put("companyCount",     statsOpt.map(r -> r.getLong("company_count")).orElse(0L))
                    .put("managerCount",     statsOpt.map(r -> r.getLong("manager_count")).orElse(0L))
                    .put("totalReviews",     statsOpt.map(r -> r.getLong("total_reviews")).orElse(0L))
                    .put("avgRating",        statsOpt.map(r -> r.getBigDecimal("avg_rating")).orElse(null))
                    .put("categoryAverages", aggregateCategoryAverages(catRows))
                    .put("companies",        companies);
                return result;
            })));
    }

    /**
     * Averages each of the 10 review categories across every manager in the industry — an
     * unweighted mean of per-manager category averages, matching the company-profile breakdown.
     */
    private static JsonObject aggregateCategoryAverages(Iterable<Row> catRows) {
        Map<String, Double>  sum   = new HashMap<>();
        Map<String, Integer> count = new HashMap<>();
        for (Row row : catRows) {
            Object catObj = row.getValue("category_averages");
            if (catObj == null) continue;
            JsonObject cats = catObj instanceof JsonObject
                ? (JsonObject) catObj : new JsonObject(catObj.toString());
            for (String key : cats.fieldNames()) {
                Object val = cats.getValue(key);
                if (val instanceof Number) {
                    sum.merge(key, ((Number) val).doubleValue(), Double::sum);
                    count.merge(key, 1, Integer::sum);
                }
            }
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Double> e : sum.entrySet()) {
            int cnt = count.get(e.getKey());
            out.put(e.getKey(), Math.round(e.getValue() / cnt * 10.0) / 10.0);
        }
        return out;
    }
}

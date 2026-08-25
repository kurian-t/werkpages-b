package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;

import org.werkpages.repository.CompanyRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time / on-demand back-fill that classifies every directory-visible company that still has a
 * NULL industry, in batches, via {@link AnthropicClient#classifyIndustries}. Going forward, new
 * companies are classified automatically on creation (see CompanyRepository.classifyIfNeeded), so
 * this only needs to be triggered once (admin: POST /api/admin/industries/classify) and again if a
 * bulk import ever adds many companies at once.
 */
public class IndustryClassificationJob {

    private static final int BATCH_SIZE  = 25;   // companies per Anthropic call
    private static final int MAX_BATCHES = 400;  // hard safety cap (~10k companies)

    private final CompanyRepository companyRepo;
    private final AnthropicClient   anthropic;

    public IndustryClassificationJob(CompanyRepository companyRepo, AnthropicClient anthropic) {
        this.companyRepo = companyRepo;
        this.anthropic   = anthropic;
    }

    /** Classify all remaining unclassified companies. Resolves with {classified, remaining}. */
    public Future<JsonObject> run() {
        return runBatch(0, 0);
    }

    private Future<JsonObject> runBatch(int batchesDone, int classifiedSoFar) {
        if (batchesDone >= MAX_BATCHES) return summary(classifiedSoFar);

        return companyRepo.findUnclassified(BATCH_SIZE).compose(rows -> {
            List<AnthropicClient.CompanyToClassify> batch = new ArrayList<>();
            for (Row r : rows) {
                batch.add(new AnthropicClient.CompanyToClassify(
                    r.getLong("id"), r.getString("name"), r.getString("domain")));
            }
            if (batch.isEmpty()) return summary(classifiedSoFar);

            return anthropic.classifyIndustries(batch).compose(map -> {
                List<Future<Void>> updates = new ArrayList<>();
                for (AnthropicClient.CompanyToClassify c : batch) {
                    // Coerce anything the model skipped to "Other" so the row leaves the
                    // unclassified set and the loop is guaranteed to terminate.
                    String industry = map.getOrDefault(c.id(), "Other");
                    updates.add(companyRepo.updateIndustry(c.id(), industry));
                }
                return Future.all(updates)
                    .compose(v -> runBatch(batchesDone + 1, classifiedSoFar + batch.size()));
            });
        });
    }

    private Future<JsonObject> summary(int classified) {
        return companyRepo.countUnclassified()
            .map(remaining -> new JsonObject().put("classified", classified).put("remaining", remaining));
    }
}

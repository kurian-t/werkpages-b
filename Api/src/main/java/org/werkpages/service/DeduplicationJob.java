package org.werkpages.service;

import io.vertx.core.Future;
import org.werkpages.repository.MergeSuggestionsRepository;
import org.werkpages.repository.MergeSuggestionsRepository.CandidatePair;
import org.werkpages.service.AnthropicClient.EvaluationResult;
import org.werkpages.service.AnthropicClient.ManagerProfile;

import java.util.Iterator;
import java.util.List;

/**
 * Hourly job that finds manager pairs with similar names and asks Claude
 * whether they are likely the same person. Results are stored in
 * merge_suggestions for admin review.
 *
 * Pairs are processed sequentially (not in parallel) to respect API rate limits
 * and keep costs predictable.
 */
public class DeduplicationJob {

    private final MergeSuggestionsRepository repo;
    private final AnthropicClient            anthropic;

    public DeduplicationJob(MergeSuggestionsRepository repo, AnthropicClient anthropic) {
        this.repo      = repo;
        this.anthropic = anthropic;
    }

    public void run() {
        System.out.println("[DeduplicationJob] Starting run...");
        repo.findCandidatePairs()
            .onSuccess(pairs -> {
                if (pairs.isEmpty()) {
                    System.out.println("[DeduplicationJob] No candidate pairs found.");
                    return;
                }
                System.out.println("[DeduplicationJob] Evaluating " + pairs.size() + " candidate pair(s)...");
                processSequentially(pairs.iterator(), 0);
            })
            .onFailure(err -> System.err.println("[DeduplicationJob] Failed to fetch candidate pairs: " + err.getMessage()));
    }

    private void processSequentially(Iterator<CandidatePair> it, int processed) {
        if (!it.hasNext()) {
            System.out.println("[DeduplicationJob] Done. Processed " + processed + " pair(s).");
            return;
        }

        CandidatePair pair = it.next();
        ManagerProfile a   = pair.profileA();
        ManagerProfile b   = pair.profileB();

        anthropic.evaluatePair(a, b)
            .compose(result -> store(a, b, result))
            .onSuccess(v -> processSequentially(it, processed + 1))
            .onFailure(err -> {
                System.err.println("[DeduplicationJob] Error evaluating pair (" + a.id() + "," + b.id() + "): " + err.getMessage());
                // Continue with next pair even on error
                processSequentially(it, processed + 1);
            });
    }

    private Future<Void> store(ManagerProfile a, ManagerProfile b, EvaluationResult result) {
        System.out.printf("[DeduplicationJob] (%d,%d) → %s: %s%n",
            a.id(), b.id(), result.confidence(), result.reason());
        return repo.upsert(a.id(), b.id(), result.confidence(), result.reason(),
            a.reviewsCount(), b.reviewsCount());
    }
}

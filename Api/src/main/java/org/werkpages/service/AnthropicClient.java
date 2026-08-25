package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Anthropic Messages API using Java's built-in HttpClient.
 * Uses claude-haiku for low-cost, high-volume manager deduplication calls.
 */
public class AnthropicClient {

    private static final String API_URL     = "https://api.anthropic.com/v1/messages";
    private static final String MODEL       = "claude-haiku-4-5-20251001";
    private static final String API_VERSION = "2023-06-01";
    private static final int    MAX_TOKENS  = 100;

    public record ManagerProfile(
        long   id,
        String name,
        String company,
        String title,
        String country,
        String state,
        String city,
        int    reviewsCount
    ) {}

    public record EvaluationResult(String confidence, String reason) {}

    /** A company to be classified into an industry. */
    public record CompanyToClassify(long id, String name, String domain) {}

    private final HttpClient httpClient;
    private final String     apiKey;
    private final Vertx      vertx;

    public AnthropicClient(Vertx vertx, String apiKey) {
        this.vertx      = vertx;
        this.apiKey     = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    public Future<EvaluationResult> evaluatePair(ManagerProfile a, ManagerProfile b) {
        Promise<EvaluationResult> promise = Promise.promise();

        String body = new JsonObject()
            .put("model", MODEL)
            .put("max_tokens", MAX_TOKENS)
            .put("messages", new JsonArray().add(
                new JsonObject()
                    .put("role", "user")
                    .put("content", buildPrompt(a, b))
            ))
            .encode();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .timeout(Duration.ofSeconds(20))
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        // Run the blocking HTTP call on a Vert.x worker thread
        vertx.executeBlocking(() -> {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Anthropic API error " + response.statusCode() + ": " + response.body());
            }
            String text = new JsonObject(response.body())
                .getJsonArray("content")
                .getJsonObject(0)
                .getString("text", "")
                .strip();

            String[] lines      = text.split("\n", 2);
            String   confidence = lines[0].trim().toUpperCase();
            String   reason     = lines.length > 1 ? lines[1].trim() : "";

            if (!confidence.equals("SAME") && !confidence.equals("LIKELY_SAME") && !confidence.equals("DIFFERENT")) {
                confidence = "DIFFERENT";
                reason = "Unexpected model response: " + text;
            }
            return new EvaluationResult(confidence, reason);
        }).onSuccess(promise::complete).onFailure(promise::fail);

        return promise.future();
    }

    /**
     * Classify a batch of companies into the fixed {@link IndustryTaxonomy}. Returns a map of
     * company id -> canonical industry. Any id the model omits or mislabels is left out / coerced
     * to "Other" by the caller. Batch to keep cost/latency low (one API call per batch).
     */
    public Future<Map<Long, String>> classifyIndustries(List<CompanyToClassify> companies) {
        if (companies == null || companies.isEmpty()) return Future.succeededFuture(Map.of());
        Promise<Map<Long, String>> promise = Promise.promise();

        StringBuilder list = new StringBuilder();
        for (CompanyToClassify c : companies) {
            list.append("- id=").append(c.id()).append(" | ").append(c.name());
            if (c.domain() != null && !c.domain().isBlank()) list.append(" (").append(c.domain()).append(")");
            list.append('\n');
        }
        String prompt = """
            Classify each company below into exactly ONE industry from this fixed list:
            %s

            Companies:
            %s
            Rules:
            - Use only industries from the list above, spelled exactly.
            - If it doesn't clearly fit any, use "Other".
            - Base the decision on the company name/domain and general world knowledge.

            Respond with ONLY a JSON array (no prose, no markdown fences) of the form:
            [{"id": 123, "industry": "Technology"}, {"id": 456, "industry": "Retail"}]
            Include every company id exactly once.
            """.formatted(String.join(", ", IndustryTaxonomy.ALL), list.toString());

        String body = new JsonObject()
            .put("model", MODEL)
            .put("max_tokens", 4096)
            .put("messages", new JsonArray().add(
                new JsonObject().put("role", "user").put("content", prompt)))
            .encode();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .timeout(Duration.ofSeconds(60))
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        vertx.executeBlocking(() -> {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Anthropic API error " + response.statusCode() + ": " + response.body());
            }
            String text = new JsonObject(response.body())
                .getJsonArray("content").getJsonObject(0).getString("text", "").strip();
            // Be tolerant of stray prose / code fences: extract the JSON array span.
            int start = text.indexOf('[');
            int end   = text.lastIndexOf(']');
            if (start < 0 || end < start) {
                throw new RuntimeException("No JSON array in classification response: " + text);
            }
            JsonArray arr = new JsonArray(text.substring(start, end + 1));
            Map<Long, String> result = new HashMap<>();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.getJsonObject(i);
                if (o.getValue("id") == null) continue;
                result.put(o.getLong("id"), IndustryTaxonomy.normalize(o.getString("industry")));
            }
            return result;
        }).onSuccess(promise::complete).onFailure(promise::fail);

        return promise.future();
    }

    private static String buildPrompt(ManagerProfile a, ManagerProfile b) {
        return """
            Are these two manager profiles likely the same person?

            Manager A:
              Name: %s
              Title: %s
              Company: %s
              Location: %s
              Reviews on file: %d

            Manager B:
              Name: %s
              Title: %s
              Company: %s
              Location: %s
              Reviews on file: %d

            Reply with exactly one of: SAME, LIKELY_SAME, or DIFFERENT on the first line.
            Give a single-sentence reason on the second line.
            """.formatted(
                a.name(), a.title(), a.company(), formatLocation(a), a.reviewsCount(),
                b.name(), b.title(), b.company(), formatLocation(b), b.reviewsCount()
            );
    }

    private static String formatLocation(ManagerProfile p) {
        StringBuilder sb = new StringBuilder();
        if (p.city()    != null && !p.city().isBlank())    sb.append(p.city()).append(", ");
        if (p.state()   != null && !p.state().isBlank())   sb.append(p.state()).append(", ");
        if (p.country() != null && !p.country().isBlank()) sb.append(p.country());
        String loc = sb.toString().replaceAll(",\\s*$", "").trim();
        return loc.isEmpty() ? "Unknown" : loc;
    }
}

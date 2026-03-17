package org.ratemymanager.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimitHandler {

    private final int maxRequests;
    private final long windowMs;
    private final ConcurrentHashMap<String, ArrayDeque<Long>> requestLog = new ConcurrentHashMap<>();

    public RateLimitHandler(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    public void handle(RoutingContext ctx) {
        String ip = getClientIp(ctx);
        long now = System.currentTimeMillis();
        long windowStart = now - windowMs;

        requestLog.compute(ip, (key, timestamps) -> {
            if (timestamps == null) timestamps = new ArrayDeque<>();
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            timestamps.addLast(now);
            return timestamps;
        });

        // Periodically evict idle IPs to prevent unbounded memory growth.
        // Only runs ~1% of the time to avoid lock contention.
        if (Math.random() < 0.01) {
            requestLog.entrySet().removeIf(e -> {
                ArrayDeque<Long> ts = e.getValue();
                return ts.isEmpty() || ts.peekLast() < windowStart;
            });
        }

        int count = requestLog.get(ip).size();
        if (count > maxRequests) {
            ctx.response()
               .setStatusCode(429)
               .putHeader("Content-Type", "application/json")
               .putHeader("Retry-After", String.valueOf(windowMs / 1000))
               .end(new JsonObject().put("error", "Too many requests. Please try again later.").encode());
        } else {
            ctx.next();
        }
    }

    private String getClientIp(RoutingContext ctx) {
        String forwarded = ctx.request().getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return ctx.request().remoteAddress().host();
    }
}

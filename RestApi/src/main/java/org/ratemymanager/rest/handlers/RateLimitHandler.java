package org.ratemymanager.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimitHandler {

    private final int maxRequests;
    private final long windowMs;
    private final ConcurrentHashMap<String, ArrayDeque<Long>> requestLog = new ConcurrentHashMap<>();

    // Hard cap on tracked IPs — triggers an immediate full eviction sweep
    // to prevent unbounded memory growth even under a spoofing attack.
    private static final int MAX_TRACKED_IPS = 50_000;

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

        // If the map has grown too large, sweep immediately regardless of the
        // probabilistic check below. This is the primary OOM defence.
        if (requestLog.size() > MAX_TRACKED_IPS) {
            requestLog.entrySet().removeIf(e -> {
                ArrayDeque<Long> ts = e.getValue();
                return ts.isEmpty() || ts.peekLast() < windowStart;
            });
        }

        // Routine probabilistic eviction (~1% of requests) to keep the map tidy
        // under normal traffic without adding lock pressure on every request.
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

    /**
     * Returns the real client IP.
     *
     * When the direct connection comes from a trusted proxy (private/loopback),
     * we first check the CF-Connecting-IP header set by Cloudflare, which always
     * reflects the real visitor IP and cannot be spoofed from outside Cloudflare.
     * Falls back to the first entry of X-Forwarded-For for non-Cloudflare proxies.
     */
    private String getClientIp(RoutingContext ctx) {
        String remoteIp = ctx.request().remoteAddress().host();
        if (isTrustedProxy(remoteIp)) {
            String cfIp = ctx.request().getHeader("CF-Connecting-IP");
            if (cfIp != null && !cfIp.isBlank()) {
                return cfIp.trim();
            }
            String forwarded = ctx.request().getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return remoteIp;
    }

    /** Returns true if the IP belongs to a private or loopback range. */
    private boolean isTrustedProxy(String ip) {
        if (ip == null) return false;
        return ip.startsWith("10.")       // RFC 1918 10.0.0.0/8
            || ip.startsWith("192.168.") // RFC 1918 192.168.0.0/16
            || ip.startsWith("127.")     // loopback
            || ip.equals("::1")          // IPv6 loopback
            || isIn172PrivateRange(ip);  // RFC 1918 172.16.0.0/12
    }

    private boolean isIn172PrivateRange(String ip) {
        if (!ip.startsWith("172.")) return false;
        try {
            int second = Integer.parseInt(ip.split("\\.")[1]);
            return second >= 16 && second <= 31;
        } catch (Exception e) {
            return false;
        }
    }
}

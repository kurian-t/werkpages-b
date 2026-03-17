package org.ratemymanager.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.util.regex.Pattern;

public final class ValidationUtils {

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final Pattern LINKEDIN_PATTERN =
        Pattern.compile("^https?://(www\\.)?linkedin\\.com/.*$", Pattern.CASE_INSENSITIVE);

    private ValidationUtils() {}

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static boolean exceedsLength(String value, int max) {
        return value != null && value.length() > max;
    }

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    public static boolean isValidLinkedinUrl(String url) {
        return url != null && LINKEDIN_PATTERN.matcher(url).matches();
    }

    /** Ratings must be between 1 and 5 inclusive. */
    public static boolean isValidRating(Double value) {
        return value != null && value >= 1.0 && value <= 5.0;
    }

    public static void badRequest(RoutingContext ctx, String message) {
        ctx.response()
           .setStatusCode(400)
           .putHeader("Content-Type", "application/json")
           .end(new JsonObject().put("error", message).encode());
    }
}

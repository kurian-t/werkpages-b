package org.werkpages.service;

/**
 * The small checks every write path repeats.
 *
 * {@code isBlank} was defined privately in two classes and the "is this field too long" test was
 * written out eighteen times in ManagerService alone, each one restating the same shape:
 *
 * <pre>if (x != null &amp;&amp; x.length() &gt; 100) return Future.failedFuture(badRequest("X too long"));</pre>
 *
 * Eighteen copies of a shape is eighteen chances to use the wrong bound, or to forget the null
 * guard, or to word the message differently from the field beside it - and all three had already
 * happened.
 *
 * Returns the problem as a String rather than a failed Future, so a caller can gather several
 * checks and fail once, and so this stays usable from methods with different Future types.
 */
public final class Fields {

    private Fields() {}

    /** The length limit almost every free-text column carries. */
    public static final int DEFAULT_MAX = 100;

    /** Null-safe blank test. */
    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Environment variable, or the default when unset or blank. */
    public static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return isBlank(value) ? defaultValue : value;
    }

    /**
     * "{label} too long", or null when it fits.
     *
     * A null value passes: absence is the business of {@link #required}, and conflating the two
     * is how an optional field ends up reported as too long when it was simply not supplied.
     */
    public static String maxLength(String value, int max, String label) {
        return value != null && value.length() > max ? label + " too long" : null;
    }

    /** {@link #maxLength} at {@link #DEFAULT_MAX}. */
    public static String maxLength(String value, String label) {
        return maxLength(value, DEFAULT_MAX, label);
    }

    /** "{label} is required", or null when present. */
    public static String required(String value, String label) {
        return isBlank(value) ? label + " is required" : null;
    }

    /**
     * The first problem among these, or null when there is none.
     *
     * Lets a method state all of a request's field rules together instead of interleaving them
     * with the work, which is what made eighteen near-identical lines easy to miss.
     */
    public static String firstProblem(String... problems) {
        for (String p : problems) {
            if (p != null) return p;
        }
        return null;
    }
}

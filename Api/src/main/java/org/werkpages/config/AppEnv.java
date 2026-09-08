package org.werkpages.config;

/**
 * Which environment this process is running in.
 *
 * Separate from {@code USE_AWS_SECRETS}, which used to serve as both. That variable means "where
 * do secrets come from", and it had quietly become the switch for seven unrelated security
 * behaviours: the CORS origin, the auth cookie's Secure flag in two places, database SSL, the
 * HSTS header, and whether Swagger is published.
 *
 * The failure mode was the problem. If that variable were ever unset, misspelled, or lost in a
 * redeploy, the application would still boot and serve traffic perfectly - while accepting CORS
 * from localhost, sending auth cookies without Secure, connecting to the database without TLS,
 * and publishing its own API schema. No error, no log, nothing to notice.
 *
 * So this one is required and has no default. A process that cannot say which environment it is
 * in does not start. Refusing to boot is a loud failure that someone fixes in a minute; silently
 * dropping TLS is a quiet one that nobody finds.
 */
public enum AppEnv {
    DEVELOPMENT,
    PRODUCTION;

    private static final String VAR = "APP_ENV";

    /**
     * Reads APP_ENV, or throws.
     *
     * Deliberately fatal. The alternative - defaulting to development - is exactly the silent
     * downgrade this class exists to remove, and defaulting to production would break every
     * developer's machine instead.
     */
    public static AppEnv current() {
        return parse(System.getenv(VAR));
    }

    /** The parsing, split out so it can be tested without setting process environment. */
    static AppEnv parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                VAR + " is not set. It must be 'production' or 'development'. "
                + "This controls CORS, cookie Secure, database TLS, HSTS and whether Swagger is "
                + "served, so there is no safe default to fall back to.");
        }
        return switch (raw.trim().toLowerCase()) {
            case "production", "prod" -> PRODUCTION;
            case "development", "dev", "local", "test" -> DEVELOPMENT;
            default -> throw new IllegalStateException(
                VAR + "='" + raw + "' is not a value I recognise. Use 'production' or 'development'.");
        };
    }

    public boolean isProduction() {
        return this == PRODUCTION;
    }
}

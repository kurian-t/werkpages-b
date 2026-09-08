package org.werkpages.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * APP_ENV decides CORS, cookie Secure, database TLS, HSTS and whether Swagger is published.
 *
 * The behaviour worth pinning is the refusal. Every one of those controls used to hang off
 * USE_AWS_SECRETS, so an unset variable silently produced a running server with no TLS to the
 * database and no Secure on its auth cookie. The fix is only a fix if absence stays fatal.
 */
class AppEnvTest {

    @Test
    void unsetIsFatal() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AppEnv.parse(null));
        assertTrue(e.getMessage().contains("APP_ENV"), "the message names the variable to set");
    }

    @Test
    void blankIsFatal() {
        assertThrows(IllegalStateException.class, () -> AppEnv.parse("   "));
    }

    @Test
    void anUnrecognisedValueIsFatalRatherThanTreatedAsDevelopment() {
        // The dangerous near-miss: a typo, or "staging", must not quietly fall back to
        // development and drop TLS.
        assertThrows(IllegalStateException.class, () -> AppEnv.parse("staging"));
        assertThrows(IllegalStateException.class, () -> AppEnv.parse("PRODUCTIN"));
    }

    @Test
    void recognisesProduction() {
        assertTrue(AppEnv.parse("production").isProduction());
        assertTrue(AppEnv.parse("PRODUCTION").isProduction());
        assertTrue(AppEnv.parse("  prod  ").isProduction());
    }

    @Test
    void recognisesDevelopment() {
        for (String v : new String[]{"development", "dev", "local", "test", "DEV"}) {
            assertFalse(AppEnv.parse(v).isProduction(), v + " is not production");
        }
    }
}

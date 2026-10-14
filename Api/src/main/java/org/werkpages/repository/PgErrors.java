package org.werkpages.repository;

import io.vertx.pgclient.PgException;

/**
 * PostgreSQL error classification, read from SQLSTATE.
 *
 * <p>These checks used to be written inline against the exception's MESSAGE, as
 * {@code err.getMessage().contains("23505")}. That happens to work today only because the Vert.x
 * driver appends the code in parentheses when it formats the text, which is a presentation detail
 * of a specific driver version and not part of any contract. It is also fooled by content: a
 * company genuinely called "23505 Holdings" would make an unrelated failure look like a unique
 * violation, and the recovery path would then run against an error it was never meant to handle.
 *
 * <p>SQLSTATE is the contract. PostgreSQL defines the codes, the driver exposes them through
 * {@link PgException#getSqlState()}, and nothing about the message text can change what they mean.
 */
final class PgErrors {

    private PgErrors() { }

    /** SQLSTATE 23505 - a UNIQUE index or constraint rejected the write. */
    static boolean isUniqueViolation(Throwable err) {
        return err instanceof PgException pg && "23505".equals(pg.getSqlState());
    }

    /**
     * A unique violation raised by a specific index or constraint.
     *
     * <p>The name is matched on a fragment rather than in full because one rule can be enforced by
     * differently named objects - the manager slug is guarded by {@code managers_slug_idx} and by
     * a trigger-generated index in older databases - and recovery must behave the same for both.
     *
     * @param nameFragment appears in the constraint name, for example {@code "slug"}
     */
    static boolean isUniqueViolationOn(Throwable err, String nameFragment) {
        if (!(err instanceof PgException pg)) return false;
        if (!"23505".equals(pg.getSqlState())) return false;
        String constraint = pg.getConstraint();
        return constraint != null && constraint.contains(nameFragment);
    }
}

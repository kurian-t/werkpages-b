package org.werkpages.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What an erased rating's byline has to look like, asserted in one place.
 *
 * <p><b>Why this is a helper and not a literal in each test.</b> Eleven assertions across four
 * integration test classes read {@code assertEquals("Anonymous User", author)}. That string is no
 * longer written anywhere in the application: account erasure mints a handle through
 * {@link org.werkpages.repository.Pseudonyms}, because a byline worn by no live account told every
 * reader of a profile which rating belonged to somebody who had deleted themselves, which is the
 * one fact the erasure exists to remove.
 *
 * <p>So those eleven assertions had stopped describing a requirement and started pinning the
 * disclosure in place. Giving each of them its own hand-written replacement is how four classes
 * end up asserting four slightly different things about one rule, so the rule is written here.
 *
 * <p><b>Shape, not merely inequality.</b> Asserting only that the byline is no longer
 * {@code Anonymous User} would also pass for {@code anon-1821}, and the design note in
 * {@code UserRepository} rejects exactly that: a name of that shape still announces that the row
 * was anonymised, and its number gives a reader something to correlate on. The requirement is that
 * the byline is indistinguishable from a handle somebody chose, so the form is what gets checked.
 */
final class ErasedByline {

    private ErasedByline() {}

    /**
     * The byline the erasure used to write, and must never write again.
     *
     * <p>Still named here deliberately. It is the predicate V107's backfill uses to find the rows
     * erased by the old code, so production rows carrying it are real, and the tests that model
     * legacy data still need the string.
     */
    static final String OLD_DISCLOSING_LITERAL = "Anonymous User";

    /**
     * {@code Pseudonyms.mint()}'s format: Adjective + Animal + two digits, e.g. KindPanda11. The
     * first digit cannot be zero because mint() draws {@code 10 + nextInt(90)}.
     */
    private static final Pattern MINTED = Pattern.compile("[A-Z][a-z]+[A-Z][a-z]+[1-9][0-9]");

    /** Asserts one erased rating's byline is a minted stand-in rather than the old disclosure. */
    static void assertMinted(String author, String what) {
        assertNotNull(author, what + ": an erased rating still needs a byline to render");
        assertFalse(OLD_DISCLOSING_LITERAL.equals(author),
            what + ": the byline is still the literal '" + OLD_DISCLOSING_LITERAL + "', which is "
            + "worn by no live account and so discloses that this rating's author deleted "
            + "themselves");
        assertTrue(MINTED.matcher(author).matches(),
            what + ": expected a minted handle shaped like KindPanda11, indistinguishable from a "
            + "handle somebody chose, but got '" + author + "'");
    }

    /**
     * Asserts one account's erased ratings did not all end up under the same handle.
     *
     * <p>ONE NAME PER ROW, not one per account. A handle shared across an erased account's ratings
     * relinks them: a reader could see that one person rated these four managers, which is a fact
     * the erasure removed everywhere else. The old code wrote a single literal with one
     * {@code SET}, so "all identical" is precisely the behaviour that was removed.
     *
     * <p><b>Why this does not assert that all of them differ.</b> {@code mint()} draws from
     * 15 x 14 x 90 = 18,900 names and is allowed to repeat; two of one person's ratings colliding
     * is legitimate, not a defect. Demanding that all of them differ would therefore fail roughly once in
     * twenty thousand runs, and a suite that fails for a correct reason teaches people to re-run
     * it. More than one distinct handle across six ratings cannot happen under the old
     * one-name-per-account behaviour, which is what this has to catch.
     */
    static void assertNotAllIdentical(List<String> bylines, String what) {
        assertTrue(bylines.size() >= 2,
            what + ": needs at least two ratings to say anything about sharing");
        Set<String> distinct = new HashSet<>(bylines);
        assertTrue(distinct.size() >= 2,
            what + ": all " + bylines.size() + " erased ratings carry the single byline '"
            + bylines.get(0) + "'. One name per account relinks the ratings the erasure "
            + "separated, which is the behaviour Pseudonyms.mint() per row replaced");
        for (String b : bylines) assertMinted(b, what);
    }
}

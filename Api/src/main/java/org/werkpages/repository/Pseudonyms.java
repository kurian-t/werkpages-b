package org.werkpages.repository;

import java.security.SecureRandom;
import java.util.Random;

/**
 * The one place a stand-in byline is invented.
 *
 * <p>The vocabulary and the format lived in {@code ManagerService} as private statics, which was
 * fine while only the service minted names. Account erasure needs one too, and it runs in
 * {@link UserRepository}, so the choice was to expose the service's private generator to a
 * repository, duplicate the word lists, or move them somewhere both can see. A third copy of the
 * vocabulary is the one option that guarantees drift: there are already two lists in this codebase
 * (this one and {@code ReviewRepository}'s seed words), and a byline minted from a different
 * vocabulary would be distinguishable from the others, which defeats the whole purpose.
 *
 * <p>{@code ManagerService.generatePseudonym()} now delegates here and keeps its signature, so its
 * ten call sites are untouched.
 *
 * <h2>The format is load-bearing</h2>
 *
 * <p>{@code AdjectiveAnimal} plus two digits, mirroring the frontend's {@code generateUsername()}.
 * It has to be indistinguishable from a handle somebody chose, because that is the only thing
 * standing between a reader and the knowledge that a particular rating's author deleted their
 * account. A name that announces itself as a stand-in, {@code Anonymous User} or
 * {@code anon-1821}, discloses exactly what the erasure exists to hide.
 *
 * <p>{@link SecureRandom}, not {@code Random}. These names are the only thing attached to an erased
 * rating, and a predictable sequence would let somebody who knows when an account was deleted
 * recognise the bylines minted at that moment.
 */
public final class Pseudonyms {

    private Pseudonyms() {}

    private static final String[] ADJ = {
        "Brave", "Swift", "Bold", "Calm", "Keen", "Wise", "Fair", "Kind",
        "Sharp", "Quiet", "Clear", "Warm", "Cool", "Bright", "Loyal"
    };
    private static final String[] ANIMAL = {
        "Falcon", "Tiger", "Eagle", "Wolf", "Bison", "Crane", "Lynx",
        "Otter", "Raven", "Gecko", "Heron", "Panda", "Finch", "Moose"
    };
    private static final Random RNG = new SecureRandom();

    /** A fresh stand-in byline, e.g. {@code KindPanda11}. */
    public static String mint() {
        return ADJ[RNG.nextInt(ADJ.length)]
             + ANIMAL[RNG.nextInt(ANIMAL.length)]
             + (10 + RNG.nextInt(90));
    }
}

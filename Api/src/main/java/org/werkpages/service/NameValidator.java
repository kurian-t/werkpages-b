package org.werkpages.service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guards against junk, placeholder, and offensive names in the find-or-create flow.
 */
public class NameValidator {

    private static final int MIN_PART_LENGTH  = 2;
    private static final int MAX_PART_LENGTH  = 50;
    /** A name part must carry real letters, not just punctuation ("--", "''" are not names). */
    private static final int MIN_PART_LETTERS = 2;

    private static final Pattern VALID_NAME_PART = Pattern.compile("[a-zA-ZÀ-ÖØ-öø-ÿ'\\-\\s]+");
    /** "Bo--b", "O''Brien" — doubled punctuation is junk, never a real name. */
    private static final Pattern REPEATED_PUNCTUATION = Pattern.compile("['\\-]{2,}");

    private static final Set<String> FAKE_FULL_NAMES = Set.of(
        "john doe", "jane doe", "john smith", "jane smith",
        "bob builder", "bob smith", "bob jones",
        "test user", "test manager", "test person", "test test",
        "first last", "firstname lastname", "your name", "my name",
        "no name", "some name", "some guy", "some person",
        "sue cunt", "jack ass", "sue me", "no one",
        "admin admin", "admin user", "null null", "undefined undefined",
        "foo bar", "foo foo", "bar baz",
        "homer simpson", "bart simpson",
        "mickey mouse", "donald duck", "daffy duck",
        "bugs bunny", "elmer fudd", "porky pig",
        "peter griffin", "joe swanson",
        "bruce wayne", "clark kent", "tony stark", "steve rogers",
        "james bond", "ethan hunt",
        "john wick", "jack reacher", "jason bourne"
    );

    private static final Set<String> FAKE_FIRST_NAMES = Set.of(
        "test", "fake", "admin", "null", "undefined", "anonymous",
        "unknown", "none", "nope", "asdf", "qwerty", "aaaa", "xxxx",
        "blah", "lorem", "ipsum"
    );

    private static final Set<String> PROFANITY = Set.of(
        "fuck", "shit", "cunt", "bitch", "asshole", "bastard", "prick",
        "dick", "cock", "pussy", "whore", "slut", "nigger", "nigga",
        "fag", "faggot", "retard", "kike", "spic", "chink", "twat",
        "wanker", "arse", "bollocks", "motherfucker", "fucker"
    );

    public record ValidationResult(boolean valid, String reason) {
        static ValidationResult ok()                  { return new ValidationResult(true, null); }
        static ValidationResult fail(String reason)   { return new ValidationResult(false, reason); }
    }

    public static ValidationResult validate(String firstName, String lastName,
                                            String title, String company, String country) {
        ValidationResult r;
        if ((r = validateNamePart(firstName, "First name")).valid == false) return r;
        if ((r = validateNamePart(lastName,  "Last name")).valid  == false) return r;
        if ((r = validateField(title,   "Title",   100)).valid    == false) return r;
        if ((r = validateField(company, "Company", 100)).valid    == false) return r;
        if ((r = validateField(country, "Country", 100)).valid    == false) return r;

        return validateNameContent(firstName, lastName);
    }

    /**
     * Name rules in full, optional fields checked only when they are present.
     *
     * For capturing a partial search. Someone who typed a name and a company but no job title has
     * still told us something an admin can act on, and {@link #validate} would reject them for the
     * field they did not fill. The name itself is held to exactly the same standard - a capture is
     * still a manager record, so the rules that keep junk out of the directory all apply.
     */
    public static ValidationResult validatePartial(String firstName, String lastName,
                                                   String title, String company, String country) {
        ValidationResult r;
        if ((r = validateNamePart(firstName, "First name")).valid == false) return r;
        if ((r = validateNamePart(lastName,  "Last name")).valid  == false) return r;
        if (title   != null && !title.isBlank()   && (r = validateField(title,   "Title",   100)).valid == false) return r;
        if (company != null && !company.isBlank() && (r = validateField(company, "Company", 100)).valid == false) return r;
        if (country != null && !country.isBlank() && (r = validateField(country, "Country", 100)).valid == false) return r;

        return validateNameContent(firstName, lastName);
    }

    /**
     * Name-only validation, for callers that submit a single "First Last" name and validate the
     * other fields themselves (e.g. the add-manager form). Applies exactly the same name rules as
     * {@link #validate} — every path that creates a manager must go through one of the two.
     */
    public static ValidationResult validateFullName(String firstName, String lastName) {
        ValidationResult r;
        if ((r = validateNamePart(firstName, "First name")).valid == false) return r;
        if ((r = validateNamePart(lastName,  "Last name")).valid  == false) return r;
        return validateNameContent(firstName, lastName);
    }

    private static ValidationResult validateNameContent(String firstName, String lastName) {
        String firstLower = firstName.trim().toLowerCase();
        String lastLower  = lastName.trim().toLowerCase();
        String fullLower  = firstLower + " " + lastLower;

        if (FAKE_FULL_NAMES.contains(fullLower))
            return ValidationResult.fail("This doesn't appear to be a real person's name");

        if (FAKE_FIRST_NAMES.contains(firstLower) || FAKE_FIRST_NAMES.contains(lastLower))
            return ValidationResult.fail("This doesn't appear to be a real person's name");

        for (String word : PROFANITY) {
            if (firstLower.contains(word) || lastLower.contains(word))
                return ValidationResult.fail("Name contains disallowed content");
        }

        return ValidationResult.ok();
    }

    private static ValidationResult validateNamePart(String value, String field) {
        if (value == null || value.isBlank())
            return ValidationResult.fail(field + " is required");
        // Collapse internal runs of whitespace so "Mary   Ann" is judged as "Mary Ann".
        String trimmed = value.trim().replaceAll("\\s+", " ");
        if (trimmed.length() < MIN_PART_LENGTH)
            return ValidationResult.fail(field + " is too short");
        if (trimmed.length() > MAX_PART_LENGTH)
            return ValidationResult.fail(field + " is too long");
        if (!VALID_NAME_PART.matcher(trimmed).matches())
            return ValidationResult.fail(field + " contains invalid characters");
        // Letters-only whitelist above still admits punctuation-only junk ("--", "' '") and
        // fragments like "A-" that are a single letter padded out to the minimum length.
        if (trimmed.chars().filter(Character::isLetter).count() < MIN_PART_LETTERS)
            return ValidationResult.fail(field + " must contain at least " + MIN_PART_LETTERS + " letters");
        if (!Character.isLetter(trimmed.charAt(0)) || !Character.isLetter(trimmed.charAt(trimmed.length() - 1)))
            return ValidationResult.fail(field + " must start and end with a letter");
        if (REPEATED_PUNCTUATION.matcher(trimmed).find())
            return ValidationResult.fail(field + " contains invalid characters");
        return ValidationResult.ok();
    }

    private static ValidationResult validateField(String value, String field, int maxLen) {
        if (value == null || value.isBlank())
            return ValidationResult.fail(field + " is required");
        if (value.trim().length() > maxLen)
            return ValidationResult.fail(field + " is too long");
        return ValidationResult.ok();
    }
}

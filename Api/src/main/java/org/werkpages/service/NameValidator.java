package org.werkpages.service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guards against junk, placeholder, and offensive names in the find-or-create flow.
 */
public class NameValidator {

    private static final int MIN_PART_LENGTH = 2;
    private static final int MAX_PART_LENGTH = 50;

    private static final Pattern VALID_NAME_PART = Pattern.compile("[a-zA-ZÀ-ÖØ-öø-ÿ'\\-\\s]+");

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
        String trimmed = value.trim();
        if (trimmed.length() < MIN_PART_LENGTH)
            return ValidationResult.fail(field + " is too short");
        if (trimmed.length() > MAX_PART_LENGTH)
            return ValidationResult.fail(field + " is too long");
        if (!VALID_NAME_PART.matcher(trimmed).matches())
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

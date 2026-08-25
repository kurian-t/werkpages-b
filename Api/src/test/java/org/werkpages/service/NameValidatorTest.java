package org.werkpages.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class NameValidatorTest {

    private static final String VALID_TITLE   = "Engineering Manager";
    private static final String VALID_COMPANY = "Acme Corp";
    private static final String VALID_COUNTRY = "Canada";

    // ── valid names ───────────────────────────────────────────────────────────

    @ParameterizedTest(name = "\"{0}\" \"{1}\" should be valid")
    @CsvSource({
        "Sarah,     Chen",
        "Olivia,    Park",
        "José,      García",
        "Renée,     Dupont",
        "Siobhan,   O'Brien",
        "Mary-Jane, Watson",
        "Al,        Kim",
        "Anne-Marie, Nguyen",
    })
    void validRealNames_pass(String first, String last) {
        var result = NameValidator.validate(first, last, VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertTrue(result.valid(), "Expected valid but got: " + result.reason());
    }

    // ── length limits ─────────────────────────────────────────────────────────

    @Test
    void firstName_tooShort_fails() {
        var r = NameValidator.validate("A", "Smith", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
        assertTrue(r.reason().contains("too short"));
    }

    @Test
    void lastName_tooShort_fails() {
        var r = NameValidator.validate("John", "A", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
        assertTrue(r.reason().contains("too short"));
    }

    @Test
    void firstName_tooLong_fails() {
        String longName = "A".repeat(51);
        var r = NameValidator.validate(longName, "Smith", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
        assertTrue(r.reason().contains("too long"));
    }

    @Test
    void lastName_tooLong_fails() {
        String longName = "B".repeat(51);
        var r = NameValidator.validate("John", longName, VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
        assertTrue(r.reason().contains("too long"));
    }

    @Test
    void firstName_exactlyMinLength_passes() {
        var r = NameValidator.validate("Al", "Brown", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertTrue(r.valid());
    }

    @Test
    void firstName_exactlyMaxLength_passes() {
        String maxName = "A".repeat(50);
        var r = NameValidator.validate(maxName, "Smith", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertTrue(r.valid());
    }

    // ── blank / null inputs ───────────────────────────────────────────────────

    @Test
    void blankFirstName_fails() {
        var r = NameValidator.validate("", "Smith", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
        assertTrue(r.reason().contains("required"));
    }

    @Test
    void blankLastName_fails() {
        var r = NameValidator.validate("John", "   ", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
        assertTrue(r.reason().contains("required"));
    }

    @Test
    void nullFirstName_fails() {
        var r = NameValidator.validate(null, "Smith", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
    }

    @Test
    void nullLastName_fails() {
        var r = NameValidator.validate("John", null, VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
    }

    @Test
    void blankTitle_fails() {
        var r = NameValidator.validate("John", "Smith", "", VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
        assertTrue(r.reason().contains("required"));
    }

    @Test
    void blankCompany_fails() {
        var r = NameValidator.validate("John", "Smith", VALID_TITLE, "   ", VALID_COUNTRY);
        assertFalse(r.valid());
        assertTrue(r.reason().contains("required"));
    }

    @Test
    void blankCountry_fails() {
        var r = NameValidator.validate("John", "Smith", VALID_TITLE, VALID_COMPANY, "");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("required"));
    }

    // ── invalid characters ────────────────────────────────────────────────────

    @ParameterizedTest(name = "name with \"{0}\" fails character check")
    @ValueSource(strings = {
        "John1",     // digit
        "John@",     // at sign
        "John!",     // exclamation
        "John.Smith", // period
        "John_Smith", // underscore
        "John+Smith", // plus
        "John=Smith", // equals
    })
    void invalidCharacters_fail(String firstName) {
        var r = NameValidator.validate(firstName, "Smith", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid(), "Expected failure for: " + firstName);
        assertTrue(r.reason().contains("invalid characters"));
    }

    // ── structural junk that the character whitelist alone lets through ───────

    @ParameterizedTest(name = "punctuation-only first name \"{0}\" fails")
    @ValueSource(strings = { "--", "''", "-'", "' '", "---" })
    void punctuationOnlyName_fails(String firstName) {
        var r = NameValidator.validate(firstName, "Smith", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid(), "Expected failure for: " + firstName);
        assertTrue(r.reason().contains("at least 2 letters"), "Got: " + r.reason());
    }

    @ParameterizedTest(name = "single letter padded to length \"{0}\" fails")
    @ValueSource(strings = { "A-", "-A", "A'", "'A", "A ", " A" })
    void singleLetterPaddedWithPunctuation_fails(String firstName) {
        var r = NameValidator.validate(firstName, "Smith", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid(), "Expected failure for: " + firstName);
    }

    @ParameterizedTest(name = "name with leading/trailing punctuation \"{0}\" fails")
    @ValueSource(strings = { "-Bob", "Bob-", "'Bob", "Bob'" })
    void leadingOrTrailingPunctuation_fails(String firstName) {
        var r = NameValidator.validate(firstName, "Smith", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid(), "Expected failure for: " + firstName);
        assertTrue(r.reason().contains("start and end with a letter"), "Got: " + r.reason());
    }

    @ParameterizedTest(name = "doubled punctuation \"{0}\" fails")
    @ValueSource(strings = { "Bo--b", "O''Brien", "Mary--Jane" })
    void repeatedPunctuation_fails(String firstName) {
        var r = NameValidator.validate(firstName, "Smith", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid(), "Expected failure for: " + firstName);
        assertTrue(r.reason().contains("invalid characters"), "Got: " + r.reason());
    }

    @Test
    void internalWhitespaceIsCollapsed_multiWordNameStillValid() {
        // "Mary   Ann" is a typo, not junk — it must not be rejected as invalid characters.
        var r = NameValidator.validate("Mary   Ann", "Fitzgerald", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertTrue(r.valid(), "Expected valid but got: " + r.reason());
    }

    // ── validateFullName: the name-only entry point used by the add-manager form ──

    @Test
    void validateFullName_realName_passes() {
        assertTrue(NameValidator.validateFullName("Margaret", "Williams").valid());
    }

    @ParameterizedTest(name = "validateFullName rejects \"{0} {1}\"")
    @CsvSource({
        "A,      B",
        "A,      Smith",
        "John,   B",
        "'--',   '--'",
        "John3,  Smith",
        "Jane,   Smith",
        "Fuck,   Smith",
    })
    void validateFullName_junk_fails(String first, String last) {
        var r = NameValidator.validateFullName(first, last);
        assertFalse(r.valid(), "Expected rejection for: " + first + " " + last);
        assertNotNull(r.reason());
    }

    @Test
    void validateFullName_missingLastName_fails() {
        var r = NameValidator.validateFullName("Margaret", "");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("required"), "Got: " + r.reason());
    }

    @Test
    void validateFullName_appliesSameRulesAsValidate() {
        // The two entry points must never diverge on the name itself.
        String[][] names = {{"A", "B"}, {"--", "--"}, {"Jane", "Smith"}, {"Margaret", "Williams"}};
        for (String[] n : names) {
            boolean viaFullName = NameValidator.validateFullName(n[0], n[1]).valid();
            boolean viaValidate = NameValidator
                .validate(n[0], n[1], VALID_TITLE, VALID_COMPANY, VALID_COUNTRY).valid();
            assertEquals(viaValidate, viaFullName,
                "validateFullName and validate disagree on: " + n[0] + " " + n[1]);
        }
    }

    // ── fake full names ───────────────────────────────────────────────────────

    @ParameterizedTest(name = "fake full name \"{0} {1}\" is rejected")
    @CsvSource({
        "John,     Doe",
        "Jane,     Doe",
        "John,     Smith",
        "Jane,     Smith",
        "Test,     User",
        "Test,     Manager",
        "Foo,      Bar",
        "First,    Last",
        "Homer,    Simpson",
        "Mickey,   Mouse",
        "Bruce,    Wayne",
        "Jason,    Bourne",
        "John,     Wick",
    })
    void fakeFullNames_rejected(String first, String last) {
        var r = NameValidator.validate(first, last, VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid(), "Expected rejection for: " + first + " " + last);
        assertTrue(r.reason().contains("real person"));
    }

    @ParameterizedTest(name = "fake full name check is case-insensitive: \"{0} {1}\"")
    @CsvSource({
        "JOHN,  DOE",
        "john,  doe",
        "John,  doe",
        "HOMER, SIMPSON",
    })
    void fakeFullNames_caseInsensitive(String first, String last) {
        var r = NameValidator.validate(first, last, VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
    }

    // ── fake first/last names ─────────────────────────────────────────────────

    @ParameterizedTest(name = "fake first name \"{0}\" is rejected")
    @ValueSource(strings = {
        "test", "fake", "admin", "null", "undefined",
        "anonymous", "unknown", "none", "asdf", "qwerty",
        "aaaa", "xxxx", "blah", "lorem", "ipsum",
    })
    void fakeFirstNames_rejected(String fakeFirst) {
        var r = NameValidator.validate(fakeFirst, "Johnson", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid(), "Expected rejection for first name: " + fakeFirst);
        assertTrue(r.reason().contains("real person"));
    }

    @ParameterizedTest(name = "fake last name \"{0}\" is rejected")
    @ValueSource(strings = {
        "test", "fake", "admin", "null", "undefined",
        "anonymous", "unknown", "none", "asdf",
    })
    void fakeLastNames_rejected(String fakeLast) {
        var r = NameValidator.validate("John", fakeLast, VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid(), "Expected rejection for last name: " + fakeLast);
        assertTrue(r.reason().contains("real person"));
    }

    @ParameterizedTest(name = "fake name check is case-insensitive: \"{0}\"")
    @ValueSource(strings = {"TEST", "Fake", "ADMIN", "NULL"})
    void fakeFirstNames_caseInsensitive(String fake) {
        var r = NameValidator.validate(fake, "Johnson", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
    }

    // ── profanity ─────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "profanity in first name \"{0}\" is rejected")
    @ValueSource(strings = { "Fuck", "fuck", "FUCK", "Shit", "shit", "Bitch" })
    void profanityInFirstName_rejected(String name) {
        var r = NameValidator.validate(name, "Johnson", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid(), "Expected rejection for: " + name);
        assertTrue(r.reason().contains("disallowed content"));
    }

    @ParameterizedTest(name = "profanity in last name \"{0}\" is rejected")
    @ValueSource(strings = { "Fucker", "Shithead", "Bastard", "Asshole" })
    void profanityInLastName_rejected(String name) {
        var r = NameValidator.validate("John", name, VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid(), "Expected rejection for: " + name);
        assertTrue(r.reason().contains("disallowed content"));
    }

    @Test
    void profanityAsSubstring_rejected() {
        // Checks that "contains" is used, not exact match
        var r = NameValidator.validate("Mcfuck", "Johnson", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
        assertTrue(r.reason().contains("disallowed content"));
    }

    // ── field length limits (title / company / country) ───────────────────────

    @Test
    void title_tooLong_fails() {
        String longTitle = "X".repeat(101);
        var r = NameValidator.validate("John", "Smith", longTitle, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
        assertTrue(r.reason().contains("too long"));
    }

    @Test
    void company_tooLong_fails() {
        String longCompany = "X".repeat(101);
        var r = NameValidator.validate("John", "Smith", VALID_TITLE, longCompany, VALID_COUNTRY);
        assertFalse(r.valid());
        assertTrue(r.reason().contains("too long"));
    }

    @Test
    void country_tooLong_fails() {
        String longCountry = "X".repeat(101);
        var r = NameValidator.validate("John", "Smith", VALID_TITLE, VALID_COMPANY, longCountry);
        assertFalse(r.valid());
        assertTrue(r.reason().contains("too long"));
    }

    // ── validation result convenience ─────────────────────────────────────────

    @Test
    void validResult_reasonIsNull() {
        var r = NameValidator.validate("Sarah", "Chen", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertTrue(r.valid());
        assertNull(r.reason());
    }

    @Test
    void invalidResult_reasonIsNonNull() {
        var r = NameValidator.validate("", "Smith", VALID_TITLE, VALID_COMPANY, VALID_COUNTRY);
        assertFalse(r.valid());
        assertNotNull(r.reason());
        assertFalse(r.reason().isBlank());
    }
}

package org.ratemymanager.unit;

import org.junit.jupiter.api.Test;
import org.ratemymanager.service.NameValidator;
import org.ratemymanager.service.NameValidator.ValidationResult;

import static org.junit.jupiter.api.Assertions.*;

class NameValidatorTest {

    private static ValidationResult validate(String first, String last) {
        return NameValidator.validate(first, last, "Engineer", "Acme Corp", "Canada");
    }

    @Test
    void validName_passes() {
        assertTrue(validate("Alice", "Johnson").valid());
    }

    @Test
    void fakeFullName_fails() {
        assertFalse(validate("John", "Doe").valid());
    }

    @Test
    void fakeFirstName_fails() {
        assertFalse(validate("test", "Smith").valid());
    }

    @Test
    void fakeLastName_fails() {
        // Covers line 69: FAKE_FIRST_NAMES.contains(lastLower) — right side of OR
        assertFalse(validate("John", "fake").valid());
    }

    @Test
    void profanityInFirstName_fails() {
        assertFalse(validate("fuck", "Smith").valid());
    }

    @Test
    void profanityInLastName_fails() {
        // Covers line 73: lastLower.contains(word)
        assertFalse(validate("John", "shit").valid());
    }

    @Test
    void firstNameBlank_fails() {
        assertFalse(validate("", "Smith").valid());
    }

    @Test
    void firstNameTooShort_fails() {
        assertFalse(validate("A", "Smith").valid());
    }

    @Test
    void firstNameTooLong_fails() {
        // Covers lines 84-86: validateNamePart length > MAX_PART_LENGTH (50)
        String tooLong = "A".repeat(51);
        assertFalse(validate(tooLong, "Smith").valid());
    }

    @Test
    void titleTooLong_fails() {
        // Covers lines 94-96: validateField length > 100
        String tooLong = "X".repeat(101);
        ValidationResult r = NameValidator.validate("Alice", "Johnson", tooLong, "Acme Corp", "Canada");
        assertFalse(r.valid());
    }

    @Test
    void companyTooLong_fails() {
        // Covers lines 94-96: validateField length > 100
        String tooLong = "X".repeat(101);
        ValidationResult r = NameValidator.validate("Alice", "Johnson", "Engineer", tooLong, "Canada");
        assertFalse(r.valid());
    }

    @Test
    void validSpecialCharsInFirstName_passes() {
        assertTrue(validate("Jean-Pierre", "Dupont").valid());
    }

    @Test
    void digitsInFirstName_fails() {
        assertFalse(validate("Al1ce", "Johnson").valid());
    }
}

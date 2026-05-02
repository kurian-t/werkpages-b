package org.ratemymanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ManagerServiceNameNormalizationTest {

    // ── toProperNameCase ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @CsvSource({
        "TIM COOK,             Tim Cook",
        "tim cook,             Tim Cook",
        "tIM cOOk,             Tim Cook",
        "SATYA NADELLA,        Satya Nadella",
        "elon musk,            Elon Musk",
        "JENNIFER ANISTON,     Jennifer Aniston",
    })
    void basicCasing(String input, String expected) {
        assertEquals(expected.strip(), ManagerService.toProperNameCase(input.strip()));
    }

    @Test
    void alreadyCorrect_unchanged() {
        assertEquals("Tim Cook", ManagerService.toProperNameCase("Tim Cook"));
    }

    @Test
    void hyphenatedName() {
        assertEquals("Mary-Jane Watson", ManagerService.toProperNameCase("mary-jane watson"));
        assertEquals("Mary-Jane Watson", ManagerService.toProperNameCase("MARY-JANE WATSON"));
    }

    @Test
    void apostropheName_obrienStyle() {
        assertEquals("O'Brien", ManagerService.toProperNameCase("o'brien"));
        assertEquals("O'Brien", ManagerService.toProperNameCase("O'BRIEN"));
        assertEquals("O'Brien", ManagerService.toProperNameCase("o'BRIEN"));
    }

    @Test
    void apostropheName_fullName() {
        assertEquals("Siobhan O'Brien", ManagerService.toProperNameCase("SIOBHAN O'BRIEN"));
    }

    @Test
    void extraWhitespace_trimmed() {
        assertEquals("Tim Cook", ManagerService.toProperNameCase("  tim   cook  "));
    }

    @Test
    void singleWord() {
        assertEquals("Cher", ManagerService.toProperNameCase("CHER"));
    }

    @Test
    void nullInput_returnsNull() {
        assertNull(ManagerService.toProperNameCase(null));
    }

    @Test
    void emptyString_returnsEmpty() {
        assertEquals("", ManagerService.toProperNameCase(""));
        assertEquals("", ManagerService.toProperNameCase("   "));
    }

    @Test
    void hyphenatedLastName_multiPart() {
        assertEquals("Sarah Smith-Jones", ManagerService.toProperNameCase("SARAH SMITH-JONES"));
    }

    @Test
    void threePartName() {
        assertEquals("Mary Lou Retton", ManagerService.toProperNameCase("MARY LOU RETTON"));
    }
}

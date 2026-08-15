package org.werkpages.rest.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompanyLogoUtilsTest {

    // ── Normal company names ──────────────────────────────────────────────────

    @Test
    void companyDomain_plainName_appendsDotCom() {
        assertEquals("shutterstock.com", CompanyLogoUtils.companyDomain("Shutterstock"));
    }

    @Test
    void companyDomain_multiWordName_stripsSpaces() {
        assertEquals("bankofamerica.com", CompanyLogoUtils.companyDomain("Bank of America"));
    }

    @Test
    void companyDomain_stripsIncSuffix() {
        assertEquals("acme.com", CompanyLogoUtils.companyDomain("Acme Inc"));
    }

    @Test
    void companyDomain_stripsCorpSuffix() {
        assertEquals("lemonade.com", CompanyLogoUtils.companyDomain("Lemonade Corp"));
    }

    @Test
    void companyDomain_stripsLtdSuffix() {
        assertEquals("widgets.com", CompanyLogoUtils.companyDomain("Widgets Ltd"));
    }

    @Test
    void companyDomain_lowercasesResult() {
        assertEquals("tesla.com", CompanyLogoUtils.companyDomain("TESLA"));
    }

    // ── Domain-shaped names — the bug that was fixed ──────────────────────────

    @Test
    void companyDomain_pricelineDotCom_returnsAsDomain() {
        // Before the fix: "Priceline.com" → "pricelinecom.com" (broken)
        // After the fix:  "Priceline.com" → "priceline.com"
        assertEquals("priceline.com", CompanyLogoUtils.companyDomain("Priceline.com"));
    }

    @Test
    void companyDomain_bookingDotCom_returnsAsDomain() {
        assertEquals("booking.com", CompanyLogoUtils.companyDomain("Booking.com"));
    }

    @Test
    void companyDomain_carsDotCom_returnsAsDomain() {
        assertEquals("cars.com", CompanyLogoUtils.companyDomain("Cars.com"));
    }

    @Test
    void companyDomain_domainNameIsCaseInsensitive() {
        assertEquals("priceline.com", CompanyLogoUtils.companyDomain("PRICELINE.COM"));
    }

    // ── resolveLogoUrl integration ────────────────────────────────────────────

    @Test
    void resolveLogoUrl_pricelineDotCom_doesNotProduceDoubledTld() {
        String url = CompanyLogoUtils.resolveLogoUrl("Priceline.com");
        assertNotNull(url);
        assertFalse(url.contains("pricelinecom.com"), "URL must not contain broken domain: " + url);
        assertTrue(url.contains("priceline.com"), "URL must contain correct domain: " + url);
    }

    @Test
    void resolveLogoUrl_bookingDotCom_doesNotProduceDoubledTld() {
        String url = CompanyLogoUtils.resolveLogoUrl("Booking.com");
        assertNotNull(url);
        assertFalse(url.contains("bookingcom.com"), "URL must not contain broken domain: " + url);
        assertTrue(url.contains("booking.com"), "URL must contain correct domain: " + url);
    }

    @Test
    void resolveLogoUrl_knownCompanyInDomainMap_usesMapEntry() {
        String url = CompanyLogoUtils.resolveLogoUrl("Google");
        assertNotNull(url);
        assertTrue(url.contains("google.com"), url);
    }

    @Test
    void resolveLogoUrl_nullInput_returnsNull() {
        assertNull(CompanyLogoUtils.resolveLogoUrl(null));
    }

    @Test
    void resolveLogoUrl_blankInput_returnsNull() {
        assertNull(CompanyLogoUtils.resolveLogoUrl("   "));
    }
}

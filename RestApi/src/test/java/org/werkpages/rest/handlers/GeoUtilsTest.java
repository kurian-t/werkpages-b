package org.werkpages.rest.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeoUtilsTest {

    @Test
    void countryName_mapsKnownCodesToAppDisplayNames() {
        assertEquals("United States",  GeoUtils.countryName("US"));
        assertEquals("Canada",         GeoUtils.countryName("CA"));
        assertEquals("United Kingdom", GeoUtils.countryName("GB"));
        assertEquals("Germany",        GeoUtils.countryName("DE"));
    }

    @Test
    void countryName_isCaseInsensitiveAndTrims() {
        assertEquals("Canada", GeoUtils.countryName("ca"));
        assertEquals("Canada", GeoUtils.countryName("  CA  "));
    }

    @Test
    void countryName_returnsNullForCloudflarePseudoCodes() {
        assertNull(GeoUtils.countryName("XX")); // Cloudflare: unknown
        assertNull(GeoUtils.countryName("T1")); // Cloudflare: Tor
    }

    @Test
    void countryName_returnsNullForInvalidOrBlankInput() {
        assertNull(GeoUtils.countryName(null));
        assertNull(GeoUtils.countryName(""));
        assertNull(GeoUtils.countryName("ZZ"));      // not a real ISO country
        assertNull(GeoUtils.countryName("Canada"));  // not a 2-letter code
    }
}

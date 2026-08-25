package org.werkpages.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the industry slug/normalisation helpers that drive routing and classification. */
class IndustryTaxonomyTest {

    @Test
    void slug_handlesSpacesAndAmpersands() {
        assertEquals("financial-services", IndustryTaxonomy.slug("Financial Services"));
        assertEquals("media-and-entertainment", IndustryTaxonomy.slug("Media & Entertainment"));
        assertEquals("technology", IndustryTaxonomy.slug("Technology"));
    }

    @Test
    void fromSlug_roundTripsEveryTaxonomyEntry() {
        for (String industry : IndustryTaxonomy.ALL) {
            assertEquals(industry, IndustryTaxonomy.fromSlug(IndustryTaxonomy.slug(industry)),
                "slug round-trip must resolve back to the same industry for: " + industry);
        }
    }

    @Test
    void fromSlug_unknownReturnsNull() {
        assertNull(IndustryTaxonomy.fromSlug("not-a-real-industry"));
        assertNull(IndustryTaxonomy.fromSlug(null));
    }

    @Test
    void normalize_coercesUnknownToOther_andMatchesCaseInsensitively() {
        assertEquals("Technology", IndustryTaxonomy.normalize("technology"));
        assertEquals("Technology", IndustryTaxonomy.normalize("  Technology  "));
        assertEquals("Financial Services", IndustryTaxonomy.normalize("FINANCIAL SERVICES"));
        assertEquals("Other", IndustryTaxonomy.normalize("Underwater Basket Weaving"));
        assertEquals("Other", IndustryTaxonomy.normalize(null));
    }
}

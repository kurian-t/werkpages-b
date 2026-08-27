package org.werkpages.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The fixed, canonical set of industries every company is classified into.
 *
 * <p>Keeping the taxonomy closed (rather than letting the model invent labels) means the
 * Industries browse page has a stable, de-duplicated set of buckets, and slugs/URLs are stable.
 * The AI classifier is instructed to pick exactly one of {@link #ALL}; anything it returns that
 * isn't in the set is coerced to {@code "Other"} via {@link #normalize(String)}.
 */
public final class IndustryTaxonomy {

    private IndustryTaxonomy() {}

    public static final List<String> ALL = List.of(
        "Technology",
        "Financial Services",
        "Healthcare",
        "Pharmaceuticals & Biotech",
        "Insurance",
        "Retail",
        "Manufacturing",
        "Education",
        "Government & Public Sector",
        "Media & Entertainment",
        "Hospitality & Tourism",
        "Food & Beverage",
        "Transportation & Logistics",
        "Energy & Utilities",
        "Real Estate",
        "Construction",
        "Professional Services",
        "Telecommunications",
        "Nonprofit",
        "Agriculture",
        "Automotive",
        "Aerospace & Defense",
        "Legal",
        "Mining & Metals",
        "Consumer Services",
        "Other"
    );

    /** Lower-cased name -> canonical name, for tolerant matching of model output. */
    private static final Map<String, String> BY_LOWER =
        ALL.stream().collect(Collectors.toMap(s -> s.toLowerCase(), s -> s));

    /** Slug -> canonical name, for resolving /industries/:slug routes. */
    private static final Map<String, String> BY_SLUG =
        ALL.stream().collect(Collectors.toMap(IndustryTaxonomy::slug, s -> s));

    /** URL slug for an industry, e.g. "Financial Services" -> "financial-services". */
    public static String slug(String industry) {
        if (industry == null) return null;
        return industry.toLowerCase()
            .replace("&", "and")
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
    }

    /** Canonical industry name for a slug, or null if the slug is unknown. */
    public static String fromSlug(String slug) {
        return slug == null ? null : BY_SLUG.get(slug);
    }

    /**
     * Coerce arbitrary model output to a canonical taxonomy value. Trims, matches
     * case-insensitively, and falls back to "Other" for anything unrecognised.
     */
    public static String normalize(String raw) {
        if (raw == null) return "Other";
        String v = raw.trim();
        String canon = BY_LOWER.get(v.toLowerCase());
        return canon != null ? canon : "Other";
    }
}

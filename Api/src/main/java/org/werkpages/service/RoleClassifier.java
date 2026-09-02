package org.werkpages.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Classifies an already-normalized job title into a role family and a seniority level.
 *
 * <p>Input is the output of {@code normalize_role_title()} in V49 — lower case, punctuation
 * stripped, abbreviations expanded. This class never normalizes; the database owns that so every
 * write path produces the same string, and duplicating the logic here would let the two drift.
 *
 * <p><b>Family and seniority are separate on purpose.</b> "Senior Engineering Manager" carries two
 * independent facts. Collapsing them into one enum would need every family multiplied by every
 * level, and would make "show me all Directors" impossible to ask.
 *
 * <p><b>Matching is by keyword, never by edit distance.</b> "Product Manager" and "Project
 * Manager" are one character apart and are different jobs; fuzzy matching would merge them and
 * quietly corrupt every number computed from the result. Everything here is exact token matching.
 */
public final class RoleClassifier {

    private RoleClassifier() {}

    // ── Families ──────────────────────────────────────────────────────────────
    // Ordered: the first family whose keyword appears wins. More specific families come first so
    // that "engineering program manager" lands in engineering rather than project_management.

    private static final Map<String, List<String>> FAMILY_KEYWORDS = new LinkedHashMap<>();
    static {
        FAMILY_KEYWORDS.put("engineering", List.of(
            "engineering", "engineer", "software", "developer", "development", "devops",
            "platform", "infrastructure", "backend", "frontend", "full stack", "mobile",
            "quality assurance", "site reliability", "architect"));
        FAMILY_KEYWORDS.put("data", List.of(
            "data", "analytics", "analyst", "machine learning", "artificial intelligence",
            "business intelligence", "statistician"));
        FAMILY_KEYWORDS.put("design", List.of(
            "design", "designer", "user experience", "ux", "ui", "creative", "brand studio"));
        FAMILY_KEYWORDS.put("product", List.of(
            "product"));
        FAMILY_KEYWORDS.put("it", List.of(
            "information technology", "systems administrator", "network", "help desk",
            "service desk", "security", "sysadmin"));
        FAMILY_KEYWORDS.put("sales", List.of(
            "sales", "account executive", "account manager", "business development",
            "revenue", "partnerships"));
        FAMILY_KEYWORDS.put("marketing", List.of(
            "marketing", "growth", "communications", "content", "seo", "demand generation",
            "public relations"));
        FAMILY_KEYWORDS.put("customer_support", List.of(
            "customer success", "customer support", "customer service", "support",
            "client services", "call centre", "call center"));
        FAMILY_KEYWORDS.put("finance", List.of(
            "finance", "financial", "accounting", "accountant", "controller", "treasury",
            "audit", "payroll", "procurement"));
        FAMILY_KEYWORDS.put("hr", List.of(
            "human resources", "people", "talent", "recruiting", "recruiter", "recruitment",
            "learning and development"));
        FAMILY_KEYWORDS.put("legal", List.of(
            "legal", "counsel", "compliance", "paralegal", "attorney"));
        FAMILY_KEYWORDS.put("project_management", List.of(
            "project", "program", "delivery", "scrum", "agile", "portfolio"));
        FAMILY_KEYWORDS.put("research", List.of(
            "research", "scientist", "laboratory", "clinical"));
        FAMILY_KEYWORDS.put("education", List.of(
            "teacher", "principal", "professor", "lecturer", "academic", "curriculum",
            "school", "faculty"));
        FAMILY_KEYWORDS.put("healthcare", List.of(
            "nurse", "nursing", "clinical services", "medical", "physician", "patient care",
            "pharmacy"));
        FAMILY_KEYWORDS.put("operations", List.of(
            "operations", "logistics", "supply chain", "warehouse", "manufacturing",
            "production", "facilities", "fleet"));
    }

    // ── Seniority ─────────────────────────────────────────────────────────────
    // Checked most senior first: "senior vice president" must not match the "senior" rule, and
    // "vice president of engineering operations" must not be caught by "operations".

    private static final List<Map.Entry<String, List<String>>> SENIORITY_RULES = List.of(
        Map.entry("executive", List.of(
            "chief executive officer", "chief technology officer", "chief financial officer",
            "chief operating officer", "chief marketing officer", "chief information officer",
            "chief product officer", "chief human resources officer", "chief",
            "president", "founder", "owner", "partner", "managing director")),
        Map.entry("vp", List.of(
            "vice president")),
        Map.entry("director", List.of(
            "director", "head of")),
        Map.entry("senior_manager", List.of(
            "senior manager", "senior engineering manager", "group manager",
            "general manager", "principal manager", "senior general manager")),
        Map.entry("manager", List.of(
            "manager", "supervisor", "foreman")),
        Map.entry("lead", List.of(
            "lead", "leader", "captain", "coordinator", "chief of staff"))
    );

    /**
     * "president" appears inside "vice president", and "managing director" inside neither cleanly.
     * These are checked before the executive rule so the more specific reading wins.
     */
    private static final List<String> NOT_EXECUTIVE_DESPITE_PRESIDENT = List.of(
        "vice president", "assistant vice president");

    // ── API ───────────────────────────────────────────────────────────────────

    /** Family for a normalized title, or empty when nothing matches confidently. */
    public static Optional<String> family(String normalizedTitle) {
        if (isBlank(normalizedTitle)) return Optional.empty();
        String title = padded(normalizedTitle);

        for (Map.Entry<String, List<String>> entry : FAMILY_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (containsPhrase(title, keyword)) return Optional.of(entry.getKey());
            }
        }

        // A title that says only how senior someone is ("senior manager", "director") is a real,
        // classifiable role with no domain attached — that is "general", not unknown.
        if (seniority(normalizedTitle).isPresent()) return Optional.of("general");

        return Optional.empty();
    }

    /** Seniority for a normalized title, or empty when nothing matches confidently. */
    public static Optional<String> seniority(String normalizedTitle) {
        if (isBlank(normalizedTitle)) return Optional.empty();
        String title = padded(normalizedTitle);

        for (Map.Entry<String, List<String>> rule : SENIORITY_RULES) {
            if ("executive".equals(rule.getKey()) && isVicePresident(title)) continue;
            for (String keyword : rule.getValue()) {
                if (containsPhrase(title, keyword)) return Optional.of(rule.getKey());
            }
        }
        return Optional.empty();
    }

    /** True when the rules could say nothing at all — the candidates for an AI pass later. */
    public static boolean isUnclassifiable(String normalizedTitle) {
        return family(normalizedTitle).isEmpty() && seniority(normalizedTitle).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isVicePresident(String paddedTitle) {
        return NOT_EXECUTIVE_DESPITE_PRESIDENT.stream().anyMatch(k -> containsPhrase(paddedTitle, k));
    }

    /**
     * Whole-word phrase match. Space padding on both sides means "lead" does not match "leader"
     * and "it" does not match "unit" — substring matching on short tokens is exactly how a
     * classifier starts producing nonsense.
     */
    private static boolean containsPhrase(String paddedTitle, String keyword) {
        return paddedTitle.contains(" " + keyword + " ");
    }

    private static String padded(String normalizedTitle) {
        return " " + normalizedTitle.trim() + " ";
    }

    private static boolean isBlank(String value) {
        return Fields.isBlank(value);
    }
}

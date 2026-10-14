package org.werkpages.service;

import java.text.Normalizer;
import java.util.*;

/**
 * THE manager-matching policy. One implementation, used by every search path.
 *
 * <p>Full specification: {@code .ai-corpus/reference/features/manager-search-flow.md} section 5a.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Five lookups used to decide independently whether a manager already existed - {@code search},
 * {@code searchInCompanies}, {@code findByCompanyExact}, {@code findByNameAndCompany} and
 * {@code findCapturedByNameAndCompany} - and every one of them made company a hard filter. A public
 * profile was therefore hidden whenever the employer typed differed, and the search then created a
 * row. One person accumulated eight rows that way; 21 people ended up with two public profiles each.
 *
 * <h2>The two rules that matter</h2>
 *
 * <p><b>Name first.</b> Discovery is by name alone. Company and title classify and rank the name
 * matches afterwards; they never filter them away.
 *
 * <p><b>Uncertainty never publishes.</b> A PLAUSIBLE name, company or title means we are not sure,
 * and not being sure is a reason to ask an admin, never a reason to publish a second person.
 */
public final class ManagerMatcher {

    private ManagerMatcher() {}

    public enum NameClass    { CONFIRMED, PLAUSIBLE, NONE }
    public enum TitleClass   { EQUIVALENT, PLAUSIBLE, DIFFERENT }
    public enum CompanyClass { VERIFIED, PLAUSIBLE, DIFFERENT }

    /** What the caller should do. See the decision matrix in section 5a. */
    public enum Outcome {
        RETURN_EXISTING,   // A: an employment pair matched
        QUEUE_REVIEW,      // B/C, and every uncertain case
        CREATE_GHOST,      // D or E, subject to the caller's eligibility rules
    }

    /** One employment record: a company and the title held AT that company. */
    public record Employment(Long companyId, String company, String title) {}

    public record Decision(Outcome outcome, NameClass nameClass,
                           CompanyClass bestCompany, TitleClass bestTitle, String reason) {}

    // ── normalisation ────────────────────────────────────────────────────────

    /** Lowercase, de-accented, punctuation folded. Hyphen and period act as separators. */
    public static String norm(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFKD);
        StringBuilder b = new StringBuilder();
        for (char c : t.toCharArray()) if (Character.getType(c) != Character.NON_SPACING_MARK) b.append(c);
        t = b.toString().toLowerCase(Locale.ROOT);
        /*
          Letters NFKD does not decompose, folded to match PostgreSQL unaccent() exactly.

          NFKD splits a composed character into a base plus combining marks, which the loop
          above drops - that is how e-acute becomes e. A letter that is not a composed form
          of anything, such as the Polish l-with-stroke, survives NFKD intact while unaccent()
          folds it anyway. The two sides then disagreed and a Polish name became unretrievable.
          These mappings are read off unaccent's own output, not guessed.
        */
        StringBuilder folded = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            String rep = EXTRA_FOLD.get(t.charAt(i));
            if (rep != null) folded.append(rep); else folded.append(t.charAt(i));
        }
        t = folded.toString();
        // The apostrophe JOINS rather than separates: O'Dell and ODell are one name, so it is
        // removed BEFORE other punctuation becomes a space. PostgreSQL does the same, in the
        // same order, inside manager_name_tokens().
        t = t.replace('\u2019', '\'').replace("'", "");
        t = t.replaceAll("[.\\-_]", " ");
        /*
          UNICODE LETTERS AND DIGITS, not ASCII.

          This was [^a-z0-9 ], which does not fold accents - it DELETES what it cannot represent.
          "Jose Garcia" with accents became {jos, "", garc, a}, and a CJK name became {"", "", ""}:
          no tokens at all, so the record could never be retrieved by any candidate query. Accent
          FOLDING is done deliberately above, by decomposing to NFKD and dropping the combining
          marks, so that a name typed without accents still finds the accented record. Stripping
          punctuation must not take the alphabet with it.

          The same mistake in its DuckDB form is why the location tokenizer carries \p{M}.
        */
        return t.replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Corporate suffixes carry no identity: "Acme Group" and "Acme" normalise alike. */
    public static String normCompany(String s) {
        List<String> out = new ArrayList<>();
        for (String w : norm(s).split(" ")) {
            if (!w.isEmpty() && !COMPANY_NOISE.contains(w)) out.add(w);
        }
        return String.join(" ", out);
    }

    /** Read off PostgreSQL unaccent()'s own output; see the fold loop in norm(). */
    private static final Map<Character, String> EXTRA_FOLD = Map.ofEntries(
        Map.entry('\u0142', "l"),  Map.entry('\u00f8', "o"),  Map.entry('\u00e6', "ae"),
        Map.entry('\u0153', "oe"), Map.entry('\u00df', "ss"), Map.entry('\u00fe', "th"),
        Map.entry('\u0111', "d"),  Map.entry('\u0131', "i"));

    private static final Set<String> COMPANY_NOISE = Set.of(
        "group", "inc", "incorporated", "ltd", "limited", "llc", "llp", "corp", "corporation", "co", "plc");

    /** Explicitly maintained. Never inferred, never fuzzy - that is the point of a list. */
    private static final Map<String, String> TITLE_ABBREV = Map.ofEntries(
        Map.entry("mgr", "manager"),      Map.entry("mgmt", "management"),
        Map.entry("sr", "senior"),        Map.entry("jr", "junior"),
        Map.entry("vp", "vice president"),Map.entry("svp", "senior vice president"),
        Map.entry("evp", "executive vice president"), Map.entry("dir", "director"),
        Map.entry("asst", "assistant"),   Map.entry("assoc", "associate"),
        Map.entry("eng", "engineering"),  Map.entry("ops", "operations"),
        Map.entry("admin", "administrative"), Map.entry("exec", "executive"),
        Map.entry("coord", "coordinator"),Map.entry("spec", "specialist"));

    public static String normTitle(String s) {
        List<String> out = new ArrayList<>();
        for (String w : norm(s).split(" ")) {
            if (w.isEmpty()) continue;
            out.add(TITLE_ABBREV.getOrDefault(w, w));
        }
        return String.join(" ", out);
    }

    // ── name ─────────────────────────────────────────────────────────────────

    private static final int PREFIX_MIN = 2;

    private enum PartClass { EXACT, PREFIX, TYPO, NONE }

    private static PartClass classifyPart(String typed, String stored) {
        return classifyPart(typed, stored, false);
    }

    /**
     * @param shareInitialCounts for the SURNAME only: a typed surname of two or three characters
     *        counts as partial when it shares its first letter with the stored one.
     *
     * <p>Carried over from the behaviour this matcher replaced. "Daniel Pa" against "Daniel
     * Perovic" is not a prefix and is nowhere near within edit distance - it is two letters that
     * merely share an initial - and the previous design deliberately surfaced it as a candidate
     * rather than minting a second Daniel. Dropping that leniency would have made the new matcher
     * CREATE a duplicate in a case the old one protected, which is the opposite of the point.
     *
     * <p>It only ever produces a candidate, never a match, so it cannot publish anything.
     */
    private static PartClass classifyPart(String typed, String stored, boolean shareInitialCounts) {
        String t = norm(typed), s = norm(stored);
        if (t.isEmpty() || s.isEmpty()) return PartClass.NONE;
        if (t.equals(s)) return PartClass.EXACT;
        // Bidirectional: the typed part may be shorter than what we hold ("Yi" -> "Yip") or
        // longer ("Smithers" against a stored "Smith"). Both are partial-name situations.
        if (t.length() >= PREFIX_MIN && t.length() < s.length() && s.startsWith(t)) return PartClass.PREFIX;
        if (s.length() >= PREFIX_MIN && s.length() < t.length() && t.startsWith(s)) return PartClass.PREFIX;
        int limit = s.length() >= 5 ? 2 : 1;
        /*
          Length gate before the edit distance. Two strings whose lengths differ by more than the
          limit cannot be within that distance - every edit changes the length by at most one - so
          this skips the matrix entirely and is exact, never a false negative.

          It matters because most candidates share one token and fail on the other: searching
          "John Smith" retrieves every John, and "smith" against "williams" would otherwise build a
          5x8 matrix for each one. Measured over 20,000 candidates this is the difference between
          65 ms and the figure in the benchmark below.
        */
        // TWO characters minimum. A single initial shares its letter with a large share of any
        // directory, so "Daniel P" would have surfaced every Daniel P-something as a candidate and
        // sent the search to review no matter who was typed. The rule this came from had the same
        // floor; porting it without the floor is how it got lost.
        if (shareInitialCounts && t.length() >= 2 && t.length() <= 3
                && t.charAt(0) == s.charAt(0)) return PartClass.PREFIX;
        if (Math.abs(t.length() - s.length()) > limit) return PartClass.NONE;
        if (s.length() >= 3 && damerau(t, s) <= limit) return PartClass.TYPO;
        return PartClass.NONE;
    }

    /**
     * At most ONE part may be inexact; the other must match exactly.
     *
     * <p>Without that rule "Jon Smith" would match "Jane Smythe", and a search box would start
     * proposing strangers. It is also what makes the SQL candidate net provably sufficient: since
     * one part is always exact, filtering on either exact token cannot miss a match.
     */
    public static NameClass classifyName(String typedFirst, String typedLast, String storedFullName) {
        String stored = norm(storedFullName);
        String[] tokens = stored.isEmpty() ? new String[0] : stored.split(" ");
        if (tokens.length < 2) return NameClass.NONE;

        /*
          EVERY SPLIT POINT IS TRIED, and the best result wins.

          A stored row holds one `name` column, while the form collects first and last separately.
          Reconstructing which is which from the text is guesswork, and any single rule gets a
          whole class of names wrong:

            split at the FIRST space  -> "Jean Luc Picard" reads as first="jean", last="luc picard"
            split at the LAST space   -> "Maria De La Cruz" reads as first="maria de la", last="cruz"

          Both are real naming conventions - compound given names and compound surnames - and
          neither is rarer than the other. So rather than pick a rule and be wrong about half of
          them, every division of the tokens is tested and the strongest classification is taken.
          "Maria De La Cruz" therefore matches a search for Maria / De La Cruz at the first split,
          and "Jean Luc Picard" matches Jean-Luc / Picard at the second.

          Cost is the token count minus one, on a handful of tokens.
        */
        NameClass best = NameClass.NONE;
        for (int cut = 1; cut < tokens.length; cut++) {
            String storedFirst = String.join(" ", Arrays.copyOfRange(tokens, 0, cut));
            String storedLast  = String.join(" ", Arrays.copyOfRange(tokens, cut, tokens.length));

            PartClass f = classifyPart(typedFirst, storedFirst);
            PartClass l = classifyPart(typedLast, storedLast, true);
            if (f == PartClass.NONE || l == PartClass.NONE) continue;

            NameClass here = (f == PartClass.EXACT && l == PartClass.EXACT) ? NameClass.CONFIRMED
                           : (f == PartClass.EXACT || l == PartClass.EXACT) ? NameClass.PLAUSIBLE
                           : NameClass.NONE;
            if (here == NameClass.CONFIRMED) return NameClass.CONFIRMED;
            if (here == NameClass.PLAUSIBLE) best = NameClass.PLAUSIBLE;
        }
        return best;
    }

    /**
     * The tokens a candidate query must match on: the first token of the given name and the last
     * token of the surname, as typed.
     *
     * <p>Why these two specifically. In any CONFIRMED or PLAUSIBLE match at least one part is
     * EXACT, and an exact part is a contiguous run of the stored name's tokens - so its first
     * token (for the given name) or its last token (for the surname) appears verbatim among the
     * stored tokens. Matching either is therefore sufficient to retrieve every candidate, and
     * remains so for compound names on both sides.
     */
    public static List<String> candidateTokens(String typedFirst, String typedLast) {
        List<String> out = new ArrayList<>();
        String f = norm(typedFirst), l = norm(typedLast);
        if (!f.isEmpty()) out.add(f.split(" ")[0]);
        if (!l.isEmpty()) { String[] lt = l.split(" "); out.add(lt[lt.length - 1]); }
        return out;
    }

    // ── title ────────────────────────────────────────────────────────────────

    private static final int TITLE_PREFIX_MIN = 3;

    /**
     * EQUIVALENT is "the same job, spelled differently". PLAUSIBLE is "a meaningful overlap we
     * cannot resolve". DIFFERENT is everything else.
     *
     * <p>Seniority and function must never collapse: Engineering Manager and Engineering Director
     * are DIFFERENT, and so are Manager and Senior Manager.
     */
    public static TitleClass classifyTitle(String typed, String stored) {
        String t = normTitle(typed), s = normTitle(stored);
        if (t.isEmpty() || s.isEmpty()) return TitleClass.DIFFERENT;
        if (t.equals(s)) return TitleClass.EQUIVALENT;
        if (s.length() >= 5 && damerau(t, s) <= 1) return TitleClass.EQUIVALENT;   // a misspelling
        if (t.length() >= TITLE_PREFIX_MIN && s.startsWith(t)) return TitleClass.PLAUSIBLE;
        Set<String> tw = new HashSet<>(Arrays.asList(t.split(" ")));
        Set<String> sw = new HashSet<>(Arrays.asList(s.split(" ")));
        if (!tw.isEmpty() && !sw.isEmpty() && !tw.equals(sw)
            && (sw.containsAll(tw) || tw.containsAll(sw))) return TitleClass.PLAUSIBLE;
        return TitleClass.DIFFERENT;
    }

    // ── company ──────────────────────────────────────────────────────────────

    /**
     * VERIFIED is identity: the same id, or the same normalised name. Aliases and merge survivors
     * are resolved by the caller into {@code verifiedIds} before this is called.
     *
     * <p>PLAUSIBLE is similarity and nothing more. It ranks, and it blocks premature publication.
     * It never establishes that two companies are the same, because somebody must still be able to
     * add a genuinely different business whose name resembles an existing one.
     */
    public static CompanyClass classifyCompany(Set<Long> verifiedIds, String typedCompany,
                                               Long recordCompanyId, String recordCompany) {
        if (recordCompanyId != null && verifiedIds != null && verifiedIds.contains(recordCompanyId))
            return CompanyClass.VERIFIED;
        String t = normCompany(typedCompany), s = normCompany(recordCompany);
        if (t.isEmpty() || s.isEmpty()) return CompanyClass.DIFFERENT;
        if (t.equals(s)) return CompanyClass.VERIFIED;
        Set<String> tw = new HashSet<>(Arrays.asList(t.split(" ")));
        Set<String> sw = new HashSet<>(Arrays.asList(s.split(" ")));
        if (!tw.equals(sw) && (sw.containsAll(tw) || tw.containsAll(sw))) return CompanyClass.PLAUSIBLE;
        return CompanyClass.DIFFERENT;
    }

    // ── the decision ─────────────────────────────────────────────────────────

    /**
     * @param nameMatches every public manager whose name is CONFIRMED or PLAUSIBLE, with the
     *                    employments of each: the current role UNIONED with career history.
     *
     * <p>Employment is matched as a PAIR. Given (Acme, Engineer) and (Globex, Director), a search
     * for (Acme, Director) matches neither: combining a company from one job with a title from
     * another would invent a role nobody held.
     */
    public static Decision decide(Map<NameClass, List<List<Employment>>> nameMatches,
                                  Set<Long> verifiedCompanyIds, String typedCompany, String typedTitle) {

        List<List<Employment>> confirmed = nameMatches.getOrDefault(NameClass.CONFIRMED, List.of());
        List<List<Employment>> plausible = nameMatches.getOrDefault(NameClass.PLAUSIBLE, List.of());

        if (confirmed.isEmpty() && plausible.isEmpty()) {
            return new Decision(Outcome.CREATE_GHOST, NameClass.NONE, null, null, "no_name_match");
        }

        /*
          RULE 7. A plausible name may never publish, whatever the employment looks like. A
          half-typed name that happens to carry an unfamiliar company and title is the single
          easiest way to manufacture a duplicate person, so it is the case guarded hardest.
        */
        if (confirmed.isEmpty()) {
            return new Decision(Outcome.QUEUE_REVIEW, NameClass.PLAUSIBLE, null, null, "fuzzy_name");
        }

        CompanyClass bestCompany = CompanyClass.DIFFERENT;
        TitleClass   bestTitle   = TitleClass.DIFFERENT;
        boolean pairMatched = false;

        for (List<Employment> employments : confirmed) {
            for (Employment e : employments) {
                CompanyClass cc = classifyCompany(verifiedCompanyIds, typedCompany, e.companyId(), e.company());
                TitleClass   tc = classifyTitle(typedTitle, e.title());
                if (cc.ordinal() < bestCompany.ordinal()) bestCompany = cc;
                if (tc.ordinal() < bestTitle.ordinal())   bestTitle   = tc;
                if (cc == CompanyClass.VERIFIED && tc == TitleClass.EQUIVALENT) pairMatched = true;
            }
        }

        if (pairMatched)
            return new Decision(Outcome.RETURN_EXISTING, NameClass.CONFIRMED, bestCompany, bestTitle, "pair_match");

        /*
          A ghost needs BOTH axes genuinely DIFFERENT, in every record. A PLAUSIBLE company or
          title is uncertainty, and uncertainty is queued, never published: that is what stops
          "Controller" at "Warrington" minting a second Aaron Yip alongside the Divisional
          Controller at Warrington PCI Management.
        */
        if (bestCompany == CompanyClass.DIFFERENT && bestTitle == TitleClass.DIFFERENT)
            return new Decision(Outcome.CREATE_GHOST, NameClass.CONFIRMED, bestCompany, bestTitle, "both_differ");

        return new Decision(Outcome.QUEUE_REVIEW, NameClass.CONFIRMED, bestCompany, bestTitle,
                            bestCompany == CompanyClass.VERIFIED ? "title_differs"
                          : bestTitle   == TitleClass.EQUIVALENT ? "company_differs"
                          : "plausible_overlap");
    }

    // ── edit distance ────────────────────────────────────────────────────────

    static int damerau(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + cost);
            }
        }
        return d[a.length()][b.length()];
    }
}

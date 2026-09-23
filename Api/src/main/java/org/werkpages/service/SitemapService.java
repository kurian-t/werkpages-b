package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;

/**
 * Generates the dynamic XML sitemap covering all public company and manager pages.
 */
public class SitemapService {

    private static final String BASE_URL = "https://werkpages.com";

    private static final String[] STATIC_PAGES = {
        "/",
        "/directory",
        "/add",
        "/about",
        "/what-is-werkpages",
        "/support",
        "/privacy",
        "/terms",
    };

    /**
     * The SQL half of the indexability rule; {@code client/lib/indexability.ts} is the other.
     *
     * <p>This used to be {@code reviews_count > 0}: a profile earned a sitemap entry only once
     * somebody had rated it. The reasoning was that a mass of review-less profiles reads to Google
     * as near-duplicate filler, which is a real way to damage a domain - but "has no reviews yet"
     * and "has nothing on it" are different conditions, and only the second is thin.
     *
     * <p>A review-less profile still carries a name, a role, an employer and a location, and
     * auto-created ones are limited to one per account and filtered for public figures, so they
     * are not generated in bulk. Excluding them hid exactly the pages somebody searching a
     * manager by name was trying to reach - the search that leads to the first review.
     *
     * <p>What stays out is a profile with no employer or no role, which really is just a name.
     */
    private static final String INDEXABLE_MANAGER =
        "  AND m.title IS NOT NULL AND btrim(m.title) <> ''\n"
      + "  AND m.company IS NOT NULL AND btrim(m.company) <> ''\n";

    private final SqlClient db;

    public SitemapService(SqlClient db) {
        this.db = db;
    }

    public Future<String> generate() {
        // Which profiles are submitted: the SQL mirror of client/lib/indexability.ts, and the two
        // have to keep saying the same thing. A URL submitted here while the page serves noindex
        // spends crawl budget to be refused; a page that is indexable but never submitted has to
        // be discovered some other way. See INDEXABLE_MANAGER.
        Future<RowSet<Row>> companiesFuture = db.query("""
                SELECT DISTINCT c.slug, c.industry
                FROM companies c
                JOIN managers m ON m.company_id = c.id
                WHERE c.status IN ('approved', 'ghost')
                  AND c.slug IS NOT NULL
                  AND m.approval_status IN ('approved', 'ghost')
                """ + INDEXABLE_MANAGER + """
                ORDER BY c.slug
                """).execute();

        Future<RowSet<Row>> managersFuture = db.query("""
                SELECT m.slug AS manager_slug, c.slug AS company_slug, c.industry
                FROM managers m
                JOIN companies c ON c.id = m.company_id
                WHERE m.approval_status IN ('approved', 'ghost')
                  AND m.slug IS NOT NULL
                  AND c.slug IS NOT NULL
                """ + INDEXABLE_MANAGER + """
                ORDER BY c.slug, m.slug
                """).execute();

        return companiesFuture.compose(companies ->
            managersFuture.map(managers -> buildXml(companies, managers)));
    }

    private String buildXml(RowSet<Row> companies, RowSet<Row> managers) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        // Static pages
        for (String path : STATIC_PAGES) {
            appendUrl(sb, BASE_URL + path, "weekly", path.equals("/") ? "1.0" : "0.7");
        }

        // Company pages — canonical industry-nested form. The frontend redirects the older
        // flat /companies/:slug URLs here, so only this shape belongs in the sitemap:
        // submitting both would hand Google a duplicate of every page.
        for (Row row : companies) {
            String slug = row.getString("slug");
            appendUrl(sb, BASE_URL + industryPath(row.getString("industry")) + "/companies/" + slug,
                      "daily", "0.9");
        }

        // Manager pages
        for (Row row : managers) {
            String companySlug = row.getString("company_slug");
            String managerSlug = row.getString("manager_slug");
            appendUrl(sb, BASE_URL + industryPath(row.getString("industry"))
                          + "/companies/" + companySlug + "/managers/" + managerSlug,
                      "weekly", "0.8");
        }

        sb.append("</urlset>");
        return sb.toString();
    }

    /**
     * "/industries/<slug>" for the canonical URL prefix. Unclassified companies fall back to
     * "other", matching UNCLASSIFIED_INDUSTRY_SLUG in the frontend's lib/urls.ts — the two must
     * agree or the sitemap advertises URLs that immediately redirect.
     */
    private static String industryPath(String industry) {
        String slug = IndustryTaxonomy.slug(industry);
        return "/industries/" + (slug == null || slug.isBlank() ? "other" : slug);
    }

    private static void appendUrl(StringBuilder sb, String loc, String changefreq, String priority) {
        sb.append("  <url>\n");
        sb.append("    <loc>").append(loc).append("</loc>\n");
        sb.append("    <changefreq>").append(changefreq).append("</changefreq>\n");
        sb.append("    <priority>").append(priority).append("</priority>\n");
        sb.append("  </url>\n");
    }
}

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

    private final SqlClient db;

    public SitemapService(SqlClient db) {
        this.db = db;
    }

    public Future<String> generate() {
        // Only pages with real content are indexable. Empty ghost/auto-created pages (0 reviews)
        // are thin, near-duplicate templates — submitting them floods Google's crawl budget and
        // produces "Discovered - currently not indexed" and "Duplicate" reports. A company/manager
        // earns a sitemap entry only once it has at least one real review (reviews_count > 0).
        Future<RowSet<Row>> companiesFuture = db.query("""
                SELECT DISTINCT c.slug
                FROM companies c
                JOIN managers m ON m.company_id = c.id
                WHERE c.status IN ('approved', 'ghost')
                  AND c.slug IS NOT NULL
                  AND m.approval_status IN ('approved', 'ghost')
                  AND m.reviews_count > 0
                ORDER BY c.slug
                """).execute();

        Future<RowSet<Row>> managersFuture = db.query("""
                SELECT m.slug AS manager_slug, c.slug AS company_slug
                FROM managers m
                JOIN companies c ON c.id = m.company_id
                WHERE m.approval_status IN ('approved', 'ghost')
                  AND m.slug IS NOT NULL
                  AND c.slug IS NOT NULL
                  AND m.reviews_count > 0
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

        // Company pages
        for (Row row : companies) {
            String slug = row.getString("slug");
            appendUrl(sb, BASE_URL + "/companies/" + slug, "daily", "0.9");
        }

        // Manager pages
        for (Row row : managers) {
            String companySlug = row.getString("company_slug");
            String managerSlug = row.getString("manager_slug");
            appendUrl(sb, BASE_URL + "/companies/" + companySlug + "/managers/" + managerSlug, "weekly", "0.8");
        }

        sb.append("</urlset>");
        return sb.toString();
    }

    private static void appendUrl(StringBuilder sb, String loc, String changefreq, String priority) {
        sb.append("  <url>\n");
        sb.append("    <loc>").append(loc).append("</loc>\n");
        sb.append("    <changefreq>").append(changefreq).append("</changefreq>\n");
        sb.append("    <priority>").append(priority).append("</priority>\n");
        sb.append("  </url>\n");
    }
}

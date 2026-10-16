package org.werkpages.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Builds a tiny, real corpus release for tests: an actual DuckDB index and actual Parquet files,
 * produced by the same SQL shape the production build uses.
 *
 * <h2>Why not text files</h2>
 *
 * <p>A release made of files containing the word "INDEX" proves the pointer logic and nothing else.
 * It cannot catch a tokenizer that drops CJK, a posting list that misses the street field, or a
 * ranking that puts the wrong branch first, because none of those involve the pointer. These
 * fixtures are small enough to build in a test and real enough to fail for the right reasons.
 *
 * <h2>Why not the full corpus</h2>
 *
 * <p>Sixteen million rows take minutes to rebuild and gigabytes to hold. Nothing about ranking,
 * geography or tokenisation needs that: a few dozen rows chosen to collide in the interesting ways
 * exercise the same code paths in under a second.
 */
public final class FixtureCorpus {

    /** The same tokeniser expression the production build uses; see LocalPlaceIndex.TOKEN_SPLIT. */
    private static final String SPLIT = "[^\\p{L}\\p{N}\\p{M}]+";

    private FixtureCorpus() {}

    /**
     * Rows chosen so that each one can fail a specific way:
     * competing branches of one chain, a business whose NAME is a street type, a business whose
     * name contains a region word, abbreviation mismatches, postal codes, and non-Latin names.
     */
    private static final String ROWS = """
        ('p-subaru-eg', 'Elk Grove Subaru',  'Subaru',     '8581 Laguna Grove Dr', 'Elk Grove', 'ca', '95757', 0.99, 'open'),
        ('p-subaru-rc', 'Roseville Subaru',  'Subaru',     '200 Automall Dr',      'Roseville', 'ca', '95661', 0.98, 'open'),
        ('p-sbux-tor',  'Starbucks',         'Starbucks',  '100 King St W',        'Toronto',   'on', 'M5X1A9', 0.97, 'open'),
        ('p-sbux-van',  'Starbucks',         'Starbucks',  '500 Granville St',     'Vancouver', 'bc', 'V6C1W6', 0.96, 'open'),
        ('p-sbux-lon',  'Starbucks',         'Starbucks',  '12 Oxford St',         'London',    'on', 'N6A1R5', 0.95, 'open'),
        ('p-scotia',    'Scotiabank Place',  NULL,         '1000 Palladium Dr',    'Ottawa',    'on', 'K2V1A5', 0.94, 'open'),
        ('p-northface', 'The North Face',    'The North Face','1 Union Square',     'New York',  'ny', '10003', 0.93, 'open'),
        ('p-nylife',    'New York Life',     'New York Life','51 Madison Ave',     'New York',  'ny', '10010', 0.92, 'open'),
        ('p-southst',   'Harbour Grill',     NULL,         '45 South Street',      'Halifax',   'ns', 'B3J1A1', 0.91, 'open'),
        ('p-closed',    'Closed Cafe',       NULL,         '9 Queen St',           'Toronto',   'on', 'M5H2N2', 0.99, 'closed'),
        ('p-tokyo',     '東京タワー',          NULL,         '4-2-8 Shibakoen',      '東京',       'jp', '1050011', 0.90, 'open'),
        ('p-cafe',      'Café Du Monde',     NULL,         '800 Decatur St',       'New Orleans','la','70116', 0.89, 'open'),
        ('p-dev',       'कॉफी हाउस',           NULL,         '12 MG Road',           'Mumbai',    'mh', '400001', 0.88, 'open'),
        ('p-thai',      'ร้านกาแฟ',            NULL,         '9 Sukhumvit',          'Bangkok',   'bk', '10110', 0.87, 'open'),
        -- Deliberate collisions, so the assertions above cannot pass by elimination.
        ('p-northst',   'Northside Grill',   NULL,         '45 North Street',      'Halifax',   'ns', 'B3J1A2', 0.99, 'open'),
        ('p-laguna2',   'Laguna Dental',     NULL,         '8581 Laguna Beach Rd', 'San Diego', 'ca', '92101', 0.99, 'open'),
        ('p-scotia2',   'Scotiabank',        'Scotiabank', '44 King St W',         'Toronto',   'on', 'M5H1A1', 0.99, 'open'),
        ('p-sbux-lon2', 'Starbucks',         'Starbucks',  '7 Regent St',          'London',    'en', 'W1B5RE', 0.99, 'open'),
        ('p-nyl-ca',    'New York Life',     'New York Life','9 Market St',        'San Jose',  'ca', '95113', 0.99, 'open'),
        -- "fac" exists as a whole token here, so prefix matching for "North Fac" cannot be
        -- decided by "the exact token is missing".
        ('p-fac',       'Fac Studio',        NULL,         '3 Mill Rd',            'Guelph',    'on', 'N1H1A1', 0.99, 'open'),
        -- GENUINE AMBIGUITY. "ca" is California in the US and Canada's country code; "london"
        -- names cities in two countries; "york" names a city as well as half a company name.
        ('p-amb-ca-us',  'Pacific Imports',  NULL,         '1 Bay St',             'San Jose',  'ca', '95113', 0.99, 'open'),
        ('p-amb-ca-ont', 'Pacific Imports',  NULL,         '2 Bay St',             'Toronto',   'on', 'M5J2R8', 0.98, 'open'),
        ('p-york-uk',    'York Roasters',    NULL,         '3 Stonegate',          'York',      'yk', 'YO18AS', 0.97, 'open'),
        ('p-york-us',    'York Roasters',    NULL,         '4 Market St',          'New York',  'ny', '10038', 0.96, 'open'),
        -- A comma inside a BUSINESS NAME, and a comma before a STREET ADDRESS. Neither is
        -- geography, and reading everything after the first comma as a place breaks both.
        ('p-smith',      'Smith, Jones & Associates', NULL, '77 Bay St',           'Hamilton',  'on', 'L8P1A1', 0.99, 'open'),
        ('p-mainst',     'Riverside Clinic',  NULL,         '123 Main Street',      'Barrie',    'on', 'L4M1A1', 0.99, 'open'),

        /*
          A business whose NAME contains a state name, which the real corpus has 19,377 of for
          California alone. Without a row like this the token "california" has no postings outside
          the state field, so the alias translation fires for the wrong reason and the fixture
          cannot reproduce the failure where a typed state name killed the whole query.
        */
        ('p-calpizza',   'California Pizza Kitchen', 'California Pizza Kitchen',
                                                     '10 Sunset Blvd',       'Los Angeles','ca', '90001', 0.99, 'open')
        """;

    /** Builds a complete, validated release under {@code root} and points {@code current} at it. */
    public static CorpusRelease publish(Path root) throws Exception {
        Path stage = root.resolve("releases").resolve("fixture-" + System.nanoTime());
        Files.createDirectories(stage);
        String idx = stage.resolve("index.duckdb").toString();

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + idx);
             Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE base AS
                SELECT row_number() OVER (ORDER BY sid) AS place_id, *,
                       lower(nfc_normalize(coalesce(name,'')))       AS name_norm,
                       lower(nfc_normalize(coalesce(brand,'')))      AS brand_norm,
                       lower(nfc_normalize(coalesce(city,'')))       AS city_norm,
                       lower(nfc_normalize(coalesce(state,'')))      AS state_norm,
                       lower(nfc_normalize(coalesce(street,'')))     AS street_norm,
                       lower(nfc_normalize(replace(coalesce(postal,''),' ',''))) AS postal_norm,
                       (brand IS NOT NULL) AS brand_flag,
                       (status <> 'closed') AS open_ok
                FROM (VALUES %s) AS t(sid, name, brand, street, city, state, postal, conf, status)
                """.formatted(ROWS));
            s.execute("""
                CREATE TABLE tok AS SELECT token, field, place_id, conf, brand, open_ok FROM (
                  SELECT 'name'  AS field, unnest(string_split_regex(name_norm,  '%1$s')) AS token, place_id, conf, brand_flag AS brand, open_ok FROM base
                  UNION ALL SELECT 'brand', unnest(string_split_regex(brand_norm, '%1$s')), place_id, conf, brand_flag, open_ok FROM base
                  UNION ALL SELECT 'city',  unnest(string_split_regex(city_norm,  '%1$s')), place_id, conf, brand_flag, open_ok FROM base
                  UNION ALL SELECT 'street',unnest(string_split_regex(street_norm,'%1$s')), place_id, conf, brand_flag, open_ok FROM base
                  UNION ALL SELECT 'state', state_norm,  place_id, conf, brand_flag, open_ok FROM base
                  UNION ALL SELECT 'postal',postal_norm, place_id, conf, brand_flag, open_ok FROM base
                ) WHERE token <> '' ORDER BY token, place_id
                """.formatted(SPLIT));
            s.execute("CREATE TABLE meta AS SELECT place_id, conf, brand_flag AS brand, open_ok,"
                    + " name_norm, brand_norm, city_norm, state_norm FROM base ORDER BY place_id");
            s.execute("CREATE TABLE tok_freq AS SELECT token, field, count(*) AS n FROM tok GROUP BY token, field ORDER BY token");
            // Region names to the codes places actually store, as the production release carries.
            // Distinct city names, so "is this comma component a place" is a lookup rather than an
            // assumption. Without it every comma was read as geography and "Smith, Jones &
            // Associates" became a search for Smith in a city called Jones.
            s.execute("CREATE TABLE geo_name AS SELECT DISTINCT city_norm AS name FROM base WHERE city_norm <> ''");
            /*
              SCHEMA 6: the geography alignment tables, built here by the SAME rules the real
              builder applies in geo_align.sql.

              They are not stubbed, because what they encode is exactly what the reader's hardest
              cases depend on. The fixture holds a CITY called New York and a BUSINESS called New
              York Life, which is the collision that made "New York Life, New York" return Life
              Pharmacy, and it holds a geography entry calling that city "New York City", which is
              the naming difference that made "Walmart New York City" return nothing: the real
              places index spells that city "new york" for 245,580 rows and "new york city" for
              225.

              region_alias CARRIES ITS SUPPORT COUNT, as the real one does, so a name resolving to
              several codes is settled by data rather than by row order.
            */
            s.execute("CREATE OR REPLACE TEMPORARY MACRO fold(x) AS "
                    + "trim(regexp_replace(lower(strip_accents(nfc_normalize(x))), "
                    + "'[^\\p{L}\\p{N}\\p{M}]+', ' ', 'g'))");
            // What the geography corpus would say about this fixture's cities. Populations are the
            // real ones, so prominence ordering can be asserted against something meaningful.
            s.execute("""
                CREATE TEMPORARY TABLE geo_fix AS SELECT * FROM (VALUES
                  ('new york city','ny', 8804190), ('elk grove','ca',  176124),
                  ('toronto','on',       2794356), ('london','on',     422324),
                  ('london','en',        8961989), ('york','yk',       153717),
                  ('vancouver','bc',      662248), ('halifax','ns',    439819),
                  ('mumbai','mh',       12442373), ('bangkok','bk',  10539000),
                  ('new orleans','la',    383997), ('san jose','ca',   969655),
                  ('san diego','ca',     1386932), ('ottawa','on',    1017449),
                  ('guelph','on',         143740), ('hamilton','on',   569353),
                  ('roseville','ca',      147773), ('tokyo','jp',     9733276)
                ) AS t(gkey, region, population)
                """);
            s.execute("CREATE TEMPORARY TABLE pc AS SELECT city_norm, state_norm, fold(city_norm) AS ckey,"
                    + " count(*) AS places FROM base WHERE city_norm <> '' GROUP BY 1,2,3");
            s.execute("CREATE TEMPORARY TABLE ck AS SELECT state_norm AS region, ckey,"
                    + " sum(places) AS places FROM pc GROUP BY 1,2");
            // Verbatim, plus the longest unclaimed prefix that the index actually uses more than
            // the full spelling. This is what links "new york city" to "new york".
            s.execute("""
                CREATE TEMPORARY TABLE anchor AS
                SELECT g.gkey, g.region, p.ckey AS member
                  FROM geo_fix g JOIN ck p ON p.region = g.region AND p.ckey = g.gkey
                UNION
                SELECT gkey, region, member FROM (
                  SELECT g.gkey, g.region, p.ckey AS member,
                         row_number() OVER (PARTITION BY g.gkey, g.region
                                            ORDER BY length(p.ckey) DESC, p.places DESC) AS rk
                  FROM geo_fix g JOIN ck p
                    ON p.region = g.region AND starts_with(g.gkey, p.ckey || ' ')
                  WHERE NOT EXISTS (SELECT 1 FROM geo_fix o
                                     WHERE o.region = g.region AND o.gkey = p.ckey)
                    AND p.places >= coalesce((SELECT f.places FROM ck f
                                               WHERE f.region = g.region AND f.ckey = g.gkey), 0)
                ) WHERE rk = 1
                """);
            s.execute("""
                CREATE TABLE city_alias AS
                SELECT ckey AS alias, state_norm AS region, city_norm, places FROM pc
                UNION
                SELECT a1.member, p.state_norm, p.city_norm, p.places
                  FROM anchor a1 JOIN anchor a2 ON a2.gkey = a1.gkey AND a2.region = a1.region
                  JOIN pc p ON p.state_norm = a2.region AND p.ckey = a2.member
                UNION
                SELECT a.gkey, p.state_norm, p.city_norm, p.places
                  FROM anchor a JOIN pc p ON p.state_norm = a.region AND p.ckey = a.member
                """);
            s.execute("""
                CREATE TABLE geo_place AS
                SELECT name, region, max(population) AS population FROM (
                  SELECT gkey AS name, region, population FROM geo_fix
                  UNION ALL
                  SELECT a.member, a.region, g.population
                    FROM anchor a JOIN geo_fix g ON g.gkey = a.gkey AND g.region = a.region
                ) GROUP BY 1,2
                """);
            s.execute("""
                CREATE TABLE region_alias AS
                SELECT name, code, coalesce(sum(k.places), 0) AS places FROM (
                  SELECT * FROM (VALUES
                    ('ontario','on'), ('british columbia','bc'), ('nova scotia','ns'),
                    ('california','ca'), ('new york','ny'), ('louisiana','la'),
                    ('maharashtra','mh'), ('england','en'), ('yorkshire','yk')) AS t(name, code)
                  UNION
                  SELECT DISTINCT state_norm, state_norm FROM pc WHERE state_norm <> ''
                ) LEFT JOIN ck k ON k.region = code
                GROUP BY 1, 2 ORDER BY name, places DESC
                """);
            s.execute("COPY (SELECT place_id, conf, brand_flag AS brand, open_ok, name_norm, brand_norm,"
                    + " city_norm, state_norm FROM base ORDER BY place_id) TO '"
                    + stage.resolve("meta.parquet") + "' (FORMAT PARQUET, ROW_GROUP_SIZE 2048)");
            s.execute("COPY (SELECT place_id, sid AS source_place_id, name, brand AS brand_name, street,"
                    // UPPER, because the real corpus carries state_code straight from Overture,
                    // where it is uppercase, while state_norm is the lowercased copy. The fixture
                    // wrote its lowercase literals through unchanged, so a suggestion built from
                    // it read "Elk Grove, ca" and no test could have noticed a casing fault.
                    + " city, upper(state) AS state_code, postal AS postal_code FROM base ORDER BY place_id) TO '"
                    + stage.resolve("payload.parquet") + "' (FORMAT PARQUET, ROW_GROUP_SIZE 2048)");
            s.execute("DROP TABLE base");
            s.execute("CHECKPOINT");
        }

        Files.writeString(stage.resolve("manifest.json"),
            "{\"release\":\"" + stage.getFileName() + "\",\"index\":\"index.duckdb\","
          + "\"meta\":\"meta.parquet\",\"payload\":\"payload.parquet\","
          // WHICH COUNTRY THIS RELEASE HOLDS, which country routing reads to decide what a
          // single-country root can answer for. Without it the registry found no countries under
          // the root and disabled the local index altogether, so the contract test's local results
          // silently vanished from the response. The payload's state codes are American and the
          // test asserts countryCode "US", so that is what this fixture is.
          + "\"country\":\"US\",\"schema\":6}");
        Path tmp = root.resolve(".current.new");
        Files.deleteIfExists(tmp);
        Files.createSymbolicLink(tmp, stage);
        Files.move(tmp, root.resolve("current"),
                   java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                   java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        CorpusRelease r = CorpusRelease.resolve(root);
        if (r == null) throw new IllegalStateException("fixture release failed to resolve");
        return r;
    }
}

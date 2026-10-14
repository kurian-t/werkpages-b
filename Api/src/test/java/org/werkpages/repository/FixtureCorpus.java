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
            s.execute("""
                CREATE TABLE region_alias AS SELECT * FROM (VALUES
                  ('ontario','on'), ('british columbia','bc'), ('nova scotia','ns'),
                  ('california','ca'), ('new york','ny'), ('louisiana','la'),
                  ('maharashtra','mh'), ('england','en'), ('yorkshire','yk')) AS t(name, code)
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
          + "\"meta\":\"meta.parquet\",\"payload\":\"payload.parquet\",\"schema\":5}");
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

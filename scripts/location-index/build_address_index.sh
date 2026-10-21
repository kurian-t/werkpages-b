#!/bin/bash
#
# Builds one country's address index: <CC>.sqlite, from that country's places payload.
#
# Pinned to ONE release, named on the command line and recorded in the manifest. A previous
# measurement was invalidated by joining a payload from release CA-1454066 against an index built
# from CA-1454538, so the release is never discovered here.
#
# Usage: build_address_index.sh <CC> <payload.parquet> <out-dir>
set -euo pipefail
CC="$1"; PAYLOAD="$2"; OUT="$3"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
DB="$OUT/$CC.sqlite"
rm -f "$DB"

duckdb <<SQL
SET threads=1; SET memory_limit='2GB';

-- The matching contract, identical to GeographyIndex.fold for Latin text: NFC, accents stripped,
-- everything but letters and digits collapsed to single spaces.
CREATE OR REPLACE MACRO fold(s) AS
  trim(regexp_replace(lower(strip_accents(nfc_normalize(s))), '[^a-z0-9]+', ' ', 'g'));

-- Thoroughfare abbreviations expanded for MATCHING only. Display strings are never rewritten:
-- the reader is shown what the source says. Must stay in step with AddressIndex.ABBREV.
CREATE OR REPLACE MACRO canon(s) AS
  trim(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(
  regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(
    ' ' || fold(s) || ' ',
    ' (st|str) ',  ' street ',    'g'), ' (rd) ',        ' road ',      'g'),
    ' (ave|av) ',  ' avenue ',    'g'), ' (blvd|boul) ', ' boulevard ', 'g'),
    ' (dr) ',      ' drive ',     'g'), ' (hwy) ',       ' highway ',   'g'),
    ' (ln) ',      ' lane ',      'g'), ' (cres) ',      ' crescent ',  'g'),
    ' (pl) ',      ' place ',     'g'), ' (ct) ',        ' court ',     'g'),
    ' (pkwy) ',    ' parkway ',   'g'), ' (sq) ',        ' square ',    'g'));

CREATE TEMP TABLE src AS
SELECT source_place_id,                            -- Overture's GERS id: the STABLE identity
       street,
       trim(regexp_replace(street, '^[0-9]+[a-zA-Z]?[[:space:]]+', '')) AS street_display,
       city  AS city_display,
       state_code
  FROM read_parquet('$PAYLOAD')
 WHERE street IS NOT NULL AND street <> ''
   AND city   IS NOT NULL AND city   <> ''
   AND source_place_id IS NOT NULL;

CREATE TABLE addr AS
SELECT canon(street)          AS addr_key,         -- "55 gould street", what a query is matched to
       street                 AS address_display,  -- the source string, verbatim
       canon(street_display)  AS street_key,       -- the street alone, for a street query
       street_display,
       fold(city_display)     AS city_key,
       city_display,
       lower(coalesce(state_code, '')) AS state_key,
       coalesce(state_code, '')        AS state_display,   -- "ON", not "on"
       source_place_id
  FROM src
 WHERE street_display <> '';

ATTACH '$DB' AS out (TYPE sqlite);
CREATE TABLE out.addr AS SELECT * FROM addr;
SQL

python3 -I "$HERE/index_sqlite.py" "$DB"

echo "built $DB  bytes=$(stat -c %s "$DB")"
echo "release: $PAYLOAD"

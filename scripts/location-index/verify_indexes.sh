#!/bin/bash
#
# Verifies a location index release: checksums, then that each file is a usable SQLite database
# with the tables and indexes the application needs.
#
# Why both halves: a truncated SQLite file opens without complaint and fails later, inside a query,
# as a corruption error. And a file published without its indexes is completely correct and
# answers every prefix range with a full table scan - which is the 2.3-second behaviour this whole
# release exists to remove, reintroduced silently.
#
# Usage: verify_indexes.sh [release-dir]        (default: /var/lib/location-index/current)
# Exit:  0 all present and valid
#        1 a checksum mismatch, a missing required file, or an unusable database
set -uo pipefail

DIR="${1:-/var/lib/location-index/current}"
fail=0
say() { printf '%-46s %s\n' "$1" "$2"; }

if [ ! -d "$DIR" ]; then
  echo "FAIL: $DIR does not exist. Nothing is provisioned."
  exit 1
fi

echo "release dir: $(readlink -f "$DIR")"
echo

# ── 1. checksums, against the manifest the release shipped with ──────────────────────────────
if [ -f "$DIR/MANIFEST.yaml" ]; then
  while read -r path; do
    read -r want
    if [ ! -f "$DIR/$path" ]; then
      say "$path" "MISSING"; fail=1; continue
    fi
    got=$(sha256sum "$DIR/$path" | cut -d' ' -f1)
    if [ "$got" = "$want" ]; then say "$path" "sha256 OK"
    else say "$path" "SHA256 MISMATCH (expected ${want:0:16}…, got ${got:0:16}…)"; fail=1; fi
  done < <(awk '/^  - path:/ {p=$3} /^    sha256:/ {print p; print $2}' "$DIR/MANIFEST.yaml")
else
  say "MANIFEST.yaml" "MISSING - cannot verify checksums"; fail=1
fi

echo

# ── 2. each database opens, holds its table, and has the index that makes it fast ────────────
check() {   # file, table, first-indexed-column, minimum rows
  local f="$DIR/$1" table="$2" idx="$3" min="$4"
  if [ ! -f "$f" ]; then say "$1" "MISSING"; return 1; fi
  python3 -I - "$f" "$table" "$idx" "$min" <<'PY'
import sqlite3, sys
f, table, idx, minimum = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
try:
    c = sqlite3.connect(f"file:{f}?mode=ro&immutable=1", uri=True)
    n = c.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
    if n < minimum:
        print(f"ONLY {n:,} ROWS in {table} (expected at least {minimum:,})"); sys.exit(1)
    # The COLUMN that must be indexed, not the index's name. Names are a build detail - routing's
    # is "ft_t" - and checking them made this script fail on a perfectly good artifact.
    indexed = []
    for (name,) in c.execute(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?", (table,)):
        cols = [r[2] for r in c.execute(f"PRAGMA index_info({name!r})")]
        if cols:
            indexed.append(cols[0])
    if idx not in indexed:
        print(f"NO INDEX leading on {idx!r}; indexed columns are {indexed}"); sys.exit(1)
    print(f"{n:,} rows, {idx} indexed")
except Exception as err:
    print(f"UNUSABLE: {err}"); sys.exit(1)
PY
}

out=$(check geography.sqlite entity norm 5000000);      rc=$?; say "geography.sqlite"     "$out"; [ $rc -ne 0 ] && fail=1
out=$(check routing.sqlite   ft     token 10000000);    rc=$?; say "routing.sqlite"       "$out"; [ $rc -ne 0 ] && fail=1
out=$(check addresses/CA.sqlite addr addr_key 1000000);   rc=$?; say "addresses/CA.sqlite"  "$out"; [ $rc -ne 0 ] && fail=1

echo
if [ $fail -eq 0 ]; then
  echo "VERIFIED: release at $DIR is complete and usable."
else
  echo "FAILED: do not point the application at this release."
fi
exit $fail

#!/usr/bin/env bash
# Verify that the RELEASE ARTIFACT contains the migrations that were reviewed, and that applying
# them to a clean database produces the objects they claim to.
#
# WHY THIS EXISTS. On 2026-11-02 the integration suites validated an amended V107 from the source
# tree while the running application applied the PREVIOUS V107 bundled inside its jar. Two
# different artifacts were being verified, and the Flyway log said "Successfully applied 1
# migration" both times. The checksum was identical because it was the same old file.
#
# "Flyway reports N successful migrations" proves nothing about WHICH migrations ran. This script
# compares the artifact against the source and then asserts the resulting objects exist.
#
# Usage:  scripts/verify-release-migrations.sh [path-to-shaded-jar]
# Exit:   0 all checks pass; non-zero on the first failure, with the reason.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${1:-$REPO/RestApi/target/RestApi-1.0-SNAPSHOT-shaded.jar}"
SRC="$REPO/Api/src/main/resources/db/migrations"
WORK="$(mktemp -d)"
CONTAINER="verify-release-migrations-$$"

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

[ -f "$JAR" ] || fail "no release artifact at $JAR. Run 'mvn clean package -DskipTests' first."
[ -d "$SRC" ] || fail "no migration source at $SRC"

echo "artifact: $JAR"
echo "built:    $(date -r "$JAR" '+%Y-%m-%d %H:%M:%S')"

# ---------------------------------------------------------------------------
# 1. The artifact's migrations must be byte-identical to the source tree.
# ---------------------------------------------------------------------------
# This is the check that would have caught the stale jar immediately. A jar built before an edit
# carries the old file, and no amount of Flyway output reveals that.
mkdir -p "$WORK/jar"
( cd "$WORK/jar" && unzip -qo "$JAR" 'db/migrations/*' )
JARDIR="$WORK/jar/db/migrations"
[ -d "$JARDIR" ] || fail "the artifact contains no db/migrations at all"

src_count=$(find "$SRC" -name '*.sql' | wc -l | tr -d ' ')
jar_count=$(find "$JARDIR" -name '*.sql' | wc -l | tr -d ' ')
[ "$src_count" = "$jar_count" ] \
  || fail "migration count differs: source has $src_count, artifact has $jar_count"

stale=0
for f in "$SRC"/*.sql; do
  base="$(basename "$f")"
  if [ ! -f "$JARDIR/$base" ]; then
    echo "  MISSING FROM ARTIFACT: $base" >&2; stale=1; continue
  fi
  if ! cmp -s "$f" "$JARDIR/$base"; then
    echo "  STALE IN ARTIFACT: $base" >&2; stale=1
  fi
done
[ "$stale" -eq 0 ] || fail "the artifact's migrations differ from the source tree. Rebuild the jar."
echo "OK  $src_count migrations in the artifact are byte-identical to the source"

# ---------------------------------------------------------------------------
# 2. Apply the ARTIFACT's migrations to a clean database.
# ---------------------------------------------------------------------------
# From the extracted copy, deliberately: this proves the artifact's own SQL runs, not that the
# source tree's does.
docker run -d --name "$CONTAINER" -e POSTGRES_PASSWORD=verify -e POSTGRES_DB=verify \
  -P postgres:15-alpine >/dev/null
PORT="$(docker port "$CONTAINER" 5432/tcp | head -1 | sed 's/.*://')"
for _ in $(seq 1 60); do
  docker exec "$CONTAINER" pg_isready -U postgres -d verify >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$CONTAINER" pg_isready -U postgres -d verify >/dev/null 2>&1 \
  || fail "the verification database never became ready"

FLYWAY_JAR="$(find "$HOME/.m2/repository/org/flywaydb" -name 'flyway-core-*.jar' | sort | tail -1)"
[ -n "$FLYWAY_JAR" ] || fail "flyway-core not found in the local repository"

# Migrate using the application's OWN Flyway, from the artifact's own SQL, through the jar itself so
# the classpath matches what production runs.
docker cp "$JARDIR" "$CONTAINER:/tmp/migrations" >/dev/null
cat > "$WORK/Migrate.java" <<'JAVA'
import org.flywaydb.core.Flyway;
public class Migrate {
  public static void main(String[] a) {
    var r = Flyway.configure().dataSource(a[0], "postgres", "verify")
                  .locations("filesystem:" + a[1]).load().migrate();
    System.out.println("APPLIED=" + r.migrationsExecuted);
  }
}
JAVA
java -cp "$JAR:$WORK" "$WORK/Migrate.java" \
     "jdbc:postgresql://localhost:$PORT/verify" "$JARDIR" \
  || fail "the artifact's migrations failed to apply to a clean database"
echo "OK  the artifact's migrations applied to a clean database"

q() { docker exec "$CONTAINER" psql -U postgres -d verify -tAc "$1" | tr -d ' '; }

# ---------------------------------------------------------------------------
# 3. The objects the reviewed migrations claim to create must exist.
# ---------------------------------------------------------------------------
# "N migrations succeeded" is not evidence. These are the specific objects whose absence the stale
# jar hid: the column was present and both triggers were not.
expect_trigger() {
  [ "$(q "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
          WHERE NOT t.tgisinternal AND c.relname='$1' AND t.tgname='$2'")" = "1" ] \
    || fail "trigger $2 on $1 is missing after migrating the artifact"
  echo "OK  trigger $2 on $1"
}

expect_trigger reviews review_author_erasure_is_authorized
expect_trigger reviews review_not_born_erased
expect_trigger reviews review_author_erasure_needs_account_deletion
expect_trigger claim_resolution_events claim_resolution_events_append_only
expect_trigger review_supersessions   review_supersessions_append_only
expect_trigger rating_opportunity_retirements rating_opportunity_retirements_append_only

[ "$(q "SELECT count(*) FROM information_schema.columns
        WHERE table_name='reviews' AND column_name='author_erased'")" = "1" ] \
  || fail "reviews.author_erased is missing"
echo "OK  reviews.author_erased"

# No ::text cast: psql -tA renders a boolean as t/f, while a cast to text renders true/false.
# Getting that wrong made this check fail against a correct database, which is the right direction
# for a gate to be wrong in but still wrong.
[ "$(q "SELECT pg_get_indexdef('idx_reviews_author_manager_role_legacy'::regclass)
         LIKE '%NOT author_erased%'")" = "t" ] \
  || fail "the legacy author index does not exclude erased authors"
echo "OK  the legacy author index excludes erased authors"

[ "$(q "SELECT count(*) FROM pg_proc WHERE proname='actor_is_erased'")" = "1" ] \
  || fail "actor_is_erased() is missing"
echo "OK  actor_is_erased()"

# ---------------------------------------------------------------------------
# 4. Report the checksums the artifact actually produced.
# ---------------------------------------------------------------------------
echo
echo "checksums recorded from the ARTIFACT:"
docker exec "$CONTAINER" psql -U postgres -d verify -c \
  "SELECT version, description, checksum FROM flyway_schema_history
    WHERE version::int >= 104 ORDER BY installed_rank"

echo
echo "PASS: the release artifact's migrations match the source and produce the expected objects."

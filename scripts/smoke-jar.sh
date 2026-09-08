#!/usr/bin/env bash
# Boots the shaded jar and asks it for one page. 1423 unit and integration tests passed today
# against a jar that could not start: they run on the module classpath, and the Jackson conflict
# only existed after the shade merge. Nothing covered "the artifact we ship actually runs".
set -uo pipefail
JAR="$(dirname "$0")/../../../../eclipse-workspace/WerkpagesBackend/WerkpagesBackend/RestApi/target/RestApi-1.0-SNAPSHOT-shaded.jar"
JAR="${1:-$JAR}"
[ -f "$JAR" ] || { echo "FAIL: no jar at $JAR"; exit 1; }
PORT="${SMOKE_PORT:-8899}"
set -a; . /home/noragrats/code-workspace/Werkpages/werkpages/.env.local; set +a
export APP_ENV=development HTTP_PORT="$PORT"
java -jar "$JAR" > /tmp/smoke-boot.log 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null' EXIT
for _ in $(seq 1 60); do
  if curl -sf -o /dev/null "http://localhost:$PORT/api/managers"; then
    echo "PASS: jar booted and served /api/managers"; exit 0
  fi
  kill -0 $PID 2>/dev/null || { echo "FAIL: process died"; tail -20 /tmp/smoke-boot.log; exit 1; }
  sleep 1
done
echo "FAIL: never became ready"; tail -20 /tmp/smoke-boot.log; exit 1

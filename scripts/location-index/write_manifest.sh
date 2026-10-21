#!/bin/bash
set -e
D="$1"
cd "$D"
{
  echo "release: $(basename "$D")"
  echo "built:   $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "engine:  sqlite3 (org.xerial:sqlite-jdbc 3.46.1.3), opened mode=ro&immutable=1"
  echo "files:"
  while read -r f; do
    sz=$(stat -c %s "$f")
    sum=$(sha256sum "$f" | cut -c1-64)
    printf "  - path: %s\n    bytes: %s\n    sha256: %s\n" "${f#./}" "$sz" "$sum"
  done < <(find . -type f -name '*.sqlite' | sort)
  echo "total_bytes: $(find . -type f -name '*.sqlite' -printf '%s\n' | paste -sd+ | bc)"
} > MANIFEST.yaml
echo "wrote $D/MANIFEST.yaml"

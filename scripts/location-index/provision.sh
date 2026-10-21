#!/bin/bash
#
# Provisions a location index release onto a host, and verifies it before anything points at it.
#
# Written for instance replacement: the artifacts live on the instance's disk, not in the container
# image and not in this repository, so a new box starts with none of them. Without this, the
# application silently falls back to the DuckDB parquet scan - correct answers, 2.3 seconds each -
# and nothing in the logs says why.
#
# Usage, from a machine that holds the release:
#   provision.sh <release-dir> <host>            e.g. provision.sh ~/loc-corpus/indexes/2026-10-20-v2 ubuntu@1.2.3.4
#
# Coverage note: street addresses in this release are CANADA ONLY. geography and routing are
# worldwide; addresses/ holds CA.sqlite and nothing else.
set -euo pipefail
SRC="${1:?usage: provision.sh <release-dir> <host>}"
HOST="${2:?usage: provision.sh <release-dir> <host>}"
REL=$(basename "$(readlink -f "$SRC")")
REMOTE=/var/lib/location-index
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "verifying the source release first, so a bad artifact never leaves this machine"
bash "$HERE/verify_indexes.sh" "$SRC"

echo
echo "free space on the target:"
ssh "$HOST" "df -h $REMOTE 2>/dev/null || df -h /var"
echo "a release is ~1.3 GiB; keep two for rollback, so ~3.25 GiB free is wanted. Ctrl-C to stop."
sleep 5

ssh "$HOST" "sudo mkdir -p $REMOTE/$REL/addresses && sudo chown -R \$(id -u):\$(id -g) $REMOTE"
for f in MANIFEST.yaml geography.sqlite routing.sqlite; do
  echo "copying $f"
  scp "$SRC/$f" "$HOST:$REMOTE/$REL/$f"
done
echo "copying addresses/CA.sqlite"
scp "$SRC/addresses/CA.sqlite" "$HOST:$REMOTE/$REL/addresses/CA.sqlite"

echo
echo "verifying on the target BEFORE moving the symlink - a truncated SQLite file opens cleanly"
scp "$HERE/verify_indexes.sh" "$HOST:/tmp/verify_indexes.sh"
ssh "$HOST" "bash /tmp/verify_indexes.sh $REMOTE/$REL"

ssh "$HOST" "ln -sfn $REMOTE/$REL $REMOTE/current"
echo
echo "provisioned and verified: $REMOTE/current -> $REL"
echo "the application still needs restarting to pick it up: handles are opened immutable=1 and"
echo "keep serving the old inode after the symlink moves."

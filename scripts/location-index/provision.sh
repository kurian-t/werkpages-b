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
#   provision.sh <release-dir> <user@host> [ssh-key]
#
# e.g. provision.sh ~/loc-corpus/indexes/2026-10-20-v2 ubuntu@203.0.113.10 ~/Downloads/key.pem
#
# The key is optional; without it ssh uses whatever your config or agent provides. Everything after
# the host is treated as the identity file, so the host must be a single user@host argument.
#
# Coverage note: street addresses in this release are CANADA ONLY. geography and routing are
# worldwide; addresses/ holds CA.sqlite and nothing else.
set -euo pipefail
SRC="${1:?usage: provision.sh <release-dir> <user@host> [ssh-key]}"
HOST="${2:?usage: provision.sh <release-dir> <user@host> [ssh-key]}"
KEY="${3:-}"

# One place that builds the ssh/scp options, so the key cannot be passed to one and forgotten on
# the other - which copies three quarters of a release and then fails on the verification step.
SSH_OPTS=()
if [ -n "$KEY" ]; then
  [ -f "$KEY" ] || { echo "no such key file: $KEY" >&2; exit 1; }
  SSH_OPTS=(-i "$KEY")
fi
SSH=(ssh "${SSH_OPTS[@]+"${SSH_OPTS[@]}"}")
SCP=(scp "${SSH_OPTS[@]+"${SSH_OPTS[@]}"}")

case "$HOST" in
  *" "*) echo "the host must be ONE argument like ubuntu@203.0.113.10; got: '$HOST'" >&2; exit 1 ;;
esac
REL=$(basename "$(readlink -f "$SRC")")
REMOTE=/var/lib/location-index
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "verifying the source release first, so a bad artifact never leaves this machine"
bash "$HERE/verify_indexes.sh" "$SRC"

echo
echo "free space on the target:"
"${SSH[@]}" "$HOST" "df -h $REMOTE 2>/dev/null || df -h /var"
echo "a release is ~1.3 GiB; keep two for rollback, so ~3.25 GiB free is wanted. Ctrl-C to stop."
sleep 5

"${SSH[@]}" "$HOST" "sudo mkdir -p $REMOTE/$REL/addresses && sudo chown -R \$(id -u):\$(id -g) $REMOTE"
for f in MANIFEST.yaml geography.sqlite routing.sqlite; do
  echo "copying $f"
  "${SCP[@]}" "$SRC/$f" "$HOST:$REMOTE/$REL/$f"
done
echo "copying addresses/CA.sqlite"
"${SCP[@]}" "$SRC/addresses/CA.sqlite" "$HOST:$REMOTE/$REL/addresses/CA.sqlite"

echo
echo "verifying on the target BEFORE moving the symlink - a truncated SQLite file opens cleanly"
"${SCP[@]}" "$HERE/verify_indexes.sh" "$HOST:/tmp/verify_indexes.sh"
"${SSH[@]}" "$HOST" "bash /tmp/verify_indexes.sh $REMOTE/$REL"

"${SSH[@]}" "$HOST" "ln -sfn $REMOTE/$REL $REMOTE/current"
echo
echo "provisioned and verified: $REMOTE/current -> $REL"
echo "the application still needs restarting to pick it up: handles are opened immutable=1 and"
echo "keep serving the old inode after the symlink moves."

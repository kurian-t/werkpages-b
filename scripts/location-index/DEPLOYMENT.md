# Location index artifacts: build, deploy, roll back

Written for: whoever deploys the location search indexes to the API host.

This covers the three SQLite artifacts that serve location autocomplete. It applies to **both**
backends, which read the same files from the same paths. Nothing here involves a database
migration: this feature adds no schema, so there is no Flyway work and no Werkpages migration
ordering to respect.

---

## 1. What the artifact set is

One directory per release, with a `current` symlink pointing at the live one.

```
/var/lib/location-index/
├── 2026-10-20-v2/
│   ├── MANIFEST.yaml          sizes and sha256 of every file, plus the source release
│   ├── geography.sqlite       countries, regions, localities, aliases
│   ├── routing.sqlite         which country partitions a query is worth opening
│   └── addresses/
│       └── CA.sqlite          Canadian street addresses
└── current -> 2026-10-20-v2
```

The applications are configured with the **`current` path**, never a release path, so a rollback is
a symlink move and a restart rather than a configuration change.

### Contents of 2026-10-20-v2

| File | Bytes | Rows |
| --- | --- | --- |
| `geography.sqlite` | 434,606,080 (414.5 MiB) | 5,236,056 entities (252 countries, 3,865 regions, 5,231,939 localities) + 4,149 aliases |
| `routing.sqlite` | 672,137,216 (641.0 MiB) | 14,727,598 token postings |
| `addresses/CA.sqlite` | 286,629,888 (273.4 MiB) | 1,420,046 addresses |
| **total** | **1,393,373,184 (1.30 GiB)** | |

`addresses/CA.sqlite` is built from places release `20261015T141112Z-CA-1454538`, named in the
manifest. It is pinned there deliberately: an earlier measurement was invalidated by joining a
payload from one release against an index built from another, so the release is passed in on the
command line and never discovered by the build.

---

## 2. Disk budget

| Item | Size |
| --- | --- |
| One release | 1.30 GiB |
| Two releases, so a rollback needs no transfer | 2.60 GiB |
| Transfer staging (one file at a time, largest) | 0.63 GiB |
| **Required free space before copying** | **≈ 3.25 GiB** |

Keep exactly two releases. A third is unused weight: a rollback goes to the previous one, and
anything older is rebuilt from source if it is ever wanted again.

> **Not yet verified:** free space on the API host. `df -h` from that host is still outstanding, and
> per the standing constraint nothing is copied to it until that number is known.

---

## 3. Configuration

Three environment variables per backend. All three are optional: with none of them set, every
lookup falls through to the existing DuckDB path exactly as it does today, which is what makes the
rollout safe to stage.

```
LOCATION_GEOGRAPHY_INDEX=/var/lib/location-index/current/geography.sqlite
LOCATION_ROUTING_INDEX=/var/lib/location-index/current/routing.sqlite
LOCATION_ADDRESS_INDEX_DIR=/var/lib/location-index/current/addresses
```

Measured against the production candidate JVM and corpus settings:

```
JAVA_TOOL_OPTIONS=-Xmx384m
LOCAL_CORPUS_POOL=3
LOCAL_CORPUS_THREADS=1
LOCAL_CORPUS_MEMORY=384MB
```

A path that is set but missing is logged as a warning and the index is left inactive. A path that
is absent is silent. Neither is ever reported to the reader as "this place does not exist".

---

## 4. Building a release

From the repository, with DuckDB on PATH:

```bash
cd RateMyManagerBackend/RateMyManagerBackend/scripts/location-index
REL=$(date -u +%Y-%m-%d)-v1
OUT=/path/to/staging/$REL

./build_address_index.sh CA \
    /home/noragrats/loc-corpus/CA/releases/20261015T141112Z-CA-1454538/payload.parquet \
    "$OUT/addresses"

# geography.sqlite and routing.sqlite carry over unchanged when only addresses are rebuilt.
# Hardlink rather than copy: same filesystem, no second gigabyte on disk.
ln /path/to/previous/geography.sqlite "$OUT/geography.sqlite"
ln /path/to/previous/routing.sqlite   "$OUT/routing.sqlite"

./write_manifest.sh "$OUT"
```

The build asserts that the published artifact's prefix range uses its index before it finishes. An
artifact without the index is correct and unusably slow, and nothing else in the pipeline would
notice.

---

## 5. Deploying

Run these yourself; they touch the production host.

1. **Check space first.**

   ```bash
   ssh <api-host> 'df -h /var'
   ```

   Stop here unless there is at least 3.25 GiB free.

2. **Copy the new release beside the current one**, never over it.

   ```bash
   scp -r /path/to/staging/2026-10-20-v2 <api-host>:/var/lib/location-index/
   ```

3. **Verify the transfer against the manifest**, on the host.

   ```bash
   ssh <api-host> 'cd /var/lib/location-index/2026-10-20-v2 && \
     sha256sum geography.sqlite routing.sqlite addresses/CA.sqlite'
   ```

   Compare each line with `MANIFEST.yaml`. A truncated SQLite file opens cleanly and fails later,
   in a query, as a corruption error, so this check is not optional.

4. **Move the symlink.**

   ```bash
   ssh <api-host> 'ln -sfn /var/lib/location-index/2026-10-20-v2 /var/lib/location-index/current'
   ```

5. **Restart the API containers.** The handles are opened `immutable=1` and held open, so a running
   process keeps serving the old inode after the symlink moves. Without a restart the deploy
   appears to have worked and has not.

6. **Confirm from the logs** that all three are active:

   ```
   geography index active: /var/lib/location-index/current/geography.sqlite
   routing index active:   /var/lib/location-index/current/routing.sqlite
   address indexes active: [CA]
   ```

7. **Confirm from the outside**, through the path the browser takes rather than the API port:

   - a country: `France`
   - a region: `Ontario`
   - a city with an accent: `Montréal`
   - a misspelling: `Tornto` should offer Toronto
   - a workplace: `Tim Hortons`
   - a Canadian address: `55 Gould Street, Toronto` should offer `55 Gould St / Toronto, ON`
   - a foreign search: `Shibuya` should offer Shibuya, Tokyo

Deploy mid-morning. The site takes real searches in the evening, and a restart during live search
traffic is the one avoidable cost here.

---

## 6. Rolling back

Two steps, no transfer, because the previous release is still on disk.

```bash
ssh <api-host> 'ln -sfn /var/lib/location-index/<previous-release> /var/lib/location-index/current'
# then restart the API containers
```

The restart is the part that matters, for the same reason as above.

**Rolling back further than one release:** unset the three environment variables and restart. Every
lookup returns to the DuckDB path. This is the escape hatch worth remembering, because it needs no
artifact on disk at all and is the fastest way out of a bad index.

---

## 7. What a bad release looks like

| Symptom | Cause | Action |
| --- | --- | --- |
| Suggestions are empty for everything | artifact missing or unreadable; the log says `set but missing` | fix the path or roll back |
| `address indexes active: []` | the `addresses` directory is empty or misnamed | the files must be `<CC>.sqlite`, uppercase |
| A query that used to work returns a different place | ranking or alias change in the geography build | roll back, then compare against the acceptance suite before rebuilding |
| Latency jumps from single-digit to hundreds of ms | the artifact was published without its indexes | rebuild; `build_address_index.sh` asserts this, so a hand-built file is the likely source |
| A corruption error in the logs under load | truncated transfer | re-verify sha256 against the manifest |

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

### The index paths

Three variables per backend, all optional. With none of them set every lookup falls through to the
DuckDB path, which is what makes the rollout safe to stage and the rollback a variable deletion.

```
LOCATION_GEOGRAPHY_INDEX=/var/lib/location-index/current/geography.sqlite
LOCATION_ROUTING_INDEX=/var/lib/location-index/current/routing.sqlite
LOCATION_ADDRESS_INDEX_DIR=/var/lib/location-index/current/addresses
```

They require the release to be visible INSIDE the container, so the service also needs:

```yaml
    volumes:
      - /var/lib/location-index/current:/var/lib/location-index/current:ro
```

**Address coverage is Canada only.** `geography.sqlite` and `routing.sqlite` are worldwide;
`addresses/` contains `CA.sqlite` and nothing else. A query naming a street in any other country
returns no address suggestion, by construction, and that is reported through
`addressCoverage()` rather than looking like a failure.

### What production actually runs

Taken from the live containers, not from a candidate plan:

```
APP_ENV=production
USE_AWS_SECRETS=true
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50 -XX:MaxDirectMemorySize=192m -Xmx384m
LOCATION_CORPUS_THREADS=4
LOCATION_LOCAL_SEARCH=true
LOCAL_CORPUS_COUNTRIES=2
mem_limit=2g        (frontends 128m)
```

Two of these are easy to get wrong:

* `LOCATION_CORPUS_THREADS` and `LOCAL_CORPUS_THREADS` are **different variables**. Production sets
  the former to 4 and does not set the latter at all. An earlier version of this document named the
  latter with a value of 1, which configures nothing production uses.
* `LOCATION_LOCAL_SEARCH=true` with `LOCAL_CORPUS_COUNTRIES=2` materially changes which code path
  serves a search. Any measurement taken without both set is not measuring production. That mistake
  cost a release: 5ms measured locally, 2.3 seconds in production, because the configuration under
  test did not exist on the host.

### When an index is missing

A path that is **blank** means "not using one" and is silent.

A path that is **set and does not resolve** is logged at SEVERE, naming the path, the consequence,
and this directory's `verify_indexes.sh`. That case means somebody intended to publish the index
and the file is not there - a failed copy, a moved symlink, a replaced instance - and it degrades
search while the application otherwise looks healthy.

Startup always emits one summary line:

```
location index status: geography=active routing=active addresses=[CA]
```

Neither a missing index nor an unreachable one is ever reported to the reader as "this place does
not exist".

---

## 4. Building a release

From the repository, with DuckDB on PATH:

```bash
cd RateMyManagerBackend/RateMyManagerBackend/scripts/location-index
REL=$(date -u +%Y-%m-%d)-v1
OUT=/path/to/staging/$REL

./build_address_index.sh CA \
    <corpus-root>/CA/releases/<release>/payload.parquet \
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

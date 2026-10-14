#!/usr/bin/env python3
"""
The location picker's acceptance harness.

WHY THIS EXISTS, AND WHY IT IS NOT A UNIT TEST

Both backend suites were green throughout every defect found in this area: a street suffix that
made a dealership unfindable, qualifier stripping that hid "New York Life" and "Canada Goose", and
a deduplication key that silently dropped distinct workplaces. Those suites prove the code does
what it did yesterday. They say nothing about whether somebody can find where they work.

So this measures the thing that actually matters, end to end, against the LIVE corpus:

  * did the intended workplace reach the candidate set at all
  * at what rank
  * how long it took, cold and warm
  * what a genuine miss costs, which is the worst case and the one users feel

CANDIDATE MEMBERSHIP IS THE POINT

Ranking cannot recover a candidate that retrieval never returned. A search that is fast because it
looked in one place is worse than a slow one, and it passes every latency check. So `found` is
asserted separately from `rank`, and a case that returns quickly without the answer is a FAIL, not
a slow pass.

NOT PART OF `mvn verify`, DELIBERATELY

Every case reads S3. Putting this in the normal suite would spend egress on every CI run and make
the build depend on a bucket. It is run on demand, against a backend that is already up.

    python3 tools/location_search_bench.py --base http://localhost:8889
"""
import argparse, json, statistics, sys, time, urllib.parse, urllib.request

# (query, country, expected label substring or None for "a genuine miss")
CASES = [
    ("8581 Laguna Grove Drive, Elk Grove, California", "United States", "Elk Grove Subaru"),
    ("8581 Laguna Grove Dr, Elk Grove, California",    "United States", "Elk Grove Subaru"),
    ("8581 Laguna Grove, Elk Grove",                   "United States", "Elk Grove Subaru"),
    # Eight distinct employers share this address. The deduplication key used to collapse them to
    # two, so six real workplaces were dropped before anybody could pick one.
    ("1400 Ottawa Street South, Kitchener",            "Canada",        "Walmart"),
    ("Madison Square Garden",                          "United States", "Madison Square Garden"),
    ("Place Ville Marie",                              "Canada",        "Place Ville Marie"),
    # The qualifier-stripping failures. A region or country word inside a NAME.
    ("New York Life",                                  "United States", "New York Life"),
    ("Canada Goose",                                   "Canada",        "Canada Goose"),
    ("North Face",                                     "United States", "North Face"),
    ("Texas Roadhouse",                                "United States", "Texas Roadhouse"),
    # The reviewer's counterexample: the name carries it AND the location repeats it.
    ("New York Life, New York",                        "United States", "New York Life"),
    # Qualifier genuinely trailing, which must keep working.
    ("Toronto, Ontario",                               "Canada",        "Toronto"),
    # Accented and non-English input.
    ("Montréal, Quebec",                               "Canada",        "Montr"),
    # A genuine miss. The cost of this is the worst case users feel.
    ("Zzzqqx Nonexistent Holdings",                    "United States", None),
]

def log_size(path):
    try:
        import os
        return os.path.getsize(path)
    except OSError:
        return 0

def decision_since(path, offset):
    """The backend's own account of the last suggestion: did the buildings file get read."""
    try:
        with open(path, "r", errors="replace") as fh:
            fh.seek(offset)
            tail = fh.read()
    except OSError:
        return "?"
    lines = [l for l in tail.splitlines() if l.startswith("location-suggest ")]
    if not lines:
        return "?"
    last = lines[-1]
    for part in last.split():
        if part.startswith("places="):
            return part.split("=", 1)[1]
    return "?"

def manifest(base):
    """Recorded with every run: results are only comparable within one corpus release."""
    try:
        with open("/tmp/werkpages-backend.log", "r", errors="replace") as fh:
            for line in fh:
                if "location corpus ready, release" in line:
                    return line.strip().split("release", 1)[1].strip()
    except OSError:
        pass
    return "unknown"

def search(base, q, country, timeout=180):
    url = base + "/api/company-locations/suggest?" + urllib.parse.urlencode({"q": q, "country": country})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            body = json.load(r)
    except Exception as err:
        return (time.perf_counter() - t0), None, str(err)
    return (time.perf_counter() - t0), body, None

def rank_of(rows, expected):
    if not expected:
        return None
    for i, row in enumerate(rows):
        if expected.lower() in (row.get("label") or "").lower():
            return i + 1
    return None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8889")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--log", default="/tmp/werkpages-backend.log",
                    help="backend log, read for the per-request search decision")
    args = ap.parse_args()

    results, failures = [], 0
    print(f"{'query':<44}{'uncached':>9}{'repeat':>8}{'n':>4} {'rank':>4}  {'places':>8}  verdict")
    print("-" * 92)
    for q, country, expected in CASES:
        before = log_size(args.log)
        cold, rows, err = search(args.base, q, country)
        # What the backend says it did, rather than what the result shape implies. An empty list
        # from a skipped search and an empty list from a search that ran are different facts.
        places_state = decision_since(args.log, before)
        warm, rows2, _  = search(args.base, q, country)
        rows = rows if rows is not None else []
        n = len(rows)
        rank = rank_of(rows, expected)

        if err:
            verdict = f"ERROR {err[:30]}"
        elif expected is None:
            # A miss is allowed to return nothing; it is NOT allowed to be slow and silent
            # forever, so the latency is what this row is really reporting.
            verdict = "miss (expected)"
        elif rank is None:
            verdict = "FAIL not in candidates"
        elif rank > 3:
            verdict = f"weak rank {rank}"
        else:
            verdict = "ok"
        if verdict.startswith("FAIL") or verdict.startswith("ERROR"):
            failures += 1

        results.append({"places": places_state,
                        "query": q, "country": country, "expected": expected,
                        "cold_s": round(cold, 2), "warm_s": round(warm, 2),
                        "n": n, "rank": rank, "verdict": verdict})
        print(f"{q[:42]:<44}{cold:>8.2f}s{warm:>7.2f}s{n:>4} {str(rank or '-'):>4}  {places_state:>8}  {verdict}")

    # Recorded AFTER the cases, not before: the corpus connects lazily, so on a freshly started
    # backend the release is not in the log until the first query has asked for it. Results are
    # only comparable within one release, so an "unknown" here invalidates the comparison.
    print(f"corpus release: {manifest(args.base)}")
    colds = [r["cold_s"] for r in results]
    colds.sort()
    p95 = colds[min(len(colds) - 1, int(round(0.95 * (len(colds) - 1))))]
    print("-" * 92)
    print(f"uncached: median {statistics.median(colds):.2f}s  p95 {p95:.2f}s  max {max(colds):.2f}s"
          f"   |  candidate failures: {failures}/{len(CASES)}")
    print("NOTE: 'uncached' is a result-cache miss in a warm process. It is NOT a cold process or a\n"
          "      cold storage cache; the first row absorbs process start and is not comparable.")
    if args.json:
        print(json.dumps(results, indent=2))
    # A non-zero exit when the intended workplace did not reach the candidate set at all.
    return 1 if failures else 0

if __name__ == "__main__":
    sys.exit(main())

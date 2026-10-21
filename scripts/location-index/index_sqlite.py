"""
Adds the indexes a published address artifact needs, then vacuums it.

There is no sqlite3 CLI on this host, and DuckDB's sqlite writer creates the table without any
indexes. A build that skips this step produces an artifact that is perfectly correct and answers
every prefix range with a full table scan, which nothing else in the build would notice.
"""
import sqlite3
import sys

db = sys.argv[1]
c = sqlite3.connect(db)
c.execute("CREATE INDEX IF NOT EXISTS addr_k   ON addr(addr_key)")
c.execute("CREATE INDEX IF NOT EXISTS street_k ON addr(street_key)")
c.execute("ANALYZE")
c.commit()
c.isolation_level = None
c.execute("VACUUM")

rows = c.execute("SELECT count(*) FROM addr").fetchone()[0]
# Proof the planner actually uses the index for the one access pattern that matters.
plan = " ".join(
    str(r) for r in c.execute(
        "EXPLAIN QUERY PLAN SELECT address_display FROM addr "
        "WHERE addr_key >= 'a' AND addr_key < 'b'"))
assert "addr_k" in plan, "the prefix range is not using the index: " + plan
print(f"rows={rows:,}  index_used=yes")
c.close()

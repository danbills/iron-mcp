#!/usr/bin/env bash
# Statement and branch coverage of iron-mcp-core on the JVM (scoverage; Scala 3's own -coverage-out). The build
# fails below 100% (coverageMinimum* on coreJVM). The report is kept in target/coverage-report.
#
# Usage: scripts/coverage.sh            # instrument, run every core test, report
#        scripts/coverage.sh --report   # print the kept report's per-file table again
#
# How it stays out of every other build:
#  - Coverage is turned on for coreJVM alone, with `set`, so Native, JS and the other modules are never instrumented.
#  - `sbt` is sbt 2's native client: each command runs in the build's long-lived server, and a `set` stays in that
#    server's session for every later command. The script ends (on success or failure) with `session clear-all` and
#    `coreJVM/clean`, so the next ordinary build compiles plain classes.
#  - Each run adds a per-run -Xmacro-settings nonce (it changes nothing in the output) so its compile is a cache miss:
#    an sbt 2 cache hit restores classes without running the compiler, which then writes no statement map
#    (scoverage-data/scoverage.coverage), and nothing is measured. scripts/purge-instrumented.py then removes the
#    instrumented compiles this left in sbt's caches.
#  - Tests fork: Scala 3 flushes its measurements when the JVM exits, which the server's JVM never does before the
#    report is read.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
kept="target/coverage-report"

restore() {
  sbt -batch "session clear-all; coreJVM/clean" >/dev/null 2>&1 || echo "warning: could not reset the sbt session; run: sbt \"session clear-all; coreJVM/clean\"" >&2
  python3 scripts/purge-instrumented.py
}

if [ "${1:-}" != "--report" ]; then
  rm -rf "$kept"
  trap restore EXIT
  run="$(date +%s%N)"
  status=0
  sbt -batch "session clear-all; coreJVM/clean; \
set core.jvm / Compile / scalacOptions += \"-Xmacro-settings:iron-mcp-coverage-run=$run\"; \
set core.jvm / coverageEnabled := true; set core.jvm / Test / fork := true; \
coreJVM/Test/testFull; coreJVM/coverageReport" || status=$?
  report="$(find target/out -type d -name scoverage-report -path '*iron-mcp-core*' 2>/dev/null | head -1)"
  if [ -n "$report" ]; then mkdir -p "$(dirname "$kept")" && cp -r "$report" "$kept"; fi
fi

[ -f "$kept/scoverage.xml" ] || { echo "no coverage report: the run failed before coverageReport" >&2; exit 1; }
python3 - "$kept/scoverage.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
rows = {}
for s in root.iter("statement"):
    if s.get("ignored") == "true":
        continue
    f = s.get("source", "").split("/scala/", 1)[-1]
    hit = int(s.get("invocation-count", "0")) > 0
    st, sc, bt, bc = rows.get(f, (0, 0, 0, 0))
    branch = s.get("branch") == "true"
    rows[f] = (st + 1, sc + hit, bt + branch, bc + (branch and hit))
print(f"{'file':52} {'statements':>12} {'branches':>10}")
for f in sorted(rows):
    st, sc, bt, bc = rows[f]
    print(f"{f:52} {sc:>5}/{st:<6} {bc:>4}/{bt:<5}{'' if (sc, bc) == (st, bt) else '  <--'}")
print(f"\ntotal: statements {root.get('statement-rate')}%, branches {root.get('branch-rate')}%")
PY
echo "HTML: $kept/index.html   uncovered lines: scripts/uncovered.py"
exit "${status:-0}"

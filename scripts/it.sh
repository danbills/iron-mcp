#!/usr/bin/env bash
# End-to-end tests: MCP Inspector's CLI (pinned in it/package.json, run with bunx, --protocol-era modern) against
# the fixture server (modules/fixture), once as the native binary and once on the JVM.
#
# Needs bun. The first run installs it/node_modules (bun install --frozen-lockfile); after that nothing touches the
# network. Not part of Test/testFull: it spawns processes and needs bun.
#
# Usage: scripts/it.sh            # native and JVM
#        scripts/it.sh native     # one of them
#        scripts/it.sh jvm
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
which="${1:-all}"
command -v bun >/dev/null || { echo "scripts/it.sh needs bun (https://bun.sh)" >&2; exit 1; }

native="$root/target/out/native0.5/scala-3.9.0/iron-mcp-fixture/iron-mcp-fixture"
jvm="$root/target/it/fixture-jvm"

# sbt 2 prints virtual paths: ${OUT} is target/out, ${CSR_CACHE} the coursier cache, and jars carry a >sha256-...
# suffix (AGENTS.md, "sbt 2 Behavior That Bites").
jvm_classpath() {
  sbt -batch -no-colors "export fixtureJVM/Runtime/fullClasspath" 2>&1 | grep '^List(' | tail -1 \
    | sed -e 's/^List(//' -e 's/)[^)]*$//' | tr -d ' ' | tr ',' '\n' \
    | sed -e "s#\${OUT}#$root/target/out#" -e "s#\${CSR_CACHE}#$HOME/.cache/coursier/v1#" -e "s#\${IVY_HOME}#$HOME/.ivy2#" -e 's#>.*$##' \
    | paste -sd:
}

case "$which" in
  all|native|jvm) ;;
  *) echo "usage: scripts/it.sh [native|jvm]" >&2; exit 2 ;;
esac

targets=()
if [ "$which" != jvm ]; then
  sbt -batch "fixtureNative/nativeLink" >/dev/null
  targets+=("native:$native")
fi
if [ "$which" != native ]; then
  sbt -batch "fixtureJVM/compile" >/dev/null
  cp="$(jvm_classpath)"
  [ -n "$cp" ] || { echo "could not read fixtureJVM's classpath from sbt" >&2; exit 1; }
  mkdir -p "$(dirname "$jvm")"
  printf '#!/bin/sh\nexec java -cp "%s" ironmcp.fixture.Main\n' "$cp" > "$jvm"
  chmod +x "$jvm"
  targets+=("jvm:$jvm")
fi

(cd it && bun install --frozen-lockfile >/dev/null)

status=0
for target in "${targets[@]}"; do
  echo "== fixture on the ${target%%:*}"
  (cd it && FIXTURE="${target#*:}" bun test) || status=1
done
exit "$status"

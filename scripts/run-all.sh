#!/usr/bin/env bash
# Runs every example server in one JVM (ironmcp.extapps.RunAll), outside sbt,
# so the sbt server stays free while they run. Prints the SERVERS JSON for
# basic-host. Env: BASE_PORT (3201), EXAMPLE (only that one).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
# sbt 2 prints virtual paths: ${OUT} is target/out, ${CSR_CACHE} the coursier
# cache, ${IVY_HOME} ~/.ivy2 (publishLocal), and jars carry a >sha256-... suffix.
raw="$(sbt -batch -no-colors "export runAll/Runtime/fullClasspath" 2>&1 | grep '^List(' | tail -1)"
cp="$(printf '%s' "$raw" | sed -e 's/^List(//' -e 's/)[^)]*$//' | tr -d ' ' | tr ',' '\n' \
  | sed -e "s#\${OUT}#$root/target/out#" -e "s#\${CSR_CACHE}#$HOME/.cache/coursier/v1#" -e "s#\${IVY_HOME}#$HOME/.ivy2#" -e 's#>.*$##' | paste -sd:)"
[ -n "$cp" ] || { echo "could not read the classpath from sbt" >&2; exit 1; }
exec java -Xss4m -cp "$cp" ironmcp.extapps.RunAll

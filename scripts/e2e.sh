#!/usr/bin/env bash
# One e2e run: starts every example server (scripts/run-all.sh) and basic-host
# (scripts/basic-host.sh) listing them, runs ext-apps' servers.spec.ts against
# them, and stops both. Extra arguments go to Playwright, e.g.
#
#   scripts/e2e.sh --grep "Quickstart|Vanilla JS"
#
# Env: GOLDENS (ts-local | upstream), RUN (results/<RUN>.json), WORKERS,
# EXAMPLE (start only that server), EXT_APPS (~/ext-apps).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
ext="${EXT_APPS:-$HOME/ext-apps}"
logs="$root/e2e/results"
mkdir -p "$logs"

pids=()
cleanup() { for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done; wait 2>/dev/null || true; }
trap cleanup EXIT

if ss -ltn | grep -qE ':(8080|8081) '; then
  echo "ports 8080/8081 are taken: stop the other basic-host first" >&2
  exit 1
fi

"$root/scripts/run-all.sh" > "$logs/servers.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 180); do
  grep -q '^SERVERS=' "$logs/servers.log" && break
  if ! kill -0 "${pids[0]}" 2>/dev/null; then cat "$logs/servers.log" >&2; exit 1; fi
  sleep 1
done
servers="$(grep '^SERVERS=' "$logs/servers.log" | head -1 | cut -d= -f2-)"
[ -n "$servers" ] || { echo "the servers did not start" >&2; cat "$logs/servers.log" >&2; exit 1; }
echo "servers: $servers"

"$root/scripts/basic-host.sh" "$servers" > "$logs/basic-host.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 120); do
  curl -s -o /dev/null http://localhost:8080 && break
  sleep 1
done

# Our specs import @playwright/test; resolve it to ext-apps' copy, the one
# that loads the config, or Playwright refuses to run.
mkdir -p "$root/e2e/node_modules"
ln -sfn "$ext/node_modules/@playwright" "$root/e2e/node_modules/@playwright"

cd "$ext"
npx playwright test -c "$root/e2e/upstream.config.mjs" "$@"

#!/usr/bin/env bash
# Builds and serves ext-apps' basic-host (~/ext-apps, or $EXT_APPS) for these
# servers, on :8080 (host) and :8081 (sandbox).
#
# basic-host's MCP client (@modelcontextprotocol/client 2.0.0) defaults to the
# 2025-11-25 `initialize` handshake and never tries `server/discover`. iron-mcp
# speaks 2026-07-28 only, so e2e/basic-host-negotiate.patch turns on the
# client's own negotiation (`versionNegotiation: { mode: "auto" }`): probe
# `server/discover`, fall back to `initialize`. Nothing else in the host (its
# AppBridge, sandbox, UI) changes. `--revert` undoes the patch.
#
# Usage: scripts/basic-host.sh [--revert] [SERVERS-json]
#   SERVERS defaults to what scripts/run-all.sh prints for BASE_PORT 3201.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
ext="${EXT_APPS:-$HOME/ext-apps}"
patch="$root/e2e/basic-host-negotiate.patch"

if [ "${1:-}" = "--revert" ]; then
  git -C "$ext" apply -R "$patch" && echo "reverted the basic-host patch"
  exit 0
fi

if git -C "$ext" apply --check "$patch" 2>/dev/null; then
  git -C "$ext" apply "$patch"
  echo "applied $patch"
fi
(cd "$ext" && npm run --workspace examples/basic-host build >/dev/null)

servers="${1:-${SERVERS:-}}"
if [ -z "$servers" ]; then
  servers='["http://localhost:3201/mcp"]'
fi
cd "$ext/examples/basic-host"
SERVERS="$servers" exec bun serve.ts

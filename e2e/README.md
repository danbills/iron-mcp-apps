# End-to-end checks

ext-apps' own suite (`tests/e2e/servers.spec.ts`, unmodified) runs against
these servers inside ext-apps' reference host, basic-host.

```bash
# once: in ~/ext-apps
npm ci && npx playwright install chromium chromium-headless-shell

# the servers (ports 3201+) and the host (8080, 8081), each in its own shell
scripts/run-all.sh
scripts/basic-host.sh '<the SERVERS json run-all.sh printed>'

# the suite, from ~/ext-apps so it shares ext-apps' @playwright/test
cd ~/ext-apps && npx playwright test -c ~/iron-mcp-ext-apps/e2e/upstream.config.mjs
```

- **basic-host patch.** `scripts/basic-host.sh` applies `basic-host-negotiate.patch`: basic-host's MCP
  client defaults to the 2025-11-25 handshake, and iron-mcp speaks 2026-07-28 only. The patch turns on the
  client's own negotiation and nothing else. `scripts/basic-host.sh --revert` undoes it.
- **Goldens.**
  - `goldens/ts-local/` holds screenshots of upstream's TypeScript examples, made on this machine with
    `--update-snapshots` (the suite's own goldens were made in Docker). `GOLDENS=upstream` compares
    against those instead.
  - The TypeScript examples pass against both sets.
- **Results.** `results/<RUN>.json` (set `RUN=`); failure screenshots go to `results/artifacts/`.

#!/usr/bin/env bash
# Launch the OpenWarden contributor-autopilot MCP server (local stdio).
#
# Registered by /.mcp.json. Runs entirely on the contributor's machine + their
# own gh auth. No SaaS, no shared token, no telemetry. Fully optional — agents
# can just Read kb/ if you don't want to run this.
#
# First run: install deps + build once.
#   cd .claude/mcp-server && npm install && npm run build
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

# Build on demand if dist is missing (dev convenience; no-op once built).
if [ ! -f "dist/server.js" ]; then
  if [ -d "node_modules" ]; then
    npm run build >&2 || {
      echo "[openwarden-kb] build failed; run 'npm install && npm run build' in $DIR" >&2
      exit 1
    }
  else
    echo "[openwarden-kb] not installed. Run: (cd $DIR && npm install && npm run build)" >&2
    exit 1
  fi
fi

exec node dist/server.js

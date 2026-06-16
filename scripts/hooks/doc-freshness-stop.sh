#!/usr/bin/env bash
# OpenWarden Stop hook — doc-freshness nudge.
# Fires when source under child-android/, parent-kmp/, or proto/ is uncommitted,
# reminding to keep docs/ + ADRs in sync in the same commit. No network, no telemetry.
# Outputs a JSON {systemMessage} when relevant; silent otherwise.
set -euo pipefail

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
command -v git >/dev/null 2>&1 || exit 0

# Only nudge if behavior-bearing source actually changed (avoid per-turn spam).
changed=$(git status --porcelain -- child-android parent-kmp proto 2>/dev/null | head -1 || true)
[[ -z "$changed" ]] && exit 0

printf '%s\n' '{"systemMessage":"Doc-freshness: source under child-android/, parent-kmp/, or proto/ is uncommitted. Did this change touch behavior described in a docs/ file or an ADR? If yes, update the doc in the same commit (CLAUDE.md: docs are the spec; v2/v3 frozen-design needs an ADR)."}'
exit 0

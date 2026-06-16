#!/usr/bin/env bash
# OpenWarden PostToolUse hook (Edit|Write) — code/doc consistency flag.
# When an edited source file lives under child-android/ or parent-kmp/, inject a
# reminder for the model to verify the matching docs/ spec + ADRs still hold and
# update them in the same commit. Read-only, no network, no telemetry.
# Reads the tool-call JSON on stdin; emits hookSpecificOutput.additionalContext
# when relevant, silent otherwise.
set -euo pipefail

input=$(cat 2>/dev/null || true)
[[ -z "$input" ]] && exit 0

have_jq() { command -v jq >/dev/null 2>&1; }

if have_jq; then
  f=$(printf '%s' "$input" | jq -r '.tool_input.file_path // .tool_response.filePath // empty' 2>/dev/null || true)
else
  f=$(printf '%s' "$input" | grep -oE '"file_path"[^,}]*' | head -1 | sed -E 's/.*"file_path"[^"]*"([^"]+)".*/\1/' || true)
fi
[[ -z "${f:-}" ]] && exit 0

# Scope: only the two app source trees.
case "$f" in
  *child-android/*|*parent-kmp/*) ;;
  *) exit 0 ;;
esac
# Scope: only source files.
case "$f" in
  *.kt|*.kts|*.swift|*.java) ;;
  *) exit 0 ;;
esac

REMINDER="Doc-sync: you edited a source file under child-android/ or parent-kmp/. Before finishing, confirm the matching docs/ spec (e.g. PROTOCOL.md, CRYPTO.md, DEFENSES.md, ARCHITECTURE.md, DNS_RESOLVER.md) and any ADR still hold; if behavior changed, update the doc in the SAME commit. If the change would contradict a doc or ADR, fix the doc or open an ADR. Keep CLAUDE.md non-negotiables: fail-closed, no content monitoring, no SaaS/telemetry."

if have_jq; then
  jq -nc --arg c "$REMINDER (file: $f)" \
    '{hookSpecificOutput:{hookEventName:"PostToolUse",additionalContext:$c}}'
else
  printf '{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"%s"}}\n' "$REMINDER"
fi
exit 0

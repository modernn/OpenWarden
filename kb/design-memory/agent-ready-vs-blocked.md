---
id: agent-ready-vs-blocked
title: The agent-ready vs agent-blocked gating rule for autonomous contributors
type: design-memory
tags: [governance, agents, agent-ready, agent-blocked, codeowners, contribution]
status: active
created: 2026-06-15
updated: 2026-06-15  # de-hardcoded "v1 milestone" -> active milestone (ADR-018)
expires: null
source_pr: null
---

# agent-ready vs agent-blocked

The contributor-autopilot flow lets many independent AI sessions pick up work safely. The
safety hinge is a single gating rule, expressed with GitHub labels + CODEOWNERS.

## The rule
An autonomous agent may **implement** an issue only when it is **all** of:
- open and on an **active milestone** (the current/next `v0.x` rung in `docs/ROADMAP.md`),
- labeled `agent-ready`,
- labeled a role area it owns: `area:child-android`, `area:parent-kmp`, `area:dns`, or
  `area:infra`. **`area:proto` is never agent-implementable** — proto is the wire-format /
  crypto surface and is always `agent-blocked` (human + ADR + tests),
- NOT labeled `agent-blocked` and NOT already `claimed`.

An issue is **`agent-blocked` (human-only)** when it touches any sensitive surface:
- crypto / `proto/` / wire format / `BundleVerifier.kt`
- policy enforcement (`PolicyEnforcer.kt`, `PolicyService.kt`) and provisioning
- CI / `.github/` / `.claude/` / `CODEOWNERS` / `AGENTS.md` / `CLAUDE.md` / `SECURITY.md`

These map to the CODEOWNERS-gated paths — the same boundary, enforced two ways (label +
required maintainer review). If an `agent-ready` issue turns out to require an
`agent-blocked` change once you dig in, **STOP and hand back to a human**; do not push
through.

## Why it's a design memory
The label is advisory; CODEOWNERS is the hard gate. An agent must respect the label
*proactively* (don't even start blocked work) so a human isn't left to catch it at review.
Crypto/protocol/policy changes additionally REQUIRE an ADR + tests before merge.

## Source / citations
- `CLAUDE.md`, `AGENTS.md` — non-negotiables + "do NOT touch crypto/proto/provisioning/
  policy/CI/.claude/CODEOWNERS/AGENTS.md/CLAUDE.md without a maintainer + ADR."
- `.github/ISSUE_TEMPLATE/agent-task.yml` — "Crypto, proto, provisioning, and
  policy-enforcement work is NOT agent-ready."
- `.github/CODEOWNERS` — the enforced gate.
- `docs/GOVERNANCE.md` — governance model.

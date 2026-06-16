---
name: openwarden
description: OpenWarden contributor front door (for developers). Asks what you want to do — run locally, write tests, implement a feature, fix a bug, or improve docs — and routes each path to the right skill, a worktree branch, tests, checks, and a signed conventional PR that honors the non-negotiables. Triggers on "/openwarden", "I want to contribute", "help me pick an issue", "get me started on OpenWarden", "how do I help".
---

# OpenWarden — contributor front door

Orchestrator skill. It does NOT reimplement the building-block skills
(`bootstrap-repo`, `test-openwarden-unit`, `test-openwarden-e2e-emulator`,
`provision-openwarden-emulator`, `verify-openwarden-spec`, `codex-second-opinion`)
— it routes to them.

## Step 0 — orient (always)
- Confirm environment: if tools may be missing, run `/bootstrap-repo`, else
  `./scripts/verify-env.sh`.
- Read [`AGENTS.md`](../../../AGENTS.md) + [`CLAUDE.md`](../../../CLAUDE.md) rules.
- Check worktrees: `git worktree list`; never work two branches in one folder.

## Step 1 — ask what they want to do
Use **AskUserQuestion** with these options:
1. **Run it locally** 2. **Write tests** 3. **Implement a feature**
4. **Fix a bug** 5. **Improve docs**

## Step 2 — route
- **Run locally** → walk [`docs/GETTING_STARTED.md`](../../../docs/GETTING_STARTED.md);
  for the DPC, `/provision-openwarden-emulator`. Done.
- **Write tests** → [`docs/TESTING.md`](../../../docs/TESTING.md); pick an under-tested
  area (policy bundle sign/verify, JCS, policy logic); finish with
  `/test-openwarden-unit`. (Tests REQUIRED for crypto/protocol/policy.)
- **Implement a feature** → `gh issue list --label "enhancement,good first issue,agent-ready"`;
  pick one. **Check `docs/adr/` + the doc tier**: if it's v2/v3 frozen-design, STOP —
  it needs an ADR first; surface that. For crypto/protocol, offer `/codex-second-opinion`.
- **Fix a bug** → `gh issue list --label bug`; reproduce; write a **regression test**
  first; then fix; `/test-openwarden-unit`.
- **Improve docs** → edit under `docs/`; match `docs/SIMPLIFY.md` tone; for spec docs
  run `/verify-openwarden-spec`.

## Step 3 — common closing path (every code/doc change)
1. Worktree branch: `git worktree add -b <conventional-branch> ../OpenWarden-<slug> main`.
2. Make the change **with tests**.
3. `cd parent-kmp && ./gradlew check` (or the relevant module build) — must be green.
4. Commit **signed + DCO, conventional**: `git commit -S -s -m "feat: …"`. Never `--no-verify`.
5. Update any touched `docs/`/ADR in the same change.
6. Push the branch; open a PR following `.github/PULL_REQUEST_TEMPLATE.md`. (`gh pr create` is in the ask-list — confirm with the user.)

## Guardrails (enforce; refuse otherwise)
- Never touch crypto / `proto` / provisioning / policy-enforcement, CI, `.claude/`
  hooks, `CODEOWNERS`, `AGENTS.md`, or `CLAUDE.md` without a maintainer + an ADR
  (these are CODEOWNERS-gated and not agent-ready).
- Never add a subscription / SaaS / telemetry / content-monitoring dependency.
- Fail-closed always; never weaken a defense to make something pass.
- Crypto/protocol changes REQUIRE tests and an ADR.

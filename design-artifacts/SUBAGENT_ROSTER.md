# SUBAGENT_ROSTER.md — OpenWarden Standardized Subagent Roster

> **Status:** Design proposal for maintainer review.
> **Location:** `design-artifacts/` — this is a DRAFT. Do NOT install into `.claude/agents/` without a maintainer-approved PR.
> **Worktree:** `C:/src/OpenWarden-agents` on branch `research/agent-taxonomy`.
> **Date:** 2026-06-15

---

## 1. Current Roster Audit

### Existing agents (`.claude/agents/`)

| Agent | Model | Tool scope | Area label | Notes / Gaps |
|---|---|---|---|---|
| `child-dpc` | sonnet | Read, Grep, Glob, Edit, Write, Bash | `area:child-android` | Well-defined; stops correctly at crypto/policy |
| `parent-ui` | sonnet | Read, Grep, Glob, Edit, Write, Bash | `area:parent-kmp` | Well-defined; stops at `:proto`/crypto |
| `test-writer` | haiku | Read, Grep, Glob, Edit, Write, Bash | any area | Cross-cutting; may test but not author crypto; good |
| `docs` | sonnet | Read, Grep, Glob, Edit, Write, Bash | docs only | Docs-only; well-scoped |
| `crypto-reviewer` | opus | Read, Grep, Glob (read-only) | agent-blocked crypto | Read-only; correctly human-gated; good |

### Gap analysis

| Work type | Existing coverage | Gap? |
|---|---|---|
| Kotlin child DPC feature work | `child-dpc` | Covered |
| KMP parent UI feature work | `parent-ui` | Covered |
| Test writing (unit) | `test-writer` | Partially — no distinction between unit, integration, and e2e; all lumped |
| Documentation | `docs` | Covered |
| Crypto/protocol review (read-only) | `crypto-reviewer` | Covered |
| CI / build scripting (`scripts/`, `.github/`) | None | **GAP** — agent-blocked anyway; but there is no read-only surveyor role |
| Refactor / migration (rename, extract module, clean dead code) | None | **GAP** — currently falls to child-dpc or parent-ui by accident |
| DNS filter / networking plumbing (`area:dns`) | None | **GAP** — v0.4+ work; no dedicated role; currently under-served |
| Marketing site (`site/`) | None | **GAP** — exists in repo; no agent scoped to it |
| Security/threat model review (non-crypto) | None | **PARTIAL GAP** — `crypto-reviewer` covers protocol; app-layer security audits have no home |
| Integration / E2E test writing | `test-writer` (undifferentiated) | **MINOR GAP** — e2e/connected tests for DPC provisioning are meaningfully different in tooling and risk |

### Key structural observations

1. The existing five agents are clean and well-guardrailed. The format (YAML front matter + role body + scope/workflow/stuck sections) is consistent and should be preserved exactly.
2. All existing agents enforce the shared non-negotiables inline in their body text. This creates duplication and drift risk — a shared block extracted by reference would be better.
3. The `test-writer` model choice (haiku) is appropriate for test pattern-matching but may underperform on complex e2e provisioning tests. Worth noting but not a hard gap.
4. There is no orchestrator/dispatch agent; that function lives in the `/openwarden` skill (tech-lead mode). This is intentional and correct — an orchestrator needs to spawn agents and cannot itself be a subagent.
5. `area:dns` and `area:infra` are real future label areas in `CONTRIBUTOR_AUTOPILOT.md` but have no agent yet.
6. The `site/` directory (marketing site) exists in the repo with real work; no agent covers it.

---

## 2. Proposed Lean Roster

**Design principles:**
- Merge if >60% scope overlap.
- Every new role must map to real, recurring work on the current ROADMAP (`v0.1`–`v0.5`).
- Agent-blocked surfaces (crypto, proto, policy, CI/CD workflows, CODEOWNERS, governance) stay human-gated. A new agent may _read_ them for analysis but never implement.
- Each role needs a distinctive tool scope or guardrail posture to justify its existence.

### 2a. Roles to ADD NOW (recurring work exists, gap is real)

#### Role: `infra-scout` (devops / CI observer)

**When to use:** A contributor wants to understand the build pipeline, check CI health, read the provisioning scripts, or survey `scripts/` — but not touch CI workflows themselves (those are agent-blocked). Use when tech-lead mode needs a read-only build/infra survey.

**Tool scope:** Read, Grep, Glob (read-only — same posture as `crypto-reviewer`)

**Model:** haiku — read-only survey work; no generation needed; fast and cheap.

**Rationale:** CI/infra paths (`scripts/`, `.github/`) are agent-blocked for writing but agents frequently need to READ them to answer questions like "what does the build gate check?" or "is ktlint wired into CI?". No existing agent can do this without violating scope. A read-only infra surveyor fills that gap without weakening the gate.

**ADD NOW or DEFER:** ADD NOW — v0.4 CI hardening (#31) makes this immediately useful as a read-and-report role.

---

#### Role: `refactor` (refactor / migration / dead-code cleanup)

**When to use:** Renaming a module, extracting a shared utility, removing a dead feature branch, reorganizing package structure, or migrating from a deprecated API — when the change is structural rather than feature-adding. Issue must be labeled `agent-ready` and `area:refactor` (or a code area). STOP if the refactor touches crypto/proto/policy.

**Tool scope:** Read, Grep, Glob, Edit, Write, Bash

**Model:** sonnet — structural reasoning over multiple files; haiku insufficient.

**Rationale:** Refactors are a distinct kind of work from feature addition. `child-dpc` and `parent-ui` claim feature issues; neither explicitly covers rename/extract/cleanup work, and mixing the two leads to poorly scoped PRs. Pattern from research (RefAgent, MaintainCoder): planner+refactor+test+verify is a common pattern precisely because refactor is cognitively distinct. In OpenWarden's context, v0.x cleanup (dead Dart/Flutter code removal, module reorganization) is recurring and fits no existing role cleanly.

**ADD NOW or DEFER:** ADD NOW — dead Flutter/Dart removal and module cleanup work exists in the backlog. Keep scope narrow: structural code movement only, not feature behavior change.

---

#### Role: `site-builder` (marketing site)

**When to use:** Implementing or updating the marketing site under `site/`. HTML, CSS, JS, or site framework work. Does NOT touch app code, CI, crypto, or governance.

**Tool scope:** Read, Grep, Glob, Edit, Write, Bash

**Model:** sonnet

**Rationale:** `site/` exists in the repo and has had real PRs (PR #77 merged marketing website). No existing agent is scoped to it. Without a dedicated role, a contributor would have to use the catch-all, which has no guardrails for this area. The role's guardrails are simpler than mobile roles (no crypto surface) but it needs its own scope boundary to prevent cross-contamination with app code.

**ADD NOW or DEFER:** ADD NOW — site exists, active work has occurred, contributors will ask.

---

### 2b. Roles to DEFER (real future need, not yet recurring)

#### Role: `e2e-tester` (end-to-end / connected Android tests)

**When to use:** Writing `connectedAndroidTest` tests that require an emulator, provisioning a real DPC, or running the full policy bundle flow end-to-end. Meaningfully different from unit tests in tooling risk.

**Why defer:** v0.4 (#30) is where E2E test infrastructure lands. Until that milestone is active and the emulator setup is stable, a dedicated E2E agent would be writing into a moving foundation. The current `test-writer` (haiku) is sufficient for the v0.1–v0.3 unit/integration test work. Revisit when v0.4 milestones are claimed.

**Approximate trigger:** When `connectedAndroidTest` issues appear labeled `agent-ready`.

---

#### Role: `dns-filter` (DNS/network plumbing, `area:dns`)

**When to use:** Implementing DNS filtering, resolver pinning, Private DNS enforcement, or network policy work (`area:dns`).

**Why defer:** No `area:dns` issues exist on an active milestone yet. DNS work begins in v0.2–v0.5 range. A dedicated role before those issues are seeded creates a ghost agent with no work to claim.

**Approximate trigger:** When first `area:dns` issue is labeled `agent-ready` on an active milestone.

---

#### Role: `security-auditor` (app-layer threat/defense audit, read-only)

**When to use:** Read-only audit of non-crypto security concerns: permission overgrant, improper broadcast receivers, logging sensitive data, intent injection, improper fail-closed enforcement. Produces written findings only.

**Why defer:** `crypto-reviewer` covers the cryptographic surface. App-layer security audits are valuable but infrequent and currently handled in maintainer review. Add when the contributor base grows past 3–5 maintainers and automated security review is warranted.

---

### 2c. Complete proposed lean roster (steady state, ADD NOW + existing)

| Role | Model | Tools | Scope in one line | Add now? |
|---|---|---|---|---|
| `child-dpc` | sonnet | R/G/Gl/E/W/Bash | Kotlin child DPC feature work (`area:child-android`) | Existing |
| `parent-ui` | sonnet | R/G/Gl/E/W/Bash | KMP parent UI feature work (`area:parent-kmp`) | Existing |
| `test-writer` | haiku | R/G/Gl/E/W/Bash | Unit + integration tests for completed features, any area | Existing |
| `docs` | sonnet | R/G/Gl/E/W/Bash | Documentation only (`docs/`, top-level `*.md`) | Existing |
| `crypto-reviewer` | opus | R/G/Gl (read-only) | READ-ONLY crypto/protocol analysis, human-gated | Existing |
| `infra-scout` | haiku | R/G/Gl (read-only) | READ-ONLY survey of build/CI/scripts | **ADD NOW** |
| `refactor` | sonnet | R/G/Gl/E/W/Bash | Structural refactor/migration/cleanup, any non-blocked area | **ADD NOW** |
| `site-builder` | sonnet | R/G/Gl/E/W/Bash | Marketing site (`site/`) | **ADD NOW** |
| `e2e-tester` | sonnet | R/G/Gl/E/W/Bash | Connected/emulator Android E2E tests | **DEFER to v0.4** |
| `dns-filter` | sonnet | R/G/Gl/E/W/Bash | DNS/network plumbing (`area:dns`) | **DEFER to v0.2+** |
| `security-auditor` | opus | R/G/Gl (read-only) | App-layer security audit, written findings only | **DEFER post-v1** |

Legend: R=Read, G=Grep, Gl=Glob, E=Edit, W=Write

---

## 3. Shared Guardrail Block

Every agent embeds this block verbatim (or references it). Role-specific bodies ADD to it; they never weaken it.

```
## Shared guardrails (enforce always — these override everything else)

### Non-negotiables
- Never add a subscription, SaaS, telemetry, analytics, phone-home, or content-monitoring
  dependency. A PR that adds any of these will not merge.
- No content of any kind (messages, photos, audio) is ever read or sent — stalkerware
  boundary.
- Fail-closed: every error path defaults to MORE restriction, never less. Never weaken a
  fail-closed assertion to make a test pass or a build green.
- No secrets in the repo: no API keys, no tokens, no BIP39 phrases, no `.env` with
  credentials, ever committed.

### Agent-ready vs agent-blocked (the safety hinge)
You may implement an issue only when it is ALL of:
  - open, on an ACTIVE milestone (current/next v0.x rung in docs/ROADMAP.md)
  - labeled `agent-ready` AND a role `area:*` label
  - NOT labeled `agent-blocked` AND NOT labeled `claimed`

The following are ALWAYS agent-blocked (human + ADR required):
  - crypto / proto / wire format / BundleVerifier.kt / PolicyEnforcer.kt / PolicyService.kt
  - provisioning / Device Owner set-up paths
  - CI workflows (.github/workflows/), .github/, .claude/, CODEOWNERS, AGENTS.md, CLAUDE.md,
    SECURITY.md, kb/** (KB changes go through propose_kb_update MCP tool, not direct edit)
If you discover the work touches any of these, STOP immediately and hand back to a human
with a one-paragraph explanation. Do NOT implement. Do NOT silently bypass.

### Anti-fabrication
- Verify every claimed bug, test failure, or behavioral assertion against the actual source
  files before stating it as fact. Never fabricate a test result or a build error.
- If you are unsure whether a dependency is approved, STOP and ask rather than assuming.
- Never silently disable, stub-out, or comment-out a security check, a fail-closed assertion,
  or a crypto verification step to make something pass.

### Scope discipline
- Claim ONE issue at a time. Implement only what the issue asks. If you discover adjacent
  work, note it in the PR description and stop — don't scope-creep.
- Do NOT open a PR without the contributor's explicit confirmation (gh pr create is
  human-gated).
- Never git push --force. Never --no-verify.

### One worktree, one branch
- Create a worktree for every task: git worktree add -b <branch> ../OpenWarden-<slug> main
- Never work two branches in the same folder. Never hijack another window's worktree.
- At session start: git worktree list + git -C <path> status -s for every listed worktree.

### Commits
- Conventional, signed, DCO: git commit -S -s -m "feat(<area>): …"
- Update any touched docs/ in the same commit.
- Test suite must be green before committing.
```

---

## 4. Self-Healing Minting Mechanism

### Problem statement

The current roster is static: five agents defined at project start, with no formal process for adding new ones as the repo grows. A gap discovered in one session is silently worked around in the next. Over time this leads to either:
- Agents stretched beyond their intended scope (scope creep by accident), or
- Work falling into the catch-all, which has no project-specific guardrails.

### Trigger: when is a new role justified?

A new agent role is justified when ALL of:
1. **Recurrence:** The same work type has appeared in 3+ separate sessions or issues without a good-fit agent.
2. **Distinctiveness:** The work type requires a materially different tool scope OR guardrail posture from every existing role. (If it's just a new `area:*` label, add the label — don't create a new agent.)
3. **Volume:** There are (or will imminently be) at least 2–3 `agent-ready` issues to justify the role's existence.
4. **Not blocked:** The work type is not inherently agent-blocked (crypto/proto/CI/governance). Blocked work stays human-only regardless of volume.

A recurrence is detected when:
- A contributor asks `/openwarden` and none of the listed roles is a good fit, OR
- Tech-lead mode (Step 1c) surveys open issues and finds a cluster of `agent-ready` issues in an area with no matching role.

### Proposed `/forge-agent` skill (extension of skill-creator)

The minting process should be a skill, not a manual doc edit, to make it repeatable and reviewable.

#### Skill: `forge-agent`

**Trigger:** `/forge-agent` or "mint a new agent" or "we need a new agent for X"

**Flow:**

```
Step 1 — Justify
  Present the maintainer with the trigger checklist (recurrence × 3, distinctiveness,
  volume, not-blocked). If any criterion fails, refuse and explain which.
  Use AskUserQuestion to confirm the maintainer agrees the threshold is met.

Step 2 — Name + scope
  Ask: What is the role name? (kebab-case)
  Ask: What is the one-line description? (shown in /openwarden role-picker)
  Ask: What area:* label does this role claim issues from? (new label or existing)
  Ask: What tools does it need? (default: Read, Grep, Glob, Edit, Write, Bash;
       read-only roles get only Read, Grep, Glob)
  Ask: What model? (haiku for fast/cheap read-only; sonnet for implementation;
       opus for review/analysis — justify the choice)
  Ask: What is the hard boundary — what does this role STOP at?

Step 3 — Generate draft
  Write the draft agent def to design-artifacts/agent-drafts/<role>.md using the
  standard template (YAML front matter + shared guardrail block + role body).
  Do NOT write to .claude/agents/. The draft is a proposal for maintainer review.

Step 4 — Update roster proposal
  Append a row to design-artifacts/SUBAGENT_ROSTER.md §2c table.
  If a new area:* label is needed, note it in the draft with the gh label create command.

Step 5 — Commit + PR (human-gated)
  Stage only design-artifacts/**. Commit: docs(research): propose <role> agent
  Open a PR per .github/PULL_REQUEST_TEMPLATE.md with label agent-roster-proposal.
  Maintainer reviews: installs to .claude/agents/ only after approval.
  CODEOWNERS gate on .claude/agents/ is the hard install gate — no agent is "live"
  until a CODEOWNERS-gated PR merges.

Step 6 — Wire into /openwarden skill
  After CODEOWNERS approval, in the SAME PR or a follow-up PR:
  - Add the role to the AskUserQuestion list in openwarden/SKILL.md §1a.
  - Add the area label to gh issue creation commands in §1b.
  - If the role has a new area:*, add it to the tech-lead dispatch list in §1c.
  This step is ALSO gated (CODEOWNERS covers .claude/skills/).
```

#### Integration with skill-hardening self-healing loop

The existing skill-hardening loop (branch `chore/skill-self-heal`) detects when skills fail or produce wrong outputs and proposes patches. The `/forge-agent` skill slots in as a **reactive gap-filling extension** of that loop:

```
skill-hardening loop detects:
  → skill failure (skill-creator patches the skill)
  → agent scope mismatch (forge-agent proposes a new agent)
  → repeated catch-all fallback (forge-agent proposes a new agent)
```

Concretely:
- The tech-lead mode Step 2 (decision) in `openwarden/SKILL.md` should record a note in the progress ledger whenever it falls back to the catch-all for want of a matching role. Three such notes → trigger `/forge-agent`.
- This is lightweight: it uses the existing `progress.json` ledger already in place.

### Governance gate (install vs propose)

| Action | Who | Gate |
|---|---|---|
| Write draft to `design-artifacts/agent-drafts/` | Any agent | None (design-artifacts is unprotected) |
| Open PR to install into `.claude/agents/` | Agent (human-gated PR) | CODEOWNERS on `.claude/` |
| Merge install PR | Maintainer only | Branch protection + CODEOWNERS |
| Update `openwarden/SKILL.md` role-picker | Agent (human-gated PR) | CODEOWNERS on `.claude/skills/` |

The CODEOWNERS hard gate is the binding constraint. A proposed agent sitting in `design-artifacts/` is inert — it can be reviewed, revised, and rejected without any risk to the live system.

---

## 5. Draft Agent Stubs

Draft stubs for the three ADD-NOW roles are in `design-artifacts/agent-drafts/`:

- [`agent-drafts/infra-scout.md`](agent-drafts/infra-scout.md)
- [`agent-drafts/refactor.md`](agent-drafts/refactor.md)
- [`agent-drafts/site-builder.md`](agent-drafts/site-builder.md)

These use the exact format of the live agents in `.claude/agents/` (YAML front matter + role body). They are proposals only — NOT installed.

---

## 6. Open questions for maintainer

1. **`area:refactor` label:** The `refactor` role needs a new `area:refactor` GitHub label (or reuse `area:child-android`/`area:parent-kmp` with a `type:refactor` tag). Which approach do you prefer? Creating a cross-cutting `area:refactor` label is cleaner but requires updating the issue-seeding flow in `/openwarden §1b`.

2. **`infra-scout` vs just using `crypto-reviewer` read-only posture:** The `infra-scout` and `crypto-reviewer` both have read-only tool scopes. Consider whether they should be merged into a single `read-only-analyst` role or kept separate (argument for separate: their knowledge bases, prompt guidance, and hazard lists are very different). Current recommendation: keep separate.

3. **`site-builder` model:** The draft uses sonnet. If site work is primarily templating/HTML, haiku may be sufficient. Recommend trying haiku first and escalating to sonnet for complex JS/framework work.

4. **`test-writer` haiku model for future e2e tests:** When v0.4 E2E work begins, `test-writer` on haiku will likely struggle with the emulator provisioning complexity. The maintainer should evaluate upgrading it to sonnet at that milestone, or minting the deferred `e2e-tester` role.

5. **`/forge-agent` skill location:** Should `/forge-agent` be a new skill file in `.claude/skills/openwarden/` (alongside `openwarden/SKILL.md`) or a top-level skill? Recommendation: sub-skill of the openwarden skill family, at `.claude/skills/openwarden/forge-agent/SKILL.md`.

6. **Progress ledger catch-all tracking:** The proposal to track catch-all fallbacks in `progress.json` requires a small change to the `/openwarden` skill. This is a `.claude/skills/` change and is CODEOWNERS-gated — maintainer must approve. Flag this explicitly in the PR.

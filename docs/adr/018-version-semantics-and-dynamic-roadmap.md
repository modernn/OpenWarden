# ADR-018: Version semantics, a dynamic protected roadmap, and a tech-lead orchestration mode

Status: Proposed
Date: 2026-06-15

## Context

Two related problems surfaced while standing up the contributor-autopilot flow.

1. **The version numbers meant the wrong thing.** [`docs/ROADMAP.md`](../ROADMAP.md)
   labeled the entire "Oliver's phone works" build as **v1**, and the single
   GitHub milestone `v1` mirrored that. But "works for one kid on one Pixel" is an
   early internal proof, not a release. The real **1.0** is a public,
   cross-platform, app-store product — iOS **and** Android, **both** the parent
   and child apps, shipped *after* a public beta, at parity with the commercial
   tools (Bark/Qustodio/etc.). Critically, **1.0 must work on iOS**, and Android
   Device Owner has no iOS equivalent — so iOS child enforcement is an open 1.0
   blocker, not a "v3, someday" nicety as the old roadmap implied. Pinning all
   near-term work to a milestone literally named `v1` mis-sets every expectation
   and, downstream, miscolors the contributor backlog.

2. **The roadmap was treated as static and unprotected-by-convention.** The plan
   below the next step is genuinely unknown — a lot gets uncovered while building.
   The old roadmap presented a fixed v1/v2/v3/v4 allocation as if it were settled.
   And while [`.github/CODEOWNERS`](../../.github/CODEOWNERS) already gates every
   path via the default `* @modernn` rule, `docs/ROADMAP.md` was not *explicitly*
   elevated to canon alongside `docs/adr/`, so there was no stated rule for how
   the plan is allowed to change.

Separately, the `/openwarden` skill is the "management portion" of the project's
agent tooling, and we want it to do more than route a single contributor: it
should act as a **guiding software lead** — survey the project, decide the
highest-leverage next work, and find adjacent work to dispatch to subagents.

## Options

- **Keep `v1 = "Oliver's phone works"`, add v2/v3/…** Rejected: it labels an
  internal one-device proof as a release, mis-sets every expectation, and buries
  iOS (a hard release requirement) as a "someday" item.
- **A fixed, fully-allocated ladder (every issue pre-assigned to its version).**
  Rejected: the plan below the next step is genuinely unknown; pretending it's
  settled forces churn and dishonest milestones.
- **A dynamic v0.x road → gated v1.0 public release, with pivots recorded by
  ADR (chosen).** Honest about uncertainty, protects the canon, and keeps a
  hard, meaningful definition of "released."
- **Tech-lead as a subagent vs. a main-thread skill mode.** Chose main-thread:
  the lead's whole job is to *spawn* subagents, and a subagent cannot spawn
  subagents in this harness.

## Decision

### 1. Version semantics

- **v0.x** is the pre-release road: first boot → a public, cross-platform,
  app-store-ready beta. Breaking changes are fine; there is no public install
  base to protect. *"Oliver's phone works"* is an internal proof inside v0.x, not
  a version.
- **v1.0** is the public release, gated on a **public beta**, shipping iOS +
  Android × parent + child to the app stores at commercial parity. It must work
  on iOS for real.
- **post-1.0** holds the smarter/economy/AI features (the old v2/v3/v4 content),
  reframed as "Beyond 1.0".

### 2. The roadmap is dynamic, and protected

- [`docs/ROADMAP.md`](../ROADMAP.md) is the **single source of truth** for version
  scope. GitHub milestones **mirror** its near-term rungs; the file is the source,
  the milestones are the copy.
- Only the **current and next** rung are committed. Lower rungs are an explicit
  sketch, expected to be re-cut as we learn.
- A **pivot** — splitting/reordering/dropping/adding a rung, or changing what a
  version *means* — requires, in the **same PR**: an ADR (or an amendment note on
  an existing ADR), the ROADMAP updated, and GitHub milestones re-synced. The plan
  is allowed to change; the change must be *recorded*, never silently drifted.
- ROADMAP gets an **explicit** CODEOWNERS line grouping it with the canon/decision
  paths (it was already covered by the default owner; this makes its canon status
  legible). The dead root-path `/CODEOWNERS` line is corrected to `.github/CODEOWNERS`.

### 3. Milestone gating is version-agnostic

The agent-ready safety rule previously required an issue be "on the `v1`
milestone." That string is now wrong and would reject all v0.x work. The rule
becomes "on an **active milestone**" — i.e. scheduled, vetted work rather than a
specific version string. Updated in the skill, both role agents, the autopilot
doc, and the `agent-ready-vs-blocked` design-memory entry.

### 4. Tech-lead / architect orchestration mode

`/openwarden` gains a maintainer mode that **surveys** (repo state, open
milestones/issues, the current ROADMAP rung, the KB, recent git history, CI),
**decides** the highest-leverage dependency-aware next work, **finds adjacent**
parallelizable tasks, and **dispatches** role subagents (each in its own
worktree) onto the agent-ready ones — keeping agent-blocked work human-gated. It
runs on the **main thread**, not as a subagent, precisely because it must spawn
subagents (a subagent cannot). It is read-and-recommend by default; any roadmap
re-scope it proposes goes through the pivot mechanism above. The same
survey→decide logic also powers bare **`/openwarden start`** (no issue#): it
auto-picks the single highest-leverage `agent-ready` issue on the active
milestone (bedrock-first) and just begins it — the zero-thought entrypoint, where
tech-lead mode is the fan-out-across-many variant.

## Consequences

- The contributor backlog is re-bucketed v0.1 → v0.4 (mirrored as GitHub
  milestones in a follow-up maintainer step; not done in this ADR's PR).
- "Frozen design" now means **v1.0-and-beyond** bullets; v0.x is buildable.
  Reviewers check the rung, not a hardcoded version, when deciding if an ADR is
  required to start something.
- iOS child enforcement and iOS parent backgrounding become tracked **1.0
  blockers** ([`ROADMAP.md`](../ROADMAP.md) → *Open architectural questions*),
  rather than deferred indefinitely.
- The skill can now act as a force multiplier (lead + fan-out) without weakening
  any gate — the guardrails and human-only surfaces are unchanged.

## Citations

- [`docs/ROADMAP.md`](../ROADMAP.md) — the rewritten canon.
- [`.github/CODEOWNERS`](../../.github/CODEOWNERS) — the enforced gate.
- [`.claude/skills/openwarden/SKILL.md`](../../.claude/skills/openwarden/SKILL.md)
  — tech-lead mode + version-agnostic gating.
- [`docs/CONTRIBUTOR_AUTOPILOT.md`](../CONTRIBUTOR_AUTOPILOT.md),
  [`kb/design-memory/agent-ready-vs-blocked.md`](../../kb/design-memory/agent-ready-vs-blocked.md)
  — the gating rule, de-hardcoded.
- [`CLAUDE.md`](../../CLAUDE.md) → *Doc tier system* — frozen-design semantics.

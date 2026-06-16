# AGENTS.md — guidance for AI coding agents

This repo is built to be contributed to with AI coding agents (Claude Code,
OpenAI Codex CLI, and others). This file is the agent-agnostic entry point.

- **Claude Code:** [`CLAUDE.md`](CLAUDE.md) is the full, authoritative ruleset and
  loads automatically. Read it first. Run the **`/openwarden`** skill
  to get onboarded and pick something to work on.
- **Codex / other agents:** read this file **and** [`CLAUDE.md`](CLAUDE.md). The
  rules in CLAUDE.md apply to you too.

New here? See [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md) (run it
locally, Android-first) and [`CONTRIBUTING.md`](CONTRIBUTING.md) (how to
contribute). Pick work from [the issues](https://github.com/modernn/OpenWarden/issues)
— `good first issue` is a good start.

## Must-knows (the short version)

**Non-negotiables — a PR that violates any of these will not merge:**
- No subscription, no SaaS, no telemetry, no analytics, no phone-home. Ever.
- No content monitoring (messages/photos/audio never read or sent).
- Fail-closed: every error path defaults to *more* restriction, never less.
- No secrets in the repo (no keys, tokens, or BIP39 phrases — ever).

**Workflow:**
- One Claude/Codex window = one **git worktree** = one branch. Don't run two
  windows in the same folder on different branches (see CLAUDE.md → *Git
  worktrees*). Start a session by running `git worktree list` and checking for
  stray uncommitted/unpushed work.
- Commits: conventional (`feat:`/`fix:`/`docs:`/`chore:`/`test:`), **signed**
  (`git commit -S`), **DCO sign-off** (`git commit -s`). Never `--no-verify`.
- Tests are **required** for crypto, protocol, and policy logic, and for new
  features and bug fixes. Run `/verify-openwarden-spec` against `docs/PROTOCOL.md`
  + `docs/CRYPTO.md` before calling crypto/protocol work done.
- Docs are the spec. If you change behavior described in `docs/` or an ADR, update
  the doc in the same PR. Architecture pivots get a new ADR in `docs/adr/`.

**Build (Android-first; iOS is built later on macOS):**
```
cd parent-kmp
./gradlew :proto:build :shared:build :androidApp:assembleDebug   # all green on JDK 17
```
Full setup: [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md).

## Where things are
- `child-android/` — Kotlin DPC (Device Owner) app
- `parent-kmp/` — Kotlin Multiplatform parent app (`:proto`, `:shared`, `:androidApp`)
- `proto/` — wire-format schema (`api.yaml`); the KMP `:proto` module mirrors it
- `docs/` — canonical specs; `docs/adr/` — decisions; `docs/research/` — input, not canon
- `.claude/skills/` — project skills (`/openwarden`, test/verify/provision helpers)

## Contributing workflow

Claude Code users: run **`/openwarden`**. Codex / other agents: follow
this same flow (it's the bridge — one source of truth in [`CONTRIBUTING.md`](CONTRIBUTING.md)
and [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md)).

Pick what you want to do, then route:
- **Run locally** → `docs/GETTING_STARTED.md` (Android-first); DPC via `scripts/provision-emulator.sh`.
- **Write tests** → `docs/TESTING.md`; tests are REQUIRED for crypto/protocol/policy.
- **Implement a feature** → take an `enhancement`/`good first issue`/`agent-ready` issue.
  Check `docs/adr/` + doc tier first — v2/v3 frozen-design needs an ADR before code.
- **Fix a bug** → take a `bug` issue; add a regression test.
- **Improve docs** → docs are the spec; keep them accurate.

Then, for any change:
1. `git worktree add -b <conventional-branch> ../OpenWarden-<slug> main`
2. change **+ tests**
3. `cd parent-kmp && ./gradlew check` (green)
4. `git commit -S -s -m "feat: …"` (conventional, signed, DCO; never `--no-verify`)
5. update any touched `docs/`/ADR in the same change
6. open a PR per `.github/PULL_REQUEST_TEMPLATE.md`

**Do NOT** touch crypto / `proto` / provisioning / policy-enforcement / CI / `.claude/`
hooks / `CODEOWNERS` / `AGENTS.md` / `CLAUDE.md` without a maintainer + ADR
(CODEOWNERS-gated, not agent-ready).

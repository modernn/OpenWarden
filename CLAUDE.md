# CLAUDE.md — OpenWarden per-repo rules

This file is loaded at every Claude Code session start. Read first, follow always.

## Project

Open-source, no-subscription, local-only parental control. Apache 2.0. See [`README.md`](README.md) and [`docs/SIMPLIFY.md`](docs/SIMPLIFY.md).

## Non-negotiables

- **No subscription, no SaaS, no telemetry.** Ever. PR that adds any = reject.
- **No content monitoring.** Messages, photos, audio = never read or sent. Stalkerware boundary lives here.
- **Fail-closed.** Every error path defaults to *more* restriction, never less.
- **Kid transparency.** Every monitored category visible on [`docs/KID_TRANSPARENCY.md`](docs/KID_TRANSPARENCY.md) screen.
- **No secrets in repo.** No `.env` w/ keys, no API tokens, no BIP39 phrases ever committed.

## Code rules

- Kotlin: ktlint defaults
- Dart (legacy parent-flutter being removed): `flutter format`
- Swift: SwiftLint defaults (added in Phase 1)
- Commits: imperative, conventional (`feat:`, `fix:`, `docs:`, `chore:`, `test:`)
- All commits signed (`git commit -S`)
- Never `git push --force` to main
- Never `--no-verify` / skip hooks

## Test rules

- Crypto + protocol code REQUIRES tests
- New features REQUIRE tests
- Bug fixes REQUIRE regression tests
- CI must pass before merge
- See [`docs/TESTING.md`](docs/TESTING.md)
- See [`docs/AI_DEV_PIPELINE.md`](docs/AI_DEV_PIPELINE.md) for AI-driven test loop

## Architecture invariants

- Policy enforced locally on child, ALWAYS (offline-tolerant)
- Signed policy bundles only (Ed25519 + RFC 8785 JCS)
- Event log encrypted to parent pubkey via libsodium sealed-box
- Replay protection mandatory (`policy_seq` monotonic + freshness window)
- No content in event log — only metadata
- Recovery phrase = root authority, BIP39 24-word

## What lives where

- `child-android/` — Kotlin DPC app
- `parent-kmp/` — KMP parent app (Android + iOS) [Phase 1+]
- `proto/` — shared signed-log + bundle schemas
- `docs/` — canonical specs + design docs (read these before coding)
- `docs/adr/` — Architecture Decision Records (read before changing direction)
- `docs/research/` — raw research reports (input, not canon)
- `scripts/` — build + test + provisioning automation
- `.claude/skills/` — project-specific Claude Code skills

## Git worktrees (parallel Claude sessions)

We often run more than one Claude Code window on this repo at once (e.g. parent-kmp
build in one, marketing site in another). A **git worktree** gives each window its
own folder with its own checked-out branch, while sharing one `.git` (same history,
branches, remotes, push/pull). This is **mandatory** here: two windows in the *same*
folder share one `HEAD`, so commits land on whichever branch is currently checked
out — we already had crypto commits accidentally land on the website branch that way.

**Rule: one window = one worktree folder = one branch.** Never run two windows in the
same folder on different branches. If you need a branch that's checked out elsewhere,
make your own worktree — don't hijack another window's folder or `reset` its branch.

Standing layout (trust `git worktree list` over this table — it drifts):

| Folder | Branch | Purpose |
|---|---|---|
| `C:\src\OpenWarden` | `main` | shared base, no active editing |
| `C:\src\OpenWarden-kmp` | `feat/parent-kmp-scaffold` | parent-kmp build |
| `C:\src\OpenWarden-site` | `feat/marketing-website` | marketing site |

**At the START of every session, before changing anything:**
1. `git worktree list` — see every worktree and its branch.
2. For each, check nothing is stranded: `git -C <path> status -s` (uncommitted) and
   `git -C <path> log --oneline @{u}..` (committed-but-unpushed). Commit + push or
   clean it — never abandon work sitting in a worktree.
3. `git worktree prune` — forget folders that were deleted by hand.
4. Confirm you're in the right folder for the branch you intend to edit.

**Cheat sheet:**
```
git worktree add <path> <branch>        # existing branch -> new folder
git worktree add -b <new> <path> main   # new branch off main -> new folder
git worktree list                       # all worktrees + branches
git worktree remove <path>              # delete a FINISHED worktree (must be clean)
git worktree prune                      # forget hand-deleted folders
```
A branch lives in only one worktree at a time. When done with a line of work, `remove`
its worktree — don't leave stale ones lying around.

## Doc tier system

- v1 canon = what we're building now
- v2/v3 frozen-design = design locked, implementation deferred. **DO NOT IMPLEMENT** without ADR or explicit scope expansion.

## First-session setup (read first)

If this is the first time Claude Code is opening this repo on a new machine:

1. Read [`BOOTSTRAP.md`](BOOTSTRAP.md)
2. Run `/bootstrap-repo` skill (installs JDK 21, Android SDK + emulator + AVD, ktlint, Codex CLI, etc.)
3. Verify w/ `./scripts/verify-env.sh`
4. Then proceed to Phase 1 (parent-kmp scaffolding) once user confirms

Triggers for first-session setup:
- User says "start setting up" / "bootstrap" / "set up the repo" / "install dev tools"
- Repo lacks built artifacts and tools

## When stuck

- Read relevant doc in `docs/` first
- Try `/codex:rescue` (codex plugin) or the `codex-second-opinion` skill for a second opinion
- For high-stakes design contention, run an adversarial Codex review (`/codex:rescue`) and apply the `impeccable` skill for UI craft (there is no `impeccable-codex-debate` plugin)
- For provisioning issues: read [`docs/PROVISIONING_V2.md`](docs/PROVISIONING_V2.md)
- For crypto: read [`docs/CRYPTO.md`](docs/CRYPTO.md) + run test vectors in `docs/test-vectors/`
- For attacks/defenses: [`docs/ATTACKS.md`](docs/ATTACKS.md) + [`docs/DEFENSES.md`](docs/DEFENSES.md)
- For architecture pivots: check [`docs/adr/`](docs/adr/) first

## Style for output

- Be concise. Match caveman mode if active.
- Don't write docs unless asked. Update existing canon, don't create new sprawl.
- File paths in code: use absolute paths from project root or `path:line` for references.

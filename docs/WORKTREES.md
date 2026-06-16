# WORKTREES.md — Worktree Coordination for OpenWarden

Canonical guide for running multiple Claude Code windows and parallel role agents on
this repo without stepping on each other.

---

## Why worktrees are mandatory here

A git worktree gives each window its own folder with its own checked-out branch, while
all worktrees share one `.git` — same history, same branches, same remote.

Without worktrees, two windows in the same folder share one `HEAD`. Commits land on
whichever branch is currently checked out, not the one you intended. This already
happened: crypto commits landed on the website branch because two sessions were both
running in `C:\src\OpenWarden`.

**Rule: one window = one worktree folder = one branch.** Never run two windows in the
same folder on different branches. If you need a branch that is checked out elsewhere,
create your own worktree — do not hijack another window's folder or `reset` its branch.

---

## Standing layout

The table below drifts. Trust `git worktree list` over it.

| Folder | Branch | Purpose |
|---|---|---|
| `C:\src\OpenWarden` | `main` | Shared base — no active editing |
| `C:\src\OpenWarden-<slug>` | `<prefix>/<slug>` | Per-task worktree (see naming below) |

`main` is the integration branch. Never do active development directly in
`C:\src\OpenWarden`; it is the reference point everything branches from and merges
back to.

---

## Start-of-session checklist

Run these before changing anything:

1. `git worktree list` — see every worktree and its branch.
2. For each worktree, check that nothing is stranded:
   - `git -C <path> status -s` — uncommitted changes?
   - `git -C <path> log --oneline @{u}..` — committed but not pushed?
   - Commit + push or clean it. Never abandon work sitting in a worktree.
3. `git worktree prune` — forget folders that were deleted by hand.
4. Confirm you are in the right folder for the branch you intend to edit.

---

## Naming conventions

| Thing | Convention | Example |
|---|---|---|
| Worktree folder | `C:\src\OpenWarden-<slug>` | `C:\src\OpenWarden-sealedbox` |
| Branch | `<prefix>/<slug>` | `feat/sealed-box-events` |

Branch prefixes: `feat/`, `fix/`, `docs/`, `test/`, `chore/`.

Slugs should be short and match the branch suffix so the folder name and branch name
are obviously related.

---

## Parallel role agents

When fanning work out to multiple agents at once:

1. Each independent task gets its own worktree and branch:
   ```
   git worktree add -b feat/my-feature C:\src\OpenWarden-myfeature main
   git worktree add -b docs/my-doc     C:\src\OpenWarden-mydoc     main
   ```
2. Send the independent dispatches together so the agents start in parallel.
3. Give each agent a non-overlapping file scope. Agents touching the same files will
   produce merge conflicts.
4. Each agent operates only in its assigned worktree folder. It must not read from or
   write to another worktree's folder.

When work is complete, merge/PR each branch independently; do not try to merge two
active-development worktrees into each other outside of the normal PR flow.

---

## Cleanup

When a line of work is done:

1. Merge or PR the branch.
2. In the folder that held the worktree:
   ```
   git worktree remove C:\src\OpenWarden-<slug>
   ```
   The folder must be clean (no uncommitted changes) for this to succeed.
3. Delete the branch if it is fully merged:
   ```
   git branch -d <branch>
   ```
4. Run `git worktree prune` to clean up any stale entries from hand-deleted folders.

A branch lives in only one worktree at a time. Do not leave stale worktrees sitting
around — each one has a lock file that blocks that branch from being added elsewhere.

---

## Progress ledger

The `/openwarden start|stop|resume` ledger is stored **uncommitted** in the git common
dir:

```
$(git rev-parse --git-common-dir)/openwarden/progress.json
```

This path resolves to the same file from every worktree — all worktrees share the git
common dir. The ledger is keyed by worktree path + branch + issue number, so sessions
for different worktrees do not collide. Never commit this file; it is intentionally
outside any branch.

---

## Cheat sheet

```
# Create a new worktree on a new branch
git worktree add -b <branch> C:\src\OpenWarden-<slug> main

# Attach a new worktree to an existing branch
git worktree add C:\src\OpenWarden-<slug> <branch>

# List all worktrees
git worktree list

# Check a worktree for stranded work
git -C C:\src\OpenWarden-<slug> status -s
git -C C:\src\OpenWarden-<slug> log --oneline @{u}..

# Remove a finished, clean worktree
git worktree remove C:\src\OpenWarden-<slug>

# Forget hand-deleted folders
git worktree prune
```

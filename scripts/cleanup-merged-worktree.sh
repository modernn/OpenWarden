#!/usr/bin/env bash
# Clean up a worktree + branch after its PR has merged — Windows-safe ordering.
#
# Why this exists: `gh pr merge --delete-branch` FAILS on every OpenWarden PR,
# because the skill mandates one-branch-per-worktree and you cannot `git branch
# -d` a branch that is checked out in a worktree. gh aborts with exit 1 *before*
# deleting the remote branch, leaving a stale remote ref behind. This script does
# the steps in the only order that works, idempotently, with lock handling:
#
#   1. remove the worktree (after stopping any Gradle daemon holding files)
#   2. delete the local branch (now unbound from the worktree)
#   3. delete the remote branch explicitly
#   4. prune worktree admin + remote-tracking refs
#   5. rm any orphaned folder a Windows file-lock left behind
#
# Run AFTER the PR is merged, e.g. `gh pr merge <n> --merge` (NOT --delete-branch).
#
# Usage:
#   scripts/cleanup-merged-worktree.sh <branch> [worktree-path]
#   scripts/cleanup-merged-worktree.sh --dry-run <branch> [worktree-path]
#
# Flags:
#   --dry-run        print what would run, change nothing
#   --force-unmerged delete the local branch even if it is NOT merged into
#                    origin/main (default: refuse, to avoid losing work)
#   --no-remote      do not touch the remote branch
#   -h, --help       this help
#
# Idempotent: re-running on an already-clean state is a successful no-op.
# Exit 0 on success (incl. "already clean"); a transient orphan-folder lock is a
# WARN, not a failure. Non-zero only on a genuine unexpected git error.
set -euo pipefail

DRY_RUN=0
FORCE_UNMERGED=0
DO_REMOTE=1
REMOTE=origin
BASE_REF=origin/main

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; }

run() {
  # Echo + execute (or just echo under --dry-run). Used for mutating commands.
  echo "+ $*"
  if [[ "$DRY_RUN" -eq 0 ]]; then "$@"; fi
}

POSITIONAL=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --force-unmerged) FORCE_UNMERGED=1; shift ;;
    --no-remote) DO_REMOTE=0; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown flag: $1" >&2; usage; exit 2 ;;
    *) POSITIONAL+=("$1"); shift ;;
  esac
done

if [[ ${#POSITIONAL[@]} -lt 1 ]]; then
  echo "error: <branch> is required" >&2
  usage
  exit 2
fi
BRANCH="${POSITIONAL[0]}"
WT_PATH="${POSITIONAL[1]:-}"

# Operate from the git common dir so we are never standing inside the worktree
# we are about to remove (git refuses to remove the worktree you are in, and
# Windows refuses to delete a folder that is some process's CWD).
COMMON_DIR="$(git rev-parse --git-common-dir 2>/dev/null || true)"
if [[ -z "$COMMON_DIR" ]]; then
  echo "error: not inside a git repository" >&2
  exit 2
fi
# --git-common-dir may be relative to CWD; resolve it, then take its parent (the
# main worktree root) as a safe place to stand.
COMMON_DIR="$(cd "$COMMON_DIR" && pwd)"
MAIN_ROOT="$(dirname "$COMMON_DIR")"
cd "$MAIN_ROOT"

echo "== cleanup-merged-worktree: branch='$BRANCH' (dry-run=$DRY_RUN) =="

# --- locate the worktree for this branch (if any) -----------------------------
# Parse `git worktree list --porcelain`: records are blank-line separated, with
# "worktree <path>" and "branch refs/heads/<name>" lines.
if [[ -z "$WT_PATH" ]]; then
  WT_PATH="$(git worktree list --porcelain | awk -v b="refs/heads/$BRANCH" '
    /^worktree /  { p = substr($0, 10) }
    /^branch /    { if ($2 == b) { print p; exit } }
  ')"
fi

# --- 0. safety guard: refuse on UNMERGED work BEFORE tearing anything down -----
# Done first (not at branch-delete time) so an accidental call on a live branch
# does not remove its worktree. With the repo's merge-commit flow a merged
# branch's tip is an ancestor of origin/main; a non-ancestor means unmerged work.
if [[ "$FORCE_UNMERGED" -eq 0 ]] && git show-ref --verify --quiet "refs/heads/$BRANCH"; then
  git fetch --quiet "$REMOTE" 2>/dev/null || true
  if ! git merge-base --is-ancestor "$BRANCH" "$BASE_REF" 2>/dev/null; then
    echo "REFUSING: local branch '$BRANCH' is not merged into $BASE_REF — nothing changed." >&2
    echo "  Merge the PR first, or re-run with --force-unmerged to discard unmerged commits." >&2
    exit 1
  fi
fi

# --- 1. remove the worktree ---------------------------------------------------
if [[ -n "$WT_PATH" ]] && git worktree list --porcelain | grep -qxF "worktree $WT_PATH"; then
  # Stop any Gradle daemon holding files in this worktree (the #1 Windows lock).
  # OpenWarden modules each carry their own wrapper.
  for g in "$WT_PATH/gradlew" "$WT_PATH"/*/gradlew; do
    if [[ -x "$g" ]]; then
      echo "+ ($(dirname "$g")) ./gradlew --stop"
      if [[ "$DRY_RUN" -eq 0 ]]; then ( cd "$(dirname "$g")" && ./gradlew --stop >/dev/null 2>&1 || true ); fi
    fi
  done
  run git worktree remove "$WT_PATH" --force || echo "WARN: 'git worktree remove' failed; continuing (prune will reconcile)"
else
  echo "  (no registered worktree for $BRANCH — skipping remove)"
fi

# --- 2. delete the local branch (merged-guard already enforced at step 0) -----
if git show-ref --verify --quiet "refs/heads/$BRANCH"; then
  run git branch -D "$BRANCH"
else
  echo "  (local branch $BRANCH already gone)"
fi

# --- 3. delete the remote branch ----------------------------------------------
if [[ "$DO_REMOTE" -eq 1 ]]; then
  if [[ -n "$(git ls-remote --heads "$REMOTE" "$BRANCH" 2>/dev/null)" ]]; then
    run git push "$REMOTE" --delete "$BRANCH"
  else
    echo "  (remote branch $REMOTE/$BRANCH already gone)"
  fi
fi

# --- 4. prune admin + tracking refs -------------------------------------------
run git worktree prune
run git fetch --prune "$REMOTE" 2>/dev/null || true

# --- 5. orphaned folder (Windows file-lock left it behind) --------------------
if [[ -n "$WT_PATH" && -d "$WT_PATH" ]]; then
  echo "  worktree folder still on disk (lock?) — attempting removal"
  removed=0
  for _ in 1 2; do
    if [[ "$DRY_RUN" -eq 1 ]]; then echo "+ rm -rf $WT_PATH"; removed=1; break; fi
    if rm -rf "$WT_PATH" 2>/dev/null; then removed=1; break; fi
  done
  if [[ "$removed" -eq 0 ]]; then
    echo "WARN: could not delete '$WT_PATH' (a process still holds a handle:"
    echo "      Gradle daemon, an editor, or a Codex/CLI reading the folder)."
    echo "      git no longer tracks it — it is a harmless orphan. Delete it"
    echo "      manually once the handle releases:  rm -rf '$WT_PATH'"
  fi
fi

# --- verify -------------------------------------------------------------------
echo "== verify =="
fail=0
if git show-ref --verify --quiet "refs/heads/$BRANCH"; then
  echo "  local branch:  STILL PRESENT ($BRANCH)"; fail=1
else
  echo "  local branch:  gone"
fi
if [[ "$DO_REMOTE" -eq 1 ]]; then
  if [[ -n "$(git ls-remote --heads "$REMOTE" "$BRANCH" 2>/dev/null)" ]]; then
    echo "  remote branch: STILL PRESENT ($REMOTE/$BRANCH)"; fail=1
  else
    echo "  remote branch: gone"
  fi
fi
if git worktree list --porcelain | awk '/^branch /{print $2}' | grep -qxF "refs/heads/$BRANCH"; then
  echo "  worktree:      STILL REGISTERED"; fail=1
else
  echo "  worktree:      unregistered"
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "dry-run: no changes made."
  exit 0
fi
if [[ "$fail" -ne 0 ]]; then
  echo "cleanup INCOMPLETE — see STILL PRESENT lines above." >&2
  exit 1
fi
echo "cleanup complete."

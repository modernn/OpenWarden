/**
 * progress.ts — worktree-aware, UNCOMMITTED dev-progress ledger.
 *
 * Backs `/openwarden start | stop | resume | status`: "maintain your place in
 * development" across sessions and across the project's multiple git worktrees.
 *
 * Storage: the ledger lives in the git COMMON dir
 *   `$(git rev-parse --git-common-dir)/openwarden/progress.json`
 * which is (a) inside `.git`, so it is NEVER committed, and (b) shared by every
 * worktree of the repo, so one ledger tracks all of them. Sessions are keyed by
 * worktree + branch + issue.
 *
 * Exposed both as MCP tools (progress_start/stop/resume/status) and as a tiny CLI
 * (`node dist/progress.js start --issue 3 ...`) so it works even if the MCP server
 * isn't running. Pure ledger logic is factored out for unit tests.
 */
import { spawnSync } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { join } from "node:path";

export type SessionStatus = "in_progress" | "paused" | "done";

export interface Session {
  worktree: string;
  branch: string;
  issue: number | null;
  area: string | null;
  status: SessionStatus;
  started: string;
  updated: string;
  step: string | null;
  done: string[];
  next: string[];
  notes: string | null;
  dirty: boolean;
}

export interface Ledger {
  version: number;
  updated: string;
  sessions: Session[];
}

export interface StartInput {
  issue?: number | null;
  area?: string | null;
  step?: string | null;
  note?: string | null;
}
export interface StopInput {
  step?: string | null;
  done?: string[];
  next?: string[];
  note?: string | null;
}

const LEDGER_VERSION = 1;

function git(repoRoot: string, args: string[]) {
  return spawnSync("git", args, {
    encoding: "utf8",
    cwd: repoRoot,
    env: { ...process.env, GIT_TERMINAL_PROMPT: "0" },
  });
}

function gitCommonDir(repoRoot: string): string {
  const r = git(repoRoot, ["rev-parse", "--path-format=absolute", "--git-common-dir"]);
  const p = (r.stdout || "").trim();
  return p || join(repoRoot, ".git");
}

function ledgerPath(repoRoot: string): string {
  const dir = join(gitCommonDir(repoRoot), "openwarden");
  mkdirSync(dir, { recursive: true });
  return join(dir, "progress.json");
}

export function currentWorktree(repoRoot: string): string {
  return (git(repoRoot, ["rev-parse", "--show-toplevel"]).stdout || repoRoot).trim();
}
export function currentBranch(repoRoot: string): string {
  return (git(repoRoot, ["rev-parse", "--abbrev-ref", "HEAD"]).stdout || "").trim();
}
function isDirty(repoRoot: string): boolean {
  return (git(repoRoot, ["status", "--porcelain"]).stdout || "").trim().length > 0;
}

/** uncommitted + unpushed snapshot for a worktree (used by stop/resume). */
export function gitSnapshot(repoRoot: string): { uncommitted: string[]; unpushed: string[] } {
  const uncommitted = (git(repoRoot, ["status", "--porcelain"]).stdout || "")
    .split("\n").map((l) => l.trim()).filter(Boolean);
  const up = git(repoRoot, ["log", "--oneline", "@{u}..HEAD"]);
  const unpushed = up.status === 0
    ? (up.stdout || "").split("\n").map((l) => l.trim()).filter(Boolean)
    : [];
  return { uncommitted, unpushed };
}

/** Parse `git worktree list --porcelain` into {path,branch} rows. */
export function listWorktrees(repoRoot: string): { path: string; branch: string }[] {
  const out = git(repoRoot, ["worktree", "list", "--porcelain"]).stdout || "";
  const rows: { path: string; branch: string }[] = [];
  let cur: { path: string; branch: string } | null = null;
  for (const line of out.split("\n")) {
    if (line.startsWith("worktree ")) {
      if (cur) rows.push(cur);
      cur = { path: line.slice("worktree ".length).trim(), branch: "(detached)" };
    } else if (line.startsWith("branch ") && cur) {
      cur.branch = line.slice("branch ".length).trim().replace(/^refs\/heads\//, "");
    }
  }
  if (cur) rows.push(cur);
  return rows;
}

// ---- pure ledger logic (unit-tested) ----

export function emptyLedger(now: string): Ledger {
  return { version: LEDGER_VERSION, updated: now, sessions: [] };
}

export function sameSession(s: Session, worktree: string, branch: string, issue: number | null): boolean {
  return s.worktree === worktree && s.branch === branch && s.issue === issue;
}

/** Insert or update a session in-place by (worktree,branch,issue); returns the new sessions array. */
export function upsertSession(sessions: Session[], next: Session): Session[] {
  const out = sessions.filter((s) => !sameSession(s, next.worktree, next.branch, next.issue));
  out.push(next);
  return out;
}

/** The active (in_progress|paused) session for a worktree, most-recently-updated first. */
export function sessionsForWorktree(ledger: Ledger, worktree: string): Session[] {
  return ledger.sessions
    .filter((s) => s.worktree === worktree && s.status !== "done")
    .sort((a, b) => (a.updated < b.updated ? 1 : -1));
}

// ---- I/O wrappers ----

function loadLedger(repoRoot: string, now: string): Ledger {
  const p = ledgerPath(repoRoot);
  if (!existsSync(p)) return emptyLedger(now);
  try {
    const parsed = JSON.parse(readFileSync(p, "utf8")) as Ledger;
    if (!Array.isArray(parsed.sessions)) return emptyLedger(now);
    return parsed;
  } catch {
    return emptyLedger(now);
  }
}

function saveLedger(repoRoot: string, ledger: Ledger): void {
  ledger.updated = ledger.updated || new Date().toISOString();
  writeFileSync(ledgerPath(repoRoot), JSON.stringify(ledger, null, 2) + "\n", "utf8");
}

function nowIso(): string {
  return new Date().toISOString();
}

// ---- operations ----

export function progressStart(repoRoot: string, input: StartInput) {
  const now = nowIso();
  const worktree = currentWorktree(repoRoot);
  const branch = currentBranch(repoRoot);
  const issue = input.issue ?? null;
  const ledger = loadLedger(repoRoot, now);
  const prior = ledger.sessions.find((s) => sameSession(s, worktree, branch, issue));
  const session: Session = {
    worktree,
    branch,
    issue,
    area: input.area ?? prior?.area ?? null,
    status: "in_progress",
    started: prior?.started ?? now,
    updated: now,
    step: input.step ?? prior?.step ?? null,
    done: prior?.done ?? [],
    next: prior?.next ?? [],
    notes: input.note ?? prior?.notes ?? null,
    dirty: isDirty(repoRoot),
  };
  ledger.sessions = upsertSession(ledger.sessions, session);
  ledger.updated = now;
  saveLedger(repoRoot, ledger);

  // worktree-awareness: warn if the issue's area doesn't fit this worktree.
  const warnings: string[] = [];
  if (session.area && !worktree.toLowerCase().includes(session.area.replace(/^area:/, "").split("-")[0])) {
    warnings.push(
      `heads up: you're in worktree "${worktree}" (branch ${branch}); confirm it matches ${session.area}.`
    );
  }
  return { started: session, worktrees: listWorktrees(repoRoot), warnings };
}

export function progressStop(repoRoot: string, input: StopInput) {
  const now = nowIso();
  const worktree = currentWorktree(repoRoot);
  const ledger = loadLedger(repoRoot, now);
  const active = sessionsForWorktree(ledger, worktree)[0];
  if (!active) {
    return { ok: false, message: `no active session in ${worktree}; run /openwarden start first.` };
  }
  const snap = gitSnapshot(repoRoot);
  active.status = "paused";
  active.updated = now;
  if (input.step !== undefined) active.step = input.step;
  if (input.done) active.done = [...active.done, ...input.done];
  if (input.next) active.next = input.next;
  if (input.note) active.notes = input.note;
  active.dirty = snap.uncommitted.length > 0;
  ledger.sessions = upsertSession(ledger.sessions, active);
  ledger.updated = now;
  saveLedger(repoRoot, ledger);
  return { ok: true, paused: active, snapshot: snap };
}

export function progressResume(repoRoot: string, issue?: number | null) {
  const now = nowIso();
  const worktree = currentWorktree(repoRoot);
  const ledger = loadLedger(repoRoot, now);
  const here = sessionsForWorktree(ledger, worktree).filter(
    (s) => issue == null || s.issue === issue
  );
  if (here.length === 0) {
    // No session in this worktree — show the cross-worktree dashboard instead.
    return {
      ok: true,
      scope: "dashboard" as const,
      message: `no paused/in-progress session in ${worktree}. Sessions across all worktrees:`,
      sessions: ledger.sessions,
      worktrees: listWorktrees(repoRoot),
    };
  }
  const snap = gitSnapshot(repoRoot);
  return { ok: true, scope: "worktree" as const, worktree, sessions: here, snapshot: snap };
}

export function progressStatus(repoRoot: string) {
  const ledger = loadLedger(repoRoot, nowIso());
  return { sessions: ledger.sessions, worktrees: listWorktrees(repoRoot), ledgerPath: ledgerPath(repoRoot) };
}

// ---- CLI (fallback when the MCP server isn't running) ----

function parseArgs(argv: string[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) {
      const key = a.slice(2);
      const val = argv[i + 1] && !argv[i + 1].startsWith("--") ? argv[++i] : "true";
      out[key] = val;
    }
  }
  return out;
}

function runCli(): void {
  const [cmd, ...rest] = process.argv.slice(2);
  const args = parseArgs(rest);
  const repoRoot = process.env.OPENWARDEN_REPO_ROOT || process.cwd();
  const issue = args.issue ? Number(args.issue) : null;
  let result: unknown;
  switch (cmd) {
    case "start":
      result = progressStart(repoRoot, { issue, area: args.area ?? null, step: args.step ?? null, note: args.note ?? null });
      break;
    case "stop":
      result = progressStop(repoRoot, { step: args.step ?? null, next: args.next ? [args.next] : undefined, note: args.note ?? null });
      break;
    case "resume":
      result = progressResume(repoRoot, issue);
      break;
    case "status":
      result = progressStatus(repoRoot);
      break;
    default:
      result = { error: `usage: progress <start|stop|resume|status> [--issue N] [--area area:x] [--step ...] [--next ...] [--note ...]` };
  }
  process.stdout.write(JSON.stringify(result, null, 2) + "\n");
}

// Run as CLI only when invoked directly (not when imported by the MCP server).
import { fileURLToPath } from "node:url";
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  runCli();
}

/**
 * propose.ts — PR-gated KB writes, FULLY ISOLATED from the contributor's working tree.
 *
 * The KB branch is created in a SEPARATE temporary git worktree based on the PR target
 * (origin/<default branch>); the entry + index are written and committed there, pushed,
 * and a PR labeled `kb-update` is opened, then the temp worktree is removed. The
 * contributor's own worktree, branch, HEAD, and uncommitted work are NEVER touched.
 *
 * (Replaces the earlier in-place `checkout -b` approach which could carry unrelated
 * commits into the PR, mishandle a detached HEAD, or strand the contributor — Codex review.)
 */
import { spawnSync } from "node:child_process";
import { mkdirSync, existsSync, writeFileSync, rmSync } from "node:fs";
import { join, dirname } from "node:path";
import { tmpdir } from "node:os";
import { runGh } from "./gh.js";
import { loadIndex, type KbIndexEntry } from "./kb.js";

export interface ProposeInput {
  type: "decision" | "gotcha" | "design-memory";
  title: string;
  body: string;
  tags: string[];
  supersedes?: string;
}

export interface ProposeResult {
  ok: boolean;
  message: string;
  branch?: string;
  prUrl?: string;
}

const MAX_TITLE = 120;
const MAX_BODY = 16000;

function git(cwd: string, args: string[]) {
  return spawnSync("git", args, {
    encoding: "utf8",
    cwd,
    env: { ...process.env, GIT_TERMINAL_PROMPT: "0", SSH_ASKPASS_REQUIRE: "never" },
  });
}

function slugify(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 60);
}

function subdirFor(type: ProposeInput["type"]): string {
  if (type === "decision") return "decisions";
  if (type === "gotcha") return "gotchas";
  return "design-memory";
}

/** Validate at the boundary; returns an error string or null. */
export function validateInput(input: ProposeInput): string | null {
  if (!input?.title?.trim()) return "title is required";
  if (/[\r\n]/.test(input.title)) return "title must be a single line";
  if (input.title.length > MAX_TITLE) return `title too long (>${MAX_TITLE} chars)`;
  if (!input?.body?.trim()) return "body is required";
  if (input.body.length > MAX_BODY) return `body too long (>${MAX_BODY} chars)`;
  const tags = Array.isArray(input.tags) ? input.tags.map(slugify).filter(Boolean) : [];
  if (tags.length === 0) return "at least one non-empty tag is required";
  if (!slugify(input.title)) return "title has no slug-safe characters";
  return null;
}

/**
 * Pure: the entry file contents + index mutation. Frontmatter scalars are JSON-quoted
 * (valid YAML double-quoted scalars) so a title/tag can never break the frontmatter.
 * Exposed for unit tests.
 */
export function buildEntry(
  input: ProposeInput,
  today: string
): { id: string; relPath: string; fileContents: string; newIndexEntry: KbIndexEntry } {
  const id = slugify(input.title);
  const relPath = `kb/${subdirFor(input.type)}/${id}.md`;
  const tags = input.tags.map(slugify).filter(Boolean);
  const tagsYaml = `[${tags.map((t) => JSON.stringify(t)).join(", ")}]`;
  const supersedesLine = input.supersedes ? `supersedes: ${slugify(input.supersedes)}\n` : "";
  const fileContents =
    `---\n` +
    `id: ${id}\n` +
    `title: ${JSON.stringify(input.title)}\n` +
    `type: ${input.type}\n` +
    `tags: ${tagsYaml}\n` +
    `status: active\n` +
    supersedesLine +
    `created: ${today}\n` +
    `updated: ${today}\n` +
    `source_pr: null\n` +
    `---\n\n` +
    `${input.body.trim()}\n`;
  const newIndexEntry: KbIndexEntry = {
    id,
    path: relPath,
    title: input.title,
    type: input.type,
    tags,
    status: "active",
  };
  return { id, relPath, fileContents, newIndexEntry };
}

/** The PR target ref (origin/main, else origin/master). */
function defaultBaseRef(repoRoot: string): string {
  for (const ref of ["origin/main", "origin/master"]) {
    if (git(repoRoot, ["rev-parse", "--verify", "--quiet", ref]).status === 0) return ref;
  }
  return "origin/main";
}

export function proposeKbUpdate(repoRoot: string, input: ProposeInput): ProposeResult {
  const err = validateInput(input);
  if (err) return { ok: false, message: err };

  const today = new Date().toISOString().slice(0, 10);
  const { id, relPath, fileContents, newIndexEntry } = buildEntry(input, today);
  const branch = `kb-update/${id}`;

  const base = defaultBaseRef(repoRoot);
  const baseName = base.replace(/^origin\//, "");
  // Refresh the base so the entry + index are computed against the live PR target.
  git(repoRoot, ["fetch", "--no-tags", "origin", baseName]);

  // Isolated temp worktree (outside the repo) — never touches the contributor's tree/branch.
  const wt = join(tmpdir(), "openwarden-kb-wt", id);
  if (existsSync(wt)) {
    return { ok: false, message: `a KB proposal worktree for "${id}" already exists (${wt}); remove it and retry.` };
  }
  mkdirSync(dirname(wt), { recursive: true });

  const add = git(repoRoot, ["worktree", "add", "-B", branch, wt, base]);
  if (add.status !== 0) {
    return { ok: false, message: `could not create isolated worktree from ${base}: ${add.stderr.trim()}` };
  }

  try {
    // Dedup against the BASE index (the one the PR actually modifies).
    const idx = loadIndex(wt);
    if (idx.entries.some((e) => e.id === id)) {
      return { ok: false, message: `an entry with id "${id}" already exists on ${baseName}; pick a distinct title.`, branch };
    }
    if (input.supersedes) {
      const target = idx.entries.find((e) => e.id === slugify(input.supersedes!));
      if (target) target.status = "superseded";
    }
    idx.entries.push(newIndexEntry);
    idx.generated = today;

    const absPath = join(wt, relPath);
    mkdirSync(dirname(absPath), { recursive: true });
    writeFileSync(absPath, fileContents, "utf8");
    writeFileSync(join(wt, "kb", "index.json"), JSON.stringify(idx, null, 2) + "\n", "utf8");

    git(wt, ["add", relPath, "kb/index.json"]);
    const commit = git(wt, [
      "commit",
      "-S",
      "-s",
      "-m",
      `docs(kb): propose ${input.type} ${JSON.stringify(input.title)}\n\n` +
        `KB-update via MCP propose_kb_update. PR-gated; reviewed under CODEOWNERS (/kb/**).\n\n` +
        `Co-Authored-By: Claude <noreply@anthropic.com>`,
    ]);
    if (commit.status !== 0) {
      return { ok: false, message: `commit failed (signing/DCO?): ${commit.stderr.trim()}`, branch };
    }

    const push = git(wt, ["push", "-u", "origin", branch]);
    if (push.status !== 0) {
      return {
        ok: false,
        message: `committed to ${branch} but push failed: ${push.stderr.trim()}. The branch exists locally; push it and open a kb-update PR manually.`,
        branch,
      };
    }

    const pr = runGh(
      [
        "pr", "create",
        "--base", baseName,
        "--label", "kb-update",
        "--title", `kb: ${input.title}`,
        "--body",
        `Proposed ${input.type} KB entry via MCP \`propose_kb_update\`.\n\n` +
          `- entry: \`${relPath}\`\n- index updated\n\n` +
          `Reviewed under CODEOWNERS (/kb/** → @modernn) and scanned by kb-content-gate.`,
      ],
      wt
    );
    if (!pr.ok) {
      return { ok: false, message: `branch pushed but PR open failed: ${pr.stderr.trim()}`, branch };
    }
    return {
      ok: true,
      message: "opened kb-update PR from an isolated worktree (your tree/branch untouched)",
      branch,
      prUrl: pr.stdout.trim(),
    };
  } finally {
    // Always tear the temp worktree down; the pushed branch stays intact.
    git(repoRoot, ["worktree", "remove", "--force", wt]);
    try {
      rmSync(wt, { recursive: true, force: true });
    } catch {
      /* best effort */
    }
  }
}

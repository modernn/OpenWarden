/**
 * Thin wrappers around the GitHub CLI (`gh`) for the autopilot MCP server.
 *
 * SECURITY / ETHOS: these run the CONTRIBUTOR's own `gh` (their own auth /
 * GITHUB_TOKEN). There is NO shared service account, NO server-side token, and
 * nothing is sent anywhere except GitHub via the user's existing credentials.
 * If `gh` is not installed or not authed, the tools fail loudly and the
 * contributor can fall back to plain `gh` commands or the web UI.
 */
import { spawnSync } from "node:child_process";

export interface GhResult {
  ok: boolean;
  stdout: string;
  stderr: string;
}

export function runGh(args: string[], cwd?: string): GhResult {
  const res = spawnSync("gh", args, {
    encoding: "utf8",
    cwd,
    // Never prompt; fail closed if auth/input is needed.
    env: { ...process.env, GH_PROMPT_DISABLED: "1", GIT_TERMINAL_PROMPT: "0" },
  });
  if (res.error) {
    return { ok: false, stdout: "", stderr: `gh not available: ${res.error.message}` };
  }
  return {
    ok: res.status === 0,
    stdout: res.stdout ?? "",
    stderr: res.stderr ?? "",
  };
}

/** List issues with the `claimed` label (the "active work" board). */
export function getActiveWork(repoRoot: string): GhResult {
  return runGh(
    [
      "issue",
      "list",
      "--label",
      "claimed",
      "--state",
      "open",
      "--json",
      "number,title,labels,assignees,url,milestone",
      "--limit",
      "50",
    ],
    repoRoot
  );
}

/**
 * Claim an issue: assign it to the authed user and add the `claimed` label.
 * Uses the contributor's own gh auth. PR/implementation stays the human/agent's job.
 */
export function claimWork(repoRoot: string, issueNumber: number): GhResult {
  if (!Number.isInteger(issueNumber) || issueNumber <= 0) {
    return { ok: false, stdout: "", stderr: `invalid issue number: ${issueNumber}` };
  }
  return runGh(
    ["issue", "edit", String(issueNumber), "--add-assignee", "@me", "--add-label", "claimed"],
    repoRoot
  );
}

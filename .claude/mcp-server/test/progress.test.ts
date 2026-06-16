import { describe, it, expect } from "vitest";
import {
  emptyLedger,
  sameSession,
  upsertSession,
  sessionsForWorktree,
  type Session,
} from "../src/progress.js";

function mk(partial: Partial<Session>): Session {
  return {
    worktree: "/wt",
    branch: "main",
    issue: null,
    area: null,
    status: "in_progress",
    started: "t0",
    updated: "t0",
    step: null,
    done: [],
    next: [],
    notes: null,
    dirty: false,
    ...partial,
  };
}

describe("progress ledger (pure)", () => {
  it("emptyLedger has version 1 and no sessions", () => {
    const l = emptyLedger("now");
    expect(l.version).toBe(1);
    expect(l.sessions).toEqual([]);
    expect(l.updated).toBe("now");
  });

  it("sameSession keys on worktree+branch+issue", () => {
    const s = mk({ worktree: "/a", branch: "b", issue: 3 });
    expect(sameSession(s, "/a", "b", 3)).toBe(true);
    expect(sameSession(s, "/a", "b", 4)).toBe(false);
    expect(sameSession(s, "/a", "c", 3)).toBe(false);
    expect(sameSession(s, "/z", "b", 3)).toBe(false);
  });

  it("upsertSession replaces the matching key and keeps others", () => {
    const a = mk({ worktree: "/a", branch: "b", issue: 3, step: "old" });
    const b = mk({ worktree: "/a", branch: "b", issue: 9 });
    const updated = mk({ worktree: "/a", branch: "b", issue: 3, step: "new" });
    const arr = upsertSession([a, b], updated);
    expect(arr.length).toBe(2);
    expect(arr.find((s) => s.issue === 3)?.step).toBe("new");
    expect(arr.find((s) => s.issue === 9)).toBeTruthy();
  });

  it("upsertSession inserts when there is no match", () => {
    const arr = upsertSession([], mk({ issue: 1 }));
    expect(arr.length).toBe(1);
  });

  it("sessionsForWorktree excludes done and sorts most-recent first", () => {
    const l = emptyLedger("now");
    l.sessions = [
      mk({ worktree: "/a", issue: 1, status: "done", updated: "t9" }),
      mk({ worktree: "/a", issue: 2, status: "paused", updated: "t1" }),
      mk({ worktree: "/a", issue: 3, status: "in_progress", updated: "t5" }),
      mk({ worktree: "/b", issue: 4, status: "in_progress", updated: "t9" }),
    ];
    const got = sessionsForWorktree(l, "/a");
    expect(got.map((s) => s.issue)).toEqual([3, 2]); // done excluded; t5 before t1; other worktree excluded
  });
});

# OpenWarden KB MCP server (local, optional)

A tiny **stdio** [MCP](https://modelcontextprotocol.io) server that gives a contributor's
Claude Code / Codex session structured access to the shared knowledgebase (`kb/`) and to
GitHub issue claiming — using **your own machine and your own `gh` auth**.

## Ethos (non-negotiable)

- **No SaaS, no shared service account, no telemetry.** Everything runs locally. GitHub
  actions use *your* `gh` credentials / `GITHUB_TOKEN`; nothing is sent anywhere else.
- **Fully optional and additive.** The repo builds and contributes fine without it — an
  agent can just `Read` the `kb/` files. This server is a convenience, not a dependency.
- **No secrets in the repo.** This server reads no secret files and stores no tokens.

## Tools

| Tool | What it does | Backed by |
|------|--------------|-----------|
| `get_session_context()` | Returns the `kb/index.json` digest + the data-not-instructions reminder. Read first at session start. | local fs |
| `search_kb(query, tags?, limit?)` | Ripgrep over `kb/` + frontmatter tag filter; ranked entries with snippets. | local fs + `rg` (JS fallback) |
| `get_active_work()` | Lists open issues labeled `claimed`. | your `gh` |
| `claim_work(issue_number)` | Assigns the issue to you + adds `claimed`. | your `gh` |
| `propose_kb_update(type, title, body, tags, supersedes?)` | **PR-gated**: new branch → writes entry + updates index → signed/DCO commit → opens a `kb-update` PR. Never writes live `kb/`. | git + your `gh` |

## Setup (one step, optional)

```bash
cd .claude/mcp-server
npm install
npm run build
npm test          # runs the search_kb unit tests
```

Then it's registered for Claude Code via the repo-root [`.mcp.json`](../../.mcp.json), which
launches it through [`start.sh`](start.sh). `start.sh` auto-builds on first run if `dist/`
is missing.

Requirements: Node 18+ (Node 22 tested), and `gh` authenticated (`gh auth status`) for the
issue tools. `rg` (ripgrep) is used for content search but there's a JS fallback if it's
absent.

## Notes

- `OPENWARDEN_REPO_ROOT` env var overrides repo-root detection (handy for tests / unusual
  layouts).
- `propose_kb_update` deliberately does **not** mutate the live `kb/` directory — it opens a
  PR so `/kb/**` CODEOWNERS review and the `kb-content-gate` workflow apply.
- `node_modules/` and `dist/` are gitignored; run `npm install && npm run build` after clone.

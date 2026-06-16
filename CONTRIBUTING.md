# Contributing to OpenWarden

Thanks for helping build a parental-control tool that protects kids **without**
surveilling them. OpenWarden is Apache-2.0 and community-built.

New here? **[`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md)** gets it running
locally (Android-first). Using Claude Code or Codex? See **[`AGENTS.md`](AGENTS.md)**
and run the **`/openwarden`** skill — it asks what you want to do and
walks you through it.

## Non-negotiables (a PR that violates any of these will not merge)

1. **No subscription, no SaaS, no telemetry/analytics/phone-home.** Ever. Optional
   convenience integrations (Cloudflare family DNS, Tailscale, ntfy.sh) are fine
   only if truly optional with a FOSS/self-hosted alternative alongside.
2. **No content monitoring.** Messages, photos, audio are never read or sent.
   OpenWarden is a *control* tool, not a *monitoring* tool — the stalkerware
   boundary lives here.
3. **Fail-closed.** Every error path defaults to *more* restriction, never less.
4. **Kid transparency.** Every monitored category is shown on the kid's device.
5. **No secrets in the repo.** No keys, tokens, or BIP39 phrases — ever.

These are also enforced by review (CODEOWNERS) and CI, not just trust.

## Ways to contribute

Contributing with an AI agent (Claude Code / Codex)? The near-autopilot flow — role-scoped
agents, a shared knowledgebase, an optional local MCP server, and approval tiers — is
documented in **[`docs/CONTRIBUTOR_AUTOPILOT.md`](docs/CONTRIBUTOR_AUTOPILOT.md)**. It's
additive and optional; everything below still works without it.

Run `/openwarden` (Claude Code) or pick a path here:

- **Run it locally** → [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md).
- **Write tests** → [`docs/TESTING.md`](docs/TESTING.md). Tests are *required* for
  crypto, protocol, and policy logic.
- **Implement a feature** → grab an `enhancement` / `good first issue` /
  `agent-ready` [issue](https://github.com/modernn/OpenWarden/issues). Check
  [`docs/adr/`](docs/adr/) and the doc-tier rules first (v2/v3 frozen-design needs
  an ADR before implementation).
- **Fix a bug** → grab a `bug` issue; add a regression test.
- **Improve docs** → docs are the spec; keep them accurate.

Good first areas: DPC hardening (more `DevicePolicyManager` coverage), policy
bundle signature/verification tests, the "Why am I blocked?" kid UX, translations.

## We'll push back on
- Content surveillance (reading messages, screenshots of social apps).
- Anything that *requires* Google Play services or a vendor cloud account.
- Multi-tenant SaaS deployment patterns.

## Pull request flow

1. **Branch via a worktree** (see [`CLAUDE.md`](CLAUDE.md) → Git worktrees):
   `git worktree add -b feat/my-thing ../OpenWarden-my-thing main`.
2. Make the change **with tests**.
3. `cd parent-kmp && ./gradlew check` (or the relevant module build) — green.
4. **Commit signed + DCO:** `git commit -S -s -m "feat: …"`.
   - **Conventional** (`feat:`/`fix:`/`docs:`/`chore:`/`test:`) — **required**.
   - **DCO sign-off** (`-s`) certifies the [Developer Certificate of Origin](https://developercertificate.org/) — **required**.
   - **Signed** (`-S`) — required (see GOVERNANCE.md). Never `--no-verify`.
5. Open a PR; fill in the template. Touched a `docs/` behavior or ADR? Update it in
   the same PR. Architecture pivot? Add an ADR in `docs/adr/`.

CI runs `./gradlew check` + lint + a DCO/conventional-commit check. CODEOWNERS
routes crypto/proto/policy/CI changes to a maintainer.

## Style
- Kotlin: ktlint defaults.
- Swift (iOS, later): SwiftLint defaults.
- Commits: imperative, conventional, signed, DCO.

## Bench-testing the DPC
Use a **dev device or emulator** (Device Owner) — **never test policy changes on a
real kid's phone first**. Provisioning: [`docs/PROVISIONING_V2.md`](docs/PROVISIONING_V2.md);
emulator E2E: `./scripts/test-emulator.sh`.

## Conduct & security
By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md). Found a
vulnerability? **Do not open a public issue** — see [`SECURITY.md`](SECURITY.md).
Governance: [`docs/GOVERNANCE.md`](docs/GOVERNANCE.md).

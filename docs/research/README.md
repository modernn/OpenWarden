# Research reports

Raw input reports from the research phase (2026-06-15). **Not canon.** Use canonical docs in [`../`](../) for current decisions.

These reports are preserved as:
- Audit trail for "how did we decide X"
- Source material for grant applications + governance decisions
- Reference for future contributors who want to understand the threat landscape

If something here contradicts a canon doc, the canon doc wins. ADRs in [`../adr/`](../adr/) supersede earlier research recommendations.

## Index

| File | Topic | Words |
|---|---|---|
| [01-foundation.md](01-foundation.md) | Initial research: DPC pitfalls, kid bypass techniques, Tailscale tradeoffs, MVP recommendations | ~1500 |
| [02-app-stack.md](02-app-stack.md) | App stack research: OSS prior art, KMP vs Flutter, iOS-without-server paths, pair-app UX | ~2200 |
| [03-redteam-adversary.md](03-redteam-adversary.md) | Red team: technical attack catalog (60+ attacks across 15 categories) | ~5800 |
| [04-redteam-kids.md](04-redteam-kids.md) | Red team: kid behavioral / social-engineering attacks | ~1950 |
| [05-redteam-deeper.md](05-redteam-deeper.md) | Red team: deeper bypass research (P9 Play Services Help, accessibility class, etc.) | ~5700 |
| [06-defenses-raw.md](06-defenses-raw.md) | Defense engineering: StrongBox patterns, AVB attestation, sealed-box envelope | ~3500 |
| [07-redteam-design-review.md](07-redteam-design-review.md) | Red team (2026-06-16): adversarial review of the v1 *spec* — gaps, bypasses, and doc/ADR contradictions found before any code. AI-generated, human-verify before acting. | ~2600 |

## How these became canon docs

| Research source | Canon doc |
|---|---|
| 01-foundation + 02-app-stack | ARCHITECTURE.md, PARENT_KMP_STRUCTURE.md, COMMUNITY.md, FUNDING.md |
| 03-04-05 red team | ATTACKS.md (synthesis) |
| 06 defenses | DEFENSES.md (synthesis) + CRYPTO.md (impl) |

## Reading order for new contributors

1. README.md (project front door)
2. ARCHITECTURE.md (architecture overview)
3. ATTACKS.md + DEFENSES.md (threat model)
4. SIMPLIFY.md (scope discipline)
5. Then dive into specific area docs as needed

These research reports are not on the critical reading path — only read if you want the unfiltered input.

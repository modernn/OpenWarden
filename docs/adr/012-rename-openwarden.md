# ADR-012: Project name = OpenWarden (renamed from Kidlock)

Status: Accepted
Date: 2026-06-16

## Context

Initial name: "Kidlock." Issues identified:
- "lock" connotation = punitive
- "kid" too narrow (project targets ages 5-17 per ADR-005)
- Domain availability not verified
- Trademark situation unclear

User asked for an "Open-prefix" name conveying protection of kids on devices.

## Options surveyed

| Name | Verdict |
|---|---|
| OpenWard | Blocked: `github.com/openward` active AI org; `openward.com` brand-held since 2009 |
| OpenGuardian | Blocked: GoGuardian = giant edtech competitor in exact space |
| OpenChaperone | Strong fit but the dictionary meaning may be too "supervisory" |
| OpenSentry | Blocked: active OAuth/identity org owns the name |
| OpenCustody | Blocked: legal/jail connotation |
| OpenHaven | Blocked: Haven Protocol crypto + Open Haven DV charity |
| OpenSteward | Blocked: all 4 domains taken |
| **OpenWarden** | Available across npm/crates/.org/.dev/.app, no major collisions |

## Decision

Adopt **OpenWarden**.

- GitHub org: `github.com/openwarden` (free)
- Domains: `openwarden.org` / `.dev` / `.app` (all unregistered)
- npm: `openwarden` (free)
- crates.io: `openwarden` (free)
- Mastodon: `@openwarden@mastodon.social` (free)
- F-Droid app id: `org.openwarden.child` (kid app), `org.openwarden.parent` (parent app)

## Connotation

- "Warden" carries dual reading: school-crossing warden (positive, parental) vs prison warden (negative). Context — anti-stalkerware OSS parental control — pulls the meaning to the former.
- Minor namespace adjacency: Bitwarden / Vaultwarden are dominant `*warden` brands in security space. Not a competitor; different category. Acceptable.

## Consequences

**Good:**
- All major handles + packages + domains available — easy to claim cleanly.
- "Open" prefix signals OSS intent.
- Single distinctive word (compound to "OpenWarden" reads as one).
- Short enough for CLI/F-Droid app IDs.

**Bad:**
- Minor Bitwarden adjacency in security search results.
- "Warden" can read punitive on first encounter; copy + branding need to soften.
- All 34 docs + repo + memory needed find-replace from "Kidlock" → "OpenWarden" (executed 2026-06-16).

## Go-grab list (immediate)

Run on commit-day:
- Register `openwarden.org`, `openwarden.dev`, `openwarden.app`
- Create `github.com/openwarden` org
- Reserve `openwarden` on npm + crates.io + PyPI
- Claim `@openwarden@mastodon.social` and `@openwarden@floss.social`

## Cross-refs

- Repo memory: `~/.claude/projects/.../openwarden-project.md`
- [`README.md`](../../README.md)

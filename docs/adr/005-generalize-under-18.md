# ADR-005: Generalize from "Oliver" to "kids under 18"

Status: Accepted
Date: 2026-06-15

## Context

Project started built for the lead maintainer's son (Oliver, age 7). Original target was ages 9-12 per research recommendation. User asked to broaden to all kids under 18.

## Options

1. **Build for Oliver specifically.** Tight scope. Hard to reach broader users.
2. **Build for ages 9-12 (research-recommended).** Reasonable. Excludes 5-8 (younger) and 13-17 (teen mode).
3. **Build for ages 5-17 w/ stage defaults.** Broadest target. More UX work per stage.

## Decision

Adopt **option 3: ages 5-17 w/ stage defaults**.

Four life stages baked into PolicyDoc presets (per [`docs/GRADUATED_PRIVILEGES.md`](../GRADUATED_PRIVILEGES.md)):
- **5-7:** very tight allowlist, pictogram-only UI, no AI, parent-driven only
- **8-10:** standard allowlist, basic kid request flow
- **11-13:** earning trust level rises, more autonomy
- **14-17:** teen mode, lighter visibility, opt-in graduation toward 18

Stage = function of (age, parent-overridden trust level). Parent picks initial age + birthday at onboarding; OpenWarden suggests defaults for that stage; parent can override.

Docs purge "Oliver" specifics in canon. Persona kept only in side reports + project memory as origin context.

## Consequences

**Good:**
- Broadest possible v1 reach.
- Existing graduated-privileges design accommodates this w/ minor extension.
- Stages provide structure without forcing 17 separate configs.

**Bad:**
- Stage-7 UI demands pictogram-only (more design effort).
- Stage-17 UI must respect teen agency (different tone calibration).
- Per-stage defaults table maintenance cost.

## Cross-refs

- [`docs/GRADUATED_PRIVILEGES.md`](../GRADUATED_PRIVILEGES.md)
- [`docs/UX_PATTERNS.md`](../UX_PATTERNS.md) §A (kid screens)
- [`docs/KID_TRANSPARENCY.md`](../KID_TRANSPARENCY.md)
- [`docs/ONBOARDING.md`](../ONBOARDING.md) (age + birthday entry)

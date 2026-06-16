# ADR-004: Multi-child data model v1, UI v2

Status: Accepted
Date: 2026-06-15

## Context

Most families have 1+ kid. Building OpenWarden for "one kid only" paints into a corner — v2 schema migration to add multi-child is painful. But shipping multi-child UI in v1 expands scope ~3 weekends.

## Options

1. **Single kid forever.** Reject (excludes most families).
2. **Multi-child UI v1.** +3 weekends scope, more design surface, harder to ship.
3. **Multi-child schema v1, UI v2.** Schema supports N kids. UI shows + manages one kid (the family's first). v2 adds "add kid" flow + multi-kid switcher.

## Decision

Adopt **option 3: schema v1, UI v2**.

PolicyDoc carries `family_id` + `kid_id`. FamilyDoc schema covers N parents × N kids per [`docs/FAMILY_MODEL.md`](../FAMILY_MODEL.md) §1. Each kid has independent identity keys, independent PolicyDoc, independent event log.

V1 UI shows a single kid panel. "Add kid" button = visible but disabled w/ tooltip "Coming v2." First kid setup wizard creates `kid_id=1`.

## Consequences

**Good:**
- Multi-child families not excluded from project intent.
- V2 = small addition (add-kid UI flow), no schema migration pain.
- Recovery phrase covers whole family root from day 1.

**Bad:**
- Slightly more complex schema than strictly needed for v1.
- Some v1 code paths reference `kid_id` redundantly.
- Tooltip on disabled button = small UX paper cut.

## Cross-refs

- [`docs/FAMILY_MODEL.md`](../FAMILY_MODEL.md)
- [`docs/PROTOCOL.md`](../PROTOCOL.md) §2 (PolicyBundle schema)
- [`docs/UX_PATTERNS.md`](../UX_PATTERNS.md) §B (co-parent feed scoping)

# ADR-042: Child serves real on-device app-usage (metadata-only) on `/usage` + an honest `/state`

Status: Accepted
Date: 2026-06-23
Supersedes/relates: ADR-030 (LAN auth; read endpoints expose metadata only, D6), #20 (child LAN-server endpoint surface), #25 (parent dashboard consumer), #74 (this work). Demo provenance: `demo/child-usage` branch (`UsageStatsHelper`, demo `/state` fields).

## Context

On `main`, the child's two read endpoints are stubs: `/usage` returns an empty
`per_app` list (`ApiServer.kt` `TODO(v1)`), and `/state` — while it already carries
a fail-closed `is_locked` (ADR-030 D5) and `policy_not_after` — does not report
whether the device is paired. The #25 parent dashboard therefore has no real
data to render.

The `demo/child-usage` branch (an **old fork** that predates main's crypto/
enforcement era — it must never be merged) carries a working `UsageStatsHelper`
that queries `UsageStatsManager` for per-app foreground time, plus richer `/state`
fields. #74 asks to **port the real, metadata-only `/usage` + the honest `/state`
fields to main** as part of the #20 endpoint surface — without dragging the stale
fork's missing-crypto regressions along.

`/usage` sits directly on the **content-monitoring boundary** — the project's
hardest non-negotiable ("Messages, photos, audio = never read or sent. Stalkerware
boundary lives here."). That is why #74 is `agent-blocked`: it is implemented
attended, with a maintainer holding the merge gate, and recorded here.

Two facts make this safe to land:

1. **It is already inside the disclosed transparency envelope.** `MonitoredCategory`
   already declares `APP_USAGE` ("Which apps you use") and `SCREEN_TIME`, and
   `docs/KID_TRANSPARENCY.md` already discloses "apps used, aggregate only" and
   "per-app totals". `TransparencyTest` machine-checks that every category renders.
   This ADR adds **no new monitored signal** — it makes an already-disclosed,
   already-permissioned signal actually flow.
2. **The manifest already declares `PACKAGE_USAGE_STATS`** (a special appops grant,
   not auto-granted). With no grant, `UsageStatsManager` returns empty.

## Decision

**D1 — `/usage` returns metadata only, never content.** `UsageStatsHelper` reads
only `UsageStatsManager.queryUsageStats` aggregates and exposes exactly three
fields per app: `packageName`, `label` (from `PackageManager`), `foregroundMinutes`
(floor of `totalTimeInForeground / 60_000`). No event timestamps, no in-app
activity, no message/photo/audio surface — none is read, none is serialized. The
stalkerware boundary is enforced by the shape of `AppUsageEntry` and guarded by a
reflection test (TST below). Results are aggregated per package, positive-time
only, sorted descending, capped at the top 15.

**D2 — No grant is fail-closed to *less* disclosure, and honest by default.** When
the `PACKAGE_USAGE_STATS` appops grant is absent, `UsageStatsManager` yields an
empty/null list (or throws `SecurityException`). The response is then:

- **Release builds → `Unavailable`.** `/usage` returns `source: "unavailable"`, an
  empty `per_app`, and a plain notice that the grant is not given. A production
  build **never fabricates usage numbers** — honest-empty, consistent with the
  "not spyware / never punitive" ethos. Showing a parent invented data on a real
  device is the failure this avoids.
- **Debug builds → `DemoFallback`.** Returns a clearly `[DEMO]`-labelled
  illustrative list (`source: "demo-fallback"` + `demo_notice`) so #25 dashboard
  development has something to render before anyone runs the appops grant. The
  label prefix is asserted by test so demo data can never be mistaken for real.

**D3 — Debug vs release is decided at runtime via `FLAG_DEBUGGABLE`, not
`BuildConfig`.** `BuildConfig` generation is not enabled in `child-android`, and
enabling a build feature drifts toward the `agent-blocked` build/CI surface.
`UsageStatsHelper` reads `(context.applicationInfo.flags and
ApplicationInfo.FLAG_DEBUGGABLE) != 0`. The flag is exposed as an injectable
parameter (`debuggable`, defaulted from the context) so both branches are tested
deterministically without a second build variant.

**D4 — Unexpected errors fail closed to empty, not to fabricated data.** A non-
`SecurityException` failure returns `Error(message)`; `/usage` answers `500` with
`source: "error"` and an empty `per_app`. An error never falls through to demo
data and never leaks an exception-derived payload.

**D5 — `/state` gains an honest `paired` field; the existing better fields stay.**
`/state` adds `"paired" to (PolicyStore(ctx).parentPubkey() != null)` — true iff a
parent Ed25519 key has been pinned at pairing. Main's existing `is_locked`
(fail-closed, ADR-030 D5) and `policy_not_after` (integer ms, §2) are **kept as-is
and not regressed** — `policy_not_after` already is the honest form of the demo
branch's `policy_expires_at` (renamed to integer-ms in §2), so this ADR does not
reintroduce the demo's hardcoded `is_locked = false` or ISO-string fields.

**D6 — Read endpoints stay open on the LAN (unchanged, ADR-030 D6).** `/usage` and
`/state` remain unauthenticated metadata reads in v1; this ADR adds no transport
auth and no new permission. `paired`/usage metadata is the same class of
LAN-visible read ADR-030 D6 already ratified.

## Consequences

- `main`'s `/usage` serves real per-app foreground minutes once the appops grant is
  present (`adb shell appops set com.openwarden.child.debug GET_USAGE_STATS allow`),
  and an honest unavailable/demo response otherwise. #25 can build against it.
- No new monitored category, no new permission, no transparency-screen change — the
  signal was already disclosed and permissioned; only the data path is new.
- **Accepted residual (device-path):** the real `UsageStatsManager` path is verified
  under Robolectric's `ShadowUsageStatsManager`, not on a physical device. A
  pre-prod gate (grant the appops on an emulator/device and confirm `source:
  "on-device"` with real numbers) is recorded for the E2E harness, consistent with
  the project's "never E2E against a stale build" rule. Host tests prove the
  aggregation/sort/cap/floor logic, the metadata-only field shape, and the
  debug-vs-release no-grant branch deterministically.
- The `demo/child-usage` branch remains an unmergeable old fork; only these two
  narrow surfaces were ported.

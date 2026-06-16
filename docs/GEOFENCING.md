# Geofencing & Location — OpenWarden Design

> **Status:** Tier 2 design proposal. Not v1, not v2 base. Pre-locks
> the data model, API choice, and the privacy contract so when v3
> picks this up there's a single artifact to argue against.
>
> **Companion docs:** [`UX_PATTERNS.md`](UX_PATTERNS.md),
> [`ATTACKS.md`](ATTACKS.md), [`SIMPLIFY.md`](SIMPLIFY.md),
> [`openwarden-graduated-privileges.md`](../../openwarden-graduated-privileges.md),
> [`DEFENSES.md`](DEFENSES.md).
>
> **Locked product decisions (non-negotiable):**
> - All enforcement is **on-device.** No cloud, no OpenWarden-operated
>   relay.
> - Alerts route **only to the paired parent device(s).**
> - **No continuous tracking.** Geofence triggers only; no polling
>   loop.
> - The kid sees exactly what is monitored, per UX_PATTERNS §C5
>   and graduated-privileges §12.

---

## 1. Use cases

Four, no more. Each maps to a concrete parent need surfaced in the
red-team and graduated-privileges analyses.

- **School-hours leak detection.** Geofence around the school
  address; during school hours, alert if the kid exits without
  re-entering a known-safe zone within a hysteresis window. Maps
  to graduated-privileges §4.1 "left school during school hours"
  super-concerning alert.
- **Home arrived/left aggregation.** Geofence around home; emit
  enter/exit events for the parent's daily summary. No alert per
  event — this is aggregate, not interrupt. ("Oliver: home 15:42,
  out 16:30, home 18:05.")
- **Banned-zone alerts.** Rare, parent-defined no-go area
  (specific friend's house under a custody-court order; a known
  bad-news mall). Documented trade-off: this is the most
  surveillance-shaped use case and the one most likely to feel
  punitive. Off by default; requires explicit parent setup with
  a per-fence reason logged to the co-parent feed.
- **Battery-aware operation.** A non-feature-feature. The system
  MUST NOT continuously poll location. If it does, the parent
  sees a phone that dies before lunch, blames OpenWarden, uninstalls.

Out of scope: real-time "where is Oliver right now" map view.
That's surveillance, not safety. The parent gets an aggregate
"at home / at school / elsewhere" indicator (§8), nothing finer.

---

## 2. Android API choice

Three viable APIs, one recommendation.

- **`FusedLocationProviderClient` (Google Play Services).** Most
  accurate, most battery-friendly. Fuses GPS + Wi-Fi + cell + sensor
  data. Hard dependency on Google Play Services, which conflicts
  with SIMPLIFY.md §2 Tier 3 "anything that requires Play Services
  in the required path."
- **`LocationManager` (AOSP).** Works on every Android device,
  including GrapheneOS / CalyxOS / de-Googled Pixels. Less accurate
  in dense urban canyons; higher battery cost; no built-in geofence
  primitive (we'd implement enter/exit logic manually).
- **`GeofencingClient` (Play Services).** Native geofence enter/exit
  event delivery; the OS does the polling-and-debouncing for us.
  Same Play Services dep as FusedLocationProvider.

**Recommendation:** ship both paths and pick at install time.

- **Default path (stock Pixel with Play Services):**
  `FusedLocationProviderClient` + `GeofencingClient`. Best accuracy,
  best battery, zero polling code in OpenWarden.
- **Fallback path (de-Googled, F-Droid build):** `LocationManager`
  with `requestLocationUpdates(PASSIVE_PROVIDER)` + a self-rolled
  geofence engine triggered by significant-motion sensor wakeups
  (§10).

The fallback adds maintenance burden — flag in SIMPLIFY.md terms as
**Build M, Maintain M.** Acceptable because the de-Googled audience
is a meaningful slice of the open-source parent population and the
fallback is mostly mechanical.

---

## 3. Battery cost

Numbers from Android documentation, Pixel 7 telemetry studies, and
DontKillMyApp battery-impact notes. All are 24-hour drain estimates
on a healthy battery.

| Mode | Drain | Verdict |
|---|---|---|
| Continuous location (1Hz GPS) | 5–10% / day | **Unacceptable.** |
| Periodic poll (every 60s) | 3–5% / day | Still too high. |
| Geofence-triggered only | 1–2% / day | **Target.** |
| Significant-motion-gated geofence | < 1% / day | Aspirational. |

The product decision: **geofence-only triggers, no continuous
polling, ever.** If a feature ask requires continuous polling, the
answer is no per SIMPLIFY.md §3.

---

## 4. Geofence event model

Events are uniform with the rest of the OpenWarden event log
(`PROTOCOL.md`, `DEFENSES.md` Pattern B).

```
GeofenceEvent {
    ts:                    Timestamp        // monotonic + wall
    fence_id:              FenceId          // uuid
    fence_name:            String           // "Home", "School"
    action:                ENTER | EXIT | DWELL
    location_accuracy_m:   u16              // GPS reported accuracy
    confidence:            u8               // 0..100
    source:                FUSED | GPS | WIFI | CELL | LAST_KNOWN
}
```

Each event is **sealed-box encrypted to the parent pubkey** before
it leaves the kid device — same envelope as every other event in
the system. Even a kid with root sees ciphertext, per
graduated-privileges §6.

Aggregation happens on the **parent device**, not on the kid
device. The parent app composes the daily summary ("Oliver: at
school 8:00–15:30, home 15:42–18:05") from the decrypted event
stream. Kid device emits raw events; nothing in the encrypted
payload pre-aggregates.

---

## 5. School-hours leak detection

The marquee use case. Wire end-to-end:

- **School geofence:** circle around the school address, radius
  150m default (covers most campuses; configurable 50–500m).
  Parent draws on a map during setup.
- **School hours:** 7:00–15:30 weekdays default, per-family
  configurable. Holidays and snow days are parent-overridable
  via a "Skip today" button.
- **Trigger logic:** during school hours, on EXIT from school
  fence, start a 5-minute timer. If after 5 minutes the kid is
  not within a Home / Library / known-safe zone, fire the alert.
- **Hysteresis:** 5-minute debounce on EXIT; ENTER clears any
  pending alert immediately. Prevents the alert storm when GPS
  jitter pushes the kid 30m outside the fence and back.
- **Accuracy gate:** alert suppressed if `location_accuracy_m > 50`.
  An exit event with 200m accuracy is noise, not signal. The
  alert routes to the daily summary as "unverified exit" instead.
- **Alert delivery:** routes to the §4.1 super-concerning channel
  per graduated-privileges. Both parents notified, cannot be
  suppressed by the slider.

False-positive rate target: **< 1 false alert per kid per month.**
Above that and parents stop reading.

---

## 6. Edge cases

Every one of these is a real-world thing that has broken commercial
geofence products. Document each, decide each.

- **Indoor location (no GPS).** Fall back to Wi-Fi/cell. Accuracy
  degrades to 50–200m. Events from these sources carry `source:
  WIFI | CELL` so the alert logic can apply the accuracy gate.
- **Indoors at school (GPS lost).** The kid walks into the school
  building, GPS drops. Use the **last-known-good** position taken
  while still inside the school fence; treat as "still inside"
  until either GPS reacquires outside or 4h elapse. The 4h cap
  prevents a phone left at school overnight from silently
  reporting "at school" all night.
- **Power-saver mode.** Android throttles location updates
  aggressively under Doze. OpenWarden requests location-bypass
  whitelist at provisioning (DPC privilege); the
  GeofencingClient path is Doze-aware by design and still fires.
- **Mock location attack.** Kid discovers Developer Options
  contains "Select mock location app." Block via
  `DISALLOW_DEBUGGING_FEATURES` (already in v1 baseline per
  ATTACKS.md) and additionally via Android's
  `isFromMockProvider()` flag — any event with mock-provider true
  is logged as a **tamper flag** (graduated-privileges §4.2),
  not as a normal geofence event.
- **Battery dead.** No location for hours. Tied to the
  heartbeat-silence ladder (DEFENSES #8 + graduated-privileges
  §4.2): 6h offline during school hours fires the super-concerning
  alert; outside school hours the silence threshold is 24h.

---

## 7. Privacy model

This is the contract. Document loudly and link from onboarding.

- **Default state:** location features **off.** Parent must opt in
  during onboarding or later in Settings → Oliver → Location.
- **Kid transparency** (per graduated-privileges §12 "What does
  OpenWarden see?"): the kid app shows, verbatim, "Dad sees if you
  leave school during school hours. Dad sees when you arrive home
  and when you leave. Dad does NOT see where you are right now."
- **Trust-level integration** (graduated-privileges §3.3):

  | Trust | Location visibility |
  |---|---|
  | L1 | Full (every geofence event) |
  | L2 | Full (default) |
  | L3 | Full (location is the irreducible safety signal) |
  | L4 | Aggregate (daily summary, no per-event) |
  | L5 | Emergency-only (super-concerning list only) |

  Location stays "Full" longer than other categories on purpose:
  a parent of a 14-year-old may not want to read messages but does
  want to know the phone made it to school.

- **No location history** persisted on the parent device beyond 90
  days. The aggregate daily summary collapses to "school days
  attended" after 30 days and is purged at 90.

---

## 8. Parent UI

Single section in the parent app: **Settings → Oliver → Location.**

- **Map view** with a geofence editor. Tap to drop a center pin,
  drag the radius. One fence at a time being edited.
- **Quick presets:** "Home," "School," "Grandma's." Each preset
  carries sensible defaults (Home: enter/exit events only, no
  alert; School: time-windowed alert per §5; Grandma's: enter
  event aggregated, no alert).
- **Per-fence settings:**
  - Enter alert: yes / no.
  - Exit alert: yes / no.
  - Time window: any / weekdays-school-hours / custom.
  - Action: alert-only / alert-and-lock (locks non-emergency apps
    when triggered).
- **"Now" indicator:** the closest the system gets to live tracking
  is a coarse status — "Oliver: at home" or "Oliver: at school" or
  "Oliver: away from known zones." No coordinates, no street-level
  precision. The view also shows the timestamp of the last event
  ("as of 4 min ago").
- **Co-parent visibility:** every fence creation, edit, or deletion
  broadcasts to the Family Feed per UX_PATTERNS §B. Co-parent can
  undo within 24h.

---

## 9. Sneaky edge cases

Documented honestly per ATTACKS.md "what we can and can't defend."

- **Kid leaves phone at home, goes elsewhere.** Phone reports "at
  home"; kid is at the mall. *Mitigation:* physical-activity
  sensor cross-check. If the phone has been stationary (no
  step-counter ticks, no motion sensor delta) for > 2h during the
  day, surface a soft anomaly: "Oliver's phone hasn't moved since
  10am." Defense in depth, not perfect. The kid who knows this
  exists can shake the phone in their backpack and defeat it.
- **Kid wraps phone in foil.** GPS dies; Wi-Fi often still works
  through aluminum if any seam exists. We do not "detect foil" —
  we fall back to last-known-good and let the offline-heartbeat
  ladder catch the suspicious silence.
- **Friend's phone scenario.** Kid carries friend's phone instead.
  Out of scope per ATTACKS.md §"V1 CANNOT defend." Not a
  OpenWarden-soluble problem.
- **Family Link / Apple Maps already shows location.** A parent
  who is already running a "where's my kid" map app is solving a
  different problem. OpenWarden is not that app.

---

## 10. Lazy-trigger optimization

The implementation patterns that make §3's "1–2% drain" achievable.

- **Sleep window:** between 3am and 5am local, suppress geofence
  evaluation entirely. The kid is in bed; the phone shouldn't be
  burning radio cycles. (Bedtime lock per UX_PATTERNS §A6
  enforces this from the policy side as well.)
- **Idle suppression:** if no geofence transition has fired in the
  last 4h and the activity sensor reports STILL, skip the next
  scheduled evaluation entirely.
- **Significant-motion gate:** on the fallback (no-Play-Services)
  path, register a `SignificantMotionSensor` listener and only
  wake the location subsystem when the phone has moved
  meaningfully. Cuts drain by another ~40% in field tests on
  Pixel 6a / GrapheneOS.
- **Batched event emission:** events are batched into the next
  store-and-forward sync window (per STORE_AND_FORWARD.md), not
  pushed real-time. The super-concerning alerts are the exception
  — those bypass batching.

---

## 11. Multi-fence priority

When fences overlap, the system reports the kid as being in the
highest-priority zone.

Priority order: **Home > School > Library > Other named zones >
Banned zones.** Banned zones are last so that a kid sitting in
"Grandma's house" that happens to be inside a "no-go mall" zone
(weird, but possible) registers as "at Grandma's," not as in the
banned zone. The exception: if the kid is *only* in the banned
zone, the banned-zone alert fires immediately.

The parent UI shows a small badge on the "Now" indicator naming
the active zone, so co-parents and the parent always see the same
classification.

---

## 12. Test plan

Pre-ship checklist. Each item must pass on bench Pixel before this
feature gets a green tag.

- **Bench Pixel walk test.** Set geofence around the office;
  walk in and out 5 times across a 30-minute window. Confirm
  5 ENTER + 5 EXIT events, none dropped, no duplicates.
- **24h battery drain.** Enable geofencing with 3 fences active.
  Measure drain over a normal use day. **Pass:** total system
  drain ≤ 15%; OpenWarden-attributable drain ≤ 2%.
- **Mock-location attack.** Enable Developer Options, set mock
  location app, walk a fake GPS track. **Pass:** all mock events
  flagged as `isFromMockProvider=true` and routed to the tamper
  log, never to the geofence event stream.
- **GPS-loss-at-school simulation.** Set a school fence; cover
  the phone (Faraday bag) for 90 minutes during "school hours";
  uncover. **Pass:** no false EXIT alert during the GPS-loss
  window; last-known-good preserved; ENTER event when GPS
  re-acquires inside the fence.
- **Doze / power-saver soak.** Leave device in power-saver mode
  overnight; walk in/out of fence on the morning commute.
  **Pass:** geofence events still fire within 60s of crossing.
- **Co-parent broadcast.** Edit a fence on parent device A;
  confirm parent device B receives the signed change within the
  next sync window and shows it on the Family Feed.

---

## 13. Tier classification

Per SIMPLIFY.md §2:

- **v1:** skip entirely. Location is not on the foundational
  list; the v1 surface is already aggressive.
- **v2:** ship basic Home and School geofences, enter/exit
  events, school-hours leak detection. No banned zones yet, no
  fancy presets.
- **v3:** time-windowed banned zones, multi-fence priority UI,
  significant-motion-gated fallback path, advanced patterns
  (route-deviation detection if it can be done without
  continuous tracking, which is doubtful — likely deferred to
  v4 or never).

**Build cost (v2):** M. **Maintain cost (v2):** M — Android's
location APIs churn, OEM battery-killing behavior shifts every
major release, and the DontKillMyApp landscape requires periodic
re-testing.

---

## 14. References

- **Android Geofencing API** —
  https://developer.android.com/develop/sensors-and-location/location/geofencing
- **FusedLocationProviderClient** —
  https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient
- **LocationManager (AOSP)** —
  https://developer.android.com/reference/android/location/LocationManager
- **DontKillMyApp** — per-OEM battery-killer notes; geofencing
  is one of the most affected feature categories.
  https://dontkillmyapp.com/
- **UnsafeBench-style honest-numbers framing** applied to
  geofence accuracy (Pixel 7 GPS field tests, 2024 community
  benchmarks): expect 4–8m accuracy outdoors with sky view,
  20–60m urban canyon, 50–200m indoors.
- **Bark / Pinwheel / Family Link location feature comparisons**
  — informed the "no live map" decision; all three offer live
  maps, all three slide toward stalkerware-shaped UX over time.
  OpenWarden declines this path.
- Internal: [`UX_PATTERNS.md`](UX_PATTERNS.md),
  [`ATTACKS.md`](ATTACKS.md), [`SIMPLIFY.md`](SIMPLIFY.md),
  [`DEFENSES.md`](DEFENSES.md),
  [`STORE_AND_FORWARD.md`](STORE_AND_FORWARD.md),
  [`PROTOCOL.md`](PROTOCOL.md),
  [`openwarden-graduated-privileges.md`](../../openwarden-graduated-privileges.md).

# Miscellaneous Topics

Four gap topics that don't yet have their own design doc but are too important
to leave undocumented. Each section is a self-contained sketch; when one of
these grows enough scaffolding to deserve its own file, lift it out.

Companion docs: [`ATTACKS.md`](ATTACKS.md), [`DEFENSES.md`](DEFENSES.md),
[`UX_PATTERNS.md`](UX_PATTERNS.md), [`ONBOARDING.md`](ONBOARDING.md).

---

## 1. App-permission audit on grant

When the parent allowlists a new app, OpenWarden surfaces the app's declared
Android permissions alongside a plain-English concern banner. This converts
the "I just want my kid to have TikTok" reflex into a thirty-second pause
where the parent sees what TikTok is actually asking for. The goal is not
to block the grant — most parents will still tap through — but to ensure
informed consent, in the spirit of `UX_PATTERNS.md` C7 ("show data, let the
parent decide").

### Mechanism

The DPC has full `QUERY_ALL_PACKAGES` access as Device Owner. Before any
new app is added to the allowlist, the parent app issues a sync request
to the child device for a fresh permission read:

```kotlin
val pkgInfo = packageManager.getPackageInfo(
    pkg, PackageManager.GET_PERMISSIONS
)
val declared = pkgInfo.requestedPermissions.orEmpty()
val granted  = pkgInfo.requestedPermissionsFlags.orEmpty()
```

The DPC returns `(permission_name, granted_flag, protection_level)` tuples
to the parent app via the store-and-forward layer.

### Concern classification

The parent app maps each permission to one of four banner states:

| State | Examples | Banner copy |
|---|---|---|
| **Routine** | `INTERNET`, `WAKE_LOCK`, `VIBRATE` | No banner. |
| **Notable** | Storage, Notifications | "App wants to access photos." |
| **Concerning** | Location (any), Camera, Microphone, Contacts, SMS, Body Sensors, Phone | Red banner + one-line rationale. |
| **Severe** | `READ_SMS`, `ACCESS_BACKGROUND_LOCATION`, `MANAGE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW` | Modal: "This app wants to read text messages. Most kid-friendly apps do not need this." |

The classification table lives in `PERMISSION_AUDIT.md` (to-be-written)
alongside a default override list for known kid-safe apps (Khan Academy
asking for camera = OK because it's homework scanning; Roblox asking for
microphone = concerning because of voice chat).

### UX flow

When the parent taps "Allow new app" in the parent app, they see one
screen with three blocks:

1. **App identity** — icon, full name, publisher, installer (Play Store vs
   sideload). Sideload flagged in red regardless of permissions, because
   `ATTACKS.md` D8 (Play web install) and unknown-source installs are the
   single highest-risk install path.
2. **Declared permissions** — list, sorted by severity descending. Each
   row has a pictogram (camera, mic, location pin, etc.) so the parent
   gets the gestalt in two seconds.
3. **Action row** — three buttons: **Allow**, **Allow with caveats**,
   **Deny**. The middle button opens a per-permission toggle sheet using
   `setPermissionGrantState(admin, pkg, permission, GRANT_STATE_DENIED)`
   to revoke individual runtime permissions while still allowing launch.

### "Allow with caveats" example

Parent allows TikTok but denies Location + Microphone. The DPC stores
the caveats in the policy bundle alongside the allowlist entry; on the
next sync the child applies them via `setPermissionGrantState`.

A Family Feed entry appears: "Dad allowed TikTok with caveats: location
denied, microphone denied." Co-parents see the rationale.

### Integration with existing flows

- The "Ask dad" request flow (`UX_PATTERNS.md` A2) routes through this
  audit screen before the parent can tap Approve. The "approve forever"
  path cannot bypass the audit; the "approve for 30 minutes" path can,
  because permissions are scoped to grants.
- Default presets: each `UX_PATTERNS.md` C8 preset ("School mode" etc.)
  ships with a baked-in permission audit verdict per app, so parents who
  use presets never see the audit screen unless they go off-script.

---

## 2. Calendar import for time windows

Parents already maintain a school calendar in Google Calendar, Apple
Calendar, or a school-district iCal feed. OpenWarden should read that
calendar (read-only, locally) and use it to auto-relax bedtime and
school-hours rules on holidays and breaks — instead of forcing the
parent to remember to toggle "vacation mode" every Friday afternoon
of a long weekend.

### Sources

- **Android parent app:** `CalendarContract` provider, queried with
  `READ_CALENDAR` permission. User selects which calendar to bind.
- **iOS parent app:** EventKit framework, `EKEntityType.event`,
  user picks an `EKCalendar`.
- **Web iCal feed:** the parent pastes a public `.ics` URL (school
  districts often publish one). The parent app fetches it on a daily
  cron, parses with a small RFC 5545 reader.

The chosen calendar is a single source of truth per child. Multiple
children can bind to different calendars (Oliver's school vs Maya's
school).

### Event interpretation

OpenWarden looks for a small vocabulary of event titles/categories,
matched case-insensitively against the event SUMMARY field:

| Event match | Effect |
|---|---|
| "No school", "School closed", "Holiday", "Break", "Vacation" | All-day → next-night bedtime relaxed 1h; school-hours rule disabled for that day. |
| "Half day", "Early dismissal" | School-hours window ends at event end time. |
| "Snow day" | Same as Holiday, but parent app prompts to confirm because timing is usually morning-of. |
| "Field trip" | School-hours intact, but Maps + Messages temporarily allowed even if normally blocked. |
| (anything else) | Ignored. |

The parser is intentionally narrow. We do not OCR PDFs, parse natural
language, or pull "free/busy" data. The match list is editable in
Advanced settings so districts with idiomatic event names ("PD Day",
"Records Day", "In-service") can be added.

### Privacy posture

- Only the title, start, and end of matching events leave the calendar
  source. Body, attendees, location are never read.
- All parsing happens on the parent device. The calendar feed is
  **never** sent through OpenWarden's store-and-forward layer. The
  parent device pushes only the derived time-window override to the
  child.
- A read happens once per day at 5am local time and once on parent
  app foreground if the cached read is > 12h old.

### UX flow

In `UX_PATTERNS.md` C8 settings, under **Rules → Time windows**, a new
optional row: **"Match my school calendar."** Off by default. When on:

- Picker shows available calendars / lets the user paste an iCal URL.
- A preview pane shows the next 14 days of matched events and what
  OpenWarden would do for each. The parent reviews and taps **Use this
  calendar**.
- The next 14 days of overrides are written to the policy bundle
  and signed (`policy_seq` ratchets — see `ATTACKS.md` C8). The child
  enforces.
- Manual overrides always win. If the parent taps "lock now" or
  "vacation mode" the calendar-derived rule is suppressed until the
  manual action expires.

### Edge cases

- **Snow day** — districts announce too late for the 5am read. The
  parent app exposes a one-tap "Snow day today" button on the
  dashboard that pushes the override immediately. The Family Feed
  shows the manual snow day with a "calendar said: no" caveat.
- **Calendar feed offline** — last known good cache is used. After
  3 days of staleness, parent app shows a notice.
- **Two children, two schools, conflicting calendars** — each child's
  time-windows are independent. No global "family vacation" toggle in
  v1; v2 may add a family-wide vacation mode.

---

## 3. Beta program design

Between today's scaffolding and a public v1, OpenWarden will go through
a small structured beta with 10 families. The goal is **not** scale
or marketing — it is to find the bugs that only surface in a real
kid's day and to validate the threat model with kids who actually
try to break it.

### Recruitment

Target: 10 families with kids age 9-12 and at least one technically
literate parent. Channels:

- **Hacker News** — "Show HN: OpenWarden, sub-free open-source parental
  control" post once the scaffolding builds clean. Comments thread
  doubles as a feedback channel.
- **r/selfhosted, r/Parenting, r/HomeNetworking, r/degoogle** — one
  post each, no crossposting spam.
- **Matrix and Signal communities** for FOSS-adjacent parents.
- **EFF** — pitched as a privacy-respecting alternative to commercial
  trackers. Their newsletter has run similar pieces for FOSS tools.
- **Personal network** — Larson asks 2-3 friends with kids in target
  age band. These are the lowest-friction recruits; they will also
  forgive the rough edges.

Application form: a short Google Form (yes, irony) or self-hosted
formless email with: kid's age, current parental control stack,
parent technical comfort (1-5), willingness to be on a 30-minute
intake call, willingness to be on Matrix for ongoing feedback.

### Onboarding

Each accepted family gets:

1. A **30-minute video call with Larson** to walk through
   `ONBOARDING.md` together. We do this live because the bugs that
   show up at the kitchen table are the ones we miss in solo testing.
2. The provisioner binary and parent APK directly from a signed
   release, not from a public Releases page yet.
3. A pre-filled feedback template: "What surprised you? What scared
   you? What did your kid try? What does your kid think?"
4. An invite to the **#openwarden-beta Matrix room**, parents only.

### Communication

- **Matrix room** for real-time questions, day-to-day chatter, kid
  bypass-attempt stories. Larson is present daily for the first month.
- **Weekly digest email** every Sunday: changelog of fixes since last
  week, top 3 issues observed, "what we're working on this week."
- **Office hours**: a 60-minute open Matrix voice call every Friday
  at a time that covers US + EU evenings.

### Bug intake

- **GitHub Issues** with a `beta` label. Beta families get write access
  via a `openwarden-beta` GitHub team.
- For families uncomfortable filing on GitHub, Matrix DM to Larson is
  fine; he files the issue on their behalf with attribution.
- Severity triage: S0 (kid can bypass restriction) → fix within 48h,
  emergency patch release. S1 (parent app crash) → next weekly.
  S2 (UX rough edges) → backlog.

### Telemetry posture

Per the pledge in `README.md`, **no automatic telemetry**. All feedback
is voluntary and human-mediated. The opt-in **crash report** flow is
the one exception:

- On a crash, the child or parent app shows a modal: "Send a crash
  report to help fix this?" The bundle is the bare minimum
  stack-trace + last 100 log lines, **stripped of family/kid
  identifiers**, signed by the parent's key for authenticity, and
  sent to a single signed-bundle email box that Larson reads.
- Crash reports never include policy contents, app names from the
  allowlist, kid names, or device identifiers beyond model/Android
  version.

### Bug bounty

None in v1 — no budget. Researchers who report security issues get
credit in `CHANGELOG.md` and an offer to write the fix as a co-authored
PR. The `SECURITY.md` policy will publish a coordinated-disclosure
window (90 days standard).

### Exit criteria for v1 public launch

The beta exits and v1 launches publicly when **all four** are true:

1. **4 consecutive weeks** with no S0 bug (kid bypass) reported by
   any beta family.
2. **All 10 families** still actively running OpenWarden at the end of
   week 4 (retention proxy — if a family drops out, exit-interview
   them).
3. **The onboarding median time** across the 10 setups is ≤ 60 minutes
   (the 45-minute target in `ONBOARDING.md` with a 15-minute buffer).
4. **No open S1 issues older than 2 weeks.**

### Anti-goals for the beta

- Not chasing the 11th family. 10 is enough for v1; more dilutes
  Larson's attention.
- Not promising a launch date. Pulled-forward dates kill quality.
- Not collecting "engagement metrics." This is not a SaaS funnel.

---

## 4. Kid-as-developer threat model

The `ATTACKS.md` adversary model is "motivated 9-13yo kid, semi-technical,
can follow YouTube + run adb." This section addresses the next tier up:
the kid who is themselves a developer — can fork a repo, modify Kotlin,
build a signed APK, and read the threat model docs. Realistically, this
is a 14-16 year old, but increasingly the lower bound is dropping
(Hacker News parents report 11-12 year olds shipping iOS apps).

### Why this is a separate tier

A kid-developer is not subject to most behavioral attack assumptions.
They will read this doc, `DEFENSES.md`, and `PROVISIONING.md`. They
will know exactly where the seams are. Treating them like the
`ATTACKS.md` baseline kid is incorrect.

### The attack

The kid forks OpenWarden on GitHub. They modify the DPC source: remove
the bedtime enforcement, remove the policy-bundle signature check,
hard-code "always-on policy" to `false`. They build the modified APK.

Now they need to get it onto the device. This is where the defense
in depth matters.

### Defense layer 1: bootloader + Verified Boot

To replace the DPC, the kid needs to either:

- Install over the existing signed APK with the same package name. The
  signatures will not match → installer rejects. Pass.
- Uninstall the existing DPC. `setUninstallBlocked(self, true)` plus
  `setUserControlDisabled` → blocked at the OS layer. Pass.
- Replace the system image. Requires bootloader unlock. `DISALLOW_OEM_UNLOCK`
  via the DPC plus FRP keyed to the parent's Google account → blocked
  unless the kid can re-flash factory partitions, which is itself
  blocked. Pass on stock Pixel 7.
- Sideload to a *different* package name and run alongside. The original
  DPC is still enforcing. Useless to the attacker. Pass.

### Defense layer 2: Verified Boot attestation

Suppose the kid does unlock the bootloader (e.g. it was a used device
with bootloader already unlocked before provisioning — operator error
on the parent's part). OpenWarden at runtime:

- Calls `KeyStore` Key Attestation, gets the attestation certificate
  chain.
- Walks the chain to the Google root.
- Parses `RootOfTrust.verifiedBootState`. If anything other than
  `VERIFIED` (green), OpenWarden refuses to enter the "operate" state
  and the parent app fires the "Oliver's device shows tampering"
  alert (`DEFENSES.md` #9).

The child cannot fake the attestation cert — it is signed by Google's
hardware-backed key in Titan M2. To forge it the kid needs Google's
signing key. Pass.

### Defense layer 3: parent-side detection

Even in the residual failure mode where the kid pulls off something
exotic (custom AVB key signed with an OEM debug cert, etc.), the
parent device sees:

- Heartbeat anomalies. The modified DPC will have different timing,
  different bundle behavior. The sealed-box event log will either
  stop or produce malformed entries. `DEFENSES.md` #8 heartbeat
  silence alarms fire at 15min / 1h / 6h / 24h.
- Attestation drift. Periodic re-attestation (`DEFENSES.md` #4) will
  fail or return a different cert chain. Parent app alerts: "Oliver's
  phone's hardware identity changed. This usually means a factory
  reset or hardware replacement."

### Realism per age band

| Age band | Probability of attempt | Probability of success on stock Pixel 7 |
|---|---|---|
| 5-10 | ~0% | n/a |
| 11-13 | ~3% (one kid in 30 will try) | <1% (would need help from older sibling) |
| 14-16 | ~15% | ~3% (real but rare) |
| 16+ | ~30% | ~10% if they're CS-track |

### Acceptance

At ages 16-18, this threat is real, the kid is months away from
adulthood, and the right product answer is: "the kid is ready to
transition off OpenWarden." Decommission flow (`ONBOARDING.md` "what
if you want to remove OpenWarden") is the intended response. OpenWarden
v1 is not for teens defended against themselves — that is a different
product (Teen Mode, scoped to v3 in `ROADMAP.md`).

### Documentation in ATTACKS.md

Add this scenario as **Tier 3 risk** in `ATTACKS.md` §"What V1 CANNOT
defend against": "Kid-developer with bootloader access on a
pre-unlocked device." The mitigation is not technical: it is to detect
the attempt and **treat it as a decommission event** — i.e. the
parent gets a high-priority alert, the family has the conversation,
and OpenWarden either transitions to Teen Mode (v3) or is removed
cleanly. There is no honest defense against a determined kid who
is also a hardware hacker; pretending otherwise is security theater
(see `DEFENSES.md` anti-patterns).

### Operational implication

The provisioning checklist in `ONBOARDING.md` Step 3 should add a
verification step: **"Confirm bootloader is locked before
provisioning."** The Provisioner already checks this in
`PROVISIONING_V2.md`; surface it to the parent in plain language:
"Your kid's phone bootloader is locked. Good. If it were unlocked,
a technical kid could replace OpenWarden."

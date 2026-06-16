# OpenWarden UX Patterns

This document captures the user-experience patterns for OpenWarden across the
child-side Android device and the parent-side KMP application (Compose for
Android, SwiftUI for iOS). It is informed by the kid red-team behavioral
analysis in `ATTACKS.md` and the deeper notes in `openwarden-redteam-kids.md`,
both of which identify **co-parent visibility** as the highest-leverage
defense currently missing from Family Link, Screen Time, and most commercial
MDM-style parental controls.

Locked product decisions:

- Age target: 9-12 (Oliver age TBD, ~10)
- License: Apache 2.0, open source
- Parent app: Kotlin Multiplatform (Compose Multiplatform + SwiftUI bridge)
- Child Android side: minimal kid-facing UI, stock launcher in v1
- Tone: respectful, never punitive

---

## A. Kid-side UX patterns (Android child DPC)

The child device runs OpenWarden as a Device Policy Controller. The kid-facing
surface area is intentionally tiny: a handful of screens, all matter-of-fact,
all reachable from a tap on a blocked or suspended app.

### A1. "Why am I blocked?" screen

Triggered when the kid taps an app that has been suspended via
`setPackagesSuspended` or blocked by the active policy. Android shows the
suspension dialog; OpenWarden replaces the default copy with a OpenWarden screen.

The screen is pictogram-led. Reading levels at 9-12 vary widely (Ramotion and
Common Sense Media both note a 3-4 grade spread inside a single age cohort),
so the pictogram carries the meaning and the text confirms it.

Layout, top to bottom:

- App icon, grayed at 40% opacity
- A single large reason pictogram, one of four:
  - **Clock**: time window (e.g. only between 4pm and 7pm)
  - **Lock**: always blocked by parent rule
  - **Question mark**: needs parent approval first
  - **Hourglass**: daily time budget used up
- One line of plain English under the pictogram. Examples:
  - "Roblox is paused until 4pm."
  - "YouTube needs dad to say yes."
  - "You used all your Minecraft time today."
- One primary button: **"Ask dad"**. Tapping opens the request flow (A2).
- One secondary text link: **"When does this change?"** which expands the
  active schedule in human terms.

Design rationale: pictogram-first reduces frustration vs text-only block
messages (Common Sense Media UX-for-kids principles, Ramotion kid-UX
articles). The grayed app icon affirms that the kid identified the right
app; not seeing their app at all is more confusing than seeing it dimmed.

### A2. "Ask dad" request flow

One screen, one request type at a time. Multi-step wizards lose kids.

- **Why?** field, optional, single-line, 80-char max. Plain placeholder
  ("Optional: tell dad why").
- **Duration ask** as four preset chips: `15 min` / `30 min` / `1 hour` /
  `Today`. No custom picker in v1 - presets are faster and reduce nuisance
  asks for 7-minute slivers.
- **Send** button. On send, the screen shows a paper-airplane pictogram and
  a one-line confirmation: "Dad got it. Usually answers in about 5 min."
  Wait estimate is based on the parent's rolling median response time.
- Tap-back returns the kid to the home launcher. A small badge appears on
  the blocked app's icon: a clock with a dot, indicating "request pending."

Anti-spam rules:

- After a denial, the same app is on a 15-minute cooldown. If the kid
  re-taps "Ask dad" within that window, the screen instead shows: "You
  asked 8 min ago. Try again in 7 min."
- A burst of three denied requests for the same app within an hour fires a
  behavioral signal in the parent app (not a block, just a chart spike on
  the Family Feed: "Oliver asked for Discord 3 times this hour").
- The cooldown is intentionally short. Long cooldowns feel punitive; short
  cooldowns surface the pattern to parents without escalating with the kid.

### A3. Home / launcher

v1 ships with the stock Pixel launcher. Replacing the launcher is a v2
goal (Headwind-MDM-style custom home), but in v1 we want OpenWarden to be
invisible 99% of the time the kid uses the phone.

- Allowlisted apps: visible in the app drawer, full color, indistinguishable
  from a non-OpenWarden device.
- Blocklisted apps: visible but grayed by `setPackagesSuspended`. The kid
  sees that the app exists but cannot launch it. The admin message routes
  to the "Why am I blocked?" screen.
- Time-window apps: visible normally, but a small clock badge appears on
  the icon during the 30 minutes before the window closes, and the icon
  greys when the window expires.

We deliberately do not hide apps. Hiding them invites the kid to assume
OpenWarden is lying, which kicks off the entire bypass research project that
`openwarden-redteam-kids.md` documents.

### A4. Lock-now screen

When a parent triggers "lock now" from the parent app, the child device
shows a full-screen OpenWarden takeover that survives reboots and recents.

- Plain copy: "Phone paused by dad."
- Time, if applicable: "Back at 6:30pm."
- One button: **"Tell dad I need it back."** This sends a signed request,
  separate from the normal A2 flow, marked as an urgent ping. The parent
  sees it on lockscreen, not buried in the feed.
- Emergency dial accessible from a small bottom-edge tap target (911 / 999 /
  112 depending on locale). This is non-negotiable: even a locked phone
  must place emergency calls.
- Cannot be dismissed by the kid. Only a signed parent unlock command, or
  the recovery-phrase ceremony (A7), clears it.

### A5. Stale-policy mode

If the child device has not synced with a parent device for more than 7
days, it enters a strict baseline:

- Only the allowlist's "always safe" subset is launchable (phone, messages,
  maps, school apps).
- A persistent banner appears at the top of the launcher: "Sync with dad
  to keep using all your apps. Tap here for help."
- Tap routes to a screen with a single button: "Ask dad to sync." Pressing
  it composes an SMS, ntfy ping, and any other paired transport in
  parallel.

This defeats the "wait it out" attack from the red-team corpus, where the
kid hopes that an unsynced policy will eventually loosen.

### A6. Bedtime lock

Bedtime is the hardest lock in the system. From the bedtime start time
until wake, the phone shows only:

- "Asleep until 7am."
- Emergency dial button.
- Nothing else. No request flow. No "ask dad."

Crucially, **even the parent's local PIN cannot unlock bedtime on the
child device.** The only way to lift bedtime early is a signed unlock
command issued from the parent's own device. This defeats two specific
attacks in the red-team notes:

- "Sneak parent's PIN while they nap, unlock my phone."
- "Convince mom to type her PIN to 'fix' the phone, actually unlocks
  bedtime."

The friction is intentional. If the parent does not have their own phone
nearby, bedtime stays locked. That's the trade-off, and it's the right
one for the threat model.

### A7. Recovery phrase use on child device

Some emergencies (parent phone lost, co-parent unreachable) require the
recovery phrase to be entered directly on the child device.

- Phrase entry happens on the child only. Showing the phrase on the parent
  device for the kid to memorize is a leak path.
- The UI shows a "look away" prompt and a soft visual obscure (large
  fingerprint-style overlay) before phrase entry.
- The parent's other devices receive an immediate audit alert: "Recovery
  phrase used on Oliver's phone at 3:42pm." This cannot be suppressed.
- Biometric-friend pattern on the parent device: if the parent has a
  trusted second adult nearby, the phrase entry can be co-witnessed via a
  parent-device biometric tap, so the phrase doesn't have to be read aloud.

---

## B. Co-parent visibility feed

This is defense #20 from `DEFENSES.md` and the highest-leverage behavioral
defense identified by the red-team analysis.

### B1. The problem

The Family Link bypass corpus shows that **4 of the top 10 behavioral
bypasses exploit divide-and-conquer between parents** plus the absence of
shared permission history. The canonical pattern:

> Kid asks dad for Discord ("I need it for a school group project"). Dad
> says yes. Mom never knows Discord was unblocked, only finds out three
> weeks later when she sees a DM thread.

Family Link and Screen Time both treat each parent as an independent
admin. There is no shared timeline, no notification when the other parent
changes a rule, no easy undo. OpenWarden fixes this.

### B2. The solution

Every rule change in the parent app broadcasts a signed command to all
paired parent devices. Co-parents see, in a unified Family Feed:

- Who changed what (which parent, which device).
- When.
- Optional reason from the changer.
- A reversible "Undo" if the change is recent (< 24h) or if it is still
  active.

Counter-commands are explicit and signed. "Mom undid Dad's unblock of
Discord at 4:32pm. Reason: 'discuss first.'" This is not a silent revert -
the original parent sees the undo immediately, which forces the
conversation that should have happened.

### B3. Data model

- A **family** is a set of N paired parent devices (1-3 typical, no hard
  cap) plus M paired child devices.
- All parent devices receive every other parent's signed commands via the
  store-and-forward layer (see `STORE_AND_FORWARD.md`).
- Commands carry: actor identity, target child device, action, optional
  reason, signature, timestamp.
- Commands are tombstoned (never deleted), so the audit log is
  append-only.
- Counter-commands reference the command they reverse.

### B4. Parent UX flow

The parent app's primary tab is **Family Feed**. It is chronological,
newest at top, infinite scroll backward.

Each feed entry shows:

- Avatar / name of the parent who acted.
- One-line summary: "Dad unblocked Discord on Oliver's phone."
- Timestamp and optional reason.
- A row of action buttons, contextual: **Undo** (if reversible), **Reply**
  (private note to the other parent), **Discuss** (opens a thread visible
  only to parents).

Notifications:

- Android co-parents get a real push notification on every rule change.
- iOS v1 surfaces changes on next foreground; v2 uses an ntfy "doorbell"
  to wake the app.
- Weekly digest: "This week: 12 unlocks, 4 blocks, 2 undos." Delivered
  Sunday evening, designed to be the one moment parents review patterns
  together.

### B5. Solo-parent mode

Not every family has co-parents. Solo mode degrades gracefully:

- No co-parent feed entries, but the parent's own actions remain logged in
  a personal audit timeline.
- Useful for the parent's own memory ("when did I unblock that?") and as
  evidence in custody contexts.
- A pinned banner offers "Invite a co-parent" but never nags after one
  dismissal.

### B6. Babysitter / guest caregiver mode

Babysitters need *some* control without becoming full admins. OpenWarden
introduces a **limited admin** role:

Babysitters can:

- Approve pre-approved exception bundles ("movie night" allows YouTube and
  Disney+ until 9pm).
- Trigger lock-now.
- See current status.

Babysitters cannot:

- Change rules permanently.
- Install or uninstall apps.
- Factory reset.
- Add or remove other admins.

Pairing flow: parent generates a QR code on their phone, babysitter scans
it, parent confirms on their device. Each babysitter action appears in
the Family Feed with the babysitter's name and a distinct color band, so
co-parents see it without confusion.

---

## C. Cross-cutting UX principles

### C1. Cooldown timers everywhere

- Every temporary unblock auto-reverts. Default: 60 minutes.
- Every denied "more time?" request triggers a 15-minute kid-side
  cooldown.
- "Vacation mode" requires an end date entered upfront; no open-ended
  loosening.
- Friction is asymmetric by design: locking more is one tap, locking less
  is several. The defaults push toward the safer state.

### C2. Audit trail visible

- Every change is logged and replayable.
- "What did I change this week?" view, scoped per parent.
- "What did Oliver do this week?" view: apps used, requests sent,
  blocks hit, with time histograms.
- These views anchor the "you said yesterday I could!" arguments that
  every parent of a 10-year-old has had.

### C3. Randomized parent check-in prompts

- The app occasionally prompts a parent at unpredictable times: "Want to
  review Oliver's last 24h?"
- This defeats the "kid learned mom's pattern" predictive bypass.
- Capped at 1 per day, with quiet hours respected. Never more than once
  per day, never at night.

### C4. Friction for irreversible actions

- "Permanent rule change" surfaces a confirmation modal with explicit
  copy stating what will change and that the change is permanent.
- "Decommission device" requires recovery phrase entry plus a 24-hour
  delay (waived only with co-parent co-sign for true emergencies).
- "Add new admin" requires an existing admin to approve in-app, not just
  receive a notification.

### C5. Honest communication with the kid

- No fake "permission denied" screens. Always explain the actual reason.
- Pictograms carry meaning for non-readers; text confirms for readers.
- The parent's name appears on every rule ("Dad set this rule") so the
  kid knows exactly whom to ask.
- Never lie to the kid. Lying teaches the kid that the system is a
  puzzle to defeat, not a framework to live inside.

### C6. Tone for kid screens

- Not infantilizing. No "Oh no! It's bedtime, sleepyhead!"
- Not authoritarian. No "ACCESS DENIED."
- Matter-of-fact. "Roblox is paused until 4pm" with a tap target for
  more detail.
- Researched against Common Sense Media kid-respect guidelines and
  Ramotion's kid-UX corpus.

### C7. Tone for parent screens

- Calm and factual. No marketing-speak. No "Take control of your child's
  screen time!"
- Show data, let the parent decide. Defaults are safe but never invisible:
  every rule the user has not explicitly set shows a "default" tag.
- The parent should never feel sold to. They should feel informed.

### C8. Settings minimalism

- Three top-level settings only: **Family**, **Devices**, **Rules**.
- Default presets cover 80% of cases: "School mode," "Family time mode,"
  "Bedtime mode." Most parents will only ever pick from these.
- Power-user toggles live under "Advanced" inside each section, never on
  the main flow.

### C9. Empty states

- Fresh install: a welcome card and a setup wizard, in that order.
- No paired child yet: a single "Pair Oliver's phone" CTA, nothing else.
- No recent activity: "Quiet today." Not "No data available."
- No pending requests: "No requests waiting." Not a sad-cloud icon.

### C10. Accessibility

- TalkBack (Android) and VoiceOver (iOS) fully supported, including the
  kid-side screens. Pictograms have descriptive labels.
- High-contrast theme available for the kid screen, with a 7:1 contrast
  ratio on all reason text.
- Tap targets are 48dp minimum (Material 3 guideline), 56dp for primary
  actions on kid screens.
- Color is never the only signal. Every state pairs color with a
  pictogram and a text label, so colorblind kids and low-vision parents
  get the same information.

---

## References

- Apple Human Interface Guidelines, parental controls and Family Sharing
  sections.
- Material 3 design system, especially elevation and tap-target
  guidelines.
- Common Sense Media UX-for-kids principles.
- Ramotion UX-for-kids article series.
- Family Link UX studies (public usability reports, 2022-2024).
- Briar pairing UX research, for the QR-pair flow.
- Internal: `ATTACKS.md`, `DEFENSES.md`, `STORE_AND_FORWARD.md`,
  `PROTOCOL.md`, `openwarden-redteam-kids.md`.

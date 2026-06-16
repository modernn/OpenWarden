# Notifications

> **Audience:** Android child-app engineers, KMP parent-app engineers,
> reviewers of any PR that posts a notification.
>
> **Companion docs:** [`UX_PATTERNS.md`](UX_PATTERNS.md) §A and §C set the
> tone rules this doc inherits. [`KID_TRANSPARENCY.md`](KID_TRANSPARENCY.md)
> defines what the kid is allowed to learn from a notification.
> [`DESIGN_PARADIGMS.md`](DESIGN_PARADIGMS.md) §1 has the PR-blocking copy
> rules. Alert thresholds referenced below come from the graduated-
> privileges system in [`FAMILY_MODEL.md`](FAMILY_MODEL.md) and the
> crisis-class signals in [`LOCAL_AI.md`](LOCAL_AI.md).

Notifications are the most visible surface OpenWarden has between sync
boundaries. A bad notification taxonomy turns OpenWarden into the thing
parents mute and kids resent. This doc nails the taxonomy down before
the code gets written.

---

## 1. Kid-facing notifications (child Android)

The kid surface area is intentionally small. Five Android notification
channels, each created on first launch (`NotificationChannel`,
Android 8+):

- **OpenWarden Status** — required foreground-service notification.
- **Blocks** — "this app is paused" banners.
- **Requests & approvals** — outcomes of Ask-dad requests.
- **Trust-level changes** — quiet celebrations when the kid levels up.
- **AI flag alerts** — fired only when an on-device classifier returns
  high-confidence positive (`LOCAL_AI.md` §classifier-thresholds).

Representative posts:

- *Blocked app banner.* "Roblox is paused until 4pm. Ask dad? [Yes]"
  Dismissable, no sound, the [Yes] action opens the Ask-dad flow
  (UX_PATTERNS §A2).
- *Bedtime in 15 min.* "Bedtime in 15 minutes." One line. No countdown
  ticker — that's an anxiety surface, not a help.
- *Request approved.* "Dad said yes to Discord. 30 min, starting now."
- *AI checked a screenshot, all clear.* Low priority, mutable. Default
  off; parents opt-in per `KID_TRANSPARENCY.md` §6.
- *Trust level up.* "You're at Level 3. Dad sees less of your texts now."
  No confetti. The change is the reward.
- *FGS persistent.* "OpenWarden active — parental controls running." Ongoing,
  non-dismissable, required by Android FGS rules.

## 2. Kid tone

The PR-blocking phrases from `DESIGN_PARADIGMS.md` §1 apply double on
notifications, because notifications are read in public.

- Never punitive. "YOU'RE BANNED" gets the PR closed.
- Matter-of-fact. "Roblox paused until 4pm" — not "Time's up, buddy!"
- 1–2 lines max. Notification shade is not a place to argue.
- Pictogram in the small-icon slot; same set as the four reason
  pictograms in UX_PATTERNS §A1 (clock, lock, question mark, hourglass).
- No lock-padlock as the app small-icon — reads as punishment. Use the
  OpenWarden shield (`KID_TRANSPARENCY.md` §8).

## 3. Parent-facing notifications

Five parent channels, mirroring the structure on the kid side but with
different priorities:

- **Requests** (high) — actionable inline.
- **Safety alerts** (max) — crisis-class only.
- **Status updates** (low) — heartbeat, sync events.
- **Co-parent feed** (medium) — UX_PATTERNS §B Family Feed events.
- **Daily digest** (low, scheduled).

Examples:

- *Request.* "Oliver requested Discord. [Approve] [Deny]" — inline
  actions on Android, deep-link to dashboard on iOS v1.
- *AI flag.* "Oliver flagged unusual content. View?" Tap opens the
  redacted incident card; the parent never sees the raw image
  (`KID_TRANSPARENCY.md` §5).
- *Heartbeat silence.* "Oliver's phone has been silent for 6h." Threshold
  per `FAMILY_MODEL.md` heartbeat policy; escalates to safety class
  at 24h.
- *Daily digest available.* Posted Sunday 7pm local; opens the weekly
  review described in UX_PATTERNS §B4.
- *Bedtime started.* "Bedtime started for Oliver. Phone is asleep until
  7am."
- *Co-parent action.* "Mom just unblocked Twitter for Oliver." Tap →
  Family Feed entry with [Undo] within 24h.
- *Crisis.* "Oliver may be in danger." Full-screen intent + sound +
  bypasses DND. Fired only by the crisis-class signals enumerated in
  `LOCAL_AI.md` (predator-grooming pattern, self-harm content, sustained
  distress in classifier output).

## 4. iOS parent (open-the-app, v1)

iOS does not get push notifications in v1. The decision is documented
in `PROTOCOL.md` and ROADMAP.md and the user must see it during onboarding.

- Local notifications post on next foreground sync.
- `BGAppRefreshTask` runs opportunistically; iOS gives no guarantee.
- Onboarding copy: **"iOS alerts may show the next time you open
  OpenWarden, or up to 6 hours later. For real-time, use an Android
  parent device."**
- Crisis events still post locally on next open; iOS parents who care
  about crisis latency are routed to the Android parent app in
  onboarding.

v2 will use an ntfy doorbell or APNs once the trust model around
third-party push is settled.

## 5. Android parent

Android parents get the real thing.

- Local-notification channel per §3.
- Inline Approve/Deny actions on Request notifications. Action posts a
  signed command via `STORE_AND_FORWARD.md`.
- Tap → open the Family Feed entry, never a settings page.
- High-priority and Max-priority channels use heads-up display.

## 6. Severity tiers

A single enum, used both sides. Channel importance maps directly.

- **Crisis.** Full-screen intent, distinct sound, vibration pattern,
  bypasses DND. Only safety-class signals.
- **Urgent.** Heads-up, default sound. Requests waiting; phone offline
  > 24h.
- **Standard.** Quiet post, no heads-up. Rule changes, blocks, sync
  events.
- **Quiet.** Silent, no badge. Daily digest, audit log entries.

A tier is a property of the *event*, not the channel — but channels are
chosen so a parent muting "Status" never accidentally mutes a Crisis.

## 7. Snooze / mute

- Parent can mute or snooze any non-Crisis channel from the system
  notification settings. OpenWarden surfaces a shortcut in **Rules →
  Notifications** to land them there.
- Crisis cannot be muted by the user. The channel is created with
  `IMPORTANCE_HIGH` and the in-app settings exclude it from the snooze
  controls. This is a safety floor.
- Kid cannot snooze the FGS Status channel. Android wouldn't let us
  even if we wanted to.
- Per-parent prefs are stored in the parent's `PolicyDoc` slice
  (`PROTOCOL.md`); "mom prefers quiet" is a real per-actor preference,
  not a device setting.

## 8. Do-Not-Disturb interaction

- All non-Crisis channels honor system DND.
- Crisis channel sets `setBypassDnd(true)` and is registered as a
  priority channel. The first time a Crisis notification posts, an
  in-app coach mark explains: **"OpenWarden crisis alerts bypass Do Not
  Disturb. You can tune sensitivity in Rules → Safety."** It posts
  once, not every time.
- Sensitivity tuning is the only user-visible knob over crisis
  thresholds; the underlying confidence cutoffs live in `LOCAL_AI.md`.

## 9. Lockscreen visibility

Lockscreen is a privacy surface. Defaults:

- **Parent lockscreen.** Show the OpenWarden icon and "Tap to open." Never
  show request contents or the child's name. The child is identified
  only inside the app.
- **Kid lockscreen.** Show the admin message (set by the parent in
  Rules) and the emergency-dial tap target. No request contents — a
  shoulder-surfing classmate is the threat model.
- `setVisibility(VISIBILITY_PRIVATE)` on every channel by default. Parents
  who want richer lockscreen previews can opt in per-channel, and the
  opt-in dialog states what's exposed.

## 10. Sound design

- **Crisis:** distinct loud alert. Parent picks from a preset library of
  5–7 sounds during onboarding so muscle-memory recognition is reliable.
- **Urgent:** soft chime.
- **Standard:** subtle ping.
- **Quiet:** silent.
- **Kid blocked-app banner:** no sound. Audible block alerts shame the
  kid in front of peers; the visual is enough.

Vibration patterns mirror the same hierarchy; Crisis has a unique
pattern documented in accessibility (§15).

## 11. Notification batching

- Multiple Requests from the same child within 5 minutes collapse into
  one summary notification: "Oliver has 3 pending requests." Inline
  actions disabled in the collapsed form — tap to expand.
- Multiple sync / status events digest into one "5 events synced" line.
- Crisis is **never** batched. Each crisis posts standalone, with full
  sound and full content.
- Co-parent feed events batch above 5 in a 10-minute window, with a
  "see all in Family Feed" affordance.

## 12. Persistent FGS notification (kid)

Android requires it; we make it boring.

- Channel: OpenWarden Status, IMPORTANCE_LOW.
- Title: "OpenWarden active."
- Body: "Parental controls running."
- Small icon: OpenWarden shield.
- No actions, no expanded content, non-dismissable.
- Tap → opens the kid app home (which is the "Why am I blocked?" /
  Transparency surface, depending on state).
- Long-press → opens the Transparency screen directly (per
  `KID_TRANSPARENCY.md` §2 entry points).

## 13. Notification opt-outs

- Every non-Crisis channel is muteable, per-parent, per-device.
- Crisis is not muteable — it is the explicit safety floor.
- Per-parent preferences sync via Family Feed so co-parents see "Mom
  set Status to silent on her phone."
- A "Notifications" tile inside **Rules** shows current per-channel
  state and a one-tap path to the system settings page.

## 14. Test plan

- Permission grant flow on Android 13+ (`POST_NOTIFICATIONS` runtime
  permission).
- Notification post + Approve/Deny actions, including the offline
  queueing path through `STORE_AND_FORWARD.md`.
- DND bypass: Crisis posts during system DND; non-Crisis does not.
- Channel mute: muting Status hides Status posts but Crisis still
  arrives.
- Crisis priority on Pixel and one Samsung device (OEM channel-importance
  quirks).
- Lockscreen visibility default (PRIVATE) renders no PII.
- FGS persistent notification survives reboot, recents-swipe, and
  battery optimizer aggressions.
- iOS local-notification on next foreground after a queued event.

## 15. Accessibility

- TalkBack reads every notification, including the small-icon content
  description.
- Crisis is multimodal by default: haptic pattern + text + sound so
  hearing-impaired parents still get the alert.
- Pictogram-only payloads always carry a text fallback in the
  `contentText` so screen readers have something to read.
- Tap targets on inline actions ≥ 48dp.
- Per `UX_PATTERNS.md` §C10, color is never the only signal — Crisis is
  red **and** the icon is the Crisis shield **and** the body text says
  "may be in danger."

## 16. Internationalization

- All notification strings live in `notifications_strings.xml` (Android)
  and a shared KMP `Strings` object for the parent app. Loaded by the
  process described in `I18N.md`.
- Time formats locale-aware. "Bedtime in 15 min" becomes
  "Bedtime in 15 minutes" in en-US and is fully rewritten in es-ES, not
  string-concatenated.
- RTL layouts honored; inline actions reorder.

## 17. Privacy

- Default lockscreen visibility PRIVATE (see §9).
- Notification body never includes the kid's full real name; the
  configured display label ("Oliver") only.
- Photo-classifier alerts never include the image. The notification
  says "Oliver flagged unusual content. View?" The redacted incident
  card lives inside the app behind device auth.
- A "privacy mode" device setting collapses every OpenWarden notification
  to just "OpenWarden notification" on lockscreen. Tap-to-expand requires
  device unlock.

## 18. References

- Android Notifications guide (channels, importance, FGS, DND bypass).
- Material 3 notification patterns.
- Apple Human Interface Guidelines — notifications and Focus.
- WCAG 2.2 — accessible alerts and multimodal signaling.
- Internal: `UX_PATTERNS.md`, `KID_TRANSPARENCY.md`,
  `DESIGN_PARADIGMS.md`, `FAMILY_MODEL.md`, `LOCAL_AI.md`,
  `STORE_AND_FORWARD.md`, `PROTOCOL.md`, `I18N.md`.

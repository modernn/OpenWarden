# OpenWarden — Graduated Privileges, Trust Levels, and the Snitch Slider

> **Status:** Design proposal. Not yet on the roadmap. This sits firmly in
> Tier 2 territory per [`SIMPLIFY.md`](openwarden/docs/SIMPLIFY.md) — it is
> not v1, and the doc is honest about why. The point of writing it now is
> to lock the **data model** and the **defaults table** before they
> calcify, so when v2/v3 picks this up there is a single artifact to argue
> against.
>
> **Companion docs:** [`UX_PATTERNS.md`](openwarden/docs/UX_PATTERNS.md),
> [`ATTACKS.md`](openwarden/docs/ATTACKS.md),
> [`DEFENSES.md`](openwarden/docs/DEFENSES.md),
> [`LOCAL_AI.md`](openwarden/docs/LOCAL_AI.md),
> [`ONBOARDING.md`](openwarden/docs/ONBOARDING.md).
>
> **One-sentence summary:** every restriction in OpenWarden carries `min_age`
> and `recommended_age`; every kid carries a parent-set `trust_level`
> (1–5); every category of visibility is a slider, not an on/off; and
> "super concerning" alerts are a hard-coded set that fires regardless of
> trust level.

---

## 0. Why this exists

The original OpenWarden model is a snapshot. Parent sets rules, parent
adjusts rules, kid lives inside them. There is no first-class concept
of "the kid is older now" or "the kid has earned more rope" beyond a
parent manually re-doing the policy.

That is fine for a 10-year-old over a six-month window. It is wrong
for a 10-year-old who becomes a 13-year-old who becomes a 16-year-old
on the same device. Two failure modes appear:

1. **The rules ossify.** Parent never gets around to loosening. Kid
   carries 8-year-old's allowlist into early teens. Resentment grows.
   Pew finds 68% of parents think 12 is the right age for a first
   smartphone, but the *rules* on that phone almost never get
   re-thought once set.
2. **The snitch problem.** Parent who saw "every URL Oliver hit" at
   age 10 keeps seeing every URL Oliver hits at age 14. That isn't
   parenting, that's surveillance. Common Sense Media's age-stage
   guidance and Bark's own age-tier copy (8–10: strong guardrails;
   11–14: monitor; 15–18: light safety net) explicitly call for
   *less* visibility as kids age.

This design adds three primitives to handle both: **age metadata on
rules**, **trust level on the kid**, and **a per-category visibility
slider on the parent's view**.

---

## 1. Privilege graduation model

### 1.1 Industry framing

Four bodies of work inform the age tiers we pick:

- **Common Sense Media** rates media in seven age bins (0–2, 2–4, 5–7,
  8–9, 10–12, 13–14, 15–17). For OpenWarden's 9–12 target audience, the
  three relevant bins are 8–9, 10–12, 13–14, and the boundary at 12–13
  is the one the system has to handle gracefully.
- **AAP Family Media Plan** explicitly drops the old "X hours per day"
  framing in favor of household rules tuned per child. AAP's 5 Cs
  (Child, Content, Calm, Crowding Out, Communication) imply the rule
  set should evolve as the Child changes.
- **Pew Research** (Oct 2025): 68% of parents say 12+ is the right age
  for a first smartphone; 67% of 9–11s already have one. The mismatch
  between the "right" age and the "real" age is the population OpenWarden
  is for.
- **Bark / Pinwheel age presets.** Bark uses 8–10 / 11–14 / 15–18.
  Pinwheel uses 8–10 ("safety first"), 11–13 ("social training"),
  14–17 ("training wheels off"). Family Link splits at 13. None expose
  *trust*, only *age*. That's the gap.

OpenWarden collapses to **four life stages** in the data model — fewer
moving parts, easier defaults, less UI for the parent:

| Stage | Ages | Vibe |
|---|---|---|
| **S1** | 5–7 | Heavy guardrails, almost no autonomy. OpenWarden not really for this age. |
| **S2** | 8–10 | Allowlist world. Default OpenWarden target. |
| **S3** | 11–13 | Allowlist + supervised social. Bark/Pinwheel's "social training." |
| **S4** | 14–17 | Light safety net. Approaching graduation. |

Stage is computed from `kid.birthday` and today's date. Stage advances
automatically. **Rule changes do not.** A stage transition fires a
parent prompt; the parent applies, edits, or skips.

### 1.2 Rule metadata

Every rule in the signed `PolicyDoc` gains four optional fields:

```
Rule {
    id:              uuid
    category:        AllowlistApp | TimeWindow | DnsCategory | …
    payload:         …
    // graduation metadata
    min_age:         u8?         // never auto-suggested below this
    recommended_age: u8?         // suggested at this age, not before
    parent_override: bool        // parent edited the suggestion;
                                 // future age prompts skip this rule
    set_by:          ParentId
    set_at:          Timestamp
}
```

`min_age` is a *hard* floor (OpenWarden will refuse to auto-apply a
"Snapchat unlocked" rule below age 13 even if the parent clicks
"apply all suggested"; parent must opt in manually). `recommended_age`
is the soft tier. `parent_override = true` means the parent has made an
explicit decision on this rule and birthday prompts should leave it
alone — graduation is *suggestions for the rules the parent hasn't
already opinion-ed on*.

### 1.3 Birthday flow

- **T-7 days from birthday:** parent app notification. "Oliver turns
  11 next week. Review suggested rule changes when you have 5 min."
  Tap opens the **Birthday Review** screen.
- **Birthday Review screen** shows three columns:
  - **Currently:** active rule.
  - **Suggested at age 11:** new rule from defaults table.
  - **Change?** toggle.
  - Reasoning copy per row, one line. Example: "Most 11-year-olds
    can self-manage bedtime ±15 min. Suggesting bedtime window
    widens from 8:30 to 8:45."
- One primary button: **"Apply selected"** (default: all toggles on
  except `min_age` violations).
- One secondary button: **"Skip — review later."** Re-prompts in 7d.
- On the kid device, at midnight on the birthday: a single screen.
  "Happy birthday. Some things changed: [list]. From dad."
  Customizable note from parent. No gamification copy, no balloons —
  matter-of-fact per UX_PATTERNS §C5/C7.

### 1.4 Stage-transition flow (12 → 13, etc.)

Same as birthday flow but more rules in scope. Parent is shown a
**Stage Transition Brief**: "Oliver is now in Stage 3 (11–13).
Common changes: messaging apps open up, web filter loosens from
'kid-safe' to 'teen', call-screen events drop from 'every call' to
'unknown numbers only.'" Defaults pre-loaded from §3 table.

---

## 2. Trust level — the parent-set economy

Age is one axis; **behavior** is the other. A 12-year-old who
repeatedly tries to bypass should not auto-graduate to looser rules
just because of a birthday. A 10-year-old who has been impeccably
behaved for a year shouldn't have to wait for an arbitrary date.

### 2.1 Five tiers

| Tier | Name | Allowlist | Time windows | Parent visibility |
|---|---|---|---|---|
| **L1** | Tight | Allowlist only | Narrow | Full (every event) |
| **L2** | Standard (default) | Allowlist + bedtime | Standard | Mixed: full on flags, aggregate on routine |
| **L3** | Earning | Allowlist relaxes | Wider | Mostly aggregate |
| **L4** | Trusted | Mostly open w/ category blocks | Loose | Flag-only |
| **L5** | Independent | Effectively unrestricted | None | Emergency-only |

L5 is the **graduation lobby**: kid is mostly self-managed, parent
gets only the irreducible safety flags. Reaching L5 is also the
signal that the family is ready to run the §9 decommission flow.

### 2.2 Earning paths

Two paths, both parent-controlled:

- **Manual grant.** Parent slides the trust level up. The slider
  requires a one-line reason ("Good behavior at camp, earned it") that
  shows on the co-parent Family Feed. Co-parent sees, can comment, can
  undo within 24h per UX_PATTERNS §B.
- **Auto-earn (optional, off by default).** Per-kid setting:
  "Raise trust level every N days without bypass attempts or AI flags."
  Defaults: 90 days. Auto-earn never crosses the L4→L5 boundary —
  graduation is always a manual conversation.

What counts as a "bypass attempt" or "AI flag" for auto-earn purposes
is logged transparently in the kid-visible audit log (§12). No
shadow-counting.

### 2.3 Trust level lives in the PolicyDoc

```
PolicyDoc {
    ...existing fields (policy_seq, not_before, not_after, signature)
    trust_level:        u8      // 1..=5
    trust_set_by:       ParentId
    trust_set_at:       Timestamp
    trust_reason:       String  // freeform, shown on co-parent feed
    auto_earn:          AutoEarnConfig?
}
```

Trust is *part of the signed bundle*. The kid device cannot change
its own trust level; only a signed update from a paired parent can.
Replay protection (`policy_seq` monotonic) prevents a kid from
re-applying an older "L5" bundle they may have observed in flight.

### 2.4 Why not pure age-based?

Bark's age-only tiers and Pinwheel's age-only tiers both run into the
same wall: kids of the same age have wildly different maturity.
Common Sense Media is explicit about this — their reviewer notes
say "reading levels at 9–12 vary widely." Trust as a second axis
avoids forcing every 12-year-old into the same bucket.

### 2.5 Why not pure trust-based?

Because age sets the floor. A perfectly-behaved 8-year-old still
should not have Snapchat. `min_age` on the rule beats `trust_level`
on the kid.

---

## 3. Configurable visibility — the snitch slider

The single most under-considered axis in commercial parental
controls. Every commercial product treats visibility as binary
(monitor / don't), and the only knob is "how often do I get
notifications." That's wrong. **Visibility should be a per-category
slider with four positions**, and the slider's default depends on the
kid's trust level.

### 3.1 Four positions

| Position | What parent sees |
|---|---|
| **Full** | Every event. App launches, DNS hits, call-screen events, contacts added, installs attempted. |
| **Aggregate** | Daily summaries. "Oliver used Discord 47 min today. Top 3 contacts: …" |
| **Flag-only** | Only events the local AI or heuristics flag as concerning. Routine activity invisible. |
| **Emergency-only** | Only the §4 hard-coded super-concerning list. |

These are **stops on a slider**, not modes. The slider is per
category. Each category has its own slider in the parent app, under
**Settings → Oliver → Visibility**.

### 3.2 Categories

| Category | Examples |
|---|---|
| App usage | Launches, duration, foreground/background |
| Web browsing | DNS hits (NOT page contents, NOT search query strings) |
| Calls | Call-screen events, unknown-number ring patterns |
| Messages | "Oliver texted Mom" metadata only — NEVER contents |
| Location | Geofence enter/exit, school-hours anomaly |
| Installs | Attempt, approval, completion |
| Contacts | New contact added, contact deleted |

Seven categories, no more. Adding more bloats the settings screen and
violates [`UX_PATTERNS.md`](openwarden/docs/UX_PATTERNS.md) §C8.

### 3.3 Defaults by trust level

This is the heart of the proposal. The defaults table:

| Category | L1 | L2 | L3 | L4 | L5 |
|---|---|---|---|---|---|
| App usage | Full | Full | Aggregate | Flag-only | Emergency |
| Web browsing | Full | Aggregate | Aggregate | Flag-only | Emergency |
| Calls | Full | Full | Aggregate | Flag-only | Emergency |
| Messages | Full (meta) | Full (meta) | Aggregate | Flag-only | Emergency |
| Location | Full | Full | Full | Aggregate | Emergency |
| Installs | Full | Full | Full | Full | Flag-only |
| Contacts | Full | Full | Aggregate | Flag-only | Emergency |

Notes:

- Location and Installs stay "Full" longer than other categories.
  Location is the irreducible safety signal — a parent of a 16-year-old
  may not want to read messages but does want to know the phone is at
  school. Installs are the lever for the entire bypass economy.
- The defaults are **suggestions**. The parent can move any slider
  independently. The "apply preset for L3" button restores the
  column.

### 3.4 The L3 contacts example (the specific user ask)

The user asked: "Can configurable contact visibility be a real thing?"
Yes. The contacts slider at L3 (Aggregate) shows:

> **Oliver's top contacts this week:** Mom (38 msgs), Jacob R (24),
> Riley S (19), Grandma (8), +2 others.
>
> **New contacts added this week:** 1 (Riley S — first message Tue
> 3:14pm).

No message contents. No "what did Oliver say to Jacob." Just the
shape of the social graph and any new entries to it. At L4, the same
view collapses to: "1 new contact this week. No flags." At L5: nothing
unless a flagged contact (unknown number, repeat unknown SMS, known
predator pattern) appears.

---

## 4. "Super concerning" alerts — the always-on floor

Regardless of trust level, regardless of slider position, **these
fire**. They are the contract OpenWarden makes with the parent: even at
L5 emergency-only, these events surface immediately.

### 4.1 Safety flags (require local AI — Tier 2 per LOCAL_AI.md)

- **Explicit imagery detected** at confidence > 0.95 (NSFW classifier
  on opted-in apps only). Threshold tuned to the high end because
  open NSFW models — per the UnsafeBench 2024 benchmark — run
  0.65–0.78 raw accuracy on real-world data. 0.95+ cuts FP to
  liveable rates at the cost of FN; the §5 policy below mitigates.
- **Self-harm signal** in kid's outgoing messages (Gemma Nano text
  classifier), when enabled at onboarding.
- **Predatory contact pattern** in kid's incoming messages (same
  classifier).
- **Geofence "left school during school hours."**
- **Phone offline >6h during school hours.**
- **Unknown number repeated calling** (≥ 3 calls / 24h, not in
  contacts).
- **Sudden out-of-hours activity** (screen on between 1am–5am when
  baseline says they're never up then).

### 4.2 Tamper flags (always on, no AI required)

- **Bypass attempt detected.** Safe Mode reboot, USB debug toggle,
  factory-reset attempt, Settings → Apps → OpenWarden spelunking past
  what's normal.
- **Policy bundle expired** (`not_after` reached without a fresh
  bundle). Implies parent device hasn't synced for the bundle's TTL.
- **Heartbeat silence > 24h.** Per DEFENSES #8 escalation ladder.
- **Factory reset attempted** (FRP block triggered).
- **Pubkey rotation attempted.** Should never happen without recovery
  phrase; if it does, alert hard.

### 4.3 Why this list specifically

These map 1:1 to the catastrophic failure modes:
**physical danger to the kid** (left school, out-of-hours, predator
contact, self-harm), **content exposure** (NSFW), and
**system compromise** (tamper). Everything else can be aggregated,
flagged, or suppressed by the parent's slider. These cannot.

The list is hard-coded in the shared module, not in PolicyDoc. The
kid cannot turn them off via any policy manipulation; only a code
change to OpenWarden can. That's intentional.

---

## 5. AI alert thresholds — fighting the false-positive problem

The 2024 UnsafeBench benchmarking found mainstream open NSFW
classifiers run 65–78% real-world accuracy. NSFW.js's published
training-set numbers are higher (~90%) but degrade meaningfully on
real-world distributions. Naive "alert on every detection" produces
4–15 false-positive alerts per kid per week. Parents stop reading
alerts inside two weeks. The whole AI tier becomes noise.

### 5.1 Three-tier alert policy

| Confidence | Severity | Action |
|---|---|---|
| > 0.95 | High | **Alert immediately.** Both parents. Cannot suppress. |
| 0.85–0.95 | Medium | **Alert next morning (8am).** Batched with other medium events. |
| 0.70–0.85 | Low | **Log only.** Visible in classifier audit log (§12). Triggers a behavioral flag at 3+/24h. |
| < 0.70 | Below floor | **Discard.** Not logged. |

### 5.2 Category-specific overrides

- **Predator/grooming pattern:** alert immediately at > 0.80. The
  asymmetry (cost of FN >> cost of FP) justifies the lower threshold.
- **Self-harm signal:** alert immediately at > 0.75 *and* batch within
  4 hours of detection, never overnight. The latency budget is
  different from NSFW.
- **NSFW low-confidence repeat:** 3 events in 24h, each 0.70–0.85,
  promote to one combined medium alert.

### 5.3 Audit on every alert

Every alert in the parent app shows:

- Classifier name + version (e.g. `falcon-nsfw 1.2`).
- Threshold that fired.
- Timestamp.
- App context (which app was foregrounded; never the content).
- "Recalibrate" link → bumps confidence threshold for that kid +
  category up by 0.05.

This is the audit story from LOCAL_AI.md §"Auditability" extended to
each alert. The parent who gets two false positives in a week clicks
"Recalibrate" twice and the noise stops.

---

## 6. Privacy-by-default — the things OpenWarden will never see

Even when every slider is at **Full**, even at L1 tight, OpenWarden does
not see, store, or transmit:

- **Message contents.** Not over SMS, not in any social app, not in
  iMessage. Metadata only: who, when, which app, length-bucket.
- **Search query strings.** DNS sees the domain hit
  (`google.com`); OpenWarden does not see `?q=…`.
- **Game-internal actions.** "Oliver opened Roblox" yes; "Oliver
  joined the Brookhaven server and said X" no.
- **Keystrokes.** Never, per LOCAL_AI.md.
- **Screen recordings.** Never.
- **Continuous audio.** Never.

The local AI tier sees content briefly **on-device**, classifies it,
emits a flag, and the content is dropped. The parent receives the
flag; the content is gone. This is the difference between *control*
software and *stalkerware*, and the line is named explicitly in
SIMPLIFY.md Tier 3.

All event log entries are **sealed-box encrypted to the parent
pubkey** (DEFENSES Pattern B). Even the kid with root sees only
ciphertext.

---

## 7. Trust-level UI

### 7.1 Parent app

Single screen: **Settings → Oliver → Trust Level.**

- Top: **"Oliver's trust level"** with the current tier in a large
  numeral and a one-word label ("Standard").
- Slider, 1–5, with tier labels under each stop. Drag releases require
  a reason (single-line text box).
- Below slider: "**Last raised:** Aug 12 by Dad. *'Consistent good
  behavior this summer.'*"
- **Auto-earn** toggle, off by default. When on: "Raises level every
  90d without bypass attempts or AI flags. Won't cross L4→L5."
- **Apply preset for: age 10 / L2 Standard** — restores defaults for
  this combination. Used when the parent has tweaked many sliders and
  wants a baseline.
- Co-parent visibility: every trust-level change broadcasts to the
  Family Feed with the reason. Per UX_PATTERNS §B.

### 7.2 Kid app

Single screen, reachable from the "Why am I blocked?" screen.

- "Your trust level: **2 of 5**."
- Below: short, factual list. "**To reach level 3:**"
  - "No bypass attempts for 30 more days (you have 12)."
  - "Or: ask dad to raise it manually."
- No XP bars. No badges. No "Level Up!" copy. The kid sees the
  system honestly per UX_PATTERNS §C5; this is the
  anti-gamification version of gamification.

This is *not* a Tier 3 "reward systems / gamification" feature in the
SIMPLIFY.md sense. The line: OpenWarden doesn't give XP for chores or
turn good behavior into a points economy. It shows the kid the trust
level the parent has set, and the conditions for the next one. The
kid can verify the system. That's transparency, not gamification.

---

## 8. Birthday flow (compressed spec)

1. **T-7d:** parent app pushes notification. "Oliver turns 11 next
   week."
2. **T-7d to T-1d:** Birthday Review screen accessible from parent
   home tab. Parent can review at leisure.
3. **T (midnight local):** rules applied if parent confirmed; rules
   queued if not (kid sees old policy + a "your rules will change
   when dad reviews them" line on next launch).
4. **T (kid's morning):** kid sees the Birthday Greeting screen on
   first phone unlock. "Happy birthday. Some things changed: …
   — from dad."
5. **T+1d:** parent gets a follow-up summary. "Oliver's birthday
   rule changes are live."

If parent has not reviewed by T+3d, the prompt re-fires daily until
addressed. After T+14d unaddressed, kid's policy remains on the
old rules; no auto-application happens.

---

## 9. Graduation off OpenWarden

Around age 16 or 17 (parent-set, default 17), the parent app shows a
**Graduation Brief**:

> "Oliver turns 17 next month. Most families graduate kids off
> OpenWarden between 16 and 18. Ready?"

Graduation = decommission. Per UX_PATTERNS §C4 + DEFENSES #15:

1. Parent confirms in the parent app, types the recovery phrase.
2. A signed graduation command is queued.
3. **7-day delay.** During the delay, the kid sees a banner: "Your
   phone graduates from OpenWarden on [date]." Either parent can cancel
   the graduation during this window from the Family Feed.
4. At T+7d, the DPC removes itself per Android's DPC-off-boarding
   procedure. Phone becomes a normal Android device.
5. The recovery phrase survives. Parent retains an offline audit
   archive (encrypted dump of the event log, opened only with the
   phrase) for custody / memorabilia / "what apps did Oliver use in
   high school" memory lane purposes.

Why 7d? Symmetric with the existing recovery-phrase delay
(UX_PATTERNS §C4). The kid cannot social-engineer "factory reset
right now"; the parent cannot impulsively decommission and regret it.

---

## 10. Contact visibility — the user's specific ask, detailed

Defaults by trust level (restating §3.3 row, expanded):

- **L1:** Parent sees the full contact list. Every new contact added
  fires a full event. Every call shows duration and direction. Every
  SMS shows recipient + length-bucket.
- **L2:** Parent sees contact list. New contacts fire an event but
  are batched into a daily summary. Calls and SMS metadata visible
  daily.
- **L3:** Parent sees **top 5 contacts this week** in aggregate.
  Unknown-number events surface. Contact additions surface only if
  the new contact is from an unknown source (not in parent's
  contacts, not in school directory).
- **L4:** Parent sees only flagged contacts. Definitions of flagged:
  - Unknown number with ≥ 3 calls in 7d.
  - Phone number matching a parent-loaded blocklist.
  - Contact whose name + interaction pattern matches the
    predator-pattern classifier (only if classifier enabled and
    confidence > 0.80).
- **L5:** Emergency-only. Predator classifier hits only.

Parent can override per slider independently of trust level. The
override is logged on the Family Feed so co-parent sees the change.

This is documented at the end of the parent's
[`ONBOARDING.md`](openwarden/docs/ONBOARDING.md) flow as a single
post-pairing screen: "How much do you want to see about Oliver's
contacts? You can always change this." Default is L2.

---

## 11. AI moderation aligned to trust level

Local AI runs the same models at all trust levels; what changes is
the **alert delivery**:

| Trust | NSFW image | Bullying text | Self-harm | Predator | Anomaly |
|---|---|---|---|---|---|
| L1 | All confidences | Enabled if parent opted in | Same | Same | Aggregate to parent daily |
| L2 | High + medium | Same | Same | Same | Daily |
| L3 | High only | Same | Same | Same | Weekly digest |
| L4 | High only, batched | Off unless parent re-enabled | Same | Same | Off |
| L5 | Off | Off | **Still on** | **Still on** | Off |

Self-harm and predator classifiers stay on through L5, off-by-default
to enable but on-by-default to *keep* once enabled. The
asymmetry is intentional: a 16-year-old has earned privacy from the
"saw a meme" classifier, not from the "someone is grooming them"
classifier.

All of the above is overridable per category by the parent. Defaults,
not policy.

---

## 12. Audit + transparency for the kid

Per LOCAL_AI.md §"Auditability" + UX_PATTERNS §C2: the kid can see
exactly what is monitored, at any time, from the kid app's
**"What does OpenWarden see?"** screen.

Layout:

- "Right now, dad sees:"
  - Apps: aggregate (a daily summary).
  - Web: flag-only (only flagged sites).
  - Contacts: aggregate (top 5 + new this week).
  - Location: full.
- "Dad does NOT see:"
  - What you type.
  - What you read on a website.
  - What you say in messages.
  - What you do inside a game.
- "AI checks running:" NSFW image (high-confidence only) on browser
  and Instagram.

Every classifier run is logged locally. The kid can pull
**"Classifier history (last 7d)"** and see "NSFW classifier ran 14
times, flagged 0 times." The point: defeat the "spy software"
complaint by showing the kid the system is bounded and inspectable.

This is the difference between Bark's posture (we see everything,
trust us) and OpenWarden's posture (we see this exact list; verify it
yourself). The kid app exposes the bounding.

---

## 13. Implementation notes

- `PolicyDoc.trust_level: u8` (1–5) — signed, monotonic with
  `policy_seq`.
- `PolicyDoc.visibility: HashMap<Category, Visibility>` where
  `Visibility ∈ { Full, Aggregate, FlagOnly, Emergency }`.
- `Rule` gains `min_age`, `recommended_age`, `parent_override`,
  `set_by`, `set_at` (all optional except `parent_override`).
- Shared module: `defaults_for(trust_level: u8, kid_age: u8) ->
  PolicyDoc`. Pure function. Unit-tested heavily — this is where
  bugs would be most embarrassing.
- Birthday scheduler: parent app `AlarmManager` job, daily check
  against `kid.birthday`. Fires the Birthday Review notification at
  T-7d and the Stage Transition Brief at stage boundaries (8, 11,
  14).
- AI alert routing: shared `AlertSink` trait, with delivery policy
  parameterized by `(trust_level, category, confidence)`. Single
  point of policy.

Estimated build cost per SIMPLIFY.md §5 labels: **Build M, Maintain
M.** The maintenance burden is the defaults table (which will need
revision every couple years) and the AI-threshold tuning (which will
need revision every model update). Both are bounded and visible.

---

## 14. Trade-offs, honestly

- **More trust = less surveillance = less defensive coverage.** A
  parent at L4/L5 sees less of their kid's life. If something goes
  wrong, it surfaces later. The L4/L5 parent has to be okay with
  that. The UI for raising the slider should include the trade-off
  copy: "At Level 4, you'll see only flags. If Oliver gets in trouble,
  you'll find out from the AI or from him, not from your dashboard."
- **Rigid age-only systems alienate teens, exhaustive systems
  alienate everyone.** This design picks the middle path: age sets
  the floor, parent picks the ceiling, defaults move in the right
  direction without forcing the change.
- **The auto-earn feature could be gamed.** "30 days without bypass
  attempts" assumes OpenWarden detects all bypass attempts. ATTACKS.md
  documents several it does not (in-app WebView, friend's phone).
  Auto-earn is off by default for this reason.
- **The slider creates a new attack surface for K1.** "Dad, can you
  put my web browsing on flag-only?" is now a thing kids can ask
  for. Mitigation: every visibility change is in the Family Feed,
  co-parent sees, can undo. Same defense as every other K1 attack.
- **The "super concerning" list is a permanent commitment.**
  Removing an item from §4 in a future version will feel like
  removing a guarantee. Add items rather than remove.

---

## 15. References

- **Common Sense Media** — age-stage rating methodology, ages
  5–7 / 8–9 / 10–12 / 13–14 / 15–17 bands.
  https://www.commonsensemedia.org/about-us/our-mission/about-our-ratings/10-12
- **AAP Family Media Plan** — 5 Cs framework, evidence-based screen
  guidance that drops the "X hours" framing in favor of household
  rules per child. https://www.aap.org/en/patient-care/media-and-children/
- **Pew Research** (Oct 2025) — "How Parents Manage Screen Time
  for Kids," 68%/12+ smartphone finding.
  https://www.pewresearch.org/internet/2025/10/08/how-parents-manage-screen-time-for-kids/
- **Bark** — 8–10 / 11–14 / 15–18 age tier copy.
  https://www.bark.us/
- **Pinwheel** — caregiver portal + "safety first / social training /
  training wheels off" stage framing. https://www.pinwheel.com/
- **UnsafeBench** (2024 ACM FAccT) — open NSFW classifier real-world
  accuracy benchmarks (NSFW_Detector 0.78, NudeNet 0.65 on sexual
  content). https://arxiv.org/pdf/2405.03486
- **Google Family Link** — age tiering at 13.
- Internal: [`UX_PATTERNS.md`](openwarden/docs/UX_PATTERNS.md),
  [`ATTACKS.md`](openwarden/docs/ATTACKS.md),
  [`DEFENSES.md`](openwarden/docs/DEFENSES.md),
  [`LOCAL_AI.md`](openwarden/docs/LOCAL_AI.md),
  [`ONBOARDING.md`](openwarden/docs/ONBOARDING.md),
  [`SIMPLIFY.md`](openwarden/docs/SIMPLIFY.md).
</content>
</invoke>
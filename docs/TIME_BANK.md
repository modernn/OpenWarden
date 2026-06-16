# Time-Bank / Chore-Economy Design

> **Audience:** maintainers planning the v1 data model with v3 features in
> mind; parents and contributors evaluating whether a credit economy
> belongs in OpenWarden at all.
>
> **Status:** **v3+ territory** per [`SIMPLIFY.md`](SIMPLIFY.md) Tier 2.
> This doc exists *now* so the v1 `PolicyDoc` data model accommodates
> earned-time credits without a painful migration later. We are not
> building UI for this in v1, v1.x, or v2.
>
> **Tension with SIMPLIFY §4.** SIMPLIFY currently lists "reward systems /
> gamification" under "things to defer indefinitely," with the rationale
> "OpenWarden is plumbing, not parenting advice." This doc proposes a narrow,
> opt-in, parent-driven *credit* primitive (not gamification) that is
> consistent with that pledge: no XP, no streaks, no avatars, no badges.
> If we ship it in v3, SIMPLIFY §4 must be amended via ADR. If the ADR
> fails, we still benefit from having shaped the data model defensively.

---

## 1. Concept

A kid earns **time-credits** for verifiable real-world actions: chores,
homework, reading, school performance. A parent issues a signed
`EarnedCredit` token. The kid later redeems credits via a signed
`RedeemedCredit` command, and OpenWarden temporarily relaxes the active
policy (a specific app, a window of time, or general allowance) until the
credit expires.

Credits are **not** money, not XP, not a high score. They are an
explicit, parent-mediated exception to the standing policy, accounted
for in the family feed.

---

## 2. Anti-patterns we explicitly reject

- **Surveillance-based earning.** "Kid watched a parent-approved video
  for 10 minutes → earns 5 minutes of YouTube." This is gamifying
  compliance with the surveillance apparatus. It teaches the kid that
  being watched is the unit of value. Out.
- **Auto-earning without parent action.** Any "the device noticed you
  did X, here's a credit" path is trivially gamed and erodes parental
  judgment. Bonus auto-earning is *only* via opt-in third-party
  integrations (§5), and even those are deferred past v4.
- **Negative scores (debt).** Credits cannot go below zero. A "you owe
  me 30 minutes" shame economy is punitive, not motivating, and
  contradicts the [`UX_PATTERNS.md`](UX_PATTERNS.md) tone pledge
  ("respectful, never punitive").
- **Sibling lending.** Credits are not transferable. v1 is one-kid
  anyway ([`SIMPLIFY.md`](SIMPLIFY.md) §3), so there is no migration
  path that needs this.
- **Convertibility to material rewards.** OpenWarden does not know or
  care what a credit is "worth" in dollars or candy. Parents make
  those calls outside the app.

---

## 3. Token model

All credits are signed end-to-end with the parent's Ed25519 key, the
same key used for [`PROTOCOL.md`](PROTOCOL.md) policy bundles. No new
crypto.

```
EarnedCredit {
  credit_id:     uuid (v4, generated parent-side)
  family_id:     uuid
  kid_id:        uuid (v1: single kid, but typed for v3 expansion)
  amount_min:    uint16 (1..480, hard cap at 8 hours)
  category:      AnyApp
                 | App { pkg: string }
                 | Window { start_ts, end_ts }
  reason:        string (optional, <=120 chars, parent-entered)
  issued_by:     parent_id
  issued_at:     timestamp
  expires_at:    timestamp (default issued_at + 7d, max +30d)
  credit_seq:    uint64 (per-family monotonic)
  nonce:         bytes[16]
  sig:           Ed25519 over canonical CBOR of above
}

RedeemedCredit {
  credit_id:     uuid (refers to an EarnedCredit)
  redeemed_at:   timestamp
  applied_until: timestamp (redeemed_at + amount_min)
  device_id:     kid device that performed redemption
  sig:           Ed25519 (kid device signs with its device key)
}
```

The kid device holds an **append-only credit ledger**: every
`EarnedCredit` and every `RedeemedCredit` is logged and replicated to the
parent app via the existing family-feed transport. Reconciliation is the
same machinery used for policy bundles.

---

## 4. Earning sources (parent-issued only)

In the v3 ship, *all* credits originate from a deliberate parent tap.
Example presets:

- "Did math homework" → 15 min
- "Read for 30 min" → 20 min
- "Helped with dishes" → 10 min
- "Got an A on a quiz" → 30 min

Amounts and labels are editable. The parent enters a reason or picks a
preset chip; the parent app signs and ships.

---

## 5. Bonus auto-earning (opt-in, deferred to v4+)

A future hook may allow trusted third-party apps (e.g. Epic for kids'
reading) to *propose* a credit on completion of an activity. The
proposal arrives in the parent app as a pending request; the parent
still taps to sign.

This is **not** auto-credit. It is auto-*proposal*. Parents stay in the
loop. The corruption-of-motivation literature on extrinsic rewards (see
§16) is the reason for this conservatism: making a kid's reading
contingent on screen time is a known way to reduce the kid's
intrinsic interest in reading.

We will not ship this before v4 and we may never ship it. The §3 data
model accommodates it without change.

---

## 6. Redemption UX

Kid-side, one screen, matching [`UX_PATTERNS.md`](UX_PATTERNS.md) §A
tone:

- Header: "You have **45 min** of credit."
- List of available credits, oldest first, with category and expiry.
- Pick one. Pick the app or accept "any app."
- Confirm sheet: "Spend 15 min credit on Roblox until 4:15pm?"
- Single tap. OpenWarden applies a temporary policy override, scoped to
  the credit's category, and schedules an auto-revert at
  `applied_until`.

No celebration animation. No coin-jingle. The confirmation line is
factual: "Roblox unlocked until 4:15pm. 30 min credit remaining."

---

## 7. Anti-cheat

- **Forgery.** Credits are signed by the parent's Ed25519. The kid
  device cannot mint credits. Same threat model as policy bundles.
- **Replay.** `credit_seq` is per-family monotonic; the kid ledger
  rejects any `EarnedCredit` whose `(family_id, credit_seq)` it has
  already seen. The `nonce` defends against same-`seq` collisions
  under multi-parent issuance races.
- **Double-spend.** Each `credit_id` may appear at most once in the
  `RedeemedCredit` log. The kid device enforces this locally; the
  parent app cross-checks on sync.
- **Clock tampering.** `applied_until` is enforced by monotonic clock
  (`elapsedRealtime`) in addition to wall clock. Rolling the system
  clock back does not extend a redemption. Same defense as bedtime
  ([`UX_PATTERNS.md`](UX_PATTERNS.md) §A6).
- **Tamper alert.** If the parent app's view of available balance
  disagrees with the kid device's view by more than one in-flight
  credit, a Family Feed entry flags the divergence for parent review.
- **Expiry.** Credits past `expires_at` are unredeemable. Default 7
  days, parent override up to 30. We are explicit about expiry to
  prevent hoarding behavior (kid stockpiles 40 hours, then disappears
  for a weekend).

---

## 8. UX surface

**Parent app:**

- A "Credit Oliver" action on the kid's profile.
- Preset chips: 5 / 10 / 15 / 30 min. Reason field below.
- Category selector: "Any app" (default) / specific app / window.
- One tap to sign and ship. Confirmation banner: "Issued 15 min."

**Kid app:**

- A persistent "Credits: 45 min" tile on the launcher overlay (only
  visible when balance > 0).
- Tap → redemption flow (§6).
- "When does this expire?" link expands ledger view.

**Family feed:**

- Every issuance and every redemption appears in the feed
  ([`UX_PATTERNS.md`](UX_PATTERNS.md) §B). Co-parent sees both sides.
- Format: "Dad credited Oliver 15 min for math homework." /
  "Oliver spent 15 min credit on Roblox."

---

## 9. Trust-level integration

OpenWarden's trust-level concept (referenced in `openwarden-graduated-privileges.md`
and inline in `DEFENSES.md`) gates how much autonomy the kid earns over
time. Time-bank slots into that:

- **L1 (baseline / new device).** Credits only via explicit parent
  tap. No auto-proposals. No third-party integrations.
- **L2.** Same as L1, plus the parent may enable a chore-checklist
  app that *proposes* credits the parent still taps to sign.
- **L3+.** Parent may pre-authorize specific rules: "any time Epic
  reports >20 min of reading, auto-issue a 10 min credit, capped at
  one per day." Even here, the rule itself was signed once by the
  parent; runtime issuance derives from that standing authorization.

Higher trust → more autonomy → fewer parent taps. Lower trust → more
deliberate parent gating. This matches the rest of the graduated
model.

---

## 10. Data model placement in PolicyDoc

To avoid v3 migration pain, the v1 `PolicyDoc` reserves:

- A `credit_policy` block (currently `{ enabled: false }`).
- A `credits` collection in the family event log, schema as §3.
- A `credit_seq` counter in family state, initialized to 0.

These cost ~30 lines of Kotlin and zero runtime behavior in v1. They
give us the migration freedom to enable credits in v3 by flipping
`enabled: true` and shipping UI, with no breaking change to the
policy-bundle wire format.

---

## 11. Expiry and balance management

- **Default expiry:** 7 days from `issued_at`.
- **Parent override:** up to 30 days. We do not allow indefinite
  expiry; stale credits are a UX hazard.
- **Balance visibility:** kid app shows total available minutes and
  per-credit breakdown. Parent app shows the same plus "12 min used
  this week."
- **No rollover beyond expiry.** If a credit expires unspent, it is
  gone. The kid app shows a small "expired" pictogram in the ledger
  view, no shame copy.

---

## 12. Anti-perversion guardrails

- Balance floor at zero. No debt, no negative scores.
- No transfer between kids (v1 is one kid; v3 may have multi-kid
  per a future SIMPLIFY revision, but credits remain non-transferable
  even then).
- No conversion to or from material rewards inside the app. If a
  parent privately tells the kid "10 credits = a candy bar," that is
  outside OpenWarden's surface.
- Earning surfaces default to "things the kid wants to do anyway"
  (reading, sports, music practice) rather than "suffering for
  minutes." This is a copy choice in the preset chips, not an
  enforced rule.

---

## 13. Tier classification

- **v1:** skip. The rules engine (allowlist + windows + lock-now)
  covers the use cases. Carve out data-model space only.
- **v2:** skip. Tier 0+1 stabilization period
  ([`SIMPLIFY.md`](SIMPLIFY.md) §2).
- **v3:** ship the parent-issued credit flow if and only if an ADR
  amends SIMPLIFY §4 and the maintainer team confirms capacity per
  the §9 "are we doing too much?" smell test.
- **v4+:** consider opt-in third-party auto-proposal hooks.

---

## 14. UX tone

The credit economy must never feel like an arcade. Every string is
calm and factual.

- "You earned 15 minutes for reading. Use them now?"
- "Roblox unlocked until 4:15pm."
- "30 min credit remaining."

Reject:

- "+15 MIN ACCESS UNLOCKED!"
- "Level up!"
- "Streak: 5 days!"
- Any badge, trophy, or progress-bar animation.

This is consistent with the broader OpenWarden tone pledge
([`UX_PATTERNS.md`](UX_PATTERNS.md) preamble).

---

## 15. Expected skip rate

Many families will never engage with credits. A parent who does not
consistently issue them will see the kid lose interest within a week,
and the feature will go dormant. **That is fine.** The feature is
opt-in (the `credit_policy.enabled` flag defaults to off, even in v3),
and a dormant feature with a zero-byte runtime cost is acceptable.

If issuance volume across the user base trends to zero over six
months post-ship, the sunset policy ([`SIMPLIFY.md`](SIMPLIFY.md) §8)
applies and we remove the surface.

---

## 16. References and prior art

- **iOS Screen Time "request more time".** Closest commercial
  analog. We diverge by making the unit of value an *earned* credit
  rather than a parent ad-hoc grant.
- **Habitica.** Heavy gamification (XP, avatars, party mechanics).
  We reject this style explicitly per §14.
- **Common Sense Media on rewards-based parenting.** Evidence is
  mixed; intrinsic-motivation studies (Deci & Ryan, Lepper) caution
  against linking enjoyable activities to extrinsic rewards. This is
  why §5 auto-proposals are deferred and why §12 keeps the surface
  narrow.
- **`UX_PATTERNS.md` §A2.** The "Ask dad" flow already covers ad-hoc
  exception requests. Time-bank is the *pre-authorized* variant of
  the same primitive, and the UX should feel like a sibling of A2,
  not a separate product.
- **`PROTOCOL.md`.** Credits ride the existing signed-event
  transport. No new crypto, no new transport, no new sync model.

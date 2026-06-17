# Simplify — Scope Discipline for OpenWarden

> **Audience:** maintainers triaging issues and PRs, contributors deciding
> whether to start work, parents wondering why we said no to something.
>
> **Companion docs:** [`DESIGN_PARADIGMS.md`](DESIGN_PARADIGMS.md) governs
> *how* features are built. This doc governs *whether* they get built.
> [`ROADMAP.md`](ROADMAP.md) is the time-ordered plan; this doc is the
> filter the roadmap passes through.

OpenWarden is grant-funded forever. No subscription, ever. That means:
**every feature has a 5-year maintenance bill, and there is no revenue
to pay it.** The single biggest threat to OpenWarden isn't a kid bypassing
restrictions or Google changing an API. It's scope creep turning the
project into a maintenance burden that the grant runway can't sustain.

This doc exists to say "no" gracefully and consistently.

---

## 1. The 5-year test

Before any feature lands, ask:

> **If we ship this in v1.x, can we still maintain it in v6.0 with a
> two-person team funded by grants?**

If the answer is **no**, the feature does not ship in core. It can live
in a fork, a plugin (when we have a plugin API, which is currently
"never"), or as a documentation entry suggesting how a user could
self-implement.

The test has three subcomponents:

1. **Cost of dependencies.** Does this pull in a library with a history
   of breaking changes? (Looking at you, every Android background-work
   API.) Does it require a paid SDK? Does it require a service we don't
   control?
2. **Cost of platform churn.** Will Apple deprecate this in iOS 19?
   Will Google make this Play-Services-only? If the answer is "probably,"
   the feature is fragile and we say no.
3. **Cost of user expectations.** Once we ship it, every parent will
   expect it forever. Removing a shipped feature is harder than not
   shipping it. Be sure before you start.

---

## 2. Feature tier system

Every issue and PR is labeled with a tier. The tier governs whether and
when we work on it.

### Tier 0 — Foundational (MUST ship in v1)

If any of these don't ship, OpenWarden doesn't ship.

- Device Owner DPC on Pixel 7 with the full `DISALLOW_*` restriction set.
- Atomic provisioning ([`PROVISIONING_V2.md`](PROVISIONING_V2.md)).
- Signed policy bundles (Ed25519) with replay protection.
- Allowlist + time windows + lock-now.
- BIP39 recovery phrase, printed PDF, child-side recovery flow.
- Parent KMP app: Android Compose UI, iOS SwiftUI UI, both pair and edit
  policies.
- LAN/mDNS transport (one transport must work).
- Family Feed with co-parent visibility
  ([`UX_PATTERNS.md`](UX_PATTERNS.md) §B).
- Audit log on both sides.
- Stale-policy mode (offline kid handling).

### Tier 1 — High-leverage (v1 if time, v2 otherwise)

These materially improve safety or UX but the product is viable without
them.

- DNS-based content filter ([`DNS_FILTER.md`](DNS_FILTER.md)).
- Install-approval flow (parent gates new app installs).
- Babysitter limited-admin role.
- Self-hosted WireGuard transport.
- Reproducible Android builds for F-Droid.
- Co-parent join flow.
- Bedtime hard-lock with parent-device-only unlock.

### Tier 2 — Nice-to-have (v3+, never before)

Real value, real cost. We will not start these before Tier 0+1 are
shipped, polished, and have <1 critical bug per month for three
consecutive months.

- On-device AI classifiers (NSFW image, bullying-signal text).
- Time-bank ("save 10 minutes for tomorrow").
- Geofence-based rules.
- Tailscale transport.
- Per-app data-usage limits.
- Weekly parent digest emails.
- Multi-language UI (beyond English).

### Tier 3 — Out of scope (never)

Every one of these has been proposed at some point. The answer is no.

- Multi-tenant SaaS. (Forks are welcome to do this; core is not.)
- Marketplace for third-party rule packs.
- White-label / enterprise distribution.
- A web dashboard.
- Parent-to-parent in-app chat.
- Per-kid policy in a multi-kid family (one family = one kid in v1).
- Per-parent UI theme customization.
- A plugin architecture.
- Anything that requires Play Services in the required path.
- Anything that requires an account on a vendor's cloud to function.
- Any form of telemetry or analytics, even "anonymized."
- Crash reporting that auto-uploads.
- Push notifications that traverse a OpenWarden-operated relay before
  reaching the parent. (Self-hosted ntfy.sh is fine; running ntfy for
  users is not.)
- Web-based content monitoring (reading messages, scraping social).
  OpenWarden is a *control* tool, not a *monitoring* tool. See
  [`SECURITY.md`](SECURITY.md) §"Data we don't keep."

---

## 3. Rules to live by

These are how we keep scope tight in practice. Each is a rule of thumb
with a specific failure mode it prevents.

**One kid, one or two parents.** v1 supports a family of one child and
one or two parents. Babysitters in v1.1. Do not generalize to
"households of five with rotating step-siblings." The data model can
support more, but the UI cannot stay simple if we expose it.
*Prevents:* enterprise-UX bloat, role-permission matrix nightmares,
"who's the admin?" confusion.

**One device *tier* model, not one family ([ADR-001](adr/001-one-device-tier.md),
[ADR-023](adr/023-enforcement-floor-tiers.md)).** Not Pixel-only: a tier
system. **Tier 1 = Pixel-class stock** (full enforcement, the reference). **Tier
2 = specific tested OEM models** (Samsung Galaxy S22+/A55+/Note, OnePlus 11+,
Motorola Edge 50+, Nothing Phone 2+ — the canonical list in ADR-023) — supported
with *documented enforcement gaps*: OEM-preloaded apps can bypass the launch
allowlist, FRP/unlock is best-effort, and watchdog liveness needs a per-OEM
battery exemption. **Tier 3 = all other or older Android 13+ devices**,
best-effort; heavy-customization OEMs (Xiaomi MIUI) and untested models of the
Tier-2 brands live here. We don't pretend OEM differences
don't exist — we **tier and disclose** them, never silently over-promise.
*Prevents:* both the infinite OEM-bug matrix *and* a silent over-claim that a
Samsung phone is as locked down as a Pixel.

**One AI model per category.** When we add NSFW image classification
(Tier 2, v3+), we ship one model. Not a marketplace, not a "choose your
classifier" toggle. One model, locally evaluated, with public eval
results. *Prevents:* AI-model maintenance death spiral.

**One transport in v1 base.** LAN/mDNS. Other transports (WireGuard,
Tailscale, BLE) ship sequentially, each with its own bug budget. Do not
ship six in week one.
*Prevents:* combinatorial transport-bug explosion.

**One UI screen per job.** Don't pile five actions into one drawer.
Don't make a dashboard with twelve cards. Three top-level tabs, each
showing one thing.
*Prevents:* UI complexity that scares non-tech parents into uninstalling.

**One supported provisioning path.** The Desktop GUI Provisioner is the
v1 path ([`PROVISIONING_V2.md`](PROVISIONING_V2.md) §5). QR
provisioning is a v2 consideration. NFC bump is not on the roadmap.
*Prevents:* "which path do I use?" parent confusion + 3x test surface.

**One paid line item, forever.** The Apple Developer Program ($99/yr)
is the only paid line item in OpenWarden. Any PR adding a second paid
line item is automatically rejected unless it bundles a removal of the
$99 line.

---

## 4. Things to defer indefinitely

These are explicitly **not on the roadmap** and not under discussion.
If someone files an issue for one, close with the polite-no template
(§6). If they keep pushing, link them here.

- **Multi-child families with different policies per kid.** The
  data model can express it; the UI cannot stay simple. Forks
  welcome.
- **Multi-parent roles beyond [admin, babysitter].** No
  "grandparent (read-only)," no "tutor (school-hours-only)," no
  "stepparent (custody-scheduled)." Two roles is enough.
- **Custom themes.** One default, one high-contrast. That's it.
  Branding the app in your family's favorite color is not a
  feature.
- **Plugin architecture.** Pluggable transports are not plugins —
  they are an internal interface. We will not expose a public
  plugin API.
- **Web dashboard.** Adds a server, adds auth, adds a frontend
  toolchain, adds attack surface, adds maintenance. The mobile
  apps are the dashboard.
- **Reporting / analytics for parents.** Beyond "today's app
  usage" on the Family Feed, no charts, no exports, no weekly PDFs.
  If a parent wants research-grade data on their kid's phone use,
  OpenWarden is the wrong tool.
- **"Mom and Dad can chat in-app."** Co-parents already have
  Signal / iMessage / SMS / a relationship. We will not build a
  worse version of those in this app.
- **A web sign-in flow** for any reason. No accounts, ever.
- **Time-of-day-based AI sensitivity** ("be stricter at night").
  Don't expose tunables that imply a model is finely-tuned when it
  isn't.
- **Parent-to-parent permission delegation** ("Mom can change rules
  but not unblock"). Both admin parents are full admins, with full
  audit. Co-parent visibility solves the "did mom know dad
  unblocked it?" problem without role hierarchy.
- **Sync with iOS Screen Time / Android Digital Wellbeing.** Pulls
  us into vendor data formats and changes. No.
- **Reward systems / gamification.** No XP for completing chores,
  no time-bonus for finishing homework. OpenWarden is plumbing, not
  parenting advice.

---

## 5. Estimate triage

Every PR and issue gets two labels, applied by the maintainer at
triage:

- **Build cost: S / M / L.** Engineering hours to ship.
  - S: < 1 week of one person's time.
  - M: 1–4 weeks.
  - L: > 4 weeks.
- **Maintain cost: S / M / L.** Ongoing hours per quarter to keep
  working after ship.
  - S: < 4h / quarter.
  - M: 4–16h / quarter (one weekend per release).
  - L: > 16h / quarter (a recurring chore that eats roadmap time).

**Rule of thumb:**
- Build S + Maintain S → likely yes, if Tier 0 or Tier 1.
- Build M + Maintain S → yes if Tier 0/1, defer if Tier 2.
- Anything Maintain L → defer to a later major version or refuse.
  We don't take on permanent rent.

The labels are visible in GitHub's issue list. Contributors filtering
"good first issue" will see build/maintain estimates and can pick
something realistic.

---

## 6. Saying no with grace — copy-paste templates

Use these verbatim. The point of templates is consistency: the same
"no" from every maintainer, every time.

### Tier 3 / out-of-scope

> Thanks for the suggestion. OpenWarden keeps scope tight to v1
> priorities; the feature you're describing falls under
> [`SIMPLIFY.md`](SIMPLIFY.md) Tier 3 (out of scope). The reasoning is
> in the doc; happy to discuss if you think it's mis-categorized. If
> you'd like to build it as a fork under Apache 2.0, that's exactly
> what the license is for, and we'll happily link to your fork from the
> README.

### Tier 2 / defer

> Thanks — this is a real value, and it's on our Tier 2 list. We're
> not picking up Tier 2 work until Tier 0+1 ship and stabilize. I'll
> add the issue to the v3+ milestone and we'll revisit when Tier 0+1
> are done. Subscribe to the milestone for updates.

### Threat-model concerns

> This PR touches the threat model in [`SECURITY.md`](SECURITY.md);
> specifically it would [weaken X / open Y]. I can't merge it as-is.
> If you have a way to achieve the same UX without weakening the
> model, I'm interested — open a fresh discussion and we'll work it
> through.

### Paid-service dependency

> This PR introduces a dependency on [paid service]. OpenWarden's
> [`README.md`](../README.md) pledges no paid line items in the
> required path (the single $99/yr Apple Developer Program for
> TestFlight is the documented exception; see
> [`DISTRIBUTION.md`](DISTRIBUTION.md)). I can't merge this. If
> there's a FOSS / self-hostable alternative, happy to consider that.

### Telemetry / analytics

> OpenWarden has a no-telemetry pledge. This PR includes [crash reporter
> / analytics / usage beacon] which auto-uploads. We can't ship that.
> If you want to add an opt-in **manual** crash export (parent taps
> "Share crash log" → emails the maintainers), that's mergeable.

### Politely declining for tone / fit

> Appreciate the work, but this isn't quite the direction OpenWarden is
> heading. Specifically: [the tone of the copy / the UX pattern / the
> screen layout] doesn't match [`UX_PATTERNS.md`](UX_PATTERNS.md) §X.
> I'd rather have a smaller, consistent surface than a larger,
> patchwork one. Closing this PR; feel free to open a discussion if
> you'd like to reshape the contribution.

### Tone notes for "no" messages

- Always thank the contributor first.
- Always link to the rule.
- Never disparage the idea — it might be great, it might fit a fork.
- Never leave them guessing why. Linked rules are non-negotiable;
  un-linked vibes are not.

---

## 7. Trash compactor (per release)

Every release, **before** the version tag, the maintainer runs the
compactor checklist:

- [ ] Run `./gradlew :shared:dependencies` and review the dependency
      tree. Any new transitive deps since last release? Justify each
      one in the release notes.
- [ ] Audit for unused code paths. Tooling: `detekt` with the
      `UnusedPrivateMember` rule, plus a manual sweep of any code
      added "for future use" and never wired up. Delete.
- [ ] Audit for dead settings. Open the parent app, walk Settings →
      Advanced. Any toggle that has been off-by-default since v1.0?
      Investigate whether anyone uses it. If usage is zero (we don't
      have analytics, but issue volume and PR comments are proxies):
      mark for sunset (§8).
- [ ] Audit `TODO` / `FIXME` comments. Either resolve, file as
      issues, or delete. Comments older than two releases get deleted
      regardless.
- [ ] Audit string resources. Unused strings get deleted; the
      translator's time is precious.
- [ ] Check binary size. The parent APK should be < 25MB. If it
      grew, find out why.

The compactor is a *removal* exercise, not an addition. The goal is
that the v1.5 codebase is smaller than the v1.4 codebase whenever
possible.

---

## 8. Sunset policy

Features that ship and don't get used should not stay in the codebase
forever.

**Trigger:** 6 months with zero issue/PR/feedback signal for a feature,
OR a documented architectural reason (deprecated API, dead transport,
etc.).

**Process:**
1. Open a "Sunset proposal" issue. State the feature, the rationale,
   the proposed removal version.
2. Two-week comment window. If a current user shows up and says "I use
   this," the proposal is paused for re-evaluation.
3. If no one shows up, the feature is marked deprecated in the next
   minor release, with an in-app banner: "X will be removed in v1.N+1."
4. The deprecation banner runs for a full minor release before removal.
5. Removal in the next minor. Document in [`ROADMAP.md`](ROADMAP.md).

**Bias:** when in doubt, sunset. Less code = less to maintain = more
runway. A removed feature can be brought back if a parent demonstrably
needs it; a never-removed feature drags forever.

---

## 9. The "are we doing too much?" smell test

Once per quarter, the maintainer team asks:

- Is the next release going to take longer than the last one to ship?
- Are bug reports outpacing the team's capacity to triage?
- Did anyone burn out this quarter?
- Are we saying "yes" more often than the templates in §6 say?

If two or more of those are "yes," **stop and trim.** Run an unplanned
trash-compactor pass (§7). Decline the next three Tier 2 promotion
proposals. Cut a maintenance-only release with no new features. Drop a
transport that didn't catch on.

The grant is to keep OpenWarden running for parents who need it. The
grant is not to keep OpenWarden growing. Those are different things, and
the second one kills the first.

---

## 10. When to revisit this doc

This doc is itself subject to scope creep. Read it once per quarter.
If the tiers have drifted, or the templates feel stale, or §3 has a
new rule, update it. Otherwise leave it alone — the value of a
guardrail document is its stability.

Major edits require an ADR. Minor edits (typo fixes, link updates) are
fine in a normal PR. Tier promotions (Tier 2 → Tier 1) are not minor;
they're ADR-worthy.

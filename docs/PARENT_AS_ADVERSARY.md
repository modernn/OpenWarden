# Parent as Adversary — Ethical Stance and Design Protections

> **Audience:** prospective users in unsafe homes, advocates and clinicians
> who might recommend (or refuse to recommend) OpenWarden, auditors from the
> Coalition Against Stalkerware and EFF, contributors tempted to add a
> "covert mode," journalists writing about parental-control software.
>
> **Companion docs:** [`PRIVACY_LEGAL.md`](PRIVACY_LEGAL.md) §5 covers the
> stalkerware policy stance from a legal angle; this doc is the ethical
> stance. [`KID_TRANSPARENCY.md`](KID_TRANSPARENCY.md) is the surface area
> we promise the kid. [`UX_PATTERNS.md`](UX_PATTERNS.md) §C5 ("never lie to
> the kid") is the tone commitment. [`DEFENSES.md`](DEFENSES.md) classifies
> attacks OpenWarden can and cannot defend against; this doc adds the attack
> class where the attacker is the adult holding the parent device.

Most threat-modeling for parental-control software treats the parent as
the trusted actor and the kid as the potential adversary. That model
holds in the majority of families. It does not hold in all of them. The
Coalition Against Stalkerware's published criteria, ENISA's 2024
stalkerware report, and NCADV's technology-abuse data all document that
"parental control" software is one of the most frequently weaponized
categories of consumer software in coercive households. OpenWarden cannot
ignore this. OpenWarden could be the operating system of a kid's
oppression, or it could be the rare parental tool that structurally
refuses that role. This document is the project's commitment to the
second outcome, and the enumeration of what it costs.

---

## 1. The threat

OpenWarden could be misused. The realistic scenarios are not hypothetical.

- **Custody-dispute surveillance.** A divorced parent installs OpenWarden
  on a child's phone primarily to surveil the ex-spouse's household —
  location pings whenever the kid is at the other parent's home,
  inference of household routine from app-usage timing.
- **Controlling-household isolation.** A coercive parent uses OpenWarden
  to block the kid from reaching outside help: counselors, teachers,
  crisis lines, the other parent, extended family.
- **Surveillance of the kid as proxy for the other adult.** Locations
  the kid visits become a map of where the other parent goes. App
  usage becomes a window into the kid's social world that the
  coercive parent then uses to punish or manipulate.
- **Evidence-manufacturing in custody fights.** Audit logs become
  exhibits in family court, framed to make the other parent look
  negligent.
- **Pure punishment.** The lock-now button used as a behavioral
  control device — "you talked back, your phone is bricked for the
  weekend" — divorced from any safety rationale.

Coalition Against Stalkerware's criteria are explicit: parental tools
that enable any of the above are stalkerware. The Coalition does not
grant blanket exemption to "parental" framing. NCADV's technology-abuse
data shows that controlling parents and partners use the same toolkit
and often the same apps; the difference between a parental-control
deployment and an intimate-partner-surveillance deployment is sometimes
only the label on the marketing page.

OpenWarden's threat model includes the parent. That is the honest start.

---

## 2. OpenWarden's design principles for this threat

OpenWarden's existing architecture already pushes against parent-as-adversary
deployments. The principles are not new mitigations bolted on; they are
the project's design floor.

- **Kid transparency baseline.** Per
  [`KID_TRANSPARENCY.md`](KID_TRANSPARENCY.md), every monitored category
  is visible to the kid. There is no admin toggle that turns on secret
  monitoring. A coercive parent who installs OpenWarden cannot enable
  features the kid doesn't see, because no such features exist.
- **Emergency floor unbypassable.** Emergency dialing (911 / 112 / 999
  / 110 per locale) is provided by Android and the carrier, not by
  OpenWarden. The lock-now screen (UX_PATTERNS A4) and the bedtime lock
  (A6) explicitly expose the emergency dialer. OpenWarden cannot disable
  it even if a parent wanted to.
- **Audit log visible to kid.** The "See what OpenWarden saw this week"
  view (KID_TRANSPARENCY §7) is the kid's copy of the same record the
  parent sees. A parent cannot use OpenWarden to act in the kid's name
  without the kid knowing.
- **Open source.** Apache 2.0, reproducible builds, source available
  for third-party audit. A kid, the other parent, a teacher, an
  advocate, or a clinician can verify that OpenWarden does only what the
  Kid Transparency Screen claims. No black box, no proprietary
  classifier the public cannot inspect.

These four are not aspirational. They are the load-bearing properties
of every other commitment in this document. Any future change to the
project that would weaken them is rejected on sight, regardless of
parent-side user demand.

---

## 3. Specific protections against abuse vectors

The mitigations below are concrete implementation rules, traceable to
specific abuse patterns.

- **No secret monitoring categories.** Every classifier (NSFW,
  bullying-signal, future additions) is enumerated on the Kid
  Transparency Screen the moment it ships. There is no parent-side
  toggle to monitor more than the kid is shown. New categories ship
  only after the screen is updated.
- **No content access.** OpenWarden structurally cannot read message
  bodies, photo libraries, search history, or in-app screens beyond the
  on-device classifier's ephemeral scan. Even if a parent requests
  "Bark-style" content monitoring, OpenWarden will not implement it.
- **Time-locked self-unlock for older teens.** A kid aged 14 or older
  can initiate a 7-day decommission countdown from the Kid Transparency
  Screen. The countdown is visible to the parent and cancelable by
  either party. At the end of the window, OpenWarden removes itself
  cleanly. The 7-day delay protects against impulsive disabling during
  a normal conflict; the existence of the option protects against
  indefinite coercive control.
- **Symmetric co-parent visibility.** Per UX_PATTERNS §B, both parents
  see the same data the kid sees and the same data the other parent
  sees. OpenWarden has no "primary parent" role with extra access. The
  kid screen and both parent screens render from the same audit log.
- **No remote camera or microphone activation.** Not implemented, will
  not be implemented. The relevant Android APIs would require additional
  permissions OpenWarden does not request. This is a hardware-adjacent
  guarantee, not a policy promise.
- **No covert presence.** The DPC announces itself as device
  administrator. The OpenWarden app icon is visible on the device. The
  notification is persistent. Hiding OpenWarden is a stalkerware behavior
  and we do not ship it.

---

## 4. UI tone for the kid screen

Tone is a defense. The Kid Transparency Screen's voice rules
(KID_TRANSPARENCY §9) already forbid surveillance verbs and punitive
framing. Parent-as-adversary adds one more rule: the kid copy never
positions the parent as the watcher.

- **Don't:** "Dad is watching you."
- **Do:** "OpenWarden was set up by your dad. Here's what it does. Here's
  how to ask for help outside the family."

The shift is small and load-bearing. "Dad is watching" frames the
parent as omniscient; the kid in a coercive home reads that as
confirmation of their reality. "OpenWarden was set up by your dad" frames
the parent as the deployer of a tool the kid can inspect, which is
literally what is happening.

Every kid-facing screen carries a help-seeking footer:

> Need help outside the family? Talk to a teacher, doctor, school
> counselor, or call 988 (US) / Childhelp 1-800-422-4453. These calls
> are private. OpenWarden does not see who you call.

The footer is non-removable by the parent. It is part of the screen's
core string set in `transparency_strings.xml`, not a configurable item.

---

## 5. Hard-coded help resources

The following resources are bundled into the kid app at build time,
displayed on the lock-now screen, the bedtime screen, and the Kid
Transparency Screen. They are localized per device locale.

- **988 Suicide & Crisis Lifeline** (US) — call or text.
- **Childhelp National Child Abuse Hotline** — 1-800-422-4453 (US), 24/7.
- **Crisis Text Line** — text HOME to 741741 (US).
- **Local-equivalent crisis lines** per locale, sourced from a
  curated list in `resources/crisis_lines.json`, refreshed quarterly.
- **Coalition Against Stalkerware** advocate-finder URL, for older
  teens who can read the resource directly.

Calls to these numbers go through the platform dialer. OpenWarden cannot
log them, cannot block them, and does not see them. This is enforced by
the same mechanism that protects emergency dialing.

---

## 6. What OpenWarden will not ship

The features below would each make OpenWarden more "competitive" with
commercial parental-control products. Each would also make OpenWarden a
stalkerware vector. The project will not ship them.

- **Audio recording** of any kind, on either side of any call,
  ambient, or via the microphone.
- **Continuous screen capture** for parent review.
- **Message-content monitoring** — neither SMS bodies, nor in-app
  message contents, nor keyboard logging.
- **Remote camera or microphone activation.**
- **Covert mode** — any feature whose function is to hide OpenWarden's
  presence from the monitored party.
- **Per-parent privacy asymmetry** — features that show one parent
  data the other parent or the kid cannot see.
- **Geofence "alert only to one parent"** — location alerts are
  symmetric across all paired parent devices and the kid's own view.

This list is open-ended. New abuse vectors will be identified, and the
list will grow. The list will never shrink.

---

## 7. The hard cases

Some scenarios are uncomfortable but not abusive. Honesty about the
boundary matters.

- **Divorced parent installs OpenWarden on the kid's phone without
  informing the other parent.** Not abuse. Many divorced parents make
  device decisions independently. The other parent will see OpenWarden on
  the phone the next time they hold it. The Kid Transparency Screen is
  visible to all adults the kid shows it to.
- **Divorced parent uses OpenWarden to infer the other parent's
  household.** The phone is at one location at a time. OpenWarden has no
  remote reach. Location is sampled per the parent's rule, visible to
  the kid, and identical across both parents' views in a paired family.
  The architecture does not give a hostile ex-spouse a meaningful
  surveillance lever they couldn't get from "where is the kid right
  now" via any other means.
- **Custody-dispute use of audit-log data.** A parent in a court case
  may screenshot their own OpenWarden view as evidence. OpenWarden cannot
  prevent this any more than a notes app could prevent screenshots.
  The audit log is honest; what either parent does with it is the
  parent's choice and the court's evaluation.
- **Parent revokes the kid's recovery phrase.** OpenWarden has no Apple-ID-
  style centralized lockout mechanism. The recovery phrase ceremony
  (UX_PATTERNS A7) is symmetric and auditable. A parent cannot use
  OpenWarden to lock the kid out of recovery in the way iCloud Family or
  Google Family Link allow.

---

## 8. Disclosing risks during onboarding

The parent onboarding flow (ONBOARDING.md) includes a screen titled
"OpenWarden can be misused." The screen is non-skippable and includes:

- The statement that OpenWarden is a tool and like any tool can be
  misused.
- A summary of the Kid Transparency Screen and the project's
  anti-stalkerware design choices.
- A link to this document.
- A link to the project's ethics statement in `README.md`.

The screen does not gatekeep. We do not refuse to install OpenWarden based
on a self-report. We do guarantee that every adult installing OpenWarden
has been shown the ethical stance and the resources for getting out of
a coercive deployment.

---

## 9. Reporting and law-enforcement posture

OpenWarden will not auto-report any use of itself to anyone.

- We do not transmit data to the project. There is no telemetry. We
  could not auto-report if we wanted to.
- We will not assist law enforcement requests for "OpenWarden data" beyond
  pointing the requester at the relevant family's devices and a court
  order. The project has no logs to share.
- We will publish, in the README and on the project website when it
  exists, contact information for the Coalition Against Stalkerware,
  NCADV, RAINN, and the Crisis Text Line for anyone who believes
  OpenWarden is being used abusively against them.

The project's response to abuse reports is to amplify external
advocates, not to position itself as one.

---

## 10. Teen agency at age 14 and 16

Teen autonomy is a defense. Per the graduated-trust model in
KID_TRANSPARENCY.md §6 and the protections in §3 above, older teens
get capabilities that are explicitly outside the parent's control.

- **Age 14+:** the kid can initiate a 7-day decommission countdown.
  The parent is notified; either party can cancel; at expiry, OpenWarden
  removes itself cleanly. The kid does not need to justify the action.
- **Age 16+:** the parent can configure full graduation off OpenWarden,
  which removes the DPC and converts the device to a normal Android
  phone with no monitoring. The default for 16+ deployments is to
  prompt the parent to consider graduation at the next policy review.
- **Throughout:** the help-seeking footer remains, the audit log
  remains visible, and the Kid Transparency Screen remains the source
  of truth.

These thresholds are conservative relative to GDPR Article 8 and
several US state thresholds; they are aggressive relative to commercial
products. The conservatism is on the parent's side, the aggression is
on the kid's side, and that is the right direction.

---

## 11. The "concerned other adult" case

A teacher, aunt, grandparent, school counselor, or pediatrician who
suspects a kid is in an unsafe home can ask the kid to open the Kid
Transparency Screen. The screen shows exactly what OpenWarden does, who
configured it, and what the audit log contains.

- The screen is evidence the concerned adult can review.
- With the kid's permission, the concerned adult can screenshot the
  screen for a referral to child protective services, family services,
  or law enforcement.
- The concerned adult does not need a OpenWarden account, a parent app
  install, or any project cooperation. The Kid Transparency Screen is
  self-contained on the kid's device.

This is a deliberate property. OpenWarden makes the kid's monitored
situation legible to anyone the kid chooses to show.

---

## 12. README and project-wide framing

The project README will carry an explicit anti-stalkerware statement:

> OpenWarden is anti-stalkerware. It is designed so the kid always sees
> what is monitored. If you are in an unsafe home and need help: call
> or text 988 (US), Childhelp 1-800-422-4453, or contact the Coalition
> Against Stalkerware.

The statement is not buried. It sits near the top of the README,
between the project pledge and the install instructions. It will appear
in equivalent form on the project website when one exists.

---

## 13. The hard truth

OpenWarden cannot prevent abuse. The project is software; software cannot
intervene in a coercive household. A determined abusive parent will
find tools to abuse with, and OpenWarden cannot stop that any more than
Signal can stop coercion of a Signal user into showing their phone.

What OpenWarden can do, and what this document commits the project to, is
ensure that OpenWarden is not the abuse vector. The kid always knows what
is monitored. Help-seeking is one tap away on every screen. The audit
log is visible to the kid. The classifier is open source. The recovery
phrase is symmetric. The 7-day teen unlock is real. The emergency
dialer is unblockable. No covert features, no remote audio or video, no
content monitoring, no per-parent secret access.

That is the line. The project will not cross it for market share, for
parent-side feature parity with commercial products, or for any
"safety" framing offered as justification.

---

## 14. References

- Coalition Against Stalkerware criteria — https://stopstalkerware.org/.
- ENISA stalkerware report 2024.
- NCADV — technology and intimate partner violence statistics, 2023–2024.
- 988 Suicide & Crisis Lifeline — https://988lifeline.org/.
- Childhelp National Child Abuse Hotline — 1-800-422-4453,
  https://www.childhelp.org/.
- Crisis Text Line — text HOME to 741741, https://www.crisistextline.org/.
- RAINN — https://www.rainn.org/.
- EFF — "When parental controls cross the line," surveillance
  self-defense guides.
- Companion: [`PRIVACY_LEGAL.md`](PRIVACY_LEGAL.md) §5 (stalkerware
  legal posture), [`KID_TRANSPARENCY.md`](KID_TRANSPARENCY.md) (kid
  surface area), [`UX_PATTERNS.md`](UX_PATTERNS.md) §C5 (honesty rule),
  [`DEFENSES.md`](DEFENSES.md) (attack/defense classification).

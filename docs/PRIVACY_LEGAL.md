# Privacy and Legal Stance

> **Audience:** parents asking "is this legal where I live?", auditors
> from EFF / Coalition Against Stalkerware / journalists, contributors
> tempted to add telemetry "for usage stats." Lawyers will eventually
> review this; until then it is our self-hosted analysis.
>
> **Companion docs:** [`README.md`](../README.md) (the pledge),
> [`ARCHITECTURE.md`](../ARCHITECTURE.md) (no-server design),
> [`KID_TRANSPARENCY.md`](KID_TRANSPARENCY.md) (what the kid is shown),
> [`DISTRIBUTION.md`](DISTRIBUTION.md) (channels and signing),
> [`FAMILY_MODEL.md`](FAMILY_MODEL.md) (data hierarchy),
> [`SIMPLIFY.md`](SIMPLIFY.md) (why we said no to a backend).

OpenWarden's privacy posture is a property of its **architecture**, not its
copy. We have no servers. We collect no data. There is nowhere for kid
data to go. Most of the world's privacy law is written for operators of
online services; OpenWarden is not one. The rest of this doc walks through
the specific regimes a reader might worry about and explains, regime by
regime, why OpenWarden either falls outside the scope or trivially
complies.

---

## 1. COPPA — Children's Online Privacy Protection Act (US, 15 U.S.C. §6501)

COPPA applies to the **operator of a website or online service directed
to children under 13**. It requires verifiable parental consent before
such an operator collects personal information from a child.

OpenWarden is not an operator. The app runs locally on the child's Pixel
and on the parent's device. No OpenWarden-controlled service ever touches
the kid's data. Per [`ARCHITECTURE.md`](../ARCHITECTURE.md) §"Data we
keep," every event log, every classifier flag, every screen-time tally
stays on the family's devices. Optional transports — LAN, BLE,
WireGuard, Tailscale free tier — carry encrypted bytes between parent
and child, but no OpenWarden-operated service participates in that exchange
beyond what the parent has chosen to enable.

**Stance:** OpenWarden the application is exempt by design. COPPA does not
apply to local-only software.

**Adjacent infrastructure.** The project may operate a marketing site
(`openwarden.dev`), a community Matrix / Discourse / GitHub Discussions
forum, and an Open Collective donation page. None of these surfaces is
"directed to children under 13." All marketing materials are written for
the parent buyer. Anyone interacting with the project's site, forum, or
donations is presumed to be an adult parent, contributor, or auditor. We
do not solicit child accounts on any project property.

---

## 2. GDPR — General Data Protection Regulation (EU 2016/679)

GDPR applies to processing personal data of EU residents. Two facts
control the analysis:

**Article 2(2)(c) household exception.** GDPR explicitly does not apply
to processing "by a natural person in the course of a purely personal or
household activity." Parents managing their own minor children's device
use is the paradigm case of household activity. OpenWarden the application,
in the hands of one family, falls within this exception. The parent is
not a data controller in any GDPR-relevant sense; they are a parent.

**Article 8 child consent thresholds.** GDPR requires parental consent
for processing the data of children under 16, with Member States
permitted to lower the threshold to 13. This applies to controllers
offering information society services — again, services, not local
software. Even if a regulator stretched GDPR to reach OpenWarden, the
parental relationship is the consent vehicle by definition: the parent
is the consenting party because the parent owns the device and runs the
software.

**The project entity.** If the OpenWarden project incorporates (e.g. as a
fiscal-hosted Open Collective entity, an Open Source Collective
project, or eventually a nonprofit), that entity processes ordinary
adult data: contributor emails, donation records, GitHub usernames,
mailing-list subscribers. Standard GDPR compliance for non-kid data
applies to those records only. We will publish a project privacy notice
covering them when the project's website exists; until then there is no
such processing.

**Stance:** The OpenWarden application is within Article 2's household
exception. The project's adjacent infrastructure will follow standard
GDPR practice for the non-child adult data it collects (contributors,
donors, subscribers).

---

## 3. CCPA / CPRA — California Consumer Privacy Act and amendments

CCPA applies to for-profit businesses that meet revenue, data-volume, or
sale-of-data thresholds. OpenWarden is not a for-profit business. The
project has no revenue. The application sells, shares, or processes no
personal information for commercial purposes. CCPA's operative
definitions do not reach us.

**Stance:** Does not apply.

---

## 4. State-level US privacy laws

A growing patchwork (Virginia VCDPA, Colorado CPA, Connecticut CTDPA,
Texas DPDPA, Utah UCPA, plus newer 2025–2026 statutes in Tennessee,
Indiana, Iowa, Montana, Oregon, Delaware, New Hampshire, New Jersey,
Maryland, Minnesota) extends CCPA-style obligations to entities that
collect personal data at scale.

All of these target collecting and processing entities. OpenWarden
processes nothing centrally. The threshold tests (revenue, user counts,
sale of data) cannot be met by a project that operates no service.

**Illinois BIPA** (biometric information privacy) is the most aggressive
state law. OpenWarden collects no biometrics. The on-device NSFW classifier
operates on screen pixels, not on faces, fingerprints, or voiceprints.
The classifier never persists the image; only an event flag (category +
timestamp) is recorded. BIPA's definitions of biometric identifiers
(retina scans, fingerprints, voiceprints, hand or face geometry) do not
reach screen-content classification.

**Stance:** Does not apply.

---

## 5. Stalkerware regulations and policies

"Stalkerware" is the category of covert surveillance apps used by
abusive partners, controlling employers, or jealous family members.
Federal law (the Wiretap Act, 18 U.S.C. §2511) prohibits intercepting
electronic communications without consent. Most US state laws have
parallel provisions. The EU's ENISA has published anti-stalkerware
guidance, and the Coalition Against Stalkerware maintains a public set
of criteria distinguishing legitimate parental tools from stalkerware.

OpenWarden's design deliberately tracks the Coalition's criteria:

- **The monitored party knows.** The Kid Transparency Screen
  ([`KID_TRANSPARENCY.md`](KID_TRANSPARENCY.md)) is reachable any time
  and discloses, in pictograms and plain language, exactly what is and
  is not seen.
- **No covert collection.** The DPC announces itself in the system UI as
  the device administrator. The OpenWarden branding is not hidden.
- **No interception of communications content.** OpenWarden does not read
  SMS bodies, does not record call audio (physically impossible on
  stock Pixel firmware in any case), does not log keystrokes, does not
  scrape in-app messages. The "What OpenWarden DOESN'T see" list is the
  honest enumeration.
- **Parent-only data, never shared.** No third-party SDKs, no analytics
  endpoints, no cloud sync to a OpenWarden service. The store-and-forward
  log is end-to-end between parent and child, even on the same Wi-Fi.
- **Designed for minors in a custodial relationship.** OpenWarden is
  scoped to parental control of minors. We will not add features whose
  primary utility is surveilling adult partners or employees.

**Stance:** OpenWarden is a parental control tool, not stalkerware. The
Coalition Against Stalkerware criteria are a design rubric for this
project. Distribution channels (F-Droid, GitHub Releases, eventual
Apple App Store) apply their own anti-stalkerware checks; OpenWarden's
public-by-design posture passes them.

---

## 6. School / educational regulations

**FERPA** (Family Educational Rights and Privacy Act, US) governs
student educational records held by educational institutions receiving
federal funds. OpenWarden is not such an institution. Parents using
OpenWarden at home, including during school hours, are not creating FERPA
records.

**COPPA-in-schools** guidance from the FTC addresses how schools can
consent on behalf of parents. Inverse direction; not relevant here.

**Stance:** Not a school product. Parents who deploy OpenWarden at home are
not subject to school regulation by doing so. If a school district ever
wished to deploy OpenWarden, that deployment would be the district's
compliance burden, not the project's.

---

## 7. Distribution-channel policies

**Google Play stalkerware policy** prohibits apps that track users
without their knowledge. OpenWarden is not on Play in v1 anyway, but
OpenWarden's transparency design would satisfy the policy if it were.

**Apple App Store** has a similar policy and an additional prohibition
on apps that take over device management without disclosure. The parent
KMP app on iOS is informational only — it does not act as MDM on the
child's iOS device, because the child device is Android. The parent app
ships via TestFlight initially and eventually the App Store; both
review processes accept parental-monitoring apps that are transparent
to the monitored party.

**F-Droid** requires fully free / open-source software, no proprietary
dependencies, reproducible builds, and disclosure of "anti-features"
such as tracking or non-free network services. OpenWarden has none. See
[`DISTRIBUTION.md`](DISTRIBUTION.md) §6.

**GitHub** Terms of Service permit hosting Apache 2.0 open-source
parental control software. No conflict.

**Stance:** OpenWarden complies with the policies of every channel it
ships through.

---

## 8. Open-source license and liability

OpenWarden is **Apache License 2.0**. The license disclaims warranty (§7)
and limits liability (§8). Contributors retain copyright and license
contributions in under the same Apache 2.0 grant via the DCO sign-off
process documented in [`CONTRIBUTING.md`](../CONTRIBUTING.md).

**Crypto export control.** OpenWarden ships Ed25519 signatures, X25519 key
exchange, and AES-GCM symmetric encryption. Such software is classified
under US Export Administration Regulations ECCN 5D002. **Open-source
cryptographic software published online is exempt under BIS §740.13(e)
("publicly available encryption source code")** provided we email
`crypt@bis.doc.gov` and `enc@nsa.gov` with the public URL once. The
project will do that notification when the v1 source repository goes
public, and will keep a copy of the notification in
`docs/EXPORT_NOTICE.md`.

**Stance:** Apache 2.0 disclaims warranty and limits liability. US
export control exempts OpenWarden's crypto per BIS §740.13(e).

---

## 9. What OpenWarden promises

The pledge in [`README.md`](../README.md) is, in plain prose, the
project's privacy contract with parents:

- **No data leaves family devices.** No telemetry, no analytics, no
  crash reports auto-uploaded, no usage stats, no opt-in upload tier.
  Forever.
- **No central server.** No OpenWarden-operated backend. Transports are
  pluggable and chosen by the parent.
- **All cryptography is open source.** Ed25519, X25519, AES-GCM,
  BIP39 — every primitive is publicly specified and implemented
  against audited libraries. See [`CRYPTO.md`](CRYPTO.md).
- **Source code is available.** Reproducible builds are a Tier 1
  feature. Auditors are welcome to verify the binary matches the source.

These promises are the architectural floor, not aspirational language.
Any PR that would weaken them is rejected on sight.

---

## 10. What OpenWarden disclaims

Honest disclaimers, surfaced in the parent ONBOARDING flow and in the
release notes:

- **Use at your own risk.** Standard Apache 2.0 disclaimer.
- **Not a substitute for parenting.** Software cannot replace
  conversation, trust, and presence. OpenWarden is a tool, not a
  parent.
- **May not catch all concerning content.** The on-device NSFW
  classifier has finite accuracy. False negatives happen. False
  positives happen. The classifier is a heads-up signal, not ground
  truth.
- **Children may find ways around restrictions.** Device Owner mode is
  the strongest constraint Android offers; it is not unbypassable. See
  [`ATTACKS.md`](ATTACKS.md) and [`DEFENSES.md`](DEFENSES.md) for the
  current state of bypass research and our response.
- **Emergency services are the platform's responsibility.** 911 / 112 /
  999 / 110 emergency calling is provided by Android and the cellular
  carrier, not by OpenWarden. OpenWarden does not block, intercept, or
  reroute emergency calls.

---

## 11. The age-13 (and age-16) boundary

COPPA triggers at age 13 in the US. GDPR Article 8 sets EU thresholds
at 13–16 depending on Member State. OpenWarden's target age is 5–17, so
some users sit on both sides of the line.

**For ages 5–12.** The parent's authority is uncontroversial under every
regime considered.

**For ages 13+.** Under GDPR-DE and similar laws, the teen has growing
data-subject rights of their own. OpenWarden does not legally answer those
rights, because the household exception removes OpenWarden from GDPR's
scope. But the social legitimacy of the tool requires more than legal
shelter. We document in [`ONBOARDING.md`](ONBOARDING.md) §"Talking to
your teen" the recommendation that parents discuss deployment with any
teen 13 or older before installing. The graduated-privileges system
([`KID_TRANSPARENCY.md`](KID_TRANSPARENCY.md) §6) progressively reduces
parental visibility as the teen ages, which mirrors the legal direction
of travel even though no law compels us.

**Stance:** Parents are encouraged to obtain teen assent for ages 13+
deployments. OpenWarden's UX assumes that conversation will happen.

---

## 12. AI-specific regulations

**EU AI Act (2024/1689).** Categorizes AI systems by risk. OpenWarden's
on-device NSFW classifier and bullying-signal classifier are most
plausibly classified as **limited-risk** systems requiring transparency
to the affected person. The Kid Transparency Screen
([`KID_TRANSPARENCY.md`](KID_TRANSPARENCY.md) §3–§5) provides exactly
that transparency: the kid is told which classifiers run, what they do,
and what is recorded. We will revisit if implementing guidance places
on-device parental classifiers in the high-risk Annex III categories
(not currently the case as of publicly available text).

**US.** No federal AI law. State-level activity includes the Texas
Responsible AI Governance Act (2025) and the Colorado AI Act (2026).
Both target deployers and developers of consequential automated
decision systems. OpenWarden's classifiers do not make consequential
decisions about children — they raise a parent-visible flag that the
parent then interprets. The classifier is advisory, not adjudicative.

**Stance:** On-device AI classifiers run locally; no decisions about
children are made by remote AI services. Transparency is provided per
the Kid Transparency Screen.

---

## 13. Mandatory reporting concerns

If OpenWarden's NSFW classifier flags imagery that appears to be CSAM,
several jurisdictions impose reporting duties on adults aware of such
content (e.g. mandatory reporter statutes in many US states, and the
NCMEC CyberTipline framework).

OpenWarden does not auto-report. We are not an "electronic communication
service provider" under 18 U.S.C. §2258A, and a flag from an on-device
classifier is not a confirmed identification. Auto-reporting would also
create a false-positive disaster for innocent families. The parent
decides what to do with a flag, including any reporting to NCMEC, local
law enforcement, or family services.

**Stance:** If OpenWarden flags severely concerning content, parents are
responsible for any follow-up reporting per their local law. The
project will publish guidance in [`ATTACKS.md`](ATTACKS.md) §"If you
find CSAM" pointing parents to NCMEC's CyberTipline and to the
NeedHelpNow.ca resource for Canadian families.

---

## 14. ADR draft

**ADR-006: Privacy via no-server architecture.**

*Decision.* OpenWarden is local-first. The project operates no servers
that touch family data. The parent device is sole storage for family
state. Transports are pluggable and the parent selects from a menu that
includes purely-local options (LAN, BLE) and external-service options
(Tailscale, self-hosted relay) clearly labeled.

*Consequences.* No direct COPPA, GDPR, CCPA, or US state-privacy-law
exposure for the OpenWarden application. Distribution-adjacent
infrastructure (project website, donation processor, contributor
records) is handled separately under standard adult-data GDPR
practice.

*Trade-offs.* No aggregated analytics for product improvement. Bug
reports and feature signals come from community submissions only.
Crash diagnostics, when shipped, are user-initiated and copy-pasted
into GitHub issues, never auto-uploaded.

---

## 15. README and ONBOARDING text drafts

The pledge in the README already says most of what's needed. Suggested
expansions to ONBOARDING, for a parent's "first launch" screen:

- "OpenWarden is a local-only parental control tool. No data leaves your
  family's devices."
- "We don't operate any servers. We can't collect data because there's
  nowhere for it to go."
- "Cryptography is end-to-end between your devices, even on the same
  Wi-Fi."
- "Source code is available. Builds are reproducible. Auditors are
  welcome."

---

## 16. Concrete recommendations

- This document (`PRIVACY_LEGAL.md`) is the canonical privacy statement
  for the project. Reference it from `README.md`, `ONBOARDING.md`, and
  `KID_TRANSPARENCY.md`.
- Add a short `LEGAL.md` for distribution-channel + licensing
  specifics (DCO, third-party licenses, trademark, export notice) —
  separate from this doc, which is privacy-focused.
- No web-facing "privacy policy" page is required because the project
  operates no web service touching family data. If and when
  `openwarden.dev` launches, it gets a minimal site privacy notice
  covering only the adult visitor data the site itself processes
  (server logs, donation processor, mailing list).
- File the BIS §740.13(e) crypto-export notification when the v1
  source repository goes public; archive the email in
  `docs/EXPORT_NOTICE.md`.

---

## 17. Lawyer review

The v1 self-hosted analysis above suffices for "designed for
compliance." We are not relying on it as a substitute for counsel; we
are relying on the architecture to keep the legal surface small enough
that the analysis is straightforward.

**Trigger for paid or pro-bono counsel review:** when any of the
following first becomes true:

- The project receives a grant of $25k or more.
- The community user base exceeds 10,000 verified installs.
- A regulator, journalist, or NGO requests a formal compliance
  statement.
- The project incorporates as a legal entity.

Likely review partners: Electronic Frontier Foundation, Public Counsel,
Software Freedom Law Center. All three have done pro-bono work for
small FOSS projects with civil-liberties relevance.

---

## 18. References

- COPPA, 15 U.S.C. §6501 et seq.; FTC COPPA FAQ
  (https://www.ftc.gov/business-guidance/resources/complying-coppa-frequently-asked-questions).
- GDPR (EU 2016/679), Articles 2(2)(c) and 8.
- CCPA (Cal. Civ. Code §1798.100 et seq.) and CPRA amendments.
- US Wiretap Act, 18 U.S.C. §2511.
- Coalition Against Stalkerware criteria
  (https://stopstalkerware.org/).
- ENISA stalkerware report 2024.
- EU AI Act (Regulation 2024/1689).
- BIS Export Administration Regulations §740.13(e), public-domain
  encryption source code notification.
- 18 U.S.C. §2258A — electronic-service-provider CSAM reporting duty
  (not applicable to OpenWarden).
- NCMEC CyberTipline (https://report.cybertip.org/).
- Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0).

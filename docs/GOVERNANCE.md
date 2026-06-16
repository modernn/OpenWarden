# Governance and Contribution Model

> **Audience:** maintainers, contributors, grant funders, and parents who
> want to understand who decides what in OpenWarden.
>
> **Companion docs:** [`CONTRIBUTING.md`](../CONTRIBUTING.md) is the
> mechanical how-to-submit-a-PR doc; this doc is the *who-decides* doc.
> [`FUNDING.md`](FUNDING.md), [`COMMUNITY.md`](COMMUNITY.md),
> [`SIMPLIFY.md`](SIMPLIFY.md), and [`DESIGN_PARADIGMS.md`](DESIGN_PARADIGMS.md)
> set the constraints this governance model exists to defend.

Governance documents fail when they read like LLC operating agreements.
This one tries to read like a parent talking to a co-parent: here is who
gets the final say, here is what we vote on, here is how we move forward
when we disagree, and here is how this works after I am gone.

---

## 1. Project type

OpenWarden is an **Apache 2.0** open-source project. Forks are encouraged
(see §11). The project is **grant-funded and donation-supported** per
[`FUNDING.md`](FUNDING.md): no subscription path, no paid tier, no
proprietary fork, ever. The project originated as a single-family
build — a parent (Larson) writing software for his own kid (Oliver) —
but its goal is a **broader community of families**, not a tool for one
household.

That origin matters for governance. Many "built for my kid" projects
stall because the founder cannot let go. OpenWarden plans for that
explicitly. The model below starts with a single decision-maker and
evolves into shared stewardship as the contributor base grows.

---

## 2. Governance models surveyed

We looked at five live models before picking ours.

- **BDFL** (Linus on Linux, Guido on early Python). One person decides;
  the project moves fast and stays coherent, but the bus factor is one.
- **Council / steering committee** (Rust's leadership council, Python
  post-Guido). A small elected body. Slower than BDFL, more durable.
- **Foundation** (Apache Software Foundation, Linux Foundation,
  Eclipse). Legal entity, board, member companies. Mature and
  bureaucratic; appropriate for tens of millions of downloads.
- **Sociocracy** (some smaller FOSS projects). Consent-based; works
  beautifully at small scale, breaks down past about twelve people.
- **Maintainer collective** (curl, jq). Two to five maintainers with
  informal consensus, no formal voting. Lightweight, scales to dozens
  of contributors.

For OpenWarden: **start BDFL (Larson), evolve to a small steering council
once we have five active maintainers.** That threshold is concrete on
purpose — it is observable, not aspirational.

---

## 3. Roles

Six roles, in order of access and commitment.

- **BDFL / Project Lead.** Larson, for v0 through v1. Owns the roadmap,
  the threat model, and the final call. Holds release keys.
- **Maintainers.** Commit access to `main`. Currently zero; grown by
  invitation after sustained, high-quality contributions (typically
  three significant merged PRs over three months, plus alignment with
  the non-negotiables in [`CONTRIBUTING.md`](../CONTRIBUTING.md)).
- **Reviewers.** PR review access without merge rights. Easier entry
  than maintainer. The on-ramp for would-be maintainers.
- **Contributors.** Anyone who has landed a PR. No special privileges,
  but visibility in `AUTHORS.md` and credit in release notes.
- **Translators.** Weblate access for the strings catalog (see
  [`I18N.md`](I18N.md)). Onboarded by any maintainer; no review history
  required for new locales.
- **Designers.** Repo access for `assets/` (icons, pictograms, screenshot
  templates). Onboarded by any maintainer.

Roles are listed publicly in `MAINTAINERS.md` (TBD when the first
maintainer is named). Removing a role requires the same process that
granted it.

---

## 4. Decision-making

Different decisions get different processes.

- **Technical decisions** (a typical PR). BDFL approves until the
  council exists. Once a council exists, **Lazy Approval**: any council
  member can approve, two approvals required for merge, a 72-hour SLA
  before silence becomes implicit consent. The BDFL retains a veto
  during v0–v1 and a tiebreaker vote after.
- **Architectural decisions.** Captured as **ADRs in `docs/adr/`**
  (Architecture Decision Records, one Markdown file per decision,
  numbered). Proposed via PR, comment window of at least five business
  days (see §7), merged by BDFL or council.
- **Roadmap.** Larson drives v0–v1. v2 and beyond are open-RFC and
  council-driven (see §10).
- **Crypto and security.** Higher bar; see §5.

The bias is toward **written, reviewable decisions**. Slack-style
"sounds good!" doesn't count.

---

## 5. Crypto and security decisions

Anything touching the threat model, key handling, signature verification,
or protocol layer is treated as elevated risk.

- At least **two cryptographers** must review (community reviewers are
  fine; they do not need to be maintainers).
- For high-stakes changes — new wire format, replacing a primitive,
  changing the key lifecycle — use the `impeccable-codex-debate` skill
  to run an adversarial review before merge.
- **No "obvious wins" merge without a second pair.** A one-line
  cryptographic change is still a cryptographic change.
- Security disclosures use **GitHub Security Advisories** plus a
  72-hour-to-acknowledge SLA. Coordinated disclosure preferred;
  publish-by-default after 90 days.

The expensive lesson the security community has learned over twenty
years is that the threat model lives or dies in PR review. OpenWarden
treats every crypto PR as if it might be the one that breaks Oliver.

---

## 6. Code of Conduct

OpenWarden uses **Contributor Covenant 2.1**, unmodified. BDFL enforces
during v0–v1; council enforces after that. Enforcement is **three
strikes**:

1. **Warning** — private, in writing, with the offending behavior
   quoted and the requested change spelled out.
2. **Temporary ban** — typically 30 days from project spaces (GitHub,
   Matrix, Discussions).
3. **Permanent ban.**

A **public log of bans** is maintained — names, dates, and reason
category (e.g., "harassment," "doxxing," "repeated CoC violations"),
with details redacted to protect targets per [`COMMUNITY.md`](COMMUNITY.md)
§15. Transparency is the point.

Stalkerware advocacy, harassment of a contributor's kid, and weaponizing
the issue tracker against another contributor are immediate permanent
bans. No three strikes.

---

## 7. PR review process

The mechanics of every PR:

1. Author opens PR; **CODEOWNERS** auto-assigns a reviewer.
2. **Lint and CI must pass** before review. Reviewers do not babysit.
3. **One approval** is sufficient for merge during BDFL v0–v1; **two
   approvals** required for crypto/security PRs (§5) and for ADR PRs.
4. **ADR PRs** require a **five-business-day comment window** before
   merge to give the community room to object.
5. The BDFL can veto any merge during v0–v1 (used sparingly; documented
   when used).
6. Stale PRs are pinged at 14 days, closed at 60 days unless the author
   asks otherwise. Closing is not rejection — the author can reopen.

---

## 8. Releases

- **BDFL controls release tags through v1.x.** That includes signing
  the Ed25519 OTA bundles per [`OTA.md`](OTA.md).
- **Council controls releases from v2 onward**, with the release key
  managed by the council (multi-sig: any two of three release officers
  can sign).
- **Cadence:** monthly minor releases, ad-hoc patch releases for
  security fixes (target: 72 hours from advisory to signed build).
- **Every release is signed.** Unsigned builds are never distributed
  on F-Droid, never linked from the website, never recommended.
- Release notes always list contributors and link to the funding ledger.

---

## 9. Funding decisions

Aligned with [`FUNDING.md`](FUNDING.md):

- **Grants** are applied for by the BDFL plus any maintainer who wants
  to co-write. Maintainer team reviews drafts before submission.
- **Open Collective** is the treasury. Public ledger, every dollar in
  and out is visible.
- **No salaries.** Modest stipends only, capped transparently. The cap
  is a council decision once the council exists; until then, the cap is
  whatever the BDFL publishes in the ledger.
- **Treasury decisions** (spending above the standing infrastructure
  budget) require BDFL plus a designated **finance volunteer**
  co-signature once the role exists. Spending below the standing budget
  (CI, relay nodes, domain renewal) is routine and not gated.
- If a grant requires telemetry, paid tiers, or proprietary deps, we
  decline. This is encoded in [`FUNDING.md`](FUNDING.md) and is not
  negotiable.

---

## 10. Roadmap process

- **v0–v1:** BDFL-authored roadmap. Public in [`ROADMAP.md`](ROADMAP.md),
  open to issue-based feedback, but final call is BDFL.
- **v2 and beyond:** open **RFC process**. Anyone can write an RFC
  (Markdown PR to `docs/rfcs/`). Council reviews; community comments
  for a minimum of two weeks; council decides.
- **Tie-breaking** for contested community votes uses the **Wilson
  score interval** on up/down votes, not naive majority — small-sample
  votes are noisy and Wilson handles that honestly.

The roadmap is filtered through [`SIMPLIFY.md`](SIMPLIFY.md)'s five-year
test before anything lands. Governance does not override scope
discipline.

---

## 11. Fork policy

Apache 2.0 means forks are **fully allowed and welcomed**, particularly
**regional or cultural forks** that adapt OpenWarden for a community we
don't understand well (different languages, different parenting norms,
different platform mixes).

The only constraint is the **trademark** (see §13): a fork that
monetizes — paid tier, SaaS, app-store paid app — must **rename**.
Free, donation-supported, or grant-funded forks may keep the OpenWarden
name if they remain compatible with the upstream protocol and the
non-negotiables in [`CONTRIBUTING.md`](../CONTRIBUTING.md).

A fork that diverges from the threat model (e.g., adds telemetry) must
rename, regardless of monetization. The name is a trust signal; we
defend it.

---

## 12. Stewardship long-term

- If the **BDFL steps down**, the council elects a new lead by simple
  majority. If no council exists yet, the most senior maintainer by
  merged-PR count assumes lead temporarily and convenes a council
  election within 90 days.
- If the **project goes dormant** (no merged PRs for six months, no
  release for nine months), the **Apache Software Foundation** is our
  fallback steward of last resort, subject to ASF's incubation
  criteria. ASF will not accept a project that doesn't fit; the
  backstop is best-effort, not guaranteed.
- **Bus factor target:** three or more active maintainers within two
  years of v1 ship. Below three, the BDFL is the project's single
  point of failure and we say so honestly.

---

## 13. Trademark and identity

- **"OpenWarden"** is a project trademark. USPTO filing is deferred until
  v3 (cost vs. benefit doesn't pencil before then).
- Apache 2.0 covers **code only**, not the trademark. Apache's own
  trademark policy is the model we follow.
- Forks that are likely to confuse users (similar logo, similar
  marketing, paid product using the OpenWarden name) **must rename**.
  Enforcement is correspondence-first; legal action is reserved for
  bad actors and would be funded only if grant money or pro-bono
  counsel covers it.

---

## 14. Conflict resolution

- **Maintainer-to-maintainer technical disagreement:** the BDFL decides
  during v0–v1; the council decides afterward.
- **Community disagreement** (issue threads, Matrix, Discussions): CoC
  enforcers may close threads, lock issues, or escalate to bans per §6.
- **Kid-versus-parent disagreement** about real-world usage is **out of
  scope**. OpenWarden is a tool, not a family counselor. Documentation
  may point at outside resources; the project takes no position on
  what limits a given family should set.

When governance and design collide, design wins: [`DESIGN_PARADIGMS.md`](DESIGN_PARADIGMS.md)
and [`SIMPLIFY.md`](SIMPLIFY.md) are upstream of governance preference.
Governance changes a decision-maker; it does not relax a constraint.

---

## 15. Documentation governance

- Docs live in the **same repo as code**. No separate docs site source
  of truth.
- **ADRs** require a PR and a five-business-day comment window (§4, §7).
- **`README.md`** and a new **`DECISIONS.md`** (TBD) track major
  governance changes in time order: who became a maintainer, when a
  council formed, what RFCs landed, who left.
- Doc-only PRs follow the same review process as code PRs but with a
  lower bar — one approval, no CI gate beyond link-checking and
  spell-check.

---

## 16. References

- **Contributor Covenant 2.1** — https://www.contributor-covenant.org
- **Rust governance model** — leadership council and team structure;
  RFC process is the canonical example for §10.
- **The Apache Way** — consensus-based, lazy-approval, ASF incubator
  criteria. Our fallback steward (§12) and the source of our PR model.
- **Open Source Sustainer toolkit** (Open Source Initiative) — useful
  patterns for grant-funded FOSS treasury and bus-factor planning.
- **Coalition Against Stalkerware** criteria — relevant to §6 bans and
  to the threat model that governance defends.

---

Governance is a means, not an end. OpenWarden exists to keep families
safer without surveillance or subscription. Every rule above is in
service of that. When a rule stops serving that goal, propose an ADR
to change it.

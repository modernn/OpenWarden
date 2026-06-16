# COMMUNITY.md — Marketing, Positioning, and Community Strategy

This document is the operating playbook for how OpenWarden is positioned in the world, how it reaches families and contributors, and how its community is built and moderated. It is a companion to `README.md`, `FUNDING.md`, `DISTRIBUTION.md`, `DESIGN_PARADIGMS.md`, `SIMPLIFY.md`, `CONTRIBUTING.md`, and `ONBOARDING.md`. Where those documents describe what OpenWarden *is*, this one describes how we *talk about it* and *grow* it.

---

## 1. Positioning

### What OpenWarden is
- Open-source, no-subscription, local-only parental control.
- "Control without surveillance."
- Built for ages 5–17, families with one or more kids.
- Android on the child device; Android or iOS on the parent device.
- Apache 2.0 licensed. Fork-friendly by design.

### What OpenWarden is NOT
- Not a monitoring or spying tool. It is explicitly anti-stalkerware.
- Not a substitute for parenting. It enforces agreements; it does not raise children.
- Not a SaaS subscription product. There is no recurring fee, ever.
- Not closed-source. Every line that runs on a child's phone is auditable.

### Elevator pitch (30 seconds)
> "OpenWarden is open-source parental control that respects your kid's privacy. We block apps, set bedtimes, and screen for harm — but we never read messages, photos, or browsing content. No subscription. No servers. Apache 2.0."

Use this pitch verbatim in talks, grant intros, and the README hero. Consistency is a feature.

---

## 2. Competitive positioning

| Product       | Open source | Local-only | No subscription | Kid-transparency screen | Graduated privileges |
| ------------- | :---------: | :--------: | :-------------: | :---------------------: | :------------------: |
| **OpenWarden**   |     yes     |    yes     |       yes       |          yes            |        yes (core)    |
| Family Link   |     no      |    no      |       yes       |          no             |        partial       |
| Bark          |     no      |    no      |       no        |          no             |        no            |
| Qustodio      |     no      |    no      |       no        |          no             |        no            |
| Pinwheel      |     no      |    no      |       no        |          partial        |        partial       |
| Headwind MDM  |     yes     |    no      |    varies       |          no             |        no            |

Differentiators worth leading with:
- **Open source.** Only OpenWarden and Headwind. Headwind is enterprise MDM, not a family product.
- **Local-only.** Only OpenWarden. Everyone else routes through a vendor cloud.
- **No subscription.** Only OpenWarden and Family Link. Family Link is a Google data play.
- **Kid-transparency screen.** Only OpenWarden. The child can see what's monitored, in plain language.
- **Graduated privileges as a built-in.** OpenWarden treats growing-up as a first-class workflow, not a paid upgrade.

Do not attack competitors by name in marketing copy. Show the matrix and let it speak.

---

## 3. Target audiences

### Primary (v1)
- **Tech-savvy parent.** Comfortable with `adb`, F-Droid, sideloading. Wants real control and real privacy. This is the v1 archetype because v1 provisioning is command-line.
- **Privacy-conscious parent.** Signal user, Tor user, runs their own DNS. Hates surveillance. Already distrusts Bark, Qustodio, Family Link. OpenWarden is for them.

### Secondary (v1)
- **Single dad / single mom.** Oliver's-dad archetype. Solo decision-maker, no co-parent to negotiate tooling with. High motivation, limited time. Will accept higher setup friction for a tool that doesn't betray the kid.
- **Open-source contributors.** Kid-safety-conscious developers who want a project to invest in. Many have their own kids; many remember being surveilled themselves.
- **Grant funders.** NLnet, Sovereign Tech Fund, Mozilla MIECO, FTC-adjacent foundations. They fund public-interest tech that the market won't.

### NOT targeting in v1
- Non-tech parents. They are real and they matter — they are the v2 audience, once a GUI provisioner exists.
- Schools and enterprises. Out of scope per `SIMPLIFY.md`. Pointing schools at OpenWarden would distort the product.
- Multi-family megasolutions, multi-household co-parenting orchestration, family-law tooling. All out of scope.

Saying "no" to audiences is how we keep the product small enough to ship.

---

## 4. Anti-stalkerware messaging

Stalkerware is software that monitors a person without their meaningful consent. Many "parental control" products meet that definition when applied to teenagers. OpenWarden does not.

- **Lead with the phrase.** "OpenWarden is anti-stalkerware parental control." Put it in the README, the website, the F-Droid description, and every talk.
- **Reference the Coalition Against Stalkerware criteria.** Show our work against their list.
- **The kid-transparency screen is the centerpiece artifact.** It is the single most important screenshot we will ever ship. Every parent who looks at OpenWarden should see it within thirty seconds of arriving at the site.
- **The one-liner test.** "If your kid can read what's monitored, it's not stalkerware." Repeat this until it is boring.
- **Honest comparison.** Bark uploads kid messages to a cloud and runs ML over them. OpenWarden does not. State this factually, with citations. Do not editorialize.

---

## 5. Community growth strategy

OpenWarden is a five-year project. Growth is measured in families using the product, not vanity metrics.

- **v0 (now):** 0 users. Pre-release. Goal: ship v1.
- **v1 ship target:** 10 families using OpenWarden in production for thirty days.
- **v2:** 100 families. Onboarding friction reduced; GUI provisioner exists.
- **v3:** 1,000 families. F-Droid listing mature, press coverage landed.
- **v4+:** Organic growth via word-of-mouth and grant-funded outreach. Ten thousand is realistic by year five if grant funding continues.

We are not chasing Bark's user count. We are building a sustainable, auditable alternative for the families who want one.

---

## 6. Community infrastructure

- **GitHub:** source, issues, discussions, releases. The center of gravity.
- **Matrix room (via Element):** synchronous chat, privacy-respecting, federated. Avoids forcing contributors onto Discord or Slack.
- **F-Droid:** primary distribution channel for Android. Reaches the privacy-conscious audience directly.
- **TestFlight:** iOS parent-app distribution, limited to invited testers until the app stabilizes.
- **Open Collective:** treasury. All inflows and outflows public.
- **Mastodon and Twitter/X:** optional announcement channels. Not load-bearing. If they vanish, the project is fine.

No Discord. No proprietary chat. Channel choice is itself a positioning statement.

---

## 7. Onboarding new contributors

- `CONTRIBUTING.md` is present and kept current.
- Beginner-friendly issues are labeled `good-first-issue`. We will maintain at least five at all times.
- Code of Conduct: Contributor Covenant 2.1, enforced by maintainers.
- New-contributor greeting: a maintainer posts a welcome comment within 48 hours on a first-time PR. Optional welcome bot if maintainer time runs short.
- Reviewers are tagged automatically via `CODEOWNERS`. PRs do not sit unattended.
- A monthly contributor call on Matrix, recorded if anyone shows up, skipped quietly if nobody does.

The goal is for a stranger to land a meaningful patch within their first week.

---

## 8. Documentation as marketing

Each document has a job in the funnel. We do not write docs for their own sake.

- `README.md` — top of funnel. First impression. Must communicate the elevator pitch within ten seconds of scrolling.
- `ONBOARDING.md` — activation. The first hour with OpenWarden. If a parent gets stuck here, we lose them.
- `KID_TRANSPARENCY.md` — trust artifact. The document we point skeptics at.
- `DISTRIBUTION.md` — "how to get it." F-Droid, sideload, TestFlight.
- `FUNDING.md` — "how it survives without subscription." Reassures parents and funders alike.

The marketing funnel: a pitch leads to README, README leads to ONBOARDING, ONBOARDING leads to install. Each link in that chain must work or the chain breaks.

---

## 9. Grant pitch language

The canonical grant paragraph, to be reused in `FUNDING.md` and all applications:

> "OpenWarden is open-source parental control designed to keep families safe without surveillance, subscriptions, or vendor lock-in. It runs as a Device Owner on stock Android, enforces policy locally, communicates over store-and-forward with end-to-end encryption, and uses on-device AI to flag concerning content without exfiltrating it. Apache 2.0. Funded by grants and community donations."

Specific NLnet ask: **€25k for v2 features** — on-device DNS resolver, parent-mediated install approval, on-device image classifier for concerning content, sealed-box per-event logging. Budget breakdown lives in `FUNDING.md`.

When pitching, lead with the public-interest framing and the local-only architecture. Grant funders are bored of "AI safety for kids" pitches. They are not bored of "we built parental control that doesn't violate the kid."

---

## 10. F-Droid metadata

- **Short description (80 chars):** "Open-source parental control for Android. Local-only. No subscription."
- **Long description (≤4000 chars):** expanded benefits, link to website, link to KID_TRANSPARENCY.md, explicit anti-stalkerware framing.
- **Screenshots:**
  1. Child phone showing a blocked app with the in-app explanation.
  2. Parent dashboard showing today's policy state.
  3. Kid-transparency screen, full text visible.
- **Categories:** System, Tools.
- **Anti-features:** ideally none. Any dependency that triggers an F-Droid anti-feature flag (`NonFreeNet`, `NonFreeDep`, `Tracking`) must be documented and justified, or removed.

F-Droid maintainers are allies. Treat their guidelines as product constraints, not paperwork.

---

## 11. Website strategy

- **Stack:** static site (Astro or Hugo). No JavaScript framework that requires a runtime.
- **Hosting:** Cloudflare Pages (free, no OpenWarden user data flows through it).
- **Pages:** home, why, how-it-works, install, blog, contribute.
- **No tracking, no cookies, no analytics beacons.** The site itself is a privacy signal.
- **Domain:** `openwarden.dev` preferred for the developer-leaning v1 audience; `openwarden.org` reserved for v2+ if the project formalizes as a nonprofit.

The website's job is to convert a curious parent into someone reading the README. Nothing more.

---

## 12. Social media strategy

- **Mastodon** is primary. Federated, no algorithm gaming, audience overlap with our target.
- **Twitter/X** is secondary, mirror-only.
- **Frequency:** one to two posts per week. Quiet is fine.
- **Content:** release announcements, kid-safety research roundups, grant milestones, security-architecture writeups.
- **Do not post:** kid stories, parent oversharing, screenshots of real children's devices, real children's names, real incidents.
- **Avoid:** clickbait, outrage cycles, replies to competitor marketing.

If a maintainer cannot think of something worth posting, they post nothing. Silence is on-brand.

---

## 13. Conferences and talks

- **FOSDEM** (Brussels, February) — apply to the Free Software in Public Interest track.
- **DEF CON** (Las Vegas, August) — apply for kid-safety or anti-stalkerware panels.
- **PrivacyCon** (FTC) — apply when topics align.
- All travel is grant-funded or self-funded by speakers. No corporate-sponsored travel.

Talks are slow-acting. Their effect is contributor recruitment and credibility, not user acquisition.

---

## 14. Press strategy

- **Lead with the open-source angle.** Wired, Ars Technica, The Register, 404 Media are friendly to this framing.
- **Pitch list:** TechCrunch (early-stage product), Ars Technica (security architecture), MIT Technology Review (research framing), The Markup (privacy beat).
- **Avoid:** parenting blogs that monetize fear, "safe internet for kids" SEO farms, any outlet whose business model depends on convincing parents their kids are in constant danger.
- **Media kit:** kept on the website. Logo in SVG, three approved screenshots, the elevator pitch, founder bio in one paragraph, one quote per maintainer.

---

## 15. Community moderation

- The Code of Conduct is enforced, not decorative.
- Discussion happens in Matrix and GitHub Discussions. Both are moderated.
- Maintainers may ban; bans are documented in a public moderation log with the reason redacted to protect targets of harassment.
- Anti-harassment policy is explicit: doxxing, threats, or weaponizing the issue tracker against another contributor results in an immediate ban.
- **No politics drift.** OpenWarden is a tool. We do not litigate parenting philosophy, screen-time studies, or culture-war debates in our channels. Focus on the tool, the architecture, and the code.

---

## 16. Sustainability narrative

Be honest with users and funders. This is a five-year project sustained by grants and donations.

- The treasury is public on Open Collective. Anyone can see runway.
- If grants dry up, the project goes quiet, but the Apache 2.0 license guarantees anyone can fork and continue.
- OpenWarden runs forever on a family's devices regardless of project status. There is no server to shut down.

The line to repeat: **"We're sustainable because we don't need money to run, only to develop."**

---

## 17. Anti-patterns to avoid

- Do not overpromise. We do not "block all bad content." Nothing does.
- Do not fear-monger. Parenting-by-panic is a competitor's business model, not ours.
- Do not claim "100% safe" or "tamper-proof." Both are lies and the security community will notice.
- Do not compete with Bark on feature count. We are a different category. Feature parity is a trap.
- Do not shame parents who chose Family Link, Bark, or Qustodio. They were doing their best with what existed.

---

## 18. Brand

- **Name:** OpenWarden.
- **Tagline:** "Parental control. Open source. No surveillance."
- **Mark:** simple shield with a key inset. To be designed by a contributor; placeholder acceptable until then.
- **Color:** indigo. Calm, deliberate, not alarming. Avoid red and orange — they signal danger, and danger is the competitor's brand.
- **Tone:** matter-of-fact, kind, respectful of both parents and children. Never condescending. Never panicked.

---

## 19. Launch sequence

- **v1 ship:** "Show HN" post; cross-post to `r/Android`, `r/selfhosted`, `r/Parenting` (carefully), and `#foss` Matrix channels. Goal: 10 families, 50 GitHub stars, three meaningful contributor PRs.
- **v2 ship:** coordinated press pitch to Ars Technica and The Markup, polish F-Droid metadata, publish architecture writeup on the blog. Goal: 100 families.
- **v3 ship:** grant report-out, NLnet news mention, FOSDEM talk submission. Goal: 1,000 families and a second grant secured.

Each launch is small, deliberate, and honest. We are not trying to go viral. We are trying to be the parental control tool that families and funders can trust for the next decade.

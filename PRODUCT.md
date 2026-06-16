# Product

## Register

brand

## Users

Three audiences land on open-warden.com, in priority order:

1. **Privacy-minded parents** of kids aged 5-17 who want real control over a kid's
   Android phone but refuse SaaS subscriptions, cloud surveillance, or handing a
   child's data to Google / Bark / Qustodio. Technically comfortable enough to flash
   or provision a phone over USB, or willing to learn. They arrive skeptical of
   "parental control" marketing because most of it is fear-based and creepy. They
   are evaluating trust, not features.

2. **Open-source contributors and auditors** (Kotlin/Android devs, cryptographers,
   anti-stalkerware advocates from the Coalition Against Stalkerware / EFF, security
   researchers). They want to verify the claims, read the threat model, and decide
   whether to contribute. The project is pre-1.0: docs complete, code not yet
   started (~22 weekends to v1). Recruiting builders matters now.

3. **Kids, advocates, clinicians, and the "concerned other adult"** who need to
   understand what OpenWarden does and does not see. The site must be legible and
   non-threatening to a 14-year-old, not only to their parent.

Context of use: mostly desktop and mobile web, calm browsing (researching, not
rushed), often arriving from a Hacker News / Reddit / word-of-mouth link or a
search for "parental control without subscription / without spying."

## Product Purpose

OpenWarden is open-source, local-only, no-subscription parental control for Android.
Two paired apps (child DPC + parent phone) enforce policy locally over LAN, with no
server, no telemetry, and structurally no content monitoring. Apache 2.0, free
forever, grant-funded.

The website's job, pre-launch:
- Make the pledge unmistakable in the first viewport: no subscription, no server,
  no content monitoring, kid transparency, fail-closed.
- Convert skepticism into trust by *showing the boundaries*, including what
  OpenWarden deliberately cannot do (the anti-stalkerware stance is a feature, not
  fine print).
- Recruit contributors and grant funders; route to GitHub and the docs.
- Be legible to a kid and to a concerned adult, not just a parent.

Success = a visitor leaves able to say in one sentence what makes OpenWarden
different (control without surveillance), and the right ones click through to GitHub
or the docs.

## Brand Personality

Three words: **honest, warm, sturdy.**

- **Honest** over persuasive. State limits plainly. Name the threats we can't stop.
  Never fear-monger. Competitors sell anxiety ("see everything your kid does!");
  OpenWarden sells trust and restraint. The copy should read like a straight-talking
  engineer-parent, not a marketing funnel.
- **Warm** over clinical. The name "Warden" risks a carceral, prison-guard reading;
  the design must actively counter it with warmth and humanity. The kid is a person,
  not a suspect. Voice is plain-spoken, calm, a little dry, never cute.
- **Sturdy** over slick. This is plumbing built to last 5+ years on a two-person
  grant budget. It should feel well-engineered, durable, and self-evidently
  trustworthy, like a good field manual or a quality hand tool, not a venture-funded
  app trying to look expensive.

Emotional goal: relief and trust. A parent should exhale. "Finally, one that isn't
creepy and isn't trying to bill me."

## Anti-references

- **Fear-based parental-control marketing** (Bark, Qustodio, Life360 aesthetics):
  dark "tactical" surveillance dashboards, alarm-red alerts, "PROTECT YOUR CHILD
  FROM PREDATORS" panic copy, stock photos of worried parents and crying teens.
  OpenWarden is the opposite of this in tone and look.
- **Surveillance / security-product clichés:** navy-and-steel, padlock and shield
  icons, fingerprint scanners, glowing-shield hero graphics, "military-grade"
  language, hexagon-grid backgrounds.
- **Hacker / "we're so technical" costume:** neon-green-on-black terminal aesthetic,
  Matrix rain, monospace everywhere as a personality substitute. We are technical;
  we don't need to cosplay it.
- **Generic SaaS landing template:** centered hero with icon-title-subtitle card
  grid, cream-and-slate palette, gradient-text headline, "hero metric" big-number
  band, three identical feature cards. The AI-slop default.
- **Editorial-magazine reflex:** display-serif-italic + drop caps + ruled three-column
  broadsheet. Over-used; this brief is not a literary magazine.

## Design Principles

1. **Practice what you preach.** The site itself ships zero trackers, zero analytics,
   zero third-party surveillance scripts, no cookie banner because there are no
   cookies to consent to. Static, fast, self-hostable. The medium proves the message.
2. **Show the boundary, don't hide it.** The strongest trust signal is the list of
   things OpenWarden refuses to do. Make "what it will never see" as prominent and
   confident as any feature list. Transparency is the product.
3. **Two readers, one page.** Every kid-relevant claim must be true and legible to a
   14-year-old reading over a parent's shoulder. Never position the parent as an
   omniscient watcher; frame OpenWarden as an inspectable tool.
4. **Earn trust with specifics.** Ed25519, sealed-box, BIP39, "enforced locally,
   works offline." Concrete mechanisms beat adjectives. Link claims to the actual
   docs and source so they're verifiable, not assertions.
5. **Calm confidence, no panic.** Never use a child's safety as a fear lever. The
   pitch is relief, durability, and respect, not threat. If a line would scare a
   parent into clicking, cut it.

## Accessibility & Inclusion

- WCAG 2.2 AA minimum: contrast, focus-visible, keyboard paths, semantic landmarks,
  reduced-motion support for every animation.
- Must read well at 200% zoom and on small phones (parents browse on the device
  they're worried about).
- Legible to a non-technical parent and to a kid: plain language first, jargon
  linked and explained, never required to grasp the core promise.
- No motion that blocks comprehension; honor `prefers-reduced-motion`.
- Crisis resources (988, Childhelp, Crisis Text Line, Coalition Against Stalkerware)
  must be present, findable, and never gated behind interaction.

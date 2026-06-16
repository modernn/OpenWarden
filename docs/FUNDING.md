# Funding strategy

**Goal:** OpenWarden costs nothing for parents, ever. Development sustained by grants + voluntary donations. No paid tiers, no SaaS revenue, no telemetry licensing.

## Constraints (non-negotiable)

- No subscription paid by parents — ever
- No advertising — anywhere
- No data brokerage — under no circumstances
- No "free for individuals, paid for enterprise" upsells. Family use *is* the only use case.
- Optional features that have hosting costs (e.g. relay nodes) must either be self-hostable for free, or covered by the project (not user-paid)

## Grant landscape (prioritized)

### Tier 1 — best fit, recurring open calls

**NLnet Foundation / NGI Zero** ([nlnet.nl](https://nlnet.nl))
- **NGI Zero Privacy & Trust Enhancing Technologies** — direct match
- **NGI Zero Commons Fund** — open digital commons
- Typical grant: €5,000 – €50,000
- Funded: Briar, Delta Chat, Element, Tor, F-Droid, Veilid
- Open calls every 2 months
- Application form is *short* — they value substance over polish
- **Action**: apply v1 ready, ~€20k ask for v2 development

**Open Technology Fund (OTF)** ([opentech.fund](https://www.opentech.fund))
- **Free and Open Source Software Sustainability Fund (FOSSSF)** — maintenance + ongoing dev
- **Internet Freedom Fund** — privacy + circumvention
- $10k – $300k range
- Funded: Tor, Signal, WireGuard, Briar
- Strong fit because of E2EE + privacy core
- **Action**: apply once v1 ships and has documented users (even just one family)

### Tier 2 — strong fit, less frequent calls

**Mozilla Foundation MOSS / Mozilla Tech Fund** ([foundation.mozilla.org](https://foundation.mozilla.org))
- Open Source Support Awards for privacy-focused tools
- Trustworthy AI program — relevant for the local AI module
- $5k – $50k typical

**Knight Foundation Public Interest Technology**
- Kid safety angle resonates
- $25k – $200k typical
- Less FOSS-native; needs framing toward "safer internet for kids"

**Ford Foundation Public Interest Technology**
- Digital rights, with kid-safety overlap
- $25k+ typical
- Application is heavy on narrative

### Tier 3 — possibilities

- **Sloan Foundation** Digital Infrastructure
- **OpenSSF Alpha-Omega** — for hardening / security audit funding once project has users
- **EU Horizon Europe NGI** umbrella (NLnet is a subgrantee, but direct calls exist)
- **Internet Society Foundation** — Beyond the Net grants

### Tier 4 — last resort or complementary

- **GitHub Sponsors** — for ongoing maintainer support
- **Open Collective** — transparent treasury, public ledger
- **Patreon** — only for general operating costs, not for features
- One-time donations from families that want to give back

## Application sequencing

1. **Pre-v1**: cite this funding doc as the model. Don't apply yet — grant panels want a working prototype.
2. **v1 ship + 1 deployed family (Oliver)**: apply to **NLnet NGI Zero Privacy & Trust Enhancing Technologies**. Target €20k for v2 work: store-and-forward, multi-transport, Flutter desktop, local AI screenshot classifier.
3. **v2 ship + 3 families demoed**: apply to **Mozilla MOSS** and **OTF FOSS Sustainability Fund** in parallel.
4. **v3 ship + 10 families**: apply to **Knight Foundation** with a kid-safety narrative.
5. Continuous: GitHub Sponsors + Open Collective. Cap personal stipend transparently.

## Grant-killer features (don't add these)

Things that make grants reject:
- Proprietary deps (mandatory Google Play, mandatory Apple iCloud)
- Closed model weights without disclosure
- Mandatory telemetry "for product improvement"
- Privacy policy with carve-outs ("we won't sell unless...")
- Subscription anywhere in the stack
- Auto-update servers you can't fork (depend on signed releases users can host themselves)

## Treasury principles

- Public ledger via **Open Collective** — every grant + every expense visible
- Spending categories: dev time, security audits, infrastructure (relay nodes, CI), accessibility audits, translations, design
- No salaries beyond modest stipends; volunteer-first
- If funded with surplus, fund **independent security audit** before pushing more features

## "What if a grant requires X we don't want?"

Easy rule: if accepting a grant requires us to add telemetry, paid tiers, or proprietary deps, we decline. The constraints listed at top are non-negotiable regardless of funding.

## Long-term sustainability

Realistic path for a project like this:
- Year 1-2: grants cover ~50% of one maintainer's time. Rest is volunteer.
- Year 3+: if widely deployed (~10k families), Open Collective monthly recurring + GitHub Sponsors can sustain.
- If not widely deployed: project remains useful for the families who use it. Apache 2.0 means it can't die — anyone can fork.
- No exit strategy: this isn't a startup. The product is the codebase + the community.

## Funder pitch in one paragraph

> OpenWarden is an open-source parental control system designed to keep families safe without surveillance, subscriptions, or vendor lock-in. It runs as a Device Owner DPC on stock Android, enforces policy locally, communicates via store-and-forward over multiple transports, and uses on-device AI to flag concerning content without exfiltrating it. Apache 2.0. Built by a parent for his own kid; usable by anyone with a Pixel and a weekend. We need [$X / €X] to fund v2 development covering [features]. No subscription path; sustained by grants and voluntary contributions on Open Collective.

# OpenWarden

Open-source, local-only parental control for Android. Apache 2.0. Free forever.

**Status:** docs complete, code not yet started. v1 target: ~22 weekends (5-6 months part-time).

## What it is

OpenWarden is two paired apps that let a parent control what their kid does on an Android phone — without surveillance, subscriptions, or cloud servers.

- **Child device:** stock Android, by **enforcement tier** ([ADR-023](docs/adr/023-enforcement-floor-tiers.md)): **Tier 1 = Pixel-class** (full anti-bypass enforcement); **Tier 2 = specific tested OEM models** (Samsung Galaxy S22+/A55+/Note, OnePlus 11+, Motorola Edge 50+, Nothing Phone 2+ — see [ADR-023](docs/adr/023-enforcement-floor-tiers.md) for the exact list; supported, but with *documented enforcement gaps* — OEM-preloaded apps can bypass the launch allowlist, factory-reset/unlock protection is best-effort, no StrongBox guarantee); **Tier 3 = other or older Android 13+ devices** (best-effort, no anti-bypass warranty). Strongest on Pixel; honest about the rest. Runs the OpenWarden DPC (Device Owner) app. Enforces policy locally — works offline.
- **Parent device:** Android phone (v1) or iOS phone (v1, foreground-poll model). Edits policy, reviews activity, holds recovery phrase.
- **Communication:** LAN-first signed REST. Both phones on the same Wi-Fi = direct sync. (v2: store-and-forward over Iroh for remote sync.)

For ages 5-17. Defaults shift per life stage (5-7 tight, 8-10 standard, 11-13 earning trust, 14-17 teen mode).

## Pledge

- **No subscription.** Forever free for parents. Funded by grants + voluntary donations.
- **No server.** No backend, no telemetry, no analytics. Your family's data stays on your family's devices.
- **No content monitoring.** Messages, photos, audio, voice calls — never read or sent. We physically cannot. Stalkerware boundary lives here.
- **Kid transparency.** Every monitored category visible on the kid's phone — kid can see what is and isn't tracked.
- **No proprietary dependencies.** Every required component is FOSS. Optional convenience integrations (Cloudflare 1.1.1.3 family DNS, Tailscale, ntfy.sh) clearly marked.
- **No vendor lock-in.** Open API, signed bundle format anyone can implement, recovery phrase that exits cleanly.

## What v1 ships

- DPC-enforced app allowlist + blocklist
- Time windows (bedtime hard-lock, school-time restriction)
- Default blocklist: Discord, Roblox, Snap (embedded-WebView leak prevention) — parent can override w/ warning
- Local DNS resolver on child device → video-session tracking by CDN (no titles, no content)
- Per-app daily video-time caps enforced at DNS layer
- Signed policy bundles (Ed25519, replay-protected via monotonic `policy_seq`)
- Sealed-box event log encrypted to parent's X25519 pubkey (kid can't read own log)
- BIP39 24-word recovery phrase + printable PDF
- 7-day time-locked self-decommission ("dad got hit by a bus" backstop)
- Kid transparency screen ("What does OpenWarden see?")
- "Ask dad" request flow + co-parent visibility
- ~30 core DPC defenses ([`docs/DEFENSES.md`](docs/DEFENSES.md))

## What v2 adds

- On-device NSFW image classifier (Falconsai, Apache 2)
- DNS filter polish + custom URL categories
- Install-approval flow
- iOS push via ntfy.sh content-free wake-up doorbell
- Tailscale + WireGuard transports
- Multi-child UI
- Graduated privilege slider

## What v3 adds

- Geofencing (home, school)
- Gemma Nano on-device text classifier (opt-in, opt-in per app)
- CallScreeningService + DPC contacts allowlist
- Time-bank / earned screen-time tokens

## Pick OpenWarden if

- You're a parent who wants meaningful control over a kid's phone
- You don't want a SaaS subscription
- You don't trust Google / Bark / Qustodio with your kid's data
- You're comfortable provisioning a phone via USB (or willing to use the desktop provisioner v2)
- You're OK with the enforcement-tier model: **full anti-bypass enforcement on Pixel-class (Tier 1)**, **specific tested Tier-2 OEM models supported with documented gaps** (Samsung/OnePlus/Motorola/Nothing — exact models in [ADR-023](docs/adr/023-enforcement-floor-tiers.md)), and **other or older Android best-effort with no anti-bypass warranty (Tier 3)**

## Don't pick OpenWarden if

- You want to read your kid's messages (we don't do this; that's stalkerware)
- You want a turnkey "blocks all bad content" subscription product (use Bark)
- You need it on iPhone child devices (Apple's Family Controls covers this OS-side)

## License

Apache 2.0. See [`LICENSE`](LICENSE).

## Docs index

- **Start here:** [`docs/ONBOARDING.md`](docs/ONBOARDING.md) — parent install journey
- **What it does:** [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- **What it doesn't do:** [`docs/PARENT_AS_ADVERSARY.md`](docs/PARENT_AS_ADVERSARY.md) — anti-stalkerware ethics
- **Threats + defenses:** [`docs/ATTACKS.md`](docs/ATTACKS.md), [`docs/DEFENSES.md`](docs/DEFENSES.md)
- **Wire format:** [`docs/PROTOCOL.md`](docs/PROTOCOL.md), [`docs/CRYPTO.md`](docs/CRYPTO.md)
- **Provisioning:** [`docs/PROVISIONING_V2.md`](docs/PROVISIONING_V2.md)
- **Privacy + legal:** [`docs/PRIVACY_LEGAL.md`](docs/PRIVACY_LEGAL.md)
- **Roadmap:** [`docs/ROADMAP.md`](docs/ROADMAP.md)
- **Contribute:** [`CONTRIBUTING.md`](CONTRIBUTING.md), [`docs/GOVERNANCE.md`](docs/GOVERNANCE.md), [`docs/DESIGN_PARADIGMS.md`](docs/DESIGN_PARADIGMS.md)
- **Decisions:** [`docs/adr/`](docs/adr/) — Architecture Decision Records

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Sign-off (DCO) required. Code of Conduct enforced. No paid tiers, no telemetry, no content monitoring — those don't merge.

## Crisis resources for kids

If you or someone you know is in danger:
- **988** (US Suicide & Crisis Lifeline)
- **741741** (Crisis Text Line)
- **1-800-422-4453** (Childhelp National Child Abuse Hotline)

These are always reachable from any Android phone, including those running OpenWarden. Emergency dialer is OS-floor, never blocked.

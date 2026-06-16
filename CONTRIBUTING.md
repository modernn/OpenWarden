# Contributing

Apache 2.0. By submitting a PR, you confirm DCO sign-off (`git commit -s`).

## Non-negotiables
1. **No subscription path.** OpenWarden will never gate features behind payment. PRs introducing paid services, paid tiers, or paid components in the required path will not merge.
2. **No telemetry, no analytics, no phone-home.** Crash reports are opt-in only and never on by default.
3. **No required third-party SaaS.** Optional convenience integrations (Tailscale, NextDNS, etc.) are fine if they remain truly optional and a self-hosted / FOSS alternative ships alongside.
4. **Threat model rules.** Read [`docs/SECURITY.md`](docs/SECURITY.md). Features that weaken the model get pushed back.

## What we want
- Hardening of the DPC (more `DevicePolicyManager` coverage)
- Better cross-platform parity in the Flutter parent app
- Self-hostable transport docs (WireGuard, mesh VPNs, LAN-only)
- Tests, especially for the policy bundle signature/verification path
- Translations
- Better "Why am I blocked?" UX on the child device

## What we'll push back on
- Content surveillance features (reading messages, screenshots of social apps). OpenWarden is a *control* tool, not a *monitoring* tool. Stalkerware concerns live here.
- Anything that requires Google Play services to function
- Anything that requires an account on a vendor's cloud
- Multi-tenant SaaS deployment patterns

## Setup
1. Clone
2. Child Android: open `child-android/` in Android Studio, sync, run on a dev device (NOT the real kid phone)
3. Parent Flutter: `cd parent-flutter && flutter pub get && flutter run`

## Style
- Kotlin: ktlint defaults
- Dart: `flutter format`
- Commits: imperative, conventional ("feat:", "fix:", "docs:") encouraged but not required

## Bench testing the DPC
Set up a **second Pixel** (or emulator with DeviceAdmin) and run the provisioning flow in `docs/PROVISIONING.md`. Never test new policy changes on a real kid's phone first.

# ADR-002: iOS parent app in v1

Status: Accepted
Date: 2026-06-15

## Context

Initial scope considered cutting iOS parent app to v2 to reduce v1 surface area. User pushed back: iOS parent app must be in v1.

Most US families have at least one iOS device; v1-Android-only parent would exclude a large fraction of potential adopters. KMP makes adding iOS cheaper than a separate native build.

## Options

1. **Android parent v1, iOS v2.** Smaller v1 scope. iOS users locked out for ~6 months.
2. **iOS parent v1 alongside Android.** Larger v1 scope (~+3 weekends). KMP shared module covers most code; iosApp/ is the Xcode shell.
3. **iOS parent v1 via PWA / web shim.** Bad: iOS Safari background limits, no APNs from PWA, no Keychain access.

## Decision

Adopt **option 2: iOS parent v1**.

iOS parent ships as `parent-kmp/iosApp/` (Xcode + SwiftUI) consuming the shared KMP `:shared` module via SKIE. Distribution = TestFlight v1.

**iOS push model v1:** open-the-app + `BGAppRefreshTask` opportunistic sync. NO APNs push v1. Parent opens app to see pending requests. Documented latency: up to 6h.

**iOS push model v2:** ntfy.sh as content-free wake-up doorbell.

## Consequences

**Good:**
- iOS users not excluded.
- KMP `:shared` module proves out cross-platform protocol parity from day 1 (test vectors run on both).
- TestFlight = real Apple-signed distribution, no sideload friction.
- Sets up v2/v3 iOS push improvement path.

**Bad:**
- $99/yr Apple Developer Program fee — the only paid line item in the project. Documented in DISTRIBUTION.md §11.
- iOS background sync is restrictive (BGAppRefreshTask fires when iOS feels like it). Documented in ONBOARDING.md.
- Snapshot tests + accessibility tests must cover SwiftUI side.
- F-Droid does not distribute iOS — TestFlight + future App Store only.

## Cross-refs

- [`docs/PARENT_KMP_STRUCTURE.md`](../PARENT_KMP_STRUCTURE.md)
- [`docs/DISTRIBUTION.md`](../DISTRIBUTION.md) §11 (TestFlight tension)
- [`docs/NOTIFICATIONS.md`](../NOTIFICATIONS.md) §4 (iOS open-the-app model)
- [`docs/ONBOARDING.md`](../ONBOARDING.md) (parent install steps)

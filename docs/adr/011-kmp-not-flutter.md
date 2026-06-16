# ADR-011: Kotlin Multiplatform for parent app, not Flutter

Status: Accepted (supersedes initial Flutter scaffolding)
Date: 2026-06-15

## Context

Initial scaffolding used Flutter for cross-platform parent app (Android + iOS + macOS + Windows). Research at [`research/02-app-stack.md`](../research/02-app-stack.md) §2 found Flutter not the best choice for this project's specific needs.

## Options

1. **Flutter** — single codebase 4 platforms. `flutter_sodium` dead since 2022; libsodium parity unsolved. Material-on-iOS uncanny valley. Platform channels needed for BLE/NFC/BGTaskScheduler anyway.
2. **React Native** — similar tradeoffs; Hermes JS adds risk surface.
3. **Kotlin Multiplatform (KMP)** w/ Compose Android + SwiftUI iOS — shared `:proto` w/ child DPC's Kotlin codebase. `ionspin/kotlin-multiplatform-libsodium` solves crypto parity. SKIE for Swift interop.
4. **Native twice** (Kotlin Android + Swift iOS) — best feel, duplicated work.

## Decision

Adopt **option 3: Kotlin Multiplatform**.

Layout per [`docs/PARENT_KMP_STRUCTURE.md`](../PARENT_KMP_STRUCTURE.md):
```
parent-kmp/
├── shared/              # KMP: protocol, crypto, sync, state
├── androidApp/          # Compose
└── iosApp/              # Xcode + SwiftUI via SKIE
```

Composite Gradle build w/ `:proto` shared between `child-android` and `parent-kmp/shared`.

V1 parent platforms: Android + iOS (per ADR-002). Desktop (macOS/Windows) deferred to v2+ via Compose Multiplatform desktop OR separate app.

## Consequences

**Good:**
- Crypto parity solved (ionspin libsodium works on both Android + iOS w/ bit-identical output).
- Native UI on each platform (Compose / SwiftUI).
- F-Droid reproducible builds simpler (Kotlin/Gradle, no Skia engine quirks).
- Code sharing w/ child-android: ~60-80% of protocol code reused.
- TestFlight + Xcode signing normal for iOS.

**Bad:**
- KMP-Compose-MP is still maturing; some Compose APIs only on android-target.
- iOS Kotlin/Native ergonomics with collections, suspend functions (SKIE solves most).
- SKIE introduces additional build-pipeline complexity.
- Sunk cost in `parent-flutter/` scaffolding (~1 day discarded — small).

## Migration

Phase 0: delete `parent-flutter/` directory.
Phase 1: scaffold `parent-kmp/` per [`docs/PARENT_KMP_STRUCTURE.md`](../PARENT_KMP_STRUCTURE.md).

## Cross-refs

- [`docs/PARENT_KMP_STRUCTURE.md`](../PARENT_KMP_STRUCTURE.md)
- [`research/02-app-stack.md`](../research/02-app-stack.md) §2
- ADR-002 (iOS parent v1)

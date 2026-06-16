# iosApp — built on macOS (deferred)

This directory holds the SwiftUI parent app. **It is not built on Windows.**
OpenWarden is developed Android-first; the iOS side is picked up later on a Mac
clone (see the dev-platform plan).

## When you clone on a Mac

1. The Kotlin/Native iOS targets in `:shared` and `:proto` activate automatically
   — they are host-gated to a macOS host (`HostManager.hostIsMac`), so no Gradle
   edits are needed. SKIE also applies only on macOS.
2. Produce the framework:
   ```
   ./gradlew :shared:assembleOpenWardenSharedXCFramework
   ```
3. Create/open the Xcode project here (`iosApp.xcodeproj`) and consume the
   XCFramework via SPM, per [`../../docs/PARENT_KMP_STRUCTURE.md`](../../docs/PARENT_KMP_STRUCTURE.md) §6–§7.
   The Swift sources in `iosApp/` are scaffold stubs to wire up first.

The `.xcodeproj` is intentionally **not** committed yet — it is generated on the
Mac that owns the iOS build, to avoid a half-configured project tracked from a
machine that can't open it.

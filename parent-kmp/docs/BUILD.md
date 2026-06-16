# Building parent-kmp

Composite build with `../child-android` is **deferred** (see `settings.gradle.kts`);
`parent-kmp` builds standalone for now.

## Prerequisites
- JDK 17+ on PATH (build verified on the default JDK 17; JDK 21 is used for the
  F-Droid reproducible release pipeline — see `REPRODUCIBILITY.md`).
- Android SDK with platform 35 + build-tools 35. Set `sdk.dir` in
  `local.properties` (not committed) or `ANDROID_HOME`.
- Gradle wrapper is committed — use `./gradlew`.

## Android (works on Windows / Linux / macOS)
```
./gradlew :proto:build              # shared wire types (KMP), runs tests
./gradlew :shared:build             # protocol/crypto/sync/policy (KMP), runs tests
./gradlew :androidApp:assembleDebug # Compose parent app APK
```
APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

## iOS (macOS only — deferred)
The iOS Kotlin/Native targets and SKIE are host-gated to macOS and activate
automatically there. See [`../iosApp/README.md`](../iosApp/README.md) and
[`PARENT_KMP_STRUCTURE.md`](PARENT_KMP_STRUCTURE.md) §6–§7.
```
./gradlew :shared:assembleOpenWardenSharedXCFramework   # macOS host
```

## Tests
```
./gradlew :proto:allTests :shared:allTests
```
Protocol parity vectors run in `commonTest` on every target (PARENT_KMP_STRUCTURE.md §10).

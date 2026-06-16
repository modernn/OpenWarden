# Parent App — Kotlin Multiplatform Structure

Status: **locked.** This document codifies the parent-app stack decision and the
exact project layout the team will scaffold in `parent-kmp/`. It supersedes the
stub `parent-flutter/` directory, which will be deleted once `parent-kmp/`
compiles green on both targets.

**Why KMP and not Flutter:** see §2 of `openwarden-app-research.md`. Summary: the
hard parts of this app (Keychain/Keystore, BLE/NFC, BGTaskScheduler/WorkManager,
libsodium parity, F-Droid reproducible builds) are all native APIs that Flutter
re-imposes a platform channel boundary over. The shared parts (signed log
replication, policy CRDT, Ed25519/X25519, bundle canonicalization) are pure
Kotlin. KMP shares 60–80% of those as actual code, gives the child DPC a free
`:proto` import, and produces a native SwiftUI app for TestFlight with no
uncanny-valley UI.

**Locked decisions** (do not re-litigate without an ADR):
- Parent app = Kotlin Multiplatform; Compose on Android, SwiftUI on iOS.
- License Apache 2.0 throughout. Every dependency must be Apache 2 / MIT / BSD /
  ISC / MPL-2.0 compatible at the file level. **No GPL.**
- A `:proto` Kotlin module is shared verbatim between this parent app and the
  child DPC (`child-android/`). One source of truth for wire types.
- libsodium via [`ionspin/kotlin-multiplatform-libsodium`](https://github.com/ionspin/kotlin-multiplatform-libsodium).
- F-Droid reproducible Android builds. Plan to publish before Google's
  Sept 2026 developer-verification deadline.
- iOS v1 distribution = TestFlight. Apple Developer Program ($99/yr) is the
  single paid line item across OpenWarden; accepted as the cost of sideload-safe
  iOS distribution. Document for purists in `FUNDING.md`.
- iOS v1 = **no push.** Open-the-app model + opportunistic `BGAppRefreshTask`.
  ntfy.sh doorbell ships in v2, owned APNs relay is v3.

---

## 1. Module layout

```
parent-kmp/
├── shared/                         # KMP library: protocol, crypto, sync, policy
│   ├── src/
│   │   ├── commonMain/kotlin/com/openwarden/parent/
│   │   │   ├── proto/              # signed log entry types, bundle format, canonical JSON
│   │   │   ├── crypto/             # libsodium wrappers, HKDF, Argon2id, sealed box
│   │   │   ├── sync/               # store-and-forward replication logic, log merge
│   │   │   ├── policy/             # editor model: allowlist, windows, restrictions
│   │   │   └── state/              # cross-platform AppState, StateFlows, repositories
│   │   ├── commonTest/kotlin/      # JVM-runnable tests; protocol test vectors live here
│   │   ├── androidMain/kotlin/com/openwarden/parent/
│   │   │   ├── crypto/             # AndroidKeyStore wrapper, StrongBox attestation
│   │   │   ├── transport/          # OkHttp/ktor-cio mDNS LAN client, WorkManager wrapper
│   │   │   └── platform/           # Context-bound singletons, file paths
│   │   ├── androidUnitTest/        # Robolectric tests for Android-only bits
│   │   ├── androidInstrumentedTest # device tests for Keystore/WorkManager
│   │   ├── iosMain/kotlin/com/openwarden/parent/
│   │   │   ├── crypto/             # Keychain via Security framework, Secure Enclave
│   │   │   ├── transport/          # NSURLSession/ktor-darwin, NSNetService mDNS
│   │   │   └── background/         # BGAppRefreshTask scheduler bindings
│   │   └── iosTest/kotlin/         # XCTest-runnable from konanTest
│   └── build.gradle.kts
├── proto/                          # Standalone KMP lib, published; consumed by child DPC
│   ├── src/commonMain/kotlin/com/openwarden/proto/
│   │   ├── Bundle.kt               # PolicyBundle, EventEntry, AckEntry
│   │   ├── Canonical.kt            # deterministic JSON serializer used by signer
│   │   └── Versioning.kt           # policy_bundle_format_version negotiation
│   └── build.gradle.kts            # targets: jvm, android, iosArm64, iosSimulatorArm64
├── androidApp/                     # Compose Material 3 app
│   ├── src/main/kotlin/com/openwarden/parent/android/
│   │   ├── OpenWardenApp.kt           # Application; libsodium init; DI graph
│   │   ├── MainActivity.kt
│   │   ├── ui/
│   │   │   ├── pair/               # PairScreen.kt (QR scan/show + emoji confirm)
│   │   │   ├── dashboard/          # DashboardScreen.kt (status pill, today's usage)
│   │   │   ├── policy/             # AllowlistEditor.kt, WindowsEditor.kt
│   │   │   ├── requests/           # PendingRequestsScreen.kt
│   │   │   └── settings/           # Backup/restore, factory-reset-child
│   │   ├── nav/                    # Compose Navigation graph
│   │   └── theme/                  # Material 3 tokens; high-contrast variant
│   └── build.gradle.kts
├── iosApp/                         # Xcode project; SPM-consumes :shared
│   ├── iosApp/
│   │   ├── OpenWardenApp.swift        # @main, libsodium init, AppDelegate w/ BGTask reg
│   │   ├── Views/
│   │   │   ├── PairView.swift
│   │   │   ├── DashboardView.swift
│   │   │   ├── PolicyEditorView.swift
│   │   │   └── SettingsView.swift
│   │   ├── ViewModels/             # @ObservableObject wrappers around shared StateFlows
│   │   └── Info.plist              # UIBackgroundModes: fetch, processing
│   ├── iosApp.xcodeproj/
│   └── Configuration/Config.xcconfig
├── build.gradle.kts                # root: KMP, Kotlin, Compose plugins
├── settings.gradle.kts             # includeBuild('../child-android') for composite build
├── gradle/libs.versions.toml       # version catalog (see §13)
├── gradle.properties               # org.gradle.parallel=false for reproducible builds
└── docs/
    ├── BUILD.md                    # how to build each target
    └── REPRODUCIBILITY.md          # F-Droid notes
```

**Why this exact shape:** `:shared` holds *everything that isn't platform UI.*
`:proto` is split out so the child DPC can depend on a small, stable, frozen
artifact without dragging ktor/libsodium into the DPC's classpath. The Xcode
project lives in `iosApp/` because Xcode wants to own its directory; the Gradle
build treats it as an opaque external build via `xcodebuild` for CI.

---

## 2. Gradle configuration

### `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "parent-kmp"
include(":shared", ":proto", ":androidApp")

// Composite build: ../child-android consumes :proto directly during dev.
includeBuild("../child-android") {
    dependencySubstitution {
        substitute(module("com.openwarden:proto")).using(project(":proto"))
    }
}
```

### Root `build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.skie) apply false
}
```

### `shared/build.gradle.kts` (abridged)

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.skie)
}

kotlin {
    androidTarget()
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { ios ->
        ios.binaries.framework {
            baseName = "OpenWardenShared"
            isStatic = true
            export(project(":proto"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":proto"))
            implementation(libs.kotlinx.coroutines)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.libsodium.bindings)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentnegotiation)
            implementation(libs.ktor.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.security.crypto)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.openwarden.parent.shared"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
}
```

### `androidApp/build.gradle.kts` (abridged)

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.openwarden.parent.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.openwarden.parent"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    // Reproducible build flags — see REPRODUCIBILITY.md
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    androidComponents {
        onVariants { variant ->
            variant.packaging.jniLibs.excludes.add("**/lib/*/libsodiumjni.so") // pull from source
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.bom)
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation)
    implementation(libs.molecule.runtime)
    implementation(libs.koalaplot.core)
}
```

### `gradle/libs.versions.toml`

See §13 for the full pinned matrix.

---

## 3. ionspin/kotlin-multiplatform-libsodium binding

**Dependency:**
```toml
libsodium-bindings = "com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings:0.9.2"
```
(ISC license — Apache-2.0 compatible.)

**Init pattern** — called once in `OpenWardenApp.onCreate()` on Android and in
`@main` on iOS *before any crypto call*:

```kotlin
suspend fun bootstrapCrypto() {
    LibsodiumInitializer.initialize()
}
```

The Android JNI binary ships in the maven artifact; for F-Droid we re-build
from source (see §8). The iOS variant is a Kotlin/Native static library — no
JNI involved on that side.

### Usage examples (all in `shared/commonMain/crypto/`)

```kotlin
// Identity keypair — generated once at first launch.
fun generateIdentity(): SignatureKeyPair =
    SignatureKeyPairGenerator.generateSignatureKeyPair()

// Sign a canonicalized policy bundle.
fun signBundle(privateKey: UByteArray, canonical: UByteArray): UByteArray =
    SignatureDetached.detached(canonical, privateKey)

// Verify a signed bundle from the child or remote.
fun verifyBundle(pub: UByteArray, sig: UByteArray, canonical: UByteArray): Boolean =
    runCatching {
        SignatureDetached.verifyDetached(sig, canonical, pub)
        true
    }.getOrDefault(false)

// Sealed box for one-shot pairing payload.
fun sealForChild(recipientPub: UByteArray, payload: UByteArray): UByteArray =
    SealedBox.seal(payload, recipientPub)

// HKDF for emoji-confirmation bytes derived from (parentPub || childPub).
fun pairingFingerprint(parentPub: UByteArray, childPub: UByteArray): UByteArray =
    GenericHash.genericHash(parentPub + childPub, 6u)

// Argon2id for any password-protected mnemonic backup.
fun deriveKey(passphrase: String, salt: UByteArray): UByteArray =
    PasswordHash.pwhash(
        outputLength = 32,
        password = passphrase,
        salt = salt,
        opsLimit = PasswordHash.OPSLIMIT_INTERACTIVE,
        memLimit = PasswordHash.MEMLIMIT_INTERACTIVE,
        algorithm = PasswordHash.crypto_pwhash_ALG_ARGON2ID13,
    )
```

### Known gotchas
- **Apple Silicon hosts:** the iOS simulator target must include both
  `iosSimulatorArm64` and `iosX64` if you ever expect to run on Intel CI.
  Build `iosSimulatorArm64` for normal local dev on M-series Macs.
- **Android JNI loading:** the artifact's `.so` files extract on first call.
  On rooted/AOSP devices without `lib*` extraction, force-load with
  `System.loadLibrary("sodiumjni")` in your `Application.onCreate()`. Not
  needed on stock Pixels.
- **Threading:** the library is internally thread-safe post-init, but
  `LibsodiumInitializer.initialize()` itself must complete before any other
  call; we suspend startup on it.
- **Versioning:** pin exact version. The library has had silent API renames
  between 0.8 and 0.9.

---

## 4. Sharing `:proto` with `child-android`

The child DPC is already Kotlin. Two options for sharing the wire types:

**Option X — Composite Gradle build (recommended).** `parent-kmp/settings.gradle.kts`
declares `includeBuild("../child-android")`. The child DPC's Gradle build
substitutes its `com.openwarden:proto` dependency with the project-typed
`:proto` from the parent KMP build. One source tree, one source of truth, no
publish step in dev.

**Option Y — Maven Local / GitHub Packages.** Publish `:proto` as a versioned
artifact; child DPC consumes via standard `implementation("com.openwarden:proto:X")`.
Useful for downstream consumers we don't control, painful for everyday dev
where a proto change needs both sides updated atomically.

**Decision: Option X for dev, Option Y for CI release.** The composite build
keeps the day-to-day loop tight: change `Bundle.kt`, both apps recompile
together, test vectors run on both. For CI release builds (F-Droid, TestFlight,
GitHub Releases) we publish `:proto` to GitHub Packages with a semver tag so
the released DPC and parent are pinned to identical proto versions and the
parent can warn "child needs update" when format versions diverge (see
`policy_bundle_format_version` in `Versioning.kt`).

Trade-off: composite builds require both repos on disk simultaneously, which is
fine for a single-team monorepo-adjacent setup and matches how OpenWarden is
actually developed today. If a third party ever forks the DPC, they fall back
to Option Y automatically.

---

## 5. Compose on Android

This is **native Android Compose**, not Compose Multiplatform with an iOS
target. Compose-MP-iOS is still maturing (Material 3 widgets feel close to
native but the system bar / a11y stories are not at parity), and we already
own SwiftUI on iOS — there's no incremental gain to running Compose on iOS.

Stack:
- **Material 3** with a custom token set in `theme/`. One default scheme +
  a high-contrast variant.
- **Navigation:** Compose Navigation 2.7+, single `NavHost` in `MainActivity`.
  Voyager was considered and rejected — Navigation 2.7 added type-safe routes,
  which closes Voyager's main advantage and avoids an extra dependency.
- **State management:** Molecule + StateFlow. Each screen owns a
  `@Composable fun presenter()` that returns a `Model` via `moleculeFlow`. No
  Redux, no Mobius, no MVI framework. The shared module already exposes the
  domain state as `StateFlow`s from `commonMain/state/`; the presenter glues
  user intents to those flows.
- **Charts (today's usage tab):** `koalaplot-core` — Apache 2, KMP-friendly,
  minimal API surface.
- **QR scanning:** `mlkit-vision` for v1 (free, accurate) with a documented
  plan to migrate to ZXing for the F-Droid build (Play Services-free).

---

## 6. SwiftUI on iOS

The Xcode project lives in `iosApp/` and consumes `:shared` as a
**Swift Package** via the SPM integration that `:shared` exposes via the
KMP plugin's `kotlinArtifacts { ... }` block — generating an `XCFramework`
plus a `Package.swift` checked into the repo.

**SKIE (touchlab/SKIE):** mandatory. Without SKIE the generated Swift API
exposes Kotlin collections as `NSArray`-typed `KotlinArray<X>` blobs and
`suspend` functions as ugly `__attribute__((swift_async))` shims. SKIE rewrites:
- `suspend fun` → real Swift `async` / `await`
- `Flow<T>` → `AsyncSequence<T>`
- sealed classes → Swift enums with associated values
- `List<T>` → Swift `Array<T>`

```kotlin
plugins { alias(libs.plugins.skie) }
skie {
    features {
        group { FlowInterop.Enabled(true) }
    }
}
```

**Concrete SwiftUI usage** — the `PolicyEditor` `StateFlow<PolicyModel>` lives
in `:shared`. SwiftUI binds it through an `@ObservableObject` wrapper:

```swift
import OpenWardenShared

@MainActor final class PolicyEditorViewModel: ObservableObject {
    @Published var model: PolicyModel
    private let editor: PolicyEditor

    init(editor: PolicyEditor = SharedDi.shared.policyEditor()) {
        self.editor = editor
        self.model = editor.state.value
        Task {
            for await m in editor.state {       // SKIE: Flow -> AsyncSequence
                self.model = m
            }
        }
    }

    func toggle(pkg: String) async {
        try? await editor.toggleAllowlist(pkg: pkg)   // SKIE: suspend -> async
    }
}

struct PolicyEditorView: View {
    @StateObject var vm = PolicyEditorViewModel()
    var body: some View {
        List(vm.model.installedApps, id: \.packageName) { app in
            Toggle(app.label, isOn: Binding(
                get: { vm.model.allowlist.contains(app.packageName) },
                set: { _ in Task { await vm.toggle(pkg: app.packageName) } }
            ))
        }
    }
}
```

Zero Kotlin/Native ergonomic warts visible to the SwiftUI author.

---

## 7. iOS build pipeline

1. `./gradlew :shared:assembleOpenWardenSharedXCFramework` — produces
   `shared/build/XCFrameworks/release/OpenWardenShared.xcframework`.
2. Xcode build: `iosApp.xcodeproj` consumes the local SPM package at
   `shared/Package.swift`. SKIE-rewritten headers ship inside the XCFramework.
3. `xcodebuild -scheme iosApp -configuration Release archive` →
   `xcodebuild -exportArchive` → signed `.ipa`.
4. `xcrun altool --upload-app` to TestFlight, or the new
   `xcrun notarytool` flow if going Mac-distributed sideload.

**TestFlight distribution:** Apple Developer Program is the only paid line
item. Acknowledge in `FUNDING.md` and `docs/SECURITY.md`. The 90-day external
testing window resets on new build upload, so plan a new TestFlight build at
minimum every quarter for active testers.

**FOSS-purist iOS distribution path** — for users unwilling to touch
TestFlight: build the `.ipa` locally with a 7-day Apple developer cert (free,
no $99 fee), install via Xcode → Devices, re-sign weekly. Document in
`docs/BUILD.md`. The 7-day expiry is Apple's, not ours, and the only true
no-Apple-money iOS path. AltStore is mentioned for completeness but adds a
third-party dependency we don't endorse.

---

## 8. F-Droid reproducible build (Android)

F-Droid main-repo inclusion requires the F-Droid build server can rebuild our
APK byte-for-byte from source. Required moves:

- `org.gradle.parallel=false` in `gradle.properties` (parallel build produces
  non-deterministic R.jar ordering).
- Pin JDK to a specific Temurin version in `metadata.yml`. We use JDK 21 LTS.
- Strip timestamps: `tasks.withType<Jar> { isPreserveFileTimestamps = false;
  isReproducibleFileOrder = true }`.
- **Build libsodium from source.** The `ionspin` artifact ships pre-built
  `.so` JNI blobs — F-Droid main-repo rejects pre-built binaries. Build script
  lives in `androidApp/native/build-libsodium.sh`, vendoring a pinned libsodium
  source tarball (verified by SHA-256) and producing the four ABI `.so`s
  reproducibly. Excluded from the artifact via `jniLibs.excludes` (see §2),
  re-added by our script.
- `metadata.yml` (F-Droid format) lives in `fdroiddata/metadata/com.openwarden.parent.yml`
  upstream; in our repo we ship the **build recipe section** (`Builds:`) ready
  to paste. Includes `gradle: yes`, `subdir: androidApp`, `commit: <tag>`,
  and the `prebuild:` step that calls `build-libsodium.sh`.
- Avoid Play Services entirely in the F-Droid variant: a `fdroid` build flavor
  swaps ML Kit QR scanning for ZXing.
- All deps must be on Maven Central with source jars. `ionspin` is on Maven
  Central, Apache 2.0 compatible. ktor, kotlinx.*, Compose all clear.

Plan: ship to F-Droid before Sept 2026, ahead of Google's developer-verification
deadline (see `openwarden-app-research.md` §7). F-Droid registration is our
umbrella; if F-Droid inclusion stalls, we register independently as a fallback.

---

## 9. Background sync (KMP)

Shared interface in `shared/commonMain/sync/SyncScheduler.kt`:

```kotlin
interface SyncScheduler {
    fun schedulePeriodicSync(intervalMinutes: Int)
    fun cancelPeriodicSync()
    suspend fun runSyncNow(): SyncResult
}
```

**Android implementation** — `shared/androidMain/sync/AndroidSyncScheduler.kt`
uses WorkManager:

```kotlin
class AndroidSyncScheduler(private val context: Context) : SyncScheduler {
    override fun schedulePeriodicSync(intervalMinutes: Int) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "openwarden-sync", ExistingPeriodicWorkPolicy.KEEP, req
        )
    }
}
```

**iOS implementation** — `shared/iosMain/background/IosSyncScheduler.kt`
registers a `BGAppRefreshTask` (NOT `BGProcessingTask` — too restrictive for
our short, frequent sync work):

```kotlin
class IosSyncScheduler : SyncScheduler {
    override fun schedulePeriodicSync(intervalMinutes: Int) {
        val request = BGAppRefreshTaskRequest("com.openwarden.parent.refresh").apply {
            earliestBeginDate = NSDate().dateByAddingTimeInterval(intervalMinutes * 60.0)
        }
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }
}
```

Registration of the task identifier happens in Swift `OpenWardenApp.swift` via
`BGTaskScheduler.shared.register(...)`, calling back into a shared Kotlin
`SyncWorker` via SKIE.

**v1 iOS behavior:** opportunistic only. When the user opens the app, run an
immediate sync in `onAppear`. When iOS deigns to fire `BGAppRefreshTask`, sync
+ surface local notifications via `UNUserNotificationCenter`. Document the
"may take up to 6 hours" caveat in `docs/USER_GUIDE.md`.

---

## 10. Testing strategy

- **`commonTest` (JVM):** unit tests for protocol canonicalization, Ed25519
  sign/verify round-trips, policy bundle merge logic, sync state machines.
  Fast (<2s suite). Runs on every save in IDE.
- **`androidUnitTest`:** Robolectric-shadowed Keystore tests, WorkManager
  enqueue verification.
- **`androidInstrumentedTest`:** device-runs against real AndroidKeyStore,
  StrongBox attestation when available, end-to-end WorkManager triggering.
- **`iosTest`:** Kotlin/Native test target, runs under `xcodebuild test`,
  validates the iOS Keychain wrapper and `BGTaskScheduler` registration on
  the simulator.
- **Protocol parity vectors:** `PROTOCOL.md` and `CRYPTO.md` ship hex test
  vectors. The same vectors execute in `commonTest`, in
  `child-android`'s `:proto-test`, *and* in `iosTest`. Identical pass on all
  three is the criterion for a green PR touching `:proto`.

---

## 11. Bootstrapping

```bash
# From C:\src\openwarden\
mkdir -p parent-kmp/{shared/src/commonMain/kotlin,shared/src/androidMain/kotlin,shared/src/iosMain/kotlin}
mkdir -p parent-kmp/{proto/src/commonMain/kotlin,androidApp/src/main/kotlin,iosApp/iosApp}
mkdir -p parent-kmp/{gradle,docs}

# Drop in the files listed in §2 (settings.gradle.kts, build.gradle.kts root,
# shared/build.gradle.kts, androidApp/build.gradle.kts, gradle/libs.versions.toml,
# gradle.properties).

# Initialize Gradle wrapper:
gradle wrapper --gradle-version 8.11 --distribution-type bin

# First build:
./gradlew :proto:build :shared:build :androidApp:assembleDebug

# Open iosApp/iosApp.xcodeproj in Xcode, build the iosApp scheme.
```

The JetBrains KMP wizard (`kotlin-multiplatform` IntelliJ project template) is
acceptable for the very first scaffold; replace its generated files with the
ones from §2 immediately afterward — the wizard does not yet emit composite
builds or SKIE integration.

---

## 12. Migration from `parent-flutter/`

**1:1 translations:**
- `parent-flutter/lib/api/child_client.dart` →
  `shared/commonMain/sync/ChildClient.kt` using ktor.
- `parent-flutter/lib/crypto/signing.dart` →
  `shared/commonMain/crypto/PolicySigner.kt` using libsodium.
- `parent-flutter/lib/models/policy.dart` →
  `proto/commonMain/Bundle.kt` (now shared with the DPC).
- `parent-flutter/lib/screens/pair_screen.dart` →
  `androidApp/.../ui/pair/PairScreen.kt` (Compose) and
  `iosApp/.../Views/PairView.swift` (SwiftUI). The pairing state machine
  itself stays in `:shared` under `state/PairingPresenter.kt`.

**Thrown away:** `pubspec.yaml`, all Dart code, Flutter scaffolding,
`flutter_sodium`/`cryptography_flutter` dependencies, the Flutter iOS/Android
runners.

**Net-new code:** SKIE plugin config, libsodium init plumbing on both targets,
Compose Navigation graph, SwiftUI views, composite-build wiring for `:proto`,
the F-Droid `build-libsodium.sh` script, the libsodium `.so` exclusion rules,
and the BGAppRefreshTask registration in Swift.

Delete `parent-flutter/` once `:androidApp:assembleDebug` runs the pairing
flow end-to-end against the child DPC over LAN mDNS.

---

## 13. Library matrix (`gradle/libs.versions.toml`)

```toml
[versions]
kotlin = "2.1.0"
agp = "8.7.3"
compose-bom = "2024.12.01"
compose-compiler = "1.5.15"
compose-navigation = "2.8.5"
coroutines = "1.10.1"
serialization = "1.7.3"
datetime = "0.6.1"
ktor = "3.0.3"
libsodium = "0.9.2"
skie = "0.10.1"
molecule = "2.0.0"
koalaplot = "0.6.2"
androidx-security = "1.1.0-alpha06"
androidx-work = "2.10.0"
androidx-activity-compose = "1.9.3"

[libraries]
kotlinx-coroutines = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "datetime" }
libsodium-bindings = { module = "com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings", version.ref = "libsodium" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-contentnegotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
androidx-security-crypto = { module = "androidx.security:security-crypto", version.ref = "androidx-security" }
androidx-work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "androidx-work" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidx-activity-compose" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-navigation = { module = "androidx.navigation:navigation-compose", version.ref = "compose-navigation" }
molecule-runtime = { module = "app.cash.molecule:molecule-runtime", version.ref = "molecule" }
koalaplot-core = { module = "io.github.koalaplot:koalaplot-core", version.ref = "koalaplot" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
compose = { id = "org.jetbrains.compose", version = "1.7.0" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
skie = { id = "co.touchlab.skie", version.ref = "skie" }
```

---

## 14. Risks

- **Compose Multiplatform iOS-target maturity.** Mitigated by choosing SwiftUI
  on iOS, not Compose-MP. Re-evaluate at v3.
- **Kotlin/Native ergonomics on iOS.** Largely solved by SKIE; remaining
  warts are `ByteArray <-> Data` conversions, addressed by a thin Swift
  extension layer in `iosApp/iosApp/Extensions/`.
- **F-Droid + KMP novelty.** No widely cited precedent of a KMP-Compose app
  in F-Droid main repo as of late 2025. Mitigation: spike the reproducible
  build *before* writing significant UI; if it stalls, fall back to F-Droid
  Archive repo (less strict) for v1 and target main repo for v2.
- **libsodium .so reproducibility.** Building libsodium reproducibly on
  multiple ABIs is non-trivial. Budget two weekends for the build script
  before claiming F-Droid main-repo readiness.
- **SKIE plugin churn.** Pin exact version, watch their changelog before bumps.
- **Composite Gradle build performance.** Slows down clean builds noticeably
  for `child-android` developers. Offset by IDE-incremental compile speed and
  the avoidance of `mvn install`-style loops.

---

## References

- JetBrains, "New KMP default structure (May 2026)" — https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/
- Compose Multiplatform docs — https://www.jetbrains.com/lp/compose-multiplatform/
- ionspin/kotlin-multiplatform-libsodium — https://github.com/ionspin/kotlin-multiplatform-libsodium
- touchlab/SKIE — https://github.com/touchlab/SKIE
- google/android-key-attestation — https://github.com/google/android-key-attestation
- F-Droid reproducible builds — https://f-droid.org/docs/Reproducible_Builds/
- KMP background sync (WorkManager + BGTaskScheduler) — https://medium.com/@ignatiah.x/background-sync-in-kotlin-multiplatform-workmanager-android-background-tasks-iosx-1f92ad56d84b
- Apple BackgroundTasks — https://developer.apple.com/documentation/backgroundtasks
- TestFlight — https://developer.apple.com/testflight/
- Companion: `openwarden-app-research.md` §2 (stack rationale), §3 (iOS reality), §7 (F-Droid pitfalls)
- Companion: `ARCHITECTURE.md` (three planes), `docs/STORE_AND_FORWARD.md`, `docs/SECURITY.md`, `docs/PROVISIONING.md`

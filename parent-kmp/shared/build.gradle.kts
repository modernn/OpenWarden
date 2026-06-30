import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    // SKIE (iOS Swift-interop) is applied conditionally below — macOS host only.
}

val isMac = HostManager.hostIsMac
if (isMac) {
    pluginManager.apply("co.touchlab.skie")
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    // JVM target for fast host-side commonTest (pure logic: JCS signing input, policy model).
    // libsodium round-trip tests run on-device (androidInstrumentedTest) / CI where the
    // native library is present — the desktop JVM does not ship it.
    jvm()

    // iOS targets + framework only on a macOS host (see dev-platform plan).
    if (isMac) {
        listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { ios ->
            ios.binaries.framework {
                baseName = "OpenWardenShared"
                isStatic = true
                export(project(":proto"))
            }
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
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
        }
        // Bouncy Castle backs the parent root-key derivation (ADR-033: Argon2id/HKDF/
        // Ed25519/X25519). It is a pure-JVM lib, so the JVM + Android source sets share it
        // and the whole derivation (plus its ratified vector) is host-testable — no device
        // and no libsodium native lib needed. iOS derivation is deferred (host-gated off).
        jvmMain.dependencies {
            implementation(libs.bouncycastle.bcprov)
        }
        androidMain.dependencies {
            implementation(libs.androidx.security.crypto)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.bouncycastle.bcprov)
            // #95 (ADR-036 D6): the parent pairing endpoint runs an embedded Ktor CIO server to
            // receive the §7.2 child POST over the LAN. Server-side only; client deps stay above.
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.contentnegotiation)
        }
        if (isMac) {
            iosMain.dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

android {
    namespace = "com.openwarden.parent.shared"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // The Argon2id root-key vector (ADR-033, m=256 MiB) runs in host-side unit tests.
        unitTests.all { it.maxHeapSize = "1g" }
        // Let unmocked android.* stubs (e.g. android.util.Log) return defaults instead of throwing,
        // so host androidUnitTests can exercise androidMain code that logs (#144 fail-closed paths).
        unitTests.isReturnDefaultValues = true
    }
}

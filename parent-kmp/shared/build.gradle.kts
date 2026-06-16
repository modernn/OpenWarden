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
        androidMain.dependencies {
            implementation(libs.androidx.security.crypto)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.ktor.client.okhttp)
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
}

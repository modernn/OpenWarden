plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Needed for @Serializable wire types in DemoLockCommandSender / future demo clients.
    alias(libs.plugins.kotlin.serialization)
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
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Reproducible-build hygiene (PARENT_KMP_STRUCTURE.md §8)
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.navigation)
    implementation(libs.androidx.activity.compose)
    // DEMO ONLY — Ktor OkHttp + JSON for DemoLockCommandSender.
    // Real signed transport lives in :shared/androidMain/transport/ (unbuilt, gated by #27/#24).
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.contentnegotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
}

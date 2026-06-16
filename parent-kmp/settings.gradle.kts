pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "parent-kmp"
include(":proto", ":shared", ":androidApp")

// Composite build with ../child-android is DEFERRED (PARENT_KMP_STRUCTURE.md §4, Option X).
// child-android does not yet consume the shared :proto module, and its Kotlin/AGP
// versions (2.0.20 / 8.7.0) are not yet aligned with this build (2.1.0 / 8.7.3).
// Re-enable once proto is shared and versions match:
//
// includeBuild("../child-android") {
//     dependencySubstitution {
//         substitute(module("com.openwarden:proto")).using(project(":proto"))
//     }
// }

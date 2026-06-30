package com.openwarden.child

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed response bodies for the child read endpoints (#114).
 *
 * These replace `call.respond(mapOf("a" to <String>, "b" to <Boolean|Int|List>))`. A
 * heterogeneous `Map<String, Any>` has **no** kotlinx serializer at runtime, so Ktor's
 * ContentNegotiation throws and the endpoint returns HTTP 500 on-device — invisible to
 * host tests, which never exercise the serialization path. A `@Serializable` data class
 * has a concrete serializer, so these encode cleanly. JSON wire keys are preserved exactly
 * (snake_case via [SerialName]) so the parent's existing client keeps deserializing them.
 */
@Serializable
data class StateResponse(
    val version: String,
    @SerialName("policy_version") val policyVersion: String,
    // §2: issued_at / not_after are integer ms (u53-bounded) rendered as strings, or "none"/"n/a".
    @SerialName("policy_not_after") val policyNotAfter: String,
    @SerialName("is_locked") val isLocked: Boolean,
    val paired: Boolean,
    // Child self-reported wall-clock (epoch ms) at response time — liveness only, for the
    // parent dashboard's freshness/online derivation (#20). Demo-grade: the kid-settable clock
    // is not a trust boundary, only a "is the child answering right now" heartbeat.
    @SerialName("reported_at") val reportedAt: Long,
)

/**
 * `/usage` envelope (ADR-042). [perApp] reuses the metadata-only
 * [UsageStatsHelper.AppUsageEntry] (package + label + foreground minutes — no content).
 * The optional notices are omitted when null (the server's Json sets `explicitNulls = false`),
 * so each source state emits only its relevant keys.
 */
@Serializable
data class UsageResponse(
    val source: String,
    @SerialName("window_hours") val windowHours: Int,
    @SerialName("per_app") val perApp: List<UsageStatsHelper.AppUsageEntry>,
    @SerialName("demo_notice") val demoNotice: String? = null,
    val notice: String? = null,
    val error: String? = null,
)

/**
 * A single entry in the `/apps` response: package name, human-readable label, and app category.
 *
 * Wire shape matches the parent's `InstalledAppEntry`: camelCase `packageName`, `label`, and
 * `category` (no [SerialName] override needed — Kotlin property names are already camelCase).
 *
 * [category] is an UPPERCASE token mapped from [android.content.pm.ApplicationInfo.category] via
 * [InstalledAppsHelper.categoryToken]. Possible values: GAMING, ENTERTAINMENT, SOCIAL, NEWS,
 * UTILITIES, PRODUCTIVITY, UNKNOWN.
 *
 * METADATA ONLY: package name + label + category only. No content, no in-app data — stalkerware
 * boundary.
 */
@Serializable
data class AppEntry(
    val packageName: String,
    val label: String,
    val category: String,
)

/**
 * `/apps` response envelope. [apps] lists all launchable (user-facing) packages excluding self —
 * the same deny-by-default surface the parent's allowlist editor needs to toggle.
 *
 * Wire shape: `{"apps":[{"packageName":"…","label":"…","category":"SOCIAL"}]}`.
 *
 * Scope decision: we return every package for which [PackageManager.getLaunchIntentForPackage]
 * returns non-null — i.e. apps a kid can actually open from the home screen. This includes both
 * user-installed apps and user-facing system apps (browser, camera, app store, etc.) while
 * excluding pure framework/service packages that have no launcher activity.
 */
@Serializable
data class AppsResponse(
    val apps: List<AppEntry>,
)

package com.openwarden.parent.sync

/**
 * Cross-platform background-sync contract (PARENT_KMP_STRUCTURE.md §9).
 * Android backs this with WorkManager; iOS with BGAppRefreshTask (added on macOS).
 */
interface SyncScheduler {
    fun schedulePeriodicSync(intervalMinutes: Int)
    fun cancelPeriodicSync()
    suspend fun runSyncNow(): SyncResult
}

data class SyncResult(
    val ok: Boolean,
    val message: String? = null,
)

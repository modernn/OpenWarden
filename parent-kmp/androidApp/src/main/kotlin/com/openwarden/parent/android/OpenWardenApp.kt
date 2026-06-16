package com.openwarden.parent.android

import android.app.Application
import com.openwarden.parent.crypto.bootstrapCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OpenWardenApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // libsodium must initialize before any crypto call (PARENT_KMP_STRUCTURE.md §3).
        appScope.launch { bootstrapCrypto() }
    }
}

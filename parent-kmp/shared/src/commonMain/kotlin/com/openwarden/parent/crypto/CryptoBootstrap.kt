package com.openwarden.parent.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer

/**
 * Must complete before ANY crypto call, on every platform
 * (PARENT_KMP_STRUCTURE.md §3). Call once at app startup.
 */
suspend fun bootstrapCrypto() {
    if (!LibsodiumInitializer.isInitialized()) {
        LibsodiumInitializer.initialize()
    }
}

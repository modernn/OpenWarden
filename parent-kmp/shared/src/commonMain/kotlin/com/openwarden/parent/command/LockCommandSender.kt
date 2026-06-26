package com.openwarden.parent.command

/**
 * App-layer seam for issuing lock / unlock commands to the child device.
 *
 * The real implementation posts signed HTTP requests to the child's /lock and /unlock
 * endpoints.  Signing is gated behind #27 / #24 and lives in a CODEOWNERS-gated seam
 * that is NOT implemented here.  Tests use [FakeLockCommandSender].
 *
 * Fail-closed contract: on any error the caller treats the child as still locked /
 * still in its previous state and surfaces the failure.
 */
interface LockCommandSender {
    /**
     * Send a lock command to the child. Returns [LockCommandResult.Success] if the
     * child acknowledged the command, [LockCommandResult.Failure] otherwise.
     */
    suspend fun sendLock(): LockCommandResult

    /**
     * Send an unlock command to the child. Returns [LockCommandResult.Success] if the
     * child acknowledged the command, [LockCommandResult.Failure] otherwise.
     */
    suspend fun sendUnlock(): LockCommandResult
}

sealed class LockCommandResult {
    data object Success : LockCommandResult()

    data class Failure(
        val message: String,
    ) : LockCommandResult()
}

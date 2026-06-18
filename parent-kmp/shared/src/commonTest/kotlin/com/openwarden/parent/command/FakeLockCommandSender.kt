package com.openwarden.parent.command

import kotlinx.coroutines.CompletableDeferred

/**
 * Test double for [LockCommandSender].
 *
 * Configure [lockResult] / [unlockResult] before calling the presenter, then inspect
 * [lockCalls] / [unlockCalls] to assert invocation counts.
 *
 * For concurrency tests, set [lockGate] / [unlockGate] to a [CompletableDeferred].
 * The fake will suspend at the gate until it is completed, allowing tests to verify
 * the reentrancy guard fires while a command is in-flight.
 *
 * Example:
 * ```
 * val gate = CompletableDeferred<Unit>()
 * val sender = FakeLockCommandSender(lockGate = gate)
 * val job = launch { presenter.lockNow() }   // suspends at gate
 * // presenter is now busy …
 * presenter.lockNow()                         // must be rejected by guard
 * gate.complete(Unit)                         // release first call
 * job.join()
 * assertEquals(1, sender.lockCalls)
 * ```
 */
class FakeLockCommandSender(
    var lockResult: LockCommandResult = LockCommandResult.Success,
    var unlockResult: LockCommandResult = LockCommandResult.Success,
    /** If non-null, [sendLock] suspends here before returning [lockResult]. */
    var lockGate: CompletableDeferred<Unit>? = null,
    /** If non-null, [sendUnlock] suspends here before returning [unlockResult]. */
    var unlockGate: CompletableDeferred<Unit>? = null,
) : LockCommandSender {
    var lockCalls = 0
        private set
    var unlockCalls = 0
        private set

    override suspend fun sendLock(): LockCommandResult {
        lockCalls++
        lockGate?.await()
        return lockResult
    }

    override suspend fun sendUnlock(): LockCommandResult {
        unlockCalls++
        unlockGate?.await()
        return unlockResult
    }
}

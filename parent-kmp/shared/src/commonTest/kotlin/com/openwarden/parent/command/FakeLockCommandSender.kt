package com.openwarden.parent.command

/**
 * Test double for [LockCommandSender].
 *
 * Configure [lockResult] / [unlockResult] before calling the presenter, then inspect
 * [lockCalls] / [unlockCalls] to assert invocation counts.
 */
class FakeLockCommandSender(
    var lockResult: LockCommandResult = LockCommandResult.Success,
    var unlockResult: LockCommandResult = LockCommandResult.Success,
) : LockCommandSender {
    var lockCalls = 0
        private set
    var unlockCalls = 0
        private set

    override suspend fun sendLock(): LockCommandResult {
        lockCalls++
        return lockResult
    }

    override suspend fun sendUnlock(): LockCommandResult {
        unlockCalls++
        return unlockResult
    }
}

package com.openwarden.parent.command

import com.openwarden.parent.state.AppState
import com.openwarden.parent.state.LockState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LockPresenterTest {

    // ── lockNow — success path ──────────────────────────────────────────────────

    @Test
    fun lockNow_success_invokesSeamOnce() = runTest {
        val sender = FakeLockCommandSender(lockResult = LockCommandResult.Success)
        val presenter = LockPresenter(AppState(), sender)

        presenter.lockNow()

        assertEquals(1, sender.lockCalls, "sendLock must be called exactly once")
        assertEquals(0, sender.unlockCalls, "sendUnlock must not be called")
    }

    @Test
    fun lockNow_success_stateBecomesLocked() = runTest {
        val appState = AppState()
        val presenter = LockPresenter(appState, FakeLockCommandSender())

        presenter.lockNow()

        assertEquals(LockState.LOCKED, presenter.uiState.value.lockState)
        assertEquals(LockState.LOCKED, appState.lockState.value)
        assertFalse(presenter.uiState.value.isBusy)
        assertNull(presenter.uiState.value.lastError)
    }

    // ── lockNow — failure path (fail-closed) ───────────────────────────────────

    @Test
    fun lockNow_failure_invokesSeamOnce() = runTest {
        val sender = FakeLockCommandSender(
            lockResult = LockCommandResult.Failure("timeout"),
        )
        val presenter = LockPresenter(AppState(), sender)

        presenter.lockNow()

        assertEquals(1, sender.lockCalls)
    }

    @Test
    fun lockNow_failure_stateBecomesUnknown_failClosed() = runTest {
        val appState = AppState()
        val sender = FakeLockCommandSender(
            lockResult = LockCommandResult.Failure("network error"),
        )
        val presenter = LockPresenter(appState, sender)

        presenter.lockNow()

        assertEquals(LockState.UNKNOWN, presenter.uiState.value.lockState, "fail-closed: must not expose UNLOCKED on error")
        assertEquals(LockState.UNKNOWN, appState.lockState.value)
        assertFalse(presenter.uiState.value.isBusy)
        assertNotNull(presenter.uiState.value.lastError)
        assertTrue(presenter.uiState.value.lastError!!.contains("network error"))
    }

    // ── lockNow — exception path (fail-closed) ─────────────────────────────────

    @Test
    fun lockNow_seamThrows_stateBecomesUnknown_failClosed() = runTest {
        val appState = AppState()
        val throwingSender = object : LockCommandSender {
            override suspend fun sendLock(): LockCommandResult =
                throw RuntimeException("connection refused")
            override suspend fun sendUnlock(): LockCommandResult =
                LockCommandResult.Success
        }
        val presenter = LockPresenter(appState, throwingSender)

        presenter.lockNow()

        assertEquals(LockState.UNKNOWN, presenter.uiState.value.lockState, "fail-closed: thrown exception must yield UNKNOWN")
        assertEquals(LockState.UNKNOWN, appState.lockState.value)
        assertFalse(presenter.uiState.value.isBusy, "isBusy must be cleared even when seam throws")
        assertNotNull(presenter.uiState.value.lastError)
    }

    // ── unlockNow — success path ────────────────────────────────────────────────

    @Test
    fun unlockNow_success_invokesSeamOnce() = runTest {
        val sender = FakeLockCommandSender(unlockResult = LockCommandResult.Success)
        val presenter = LockPresenter(AppState(), sender)

        presenter.unlockNow()

        assertEquals(1, sender.unlockCalls, "sendUnlock must be called exactly once")
        assertEquals(0, sender.lockCalls, "sendLock must not be called")
    }

    @Test
    fun unlockNow_success_stateBecomesUnlocked() = runTest {
        val appState = AppState()
        val presenter = LockPresenter(appState, FakeLockCommandSender())

        presenter.unlockNow()

        assertEquals(LockState.UNLOCKED, presenter.uiState.value.lockState)
        assertEquals(LockState.UNLOCKED, appState.lockState.value)
        assertFalse(presenter.uiState.value.isBusy)
        assertNull(presenter.uiState.value.lastError)
    }

    // ── unlockNow — failure path (fail-closed) ─────────────────────────────────

    @Test
    fun unlockNow_failure_stateBecomesUnknown_failClosed() = runTest {
        val appState = AppState()
        val sender = FakeLockCommandSender(
            unlockResult = LockCommandResult.Failure("child unreachable"),
        )
        val presenter = LockPresenter(appState, sender)

        presenter.unlockNow()

        assertEquals(LockState.UNKNOWN, presenter.uiState.value.lockState, "fail-closed: must not expose UNLOCKED on error")
        assertEquals(LockState.UNKNOWN, appState.lockState.value)
        assertFalse(presenter.uiState.value.isBusy)
        assertNotNull(presenter.uiState.value.lastError)
    }

    // ── unlockNow — exception path (fail-closed) ───────────────────────────────

    @Test
    fun unlockNow_seamThrows_stateBecomesUnknown_failClosed() = runTest {
        val appState = AppState()
        val throwingSender = object : LockCommandSender {
            override suspend fun sendLock(): LockCommandResult =
                LockCommandResult.Success
            override suspend fun sendUnlock(): LockCommandResult =
                throw RuntimeException("unlock boom")
        }
        val presenter = LockPresenter(appState, throwingSender)

        presenter.unlockNow()

        assertEquals(LockState.UNKNOWN, presenter.uiState.value.lockState, "fail-closed: thrown exception must yield UNKNOWN")
        assertEquals(LockState.UNKNOWN, appState.lockState.value)
        assertFalse(presenter.uiState.value.isBusy, "isBusy must be cleared even when seam throws")
        assertNotNull(presenter.uiState.value.lastError)
    }

    // ── reentrancy guard ────────────────────────────────────────────────────────

    /**
     * Two coroutines call [LockPresenter.lockNow] concurrently while the seam is
     * suspended on a [CompletableDeferred] gate.  Only the first caller must invoke
     * the seam; the second must be rejected by the atomic busy guard.
     *
     * This test WILL FAIL if the compareAndSet guard is removed (both callers would
     * reach the seam, yielding lockCalls == 2).
     */
    @Test
    fun lockNow_whileBusy_doesNotCallSeamAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sender = FakeLockCommandSender(lockGate = gate)
        val presenter = LockPresenter(AppState(), sender)

        // First call — suspends inside the seam at the gate.
        val job = launch { presenter.lockNow() }

        // Yield control so the first coroutine can advance and set isBusy = true.
        // runTest's scheduler is deterministic: after one yield the launched
        // coroutine has run up to its first suspension point (the gate await).
        kotlinx.coroutines.yield()

        // Verify isBusy is set before issuing the second call.
        assertTrue(presenter.uiState.value.isBusy, "presenter must be busy while first call is in-flight")

        // Second call — must be rejected by the atomic guard.
        presenter.lockNow()

        // Release the gate so the first call can complete.
        gate.complete(Unit)
        job.join()

        assertEquals(1, sender.lockCalls, "seam must be invoked exactly once — reentrancy guard must reject second caller")
        assertFalse(presenter.uiState.value.isBusy, "isBusy must be cleared after first call completes")
    }

    /**
     * Mirror of the lock reentrancy test for [LockPresenter.unlockNow].
     */
    @Test
    fun unlockNow_whileBusy_doesNotCallSeamAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sender = FakeLockCommandSender(unlockGate = gate)
        val presenter = LockPresenter(AppState(), sender)

        val job = launch { presenter.unlockNow() }
        kotlinx.coroutines.yield()

        assertTrue(presenter.uiState.value.isBusy)
        presenter.unlockNow()

        gate.complete(Unit)
        job.join()

        assertEquals(1, sender.unlockCalls, "seam must be invoked exactly once — reentrancy guard must reject second caller")
        assertFalse(presenter.uiState.value.isBusy)
    }
}

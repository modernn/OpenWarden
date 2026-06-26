package com.openwarden.child

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure HTTP-shaping for the signed-command endpoints (ADR-030 test plan: "unsigned/garbage body ⇒
 * 400" and the lock/unlock state surface). Exercises the fail-closed wire mapping without standing
 * up Ktor or the encrypted store.
 */
class CommandDispatchTest {
    private val myId = "child-abcd"
    private val pinned = ByteArray(32) { 1 }

    private fun cmd(type: String = SignedCommand.TYPE_LOCK) = SignedCommand(v = 1, type = type, child_device_id = myId, issued_at = 1L)

    @Test
    fun `null command (unparseable body) maps to 400 MALFORMED`() {
        val resp =
            CommandDispatch.dispatch(null, SignedCommand.TYPE_LOCK, pinned) { _, _, _ ->
                error("admit must not be called for a null command")
            }
        assertEquals(400, resp.status)
        assertEquals("MALFORMED", resp.body["error"])
    }

    @Test
    fun `accepted lock maps to 200 locked`() {
        val resp =
            CommandDispatch.dispatch(cmd(SignedCommand.TYPE_LOCK), SignedCommand.TYPE_LOCK, pinned) { _, _, _ ->
                CommandAdmission.Outcome.Accept(SignedCommand.TYPE_LOCK)
            }
        assertEquals(200, resp.status)
        assertEquals("locked", resp.body["status"])
    }

    @Test
    fun `accepted unlock maps to 200 unlocked`() {
        val resp =
            CommandDispatch.dispatch(cmd(SignedCommand.TYPE_UNLOCK), SignedCommand.TYPE_UNLOCK, pinned) { _, _, _ ->
                CommandAdmission.Outcome.Accept(SignedCommand.TYPE_UNLOCK)
            }
        assertEquals(200, resp.status)
        assertEquals("unlocked", resp.body["status"])
    }

    @Test
    fun `rejected command maps to 400 REJECTED with the reason`() {
        val resp =
            CommandDispatch.dispatch(cmd(), SignedCommand.TYPE_LOCK, pinned) { _, _, _ ->
                CommandAdmission.Outcome.Reject("SIG_FAIL")
            }
        assertEquals(400, resp.status)
        assertEquals("REJECTED", resp.body["error"])
        assertEquals("SIG_FAIL", resp.body["reason"])
    }

    @Test
    fun `a durable-write failure inside the gate maps to 400 REJECTED, not a 500`() {
        val resp =
            CommandDispatch.dispatch(cmd(), SignedCommand.TYPE_LOCK, pinned) { _, _, _ ->
                throw IllegalStateException("command admit commit() failed (fail-closed)")
            }
        assertEquals(400, resp.status)
        assertEquals("REJECTED", resp.body["error"])
        assertEquals("command not durably admitted", resp.body["reason"])
    }

    @Test
    fun `is_locked read failure assumes locked (fail-closed)`() {
        assertTrue(CommandDispatch.isLockedFailClosed { throw RuntimeException("store unreadable") })
        assertTrue(CommandDispatch.isLockedFailClosed { true })
        assertEquals(false, CommandDispatch.isLockedFailClosed { false })
    }
}

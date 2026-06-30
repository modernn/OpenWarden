package com.openwarden.parent.android.command

import com.openwarden.parent.command.LockCommandResult
import com.openwarden.parent.crypto.RootKeyProvider
import com.openwarden.proto.SignedCommand
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RealLockCommandSender] (ADR-046 D3). All collaborators faked:
 *  - [FakeRootKeys] controls whether a signature is produced (provisioned vs not).
 *  - a `childIdProvider` lambda controls paired vs not.
 *  - Ktor [MockEngine] returns controlled responses + captures the POSTed body.
 *
 * Fail-closed contract: no paired child / no root key / non-200 → [LockCommandResult.Failure], never a
 * false Success; and no POST is sent when the command can't be signed/addressed.
 */
class RealLockCommandSenderTest {
    private class FakeRootKeys(
        private val sig: ByteArray?,
    ) : RootKeyProvider {
        override fun rootPublicKey(): ByteArray? = sig?.let { ByteArray(32) }

        override fun encryptionPublicKey(): ByteArray? = null

        override fun sign(message: ByteArray): ByteArray? = sig
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun sender(
        engine: MockEngine,
        childId: String? = "child-abcd",
        sig: ByteArray? = ByteArray(64) { 0xAB.toByte() },
    ) = RealLockCommandSender(
        rootKeyProvider = FakeRootKeys(sig),
        childIdProvider = { childId },
        clockMs = { 12345L },
        baseUrl = "http://localhost",
        engine = engine,
    )

    @Test
    fun notPaired_returnsFailure_noPost() =
        runTest {
            var calls = 0
            val s =
                sender(
                    MockEngine {
                        calls++
                        respond("", HttpStatusCode.OK)
                    },
                    childId = null,
                )
            assertTrue(s.sendLock() is LockCommandResult.Failure)
            assertEquals("must not POST without a paired child", 0, calls)
        }

    @Test
    fun notProvisioned_returnsFailure_noPost() =
        runTest {
            var calls = 0
            val s =
                sender(
                    MockEngine {
                        calls++
                        respond("", HttpStatusCode.OK)
                    },
                    sig = null,
                )
            assertTrue(s.sendLock() is LockCommandResult.Failure)
            assertEquals("must not POST without a root key", 0, calls)
        }

    @Test
    fun success_postsSignedCommandToLock() =
        runTest {
            var body = ""
            var url = ""
            val engine =
                MockEngine { req ->
                    url = req.url.toString()
                    body = req.body.toByteArray().decodeToString()
                    respond("", HttpStatusCode.OK)
                }
            val result = sender(engine).sendLock()
            assertTrue("expected Success, got $result", result is LockCommandResult.Success)
            assertTrue("posts to /lock: $url", url.endsWith("/lock"))
            val cmd = json.decodeFromString(SignedCommand.serializer(), body)
            assertEquals(1, cmd.v)
            assertEquals("lock", cmd.type)
            assertEquals("child-abcd", cmd.childDeviceId)
            assertEquals(12345L, cmd.issuedAt)
            // FakeRootKeys signs with 64 × 0xAB → exact lowercase-hex "ab" × 64. Asserting the literal
            // content (not just length == 128) proves toHexLower emits lowercase, zero-padded hex — the
            // form the child's case-insensitive decoder needs and the byte the signature covers.
            assertEquals("ab".repeat(64), cmd.sig)
        }

    @Test
    fun unlock_postsToUnlock() =
        runTest {
            var url = ""
            val engine =
                MockEngine { req ->
                    url = req.url.toString()
                    respond("", HttpStatusCode.OK)
                }
            sender(engine).sendUnlock()
            assertTrue("posts to /unlock: $url", url.endsWith("/unlock"))
        }

    @Test
    fun childRejects_returnsFailure() =
        runTest {
            val s = sender(MockEngine { respond("", HttpStatusCode.BadRequest) })
            assertTrue(s.sendLock() is LockCommandResult.Failure)
        }
}

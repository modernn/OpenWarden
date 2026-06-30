package com.openwarden.parent.android.command

import com.openwarden.parent.command.LockCommandResult
import com.openwarden.parent.command.LockCommandSender
import com.openwarden.parent.crypto.CommandSigner
import com.openwarden.parent.crypto.RootKeyProvider
import com.openwarden.proto.SignedCommand
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Real signed lock / unlock sender (ADR-046 D3) — replaces the empty-body [DemoLockCommandSender] stub
 * the child correctly 400-rejects (it parses no `SignedCommand`).
 *
 * Builds a [SignedCommand], signs the JCS-canonical bytes ([CommandSigner.signingBytes]) with the
 * parent root key ([RootKeyProvider.sign]), hex-encodes the 64-byte detached signature into `sig`, and
 * POSTs it as JSON to the child `/lock` or `/unlock`. The child verifies the Ed25519 signature against
 * the pinned parent key + audience (`child_device_id`) + monotonic `issued_at` floor + 5-min freshness.
 *
 * **Fail-closed**: no paired child / no root key / non-200 → [LockCommandResult.Failure], never a false
 * Success. `child_device_id` comes from the demo-pair store; `issued_at` is the parent wall-clock.
 *
 * Signing is delegated to the existing [RootKeyProvider] — no key material lives here.
 */
internal class RealLockCommandSender(
    private val rootKeyProvider: RootKeyProvider,
    private val childIdProvider: () -> String?,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val baseUrl: String = DEMO_CHILD_BASE_URL,
    engine: HttpClientEngine =
        OkHttp.create {
            config {
                connectTimeout(5, TimeUnit.SECONDS)
                readTimeout(5, TimeUnit.SECONDS)
            }
        },
) : LockCommandSender,
    java.io.Closeable {
    private val http =
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    override suspend fun sendLock(): LockCommandResult = send(SignedCommand.TYPE_LOCK, "/lock")

    override suspend fun sendUnlock(): LockCommandResult = send(SignedCommand.TYPE_UNLOCK, "/unlock")

    private suspend fun send(
        type: String,
        path: String,
    ): LockCommandResult {
        val childId = childIdProvider()
        if (childId.isNullOrBlank()) {
            return LockCommandResult.Failure("No paired child yet — pair with the child device first.")
        }
        val unsigned = SignedCommand(type = type, childDeviceId = childId, issuedAt = clockMs())
        // Fail-closed: no root key → no signature → never POST an unsigned/forged command.
        val sigBytes =
            rootKeyProvider.sign(CommandSigner.signingBytes(unsigned))
                ?: return LockCommandResult.Failure("No recovery key — set up the parent recovery key first.")
        val signed = unsigned.copy(sig = sigBytes.toHexLower())
        return runCatching {
            val resp: HttpResponse =
                http.post("$baseUrl$path") {
                    contentType(ContentType.Application.Json)
                    setBody(signed)
                }
            if (resp.status == HttpStatusCode.OK) {
                LockCommandResult.Success
            } else {
                LockCommandResult.Failure("Child rejected the $type command (HTTP ${resp.status.value}).")
            }
        }.getOrElse { LockCommandResult.Failure(it.message ?: "$type failed") }
    }

    override fun close() {
        http.close()
    }

    // Deliberate small mirror of shared/commonMain's `ByteArray.toHexLower` (PolicySendSeams.kt): that
    // one is `internal` to the shared module, so it isn't visible from androidApp. Both are byte-identical
    // and each is test-covered; extracting a public cross-module util for one line isn't worth the churn.
    private fun ByteArray.toHexLower(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

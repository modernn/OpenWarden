package com.openwarden.parent.android.command

import com.openwarden.parent.command.LockCommandResult
import com.openwarden.parent.command.LockCommandSender
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * DEMO ONLY — insecure, no TLS, no auth.
 *
 * Real transport uses mDNS peer discovery + pinned TLS + signed commands (gated
 * behind issues #27 / #24, CODEOWNERS-blocked).  This implementation wires the
 * [LockCommandSender] seam to the two-emulator demo child server at the same
 * base URL used by the demo dashboard.
 *
 * adb forward prerequisite (run once per emulator session):
 *   adb -s emulator-5554 forward tcp:7180 tcp:7180
 *
 * Android emulators reach the host loopback at 10.0.2.2 so both emulators can
 * talk to each other through the host bridge.
 */
internal const val DEMO_CHILD_BASE_URL = "http://10.0.2.2:7180"

internal class DemoLockCommandSender : LockCommandSender {

    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        engine {
            config {
                connectTimeout(5, TimeUnit.SECONDS)
                readTimeout(5, TimeUnit.SECONDS)
            }
        }
    }

    override suspend fun sendLock(): LockCommandResult = runCatching {
        http.post("$DEMO_CHILD_BASE_URL/lock")
        LockCommandResult.Success
    }.getOrElse { LockCommandResult.Failure(it.message ?: "lock failed") }

    override suspend fun sendUnlock(): LockCommandResult = runCatching {
        http.post("$DEMO_CHILD_BASE_URL/unlock")
        LockCommandResult.Success
    }.getOrElse { LockCommandResult.Failure(it.message ?: "unlock failed") }
}

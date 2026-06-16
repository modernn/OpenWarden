package com.openwarden.child

import android.content.Context
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

/**
 * Ktor server, bound to localhost. (v1: bind to all interfaces, rely on Tailscale ACLs.
 * v2: discover the actual Tailscale interface IP and bind only there.)
 *
 * All endpoints require Authorization: OpenWarden <hex-hmac> over body + timestamp.
 * Stub here — actual HMAC validation goes in v1.
 */
class ApiServer(private val context: Context) {

    private var engine: ApplicationEngine? = null

    fun start() {
        engine = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                get("/state") {
                    val store = PolicyStore(this@ApiServer.context)
                    val active = store.loadActive()
                    call.respond(mapOf(
                        "version" to BuildVersion,
                        "policy_version" to (active?.issued_at ?: "none"),
                        "policy_expires_at" to (active?.expires_at ?: "n/a"),
                        "is_locked" to false   // TODO
                    ))
                }
                post("/policy") {
                    val bundle = call.receive<SignedBundle>()
                    val result = PolicyStore(this@ApiServer.context).ingest(bundle)
                    if (result == PolicyStore.IngestResult.Applied) {
                        PolicyEnforcer(this@ApiServer.context).applyAllowlist(bundle.policy.allowlist.toSet())
                        call.respond(HttpStatusCode.OK, mapOf("status" to "applied"))
                    } else {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.name))
                    }
                }
                post("/lock") {
                    PolicyEnforcer(this@ApiServer.context).lockNow()
                    call.respond(HttpStatusCode.OK, mapOf("status" to "locked"))
                }
                get("/usage") {
                    // TODO(v1): UsageStatsManager queryAndAggregateUsageStats
                    call.respond(mapOf("per_app" to emptyList<Map<String, Any>>()))
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
    }

    companion object {
        const val PORT = 7180
        const val BuildVersion = "0.1.0-dev"
    }
}

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
                        // §2: issued_at / not_after are integer ms (u53-bounded), not ISO strings.
                        "policy_version" to (active?.issued_at?.toString() ?: "none"),
                        "policy_not_after" to (active?.not_after?.toString() ?: "n/a"),
                        "is_locked" to false   // TODO
                    ))
                }
                post("/policy") {
                    val bundle = call.receive<SignedBundle>()
                    val ctx = this@ApiServer.context
                    val store = PolicyStore(ctx)
                    val floorStore = ReplayFloorStore(ctx)
                    // ADR-017 admission pipeline: JC1 -> audience -> signature -> genesis/floor
                    // -> monotonic/jump, then two-phase commit (stage -> apply+fsync -> advance
                    // floor -> ack). Floor advances LAST. The parent key is pinned out-of-band at
                    // pairing (PolicyStore.pinParentPubkey), so the wire uses the pinned-key path;
                    // bundle-carried genesis TOFU (decide(genesis=true)) is implemented + tested in
                    // PolicyAdmission but is not reachable here because v1 bundles carry no pubkey.
                    val result = PolicyAdmission.admit(
                        bundle = bundle,
                        store = floorStore,
                        applier = DefaultPolicyApplier(ctx),
                        pinParentKey = { store.pinParentPubkey(it) },
                        pinnedParentPubkey = store.parentPubkey(),
                    )
                    when (result) {
                        is PolicyAdmission.Result.Applied ->
                            call.respond(HttpStatusCode.OK, mapOf("status" to "applied", "policy_seq" to result.policySeq))
                        is PolicyAdmission.Result.Rejected ->
                            // Fail-closed: the previous (or strict baseline) policy stays in force.
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to if (result.malformed) "MALFORMED" else "REJECTED", "reason" to result.reason),
                            )
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

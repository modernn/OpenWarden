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
import kotlinx.serialization.json.jsonObject

/**
 * Embedded Ktor server bound to the LAN (v1: bind to all interfaces, rely on Tailscale ACLs;
 * v2: discover the actual Tailscale interface IP and bind only there).
 *
 * Authentication is APP-LAYER, not transport-layer (ADR-030): every state-changing request carries a
 * parent-signed wire object verified against the parent Ed25519 key pinned at pairing (ADR-025) —
 * `/policy` a [SignedBundle] (ADR-017), `/heartbeat` a [SignedHeartbeat] (ADR-024 D4), `/lock` and
 * `/unlock` a [SignedCommand] (ADR-030). There is no transport HMAC: the ratified pairing flow
 * establishes no shared secret, and reusing the pinned key is strictly stronger (ADR-030 D1). The
 * read endpoints (`/state`, `/usage`) expose metadata only and stay open on the LAN in v1 (ADR-030 D6).
 */
class ApiServer(private val context: Context) {

    private var engine: ApplicationEngine? = null
    private val mdns = MdnsAdvertiser(context)

    fun start() {
        engine = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                get("/state") {
                    val ctx = this@ApiServer.context
                    val active = PolicyStore(ctx).loadActive()
                    call.respond(mapOf(
                        "version" to BuildVersion,
                        // §2: issued_at / not_after are integer ms (u53-bounded), not ISO strings.
                        "policy_version" to (active?.issued_at?.toString() ?: "none"),
                        "policy_not_after" to (active?.not_after?.toString() ?: "n/a"),
                        // ADR-030 D5: real durable lock state set by signed /lock /unlock commands.
                        // Fail-closed: an unreadable store assumes locked, never reports false-unlocked.
                        "is_locked" to CommandDispatch.isLockedFailClosed { ReplayFloorStore(ctx).isLocked() }
                    ))
                }
                post("/policy") {
                    val ctx = this@ApiServer.context
                    val store = PolicyStore(ctx)
                    val floorStore = ReplayFloorStore(ctx)
                    // ADR-040: verify over the RECEIVED bytes. Read the raw body and parse it to a
                    // JsonObject (the signed document) — the child MUST NOT re-canonicalize a typed
                    // re-serialization (ADR-019 D2). admit() does JC1 -> size -> audience -> signature
                    // over this document, decodes the typed bundle from it (verify first, parse
                    // second), then runs genesis/floor -> two-phase commit (stage -> apply+fsync ->
                    // advance floor -> ack). Floor advances LAST. The parent key is pinned out-of-band
                    // at pairing (PolicyStore.pinParentPubkey), so the wire uses the pinned-key path;
                    // bundle-carried genesis TOFU is implemented + tested but not reachable here (v1
                    // bundles carry no pubkey). An unparseable body is MALFORMED, fail-closed.
                    val receivedDoc = try {
                        Json.parseToJsonElement(call.receiveText()).jsonObject
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "MALFORMED", "reason" to "unparseable JSON body"),
                        )
                        return@post
                    }
                    val result = PolicyAdmission.admit(
                        receivedDoc = receivedDoc,
                        store = floorStore,
                        applier = DefaultPolicyApplier(ctx),
                        pinParentKey = { store.pinParentPubkey(it) },
                        pinnedParentPubkey = store.parentPubkey(),
                    )
                    when (result) {
                        is PolicyAdmission.Result.Applied -> {
                            // ADR-024: a successful authenticated bundle apply IS parent contact —
                            // reset the no-contact ratchet. Best-effort: a failed marker write must
                            // not fail the (already durable) apply; a missed reset only tightens later.
                            runCatching { ContactClock.forContext(ctx).recordContact() }
                            call.respond(HttpStatusCode.OK, mapOf("status" to "applied", "policy_seq" to result.policySeq))
                        }
                        is PolicyAdmission.Result.Rejected ->
                            // Fail-closed: the previous (or strict baseline) policy stays in force.
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to if (result.malformed) "MALFORMED" else "REJECTED", "reason" to result.reason),
                            )
                    }
                }
                post("/heartbeat") {
                    // ADR-024 D4: a minimal authenticated keep-alive. Verifies the signed heartbeat
                    // against the pinned parent key (+ audience + monotonic replay floor) and, on
                    // success, resets the no-contact ratchet — without re-issuing a policy bundle.
                    val hb = call.receive<SignedHeartbeat>()
                    val ctx = this@ApiServer.context
                    val pinned = PolicyStore(ctx).parentPubkey()
                    val admitted = ContactClock.forContext(ctx).admitHeartbeat(hb, pinned)
                    if (admitted) {
                        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                    } else {
                        // Fail-closed: an unverified/replayed/mis-addressed heartbeat changes nothing.
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "REJECTED"))
                    }
                }
                post("/lock") { handleCommand(call, SignedCommand.TYPE_LOCK) }
                post("/unlock") { handleCommand(call, SignedCommand.TYPE_UNLOCK) }
                get("/usage") {
                    // TODO(v1): UsageStatsManager queryAndAggregateUsageStats
                    call.respond(mapOf("per_app" to emptyList<Map<String, Any>>()))
                }
            }
        }.start(wait = false)

        // Advertise this server over mDNS so the parent can discover it without a hand-typed IP
        // (ADR-031 D3). Fail-safe + orthogonal to enforcement: a discovery hiccup (NSD unavailable,
        // keystore read of the child id throwing) never crashes the server nor weakens policy.
        // Discovery is UNTRUSTED — trust comes from the identity-bound TLS SPKI ([SpkiBinding]) +
        // app-layer Ed25519 verify (ADR-031 D1/D2), never from anything advertised here.
        runCatching {
            val childId = ReplayFloorStore(context).childDeviceId()
            mdns.start(MdnsServiceSpec.forChild(childId, PORT))
        }
    }

    fun stop() {
        mdns.stop()
        engine?.stop(1000, 2000)
        engine = null
    }

    /**
     * Admit a [SignedCommand] for [expectedType] (ADR-030). [CommandGate] verifies the parent Ed25519
     * signature against the pinned key + audience + endpoint↔type binding + monotonic replay floor +
     * freshness window, and on accept atomically advances the floor and sets the durable lock flag.
     * A lock additionally fires the best-effort DPM keyguard (`lockNow`); the authenticated state is
     * already durable via the gate regardless.
     *
     * Fail-closed: an unparseable body, an unverified/replayed/stale/mis-typed command, OR a
     * durable-write failure inside the gate all return 400 and change no state.
     */
    private suspend fun handleCommand(call: ApplicationCall, expectedType: String) {
        val ctx = context
        val cmd = runCatching { call.receive<SignedCommand>() }.getOrNull()
        val pinned = PolicyStore(ctx).parentPubkey()
        val gate = CommandGate(ReplayFloorStore(ctx))
        // Capture an accepted lock so the keyguard side effect fires AFTER the durable state lands.
        var acceptedLock = false
        val resp = CommandDispatch.dispatch(cmd, expectedType, pinned) { c, t, p ->
            gate.admit(c, t, p).also {
                if (it is CommandAdmission.Outcome.Accept && it.type == SignedCommand.TYPE_LOCK) acceptedLock = true
            }
        }
        if (acceptedLock) {
            // Best-effort keyguard nag (PolicyEnforcer.lockNow is v1 best-effort; v2 = PIN gate). The
            // authenticated lock state is already durable via the gate regardless of this firing.
            runCatching { PolicyEnforcer(ctx).lockNow() }
        }
        call.respond(HttpStatusCode.fromValue(resp.status), resp.body)
    }

    companion object {
        const val PORT = 7180
        const val BuildVersion = "0.1.0-dev"
    }
}

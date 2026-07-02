package com.openwarden.child

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Embedded Ktor server bound to the LAN (v1: bind to all interfaces, rely on Tailscale ACLs;
 * v2: discover the actual Tailscale interface IP and bind only there).
 *
 * The control channel runs over **HTTPS** (ADR-031 D8): Netty + `sslConnector` terminate TLS with an
 * ephemeral self-signed leaf ([TlsLeaf]). TLS is **confidentiality-only** — authentication is APP-LAYER
 * (ADR-030): every state-changing request carries a parent-signed wire object verified against the
 * parent Ed25519 key pinned at pairing (ADR-025) — `/policy` a [SignedBundle] (ADR-017), `/heartbeat` a
 * [SignedHeartbeat] (ADR-024 D4), `/lock` and `/unlock` a [SignedCommand] (ADR-030). There is no
 * transport HMAC: the ratified pairing flow establishes no shared secret, and reusing the pinned key is
 * strictly stronger (ADR-030 D1). The channel's TLS provenance is vouched for by the child-identity-
 * signed `/spki-assertion` (ADR-031 D2), never trust-on-first-use. The read endpoints (`/state`,
 * `/usage`) expose metadata only and stay open on the LAN in v1 (ADR-030 D6).
 */
class ApiServer(
    private val context: Context,
    // ADR-031 D5/D8 seam: the child identity key that signs the TLS SPKI assertion. The real
    // StrongBox/TEE-backed key (issue #22) is the default; tests inject a synthetic provider. Before
    // pairing (or on an emulator without StrongBox) it yields null, so the signer vouches for nothing.
    private val identityKeyProvider: IdentityKeyProvider = KeystoreIdentityKeyProvider(KeystoreChildKeys()),
) {
    private var engine: ApplicationEngine? = null
    private val mdns = MdnsAdvertiser(context)

    // Serializes the demo-pair check→pin→genesis-seed critical section so two concurrent first-pair
    // POSTs cannot both win the pin (#150 crypto review F3 TOCTOU). One ApiServer instance per process.
    private val pairLock = Any()

    fun start() {
        // ADR-031 D8: mint an ephemeral self-signed TLS leaf and serve the control channel over HTTPS
        // (Netty + sslConnector; the CIO engine cannot terminate TLS). FAIL-CLOSED: if TLS bring-up
        // fails, the socket simply does not come up — we NEVER fall back to plaintext (D4). Policy
        // enforcement is independent of this socket and is unaffected; the watchdog keeps the baseline
        // in force offline.
        val leaf =
            runCatching { TlsLeaf.generate() }
                .getOrElse {
                    Log.e(TAG, "TLS leaf generation failed; HTTPS control channel not started (enforcement unaffected)", it)
                    return
                }

        engine =
            embeddedServer(
                Netty,
                applicationEngineEnvironment {
                    sslConnector(
                        keyStore = leaf.keyStore,
                        keyAlias = leaf.alias,
                        keyStorePassword = { leaf.password },
                        privateKeyPassword = { leaf.password },
                    ) {
                        port = PORT
                        host = "0.0.0.0"
                    }
                    module { configure { leaf.spkiDer } }
                },
            ).start(wait = false)

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
     * Installs JSON content negotiation + all control-plane routes on this [Application]. Extracted from
     * [start] so the engine can be Netty-with-TLS (ADR-031 D8) without re-nesting the route table.
     * [leafSpkiDer] supplies the live TLS leaf's DER `SubjectPublicKeyInfo` to the `/spki-assertion`
     * route (null ⇒ no assertion served, fail-closed).
     */
    private fun Application.configure(leafSpkiDer: () -> ByteArray?) {
        // explicitNulls = false so optional response fields (e.g. UsageResponse notices)
        // are omitted when null rather than emitted as `null` (#114).
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
        routing {
            get("/state") {
                val ctx = this@ApiServer.context
                val active = PolicyStore(ctx).loadActive()
                call.respond(
                    StateResponse(
                        version = BUILD_VERSION,
                        // §2: issued_at / not_after are integer ms (u53-bounded), not ISO strings.
                        policyVersion = active?.issued_at?.toString() ?: "none",
                        policyNotAfter = active?.not_after?.toString() ?: "n/a",
                        // ADR-030 D5: real durable lock state set by signed /lock /unlock commands.
                        // Fail-closed: an unreadable store assumes locked, never reports false-unlocked.
                        isLocked = CommandDispatch.isLockedFailClosed { ReplayFloorStore(ctx).isLocked() },
                        // ADR-042 D5: honest pairing state — true iff a parent Ed25519 key is pinned
                        // (ADR-025). Fail-closed like is_locked: an unreadable key file reports the
                        // LESS-disclosing default (not-paired), never crashes /state.
                        paired = runCatching { PolicyStore(ctx).parentPubkey() != null }.getOrDefault(false),
                        // Liveness heartbeat for the parent dashboard (#20): the child's current
                        // wall clock. Freshness is judged parent-side against its 90s window.
                        reportedAt = System.currentTimeMillis(),
                    ),
                )
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
                // bundles carry no pubkey). An unparseable or non-object body is MALFORMED.
                // DoS guard (fail-closed): bound the body BEFORE buffering it. receiveText() would
                // otherwise read an unbounded LAN body into a heap String before the canonical-size
                // gate fires, an OOM vector any LAN host could fire pre-auth. Require a declared
                // Content-Length within MAX_POLICY_BODY_BYTES (canonical max + JSON/sig overhead).
                val declaredLen = call.request.contentLength()
                if (declaredLen == null || declaredLen > MAX_POLICY_BODY_BYTES) {
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        mapOf("error" to "MALFORMED", "reason" to "missing or oversize Content-Length"),
                    )
                    return@post
                }
                val receivedDoc =
                    try {
                        Json.parseToJsonElement(call.receiveText()) as? JsonObject
                            ?: throw IllegalArgumentException("policy body is not a JSON object")
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "MALFORMED", "reason" to "unparseable or non-object JSON body"),
                        )
                        return@post
                    }
                val result =
                    PolicyAdmission.admit(
                        receivedDoc = receivedDoc,
                        store = floorStore,
                        applier = DefaultPolicyApplier(ctx),
                        pinParentKey = { store.pinParentPubkey(it) },
                        pinnedParentPubkey = store.parentPubkey(),
                        // ADR-041: the kernel-monotonic clock for the §5.1 freshness estimate + the new
                        // anchor's elapsed component (NOT the kid-settable wall clock).
                        nowElapsedMs = SystemClock.elapsedRealtime(),
                    )
                when (result) {
                    is PolicyAdmission.Result.Applied -> {
                        // ADR-024: a successful authenticated bundle apply IS parent contact —
                        // reset the no-contact ratchet. Best-effort: a failed marker write must
                        // not fail the (already durable) apply; a missed reset only tightens later.
                        runCatching { ContactClock.forContext(ctx).recordContact() }
                        call.respond(HttpStatusCode.OK, mapOf("status" to "applied", "policy_seq" to result.policySeq))
                    }

                    // ADR-041 §2.1 step 9 (CLOCK_SKEW): not yet valid — keep the current policy and
                    // let the parent retry. Not applied, not an anomaly.
                    is PolicyAdmission.Result.Deferred -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "CLOCK_SKEW", "reason" to result.reason),
                        )
                    }

                    // ADR-041 §2.1 step 10 (EXPIRED): window closed — not applied; the watchdog
                    // holds the stale baseline via the active-bundle freshness tier.
                    is PolicyAdmission.Result.Expired -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "EXPIRED", "reason" to result.reason),
                        )
                    }

                    is PolicyAdmission.Result.Rejected -> {
                        // Fail-closed: the previous (or strict baseline) policy stays in force.
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to if (result.malformed) "MALFORMED" else "REJECTED", "reason" to result.reason),
                        )
                    }
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
            // ADR-046 F4 (#150 crypto review): the unauthenticated demo-pair endpoint is
            // registered ONLY on debuggable (debug) builds, so a real release / enforcing child
            // NEVER exposes an open pin endpoint on 0.0.0.0. Fail-closed — release has no /pair
            // at all; the attested ADR-043 flow (#96) supersedes it for distribution.
            if ((this@ApiServer.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                post("/pair") { handlePair(call) }
            }
            post("/lock") { handleCommand(call, SignedCommand.TYPE_LOCK) }
            post("/unlock") { handleCommand(call, SignedCommand.TYPE_UNLOCK) }
            get("/apps") {
                // METADATA ONLY — package name + human-readable label for each user-installed
                // (non-system) app, excluding self. No content, no usage times, no in-app data.
                // See InstalledAppsHelper for the system-app exclusion rationale and
                // InstalledAppsHelper.mapToEntries for the host-testable pure logic.
                // Errors fail closed to an empty list: the parent receives a valid envelope
                // (never HTTP 500) and handles the empty case by showing no toggles.
                val ctx = this@ApiServer.context
                val entries =
                    runCatching { InstalledAppsHelper.query(ctx) }
                        .getOrElse { emptyList() }
                call.respond(AppsResponse(apps = entries))
            }
            get("/usage") {
                // ADR-042: real per-app foreground usage (METADATA ONLY — package + label +
                // foreground minutes; never in-app content). On-device data when the
                // PACKAGE_USAGE_STATS appops grant is present; otherwise honest-empty on release
                // and a clearly-labelled [DEMO] list on debug builds (D2). Errors fail closed to
                // an empty list, never to fabricated data (D4).
                val ctx = this@ApiServer.context
                when (
                    val result =
                        runCatching { UsageStatsHelper.query(ctx) }
                            .getOrElse { UsageStatsHelper.UsageResult.Error(it.message ?: "unknown") }
                ) {
                    is UsageStatsHelper.UsageResult.OnDevice -> {
                        call.respond(
                            UsageResponse(source = "on-device", windowHours = 24, perApp = result.entries),
                        )
                    }

                    is UsageStatsHelper.UsageResult.DemoFallback -> {
                        call.respond(
                            UsageResponse(
                                source = "demo-fallback",
                                windowHours = 24,
                                perApp = result.entries,
                                demoNotice = "PACKAGE_USAGE_STATS not granted — illustrative demo data only (debug build)",
                            ),
                        )
                    }

                    is UsageStatsHelper.UsageResult.Unavailable -> {
                        call.respond(
                            UsageResponse(
                                source = "unavailable",
                                windowHours = 24,
                                perApp = emptyList(),
                                notice = "PACKAGE_USAGE_STATS not granted — no usage data available",
                            ),
                        )
                    }

                    is UsageStatsHelper.UsageResult.Error -> {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            UsageResponse(
                                source = "error",
                                windowHours = 24,
                                perApp = emptyList(),
                                error = result.message,
                            ),
                        )
                    }
                }
            }
            // ADR-031 D2/D8: vouch for the live TLS leaf's SPKI with the child identity key. Fail-closed
            // (204) when there is no identity key, so the parent rejects rather than TOFU-accepting.
            spkiAssertionRoute(leafSpkiDer, this@ApiServer.identityKeyProvider)
        }
    }

    /**
     * v0.x demo-grade pairing (ADR-046 D1): pin the parent Ed25519 public key so the existing signed
     * `/policy` + `/lock` paths become reachable. App-layer, **unauthenticated** LAN endpoint — see
     * ADR-046 for the security delta (no attestation / SAS / TLS) and why it is v0.x-interim-only.
     *
     * Fail-closed: already-paired → 409 (first-pairing-only, never overwrite); malformed key → 400;
     * a pin-write failure → 500 with **no** "paired" claim. On accept it responds with the **real**
     * `child_device_id` ([ReplayFloorStore.childDeviceId]) — the audience for signed bundles/commands.
     */
    private suspend fun handlePair(call: ApplicationCall) {
        val ctx = context
        // DoS guard (fail-closed, mirrors /policy): bound the body BEFORE buffering it. /pair carries
        // only a ~44-char base64 key, so a tiny cap is ample and a LAN host cannot OOM the child.
        val declaredLen = call.request.contentLength()
        if (declaredLen == null || declaredLen > MAX_PAIR_BODY_BYTES) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                mapOf("error" to "MALFORMED", "reason" to "missing or oversize Content-Length"),
            )
            return
        }
        val store = PolicyStore(ctx)
        val floor = ReplayFloorStore(ctx)
        // Parse the body OUTSIDE the lock (receive is suspending; the critical section must not suspend).
        val body = runCatching { call.receive<PairRequest>() }.getOrNull()
        // Critical section (#150 crypto review F1 + F3): the already-provisioned check, the key pin, and
        // the genesis coupling (floor + marker) run under ONE lock so (a) two concurrent first-pair POSTs
        // cannot both win the pin (TOCTOU), and (b) pin + provisioning-marker + seeded-floor commit
        // together as one genesis (PROTOCOL §5 item 6) — never a half-provisioned child whose first
        // signed bundle is then rejected as a "missing floor" anomaly. "Already paired" is the
        // provisioning MARKER (not merely the key file): a crash-partial pair re-runs here, not 409-wedges.
        val commit =
            synchronized(pairLock) {
                when (val decision = PairingAdmission.decide(body?.parentPubkey, alreadyPaired = floor.isProvisioned())) {
                    is PairingAdmission.Outcome.AlreadyPaired -> {
                        PairCommit.AlreadyPaired
                    }

                    is PairingAdmission.Outcome.Malformed -> {
                        PairCommit.Malformed(decision.reason)
                    }

                    is PairingAdmission.Outcome.Accept -> {
                        runCatching {
                            store.pinParentPubkey(decision.rawPubkey)
                            floor.seedGenesisProvisioning()
                            floor.childDeviceId()
                        }.fold(
                            onSuccess = { childId -> PairCommit.Paired(childId) },
                            // Fail-closed: any pin/seed failure -> no "paired" claim. The marker is written
                            // LAST, so a failure leaves the child not-provisioned and /pair can be retried.
                            onFailure = { PairCommit.Failed },
                        )
                    }
                }
            }
        when (commit) {
            PairCommit.AlreadyPaired -> {
                call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "ALREADY_PAIRED", "reason" to "this child is already paired; re-pairing is recovery-gated"),
                )
            }

            is PairCommit.Malformed -> {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "MALFORMED", "reason" to commit.reason),
                )
            }

            PairCommit.Failed -> {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "PIN_FAILED", "reason" to "could not persist the pairing"),
                )
            }

            is PairCommit.Paired -> {
                call.respond(
                    HttpStatusCode.OK,
                    PairResponse(status = "paired", childId = commit.childId),
                )
            }
        }
    }

    /** Result of the locked demo-pair critical section, mapped to an HTTP response outside the lock. */
    private sealed interface PairCommit {
        data class Paired(
            val childId: String,
        ) : PairCommit

        data object AlreadyPaired : PairCommit

        data class Malformed(
            val reason: String,
        ) : PairCommit

        data object Failed : PairCommit
    }

    /**
     * Admit a [SignedCommand] for [expectedType] (ADR-030). [CommandGate] verifies the parent Ed25519
     * signature against the pinned key + audience + endpoint/type binding + monotonic replay floor +
     * freshness window, and on accept atomically advances the floor and sets the durable lock flag.
     * A lock additionally fires the best-effort DPM keyguard (`lockNow`); the authenticated state is
     * already durable via the gate regardless.
     *
     * Fail-closed: an unparseable body, an unverified/replayed/stale/mis-typed command, OR a
     * durable-write failure inside the gate all return 400 and change no state.
     */
    private suspend fun handleCommand(
        call: ApplicationCall,
        expectedType: String,
    ) {
        val ctx = context
        val cmd = runCatching { call.receive<SignedCommand>() }.getOrNull()
        val pinned = PolicyStore(ctx).parentPubkey()
        val gate = CommandGate(ReplayFloorStore(ctx))
        // Capture an accepted lock so the keyguard side effect fires AFTER the durable state lands.
        var acceptedLock = false
        val resp =
            CommandDispatch.dispatch(cmd, expectedType, pinned) { c, t, p ->
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
        const val BUILD_VERSION = "0.1.0-dev"
        private const val TAG = "ApiServer"

        /**
         * Max accepted `/policy` request body (bytes). The signed canonical bundle is bounded to
         * [PolicyAdmission.MAX_CANONICAL_SIZE] (65536); this adds headroom for JSON syntax + the hex
         * `sig` field so a legitimate bundle is never rejected, while capping the pre-parse buffer so
         * a LAN host cannot OOM the child with an unbounded body (fail-closed DoS guard).
         */
        const val MAX_POLICY_BODY_BYTES = 131072L

        /**
         * Max accepted `/pair` request body (bytes). The body is a single ~44-char base64 Ed25519 key
         * in a tiny JSON envelope; 4 KiB is generous headroom while capping the pre-parse buffer so a
         * LAN host cannot OOM the child with an unbounded body (fail-closed DoS guard, mirrors `/policy`).
         */
        const val MAX_PAIR_BODY_BYTES = 4096L
    }
}

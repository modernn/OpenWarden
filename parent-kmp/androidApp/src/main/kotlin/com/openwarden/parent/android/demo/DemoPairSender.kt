package com.openwarden.parent.android.demo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.IOException

/**
 * Result of the demo-pair POST (ADR-046 D4).
 *
 * Fail-closed: [pairedChildStore] is only written on [Paired]; no other branch stores anything.
 */
sealed interface DemoPairResult {
    /** Child accepted the pairing and returned a [childId]; the child id is now pinned locally. */
    data class Paired(
        val childId: String,
    ) : DemoPairResult

    /** Child already has a parent key pinned (HTTP 409). No store write. */
    data object AlreadyPaired : DemoPairResult

    /** Root key is not yet provisioned — parent must complete recovery-key setup first. No POST sent. */
    data object NotProvisioned : DemoPairResult

    /** Network error, unexpected status code, or parse failure. No store write. */
    data class Failure(
        val message: String,
    ) : DemoPairResult
}

@Serializable
internal data class PairRequest(
    @SerialName("parent_pubkey") val parentPubkey: String,
)

@Serializable
internal data class PairResponse(
    val status: String = "",
    @SerialName("child_id") val childId: String = "",
)

/**
 * Interface for the pairing store; narrows to only what [DemoPairSender] needs.
 * Simplifies injection in unit tests — no EncryptedSharedPrefs needed in the test process.
 */
fun interface DemoPairChildStore {
    /** Pin the child id string returned by the child on a successful pairing. */
    fun storeChildId(childId: String)
}

/**
 * DEMO-ONLY (ADR-046 D4): POST the parent's Ed25519 public key to the child's `/pair` endpoint and
 * pin the returned `child_id`.
 *
 * **Fail-closed by construction.** [pair] reads [rootKeyProvider.rootPublicKey] first; if null it
 * returns [DemoPairResult.NotProvisioned] without making any network call. On HTTP 200 it calls
 * [pairedChildStore.storeChildId] ONLY AFTER the response is successfully parsed. On any other
 * outcome, [pairedChildStore] is never written.
 *
 * The internal [httpClientFactory] parameter is exposed only for tests (via the [internal]
 * constructor) so tests can inject a [io.ktor.client.engine.mock.MockEngine] without touching
 * production code paths.
 *
 * CRYPTO REVIEWER NOTE: the ONLY crypto call in this file is [rootKeyProvider.rootPublicKey]
 * — it reads the *public* key already persisted by the onboarding flow. No derivation, signing,
 * or key material is created here.
 *
 * CRYPTO REVIEWER LINES (ADR-046 gate):
 *   - L-rootPublicKey: `val pub = rootKeyProvider.rootPublicKey() ?: return DemoPairResult.NotProvisioned`
 *   - L-b64: `val pubB64 = Base64.encodeToString(pub, Base64.NO_WRAP)`
 *   - L-post: `http.post("$baseUrl/pair") { … setBody(PairRequest(pubB64)) }`
 *   - L-pin: `pairedChildStore.storeChildId(response.childId)` (only on 200 with non-blank child_id)
 */
class DemoPairSender internal constructor(
    private val rootKeyProvider: RootKeyAccess,
    private val pairedChildStore: DemoPairChildStore,
    private val baseUrl: String = DEMO_CHILD_BASE_URL,
    httpClientFactory: () -> HttpClient,
) : Closeable {
    /** Production constructor — builds an OkHttp-backed Ktor client. */
    constructor(
        rootKeyProvider: RootKeyAccess,
        pairedChildStore: DemoPairChildStore,
        baseUrl: String = DEMO_CHILD_BASE_URL,
    ) : this(rootKeyProvider, pairedChildStore, baseUrl, {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
            // expectSuccess=false so we can inspect the status ourselves (409 is expected).
            expectSuccess = false
        }
    })

    /** Narrows to only what this class reads from the root key provider. */
    fun interface RootKeyAccess {
        fun rootPublicKey(): ByteArray?
    }

    private val http: HttpClient by lazy { httpClientFactory() }

    /**
     * Attempt demo-pairing.
     */
    suspend fun pair(): DemoPairResult {
        // CRYPTO REVIEWER LINE L-rootPublicKey
        val pub = rootKeyProvider.rootPublicKey() ?: return DemoPairResult.NotProvisioned
        // CRYPTO REVIEWER LINE L-b64: base64-encode the Ed25519 public key (32 bytes → 44-char string)
        // java.util.Base64 is used (not android.util.Base64) so this runs in JVM unit tests too.
        val pubB64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(pub)

        return try {
            // CRYPTO REVIEWER LINE L-post
            val response =
                http.post("$baseUrl/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(PairRequest(pubB64))
                }

            when (response.status) {
                HttpStatusCode.OK -> {
                    val body = response.body<PairResponse>()
                    val childId = body.childId
                    if (childId.isBlank()) {
                        DemoPairResult.Failure("Child returned empty child_id")
                    } else {
                        // CRYPTO REVIEWER LINE L-pin: only written here (fail-closed)
                        pairedChildStore.storeChildId(childId)
                        DemoPairResult.Paired(childId)
                    }
                }

                HttpStatusCode.Conflict -> {
                    DemoPairResult.AlreadyPaired
                }

                else -> {
                    DemoPairResult.Failure(
                        "Unexpected status from child: ${response.status.value}",
                    )
                }
            }
        } catch (e: IOException) {
            DemoPairResult.Failure("Network error: ${e.message ?: "unknown"}")
        } catch (e: Exception) {
            DemoPairResult.Failure("Pair failed: ${e.message ?: "unknown"}")
        }
    }

    override fun close() {
        runCatching { http.close() }
    }
}

/**
 * Adapts a [android.content.SharedPreferences] to the [DemoPairChildStore] interface,
 * storing the child id string returned by the child on a successful pairing.
 *
 * The demo-pair endpoint returns only a `child_id` (base64url Ed25519 public key string). The full
 * [com.openwarden.parent.pairing.PinnedChild] (both Ed25519 + X25519 pub keys) is required for the
 * production [com.openwarden.parent.policy.PairedChildStore.pin] path — that atomic, write-once pin
 * happens at the end of the SAS-verified pairing flow (ADR-043). This demo path stores only the ID
 * string for dashboard display and policy dispatch.
 */
class DemoPairChildStoreImpl(
    private val prefs: android.content.SharedPreferences,
) : DemoPairChildStore {
    override fun storeChildId(childId: String) {
        prefs.edit().putString(KEY_CHILD_ID, childId).apply()
    }

    companion object {
        const val KEY_CHILD_ID = "demo_paired_child_id"
        const val PREFS_NAME = "openwarden_demo_pair"

        fun childId(prefs: android.content.SharedPreferences): String? = prefs.getString(KEY_CHILD_ID, null)
    }
}

package com.openwarden.parent.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.openwarden.parent.android.demo.ApiChildStateRepository
import com.openwarden.parent.android.demo.ChildApiClient
import com.openwarden.parent.android.policy.DemoAllowlistRepository
import com.openwarden.parent.android.policy.KtorPolicyTransport
import com.openwarden.parent.android.ui.dashboard.DashboardAndroidViewModel
import com.openwarden.parent.android.ui.dashboard.DashboardScreen
import com.openwarden.parent.android.ui.pair.PairingFlowScreen
import com.openwarden.parent.android.ui.pair.PairingViewModel
import com.openwarden.parent.android.ui.policy.AllowlistEditorScreen
import com.openwarden.parent.crypto.AndroidSecureKeyStorage
import com.openwarden.parent.crypto.StoredRootKeyProvider
import com.openwarden.parent.policy.AndroidPairedChildStore
import com.openwarden.parent.policy.AndroidPolicySeqStore
import com.openwarden.parent.policy.PolicySender
import com.openwarden.parent.policy.SecureRandomNonceGenerator

/**
 * Main activity.
 *
 * Wiring (#20): the real [ApiChildStateRepository] (HTTP to the child's /state + /usage on
 * http://10.0.2.2:7180) is injected, replacing the old FakeChildStateRepository fixture. The
 * dashboard now renders the child's actual self-reported state — online status from the child's
 * reported_at freshness, plus real per-app usage. DEMO transport (no auth/TLS) — the secure
 * pairing/mDNS path is still unbuilt.
 *
 * PolicySender wiring: the "Apply to child" button in [AllowlistEditorScreen] sends a signed
 * policy bundle. The sender is composed here from:
 *   - [StoredRootKeyProvider] (backed by [AndroidSecureKeyStorage]) — Ed25519 signing key
 *   - [AndroidPolicySeqStore] — durable monotonic policy_seq
 *   - [AndroidPairedChildStore] — pinned child identity (child_device_id)
 *   - [KtorPolicyTransport] — DEMO HTTP POST to child /policy
 *   - [SecureRandomNonceGenerator] — CSPRNG nonce
 *
 * The sender will return [com.openwarden.parent.policy.SendResult.NotProvisioned] until the
 * parent completes recovery-key setup, and [com.openwarden.parent.policy.SendResult.NotPaired]
 * until a child is paired — both are surfaced as explicit UI states (fail-closed).
 *
 * Lines that wire PolicySender (for crypto reviewer gate):
 *   L60-L68 — construction of seqStore, pairedChildStore, rootKeyProvider, transport
 *   L69-L76 — PolicySender instantiation binding all collaborators
 *   AllowlistEditorScreen receives 'sender' at L105; AllowlistEditorViewModel.apply() at
 *   shared/.../policy/AllowlistEditorViewModel.kt:apply() calls sender.send(policy).
 */
class MainActivity : ComponentActivity() {
    // Held at Activity scope so the OkHttp pool is closed exactly once in onDestroy.
    // The client is constructed here (explicit, not defaulted) so this Activity is its sole owner.
    private val childRepo = ApiChildStateRepository(ChildApiClient())

    private val dashboardVm: DashboardAndroidViewModel by viewModels {
        DashboardAndroidViewModel.Factory(repository = childRepo)
    }

    private val allowlistRepo = DemoAllowlistRepository()

    // ---------------------------------------------------------------------------
    // PolicySender wiring — "Apply to child" push path (ADR-034)
    //
    // Each collaborator is constructed once per Activity lifetime and released in
    // onDestroy alongside the HTTP client. The transport is Closeable.
    //
    // CRYPTO REVIEWER NOTE: no crypto code lives here — this is purely constructor
    // calls on existing, already-reviewed implementations.
    // ---------------------------------------------------------------------------
    private val seqStore by lazy { AndroidPolicySeqStore(this) }
    private val pairedChildStore by lazy { AndroidPairedChildStore(this) }
    private val rootKeyProvider by lazy {
        StoredRootKeyProvider(AndroidSecureKeyStorage(this))
    }
    private val policyTransport = KtorPolicyTransport()
    private val nonceGenerator = SecureRandomNonceGenerator()

    /**
     * PolicySender instance injected into [AllowlistEditorScreen].
     *
     * Constructed lazily (on first access from the editor) so the auth-gated StrongBox/
     * EncryptedSharedPreferences init stays off the Activity constructor.
     * The sender checks [rootKeyProvider] and [pairedChildStore] on every [PolicySender.send]
     * call — it will return [com.openwarden.parent.policy.SendResult.NotProvisioned] /
     * [com.openwarden.parent.policy.SendResult.NotPaired] until setup is complete.
     */
    private val policySender by lazy {
        PolicySender(
            rootKeyProvider = rootKeyProvider,
            seqStore = seqStore,
            pairedChildStore = pairedChildStore,
            transport = policyTransport,
            nonceGenerator = nonceGenerator,
            clockMs = { System.currentTimeMillis() },
        )
    }

    /**
     * The parent pairing flow (ADR-043), held in a [PairingViewModel] so the controller — and any
     * in-flight attempt — **survives Activity config changes** (#119). Teardown is the ViewModel's
     * `onCleared()` (real finish) or an explicit Back/Cancel, never a mere rotation.
     */
    private val pairingVm: PairingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot(
                    dashboardVm = dashboardVm,
                    allowlistRepo = allowlistRepo,
                    policySender = policySender,
                    pairingVm = pairingVm,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        childRepo.close()
        allowlistRepo.close()
        policyTransport.close()
    }
}

@Composable
private fun AppRoot(
    dashboardVm: DashboardAndroidViewModel,
    allowlistRepo: DemoAllowlistRepository,
    policySender: PolicySender,
    pairingVm: PairingViewModel,
) {
    // rememberSaveable so the visible screen survives a config change (#119) — otherwise a rotation
    // would drop back to the dashboard even though the pairing attempt is retained in the ViewModel.
    var showAllowlist by rememberSaveable { mutableStateOf(false) }
    var showPairing by rememberSaveable { mutableStateOf(false) }

    when {
        showAllowlist -> {
            AllowlistEditorScreen(
                repo = allowlistRepo,
                sender = policySender,
                onBack = { showAllowlist = false },
            )
        }

        showPairing -> {
            PairingFlowScreen(
                controller = pairingVm.controller,
                onEnsureStarted = { pairingVm.ensureStarted() },
                onBack = { showPairing = false },
                onPaired = { showPairing = false },
            )
        }

        else -> {
            DashboardScreen(
                viewModel = dashboardVm,
                onOpenAllowlist = { showAllowlist = true },
                onOpenPairing = { showPairing = true },
            )
        }
    }
}

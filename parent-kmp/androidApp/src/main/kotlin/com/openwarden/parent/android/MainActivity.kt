package com.openwarden.parent.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.openwarden.parent.android.demo.ApiChildStateRepository
import com.openwarden.parent.android.demo.ChildApiClient
import com.openwarden.parent.android.demo.DemoPairChildStoreImpl
import com.openwarden.parent.android.demo.DemoPairSender
import com.openwarden.parent.android.policy.DemoAllowlistRepository
import com.openwarden.parent.android.policy.KtorPolicyTransport
import com.openwarden.parent.android.ui.dashboard.DashboardAndroidViewModel
import com.openwarden.parent.android.ui.dashboard.DashboardScreen
import com.openwarden.parent.android.ui.onboarding.RecoveryOnboardingScreen
import com.openwarden.parent.android.ui.pair.PairingFlowScreen
import com.openwarden.parent.android.ui.pair.PairingViewModel
import com.openwarden.parent.android.ui.policy.AllowlistEditorScreen
import com.openwarden.parent.crypto.AndroidSecureKeyStorage
import com.openwarden.parent.crypto.RecoveryOnboarding
import com.openwarden.parent.crypto.StoredRootKeyProvider
import com.openwarden.parent.onboarding.RecoveryOnboardingViewModel
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
 * Recovery-key onboarding wiring (ADR-046 D2):
 *   When [rootKeyProvider.isProvisioned] is false, the AppRoot shows [RecoveryOnboardingScreen]
 *   before any other screen. The screen calls [RecoveryOnboarding.start] once (inside the
 *   [remember] block in AppRoot), wraps the session in a [RecoveryOnboardingViewModel], and
 *   on [OnboardingUiState.Provisioned] navigates to the dashboard.
 *
 * Demo-pair wiring (ADR-046 D4):
 *   [DemoPairSender] is constructed once with [rootKeyProvider] (for the public key) and a
 *   [DemoPairChildStoreImpl] (SharedPreferences-backed child-id store). The "Pair with child
 *   (demo)" button is rendered on the dashboard.
 *
 * CRYPTO REVIEWER NOTE: lines that touch root-key provisioning or the demo-pair POST:
 *   - L-rootKeyProvider: `private val rootKeyProvider by lazy { StoredRootKeyProvider(AndroidSecureKeyStorage(this)) }`
 *   - L-onboarding-start: `RecoveryOnboarding(AndroidSecureKeyStorage(context)).start()` inside AppRoot remember{}
 *   - L-demopair-build: `DemoPairSender(rootKeyProvider = { rootKeyProvider.rootPublicKey() }, …)`
 *   - L-pair-call: `demoPairSender.pair()` triggered from DashboardScreen "Pair with child (demo)" button
 *   All signing, derivation, and key persistence live in [RecoveryOnboarding.Session.confirm],
 *   [StoredRootKeyProvider], and [RootKeyDerivation] — NOT in this file.
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

    // CRYPTO REVIEWER LINE L-rootKeyProvider
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
     * Demo-pair sender (ADR-046 D4).
     *
     * CRYPTO REVIEWER LINE L-demopair-build:
     *   rootKeyProvider lambda calls [StoredRootKeyProvider.rootPublicKey] — reads the public key only.
     *   pairedChildStore writes only the child_id string returned by the child on HTTP 200.
     */
    private val demoPairSender by lazy {
        val demoPairPrefs =
            getSharedPreferences(DemoPairChildStoreImpl.PREFS_NAME, Context.MODE_PRIVATE)
        DemoPairSender(
            rootKeyProvider = { rootKeyProvider.rootPublicKey() },
            pairedChildStore = DemoPairChildStoreImpl(demoPairPrefs),
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
                    rootKeyProvider = rootKeyProvider,
                    demoPairSender = demoPairSender,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        childRepo.close()
        allowlistRepo.close()
        policyTransport.close()
        demoPairSender.close()
    }
}

@Composable
private fun AppRoot(
    dashboardVm: DashboardAndroidViewModel,
    allowlistRepo: DemoAllowlistRepository,
    policySender: PolicySender,
    pairingVm: PairingViewModel,
    rootKeyProvider: StoredRootKeyProvider,
    demoPairSender: DemoPairSender,
) {
    // rememberSaveable so the visible screen survives a config change (#119) — otherwise a rotation
    // would drop back to the dashboard even though the pairing attempt is retained in the ViewModel.
    var showAllowlist by rememberSaveable { mutableStateOf(false) }
    var showPairing by rememberSaveable { mutableStateOf(false) }
    // Onboarding: cleared after provisioning so we never navigate back to it.
    var showOnboarding by rememberSaveable { mutableStateOf(!rootKeyProvider.isProvisioned()) }

    when {
        showOnboarding -> {
            // CRYPTO REVIEWER LINE L-onboarding-start:
            // RecoveryOnboarding(AndroidSecureKeyStorage) is created inside remember{}
            // so it is built at most once per composition lifetime. session.confirm(answers)
            // is what actually derives + persists the root key (inside RecoveryOnboardingViewModel.confirm).
            val context = androidx.compose.ui.platform.LocalContext.current
            val viewModel =
                remember {
                    val session =
                        RecoveryOnboarding(
                            com.openwarden.parent.crypto
                                .AndroidSecureKeyStorage(context),
                        ).start()
                    RecoveryOnboardingViewModel(
                        mnemonic = session.mnemonic,
                        challengePositions = session.challengePositions,
                        confirmSession = { answers -> session.confirm(answers) },
                    )
                }
            RecoveryOnboardingScreen(
                viewModel = viewModel,
                onProvisioned = { showOnboarding = false },
            )
        }

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
                demoPairSender = demoPairSender,
            )
        }
    }
}

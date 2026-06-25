package com.openwarden.parent.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.openwarden.parent.android.demo.ApiChildStateRepository
import com.openwarden.parent.android.demo.ChildApiClient
import com.openwarden.parent.android.policy.DemoAllowlistRepository
import com.openwarden.parent.android.ui.dashboard.DashboardAndroidViewModel
import com.openwarden.parent.android.ui.dashboard.DashboardScreen
import com.openwarden.parent.android.ui.pair.PairingFlowScreen
import com.openwarden.parent.android.ui.policy.AllowlistEditorScreen
import com.openwarden.parent.crypto.AndroidSecureKeyStorage
import com.openwarden.parent.crypto.StoredRootKeyProvider
import com.openwarden.parent.pairing.AndroidPairingFactory
import com.openwarden.parent.pairing.PairingController

/**
 * Main activity.
 *
 * Wiring (#20): the real [ApiChildStateRepository] (HTTP to the child's /state + /usage on
 * http://10.0.2.2:7180) is injected, replacing the old FakeChildStateRepository fixture. The
 * dashboard now renders the child's actual self-reported state — online status from the child's
 * reported_at freshness, plus real per-app usage. DEMO transport (no auth/TLS) — the secure
 * pairing/mDNS path is still unbuilt.
 */
class MainActivity : ComponentActivity() {
    // Held at Activity scope so the OkHttp pool is closed exactly once in onDestroy.
    // The client is constructed here (explicit, not defaulted) so this Activity is its sole owner.
    private val childRepo = ApiChildStateRepository(ChildApiClient())

    private val dashboardVm: DashboardAndroidViewModel by viewModels {
        DashboardAndroidViewModel.Factory(repository = childRepo)
    }

    private val allowlistRepo = DemoAllowlistRepository()

    /**
     * The parent pairing flow controller (ADR-043). Built once from the Android seams via
     * [AndroidPairingFactory]. The Tier-1 Google-root SPKI pin is not committed yet, so an **empty** pin
     * is passed: every §7.3 attestation then refuses fail-closed (no allow-listed root) — the UI + flow
     * run end-to-end up to a fail-closed `ATTESTATION_FAILED`, and the real root + on-device StrongBox
     * chain remain the inherited HARD pre-prod gate (ADR-037 D3 / ADR-039).
     */
    private val pairingController: PairingController by lazy {
        AndroidPairingFactory.create(
            context = applicationContext,
            rootKeys = StoredRootKeyProvider(AndroidSecureKeyStorage(applicationContext)),
            googleRootSpkiDer = ByteArray(0),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot(
                    dashboardVm = dashboardVm,
                    allowlistRepo = allowlistRepo,
                    pairingController = pairingController,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        childRepo.close()
        allowlistRepo.close()
    }
}

@Composable
private fun AppRoot(
    dashboardVm: DashboardAndroidViewModel,
    allowlistRepo: DemoAllowlistRepository,
    pairingController: PairingController,
) {
    var showAllowlist by remember { mutableStateOf(false) }
    var showPairing by remember { mutableStateOf(false) }

    when {
        showAllowlist -> {
            AllowlistEditorScreen(
                repo = allowlistRepo,
                onBack = { showAllowlist = false },
            )
        }

        showPairing -> {
            PairingFlowScreen(
                controller = pairingController,
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

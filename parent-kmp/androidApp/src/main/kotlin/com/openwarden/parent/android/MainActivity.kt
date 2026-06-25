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
import com.openwarden.parent.android.ui.dashboard.DashboardAndroidViewModel
import com.openwarden.parent.android.ui.dashboard.DashboardScreen
import com.openwarden.parent.android.ui.pair.PairingFlowScreen
import com.openwarden.parent.android.ui.pair.PairingViewModel
import com.openwarden.parent.android.ui.policy.AllowlistEditorScreen

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
                    pairingVm = pairingVm,
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

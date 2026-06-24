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
import com.openwarden.parent.android.policy.DemoAllowlistRepository
import com.openwarden.parent.android.ui.dashboard.DashboardAndroidViewModel
import com.openwarden.parent.android.ui.dashboard.DashboardScreen
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
    private val childRepo = ApiChildStateRepository()

    private val dashboardVm: DashboardAndroidViewModel by viewModels {
        DashboardAndroidViewModel.Factory(repository = childRepo)
    }

    private val allowlistRepo = DemoAllowlistRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot(
                    dashboardVm = dashboardVm,
                    allowlistRepo = allowlistRepo,
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
) {
    var showAllowlist by remember { mutableStateOf(false) }

    if (showAllowlist) {
        AllowlistEditorScreen(
            repo = allowlistRepo,
            onBack = { showAllowlist = false },
        )
    } else {
        DashboardScreen(
            viewModel = dashboardVm,
            onOpenAllowlist = { showAllowlist = true },
        )
    }
}

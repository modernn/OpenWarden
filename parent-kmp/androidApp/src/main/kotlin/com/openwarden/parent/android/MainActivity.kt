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
import com.openwarden.parent.android.policy.DemoAllowlistRepository
import com.openwarden.parent.android.ui.dashboard.DashboardAndroidViewModel
import com.openwarden.parent.android.ui.dashboard.DashboardScreen
import com.openwarden.parent.android.ui.policy.AllowlistEditorScreen
import com.openwarden.parent.dashboard.FakeChildStateRepository

/**
 * Main activity.
 *
 * Wiring note: the real [ChildStateRepository] (HTTP to child /state + /usage)
 * is not yet built (depends on issue #20).  Until then the [FakeChildStateRepository]
 * online scenario is injected so the dashboard renders live fixture data.
 * Replace FakeChildStateRepository with the real HTTP implementation when #20 lands.
 */
class MainActivity : ComponentActivity() {

    private val dashboardVm: DashboardAndroidViewModel by viewModels {
        // TODO(#20): replace FakeChildStateRepository with the real HTTP client
        //            once child /state and /usage endpoints are built.
        DashboardAndroidViewModel.Factory(
            repository = FakeChildStateRepository(FakeChildStateRepository.Scenario.Online),
        )
    }

    // Repository is held at Activity scope so it is closed exactly once in onDestroy,
    // releasing the underlying OkHttp thread pool and connection pool.
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

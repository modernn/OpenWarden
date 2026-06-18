package com.openwarden.parent.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.openwarden.parent.android.ui.dashboard.DashboardAndroidViewModel
import com.openwarden.parent.android.ui.dashboard.DashboardScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DashboardScreen(viewModel = dashboardVm)
            }
        }
    }
}

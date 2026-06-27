package com.openwarden.parent.android.ui.pair

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.openwarden.parent.crypto.AndroidSecureKeyStorage
import com.openwarden.parent.crypto.StoredRootKeyProvider
import com.openwarden.parent.pairing.AndroidPairingFactory
import com.openwarden.parent.pairing.PairingController

/**
 * Retains the [PairingController] across Activity config changes (#119, ADR-043 D6 / Codex F6).
 *
 * Before this, the controller lived as an Activity property and `PairingFlowScreen` cancelled the attempt
 * in `DisposableEffect.onDispose` — so a screen rotation (which recreates the Activity + composition)
 * **burned the in-flight pairing**. Holding the controller in a `ViewModel` makes it survive recreation;
 * teardown happens only on real flow exit ([onCleared], i.e. the Activity is genuinely finishing) or an
 * explicit Back/Cancel — not on a mere config change.
 *
 * Built from the same Android seams as the old Activity wiring. The empty Google-root SPKI keeps
 * attestation fail-closed until the real root is committed (inherited HARD pre-prod gate, ADR-043 D6).
 */
class PairingViewModel(
    app: Application,
) : AndroidViewModel(app) {
    val controller: PairingController by lazy {
        AndroidPairingFactory.create(
            context = getApplication<Application>().applicationContext,
            rootKeys = StoredRootKeyProvider(AndroidSecureKeyStorage(getApplication())),
            googleRootSpkiDer = ByteArray(0),
        )
    }

    /** Start an attempt on screen entry, idempotently — a config-change re-entry does not restart it. */
    fun ensureStarted() = controller.ensureStarted()

    /**
     * The Activity is finishing for real (not a config change) — burn any live attempt and stop the
     * listener so it never outlives the flow. Config changes do NOT call this (the ViewModel survives).
     */
    override fun onCleared() {
        super.onCleared()
        controller.cancel()
    }
}

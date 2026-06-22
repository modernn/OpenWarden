package com.openwarden.child

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises the child LAN server over mDNS / DNS-SD (`_openwarden._tcp`) via Android `NsdManager`, so
 * the parent app can discover it without a hand-typed IP (ADR-031 D3). Discovery is **untrusted**:
 * trust comes only from the identity-bound TLS SPKI ([SpkiBinding]) + app-layer Ed25519 verification
 * (ADR-031 D1/D2). This class only broadcasts presence + the child audience id (see [MdnsServiceSpec]),
 * never a secret.
 *
 * **Fail-safe:** every `NsdManager` interaction is wrapped — a registration failure is logged and
 * swallowed. Advertising is orthogonal to policy enforcement, so a discovery hiccup must never crash
 * the server nor weaken enforcement; the worst case is the parent enters the IP by hand.
 */
class MdnsAdvertiser(private val context: Context) {

    private val nsd: NsdManager?
        get() = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var listener: NsdManager.RegistrationListener? = null

    /**
     * Register [spec]. Idempotent-ish: a prior registration is unregistered first. No-op on failure.
     * `@Synchronized` so concurrent/rapid start↔stop (lifecycle relaunch, config change) cannot race
     * the mutable [listener] against an in-flight async NsdManager callback.
     */
    @Synchronized
    fun start(spec: MdnsServiceSpec) {
        stop()
        val manager = nsd ?: run {
            Log.w(TAG, "NSD service unavailable; skipping mDNS advertise")
            return
        }
        val info = NsdServiceInfo().apply {
            serviceName = spec.serviceName
            serviceType = spec.serviceType
            port = spec.port
            spec.txtRecords.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS advertised: ${info.serviceName} ${info.serviceType}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed (error $errorCode); discovery unavailable")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed (error $errorCode)")
            }
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onSuccess { listener = l }
            .onFailure { Log.w(TAG, "mDNS registerService threw; discovery unavailable", it) }
    }

    /** Unregister any active advertisement. No-op if none / on failure. `@Synchronized` — see [start]. */
    @Synchronized
    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd?.unregisterService(l) }
            .onFailure { Log.w(TAG, "mDNS unregisterService threw", it) }
    }

    private companion object {
        const val TAG = "MdnsAdvertiser"
    }
}

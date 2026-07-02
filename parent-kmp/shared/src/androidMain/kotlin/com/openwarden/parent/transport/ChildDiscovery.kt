package com.openwarden.parent.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Parent-side mDNS / DNS-SD discovery of the child's LAN server (`_openwarden._tcp`) via Android
 * `NsdManager` — the counterpart to the child's advertiser (ADR-031 D3). It removes the hand-typed IP:
 * the resolved host/port feed [PinnedChildConnector], which is where **all** trust is established.
 *
 * Discovery is **untrusted** (ADR-031 D3): mDNS/TXT is spoofable by design (the ARP/mDNS-spoof vector of
 * red-team TR1). Nothing resolved here is trusted — a spoofed `_openwarden._tcp` responder is defeated
 * not by discovery but by [PinnedChildConnector] (it cannot present a leaf with a valid identity-bound
 * assertion). This class only turns a service record into a candidate `host:port` to *attempt* a pinned
 * connection to; the TXT child id is a disambiguation hint, never a trust signal.
 *
 * **Fail-safe:** every `NsdManager` interaction is wrapped — a discovery/resolve failure is logged and
 * swallowed (the worst case is the parent falls back to a hand-entered address). Discovery is orthogonal
 * to trust and to enforcement.
 */
class ChildDiscovery(
    private val context: Context,
) {
    /** A discovered candidate endpoint. UNTRUSTED until [PinnedChildConnector] verifies the pin. */
    data class Discovered(
        val host: String,
        val port: Int,
        val childId: String?,
    )

    private val nsd: NsdManager?
        get() = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var listener: NsdManager.DiscoveryListener? = null

    /**
     * Start discovering `_openwarden._tcp`. [onFound] is invoked (on an NSD callback thread) for each
     * resolved candidate. No-op / logged on any failure. Call [stop] when done.
     */
    @Synchronized
    fun start(onFound: (Discovered) -> Unit) {
        stop()
        val manager =
            nsd ?: run {
                Log.w(TAG, "NSD service unavailable; skipping mDNS discovery")
                return
            }
        val l =
            object : NsdManager.DiscoveryListener {
                @Suppress("DEPRECATION") // resolveService(info, listener) is the minSdk-28-compatible overload
                override fun onServiceFound(service: NsdServiceInfo) {
                    // Resolve to obtain host/port; untrusted — only a candidate to attempt a pinned connect.
                    runCatching { manager.resolveService(service, resolveListener(onFound)) }
                        .onFailure { Log.w(TAG, "resolveService threw for ${service.serviceName}", it) }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    Log.i(TAG, "mDNS service lost: ${service.serviceName}")
                }

                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "mDNS discovery start failed (error $errorCode)")
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "mDNS discovery stop failed (error $errorCode)")
                }
            }
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onSuccess { listener = l }
            .onFailure { Log.w(TAG, "discoverServices threw; discovery unavailable", it) }
    }

    /** Stop any active discovery. No-op if none / on failure. */
    @Synchronized
    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd?.stopServiceDiscovery(l) }
            .onFailure { Log.w(TAG, "stopServiceDiscovery threw", it) }
    }

    private fun resolveListener(onFound: (Discovered) -> Unit) =
        object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                runCatching {
                    @Suppress("DEPRECATION") // getHost() is the minSdk-28-compatible accessor
                    val host =
                        info.host?.hostAddress ?: run {
                            Log.w(TAG, "resolved service ${info.serviceName} has no host address; ignoring")
                            return
                        }
                    val childId = info.attributes[TXT_CHILD_ID]?.let { String(it, Charsets.UTF_8) }
                    onFound(Discovered(host = host, port = info.port, childId = childId))
                }.onFailure { Log.w(TAG, "onServiceResolved handling threw", it) }
            }

            override fun onResolveFailed(
                info: NsdServiceInfo,
                errorCode: Int,
            ) {
                Log.w(TAG, "mDNS resolve failed (error $errorCode) for ${info.serviceName}")
            }
        }

    companion object {
        /** DNS-SD service type advertised by the child (ADR-031 D3); mirrors child-android `MdnsServiceSpec`. */
        const val SERVICE_TYPE = "_openwarden._tcp"

        /** TXT key carrying the child audience id (disambiguation only — never trusted). */
        const val TXT_CHILD_ID = "id"

        private const val TAG = "ChildDiscovery"
    }
}

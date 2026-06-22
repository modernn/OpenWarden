package com.openwarden.child

/**
 * The mDNS / DNS-SD advertisement for this child's LAN server (ADR-031 D3). Pure + validated so it is
 * unit-testable without a device; [MdnsAdvertiser] turns it into an Android `NsdManager` registration.
 *
 * mDNS/TXT is **untrusted discovery only** — it is spoofable by design (the ARP/mDNS-spoof vector of
 * red-team TR1), so it carries only the child audience id for disambiguation when several children
 * share a LAN, **never a secret**. All trust comes from the identity-bound TLS SPKI ([SpkiBinding])
 * plus app-layer Ed25519 verification (ADR-031 D1/D2), not from anything advertised here.
 */
data class MdnsServiceSpec(
    val serviceType: String,
    val serviceName: String,
    val port: Int,
    val txtRecords: Map<String, String>,
) {
    companion object {
        /** DNS-SD service type for the child LAN server (PROTOCOL §4 / §7.1 transport hint). */
        const val SERVICE_TYPE = "_openwarden._tcp"

        /** TXT key carrying the child audience id (for multi-child LAN disambiguation). */
        const val TXT_CHILD_ID = "id"

        /** TXT key carrying the advertisement schema version. */
        const val TXT_VERSION = "v"

        /**
         * Build the canonical advertisement for [childId] on [port].
         *
         * @throws IllegalArgumentException if [childId] is empty or [port] is outside 1..65535 —
         *   fail-closed: we never advertise a malformed service record.
         */
        fun forChild(childId: String, port: Int): MdnsServiceSpec {
            require(childId.isNotEmpty()) { "childId must be non-empty" }
            require(port in 1..65535) { "port $port out of range 1..65535" }
            return MdnsServiceSpec(
                serviceType = SERVICE_TYPE,
                serviceName = "openwarden-$childId",
                port = port,
                txtRecords = mapOf(TXT_CHILD_ID to childId, TXT_VERSION to "1"),
            )
        }
    }
}

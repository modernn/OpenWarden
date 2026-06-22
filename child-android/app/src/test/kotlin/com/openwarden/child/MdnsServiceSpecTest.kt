package com.openwarden.child

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The pure mDNS advertisement spec (ADR-031 D3). Discovery is untrusted; the spec carries only the
 * child audience id + version, never a secret.
 */
class MdnsServiceSpecTest {

    @Test
    fun `forChild builds the canonical openwarden tcp advertisement`() {
        val spec = MdnsServiceSpec.forChild("child-abcd", 7180)
        assertEquals("_openwarden._tcp", spec.serviceType)
        assertEquals(7180, spec.port)
        assertEquals("openwarden-child-abcd", spec.serviceName)
        assertEquals("child-abcd", spec.txtRecords[MdnsServiceSpec.TXT_CHILD_ID])
        assertEquals("1", spec.txtRecords[MdnsServiceSpec.TXT_VERSION])
    }

    @Test
    fun `empty child id is rejected fail-closed`() {
        assertFailsWith<IllegalArgumentException> { MdnsServiceSpec.forChild("", 7180) }
    }

    @Test
    fun `out-of-range ports are rejected`() {
        assertFailsWith<IllegalArgumentException> { MdnsServiceSpec.forChild("child", 0) }
        assertFailsWith<IllegalArgumentException> { MdnsServiceSpec.forChild("child", 70000) }
    }

    @Test
    fun `txt records carry only id and version - never a secret (ADR-031 D3)`() {
        val spec = MdnsServiceSpec.forChild("child-abcd", 7180)
        assertEquals(setOf("id", "v"), spec.txtRecords.keys)
    }
}

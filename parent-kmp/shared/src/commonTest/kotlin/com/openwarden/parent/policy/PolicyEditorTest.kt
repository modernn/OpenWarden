package com.openwarden.parent.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyEditorTest {
    @Test
    fun toggleAddsThenRemoves() {
        val e = PolicyEditor()
        e.toggleAllowlist("com.example.app")
        assertTrue("com.example.app" in e.state.value.allowlist)
        e.toggleAllowlist("com.example.app")
        assertFalse("com.example.app" in e.state.value.allowlist)
    }

    @Test
    fun toProtoPolicyIsSortedAllowlist() {
        val e = PolicyEditor()
        e.toggleAllowlist("b.app")
        e.toggleAllowlist("a.app")
        assertEquals(listOf("a.app", "b.app"), e.toProtoPolicy().allowlist)
    }
}

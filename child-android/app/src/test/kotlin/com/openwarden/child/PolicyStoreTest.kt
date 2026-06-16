package com.openwarden.child

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PolicyStoreTest {

    private fun makeBundle(
        issuedAt: String = "2024-01-01T00:00:00Z",
        allowlist: List<String> = listOf("com.example.app"),
    ) = SignedBundle(
        v = 1,
        issued_at = issuedAt,
        expires_at = "2099-12-31T23:59:59Z",
        nonce = "test-nonce",
        policy = PolicyDoc(allowlist = allowlist),
        sig = "", // storage tests do not exercise crypto
    )

    @Test
    fun `round-trip persist then fresh instance loadActive returns equal bundle`() {
        val context = RuntimeEnvironment.getApplication()
        val store1 = PolicyStore(context)
        val bundle = makeBundle()

        store1.persist(bundle)

        // Simulate process death: new PolicyStore instance, same filesDir
        val store2 = PolicyStore(context)
        val loaded = store2.loadActive()

        assertEquals(bundle.v, loaded?.v, "v must round-trip")
        assertEquals(bundle.issued_at, loaded?.issued_at, "issued_at must round-trip")
        assertEquals(bundle.nonce, loaded?.nonce, "nonce must round-trip")
        assertEquals(bundle.policy.allowlist, loaded?.policy?.allowlist, "allowlist must round-trip")
    }

    @Test
    fun `missing file returns LoadResult-Missing and loadActive returns null`() {
        val context = RuntimeEnvironment.getApplication()
        // Use a sub-dir that has never had a bundle written to it
        val freshContext = object : android.content.ContextWrapper(context) {
            private val tmpDir = File(context.filesDir, "missing_test_${System.nanoTime()}")
            override fun getFilesDir(): File = tmpDir
        }
        val store = PolicyStore(freshContext)

        assertTrue(store.load() is PolicyStore.LoadResult.Missing, "load() must be Missing")
        assertNull(store.loadActive(), "loadActive() must return null for missing file")
    }

    @Test
    fun `corrupt file returns LoadResult-Corrupt and loadActive returns null`() {
        val context = RuntimeEnvironment.getApplication()
        val policyDir = File(context.filesDir, "corrupt_test_${System.nanoTime()}/policy")
        policyDir.mkdirs()
        File(policyDir, "active.json").writeText("{ not json")

        val corruptContext = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): File = policyDir.parentFile!!
        }
        val store = PolicyStore(corruptContext)

        assertTrue(store.load() is PolicyStore.LoadResult.Corrupt, "load() must be Corrupt")
        assertNull(store.loadActive(), "loadActive() must return null for corrupt file (fail-closed)")
    }

    @Test
    fun `no temp file left behind after successful persist`() {
        val context = RuntimeEnvironment.getApplication()
        val store = PolicyStore(context)
        val bundle = makeBundle()

        store.persist(bundle)

        val policyDir = File(context.filesDir, "policy")
        val tmp = File(policyDir, "active.json.tmp")
        assertFalse(tmp.exists(), "active.json.tmp must not exist after a successful persist")
    }

    @Test
    fun `atomic overwrite — second persist wins`() {
        val context = RuntimeEnvironment.getApplication()
        val store = PolicyStore(context)

        val bundleA = makeBundle(issuedAt = "2024-01-01T00:00:00Z", allowlist = listOf("com.a"))
        val bundleB = makeBundle(issuedAt = "2024-06-01T00:00:00Z", allowlist = listOf("com.b"))

        store.persist(bundleA)
        store.persist(bundleB)

        val loaded = store.loadActive()
        assertEquals(listOf("com.b"), loaded?.policy?.allowlist, "loadActive() must return bundle B after overwrite")
        assertEquals("2024-06-01T00:00:00Z", loaded?.issued_at, "issued_at must be B's value")
    }
}

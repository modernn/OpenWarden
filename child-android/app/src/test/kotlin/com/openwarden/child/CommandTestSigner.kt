package com.openwarden.child

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Shared Ed25519 test signer for [SignedCommand] — produces signatures [CommandVerifier] accepts,
 * mirroring [HeartbeatTestSigner]. Test-source only.
 */
object CommandTestSigner {
    private val curve = EdDSANamedCurveTable.getByName("Ed25519")

    class Keypair(
        val priv: EdDSAPrivateKey,
        val pubRaw: ByteArray,
    )

    fun newKeypair(): Keypair {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val privSpec = EdDSAPrivateKeySpec(seed, curve)
        return Keypair(EdDSAPrivateKey(privSpec), EdDSAPublicKeySpec(privSpec.a, curve).a.toByteArray())
    }

    /** Sign the JCS canonical command body (minus sig) and return it with a real hex sig. */
    fun sign(
        cmd: SignedCommand,
        kp: Keypair,
    ): SignedCommand {
        val body = CommandVerifier.canonicalBody(cmd.copy(sig = ""))
        val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        engine.initSign(kp.priv)
        engine.update(body)
        return cmd.copy(sig = engine.sign().joinToString("") { "%02x".format(it) })
    }
}

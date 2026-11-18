package fr.foodsafe.pos.core.journal

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Signature des documents fiscaux (ECDSA P-256 / SHA-256).
 * Sur Android, la clé privée sera générée dans l'Android Keystore (non exportable) ;
 * ici l'implémentation JVM sert aux tests et au vérificateur côté back-office.
 */
interface FiscalSigner {
    val keyId: String
    fun sign(data: ByteArray): String
}

class EcdsaSigner(override val keyId: String, private val privateKey: PrivateKey) : FiscalSigner {
    override fun sign(data: ByteArray): String = Signature.getInstance(ALGO).run {
        initSign(privateKey); update(data); Base64.getEncoder().encodeToString(sign())
    }
    companion object {
        const val ALGO = "SHA256withECDSA"
        fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }
}

object SignatureVerifier {
    fun verify(publicKey: PublicKey, data: ByteArray, signatureB64: String): Boolean = runCatching {
        Signature.getInstance(EcdsaSigner.ALGO).run {
            initVerify(publicKey); update(data); verify(Base64.getDecoder().decode(signatureB64))
        }
    }.getOrDefault(false)
}

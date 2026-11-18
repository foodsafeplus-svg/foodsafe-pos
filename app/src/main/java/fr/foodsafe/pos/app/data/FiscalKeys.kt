package fr.foodsafe.pos.app.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import fr.foodsafe.pos.core.journal.EcdsaSigner
import fr.foodsafe.pos.core.journal.FiscalSigner
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import fr.foodsafe.pos.core.util.B64

/** Fournit la clé de signature fiscale du terminal et sa clé publique (pour la vérification). */
interface FiscalKeyProvider {
    val signer: FiscalSigner
    val publicKey: PublicKey
}

/**
 * Clé ECDSA P-256 générée dans l'Android Keystore : la clé privée n'est jamais extractible de l'appareil
 * (matériel sécurisé quand le terminal en dispose). Une réinstallation de l'application génère une nouvelle clé
 * (nouvel identifiant) : les anciennes clés publiques restent dans fiscal_keys pour vérifier l'historique.
 */
class AndroidKeystoreKeyProvider(terminalId: String) : FiscalKeyProvider {
    private val alias = "foodsafe-fiscal-$terminalId"
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!ks.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair()
        }
    }

    override val publicKey: PublicKey = ks.getCertificate(alias).publicKey
    private val keyId = "$terminalId-" + fingerprint(publicKey)

    override val signer = object : FiscalSigner {
        override val keyId = this@AndroidKeystoreKeyProvider.keyId
        override fun sign(data: ByteArray): String {
            val key = ks.getKey(alias, null) as PrivateKey
            return Signature.getInstance(EcdsaSigner.ALGO).run {
                initSign(key); update(data); B64.encode(sign())
            }
        }
    }

    companion object {
        val isSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }
}

/** Clé logicielle : UNIQUEMENT pour les tests (Robolectric ne fournit pas d'Android Keystore). */
class SoftwareKeyProvider(terminalId: String) : FiscalKeyProvider {
    private val pair = EcdsaSigner.generateKeyPair()
    override val publicKey: PublicKey = pair.public
    override val signer = EcdsaSigner("$terminalId-" + fingerprint(pair.public), pair.private)
}

fun fingerprint(key: PublicKey): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(key.encoded).take(6).joinToString("") { "%02x".format(it) }

fun decodePublicKey(b64: String): PublicKey =
    java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(B64.decode(b64)))

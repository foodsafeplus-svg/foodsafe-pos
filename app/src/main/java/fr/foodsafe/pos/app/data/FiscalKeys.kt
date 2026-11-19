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

/**
 * Clé ECDSA P-256 logicielle, conservée dans le stockage privé de l'application. Repli UNIQUEMENT quand
 * l'Android Keystore du terminal est défaillant (constaté sur un Rockchip ECR-200 / Android 11 :
 * KeyStoreException « Unknown error » à la génération). Protection moindre que le Keystore (clé lisible
 * par un appareil rooté) : l'identifiant de clé porte le suffixe « SW » pour que ce soit visible au journal.
 */
class FileKeyProvider(terminalId: String, dir: java.io.File) : FiscalKeyProvider {
    private val file = java.io.File(dir, "fiscal-key-$terminalId.p8")
    private val pair: java.security.KeyPair = load() ?: EcdsaSigner.generateKeyPair().also { save(it) }
    override val publicKey: PublicKey = pair.public
    override val signer = EcdsaSigner("$terminalId-SW-" + fingerprint(pair.public), pair.private)

    private fun load(): java.security.KeyPair? = runCatching {
        val (priv, pub) = file.readLines().map { B64.decode(it) }
        val kf = java.security.KeyFactory.getInstance("EC")
        java.security.KeyPair(
            kf.generatePublic(java.security.spec.X509EncodedKeySpec(pub)),
            kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(priv)),
        )
    }.getOrNull()

    private fun save(p: java.security.KeyPair) {
        val tmp = java.io.File(file.path + ".tmp")
        tmp.writeText(B64.encode(p.private.encoded) + "\n" + B64.encode(p.public.encoded) + "\n")
        check(tmp.renameTo(file)) { "Impossible d'enregistrer la clé fiscale" }
    }
}

/** Keystore d'abord ; si le terminal ne sait pas générer la clé, repli sur la clé logicielle persistante. */
fun fiscalKeyProvider(terminalId: String, dir: java.io.File): FiscalKeyProvider {
    val sw = java.io.File(dir, "fiscal-key-$terminalId.p8")
    if (sw.exists()) return FileKeyProvider(terminalId, dir) // ne jamais rebasculer : une seule clé active
    return try {
        AndroidKeystoreKeyProvider(terminalId).also { it.signer.sign(byteArrayOf(0)) }
    } catch (e: Exception) {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("foodsafe-fiscal-$terminalId")
        }
        FileKeyProvider(terminalId, dir)
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

package fr.foodsafe.pos.core.auth

import java.security.MessageDigest
import java.security.SecureRandom
import fr.foodsafe.pos.core.util.B64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class Role { SERVER, MANAGER, ADMIN }

data class Employee(val id: String, val name: String, val role: Role, val pinHash: String, val active: Boolean = true)

/**
 * Hachage des PIN : PBKDF2-HMAC-SHA256 implémenté sur Mac (disponible dès Android 7, contrairement à
 * SecretKeyFactory "PBKDF2WithHmacSHA256" qui exige l'API 26). Format : "pbkdf2$itérations$sel$hash".
 * Un PIN court reste devinable hors ligne : la protection principale est le verrouillage après échecs.
 */
object PinHasher {
    private const val ITERATIONS = 20_000
    private val random = SecureRandom()

    fun hash(pin: String, iterations: Int = ITERATIONS): String {
        require(pin.length in 4..8 && pin.all { it.isDigit() }) { "PIN de 4 à 8 chiffres" }
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2$$iterations$${B64.encode(salt)}$${B64.encode(pbkdf2(pin, salt, iterations))}"
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val expected = runCatching { B64.decode(parts[3]) }.getOrNull() ?: return false
        val salt = runCatching { B64.decode(parts[2]) }.getOrNull() ?: return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..1_000_000 } ?: return false
        return MessageDigest.isEqual(expected, pbkdf2(pin, salt, iterations))
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        // Un seul bloc (32 octets) suffit : T1 = U1 xor U2 xor ... xor Uc
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        return t
    }
}

sealed interface LoginResult {
    data class Success(val employee: Employee) : LoginResult
    data object WrongPin : LoginResult
    data class Locked(val untilMs: Long) : LoginResult
}

/** Connexion par PIN avec verrouillage du terminal après [maxFailures] échecs consécutifs. */
class PinAuthenticator(private val maxFailures: Int = 5, private val lockMs: Long = 30_000) {
    private var failures = 0
    private var lockedUntil = 0L

    @Synchronized
    fun login(pin: String, employees: List<Employee>, now: Long): LoginResult {
        if (now < lockedUntil) return LoginResult.Locked(lockedUntil)
        val match = employees.firstOrNull { it.active && PinHasher.verify(pin, it.pinHash) }
        if (match != null) { failures = 0; return LoginResult.Success(match) }
        failures++
        if (failures >= maxFailures) { failures = 0; lockedUntil = now + lockMs; return LoginResult.Locked(lockedUntil) }
        return LoginResult.WrongPin
    }
}

/** Actions sensibles soumises à une politique configurable par rôle. */
enum class SensitiveAction(val label: String) {
    TICKET_DISCOUNT("Remise sur la note"),
    LINE_DISCOUNT("Remise sur une ligne"),
    CANCEL_SENT_LINE("Annuler un produit envoyé"),
    REPRINT_DUPLICATE("Réimprimer un ticket (duplicata)"),
    Z_CLOSURE("Clôture Z"),
    REFUND("Remboursement"),
    OPEN_DRAWER("Ouvrir le tiroir sans vente"),
    EDIT_CATALOG("Modifier les produits et claviers"),
}

sealed interface Policy {
    data object Free : Policy
    /** Autorisé jusqu'à [limit] (points de base pour un %, centimes pour un montant), validation responsable au-delà. */
    data class Limited(val limit: Long) : Policy
    data object ManagerPin : Policy
    /** Validation par un responsable à chaque fois, même si l'auteur est responsable (double contrôle). */
    data object AlwaysValidate : Policy
    data object Forbidden : Policy
}

sealed interface Decision {
    data object Allowed : Decision
    data class NeedsManager(val reason: String) : Decision
    data class Denied(val reason: String) : Decision
}

class PermissionPolicy(private val rules: Map<Pair<Role, SensitiveAction>, Policy>) {
    fun policy(role: Role, action: SensitiveAction): Policy = rules[role to action] ?: when (role) {
        Role.ADMIN, Role.MANAGER -> Policy.Free
        Role.SERVER -> Policy.ManagerPin
    }

    fun check(role: Role, action: SensitiveAction, value: Long = 0): Decision = when (val p = policy(role, action)) {
        Policy.Free -> Decision.Allowed
        is Policy.Limited -> if (value <= p.limit) Decision.Allowed else Decision.NeedsManager("${action.label} au-delà de la limite autorisée")
        Policy.ManagerPin -> if (role != Role.SERVER) Decision.Allowed else Decision.NeedsManager("${action.label} : validation responsable")
        Policy.AlwaysValidate -> Decision.NeedsManager("${action.label} : validation systématique")
        Policy.Forbidden -> Decision.Denied("${action.label} : interdit pour ce profil")
    }

    /** Un validateur doit être responsable/admin ET ne pas être lui-même interdit pour l'action. */
    fun canApprove(approver: Employee, action: SensitiveAction): Boolean =
        approver.active && approver.role != Role.SERVER && policy(approver.role, action) != Policy.Forbidden

    companion object {
        /** Politique par défaut proposée (modifiable par établissement dans le back-office, phase 2). */
        val DEFAULT = PermissionPolicy(
            mapOf(
                (Role.SERVER to SensitiveAction.TICKET_DISCOUNT) to Policy.Limited(1000), // 10 %
                (Role.SERVER to SensitiveAction.LINE_DISCOUNT) to Policy.Limited(1000),
                (Role.SERVER to SensitiveAction.CANCEL_SENT_LINE) to Policy.ManagerPin,
                (Role.SERVER to SensitiveAction.REPRINT_DUPLICATE) to Policy.ManagerPin,
                (Role.SERVER to SensitiveAction.Z_CLOSURE) to Policy.Forbidden,
                (Role.SERVER to SensitiveAction.REFUND) to Policy.Forbidden,
                (Role.MANAGER to SensitiveAction.REFUND) to Policy.AlwaysValidate,
                (Role.SERVER to SensitiveAction.OPEN_DRAWER) to Policy.ManagerPin,
                (Role.SERVER to SensitiveAction.EDIT_CATALOG) to Policy.Forbidden,
            ),
        )
    }
}

/** Déconnexion après inactivité (null = session permanente). */
data class SessionPolicy(val inactivityTimeoutMs: Long?) {
    fun isExpired(lastActivity: Long, now: Long) = inactivityTimeoutMs != null && now - lastActivity >= inactivityTimeoutMs
}

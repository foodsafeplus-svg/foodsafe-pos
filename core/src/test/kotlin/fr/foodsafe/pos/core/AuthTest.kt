package fr.foodsafe.pos.core

import fr.foodsafe.pos.core.auth.Decision
import fr.foodsafe.pos.core.auth.Employee
import fr.foodsafe.pos.core.auth.LoginResult
import fr.foodsafe.pos.core.auth.PermissionPolicy
import fr.foodsafe.pos.core.auth.PinAuthenticator
import fr.foodsafe.pos.core.auth.PinHasher
import fr.foodsafe.pos.core.auth.Policy
import fr.foodsafe.pos.core.auth.Role
import fr.foodsafe.pos.core.auth.SensitiveAction
import fr.foodsafe.pos.core.auth.SessionPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AuthTest {
    private val marie = Employee("marie", "Marie", Role.SERVER, PinHasher.hash("1111", 1000))
    private val paul = Employee("paul", "Paul", Role.MANAGER, PinHasher.hash("9999", 1000))

    @Test fun `PIN hache sale et verifie`() {
        val h1 = PinHasher.hash("1234", 1000)
        val h2 = PinHasher.hash("1234", 1000)
        assertTrue(h1 != h2) // sel aléatoire
        assertFalse("1234" in h1)
        assertTrue(PinHasher.verify("1234", h1))
        assertFalse(PinHasher.verify("1235", h1))
        assertFalse(PinHasher.verify("1234", "garbage"))
        assertThrows<IllegalArgumentException> { PinHasher.hash("12a4") }
    }

    @Test fun `vecteur de reference PBKDF2 HMAC SHA256`() {
        // RFC 7914 §11 : P="passwd", S="salt", c=1 → 55ac046e56e3089f... (on vérifie le premier bloc)
        val m = PinHasher::class.java.getDeclaredMethod("pbkdf2", String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
        m.isAccessible = true
        val out = m.invoke(PinHasher, "passwd", "salt".toByteArray(), 1) as ByteArray
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", out.joinToString("") { "%02x".format(it) })
    }

    @Test fun `connexion et verrouillage apres 5 echecs`() {
        val auth = PinAuthenticator(maxFailures = 5, lockMs = 30_000)
        assertEquals(LoginResult.Success(paul), auth.login("9999", listOf(marie, paul), 0))
        repeat(4) { assertEquals(LoginResult.WrongPin, auth.login("0000", listOf(marie, paul), 1)) }
        assertEquals(LoginResult.Locked(30_001), auth.login("0000", listOf(marie, paul), 1))
        assertEquals(LoginResult.Locked(30_001), auth.login("1111", listOf(marie, paul), 10_000)) // même le bon PIN
        assertEquals(LoginResult.Success(marie), auth.login("1111", listOf(marie, paul), 30_001))
        assertEquals(LoginResult.WrongPin, auth.login("1111", listOf(marie.copy(active = false)), 30_002))
    }

    @Test fun `politique de droits par defaut`() {
        val p = PermissionPolicy.DEFAULT
        assertEquals(Decision.Allowed, p.check(Role.SERVER, SensitiveAction.TICKET_DISCOUNT, 1000))
        assertInstanceOf(Decision.NeedsManager::class.java, p.check(Role.SERVER, SensitiveAction.TICKET_DISCOUNT, 1500))
        assertEquals(Decision.Allowed, p.check(Role.MANAGER, SensitiveAction.TICKET_DISCOUNT, 5000))
        assertInstanceOf(Decision.NeedsManager::class.java, p.check(Role.SERVER, SensitiveAction.CANCEL_SENT_LINE))
        assertInstanceOf(Decision.Denied::class.java, p.check(Role.SERVER, SensitiveAction.Z_CLOSURE))
        assertEquals(Decision.Allowed, p.check(Role.MANAGER, SensitiveAction.Z_CLOSURE))
        assertInstanceOf(Decision.NeedsManager::class.java, p.check(Role.MANAGER, SensitiveAction.REFUND)) // systématique
        assertTrue(p.canApprove(paul, SensitiveAction.TICKET_DISCOUNT))
        assertFalse(p.canApprove(marie, SensitiveAction.TICKET_DISCOUNT))
        val strict = PermissionPolicy(mapOf((Role.MANAGER to SensitiveAction.Z_CLOSURE) to Policy.Forbidden))
        assertFalse(strict.canApprove(paul, SensitiveAction.Z_CLOSURE))
    }

    @Test fun `deconnexion apres inactivite`() {
        assertFalse(SessionPolicy(null).isExpired(0, Long.MAX_VALUE))
        assertFalse(SessionPolicy(30_000).isExpired(0, 29_999))
        assertTrue(SessionPolicy(10_000).isExpired(0, 10_000))
    }
}

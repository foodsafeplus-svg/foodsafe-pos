package fr.foodsafe.pos.core

import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.money.mulDivRound
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MoneyTest {
    @Test fun `format francais`() {
        assertEquals("12,34 €", Cents(1234).format())
        assertEquals("1 234 567,05 €", Cents(123456705).format())
        assertEquals("-0,50 €", Cents(-50).format())
    }

    @Test fun `arrondi demi au-dessus symetrique`() {
        assertEquals(3, mulDivRound(5, 1, 2))     // 2,5 -> 3
        assertEquals(-3, mulDivRound(-5, 1, 2))   // -2,5 -> -3
        assertEquals(2, mulDivRound(7, 1, 3))     // 2,33 -> 2
    }

    @Test fun `depassement de capacite detecte`() {
        assertThrows<ArithmeticException> { Cents(Long.MAX_VALUE) + Cents(1) }
    }

    @Test fun `taux bornes`() {
        assertThrows<IllegalArgumentException> { BasisPoints(10_001) }
        assertEquals("5,5 %", BasisPoints(550).format())
        assertEquals("20 %", BasisPoints(2000).format())
    }
}

class B64Test {
    @Test fun `codec base64 identique a la reference JDK`() {
        val rnd = kotlin.random.Random(1)
        repeat(500) {
            val data = rnd.nextBytes(rnd.nextInt(0, 80))
            val ref = java.util.Base64.getEncoder().encodeToString(data)
            assertEquals(ref, fr.foodsafe.pos.core.util.B64.encode(data))
            org.junit.jupiter.api.Assertions.assertArrayEquals(data, fr.foodsafe.pos.core.util.B64.decode(ref))
        }
    }
}

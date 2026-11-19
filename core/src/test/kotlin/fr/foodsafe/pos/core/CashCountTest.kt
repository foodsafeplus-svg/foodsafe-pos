package fr.foodsafe.pos.core

import fr.foodsafe.pos.core.cash.CashCount
import fr.foodsafe.pos.core.money.Cents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CashCountTest {
    @Test fun `exemple de la caisse actuelle`() {
        // 2×2 € + 5×5 € + 25×0,20 € + 34×0,50 € + 5×10 € = 101,00 €
        val c = CashCount().add(Cents(200), 2).add(Cents(500), 5).add(Cents(20), 25).add(Cents(50), 34).add(Cents(1000), 5)
        assertEquals(Cents(10100), c.total)
        assertEquals(5, c.lines.size)
    }

    @Test fun `retoucher une valeur ajoute a la ligne`() {
        val c = CashCount().add(Cents(1000), 3).add(Cents(1000), 2)
        assertEquals(1, c.lines.size)
        assertEquals(5L, c.lines[0].quantity)
    }

    @Test fun `moins un sur la derniere unite supprime la ligne`() {
        assertEquals(0, CashCount().add(Cents(50), 1).step(0, -1).lines.size)
    }

    @Test fun `valeur inconnue refusee`() {
        assertThrows<IllegalArgumentException> { CashCount().add(Cents(300), 1) }
    }

    @Test fun `ticket trie par valeur decroissante`() {
        val r = CashCount().add(Cents(20), 25).add(Cents(1000), 5).render("COMPTAGE", listOf("x"))
        val i10 = r.indexOfFirst { it.startsWith("10,00 €") }
        val i020 = r.indexOfFirst { it.startsWith("0,20 €") }
        assertTrue(i10 in 0 until i020)
        assertTrue(r.last().endsWith("55,00 €"))
    }
}

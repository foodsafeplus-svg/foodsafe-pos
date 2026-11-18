package fr.foodsafe.pos.core

import fr.foodsafe.pos.core.cash.CashAttribution
import fr.foodsafe.pos.core.cash.CashTarget
import fr.foodsafe.pos.core.cash.TerminalKind
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.payment.PaymentMethod
import fr.foodsafe.pos.core.payment.PaymentRules
import fr.foodsafe.pos.core.split.Split
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SplitAndPaymentTest {
    @Test fun `division egale - centimes residuels aux premieres parts`() {
        assertEquals(listOf(3334L, 3333L, 3333L), Split.equally(Cents(10000), 3).map { it.value })
        assertEquals(listOf(26L, 25L, 25L, 25L), Split.equally(Cents(101), 4).map { it.value })
        assertEquals(Cents(9999), Cents(Split.equally(Cents(9999), 7).sumOf { it.value }))
    }

    @Test fun `division par montants libres - solde en derniere part`() {
        assertEquals(listOf(Cents(2000), Cents(1000), Cents(1800)), Split.byAmounts(Cents(4800), listOf(Cents(2000), Cents(1000))))
        assertThrows<IllegalArgumentException> { Split.byAmounts(Cents(1000), listOf(Cents(1200))) }
    }

    @Test fun `paiement mixte et rendu monnaie`() {
        val total = Cents(4800)
        val card = PaymentRules.pay("p1", "o1", total, emptyList(), PaymentMethod.CARD, Cents(2000), "marie", "T01", 1)
        val cash = PaymentRules.pay("p2", "o1", total, listOf(card), PaymentMethod.CASH, Cents(5000), "paul", "T01", 2)
        assertEquals(Cents(2800), cash.amount)
        assertEquals(Cents(2200), cash.change)
        assertEquals(Cents.ZERO, PaymentRules.remaining(total, listOf(card, cash)))
    }

    @Test fun `carte superieure au reste refusee`() {
        assertThrows<IllegalArgumentException> {
            PaymentRules.pay("p1", "o1", Cents(1000), emptyList(), PaymentMethod.CARD, Cents(1500), "marie", "T01", 1)
        }
    }

    @Test fun `attribution des especces - les quatre regles`() {
        fun t(kind: TerminalKind, banane: Boolean, drawers: List<String>, method: PaymentMethod = PaymentMethod.CASH, enabled: Boolean = true) =
            CashAttribution.target(method, enabled, kind, if (kind == TerminalKind.FIXED) "T1" else null, "marie", banane, drawers)

        assertEquals(CashTarget.Drawer("T1"), t(TerminalKind.FIXED, banane = true, drawers = listOf("T1", "T2")))
        assertEquals(CashTarget.PersonalFloat("marie"), t(TerminalKind.PAD, true, listOf("T1", "T2")))
        assertEquals(CashTarget.Drawer("T1"), t(TerminalKind.PAD, false, listOf("T1")))
        assertEquals(CashTarget.AskDrawer(listOf("T1", "T2")), t(TerminalKind.PAD, false, listOf("T1", "T2")))
        assertEquals(CashTarget.None, t(TerminalKind.FIXED, false, listOf("T1"), method = PaymentMethod.CARD))
        assertEquals(CashTarget.None, t(TerminalKind.PAD, false, listOf("T1"), enabled = false))
    }
}

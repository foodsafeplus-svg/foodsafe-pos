package fr.foodsafe.pos.core

import fr.foodsafe.pos.core.fiscal.toFiscalLines
import fr.foodsafe.pos.core.kitchen.KitchenDispatcher
import fr.foodsafe.pos.core.kitchen.KitchenRuleViolation
import fr.foodsafe.pos.core.kitchen.KitchenTicketKind
import fr.foodsafe.pos.core.kitchen.KitchenTicketRenderer
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.OrderPricer
import fr.foodsafe.pos.core.order.LineRole
import fr.foodsafe.pos.core.tax.FrenchVat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Commentaires (gratuits) et suppléments (payants) : sous-lignes rattachées à une ligne produit. */
class ModifierLinesTest {
    private val ids = SeqIds()
    private val pizza = line("l1", "Pizza Reine", 1350, level = 2)
    private val oeuf = line("l2", "Œuf", 100, level = 2).copy(parentLineId = "l1", role = LineRole.MODIFIER)
    private val sansOlives = line("l3", "Sans olives", 0, level = 2).copy(parentLineId = "l1", role = LineRole.MODIFIER)
    private val whisky = line("l4", "Whisky", 800, rate = FrenchVat.NORMAL, destination = "BAR")
    private val coca = line("l5", "Coca", 100, rate = FrenchVat.NORMAL, destination = "BAR").copy(parentLineId = "l4", role = LineRole.MODIFIER)

    @Test fun `bon cuisine - sous-lignes en retrait sous leur produit`() {
        val t = KitchenDispatcher.send(order(pizza, oeuf, sansOlives), ids).tickets.single()
        val text = KitchenTicketRenderer.render(t)
        val i = text.indexOfFirst { it.contains("Pizza Reine") }
        assertEquals("    > Œuf", text[i + 1])
        assertEquals("    > Sans olives", text[i + 2])
    }

    @Test fun `prix et TVA - supplement compte, commentaire gratuit absent du ticket`() {
        val priced = OrderPricer.price(order(pizza, oeuf, sansOlives, whisky, coca))
        assertEquals(Cents(1350 + 100 + 800 + 100), priced.totalTtc)
        val labels = priced.toFiscalLines().map { it.label }
        assertEquals(listOf("Pizza Reine", "+ Œuf", "Whisky", "+ Coca"), labels)
        // le diluant suit la TVA de l'alcool : 9,00 € à 20 %
        assertEquals(Cents(900), priced.taxes.byRate.first { it.rate == FrenchVat.NORMAL }.ttc)
    }

    @Test fun `annuler le produit annule ses sous-lignes et le bon les reprend`() {
        val sent = KitchenDispatcher.send(order(pizza, oeuf, sansOlives), ids).order
        val r = KitchenDispatcher.cancelLine(sent, "l1", 1000, "erreur", "paul", 1, ids)
        assertTrue(r.order.lines.none { it.isActive })
        val t = r.tickets.single()
        assertEquals(KitchenTicketKind.CANCELLATION, t.kind)
        assertEquals(listOf("Pizza Reine", "Œuf", "Sans olives"), t.items.map { it.label })
        assertEquals(Cents.ZERO, OrderPricer.price(r.order).totalTtc)
    }

    @Test fun `annulation partielle refusee quand la ligne porte des sous-lignes`() {
        val o = order(pizza.copy(quantityMilli = 2000), oeuf.copy(quantityMilli = 2000))
        assertThrows<KitchenRuleViolation> { KitchenDispatcher.cancelLine(o, "l1", 1000, "erreur", "paul", 1, ids) }
    }

    @Test fun `un supplement seul peut etre retire`() {
        val r = KitchenDispatcher.cancelLine(order(pizza, oeuf), "l2", 1000, "client", "marie", 1, ids)
        assertEquals(listOf(true, false), r.order.lines.map { it.isActive })
    }
}

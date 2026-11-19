package fr.foodsafe.pos.core

import fr.foodsafe.pos.core.fiscal.toFiscalLines
import fr.foodsafe.pos.core.kitchen.KitchenDispatcher
import fr.foodsafe.pos.core.kitchen.KitchenRuleViolation
import fr.foodsafe.pos.core.kitchen.KitchenTicketKind
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.FormulaComposer
import fr.foodsafe.pos.core.order.FormulaPick
import fr.foodsafe.pos.core.order.OrderPricer
import fr.foodsafe.pos.core.order.PromoEngine
import fr.foodsafe.pos.core.order.PromoRule
import fr.foodsafe.pos.core.order.Prorata
import fr.foodsafe.pos.core.order.ServiceLevel
import fr.foodsafe.pos.core.tax.FrenchVat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FormulaTest {
    private val ids = SeqIds()
    private val pizza = FormulaPick("reine", "Pizza Reine", Cents(1350), FrenchVat.INTERMEDIATE, ServiceLevel(1), "CUISINE")
    private val biere = FormulaPick("biere", "Bière pression", Cents(600), FrenchVat.NORMAL, ServiceLevel.DIRECT, "BAR")
    private val tiramisu = FormulaPick("tiramisu", "Tiramisu", Cents(650), FrenchVat.INTERMEDIATE, ServiceLevel(2), "CUISINE")

    @Test fun `prorata au centime pres`() {
        assertEquals(listOf(334L, 333L, 333L), Prorata.split(1000, listOf(1, 1, 1)))
        assertEquals(1990L, Prorata.split(1990, listOf(1350, 600, 650)).sum())
        assertEquals(listOf(500L, 500L), Prorata.split(1000, listOf(0, 0)))
    }

    @Test fun `formule service - prix ventile, TVA par taux, niveaux par etape`() {
        val lines = FormulaComposer.lines("f-midi", "Formule midi", Cents(1990), listOf(pizza, biere, tiramisu), ids, "marie", 0)
        val o = order(*lines.toTypedArray())
        val priced = OrderPricer.price(o)
        assertEquals(Cents(1990), priced.totalTtc)
        // 1990 × 600 / 2600 = 459,23 → part de la bière à 20 %
        assertEquals(Cents(459), priced.taxes.byRate.first { it.rate == FrenchVat.NORMAL }.ttc)

        val sent = KitchenDispatcher.send(o, ids)
        val items = sent.tickets.flatMap { t -> t.items.map { t.destination to it.label + "@" + it.level.label } }
        assertEquals(setOf("BAR" to "Bière pression@DIRECT", "CUISINE" to "Pizza Reine@N1", "CUISINE" to "Tiramisu@N2"), items.toSet())
        assertTrue(sent.order.lines.all { it.isSent })
        assertEquals(ServiceLevel(1), KitchenDispatcher.nextClaimableLevel(sent.order))
    }

    @Test fun `ticket - en-tete au prix de la formule, composants sans prix`() {
        val lines = FormulaComposer.lines("f-midi", "Formule midi", Cents(1990), listOf(pizza, biere), ids, "marie", 0)
        val fl = OrderPricer.price(order(*lines.toTypedArray())).toFiscalLines()
        assertEquals(listOf("Formule midi", "  > Pizza Reine", "  > Bière pression"), fl.map { it.label })
        assertEquals(Cents(1990), Cents(fl.sumOf { it.gross.value }))
    }

    @Test fun `annulation - composant seul refuse, formule entiere annulee avec bons par destination`() {
        val lines = FormulaComposer.lines("f-midi", "Formule midi", Cents(1990), listOf(pizza, biere), ids, "marie", 0)
        val sent = KitchenDispatcher.send(order(*lines.toTypedArray()), ids).order
        assertThrows<KitchenRuleViolation> { KitchenDispatcher.cancelLine(sent, lines[1].id, 1000, "x", "paul", 1, ids) }
        val r = KitchenDispatcher.cancelLine(sent, lines[0].id, 1000, "erreur", "paul", 1, ids)
        assertTrue(r.order.lines.none { it.isActive })
        assertEquals(listOf("BAR", "CUISINE"), r.tickets.map { it.destination })
        assertTrue(r.tickets.all { it.kind == KitchenTicketKind.CANCELLATION })
    }

    private val promo = PromoRule("promo-1", "Pizza + bière", Cents(1700), listOf(setOf("p-Pizza", "p-Calzone"), setOf("p-Bière")))

    @Test fun `promo - appliquee a la combinaison, plus cher retenu, plancher`() {
        val o = order(line("l1", "Pizza", 1350), line("l2", "Calzone", 1300), line("l3", "Bière", 600, rate = FrenchVat.NORMAL))
        val p = PromoEngine.apply(o, listOf(promo), ids)
        assertEquals(Cents(1700 + 1300), OrderPricer.price(p).totalTtc)
        assertEquals(p.lines[0].promoGroup, p.lines[2].promoGroup)
        assertNull(p.lines[1].promoGroup)
        // plancher : une promo plus chère que le détail ne s'applique pas
        val cheap = order(line("l1", "Pizza", 900), line("l3", "Bière", 600))
        assertEquals(cheap, PromoEngine.apply(cheap, listOf(promo), ids))
    }

    @Test fun `promo retiree - refusee, plus reproposee`() {
        val o = PromoEngine.apply(order(line("l1", "Pizza", 1350), line("l3", "Bière", 600)), listOf(promo), ids)
        val removed = PromoEngine.remove(o, o.lines[0].promoGroup!!, refuse = true)
        assertEquals(Cents(1950), OrderPricer.price(removed).totalTtc)
        assertEquals(removed.lines, PromoEngine.apply(removed, listOf(promo), ids).lines)
    }
}

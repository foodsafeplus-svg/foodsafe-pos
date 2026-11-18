package fr.foodsafe.pos.core

import fr.foodsafe.pos.core.discount.LineDiscount
import fr.foodsafe.pos.core.discount.TicketDiscount
import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.OrderPricer
import fr.foodsafe.pos.core.tax.FrenchVat
import fr.foodsafe.pos.core.tax.TaxCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class PricingTest {
    @Test fun `exemple du cahier des charges - remise note 10 pourcent ligne par ligne`() {
        val o = order(
            line("l1", "Pizza", 1200, FrenchVat.INTERMEDIATE),
            line("l2", "Bière", 600, FrenchVat.NORMAL),
        ).copy(ticketDiscount = TicketDiscount(BasisPoints.percent(10)))

        val p = OrderPricer.price(o)
        assertEquals(Cents(1080), p.lines[0].net)
        assertEquals(Cents(540), p.lines[1].net)
        assertEquals(Cents(1620), p.totalTtc)
        assertEquals(Cents(180), p.totalDiscount)

        val r10 = p.taxes.byRate.single { it.rate == FrenchVat.INTERMEDIATE }
        assertEquals(Cents(1080), r10.ttc); assertEquals(Cents(982), r10.ht); assertEquals(Cents(98), r10.vat)
        val r20 = p.taxes.byRate.single { it.rate == FrenchVat.NORMAL }
        assertEquals(Cents(540), r20.ttc); assertEquals(Cents(450), r20.ht); assertEquals(Cents(90), r20.vat)
        assertEquals(p.totalTtc, p.taxes.totalTtc)
    }

    @Test fun `remise ligne en euros puis remise note en pourcentage sur le net`() {
        val o = order(
            line("l1", "Pizza", 1200).copy(lineDiscount = LineDiscount.Amount(Cents(200))),
        ).copy(ticketDiscount = TicketDiscount(BasisPoints.percent(10)))
        val pl = OrderPricer.price(o).lines.single()
        assertEquals(Cents(200), pl.lineDiscount)
        assertEquals(Cents(100), pl.ticketDiscount) // 10 % de 10,00
        assertEquals(Cents(900), pl.net)
    }

    @Test fun `remise ligne en pourcentage avec quantite`() {
        val o = order(line("l1", "Café", 250, qty = 3).copy(lineDiscount = LineDiscount.Percent(BasisPoints(3333))))
        val pl = OrderPricer.price(o).lines.single()
        assertEquals(Cents(750), pl.gross)
        assertEquals(Cents(250), pl.lineDiscount) // 749,975 -> 250
        assertEquals(Cents(500), pl.net)
    }

    @Test fun `remise euros superieure a la ligne refusee - jamais de montant negatif`() {
        val o = order(line("l1", "Café", 250).copy(lineDiscount = LineDiscount.Amount(Cents(300))))
        assertThrows<IllegalArgumentException> { OrderPricer.price(o) }
    }

    @Test fun `remise 100 pourcent donne zero et non negatif`() {
        val o = order(line("l1", "Pizza", 1200)).copy(ticketDiscount = TicketDiscount(BasisPoints.percent(100)))
        assertEquals(Cents.ZERO, OrderPricer.price(o).totalTtc)
    }

    @Test fun `lignes annulees exclues du total`() {
        val o = order(line("l1", "Pizza", 1200), line("l2", "Bière", 600)
            .copy(cancellation = fr.foodsafe.pos.core.order.Cancellation("erreur", "marie", 0)))
        assertEquals(Cents(1200), OrderPricer.price(o).totalTtc)
    }

    @Test fun `propriete - HT plus TVA egale TTC pour tout montant et taux`() {
        val rnd = Random(42)
        val rates = listOf(FrenchVat.NORMAL, FrenchVat.INTERMEDIATE, FrenchVat.REDUCED, FrenchVat.SUPER_REDUCED)
        repeat(10_000) {
            val amounts = List(rnd.nextInt(1, 8)) { rates.random(rnd) to Cents(rnd.nextLong(0, 1_000_000)) }
            val b = TaxCalculator.breakdown(amounts)
            b.byRate.forEach { assertEquals(it.ttc, it.ht + it.vat) }
            assertEquals(Cents(amounts.sumOf { it.second.value }), b.totalTtc)
        }
    }

    @Test fun `propriete - remise note jamais negative et net inferieur au brut`() {
        val rnd = Random(7)
        repeat(2_000) {
            val lines = List(rnd.nextInt(1, 6)) { i ->
                val l = line("l$i", "P$i", rnd.nextLong(1, 50_000), qty = rnd.nextLong(1, 5))
                if (rnd.nextBoolean()) l.copy(lineDiscount = LineDiscount.Percent(BasisPoints(rnd.nextInt(0, 10_001)))) else l
            }
            val o = order(*lines.toTypedArray()).copy(ticketDiscount = TicketDiscount(BasisPoints(rnd.nextInt(0, 10_001))))
            val p = OrderPricer.price(o)
            p.lines.forEach { assert(!it.net.isNegative && it.net <= it.gross) }
            assertEquals(p.gross - p.totalDiscount, p.totalTtc)
        }
    }
}

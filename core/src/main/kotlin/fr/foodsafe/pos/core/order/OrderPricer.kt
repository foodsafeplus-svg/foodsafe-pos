package fr.foodsafe.pos.core.order

import fr.foodsafe.pos.core.discount.LineDiscount
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.money.mulDivRound
import fr.foodsafe.pos.core.tax.TaxBreakdown
import fr.foodsafe.pos.core.tax.TaxCalculator

data class PricedLine(
    val line: OrderLine,
    /** Prix unitaire × quantité, avant toute remise. */
    val gross: Cents,
    val lineDiscount: Cents,
    /** Part de la remise note (%) imputée à cette ligne. */
    val ticketDiscount: Cents,
) {
    val net: Cents get() = gross - lineDiscount - ticketDiscount
}

data class PricedOrder(val lines: List<PricedLine>, val taxes: TaxBreakdown) {
    val gross: Cents get() = Cents(lines.sumOf { it.gross.value })
    val totalDiscount: Cents get() = Cents(lines.sumOf { it.lineDiscount.value + it.ticketDiscount.value })
    val totalTtc: Cents get() = Cents(lines.sumOf { it.net.value })
}

/**
 * Calcul exact d'une note.
 * Ordre d'application (règle de cumul explicite) :
 *  1. brut = prix unitaire TTC × quantité (arrondi au centime) ;
 *  2. remise ligne (% ou €), plafonnée : une remise € supérieure au brut est REFUSÉE (pas de montant négatif) ;
 *  3. remise note en % appliquée LIGNE PAR LIGNE sur le net après remise ligne, chaque ligne gardant son taux de TVA ;
 *  4. ventilation TVA par taux sur les nets.
 * Les lignes annulées sont exclues.
 */
object OrderPricer {
    fun price(order: Order): PricedOrder {
        val priced = order.activeLines.map { line ->
            val gross = Cents(mulDivRound(line.unitPriceTtc.value, line.quantityMilli, 1000))
            val lineDisc = when (val d = line.lineDiscount) {
                null -> Cents.ZERO
                is LineDiscount.Percent -> Cents(mulDivRound(gross.value, d.rate.value.toLong(), 10_000))
                is LineDiscount.Amount -> {
                    require(d.amount <= gross) { "remise ${d.amount} supérieure au montant de la ligne ${line.label} ($gross)" }
                    d.amount
                }
            }
            val afterLine = gross - lineDisc
            val ticketDisc = order.ticketDiscount
                ?.let { Cents(mulDivRound(afterLine.value, it.rate.value.toLong(), 10_000)) }
                ?: Cents.ZERO
            PricedLine(line, gross, lineDisc, ticketDisc).also { check(!it.net.isNegative) }
        }
        return PricedOrder(priced, TaxCalculator.breakdown(priced.map { it.line.vatRate to it.net }))
    }
}

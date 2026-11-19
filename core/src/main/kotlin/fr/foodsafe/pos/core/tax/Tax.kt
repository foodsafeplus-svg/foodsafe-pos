package fr.foodsafe.pos.core.tax

import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.money.mulDivRound

/** Taux de TVA français courants (points de base). */
object FrenchVat {
    val NORMAL = BasisPoints(2000)        // 20 %
    val INTERMEDIATE = BasisPoints(1000)  // 10 % : restauration sur place, plats à emporter à consommer immédiatement
    val REDUCED = BasisPoints(550)        // 5,5 %
    val SUPER_REDUCED = BasisPoints(210)  // 2,1 %
}

data class VatLine(val rate: BasisPoints, val ttc: Cents, val ht: Cents, val vat: Cents)

data class TaxBreakdown(val byRate: List<VatLine>) {
    val totalTtc: Cents get() = Cents(byRate.sumOf { it.ttc.value })
    val totalHt: Cents get() = Cents(byRate.sumOf { it.ht.value })
    val totalVat: Cents get() = Cents(byRate.sumOf { it.vat.value })
}

/**
 * Ventilation TVA à partir de montants TTC (prix restaurant saisis TTC).
 * Méthode : on additionne les TTC par taux, puis HT = arrondi(TTC × 10000 / (10000 + taux)),
 * TVA = TTC − HT. Ainsi HT + TVA = TTC exactement pour chaque taux.
 * (Méthode à faire valider par l'expert-comptable : voir docs/A, hypothèses.)
 */
object TaxCalculator {
    fun htFromTtc(ttc: Cents, rate: BasisPoints): Cents =
        Cents(mulDivRound(ttc.value, 10_000, 10_000L + rate.value))

    fun breakdown(amounts: List<Pair<BasisPoints, Cents>>): TaxBreakdown {
        val lines = amounts
            .filter { !it.second.isZero } // une sous-ligne gratuite n'ouvre pas de taux à 0,00
            .groupBy({ it.first.value }, { it.second.value })
            .toSortedMap()
            .map { (rate, ttcs) ->
                val ttc = Cents(ttcs.sum())
                val bp = BasisPoints(rate)
                val ht = htFromTtc(ttc, bp)
                VatLine(bp, ttc, ht, ttc - ht)
            }
        return TaxBreakdown(lines)
    }
}

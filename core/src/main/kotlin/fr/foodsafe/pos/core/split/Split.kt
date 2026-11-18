package fr.foodsafe.pos.core.split

import fr.foodsafe.pos.core.money.Cents

object Split {
    /**
     * Répartit un montant en [parts] parts égales ; les centimes résiduels vont aux PREMIÈRES parts.
     * 100,00 € / 3 = 33,34 ; 33,33 ; 33,33.
     */
    fun equally(total: Cents, parts: Int): List<Cents> {
        require(parts >= 1) { "au moins une part" }
        require(!total.isNegative) { "montant négatif" }
        val base = total.value / parts
        val remainder = (total.value % parts).toInt()
        return List(parts) { i -> Cents(base + if (i < remainder) 1 else 0) }
    }

    /** Montants libres : la somme ne doit pas dépasser le reste dû ; le solde éventuel forme une dernière part. */
    fun byAmounts(total: Cents, amounts: List<Cents>): List<Cents> {
        require(amounts.all { it.value > 0 }) { "chaque part doit être positive" }
        val sum = Cents(amounts.sumOf { it.value })
        require(sum <= total) { "la somme des parts ($sum) dépasse le reste dû ($total)" }
        return if (sum == total) amounts else amounts + (total - sum)
    }
}

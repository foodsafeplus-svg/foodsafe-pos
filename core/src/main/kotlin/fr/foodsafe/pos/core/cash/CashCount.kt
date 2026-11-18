package fr.foodsafe.pos.core.cash

import fr.foodsafe.pos.core.money.Cents

/**
 * Comptage des espèces par valeur faciale (compteur d'espèces). Montants en centimes entiers.
 * Une ligne par valeur : retoucher une valeur déjà comptée ajoute à sa quantité.
 */
data class CashCount(val lines: List<Line> = emptyList()) {
    data class Line(val denomination: Cents, val quantity: Long) {
        init { require(quantity >= 0) { "quantité négative" } }
        val total: Cents get() = denomination * quantity
    }

    val total: Cents get() = Cents(lines.sumOf { it.total.value })

    /** Ajoute [quantity] pièces ou billets de [denomination] (crée la ligne si besoin). */
    fun add(denomination: Cents, quantity: Long): CashCount {
        require(denomination in DENOMINATIONS) { "valeur inconnue : $denomination" }
        require(quantity > 0) { "quantité nulle" }
        val existing = lines.indexOfFirst { it.denomination == denomination }
        return if (existing < 0) copy(lines = lines + Line(denomination, quantity))
        else copy(lines = lines.mapIndexed { i, l -> if (i == existing) l.copy(quantity = Math.addExact(l.quantity, quantity)) else l })
    }

    /** +1 / −1 sur une ligne ; à zéro, la ligne disparaît. */
    fun step(index: Int, delta: Long): CashCount {
        val l = lines[index]
        val q = l.quantity + delta
        return if (q <= 0) remove(index) else copy(lines = lines.mapIndexed { i, x -> if (i == index) x.copy(quantity = q) else x })
    }

    fun remove(index: Int) = copy(lines = lines.filterIndexed { i, _ -> i != index })

    /** Lignes du ticket de comptage (document non fiscal), triées par valeur décroissante. */
    fun render(title: String, header: List<String>, columns: Int = 48): List<String> {
        val sep = "-".repeat(columns)
        fun center(s: String) = if (s.length >= columns) s.take(columns) else " ".repeat((columns - s.length) / 2) + s
        fun lr(l: String, r: String) = l.take(columns - r.length - 1).padEnd(columns - r.length - 1) + " " + r
        val out = mutableListOf(center(title), center("Document non fiscal"))
        out += header
        out += sep
        out += lr("Valeur          Quantité", "Total")
        lines.sortedByDescending { it.denomination }.forEach {
            out += lr(it.denomination.format().padEnd(16) + it.quantity.toString(), it.total.format(false))
        }
        out += sep
        out += lr("TOTAL COMPTÉ", total.format())
        return out
    }

    companion object {
        /** Pièces puis billets en euros, du plus petit au plus grand. */
        val DENOMINATIONS: List<Cents> = listOf(1L, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000).map(::Cents)
        fun isCoin(c: Cents) = c.value <= 200
    }
}

package fr.foodsafe.pos.core.money

/**
 * Montant monétaire en centimes d'euro. Aucune opération flottante n'est exposée.
 * Les dépassements de capacité lèvent une ArithmeticException (Math.*Exact).
 */
@JvmInline
value class Cents(val value: Long) : Comparable<Cents> {
    operator fun plus(other: Cents) = Cents(Math.addExact(value, other.value))
    operator fun minus(other: Cents) = Cents(Math.subtractExact(value, other.value))
    operator fun times(factor: Long) = Cents(Math.multiplyExact(value, factor))
    operator fun unaryMinus() = Cents(Math.negateExact(value))
    override fun compareTo(other: Cents) = value.compareTo(other.value)

    val isNegative get() = value < 0
    val isZero get() = value == 0L

    /** Format français : "1 234,50 €" (espace insécable fine non utilisée pour compatibilité imprimante). */
    fun format(withSymbol: Boolean = true): String {
        val abs = Math.abs(value)
        val euros = (abs / 100).toString().reversed().chunked(3).joinToString(" ").reversed()
        val cents = (abs % 100).toString().padStart(2, '0')
        val sign = if (value < 0) "-" else ""
        return "$sign$euros,$cents" + if (withSymbol) " €" else ""
    }

    override fun toString() = format()

    companion object {
        val ZERO = Cents(0)
        fun euros(euros: Long, cents: Long = 0) = Cents(Math.addExact(Math.multiplyExact(euros, 100), cents))
    }
}

fun Iterable<Cents>.sum(): Cents = fold(Cents.ZERO) { acc, c -> acc + c }

/**
 * Calcule round(a * num / den) avec arrondi "demi éloigné de zéro" (half-up commercial),
 * en arithmétique entière exacte.
 */
fun mulDivRound(a: Long, num: Long, den: Long): Long {
    require(den > 0) { "dénominateur strictement positif requis" }
    val product = Math.multiplyExact(a, num)
    val q = product / den
    val r = product % den
    return if (Math.abs(r) * 2 >= den) q + (if (product < 0) -1 else 1) else q
}

/**
 * Pourcentage ou taux en points de base : 10000 = 100 %, 1000 = 10 %, 550 = 5,5 %.
 */
@JvmInline
value class BasisPoints(val value: Int) {
    init { require(value in 0..10_000) { "taux hors bornes [0 ; 100 %] : $value" } }
    fun format(): String {
        val i = value / 100
        val d = value % 100
        return if (d == 0) "$i %" else "$i,${d.toString().padStart(2, '0').trimEnd('0')} %"
    }
    companion object {
        fun percent(p: Int) = BasisPoints(p * 100)
    }
}

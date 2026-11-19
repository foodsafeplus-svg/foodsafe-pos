package fr.foodsafe.pos.core.order

import fr.foodsafe.pos.core.discount.LineDiscount
import fr.foodsafe.pos.core.kitchen.IdGenerator
import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents

/** Répartition d'un montant au prorata de poids, au centime près (méthode du plus fort reste). */
object Prorata {
    fun split(total: Long, weights: List<Long>): List<Long> {
        require(weights.isNotEmpty() && weights.all { it >= 0 })
        val w = if (weights.sum() == 0L) weights.map { 1L } else weights
        val sum = w.sum()
        val base = w.map { Math.multiplyExact(total, it) / sum }
        val rest = w.mapIndexed { i, x -> i to (Math.multiplyExact(total, x) % sum) }.sortedByDescending { it.second }
        val out = base.toMutableList()
        repeat((total - base.sum()).toInt()) { k -> out[rest[k % rest.size].first] += 1 }
        return out
    }
}

/** Article choisi pour une étape de formule. */
data class FormulaPick(
    val productId: String, val label: String, val price: Cents, val vat: BasisPoints, val level: ServiceLevel, val destination: String,
)

/**
 * Formule (directe, avec niveaux de service, ou package) : une ligne d'en-tête à 0 € et une ligne par composant.
 * Le prix de la formule est ventilé entre les composants au prorata de leur prix catalogue : chaque composant garde
 * son taux de TVA, et la somme des composants vaut exactement le prix de la formule.
 */
object FormulaComposer {
    fun lines(formulaId: String, name: String, price: Cents, picks: List<FormulaPick>, ids: IdGenerator, actorId: String, now: Long): List<OrderLine> {
        require(picks.isNotEmpty()) { "formule vide" }
        require(!price.isNegative)
        val header = OrderLine(
            id = ids.next(), productId = formulaId, label = name, unitPriceTtc = Cents.ZERO, quantityMilli = 1000,
            vatRate = picks.first().vat, level = ServiceLevel.DIRECT, destination = picks.first().destination,
            authorEmployeeId = actorId, createdAt = now, role = LineRole.FORMULA,
        )
        val shares = Prorata.split(price.value, picks.map { it.price.value })
        return listOf(header) + picks.mapIndexed { i, p ->
            OrderLine(
                id = ids.next(), productId = p.productId, label = p.label, unitPriceTtc = Cents(shares[i]), quantityMilli = 1000,
                vatRate = p.vat, level = p.level, destination = p.destination, authorEmployeeId = actorId, createdAt = now,
                parentLineId = header.id, role = LineRole.COMPONENT,
            )
        }
    }
}

/** Formule promo : un produit par étape ; quand la combinaison est sur la note, l'ensemble est ramené à [price]. */
data class PromoRule(val id: String, val name: String, val price: Cents, val steps: List<Set<String>>)

/**
 * Promotions automatiques. Seuls les articles simples, sans remise et pas déjà engagés dans une promo, sont
 * candidats ; pour chaque étape on retient l'article le plus cher (meilleur prix pour le client). La promo ne
 * s'applique que si elle est moins chère que les articles au détail (jamais de hausse). La remise est ventilée
 * en remises ligne, au prorata : la TVA reste juste par taux.
 */
object PromoEngine {
    const val REFUSED = "REFUSED"

    fun apply(order: Order, rules: List<PromoRule>, ids: IdGenerator): Order {
        var lines = order.lines
        for (rule in rules) {
            if (rule.steps.isEmpty() || rule.steps.any { it.isEmpty() }) continue
            while (true) {
                val free = lines.filter { it.isActive && it.role == LineRole.PRODUCT && it.promoGroup == null && it.lineDiscount == null }
                val picked = mutableListOf<OrderLine>()
                for (step in rule.steps) {
                    picked += free.filter { it.productId in step && it !in picked }.maxByOrNull { it.unitPriceTtc.value * it.quantityMilli } ?: break
                }
                if (picked.size != rule.steps.size) break
                val grosses = picked.map { Cents(fr.foodsafe.pos.core.money.mulDivRound(it.unitPriceTtc.value, it.quantityMilli, 1000)) }
                val sum = grosses.sumOf { it.value }
                if (rule.price.value >= sum) break
                val discounts = Prorata.split(sum - rule.price.value, grosses.map { it.value })
                val group = "${rule.id}:${ids.next()}"
                val byId = picked.mapIndexed { i, l -> l.id to discounts[i] }.toMap()
                lines = lines.map { l -> byId[l.id]?.let { d -> l.copy(promoGroup = group, lineDiscount = if (d > 0) LineDiscount.Amount(Cents(d)) else null) } ?: l }
            }
        }
        return if (lines == order.lines) order else order.copy(lines = lines).bumped()
    }

    /** Retire une promo : les articles reviennent au détail. [refuse] : ils ne seront plus proposés pour une promo. */
    fun remove(order: Order, group: String, refuse: Boolean): Order = order.copy(
        lines = order.lines.map { if (it.promoGroup == group) it.copy(promoGroup = if (refuse) REFUSED else null, lineDiscount = null) else it },
    ).bumped()

    fun ruleId(group: String?) = group?.takeIf { it != REFUSED }?.substringBefore(':')
}

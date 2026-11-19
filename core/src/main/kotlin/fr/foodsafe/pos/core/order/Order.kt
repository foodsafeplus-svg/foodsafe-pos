package fr.foodsafe.pos.core.order

import fr.foodsafe.pos.core.discount.LineDiscount
import fr.foodsafe.pos.core.discount.TicketDiscount
import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents

enum class SaleMode { DINE_IN, TAKEAWAY, DELIVERY }
enum class OrderStatus { OPEN, ON_HOLD, PAID, CANCELLED }

/** Niveau de service : 0 = direct (préparation immédiate), 1 = N1, 2 = N2… sans limite. */
@JvmInline
value class ServiceLevel(val value: Int) {
    init { require(value >= 0) }
    val isDirect get() = value == 0
    val label get() = if (isDirect) "DIRECT" else "N$value"
    companion object { val DIRECT = ServiceLevel(0) }
}

/**
 * Rôle d'une ligne. PRODUCT : article simple. MODIFIER : commentaire ou supplément (sous-ligne).
 * FORMULA : en-tête d'une formule (montant porté par ses composants). COMPONENT : article d'une formule,
 * au prix ventilé (pour une TVA juste par taux).
 */
enum class LineRole { PRODUCT, MODIFIER, FORMULA, COMPONENT }

data class Cancellation(val reason: String, val byEmployeeId: String, val at: Long)

data class OrderLine(
    val id: String,
    val productId: String,
    val label: String,
    val unitPriceTtc: Cents,
    /** Quantité en millièmes (1000 = 1 unité). */
    val quantityMilli: Long,
    val vatRate: BasisPoints,
    val level: ServiceLevel,
    /** Destination de préparation (cuisine, bar…) utilisée par le routage des bons. */
    val destination: String,
    val authorEmployeeId: String,
    val createdAt: Long,
    val lineDiscount: LineDiscount? = null,
    /** Renseigné quand la ligne a été transmise en cuisine. Une ligne envoyée n'est JAMAIS renvoyée. */
    val dispatchId: String? = null,
    val cancellation: Cancellation? = null,
    /**
     * Commentaire ou supplément rattaché à une ligne produit (« Saignant », « + Œuf »). Il suit le niveau,
     * la destination et l'envoi de sa ligne parente, et il est annulé avec elle.
     */
    val parentLineId: String? = null,
    val role: LineRole = if (parentLineId != null) LineRole.MODIFIER else LineRole.PRODUCT,
    /** Promotion appliquée automatiquement (même valeur pour les lignes de la combinaison) ; « REFUSED » = promo retirée. */
    val promoGroup: String? = null,
) {
    init { require(quantityMilli > 0) { "quantité strictement positive" } }
    val isModifier get() = role == LineRole.MODIFIER
    val isFormulaHeader get() = role == LineRole.FORMULA
    val isComponent get() = role == LineRole.COMPONENT
    val isSent get() = dispatchId != null
    val isActive get() = cancellation == null
}

data class Order(
    val id: String,
    val saleMode: SaleMode,
    val tableNumber: String?,
    val ownerEmployeeId: String,
    val status: OrderStatus = OrderStatus.OPEN,
    /** Version pour verrouillage optimiste (incrémentée à chaque modification). */
    val version: Long = 0,
    val lines: List<OrderLine> = emptyList(),
    val claimedLevels: Set<Int> = emptySet(),
    val ticketDiscount: TicketDiscount? = null,
) {
    val activeLines get() = lines.filter { it.isActive }
    fun bumped() = copy(version = version + 1)
}

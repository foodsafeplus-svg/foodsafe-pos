package fr.foodsafe.pos.core.fiscal

import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.payment.PaymentMethod
import fr.foodsafe.pos.core.tax.VatLine

/**
 * Types de documents. Chaque type a sa propre séquence continue et sa propre chaîne de hachage, par terminal.
 */
enum class FiscalDocType(val code: String, val label: String) {
    TICKET("T", "Ticket"),
    /** Note provisoire : tracée et numérotée, mais n'est PAS une vente et n'entre dans aucun total. */
    PROVISIONAL_NOTE("N", "Note provisoire"),
    DUPLICATE("D", "Duplicata"),
    Z("Z", "Clôture Z"),
}

data class Establishment(
    val id: String,
    val name: String,
    val addressLines: List<String>,
    val siret: String,
    val vatNumber: String,
)

data class FiscalLine(
    val label: String,
    val quantityMilli: Long,
    val unitPriceTtc: Cents,
    val gross: Cents,
    val discount: Cents,
    val net: Cents,
    val vatRate: BasisPoints,
)

data class FiscalPayment(val method: PaymentMethod, val amount: Cents)

/** Données propres à une clôture Z. */
data class ClosureInfo(
    val periodStart: Long,
    val periodEnd: Long,
    val ticketCount: Int,
    /** Numéros du premier et du dernier ticket couverts (null si aucun ticket). */
    val firstTicketNumber: Long?,
    val lastTicketNumber: Long?,
    val discounts: Cents,
)

data class FiscalDocument(
    val id: String,
    val establishmentId: String,
    val terminalId: String,
    val type: FiscalDocType,
    /** Numéro continu dans la séquence (terminal, type), à partir de 1. */
    val number: Long,
    val issuedAt: Long,
    val actorId: String,
    val orderId: String?,
    val tableLabel: String?,
    /** Document d'origine (duplicata → ticket). */
    val relatedDocumentId: String?,
    val lines: List<FiscalLine>,
    val taxes: List<VatLine>,
    val total: Cents,
    val payments: List<FiscalPayment>,
    /** Grand total perpétuel des tickets de ce terminal APRÈS ce document (jamais remis à zéro). */
    val perpetualTotal: Cents,
    /** Grand total perpétuel en valeur absolue (insensible aux compensations futures par remboursement). */
    val perpetualTotalAbs: Cents,
    val closure: ClosureInfo?,
    val prevHash: String,
    val hash: String,
    val signature: String,
    val keyId: String,
) {
    val numberLabel: String get() = "$terminalId-${type.code}-${number.toString().padStart(6, '0')}"
}

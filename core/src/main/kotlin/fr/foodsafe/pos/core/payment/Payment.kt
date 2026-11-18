package fr.foodsafe.pos.core.payment

import fr.foodsafe.pos.core.money.Cents

enum class PaymentMethod(val label: String, val isCash: Boolean) {
    CASH("Espèces", true),
    CARD("Carte (TPE)", false),
    MEAL_VOUCHER("Titre-restaurant", false),
    CREDIT_NOTE("Avoir", false),
}

/** Événement de paiement : immuable, distinct du mouvement de fonds et du document fiscal. */
data class Payment(
    val id: String,
    val orderId: String,
    val method: PaymentMethod,
    /** Montant affecté à la note. */
    val amount: Cents,
    /** Montant remis par le client (espèces) ; rendu = tendered − amount. */
    val tendered: Cents,
    val cashierEmployeeId: String,
    val terminalId: String,
    val at: Long,
) {
    init {
        require(amount.value > 0) { "paiement strictement positif" }
        require(tendered >= amount) { "montant remis insuffisant" }
        require(method.isCash || tendered == amount) { "rendu monnaie uniquement en espèces" }
    }
    val change: Cents get() = tendered - amount
}

object PaymentRules {
    fun remaining(total: Cents, payments: List<Payment>): Cents =
        total - Cents(payments.sumOf { it.amount.value })

    /**
     * Construit un paiement : en espèces, un montant remis supérieur au reste dû génère un rendu ;
     * pour les autres moyens, un montant supérieur au reste dû est refusé.
     */
    fun pay(
        id: String, orderId: String, total: Cents, existing: List<Payment>, method: PaymentMethod,
        given: Cents, cashierId: String, terminalId: String, at: Long,
    ): Payment {
        val rest = remaining(total, existing)
        require(rest.value > 0) { "note déjà soldée" }
        require(given.value > 0) { "montant nul" }
        val amount = if (given > rest) {
            require(method.isCash) { "montant supérieur au reste dû ($rest) pour ${method.label}" }
            rest
        } else given
        return Payment(id, orderId, method, amount, given, cashierId, terminalId, at)
    }
}

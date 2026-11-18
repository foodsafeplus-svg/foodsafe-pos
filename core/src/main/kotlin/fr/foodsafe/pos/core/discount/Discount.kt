package fr.foodsafe.pos.core.discount

import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents

/** Remise sur une ligne article : pourcentage OU montant en euros. */
sealed interface LineDiscount {
    data class Percent(val rate: BasisPoints) : LineDiscount
    data class Amount(val amount: Cents) : LineDiscount {
        init { require(!amount.isNegative) { "remise négative interdite" } }
    }
}

/**
 * Remise sur la note entière : UNIQUEMENT en pourcentage (exigence métier).
 * Il n'existe volontairement aucun type "remise note en euros" : l'erreur est impossible à la compilation.
 */
data class TicketDiscount(val rate: BasisPoints)

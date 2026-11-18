package fr.foodsafe.pos.core.cash

import fr.foodsafe.pos.core.payment.PaymentMethod

enum class TerminalKind { FIXED, PAD }

/** Où créditer les espèces d'un paiement. */
sealed interface CashTarget {
    /** Paiement non-espèces, ou gestion des fonds désactivée : aucun mouvement de fonds. */
    data object None : CashTarget
    data class Drawer(val drawerId: String) : CashTarget
    data class PersonalFloat(val employeeId: String) : CashTarget
    /** Plusieurs tiroirs possibles : l'UI doit demander lequel a reçu les espèces. */
    data class AskDrawer(val drawerIds: List<String>) : CashTarget
}

/**
 * Règles d'attribution des espèces (cahier des charges §8) :
 *  - caisse fixe → tiroir associé ;
 *  - pad + serveur avec banane → banane ;
 *  - pad + serveur sans banane + un seul tiroir → ce tiroir ;
 *  - pad sans banane + plusieurs tiroirs → demander.
 * Un paiement carte ne crédite jamais les espèces.
 */
object CashAttribution {
    fun target(
        method: PaymentMethod,
        cashManagementEnabled: Boolean,
        terminalKind: TerminalKind,
        terminalDrawerId: String?,
        employeeId: String,
        employeeHasPersonalFloat: Boolean,
        establishmentDrawers: List<String>,
    ): CashTarget {
        if (!method.isCash || !cashManagementEnabled) return CashTarget.None
        return when (terminalKind) {
            TerminalKind.FIXED -> CashTarget.Drawer(
                requireNotNull(terminalDrawerId) { "caisse fixe sans tiroir associé : configuration invalide" },
            )
            TerminalKind.PAD -> when {
                employeeHasPersonalFloat -> CashTarget.PersonalFloat(employeeId)
                establishmentDrawers.size == 1 -> CashTarget.Drawer(establishmentDrawers.single())
                establishmentDrawers.isEmpty() -> error("aucun tiroir ni banane : encaissement espèces impossible")
                else -> CashTarget.AskDrawer(establishmentDrawers)
            }
        }
    }
}

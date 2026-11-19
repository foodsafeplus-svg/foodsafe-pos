package fr.foodsafe.pos.core.kitchen

import fr.foodsafe.pos.core.order.ServiceLevel

enum class KitchenTicketKind {
    /** Premier envoi des lignes (tous niveaux non encore réclamés), regroupés par niveau. */
    ORDER,
    /** Nouveaux articles ajoutés à un niveau déjà réclamé : "AJOUT N2". */
    ADDITION,
    /** "Réclamer N1" : demande de lancement du niveau. */
    CLAIM,
    /** Annulation d'un article déjà transmis (imprimé en inversé si possible). */
    CANCELLATION,
    /** Changement de table (43 → 56), sans réimprimer les préparations. */
    TABLE_MOVE,
}

/** [modifier] : commentaire ou supplément imprimé en retrait sous son produit. */
data class KitchenItem(val quantityMilli: Long, val label: String, val level: ServiceLevel, val modifier: Boolean = false)

data class KitchenTicket(
    val dispatchId: String,
    val destination: String,
    val kind: KitchenTicketKind,
    val tableLabel: String,
    val items: List<KitchenItem>,
    val level: ServiceLevel? = null,
    /** Fond noir / texte blanc demandé (si l'imprimante le supporte). */
    val inverse: Boolean = false,
    val note: String? = null,
)

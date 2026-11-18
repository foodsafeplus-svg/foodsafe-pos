package fr.foodsafe.pos.core.kitchen

import fr.foodsafe.pos.core.order.Cancellation
import fr.foodsafe.pos.core.order.Order
import fr.foodsafe.pos.core.order.OrderLine
import fr.foodsafe.pos.core.order.OrderStatus
import fr.foodsafe.pos.core.order.ServiceLevel

fun interface IdGenerator { fun next(): String }

data class DispatchResult(val order: Order, val tickets: List<KitchenTicket>) {
    val isEmpty get() = tickets.isEmpty()
}

class KitchenRuleViolation(message: String) : IllegalStateException(message)

/**
 * Règles d'envoi cuisine. Fonctions pures : (commande, action) -> (nouvelle commande, bons à imprimer).
 * La persistance doit enregistrer la nouvelle commande ET les bons dans la même transaction.
 */
object KitchenDispatcher {

    private fun tableLabel(order: Order) = order.tableNumber?.let { "TABLE $it" } ?: order.saleMode.name

    /**
     * "Envoyer commande" : transmet uniquement les lignes actives jamais envoyées.
     * Un second appel sans nouvelle ligne ne produit AUCUN bon.
     */
    fun send(order: Order, ids: IdGenerator): DispatchResult {
        val unsent = order.activeLines.filter { !it.isSent }
        if (unsent.isEmpty()) return DispatchResult(order, emptyList())
        val dispatchId = ids.next()
        val tickets = mutableListOf<KitchenTicket>()
        unsent.groupBy { it.destination }.toSortedMap().forEach { (destination, lines) ->
            val (additions, regular) = lines.partition { !it.level.isDirect && it.level.value in order.claimedLevels }
            if (regular.isNotEmpty()) {
                tickets += KitchenTicket(
                    dispatchId = dispatchId,
                    destination = destination,
                    kind = KitchenTicketKind.ORDER,
                    tableLabel = tableLabel(order),
                    items = regular.sortedBy { it.level.value }.map { it.toItem() },
                )
            }
            additions.groupBy { it.level.value }.toSortedMap().forEach { (level, addLines) ->
                tickets += KitchenTicket(
                    dispatchId = dispatchId,
                    destination = destination,
                    kind = KitchenTicketKind.ADDITION,
                    tableLabel = tableLabel(order),
                    items = addLines.map { it.toItem() },
                    level = ServiceLevel(level),
                )
            }
        }
        val unsentIds = unsent.map { it.id }.toSet()
        val updated = order.copy(
            lines = order.lines.map { if (it.id in unsentIds) it.copy(dispatchId = dispatchId) else it },
        ).bumped()
        return DispatchResult(updated, tickets)
    }

    /** Niveau que le bouton dynamique "Réclamer Nx" doit proposer, ou null s'il n'y a plus rien à réclamer. */
    fun nextClaimableLevel(order: Order): ServiceLevel? =
        order.activeLines.asSequence()
            .map { it.level.value }
            .filter { it > 0 && it !in order.claimedLevels }
            .minOrNull()
            ?.let { ServiceLevel(it) }

    /**
     * "Réclamer Nx" : strictement séquentiel. Les lignes non encore envoyées sont d'abord transmises,
     * puis un bon de réclamation part vers chaque destination concernée par ce niveau.
     * La note passe "en attente" (l'UI retourne ensuite au plan de salle).
     */
    fun claim(order: Order, level: ServiceLevel, ids: IdGenerator): DispatchResult {
        val expected = nextClaimableLevel(order)
            ?: throw KitchenRuleViolation("aucun niveau à réclamer")
        if (level != expected) throw KitchenRuleViolation("réclamation séquentielle : ${expected.label} doit être réclamé avant ${level.label}")
        val sent = send(order, ids)
        val claimId = ids.next()
        val claimTickets = sent.order.activeLines
            .filter { it.level == level }
            .groupBy { it.destination }
            .toSortedMap()
            .map { (destination, lines) ->
                KitchenTicket(
                    dispatchId = claimId,
                    destination = destination,
                    kind = KitchenTicketKind.CLAIM,
                    tableLabel = tableLabel(order),
                    items = lines.map { it.toItem() },
                    level = level,
                )
            }
        val updated = sent.order.copy(
            claimedLevels = sent.order.claimedLevels + level.value,
            status = OrderStatus.ON_HOLD,
        ).bumped()
        return DispatchResult(updated, sent.tickets + claimTickets)
    }

    /**
     * Annulation (totale ou partielle) d'une ligne. Motif OBLIGATOIRE (journalisé, jamais imprimé en cuisine).
     * Si la ligne avait été transmise : bon d'annulation inversé vers sa destination.
     */
    fun cancelLine(
        order: Order,
        lineId: String,
        quantityMilli: Long,
        reason: String,
        actorId: String,
        now: Long,
        ids: IdGenerator,
    ): DispatchResult {
        require(reason.isNotBlank()) { "motif d'annulation obligatoire" }
        val line = order.lines.firstOrNull { it.id == lineId } ?: throw NoSuchElementException("ligne $lineId")
        if (!line.isActive) throw KitchenRuleViolation("ligne déjà annulée")
        require(quantityMilli in 1..line.quantityMilli) { "quantité à annuler invalide" }
        // Les commentaires et suppléments d'une ligne sont annulés avec elle.
        val children = order.lines.filter { it.parentLineId == lineId && it.isActive }
        if (children.isNotEmpty() && quantityMilli != line.quantityMilli) throw KitchenRuleViolation("annulez la ligne entière (elle porte des commentaires ou suppléments)")
        val cancelledIds = setOf(lineId) + children.map { it.id }

        val cancellation = Cancellation(reason.trim(), actorId, now)
        val newLines = if (quantityMilli == line.quantityMilli) {
            order.lines.map { if (it.id in cancelledIds) it.copy(cancellation = cancellation) else it }
        } else {
            // Annulation partielle : la ligne d'origine garde le reste, une ligne annulée trace la part retirée.
            order.lines.flatMap {
                if (it.id != lineId) listOf(it) else listOf(
                    it.copy(quantityMilli = it.quantityMilli - quantityMilli),
                    it.copy(id = ids.next(), quantityMilli = quantityMilli, cancellation = cancellation),
                )
            }
        }
        val tickets = if (line.isSent) listOf(
            KitchenTicket(
                dispatchId = ids.next(),
                destination = line.destination,
                kind = KitchenTicketKind.CANCELLATION,
                tableLabel = tableLabel(order),
                items = listOf(KitchenItem(quantityMilli, line.label, line.level, modifier = line.isModifier)) +
                    children.filter { it.isSent }.map { it.toItem() },
                inverse = true,
            ),
        ) else emptyList()
        return DispatchResult(order.copy(lines = newLines).bumped(), tickets)
    }

    /**
     * Changement de table : petit bon "TABLE 43 → 56" vers les seules destinations ayant déjà reçu des préparations.
     */
    fun moveTable(order: Order, newTable: String, ids: IdGenerator): DispatchResult {
        require(newTable.isNotBlank())
        val old = order.tableNumber
        val destinations = order.activeLines.filter { it.isSent }.map { it.destination }.toSortedSet()
        val id = ids.next()
        val tickets = destinations.map {
            KitchenTicket(
                dispatchId = id,
                destination = it,
                kind = KitchenTicketKind.TABLE_MOVE,
                tableLabel = "TABLE $newTable",
                items = emptyList(),
                note = "TABLE ${old ?: "?"} → $newTable",
            )
        }
        return DispatchResult(order.copy(tableNumber = newTable).bumped(), tickets)
    }

    private fun OrderLine.toItem() = KitchenItem(quantityMilli, label, level, modifier = isModifier)
}

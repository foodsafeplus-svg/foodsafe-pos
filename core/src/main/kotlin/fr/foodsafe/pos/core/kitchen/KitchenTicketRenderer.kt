package fr.foodsafe.pos.core.kitchen

/**
 * Rendu texte d'un bon cuisine (aperçu écran et base de l'encodage ESC/POS).
 * 32 colonnes ≈ papier 58 mm, 48 colonnes ≈ 80 mm (police A).
 */
object KitchenTicketRenderer {
    fun render(ticket: KitchenTicket, columns: Int = 48, backupFor: String? = null): List<String> {
        val out = mutableListOf<String>()
        val sep = "-".repeat(columns)
        if (backupFor != null) out += center("DESTINATION : ${backupFor.uppercase()}", columns)
        val title = when (ticket.kind) {
            KitchenTicketKind.ORDER -> "COMMANDE"
            KitchenTicketKind.ADDITION -> "AJOUT ${ticket.level?.label}"
            KitchenTicketKind.CLAIM -> "RÉCLAMER ${ticket.level?.label}"
            KitchenTicketKind.CANCELLATION -> "*** ANNULATION ***"
            KitchenTicketKind.TABLE_MOVE -> "CHANGEMENT DE TABLE"
        }
        out += center(title, columns)
        out += center(ticket.tableLabel, columns)
        ticket.note?.let { out += center(it, columns) }
        if (ticket.items.isNotEmpty()) out += sep
        var currentLevel: Int? = null
        for (item in ticket.items) {
            if (ticket.kind == KitchenTicketKind.ORDER && item.level.value != currentLevel) {
                if (currentLevel != null) out += center("--- À suivre ---", columns)
                out += item.level.label
                currentLevel = item.level.value
            }
            out += (if (item.modifier) "    > ${item.label}" else "${formatQty(item.quantityMilli)} x ${item.label}").take(columns)
        }
        out += sep
        return out
    }

    fun formatQty(milli: Long): String =
        if (milli % 1000 == 0L) (milli / 1000).toString()
        else (milli / 1000).toString() + "," + (milli % 1000).toString().padStart(3, '0').trimEnd('0')

    private fun center(s: String, columns: Int): String {
        if (s.length >= columns) return s.take(columns)
        return " ".repeat((columns - s.length) / 2) + s
    }
}

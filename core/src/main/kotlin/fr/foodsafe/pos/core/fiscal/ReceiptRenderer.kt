package fr.foodsafe.pos.core.fiscal

import fr.foodsafe.pos.core.kitchen.KitchenTicketRenderer
import fr.foodsafe.pos.core.money.Cents
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Mise en page texte des documents (58 mm = 32 colonnes, 80 mm = 48 colonnes).
 * Les mentions légales (raison sociale, SIRET, TVA, numéro, date, ventilation TVA, empreinte) ne sont pas
 * paramétrables : seuls l'en-tête libre et le pied de page le seront (gabarits, phase suivante).
 * Liste des mentions à faire valider par l'expert-comptable / l'organisme certificateur.
 */
object ReceiptRenderer {
    /** Nom et version du logiciel imprimés en pied de document ; la version est fixée au démarrage de l'application. */
    @Volatile var SOFTWARE = "FOODSAFE+ POS"

    fun render(
        doc: FiscalDocument, est: Establishment, columns: Int = 48,
        zone: ZoneId = ZoneId.of("Europe/Paris"), remaining: Cents? = null,
    ): List<String> {
        val out = mutableListOf<String>()
        val sep = "-".repeat(columns)
        fun center(s: String) = if (s.length >= columns) s.take(columns) else " ".repeat((columns - s.length) / 2) + s
        fun lr(l: String, r: String): String {
            val space = columns - r.length - 1
            return l.take(space).padEnd(space) + " " + r
        }
        val date = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(zone).format(Instant.ofEpochMilli(doc.issuedAt))

        when (doc.type) {
            FiscalDocType.PROVISIONAL_NOTE -> { out += center("*** TICKET PROVISOIRE ***"); out += center("Ne vaut pas ticket de caisse") }
            FiscalDocType.DUPLICATE -> out += center("*** DUPLICATA ***")
            else -> {}
        }
        out += center(est.name.uppercase())
        est.addressLines.forEach { out += center(it) }
        out += center("SIRET ${est.siret}")
        out += center("TVA ${est.vatNumber}")
        out += sep
        if (doc.type == FiscalDocType.Z) return (out + renderZ(doc, date, sep, ::lr)).flatMap { it.chunked(columns) }

        doc.tableLabel?.let { out += it }
        doc.lines.forEachIndexed { i, l ->
            val qty = KitchenTicketRenderer.formatQty(l.quantityMilli)
            when {
                // composant de formule : sans prix, le total est sur l'en-tête
                l.label.startsWith(COMPONENT_PREFIX) -> out += "    " + l.label.removePrefix(COMPONENT_PREFIX)
                else -> {
                    val components = doc.lines.drop(i + 1).takeWhile { it.label.startsWith(COMPONENT_PREFIX) }
                    out += lr("$qty x ${l.label}", (if (components.isEmpty()) l.gross else Cents(components.sumOf { it.gross.value })).format(false))
                }
            }
            if (!l.discount.isZero) out += lr("   remise", "-" + l.discount.format(false))
        }
        out += sep
        out += lr("TOTAL TTC", doc.total.format())
        if (doc.type != FiscalDocType.PROVISIONAL_NOTE) {
            // Ventilation TVA : absente de la note provisoire (exigence métier).
            out += lr("Taux      HT        TVA", "TTC")
            doc.taxes.forEach { t ->
                out += lr("${t.rate.format().padEnd(9)} ${t.ht.format(false).padEnd(9)} ${t.vat.format(false)}", t.ttc.format(false))
            }
        }
        if (doc.payments.isNotEmpty()) {
            out += sep
            doc.payments.forEach { out += lr(it.method.label, it.amount.format()) }
            if (doc.type == FiscalDocType.PROVISIONAL_NOTE && remaining != null) out += lr("RESTE À PAYER", remaining.format())
        }
        out += sep
        out += when (doc.type) {
            FiscalDocType.DUPLICATE -> "Duplicata n° ${doc.numberLabel}"
            FiscalDocType.PROVISIONAL_NOTE -> "Note n° ${doc.numberLabel}"
            else -> "Ticket n° ${doc.numberLabel}"
        }
        out += date
        out += "Terminal ${doc.terminalId}"
        out += "Empreinte ${doc.hash.take(12)}"
        out += SOFTWARE
        return out.flatMap { it.chunked(columns) }
    }

    /** Ticket cadeau : produits et quantités sans aucun prix. N'est PAS un document fiscal (aucune seconde vente). */
    fun renderGift(doc: FiscalDocument, est: Establishment, selectedLabels: Set<String>? = null, columns: Int = 48): List<String> {
        val c = { s: String -> " ".repeat(((columns - s.length) / 2).coerceAtLeast(0)) + s }
        return listOf(c("TICKET CADEAU"), c(est.name.uppercase()), "-".repeat(columns)) +
            doc.lines.filter { selectedLabels == null || it.label in selectedLabels }
                .map { "${KitchenTicketRenderer.formatQty(it.quantityMilli)} x ${it.label}" } +
            listOf("-".repeat(columns), "Réf. ${doc.numberLabel}")
    }

    private fun renderZ(doc: FiscalDocument, date: String, sep: String, lr: (String, String) -> String): List<String> {
        val c = doc.closure!!
        val out = mutableListOf<String>()
        out += "CLÔTURE Z n° ${doc.number}"
        out += "Édité le $date"
        out += "Tickets : ${c.ticketCount}" + (c.firstTicketNumber?.let { " (n° $it à ${c.lastTicketNumber})" } ?: "")
        out += sep
        out += lr("CA TTC", doc.total.format())
        out += lr("dont remises", c.discounts.format())
        doc.taxes.forEach { out += lr("TVA ${it.rate.format()} (HT ${it.ht.format(false)})", it.vat.format()) }
        out += sep
        doc.payments.forEach { out += lr(it.method.label, it.amount.format()) }
        out += sep
        out += lr("Grand total perpétuel", doc.perpetualTotal.format())
        out += lr("GT perpétuel absolu", doc.perpetualTotalAbs.format())
        out += "Empreinte ${doc.hash.take(12)}"
        out += SOFTWARE
        return out
    }

    /** Rapport X : consultation, aucune remise à zéro, aucun document fiscal. */
    fun renderX(r: ClosureReport, est: Establishment, columns: Int = 48, zone: ZoneId = ZoneId.of("Europe/Paris")): List<String> {
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zone)
        fun lr(l: String, rr: String): String { val sp = columns - rr.length - 1; return l.take(sp).padEnd(sp) + " " + rr }
        val sep = "-".repeat(columns)
        val out = mutableListOf("RAPPORT X (sans clôture)", est.name.uppercase(),
            "Du ${fmt.format(Instant.ofEpochMilli(r.periodStart))}", "au ${fmt.format(Instant.ofEpochMilli(r.periodEnd))}",
            "Tickets : ${r.tickets.size}", sep, lr("CA TTC", r.total.format()), lr("dont remises", r.discounts.format()))
        r.taxes.forEach { out += lr("TVA ${it.rate.format()} (HT ${it.ht.format(false)})", it.vat.format()) }
        out += sep
        r.payments.forEach { out += lr(it.method.label, it.amount.format()) }
        out += sep
        out += lr("Grand total perpétuel", r.perpetualTotal.format())
        return out.flatMap { it.chunked(columns) }
    }
}

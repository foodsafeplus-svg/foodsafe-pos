package fr.foodsafe.pos.core.fiscal

import fr.foodsafe.pos.core.journal.CanonicalEncoder
import fr.foodsafe.pos.core.journal.FiscalSigner
import fr.foodsafe.pos.core.journal.JournalHasher
import fr.foodsafe.pos.core.journal.SignatureVerifier
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.PricedOrder
import fr.foodsafe.pos.core.payment.Payment
import fr.foodsafe.pos.core.tax.TaxCalculator
import java.security.PublicKey

/** Stockage append-only des documents fiscaux (Room côté Android, mémoire dans les tests). */
interface FiscalStore {
    fun last(type: FiscalDocType): FiscalDocument?
    fun insert(doc: FiscalDocument)
    fun byId(id: String): FiscalDocument?
    /** Documents d'un type, par numéro croissant. */
    fun all(type: FiscalDocType): List<FiscalDocument>
}

class InMemoryFiscalStore : FiscalStore {
    private val docs = mutableListOf<FiscalDocument>()
    @Synchronized override fun last(type: FiscalDocType) = docs.lastOrNull { it.type == type }
    @Synchronized override fun insert(doc: FiscalDocument) {
        val expected = (last(doc.type)?.number ?: 0) + 1
        require(doc.number == expected) { "numérotation discontinue : ${doc.number} au lieu de $expected" }
        docs += doc
    }
    @Synchronized override fun byId(id: String) = docs.firstOrNull { it.id == id }
    @Synchronized override fun all(type: FiscalDocType) = docs.filter { it.type == type }
}

class FiscalRuleViolation(message: String) : IllegalStateException(message)

/**
 * Émission des documents fiscaux d'UN terminal. À appeler dans la transaction de l'opération métier.
 *
 * Garanties : numérotation continue par type, chaînage SHA-256 par type, signature ECDSA de chaque hash,
 * grand total perpétuel (jamais remis à zéro). Aucun document n'est modifié ni supprimé : une erreur se corrige
 * par un document correctif (remboursement, phase suivante).
 */
class FiscalLedger(
    private val store: FiscalStore,
    private val signer: FiscalSigner,
    private val establishment: Establishment,
    private val terminalId: String,
) {
    fun issueTicket(
        id: String, orderId: String, tableLabel: String?, priced: PricedOrder, payments: List<Payment>,
        actorId: String, now: Long,
    ): FiscalDocument {
        val paid = Cents(payments.sumOf { it.amount.value })
        if (paid != priced.totalTtc) throw FiscalRuleViolation("ticket émis uniquement à solde nul (payé $paid / dû ${priced.totalTtc})")
        if (store.all(FiscalDocType.TICKET).any { it.orderId == orderId }) throw FiscalRuleViolation("ticket déjà émis pour cette commande")
        val last = store.last(FiscalDocType.TICKET)
        val total = priced.totalTtc
        return seal(
            id, FiscalDocType.TICKET, now, actorId, orderId, tableLabel, null,
            lines = priced.toFiscalLines(), taxes = priced.taxes.byRate, total = total,
            payments = payments.toFiscalPayments(),
            perpetual = (last?.perpetualTotal ?: Cents.ZERO) + total,
            perpetualAbs = (last?.perpetualTotalAbs ?: Cents.ZERO) + Cents(Math.abs(total.value)),
            closure = null,
        )
    }

    /** « Imprimer la note » : provisoire, ne clôture pas la vente, sans effet sur les totaux. */
    fun issueProvisionalNote(
        id: String, orderId: String, tableLabel: String?, priced: PricedOrder, payments: List<Payment>,
        actorId: String, now: Long,
    ): FiscalDocument = seal(
        id, FiscalDocType.PROVISIONAL_NOTE, now, actorId, orderId, tableLabel, null,
        lines = priced.toFiscalLines(), taxes = emptyList(), total = priced.totalTtc,
        payments = payments.toFiscalPayments(), perpetual = currentPerpetual(), perpetualAbs = currentPerpetualAbs(),
        closure = null,
    )

    /** Réimpression d'un ticket : document distinct marqué DUPLICATA, numéroté dans sa propre séquence. */
    fun issueDuplicate(id: String, originalId: String, actorId: String, now: Long): FiscalDocument {
        val original = store.byId(originalId) ?: throw NoSuchElementException("document $originalId")
        if (original.type != FiscalDocType.TICKET) throw FiscalRuleViolation("seul un ticket peut être dupliqué")
        return seal(
            id, FiscalDocType.DUPLICATE, now, actorId, original.orderId, original.tableLabel, original.id,
            lines = original.lines, taxes = original.taxes, total = original.total, payments = original.payments,
            perpetual = currentPerpetual(), perpetualAbs = currentPerpetualAbs(), closure = null,
        )
    }

    /** Rapport X : mêmes chiffres qu'un Z, sans rien enregistrer ni remettre à zéro. */
    fun reportX(now: Long): ClosureReport = computeClosure(now)

    /** Clôture Z : couvre tous les tickets depuis le Z précédent. Jamais réécrite. */
    fun closeZ(id: String, actorId: String, now: Long): FiscalDocument {
        val r = computeClosure(now)
        return seal(
            id, FiscalDocType.Z, now, actorId, null, null, null,
            lines = emptyList(), taxes = r.taxes, total = r.total, payments = r.payments,
            perpetual = r.perpetualTotal, perpetualAbs = r.perpetualTotalAbs,
            closure = ClosureInfo(r.periodStart, now, r.tickets.size, r.tickets.firstOrNull()?.number, r.tickets.lastOrNull()?.number, r.discounts),
        )
    }

    private fun computeClosure(now: Long): ClosureReport {
        val lastZ = store.last(FiscalDocType.Z)
        // Borne : dernier ticket couvert par un Z (un Z sans ticket ne déplace pas la borne).
        val after = store.all(FiscalDocType.Z).mapNotNull { it.closure?.lastTicketNumber }.maxOrNull() ?: 0L
        val tickets = store.all(FiscalDocType.TICKET).filter { it.number > after }
        val taxes = TaxCalculator.breakdown(tickets.flatMap { t -> t.taxes.map { it.rate to it.ttc } })
        val payments = tickets.flatMap { it.payments }.groupBy { it.method }
            .map { (m, ps) -> FiscalPayment(m, Cents(ps.sumOf { it.amount.value })) }
            .sortedBy { it.method.ordinal }
        return ClosureReport(
            periodStart = lastZ?.issuedAt ?: (tickets.firstOrNull()?.issuedAt ?: now),
            periodEnd = now,
            tickets = tickets,
            // On conserve les TVA calculées sur chaque ticket (somme), le recalcul global servant de contrôle.
            taxes = mergeTaxes(tickets),
            recomputedTaxes = taxes.byRate,
            total = Cents(tickets.sumOf { it.total.value }),
            discounts = Cents(tickets.sumOf { t -> t.lines.sumOf { it.discount.value } }),
            payments = payments,
            perpetualTotal = currentPerpetual(),
            perpetualTotalAbs = currentPerpetualAbs(),
        )
    }

    private fun mergeTaxes(tickets: List<FiscalDocument>) = tickets.flatMap { it.taxes }
        .groupBy { it.rate.value }.toSortedMap()
        .map { (_, l) -> fr.foodsafe.pos.core.tax.VatLine(l.first().rate, Cents(l.sumOf { it.ttc.value }), Cents(l.sumOf { it.ht.value }), Cents(l.sumOf { it.vat.value })) }

    private fun currentPerpetual() = store.last(FiscalDocType.TICKET)?.perpetualTotal ?: Cents.ZERO
    private fun currentPerpetualAbs() = store.last(FiscalDocType.TICKET)?.perpetualTotalAbs ?: Cents.ZERO

    private fun seal(
        id: String, type: FiscalDocType, now: Long, actorId: String, orderId: String?, tableLabel: String?,
        relatedId: String?, lines: List<FiscalLine>, taxes: List<fr.foodsafe.pos.core.tax.VatLine>, total: Cents,
        payments: List<FiscalPayment>, perpetual: Cents, perpetualAbs: Cents, closure: ClosureInfo?,
    ): FiscalDocument {
        val last = store.last(type)
        val draft = FiscalDocument(
            id = id, establishmentId = establishment.id, terminalId = terminalId, type = type,
            number = (last?.number ?: 0) + 1, issuedAt = now, actorId = actorId, orderId = orderId,
            tableLabel = tableLabel, relatedDocumentId = relatedId, lines = lines, taxes = taxes, total = total,
            payments = payments, perpetualTotal = perpetual, perpetualTotalAbs = perpetualAbs, closure = closure,
            prevHash = last?.hash ?: FiscalCanonical.genesis(terminalId, type), hash = "", signature = "", keyId = signer.keyId,
        )
        val hash = FiscalCanonical.hash(draft)
        val doc = draft.copy(hash = hash, signature = signer.sign(hash.toByteArray(Charsets.UTF_8)))
        store.insert(doc)
        return doc
    }
}

data class ClosureReport(
    val periodStart: Long,
    val periodEnd: Long,
    val tickets: List<FiscalDocument>,
    val taxes: List<fr.foodsafe.pos.core.tax.VatLine>,
    val recomputedTaxes: List<fr.foodsafe.pos.core.tax.VatLine>,
    val total: Cents,
    val discounts: Cents,
    val payments: List<FiscalPayment>,
    val perpetualTotal: Cents,
    val perpetualTotalAbs: Cents,
)

object FiscalCanonical {
    fun genesis(terminalId: String, type: FiscalDocType) = JournalHasher.sha256Hex("GENESIS|FISCAL|$terminalId|${type.code}")

    /** Contenu canonique signé : tout ce qui figure sur le document + le chaînage. */
    fun canonical(d: FiscalDocument): String = CanonicalEncoder.fields(
        d.id, d.establishmentId, d.terminalId, d.type.code, d.number.toString(), d.issuedAt.toString(), d.actorId,
        d.orderId, d.tableLabel, d.relatedDocumentId,
        d.lines.joinToString("\n") { "${it.label};${it.quantityMilli};${it.unitPriceTtc.value};${it.gross.value};${it.discount.value};${it.net.value};${it.vatRate.value}" },
        d.taxes.joinToString("\n") { "${it.rate.value};${it.ttc.value};${it.ht.value};${it.vat.value}" },
        d.total.value.toString(),
        d.payments.joinToString("\n") { "${it.method.name};${it.amount.value}" },
        d.perpetualTotal.value.toString(), d.perpetualTotalAbs.value.toString(),
        d.closure?.let { "${it.periodStart};${it.periodEnd};${it.ticketCount};${it.firstTicketNumber};${it.lastTicketNumber};${it.discounts.value}" },
        d.keyId, d.prevHash,
    )

    fun hash(d: FiscalDocument) = JournalHasher.sha256Hex(canonical(d))
}

sealed interface FiscalVerification {
    data class Valid(val count: Int) : FiscalVerification
    data class Broken(val type: FiscalDocType, val number: Long, val problem: String) : FiscalVerification
}

object FiscalVerifier {
    /** Vérifie numérotation, chaînage, hash et signature de chaque type de document. */
    fun verify(terminalId: String, store: FiscalStore, publicKeys: Map<String, PublicKey>): FiscalVerification {
        var count = 0
        for (type in FiscalDocType.entries) {
            var prev = FiscalCanonical.genesis(terminalId, type)
            var expected = 1L
            for (d in store.all(type)) {
                if (d.number != expected) return FiscalVerification.Broken(type, d.number, "numéro attendu $expected")
                if (d.prevHash != prev) return FiscalVerification.Broken(type, d.number, "chaînage rompu")
                if (FiscalCanonical.hash(d) != d.hash) return FiscalVerification.Broken(type, d.number, "contenu altéré")
                val key = publicKeys[d.keyId] ?: return FiscalVerification.Broken(type, d.number, "clé ${d.keyId} inconnue")
                if (!SignatureVerifier.verify(key, d.hash.toByteArray(Charsets.UTF_8), d.signature)) {
                    return FiscalVerification.Broken(type, d.number, "signature invalide")
                }
                prev = d.hash; expected++; count++
            }
        }
        return FiscalVerification.Valid(count)
    }
}

/** Les commentaires gratuits (« Saignant ») ne vont qu'en cuisine ; un supplément payant figure sur le ticket (« + Œuf »). */
const val COMPONENT_PREFIX = "  > "

fun PricedOrder.toFiscalLines() = lines.filter { !(it.line.isModifier && it.gross.isZero) }.map {
    FiscalLine(
        when {
            it.line.isModifier -> "+ " + it.line.label
            // composant de formule : montant ventilé (TVA par taux), imprimé sous l'en-tête sans prix
            it.line.isComponent -> COMPONENT_PREFIX + it.line.label
            else -> it.line.label
        }, it.line.quantityMilli, it.line.unitPriceTtc, it.gross, it.lineDiscount + it.ticketDiscount, it.net, it.line.vatRate)
}

private fun List<Payment>.toFiscalPayments() = map { FiscalPayment(it.method, it.amount) }

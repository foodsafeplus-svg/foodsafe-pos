package fr.foodsafe.pos.core

import fr.foodsafe.pos.core.discount.TicketDiscount
import fr.foodsafe.pos.core.fiscal.Establishment
import fr.foodsafe.pos.core.fiscal.FiscalDocType
import fr.foodsafe.pos.core.fiscal.FiscalLedger
import fr.foodsafe.pos.core.fiscal.FiscalRuleViolation
import fr.foodsafe.pos.core.fiscal.FiscalVerification
import fr.foodsafe.pos.core.fiscal.FiscalVerifier
import fr.foodsafe.pos.core.fiscal.InMemoryFiscalStore
import fr.foodsafe.pos.core.fiscal.ReceiptRenderer
import fr.foodsafe.pos.core.journal.EcdsaSigner
import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.OrderPricer
import fr.foodsafe.pos.core.payment.Payment
import fr.foodsafe.pos.core.payment.PaymentMethod
import fr.foodsafe.pos.core.tax.FrenchVat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FiscalTest {
    private val est = Establishment("E1", "Pizzeria Test", listOf("1 rue de la Paix", "75002 Paris"), "12345678900011", "FR00123456789")
    private val kp = EcdsaSigner.generateKeyPair()
    private val store = InMemoryFiscalStore()
    private val ledger = FiscalLedger(store, EcdsaSigner("T01-k1", kp.private), est, "T01")
    private val keys = mapOf("T01-k1" to kp.public)
    private var n = 0
    private fun id() = "doc-${++n}"

    private val pizzaBiere = order(
        line("l1", "Pizza", 1200, FrenchVat.INTERMEDIATE),
        line("l2", "Bière", 600, FrenchVat.NORMAL),
    ).copy(ticketDiscount = TicketDiscount(BasisPoints.percent(10)))

    private fun pay(orderId: String, vararg p: Pair<PaymentMethod, Long>) =
        p.mapIndexed { i, (m, a) -> Payment("p$orderId$i", orderId, m, Cents(a), Cents(a), "marie", "T01", 0) }

    private fun sell(orderId: String, amountOrder: fr.foodsafe.pos.core.order.Order, at: Long, vararg p: Pair<PaymentMethod, Long>) =
        ledger.issueTicket(id(), orderId, "TABLE 43", OrderPricer.price(amountOrder.copy(id = orderId)), pay(orderId, *p), "marie", at)

    @Test fun `ticket numerote chaine signe avec grand total perpetuel`() {
        val t1 = sell("o1", pizzaBiere, 1000, PaymentMethod.CARD to 1000, PaymentMethod.CASH to 620)
        val t2 = sell("o2", order(line("l1", "Café", 200)), 2000, PaymentMethod.CASH to 200)
        assertEquals("T01-T-000001", t1.numberLabel)
        assertEquals(2L, t2.number)
        assertEquals(t1.hash, t2.prevHash)
        assertEquals(Cents(1620), t1.perpetualTotal)
        assertEquals(Cents(1820), t2.perpetualTotal)
        assertEquals(FiscalVerification.Valid(2), FiscalVerifier.verify("T01", store, keys))
    }

    @Test fun `ticket refuse si solde non nul ou deja emis`() {
        assertThrows<FiscalRuleViolation> { sell("o1", pizzaBiere, 1, PaymentMethod.CARD to 1000) }
        sell("o1", pizzaBiere, 1, PaymentMethod.CARD to 1620)
        assertThrows<FiscalRuleViolation> { sell("o1", pizzaBiere, 2, PaymentMethod.CARD to 1620) }
    }

    @Test fun `alteration d un montant ou d une signature detectee`() {
        sell("o1", pizzaBiere, 1, PaymentMethod.CARD to 1620)
        sell("o2", pizzaBiere, 2, PaymentMethod.CARD to 1620)
        val docs = store.all(FiscalDocType.TICKET)
        val tampered = InMemoryFiscalStore().apply {
            insert(docs[0].copy(total = Cents(1000)))
            insert(docs[1])
        }
        val r = FiscalVerifier.verify("T01", tampered, keys)
        assertEquals(FiscalVerification.Broken(FiscalDocType.TICKET, 1, "contenu altéré"), r)

        val otherKey = EcdsaSigner.generateKeyPair().public
        assertInstanceOf(FiscalVerification.Broken::class.java, FiscalVerifier.verify("T01", store, mapOf("T01-k1" to otherKey)))
    }

    @Test fun `note provisoire sans TVA sans effet sur les totaux ni sur le Z`() {
        val o = pizzaBiere.copy(id = "o1")
        val partial = pay("o1", PaymentMethod.CARD to 1000)
        val note = ledger.issueProvisionalNote(id(), "o1", "TABLE 43", OrderPricer.price(o), partial, "marie", 1)
        assertEquals(FiscalDocType.PROVISIONAL_NOTE, note.type)
        assertTrue(note.taxes.isEmpty())
        assertEquals(Cents.ZERO, note.perpetualTotal)
        val text = ReceiptRenderer.render(note, est, remaining = Cents(620))
        assertEquals("*** TICKET PROVISOIRE ***", text.first().trim())
        assertTrue(text.indexOfFirst { "TICKET PROVISOIRE" in it } < text.indexOfFirst { "Pizza" in it })
        assertFalse(text.any { it.startsWith("Taux") })
        assertTrue(text.any { "RESTE À PAYER" in it && "6,20" in it })
        assertEquals(0, ledger.reportX(2).tickets.size)
    }

    @Test fun `duplicata marque et numerote dans sa propre sequence`() {
        val t = sell("o1", pizzaBiere, 1, PaymentMethod.CARD to 1620)
        val d1 = ledger.issueDuplicate(id(), t.id, "paul", 2)
        val d2 = ledger.issueDuplicate(id(), t.id, "paul", 3)
        assertEquals("T01-D-000002", d2.numberLabel)
        assertEquals(t.id, d1.relatedDocumentId)
        assertEquals("*** DUPLICATA ***", ReceiptRenderer.render(d1, est).first().trim())
        assertEquals(Cents(1620), ledger.reportX(4).total) // un duplicata n'est pas une vente
        assertThrows<FiscalRuleViolation> { ledger.issueDuplicate(id(), d1.id, "paul", 5) }
    }

    @Test fun `ticket final avec mentions et ventilation TVA`() {
        val t = sell("o1", pizzaBiere, 1_700_000_000_000, PaymentMethod.CARD to 1620)
        val text = ReceiptRenderer.render(t, est, columns = 48).joinToString("\n")
        listOf("PIZZERIA TEST", "SIRET 12345678900011", "TVA FR00123456789", "TOTAL TTC", "Ticket n° T01-T-000001", "Empreinte ${t.hash.take(12)}", "14/11/2023")
            .forEach { assertTrue(it in text, "mention absente : $it\n$text") }
        assertTrue(text.lines().any { it.startsWith("10 %") && it.endsWith("10,80") })
        assertTrue(text.lines().any { it.startsWith("20 %") && it.endsWith("5,40") })
        assertTrue(text.lines().all { it.length <= 48 })
        assertTrue(ReceiptRenderer.render(t, est, columns = 32).all { it.length <= 32 })
    }

    @Test fun `ticket cadeau sans aucun prix et sans document fiscal`() {
        val t = sell("o1", pizzaBiere, 1, PaymentMethod.CARD to 1620)
        val before = store.all(FiscalDocType.TICKET).size
        val gift = ReceiptRenderer.renderGift(t, est, setOf("Pizza"))
        assertTrue(gift.none { "€" in it || "," in it })
        assertTrue(gift.any { "Pizza" in it } && gift.none { "Bière" in it })
        assertEquals(before, store.all(FiscalDocType.TICKET).size)
    }

    @Test fun `X ne cloture rien et Z couvre exactement les tickets depuis le Z precedent`() {
        sell("o1", pizzaBiere, 10, PaymentMethod.CARD to 1620)
        sell("o2", order(line("l1", "Café", 200)), 20, PaymentMethod.CASH to 200)
        val x = ledger.reportX(30)
        assertEquals(Cents(1820), x.total)
        assertEquals(Cents(1820), ledger.reportX(31).total) // X répété : identique

        val z1 = ledger.closeZ(id(), "paul", 40)
        assertEquals(2, z1.closure!!.ticketCount)
        assertEquals(Cents(1820), z1.total)
        assertEquals(Cents(180), z1.closure!!.discounts)
        assertEquals(listOf(PaymentMethod.CASH to Cents(200), PaymentMethod.CARD to Cents(1620)), z1.payments.map { it.method to it.amount })
        assertEquals(Cents(z1.taxes.sumOf { it.ttc.value }), z1.total)
        // Contrôle : TVA du Z = somme des TVA des tickets ; écart avec le recalcul global ≤ 1 centime par ticket
        x.taxes.zip(x.recomputedTaxes).forEach { (a, b) -> assertTrue(Math.abs(a.vat.value - b.vat.value) <= x.tickets.size) }

        val emptyZ = ledger.closeZ(id(), "paul", 50)
        assertEquals(0, emptyZ.closure!!.ticketCount)
        assertNull(emptyZ.closure!!.lastTicketNumber)

        sell("o3", order(line("l1", "Eau", 400)), 60, PaymentMethod.CASH to 400)
        val z3 = ledger.closeZ(id(), "paul", 70)
        assertEquals(1, z3.closure!!.ticketCount)
        assertEquals(3L, z3.closure!!.firstTicketNumber)
        assertEquals(Cents(2220), z3.perpetualTotal) // le grand total ne repart jamais à zéro
        assertEquals(Cents(400), z3.total)
        assertTrue("CLÔTURE Z n° 3" in ReceiptRenderer.render(z3, est).joinToString("\n"))
        assertEquals(FiscalVerification.Valid(6), FiscalVerifier.verify("T01", store, keys))
    }

    @Test fun `insertion hors sequence refusee par le stockage`() {
        val t = sell("o1", pizzaBiere, 1, PaymentMethod.CARD to 1620)
        assertThrows<IllegalArgumentException> { store.insert(t.copy(id = "x", number = 5)) }
    }
}

package fr.foodsafe.pos.app

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.foodsafe.pos.app.data.ActionDenied
import fr.foodsafe.pos.app.data.ApprovalRequired
import fr.foodsafe.pos.app.data.DemoCatalog
import fr.foodsafe.pos.app.data.PosDatabase
import fr.foodsafe.pos.app.data.PosRepository
import fr.foodsafe.pos.app.data.SoftwareKeyProvider
import fr.foodsafe.pos.core.auth.LoginResult
import fr.foodsafe.pos.core.discount.TicketDiscount
import fr.foodsafe.pos.core.fiscal.FiscalDocType
import fr.foodsafe.pos.core.fiscal.FiscalVerification
import fr.foodsafe.pos.core.journal.ChainVerification
import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.payment.PaymentMethod
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FiscalRepositoryTest {
    private lateinit var db: PosDatabase
    private lateinit var repo: PosRepository
    private var clock = 1_700_000_000_000L
    private val p = DemoCatalog.products.associateBy { it.id }
    private var k = 0
    private fun key() = "k${++k}"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PosDatabase::class.java)
            .allowMainThreadQueries().addCallback(PosDatabase.APPEND_ONLY_CALLBACK).build()
        repo = PosRepository(db, TestFixtures.establishment, "T01", SoftwareKeyProvider("T01"), clock = { clock += 1000; clock })
        repo.bootstrap()
    }

    @After fun tearDown() = db.close()

    private fun sale(table: String, vararg products: String, method: PaymentMethod = PaymentMethod.CARD): String {
        val o = repo.openOrder("marie", table).order.id
        products.forEach { repo.addProduct(o, "marie", p.getValue(it), null, key()) }
        repo.pay(o, "marie", method, repo.snapshot(o)!!.remaining, key())
        return o
    }

    @Test fun connexionPinEtVerrouillage() {
        assertEquals("paul", (repo.login("9999") as LoginResult.Success).employee.id)
        repeat(4) { assertEquals(LoginResult.WrongPin, repo.login("0000")) }
        assertTrue(repo.login("0000") is LoginResult.Locked)
        assertTrue(repo.login("1111") is LoginResult.Locked)
        val types = db.dao().allJournal().map { it.type }
        assertTrue("LOGIN" in types && "LOGIN_FAILED" in types && "TERMINAL_LOCKED" in types)
        assertTrue(db.dao().allJournal().none { it.after_json?.contains("0000") == true || it.reason?.contains("0000") == true })
    }

    @Test fun ticketFiscalEmisAuSoldeDansLaMemeTransaction() {
        val o = repo.openOrder("marie", "12").order.id
        repo.addProduct(o, "marie", p.getValue("margherita"), null, key())
        repo.addProduct(o, "marie", p.getValue("biere"), null, key())
        repo.pay(o, "marie", PaymentMethod.CARD, Cents(1000), key())
        assertNull(repo.snapshot(o)!!.ticket) // paiement partiel : pas de ticket
        val done = repo.pay(o, "marie", PaymentMethod.CASH, Cents(1000), key())
        val t = done.ticket!!
        assertEquals("T01-T-000001", t.numberLabel)
        assertEquals(Cents(1800), t.total)
        assertEquals(Cents(1800), t.perpetualTotal)
        assertEquals(2, t.payments.size)
        val r = repo.verifyAll()
        assertTrue(r.journal is ChainVerification.Valid)
        assertEquals(FiscalVerification.Valid(1), r.fiscal)
    }

    @Test fun noteProvisoireDuplicataEtDroits() {
        val o = repo.openOrder("marie", "5").order.id
        repo.addProduct(o, "marie", p.getValue("salade"), null, key())
        val note = repo.printProvisionalNote(o, "marie")
        assertEquals(FiscalDocType.PROVISIONAL_NOTE, note.type)
        repo.pay(o, "marie", PaymentMethod.CARD, Cents(950), key())
        val ticket = repo.snapshot(o)!!.ticket!!
        try { repo.duplicate(ticket.id, "marie"); fail() } catch (_: ApprovalRequired) {}
        val d = repo.duplicate(ticket.id, "marie", approverId = "paul")
        assertEquals("T01-D-000001", d.numberLabel)
        try { repo.duplicate(ticket.id, "marie", approverId = "marie"); fail() } catch (_: ActionDenied) {}
        assertEquals(FiscalVerification.Valid(3), repo.verifyAll().fiscal)
    }

    @Test fun remiseAuDelaDuSeuilExigeResponsable() {
        val o = repo.openOrder("marie", "8").order.id
        repo.addProduct(o, "marie", p.getValue("reine"), null, key())
        repo.setTicketDiscount(o, "marie", TicketDiscount(BasisPoints.percent(10)), key())
        try { repo.setTicketDiscount(o, "marie", TicketDiscount(BasisPoints.percent(20)), key()); fail() } catch (_: ApprovalRequired) {}
        assertEquals(Cents(1215), repo.snapshot(o)!!.priced.totalTtc) // toujours 10 %
        repo.setTicketDiscount(o, "marie", TicketDiscount(BasisPoints.percent(20)), key(), approverId = "paul")
        assertEquals(Cents(1080), repo.snapshot(o)!!.priced.totalTtc)
        repo.setTicketDiscount(o, "paul", TicketDiscount(BasisPoints.percent(50)), key()) // responsable : libre
        assertEquals(Cents(675), repo.snapshot(o)!!.priced.totalTtc)
    }

    @Test fun clotureZInterditeAuServeurEtCorrecte() {
        sale("1", "margherita", "biere")
        sale("2", "cafe", method = PaymentMethod.CASH)
        try { repo.closeZ("marie"); fail() } catch (_: ActionDenied) {}
        try { repo.closeZ("marie", approverId = "paul"); fail() } catch (_: ActionDenied) {} // interdit ≠ validation
        assertEquals(Cents(2000), repo.reportX("paul").total)
        val z = repo.closeZ("paul")
        assertEquals(2, z.closure!!.ticketCount)
        assertEquals(Cents(2000), z.total)
        sale("3", "eau")
        val z2 = repo.closeZ("paul")
        assertEquals(1, z2.closure!!.ticketCount)
        assertEquals(Cents(2400), z2.perpetualTotal)
        assertEquals(FiscalVerification.Valid(5), repo.verifyAll().fiscal)
    }

    @Test fun documentsFiscauxInalterablesEnBase() {
        sale("1", "cafe")
        val w = db.openHelper.writableDatabase
        listOf(
            "UPDATE fiscal_documents SET total = 1",
            "DELETE FROM fiscal_documents",
            "DELETE FROM fiscal_keys",
        ).forEach { sql ->
            try { w.execSQL(sql); fail("accepté : $sql") } catch (e: SQLiteException) { assertTrue(e.message!!.contains("ajout seul")) }
        }
        // Insertion forgée hors séquence refusée par trigger
        try {
            w.execSQL(
                "INSERT INTO fiscal_documents (id, establishmentId, terminalId, type, number, issuedAt, actorId, linesJson, taxesJson, total, paymentsJson, perpetualTotal, perpetualTotalAbs, prevHash, hash, signature, keyId) " +
                    "VALUES ('x','ETAB','T01','TICKET',7,0,'pirate','[]','[]',0,'[]',0,0,'0','0','0','k')",
            )
            fail("insertion forgée acceptée")
        } catch (e: SQLiteException) { assertTrue(e.message!!.contains("hors séquence")) }
        assertEquals(FiscalVerification.Valid(1), repo.verifyAll().fiscal)
    }

    /** Produit des exemples de documents (docs/exemples) à faire relire par l'expert-comptable. */
    @Test fun exemplesDeDocuments() {
        val o = repo.openOrder("marie", "43").order.id
        listOf("salade", "margherita", "biere").forEach { repo.addProduct(o, "marie", p.getValue(it), null, key()) }
        repo.setTicketDiscount(o, "paul", TicketDiscount(BasisPoints.percent(10)), key())
        repo.pay(o, "marie", PaymentMethod.CARD, Cents(1500), key())
        val note = repo.printProvisionalNote(o, "marie")
        repo.pay(o, "marie", PaymentMethod.CASH, Cents(1000), key())
        val ticket = repo.snapshot(o)!!.ticket!!
        val dup = repo.duplicate(ticket.id, "paul")
        val x = repo.reportX("paul")
        val z = repo.closeZ("paul")
        val est = TestFixtures.establishment
        val R = fr.foodsafe.pos.core.fiscal.ReceiptRenderer
        val files = mapOf(
            "note-provisoire-80mm.txt" to R.render(note, est, remaining = Cents(975)),
            "ticket-80mm.txt" to R.render(ticket, est),
            "ticket-58mm.txt" to R.render(ticket, est, columns = 32),
            "duplicata-80mm.txt" to R.render(dup, est),
            "ticket-cadeau-80mm.txt" to R.renderGift(ticket, est),
            "rapport-x-80mm.txt" to R.renderX(x, est),
            "cloture-z-80mm.txt" to R.render(z, est),
        )
        files.values.forEach { lines -> assertTrue(lines.isNotEmpty()) }
        val dir = System.getProperty("examplesDir")?.let(::File) ?: return
        dir.mkdirs()
        files.forEach { (name, lines) -> File(dir, name).writeText(lines.joinToString("\n") + "\n") }
    }

    @Test fun tableDejaOuverteRefusee() {
        repo.openOrder("marie", "40")
        try { repo.openOrder("paul", "40"); fail() } catch (_: IllegalStateException) {}
    }
}

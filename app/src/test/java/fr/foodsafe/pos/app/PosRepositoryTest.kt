package fr.foodsafe.pos.app

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.foodsafe.pos.app.data.DemoCatalog
import fr.foodsafe.pos.app.data.PosDatabase
import fr.foodsafe.pos.app.data.PosRepository
import fr.foodsafe.pos.core.discount.TicketDiscount
import fr.foodsafe.pos.core.journal.ChainVerification
import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.OrderStatus
import fr.foodsafe.pos.core.order.ServiceLevel
import fr.foodsafe.pos.core.payment.PaymentMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests d'intégration Room réels (SQLite Android via Robolectric) : transactions, idempotence, triggers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PosRepositoryTest {
    private lateinit var db: PosDatabase
    private lateinit var repo: PosRepository
    private var clock = 1_000L
    private val p = DemoCatalog.products.associateBy { it.id }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PosDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(PosDatabase.APPEND_ONLY_CALLBACK)
            .build()
        repo = PosRepository(db, "ETAB", "T01", clock = { clock++ })
    }

    @After fun tearDown() = db.close()

    private fun ticketCount(orderId: String) = db.dao().ticketCount(orderId)

    @Test fun parcoursCompletPersisteEtJournalIntegre() {
        val o = repo.openOrder("marie", "43").order.id
        repo.addProduct(o, "marie", p.getValue("salade"), null, "k1")
        repo.addProduct(o, "marie", p.getValue("margherita"), null, "k2")
        repo.addProduct(o, "marie", p.getValue("biere"), null, "k3")

        repo.sendToKitchen(o, "marie", "k4")
        assertEquals(2, ticketCount(o)) // CUISINE + BAR
        repo.sendToKitchen(o, "marie", "k5") // second envoi : rien
        assertEquals(2, ticketCount(o))

        val claimed = repo.claimNext(o, "marie", "k6")
        assertEquals(OrderStatus.ON_HOLD, claimed.order.status)
        assertEquals(setOf(1), claimed.order.claimedLevels)

        repo.setTicketDiscount(o, "marie", TicketDiscount(BasisPoints.percent(10)), "k7")
        val s = repo.snapshot(o)!!
        // 9,50→8,55 ; 12,00→10,80 ; 6,00→5,40
        assertEquals(Cents(2475), s.priced.totalTtc)

        repo.pay(o, "paul", PaymentMethod.CARD, Cents(1000), "k8")
        val done = repo.pay(o, "paul", PaymentMethod.CASH, Cents(2000), "k9")
        assertEquals(OrderStatus.PAID, done.order.status)
        assertEquals(Cents(525), done.payments.last().change)

        // Relecture depuis la base (pas de cache) : état identique
        assertEquals(done.order, repo.snapshot(o)!!.order)
        val v = repo.verifyJournal()
        assertTrue(v.toString(), v is ChainVerification.Valid)
        runBlocking { assertTrue(repo.journalFlow.first().isNotEmpty()) }
    }

    @Test fun memeCleIdempotenceNeRejouePas() {
        val o = repo.openOrder("marie", "12").order.id
        repo.addProduct(o, "marie", p.getValue("reine"), null, "same")
        repo.addProduct(o, "marie", p.getValue("reine"), null, "same")
        assertEquals(1, repo.snapshot(o)!!.order.lines.size)
        repo.pay(o, "marie", PaymentMethod.CARD, Cents(1350), "pay-1")
        repo.pay(o, "marie", PaymentMethod.CARD, Cents(1350), "pay-1") // renvoi après coupure
        assertEquals(1, repo.snapshot(o)!!.payments.size)
    }

    @Test fun ajoutApresReclamationProduitTicketAjout() {
        val o = repo.openOrder("marie", "7").order.id
        repo.addProduct(o, "marie", p.getValue("salade"), null, "a")
        repo.claimNext(o, "marie", "b")
        repo.addProduct(o, "marie", p.getValue("burrata"), ServiceLevel(1), "c")
        repo.sendToKitchen(o, "marie", "d")
        val last = runBlocking { repo.tickets.first() }.first()
        assertEquals("ADDITION", last.kind)
        assertTrue(last.renderedText.contains("AJOUT N1"))
        assertTrue(last.renderedText.contains("Burrata"))
        assertTrue(!last.renderedText.contains("Salade"))
    }

    @Test fun annulationLigneEnvoyeeExigeMotifEtJournalise() {
        val o = repo.openOrder("marie", "9").order.id
        repo.addProduct(o, "marie", p.getValue("margherita"), null, "a")
        repo.sendToKitchen(o, "marie", "b")
        val lineId = repo.snapshot(o)!!.order.lines.single().id
        try { repo.cancelLine(o, "marie", lineId, " ", "c"); fail("motif vide accepté") } catch (_: IllegalArgumentException) {}
        repo.cancelLine(o, "marie", lineId, "Client parti", "d")
        val t = runBlocking { repo.tickets.first() }.first()
        assertEquals("CANCELLATION", t.kind)
        assertTrue(t.inverse)
        assertTrue(!t.renderedText.contains("Client parti"))
        assertEquals("Client parti", db.dao().allJournal().last().reason)
    }

    @Test fun triggersInterdisentModificationJournalEtPaiements() {
        val o = repo.openOrder("marie", "1").order.id
        repo.addProduct(o, "marie", p.getValue("cafe"), null, "a")
        repo.pay(o, "marie", PaymentMethod.CASH, Cents(200), "b")
        val w = db.openHelper.writableDatabase
        listOf(
            "UPDATE journal_events SET actor_id = 'pirate'",
            "DELETE FROM journal_events",
            "UPDATE payments SET amount = 1",
            "DELETE FROM payments",
        ).forEach { sql ->
            try { w.execSQL(sql); fail("accepté : $sql") } catch (e: SQLiteException) { assertTrue(e.message!!.contains("ajout seul")) }
        }
        assertTrue(repo.verifyJournal() is ChainVerification.Valid)
    }

    @Test fun commandeSoldeeNonModifiable() {
        val o = repo.openOrder("marie", "2").order.id
        repo.addProduct(o, "marie", p.getValue("eau"), null, "a")
        repo.pay(o, "marie", PaymentMethod.CARD, Cents(400), "b")
        try { repo.addProduct(o, "marie", p.getValue("eau"), null, "c"); fail() } catch (_: IllegalStateException) {}
        assertEquals(1, repo.snapshot(o)!!.order.lines.size)
    }
}

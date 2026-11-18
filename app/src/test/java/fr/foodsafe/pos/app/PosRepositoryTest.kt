package fr.foodsafe.pos.app

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.foodsafe.pos.app.data.DemoCatalog
import fr.foodsafe.pos.app.data.PosDatabase
import fr.foodsafe.pos.app.data.PosRepository
import fr.foodsafe.pos.app.data.SoftwareKeyProvider
import fr.foodsafe.pos.app.data.ApprovalRequired
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
        repo = PosRepository(db, TestFixtures.establishment, "T01", SoftwareKeyProvider("T01"), clock = { clock++ })
        repo.bootstrap()
    }

    @After fun tearDown() = db.close()

    private fun ticketCount(orderId: String) = db.dao().ticketCount(orderId)

    @Test fun catalogueReserveAuResponsableEtChangementDePrixJournalise() {
        val salade = p.getValue("salade")
        try { repo.catalog.saveProduct("marie", salade.copy(price = Cents(1)) ); fail("une serveuse ne modifie pas le catalogue") }
        catch (e: fr.foodsafe.pos.app.data.ActionDenied) { /* attendu */ }

        // Une note ouverte avant le changement garde l'ancien prix sur sa ligne.
        val o = repo.openOrder("marie", "12").order.id
        repo.addProduct(o, "marie", salade, null, "a1")
        repo.catalog.saveProduct("paul", salade.copy(price = Cents(1050)))
        val changed = runBlocking { repo.catalog.state.first() }.products.first { it.id == "salade" }
        assertEquals(Cents(1050), changed.price)
        assertEquals(Cents(950), repo.snapshot(o)!!.order.lines.single().unitPriceTtc)

        val events = db.dao().allJournal()
        val upd = events.last { it.type == "PRODUCT_UPDATED" }
        assertTrue(upd.before_json!!.contains("\"priceCents\":950"))
        assertTrue(upd.after_json!!.contains("\"priceCents\":1050"))
        assertTrue(repo.verifyJournal() is ChainVerification.Valid)
    }

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
        try { repo.cancelLine(o, "marie", lineId, "Client parti", "d"); fail("serveur sans validation") } catch (_: ApprovalRequired) {}
        repo.cancelLine(o, "marie", lineId, "Client parti", "d", approverId = "paul")
        val t = runBlocking { repo.tickets.first() }.first()
        assertEquals("CANCELLATION", t.kind)
        assertTrue(t.inverse)
        assertTrue(!t.renderedText.contains("Client parti"))
        assertEquals("Client parti · validé par Paul", db.dao().allJournal().last().reason)
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

    @Test fun commentairesEtSupplementsObligatoireEtAnnulation() {
        val cat = runBlocking { repo.catalog.state.first() }
        val cuisson = cat.groups.first { it.name == "Cuisson" }
        val diluants = cat.groups.first { it.name == "Diluants" }
        // la Cuisson devient obligatoire pour la Burrata (exemple), un diluant payant sur le vin (TVA suit le produit : 20 %)
        repo.catalog.setProductGroups("paul", "burrata", listOf(cuisson.id))
        val o = repo.openOrder("marie", "21").order.id
        repo.addProduct(o, "marie", p.getValue("burrata"), null, "m1")
        try { repo.sendToKitchen(o, "marie", "m2"); fail("envoi sans cuisson accepté") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Cuisson (Burrata)")) }

        val burrata = repo.snapshot(o)!!.order.lines.single()
        repo.addModifiers(o, "marie", burrata.id, listOf(cuisson.items.first { it.label == "Saignant" }), "m3")
        repo.addProduct(o, "marie", p.getValue("vin"), null, "m4", listOf(diluants.items.first { it.label == "Coca" }))
        repo.sendToKitchen(o, "marie", "m5")
        val s = repo.snapshot(o)!!
        assertEquals(listOf("Burrata", "Saignant", "Verre de vin", "Coca"), s.order.lines.map { it.label })
        assertEquals(Cents(1100 + 550 + 100), s.priced.totalTtc)
        assertEquals(BasisPoints(2000), s.order.lines.last().vatRate)
        val cuisine = runBlocking { repo.tickets.first() }.first { it.destination == "CUISINE" }
        assertTrue(cuisine.renderedText, cuisine.renderedText.contains("> Saignant"))

        // annulation du vin : le Coca part avec
        val vin = s.order.lines.first { it.label == "Verre de vin" }
        repo.cancelLine(o, "marie", vin.id, "erreur", "m6", approverId = "paul")
        assertEquals(Cents(1100), repo.snapshot(o)!!.priced.totalTtc)
        repo.pay(o, "marie", PaymentMethod.CARD, Cents(1100), "m7")
        assertTrue(repo.verifyJournal() is ChainVerification.Valid)
    }

    @Test fun groupesReservesAuResponsableEtJournalises() {
        try { repo.catalog.saveGroup("marie", null, "Sauces", false, true); fail() } catch (_: fr.foodsafe.pos.app.data.ActionDenied) {}
        val g = repo.catalog.saveGroup("paul", null, "Sauces", mandatory = false, multiple = true)
        repo.catalog.saveItem("paul", fr.foodsafe.pos.app.data.ModifierItem("", g, "Béarnaise", Cents(150), null))
        repo.catalog.deleteGroup("paul", g)
        val st = runBlocking { repo.catalog.state.first() }
        assertTrue(st.groups.none { it.id == g })
        val types = db.dao().allJournal().map { it.type }
        assertTrue(types.containsAll(listOf("MODIFIER_GROUP_CREATED", "MODIFIER_CREATED", "MODIFIER_GROUP_DELETED")))
    }
}

package fr.foodsafe.pos.core

import fr.foodsafe.pos.core.kitchen.KitchenDispatcher
import fr.foodsafe.pos.core.kitchen.KitchenRuleViolation
import fr.foodsafe.pos.core.kitchen.KitchenTicketKind
import fr.foodsafe.pos.core.kitchen.KitchenTicketRenderer
import fr.foodsafe.pos.core.order.OrderStatus
import fr.foodsafe.pos.core.order.ServiceLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KitchenDispatcherTest {
    private val ids = SeqIds()

    @Test fun `envoi puis second envoi - aucune ligne renvoyee`() {
        val o = order(line("l1", "Salade", 900, level = 1), line("l2", "Pizza", 1200, level = 2))
        val first = KitchenDispatcher.send(o, ids)
        assertEquals(1, first.tickets.size)
        assertEquals(listOf("Salade", "Pizza"), first.tickets.single().items.map { it.label })
        assertTrue(first.order.lines.all { it.isSent })

        val second = KitchenDispatcher.send(first.order, ids)
        assertTrue(second.isEmpty)
        assertEquals(first.order, second.order)
    }

    @Test fun `ajout sur commande existante - seules les nouvelles lignes partent`() {
        val sent = KitchenDispatcher.send(order(line("l1", "Salade", 900, level = 1)), ids).order
        val r = KitchenDispatcher.send(sent.add(line("l2", "Coca", 350, destination = "BAR")), ids)
        assertEquals(1, r.tickets.size)
        assertEquals("BAR", r.tickets.single().destination)
        assertEquals(listOf("Coca"), r.tickets.single().items.map { it.label })
    }

    @Test fun `routage par destination - un bon par destination`() {
        val r = KitchenDispatcher.send(
            order(line("l1", "Pizza", 1200), line("l2", "Bière", 600, destination = "BAR")), ids,
        )
        assertEquals(setOf("BAR", "CUISINE"), r.tickets.map { it.destination }.toSet())
    }

    @Test fun `reclamation sequentielle et bouton dynamique`() {
        var o = order(
            line("l1", "Salade", 900, level = 1),
            line("l2", "Pizza", 1200, level = 2),
            line("l3", "Tiramisu", 650, level = 3),
        )
        assertEquals(ServiceLevel(1), KitchenDispatcher.nextClaimableLevel(o))
        assertThrows<KitchenRuleViolation> { KitchenDispatcher.claim(o, ServiceLevel(2), ids) }

        val c1 = KitchenDispatcher.claim(o, ServiceLevel(1), ids)
        // les lignes non envoyées sont d'abord transmises, puis le bon de réclamation
        assertEquals(listOf(KitchenTicketKind.ORDER, KitchenTicketKind.CLAIM), c1.tickets.map { it.kind })
        assertEquals(listOf("Salade"), c1.tickets.last().items.map { it.label })
        assertEquals(OrderStatus.ON_HOLD, c1.order.status)
        o = c1.order
        assertEquals(ServiceLevel(2), KitchenDispatcher.nextClaimableLevel(o))

        val c2 = KitchenDispatcher.claim(o, ServiceLevel(2), ids)
        assertEquals(listOf(KitchenTicketKind.CLAIM), c2.tickets.map { it.kind }) // rien à renvoyer
        o = KitchenDispatcher.claim(c2.order, ServiceLevel(3), ids).order
        assertNull(KitchenDispatcher.nextClaimableLevel(o))
    }

    @Test fun `ajout a un niveau deja reclame - ticket AJOUT N2 avec uniquement les nouveaux articles`() {
        var o = order(line("l1", "Salade", 900, level = 1), line("l2", "Pizza", 1200, level = 2))
        o = KitchenDispatcher.claim(o, ServiceLevel(1), ids).order
        o = KitchenDispatcher.claim(o, ServiceLevel(2), ids).order
        val r = KitchenDispatcher.send(o.add(line("l3", "Calzone", 1300, level = 2), line("l4", "Pain", 0 + 100)), ids)

        val addition = r.tickets.single { it.kind == KitchenTicketKind.ADDITION }
        assertEquals(ServiceLevel(2), addition.level)
        assertEquals(listOf("Calzone"), addition.items.map { it.label })
        val direct = r.tickets.single { it.kind == KitchenTicketKind.ORDER }
        assertEquals(listOf("Pain"), direct.items.map { it.label })
        assertEquals("AJOUT N2", KitchenTicketRenderer.render(addition)[0].trim())
    }

    @Test fun `annulation sans motif refusee`() {
        val o = KitchenDispatcher.send(order(line("l1", "Pizza", 1200)), ids).order
        assertThrows<IllegalArgumentException> { KitchenDispatcher.cancelLine(o, "l1", 1000, "  ", "marie", 1, ids) }
    }

    @Test fun `annulation d une ligne envoyee - bon inverse sans motif imprime`() {
        val o = KitchenDispatcher.send(order(line("l1", "Pizza", 1200, qty = 3)), ids).order
        val r = KitchenDispatcher.cancelLine(o, "l1", 1000, "Client parti", "chef", 5, ids)
        val t = r.tickets.single()
        assertEquals(KitchenTicketKind.CANCELLATION, t.kind)
        assertTrue(t.inverse)
        assertEquals(1000, t.items.single().quantityMilli)
        val rendered = KitchenTicketRenderer.render(t).joinToString("\n")
        assertTrue("Client parti" !in rendered)
        // annulation partielle : 2 restent actives, 1 tracée annulée avec motif
        assertEquals(2000, r.order.activeLines.single().quantityMilli)
        assertEquals("Client parti", r.order.lines.single { !it.isActive }.cancellation!!.reason)
        // l'annulation ne déclenche aucun renvoi
        assertTrue(KitchenDispatcher.send(r.order, ids).isEmpty)
    }

    @Test fun `annulation d une ligne non envoyee - aucun bon`() {
        val r = KitchenDispatcher.cancelLine(order(line("l1", "Pizza", 1200)), "l1", 1000, "erreur saisie", "marie", 1, ids)
        assertTrue(r.isEmpty)
        assertTrue(r.order.activeLines.isEmpty())
    }

    @Test fun `changement de table 43 vers 56 - notification seulement aux destinations concernees`() {
        var o = order(line("l1", "Pizza", 1200), line("l2", "Bière", 600, destination = "BAR"), table = "43")
        o = KitchenDispatcher.send(o.copy(lines = o.lines.take(1)), ids).order.add(o.lines[1]) // bière pas encore envoyée
        val r = KitchenDispatcher.moveTable(o, "56", ids)
        assertEquals("56", r.order.tableNumber)
        val t = r.tickets.single()
        assertEquals("CUISINE", t.destination)
        assertTrue(t.items.isEmpty()) // aucune préparation réimprimée
        assertEquals("TABLE 43 → 56", t.note)
    }

    @Test fun `idempotence - meme cle meme resultat`() {
        val reg = IdempotencyRegistry<fr.foodsafe.pos.core.kitchen.DispatchResult>()
        val o = order(line("l1", "Pizza", 1200))
        val a = reg.execute("tap-1") { KitchenDispatcher.send(o, ids) }
        val b = reg.execute("tap-1") { KitchenDispatcher.send(o, ids) }
        assertTrue(a === b)
    }

    @Test fun `rendu 58mm du bon avec niveaux et a suivre`() {
        val r = KitchenDispatcher.send(order(line("l1", "Salade", 900, level = 1), line("l2", "Pizza", 1200, level = 2)), ids)
        val text = KitchenTicketRenderer.render(r.tickets.single(), columns = 32, backupFor = "Cuisine")
        assertEquals("DESTINATION : CUISINE", text[0].trim())
        assertTrue(text.any { it.contains("À suivre") })
        assertTrue(text.all { it.length <= 32 })
    }
}

package fr.foodsafe.pos.core

import fr.foodsafe.pos.core.journal.ChainVerification
import fr.foodsafe.pos.core.journal.EcdsaSigner
import fr.foodsafe.pos.core.journal.InMemoryJournalStore
import fr.foodsafe.pos.core.journal.JournalDraft
import fr.foodsafe.pos.core.journal.JournalEvent
import fr.foodsafe.pos.core.journal.JournalHasher
import fr.foodsafe.pos.core.journal.JournalSchema
import fr.foodsafe.pos.core.journal.JournalStore
import fr.foodsafe.pos.core.journal.JournalVerifier
import fr.foodsafe.pos.core.journal.SignatureVerifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

fun draft(i: Int, reason: String? = null) = JournalDraft(
    id = "ev-$i", establishmentId = "etab-1", terminalId = "T01", actorId = "marie", occurredAt = 1_000L + i,
    type = "ORDER_LINE_ADDED", entityType = "order", entityId = "o1",
    beforeJson = null, afterJson = """{"line":"l$i","label":"Pizza"}""", reason = reason,
)

class JournalChainTest {
    @Test fun `chaine valide puis alteration detectee`() {
        val store = InMemoryJournalStore()
        repeat(50) { store.append(draft(it)) }
        val events = store.all()
        assertEquals(ChainVerification.Valid(50, events.last().hash), JournalVerifier.verify("T01", events))

        val tampered = events.toMutableList().also {
            it[20] = it[20].copy(draft = it[20].draft.copy(afterJson = """{"line":"l20","label":"Pizzb"}"""))
        }
        val r = JournalVerifier.verify("T01", tampered)
        assertInstanceOf(ChainVerification.Broken::class.java, r)
        assertEquals(21L, (r as ChainVerification.Broken).seq)
    }

    @Test fun `suppression d un maillon detectee`() {
        val store = InMemoryJournalStore()
        repeat(5) { store.append(draft(it)) }
        val r = JournalVerifier.verify("T01", store.all().filterIndexed { i, _ -> i != 2 })
        assertEquals(4L, (r as ChainVerification.Broken).seq)
    }

    @Test fun `encodage canonique non ambigu`() {
        val a = draft(1).copy(beforeJson = "a|b", afterJson = "c")
        val b = draft(1).copy(beforeJson = "a", afterJson = "b|c")
        assertTrue(JournalHasher.hash("x", 1, a) != JournalHasher.hash("x", 1, b))
        val n = draft(1).copy(reason = null)
        val e = draft(1).copy(reason = "")
        assertTrue(JournalHasher.hash("x", 1, n) != JournalHasher.hash("x", 1, e))
    }

    @Test fun `signature ECDSA verifiable et alteration rejetee`() {
        val kp = EcdsaSigner.generateKeyPair()
        val signer = EcdsaSigner("T01-k1", kp.private)
        val data = "T01-000001|16,20|hash".toByteArray()
        val sig = signer.sign(data)
        assertTrue(SignatureVerifier.verify(kp.public, data, sig))
        assertFalse(SignatureVerifier.verify(kp.public, "T01-000001|16,21|hash".toByteArray(), sig))
    }
}

/** Journal sur SQLite réel (même DDL et mêmes triggers que l'app Android). */
class JdbcJournalStore(private val c: Connection) : JournalStore {
    override fun last(): JournalEvent? = c.prepareStatement(
        "SELECT * FROM journal_events ORDER BY seq DESC LIMIT 1",
    ).executeQuery().use { rs -> if (rs.next()) read(rs) else null }

    override fun all(): List<JournalEvent> = c.prepareStatement("SELECT * FROM journal_events ORDER BY seq")
        .executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }

    override fun insert(event: JournalEvent) {
        c.prepareStatement("INSERT INTO journal_events VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { st ->
            val d = event.draft
            listOf(event.seq, d.id, d.establishmentId, d.terminalId, d.actorId, d.occurredAt, d.type, d.entityType,
                d.entityId, d.beforeJson, d.afterJson, d.reason, event.prevHash, event.hash)
                .forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeUpdate()
        }
    }

    private fun read(rs: java.sql.ResultSet) = JournalEvent(
        seq = rs.getLong("seq"),
        draft = JournalDraft(rs.getString("id"), rs.getString("establishment_id"), rs.getString("terminal_id"),
            rs.getString("actor_id"), rs.getLong("occurred_at"), rs.getString("type"), rs.getString("entity_type"),
            rs.getString("entity_id"), rs.getString("before_json"), rs.getString("after_json"), rs.getString("reason")),
        prevHash = rs.getString("prev_hash"), hash = rs.getString("hash"),
    )
}

class JournalSqliteTest {
    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { c ->
        c.createStatement().use { st ->
            st.execute(JournalSchema.CREATE_TABLE)
            JournalSchema.APPEND_ONLY_TRIGGERS.forEach(st::execute)
        }
    }

    @Test fun `insertion et verification sur SQLite`() {
        db().use { c ->
            val store = JdbcJournalStore(c)
            repeat(10) { store.append(draft(it, reason = if (it == 3) "Client parti" else null)) }
            assertEquals(ChainVerification.Valid(10, store.last()!!.hash), JournalVerifier.verify("T01", store.all()))
        }
    }

    @Test fun `UPDATE et DELETE refuses par les triggers`() {
        db().use { c ->
            val store = JdbcJournalStore(c)
            repeat(3) { store.append(draft(it)) }
            val upd = assertThrows<SQLException> { c.createStatement().execute("UPDATE journal_events SET reason='x' WHERE seq=2") }
            assertTrue(upd.message!!.contains("ajout seul"))
            assertThrows<SQLException> { c.createStatement().execute("DELETE FROM journal_events WHERE seq=3") }
            assertEquals(3, store.all().size)
        }
    }

    @Test fun `insertion hors chaine refusee par trigger`() {
        db().use { c ->
            val store = JdbcJournalStore(c)
            repeat(2) { store.append(draft(it)) }
            val forged = JournalHasher.next(store.last(), draft(9)).copy(prevHash = "0".repeat(64))
            assertThrows<SQLException> { store.insert(forged) }
            val gap = JournalHasher.next(store.last(), draft(9)).let { it.copy(seq = it.seq + 5) }
            assertThrows<SQLException> { store.insert(gap) }
        }
    }
}

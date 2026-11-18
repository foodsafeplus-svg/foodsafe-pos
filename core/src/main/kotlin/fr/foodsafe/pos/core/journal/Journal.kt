package fr.foodsafe.pos.core.journal

import java.security.MessageDigest

/** Événement à journaliser (avant chaînage). */
data class JournalDraft(
    val id: String,
    val establishmentId: String,
    val terminalId: String,
    val actorId: String,
    val occurredAt: Long,
    val type: String,
    val entityType: String,
    val entityId: String,
    val beforeJson: String? = null,
    val afterJson: String? = null,
    val reason: String? = null,
)

/** Maillon du journal append-only : immuable, chaîné au précédent par SHA-256. */
data class JournalEvent(
    val seq: Long,
    val draft: JournalDraft,
    val prevHash: String,
    val hash: String,
)

/**
 * Sérialisation canonique non ambiguë : chaque champ est préfixé par sa longueur en octets UTF-8
 * (les null sont codés "-1:"). Deux contenus différents ne peuvent pas produire la même chaîne.
 */
object CanonicalEncoder {
    fun encode(seq: Long, d: JournalDraft): String = buildString {
        fun field(v: String?) {
            if (v == null) append("-1:") else append(v.toByteArray(Charsets.UTF_8).size).append(':').append(v)
            append('|')
        }
        field(seq.toString()); field(d.id); field(d.establishmentId); field(d.terminalId); field(d.actorId)
        field(d.occurredAt.toString()); field(d.type); field(d.entityType); field(d.entityId)
        field(d.beforeJson); field(d.afterJson); field(d.reason)
    }
}

object JournalHasher {
    fun genesis(terminalId: String) = sha256Hex("GENESIS|$terminalId")

    fun hash(prevHash: String, seq: Long, d: JournalDraft): String =
        sha256Hex(prevHash + "|" + CanonicalEncoder.encode(seq, d))

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Calcule le maillon suivant à partir du dernier maillon connu (null = début de chaîne). */
    fun next(last: JournalEvent?, draft: JournalDraft): JournalEvent {
        val seq = (last?.seq ?: 0) + 1
        val prev = last?.hash ?: genesis(draft.terminalId)
        return JournalEvent(seq, draft, prev, hash(prev, seq, draft))
    }
}

sealed interface ChainVerification {
    data class Valid(val count: Int, val lastHash: String?) : ChainVerification
    data class Broken(val seq: Long, val problem: String) : ChainVerification
}

object JournalVerifier {
    /** Recalcule toute la chaîne et renvoie le premier maillon incohérent (trou de séquence, hash altéré…). */
    fun verify(terminalId: String, events: List<JournalEvent>): ChainVerification {
        var prev = JournalHasher.genesis(terminalId)
        var expectedSeq = 1L
        for (e in events) {
            if (e.seq != expectedSeq) return ChainVerification.Broken(e.seq, "séquence attendue $expectedSeq")
            if (e.prevHash != prev) return ChainVerification.Broken(e.seq, "chaînage rompu (prev_hash)")
            if (JournalHasher.hash(prev, e.seq, e.draft) != e.hash) return ChainVerification.Broken(e.seq, "contenu altéré")
            prev = e.hash
            expectedSeq++
        }
        return ChainVerification.Valid(events.size, events.lastOrNull()?.hash)
    }
}

/** Stockage append-only : pas de méthode update ni delete. */
interface JournalStore {
    fun last(): JournalEvent?
    fun insert(event: JournalEvent)
    fun all(): List<JournalEvent>

    /** À appeler dans la transaction de l'opération métier. */
    fun append(draft: JournalDraft): JournalEvent = JournalHasher.next(last(), draft).also(::insert)
}

class InMemoryJournalStore : JournalStore {
    private val events = mutableListOf<JournalEvent>()
    @Synchronized override fun last() = events.lastOrNull()
    @Synchronized override fun insert(event: JournalEvent) {
        require(event.seq == (events.lastOrNull()?.seq ?: 0) + 1) { "insertion hors séquence" }
        events += event
    }
    @Synchronized override fun all() = events.toList()
}

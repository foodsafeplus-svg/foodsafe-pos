package fr.foodsafe.pos.core.journal

/**
 * DDL SQLite du journal. La table est créée par Room côté Android (entité JournalEventEntity, même structure) ;
 * les triggers ci-dessous sont installés à l'ouverture de la base et rendent toute modification impossible
 * au niveau SQL, quel que soit le code appelant.
 */
object JournalSchema {
    const val TABLE = "journal_events"

    /** Structure identique à l'entité Room ; utilisée par les tests JVM et la documentation. */
    val CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS $TABLE (
            seq INTEGER NOT NULL PRIMARY KEY,
            id TEXT NOT NULL,
            establishment_id TEXT NOT NULL,
            terminal_id TEXT NOT NULL,
            actor_id TEXT NOT NULL,
            occurred_at INTEGER NOT NULL,
            type TEXT NOT NULL,
            entity_type TEXT NOT NULL,
            entity_id TEXT NOT NULL,
            before_json TEXT,
            after_json TEXT,
            reason TEXT,
            prev_hash TEXT NOT NULL,
            hash TEXT NOT NULL
        )
    """.trimIndent()

    val APPEND_ONLY_TRIGGERS = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS journal_no_update BEFORE UPDATE ON $TABLE
        BEGIN SELECT RAISE(ABORT, 'journal_events est en ajout seul'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS journal_no_delete BEFORE DELETE ON $TABLE
        BEGIN SELECT RAISE(ABORT, 'journal_events est en ajout seul'); END
        """.trimIndent(),
        // Empêche une insertion qui ne prolonge pas la chaîne (seq + 1 et prev_hash = hash précédent).
        """
        CREATE TRIGGER IF NOT EXISTS journal_chain_insert BEFORE INSERT ON $TABLE
        WHEN (SELECT COUNT(*) FROM $TABLE) > 0 AND (
            NEW.seq <> (SELECT MAX(seq) FROM $TABLE) + 1 OR
            NEW.prev_hash <> (SELECT hash FROM $TABLE ORDER BY seq DESC LIMIT 1)
        )
        BEGIN SELECT RAISE(ABORT, 'insertion hors chaîne'); END
        """.trimIndent(),
    )
}

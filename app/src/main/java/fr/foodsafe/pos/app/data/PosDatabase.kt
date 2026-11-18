package fr.foodsafe.pos.app.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.foodsafe.pos.core.journal.JournalSchema

@Database(
    entities = [
        OrderEntity::class, OrderLineEntity::class, KitchenTicketEntity::class,
        ProcessedOperationEntity::class, PaymentEntity::class, JournalEventEntity::class,
        EmployeeEntity::class, FiscalDocumentEntity::class, FiscalKeyEntity::class,
        ProductEntity::class, KeyboardEntity::class, KeyboardPageEntity::class, KeyboardCellEntity::class, ImageEntity::class,
        ModifierGroupEntity::class, ModifierItemEntity::class, ProductModifierGroupEntity::class,
    ],
    version = 4,
    exportSchema = true,
    // v1 (incrément 1) → v2 : ajout des tables employés, documents fiscaux et clés. Aucune donnée existante modifiée.
    // v2 → v3 : catalogue éditable (produits, claviers, pages, cases, images). Nouvelles tables uniquement.
    // v3 → v4 : commentaires et suppléments (nouvelles tables) + colonne nullable order_lines.parentLineId.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class PosDatabase : RoomDatabase() {
    abstract fun dao(): PosDao
    abstract fun catalogDao(): CatalogDao

    companion object {
        /** Triggers d'inaltérabilité, réinstallés (IF NOT EXISTS) à chaque ouverture. */
        val APPEND_ONLY_CALLBACK = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                JournalSchema.APPEND_ONLY_TRIGGERS.forEach(db::execSQL)
                for (table in listOf("payments", "fiscal_documents", "fiscal_keys")) {
                    listOf("UPDATE", "DELETE").forEach { op ->
                        db.execSQL(
                            "CREATE TRIGGER IF NOT EXISTS ${table}_no_${op.lowercase()} BEFORE $op ON $table " +
                                "BEGIN SELECT RAISE(ABORT, '$table est en ajout seul'); END",
                        )
                    }
                }
                // Numérotation continue et chaînage par (terminal, type) garantis par la base elle-même.
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS fiscal_chain_insert BEFORE INSERT ON fiscal_documents
                    WHEN NEW.number <> COALESCE((SELECT MAX(number) FROM fiscal_documents
                            WHERE terminalId = NEW.terminalId AND type = NEW.type), 0) + 1
                      OR (NEW.number > 1 AND NEW.prevHash <> (SELECT hash FROM fiscal_documents
                            WHERE terminalId = NEW.terminalId AND type = NEW.type ORDER BY number DESC LIMIT 1))
                    BEGIN SELECT RAISE(ABORT, 'document fiscal hors séquence ou hors chaîne'); END
                    """.trimIndent(),
                )
            }
        }

        fun build(context: Context): PosDatabase =
            Room.databaseBuilder(context, PosDatabase::class.java, "foodsafe-pos.db")
                .addCallback(APPEND_ONLY_CALLBACK)
                .build()
    }
}

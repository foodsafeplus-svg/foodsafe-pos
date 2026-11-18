package fr.foodsafe.pos.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.foodsafe.pos.core.journal.JournalSchema

@Database(
    entities = [
        OrderEntity::class, OrderLineEntity::class, KitchenTicketEntity::class,
        ProcessedOperationEntity::class, PaymentEntity::class, JournalEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PosDatabase : RoomDatabase() {
    abstract fun dao(): PosDao

    companion object {
        /** Triggers d'inaltérabilité, réinstallés (IF NOT EXISTS) à chaque ouverture. */
        val APPEND_ONLY_CALLBACK = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                JournalSchema.APPEND_ONLY_TRIGGERS.forEach(db::execSQL)
                listOf("UPDATE", "DELETE").forEach { op ->
                    db.execSQL(
                        "CREATE TRIGGER IF NOT EXISTS payments_no_${op.lowercase()} BEFORE $op ON payments " +
                            "BEGIN SELECT RAISE(ABORT, 'payments est en ajout seul'); END",
                    )
                }
            }
        }

        fun build(context: Context): PosDatabase =
            Room.databaseBuilder(context, PosDatabase::class.java, "foodsafe-pos.db")
                .addCallback(APPEND_ONLY_CALLBACK)
                .build()
    }
}

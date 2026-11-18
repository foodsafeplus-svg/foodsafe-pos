package fr.foodsafe.pos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PosDao {
    @Query("SELECT * FROM orders WHERE id = :id") fun order(id: String): OrderEntity?
    @Query("SELECT * FROM order_lines WHERE orderId = :orderId ORDER BY position") fun lines(orderId: String): List<OrderLineEntity>
    @Upsert fun upsertOrder(order: OrderEntity)
    @Upsert fun upsertLines(lines: List<OrderLineEntity>)

    @Query("SELECT * FROM orders WHERE status IN ('OPEN','ON_HOLD') ORDER BY openedAt DESC LIMIT 1")
    fun lastOpenOrder(): OrderEntity?
    @Query("SELECT COUNT(*) FROM orders") fun orderCount(): Int

    @Insert fun insertTickets(tickets: List<KitchenTicketEntity>)
    @Query("SELECT * FROM kitchen_tickets ORDER BY rowId DESC LIMIT 100") fun observeTickets(): Flow<List<KitchenTicketEntity>>
    @Query("SELECT COUNT(*) FROM kitchen_tickets WHERE orderId = :orderId") fun ticketCount(orderId: String): Int

    /** Renvoie -1 si la clé existe déjà (opération déjà traitée). */
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertOperation(op: ProcessedOperationEntity): Long

    @Insert fun insertPayment(p: PaymentEntity)
    @Query("SELECT * FROM payments WHERE orderId = :orderId ORDER BY at") fun payments(orderId: String): List<PaymentEntity>

    @Insert fun insertJournal(e: JournalEventEntity)
    @Query("SELECT * FROM journal_events ORDER BY seq DESC LIMIT 1") fun lastJournal(): JournalEventEntity?
    @Query("SELECT * FROM journal_events ORDER BY seq") fun allJournal(): List<JournalEventEntity>
    @Query("SELECT * FROM journal_events ORDER BY seq DESC LIMIT 200") fun observeJournal(): Flow<List<JournalEventEntity>>
}

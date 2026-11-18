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

    @Query("SELECT * FROM orders WHERE status IN ('OPEN','ON_HOLD') ORDER BY tableNumber")
    fun observeOpenOrders(): Flow<List<OrderEntity>>
    @Query("SELECT * FROM order_lines WHERE orderId IN (SELECT id FROM orders WHERE status IN ('OPEN','ON_HOLD'))")
    fun observeOpenLines(): Flow<List<OrderLineEntity>>

    @Query("SELECT tableNumber FROM orders WHERE status IN ('OPEN','ON_HOLD') AND tableNumber IS NOT NULL")
    fun allOpenOrderTables(): List<String>

    @Query("SELECT * FROM employees WHERE active = 1") fun employees(): List<EmployeeEntity>
    @Query("SELECT * FROM employees WHERE id = :id") fun employee(id: String): EmployeeEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertEmployees(e: List<EmployeeEntity>)

    @Insert fun insertFiscal(d: FiscalDocumentEntity)
    @Query("SELECT * FROM fiscal_documents WHERE terminalId = :terminalId AND type = :type ORDER BY number DESC LIMIT 1")
    fun lastFiscal(terminalId: String, type: String): FiscalDocumentEntity?
    @Query("SELECT * FROM fiscal_documents WHERE terminalId = :terminalId AND type = :type ORDER BY number")
    fun fiscalOfType(terminalId: String, type: String): List<FiscalDocumentEntity>
    @Query("SELECT * FROM fiscal_documents WHERE id = :id") fun fiscal(id: String): FiscalDocumentEntity?
    @Query("SELECT * FROM fiscal_documents ORDER BY issuedAt DESC, number DESC LIMIT 200")
    fun observeFiscal(): Flow<List<FiscalDocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertKey(k: FiscalKeyEntity)
    @Query("SELECT * FROM fiscal_keys") fun keys(): List<FiscalKeyEntity>
}

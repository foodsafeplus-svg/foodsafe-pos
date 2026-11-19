package fr.foodsafe.pos.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val id: String,
    val saleMode: String,
    val tableNumber: String?,
    val ownerEmployeeId: String,
    val status: String,
    val version: Long,
    /** Niveaux réclamés, séparés par des virgules ("1,2"). */
    val claimedLevels: String,
    val ticketDiscountBp: Int?,
    val openedAt: Long,
)

@Entity(tableName = "order_lines", indices = [Index("orderId")])
data class OrderLineEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val position: Int,
    val productId: String,
    val label: String,
    val unitPriceTtc: Long,
    val quantityMilli: Long,
    val vatRateBp: Int,
    val serviceLevel: Int,
    val destination: String,
    val authorEmployeeId: String,
    val createdAt: Long,
    val lineDiscountKind: String?,
    val lineDiscountValue: Long?,
    val dispatchId: String?,
    val cancelReason: String?,
    val cancelledBy: String?,
    val cancelledAt: Long?,
    /** v4 : commentaire ou supplément rattaché à cette ligne parente. */
    val parentLineId: String? = null,
    /** v5 : rôle (PRODUCT, MODIFIER, FORMULA, COMPONENT) ; null = déduit (lignes v4). */
    val role: String? = null,
    /** v5 : promotion automatique appliquée (ou « REFUSED »). */
    val promoGroup: String? = null,
)

/** Bon cuisine produit (aperçu ; deviendra un print_job dans la couche matérielle). */
@Entity(tableName = "kitchen_tickets", indices = [Index("orderId")])
data class KitchenTicketEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val dispatchId: String,
    val orderId: String,
    val destination: String,
    val kind: String,
    val inverse: Boolean,
    val renderedText: String,
    val createdAt: Long,
)

/** Clés d'idempotence déjà traitées : un second tap / un renvoi ne rejoue pas l'opération. */
@Entity(tableName = "processed_operations")
data class ProcessedOperationEntity(
    @PrimaryKey val idempotencyKey: String,
    val type: String,
    val at: Long,
)

/** Événement de paiement (append-only, triggers SQL). */
@Entity(tableName = "payments", indices = [Index("orderId")])
data class PaymentEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val method: String,
    val amount: Long,
    val tendered: Long,
    val cashierEmployeeId: String,
    val terminalId: String,
    val at: Long,
)

/** Même structure que fr.foodsafe.pos.core.journal.JournalSchema.CREATE_TABLE. */
@Entity(tableName = "journal_events")
data class JournalEventEntity(
    @PrimaryKey val seq: Long,
    val id: String,
    val establishment_id: String,
    val terminal_id: String,
    val actor_id: String,
    val occurred_at: Long,
    val type: String,
    val entity_type: String,
    val entity_id: String,
    val before_json: String?,
    val after_json: String?,
    val reason: String?,
    val prev_hash: String,
    val hash: String,
)

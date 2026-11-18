package fr.foodsafe.pos.app.data

import fr.foodsafe.pos.core.discount.TicketDiscount
import fr.foodsafe.pos.core.journal.ChainVerification
import fr.foodsafe.pos.core.journal.JournalDraft
import fr.foodsafe.pos.core.journal.JournalEvent
import fr.foodsafe.pos.core.journal.JournalStore
import fr.foodsafe.pos.core.journal.JournalVerifier
import fr.foodsafe.pos.core.kitchen.DispatchResult
import fr.foodsafe.pos.core.kitchen.IdGenerator
import fr.foodsafe.pos.core.kitchen.KitchenDispatcher
import fr.foodsafe.pos.core.kitchen.KitchenTicketRenderer
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.Order
import fr.foodsafe.pos.core.order.OrderLine
import fr.foodsafe.pos.core.order.OrderPricer
import fr.foodsafe.pos.core.order.OrderStatus
import fr.foodsafe.pos.core.order.SaleMode
import fr.foodsafe.pos.core.order.ServiceLevel
import fr.foodsafe.pos.core.payment.Payment
import fr.foodsafe.pos.core.payment.PaymentMethod
import fr.foodsafe.pos.core.payment.PaymentRules
import org.json.JSONObject
import java.util.UUID

data class OrderSnapshot(val order: Order, val payments: List<Payment>) {
    val priced get() = OrderPricer.price(order)
    val remaining: Cents get() = PaymentRules.remaining(priced.totalTtc, payments)
}

/**
 * Point d'entrée unique des écritures. Chaque opération s'exécute dans UNE transaction SQLite :
 * contrôle d'idempotence → état commande → bons cuisine → paiement → maillon du journal.
 * Si une étape échoue, rien n'est écrit.
 */
class PosRepository(
    private val db: PosDatabase,
    private val establishmentId: String,
    private val terminalId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() },
) {
    private val dao = db.dao()

    private val journal = object : JournalStore {
        override fun last() = dao.lastJournal()?.toDomain()
        override fun insert(event: JournalEvent) = dao.insertJournal(event.toEntity())
        override fun all() = dao.allJournal().map { it.toDomain() }
    }

    val tickets get() = dao.observeTickets()
    val journalFlow get() = dao.observeJournal()

    fun snapshot(orderId: String): OrderSnapshot? = dao.order(orderId)?.let { o ->
        OrderSnapshot(o.toDomain(dao.lines(orderId)), dao.payments(orderId).map { it.toDomain() })
    }

    fun currentOrNew(ownerId: String): OrderSnapshot {
        dao.lastOpenOrder()?.let { return snapshot(it.id)!! }
        return openOrder(ownerId, tableNumber = (40 + dao.orderCount() + 3).toString())
    }

    fun openOrder(ownerId: String, tableNumber: String, mode: SaleMode = SaleMode.DINE_IN): OrderSnapshot =
        db.runInTransaction<OrderSnapshot> {
            val order = Order(ids.next(), mode, tableNumber, ownerId)
            dao.upsertOrder(order.toEntity(clock()))
            log(ownerId, "ORDER_OPENED", order.id, null, order.summary())
            OrderSnapshot(order, emptyList())
        }

    fun addProduct(orderId: String, actorId: String, product: Product, level: ServiceLevel?, key: String) =
        mutate(orderId, actorId, key, "ORDER_LINE_ADDED") { order ->
            val line = OrderLine(
                id = ids.next(), productId = product.id, label = product.label, unitPriceTtc = product.price,
                quantityMilli = 1000, vatRate = product.vat, level = level ?: product.defaultLevel,
                destination = product.destination, authorEmployeeId = actorId, createdAt = clock(),
            )
            DispatchResult(order.copy(lines = order.lines + line).bumped(), emptyList())
        }

    fun sendToKitchen(orderId: String, actorId: String, key: String) =
        mutate(orderId, actorId, key, "KITCHEN_SENT") { KitchenDispatcher.send(it, ids) }

    fun claimNext(orderId: String, actorId: String, key: String) =
        mutate(orderId, actorId, key, "LEVEL_CLAIMED") { order ->
            val level = KitchenDispatcher.nextClaimableLevel(order) ?: error("rien à réclamer")
            KitchenDispatcher.claim(order, level, ids)
        }

    fun cancelLine(orderId: String, actorId: String, lineId: String, reason: String, key: String) =
        mutate(orderId, actorId, key, "LINE_CANCELLED", reason) { order ->
            val line = order.lines.first { it.id == lineId }
            KitchenDispatcher.cancelLine(order, lineId, line.quantityMilli, reason, actorId, clock(), ids)
        }

    fun setTicketDiscount(orderId: String, actorId: String, discount: TicketDiscount?, key: String) =
        mutate(orderId, actorId, key, "TICKET_DISCOUNT_SET") { DispatchResult(it.copy(ticketDiscount = discount).bumped(), emptyList()) }

    /**
     * Encaissement. Mode sur place : les lignes non envoyées partent en cuisine automatiquement au règlement.
     * Solde nul → commande PAID.
     */
    fun pay(orderId: String, cashierId: String, method: PaymentMethod, given: Cents, key: String): OrderSnapshot =
        db.runInTransaction<OrderSnapshot> {
            if (dao.insertOperation(ProcessedOperationEntity(key, "PAYMENT", clock())) == -1L) return@runInTransaction snapshot(orderId)!!
            val before = snapshot(orderId)!!
            require(before.order.status != OrderStatus.PAID) { "note déjà soldée" }
            val sent = KitchenDispatcher.send(before.order, ids)
            persist(before.order, sent, cashierId, "KITCHEN_SENT_ON_PAYMENT", null)
            val payment = PaymentRules.pay(
                ids.next(), orderId, OrderPricer.price(sent.order).totalTtc, before.payments, method, given,
                cashierId, terminalId, clock(),
            )
            dao.insertPayment(payment.toEntity())
            log(cashierId, "PAYMENT_RECORDED", orderId, null, JSONObject()
                .put("payment", payment.id).put("method", method.name).put("amount", payment.amount.value)
                .put("tendered", payment.tendered.value).put("change", payment.change.value).toString())
            val after = snapshot(orderId)!!
            if (after.remaining.isZero) {
                val paid = after.order.copy(status = OrderStatus.PAID).bumped()
                dao.upsertOrder(paid.toEntity(dao.order(orderId)!!.openedAt))
                log(cashierId, "ORDER_PAID", orderId, after.order.summary(), paid.summary())
                snapshot(orderId)!!
            } else after
        }

    fun verifyJournal(): ChainVerification = JournalVerifier.verify(terminalId, journal.all())

    private fun mutate(
        orderId: String, actorId: String, key: String, type: String, reason: String? = null,
        op: (Order) -> DispatchResult,
    ): OrderSnapshot = db.runInTransaction<OrderSnapshot> {
        // Idempotence : la même clé (même tap) ne rejoue jamais l'opération.
        if (dao.insertOperation(ProcessedOperationEntity(key, type, clock())) == -1L) return@runInTransaction snapshot(orderId)!!
        val before = snapshot(orderId)!!.order
        if (before.status == OrderStatus.PAID) error("commande soldée : modification interdite")
        persist(before, op(before), actorId, type, reason)
        snapshot(orderId)!!
    }

    private fun persist(before: Order, result: DispatchResult, actorId: String, type: String, reason: String?) {
        if (result.order == before && result.isEmpty) return
        val openedAt = dao.order(before.id)!!.openedAt
        dao.upsertOrder(result.order.toEntity(openedAt))
        dao.upsertLines(result.order.lines.mapIndexed { i, l -> l.toEntity(before.id, i) })
        if (!result.isEmpty) dao.insertTickets(result.tickets.map {
            KitchenTicketEntity(
                dispatchId = it.dispatchId, orderId = before.id, destination = it.destination, kind = it.kind.name,
                inverse = it.inverse, renderedText = KitchenTicketRenderer.render(it, columns = 32).joinToString("\n"),
                createdAt = clock(),
            )
        })
        log(actorId, type, before.id, before.summary(), result.order.summary(), reason)
    }

    private fun log(actorId: String, type: String, orderId: String, before: String?, after: String?, reason: String? = null) {
        journal.append(JournalDraft(ids.next(), establishmentId, terminalId, actorId, clock(), type, "order", orderId, before, after, reason))
    }

    private fun Order.summary(): String {
        val p = OrderPricer.price(this)
        return JSONObject()
            .put("version", version).put("status", status.name).put("table", tableNumber)
            .put("lines", activeLines.size).put("sent", activeLines.count { it.isSent })
            .put("claimed", claimedLevels.sorted().joinToString(","))
            .put("ticketDiscountBp", ticketDiscount?.rate?.value ?: 0)
            .put("totalTtc", p.totalTtc.value)
            .toString()
    }
}

private fun PaymentEntity.toDomain() = Payment(id, orderId, PaymentMethod.valueOf(method), Cents(amount), Cents(tendered), cashierEmployeeId, terminalId, at)
private fun Payment.toEntity() = PaymentEntity(id, orderId, method.name, amount.value, tendered.value, cashierEmployeeId, terminalId, at)

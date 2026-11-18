package fr.foodsafe.pos.app.data

import fr.foodsafe.pos.core.discount.LineDiscount
import fr.foodsafe.pos.core.discount.TicketDiscount
import fr.foodsafe.pos.core.journal.JournalDraft
import fr.foodsafe.pos.core.journal.JournalEvent
import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.Cancellation
import fr.foodsafe.pos.core.order.Order
import fr.foodsafe.pos.core.order.OrderLine
import fr.foodsafe.pos.core.order.OrderStatus
import fr.foodsafe.pos.core.order.SaleMode
import fr.foodsafe.pos.core.order.ServiceLevel

fun OrderEntity.toDomain(lines: List<OrderLineEntity>) = Order(
    id = id,
    saleMode = SaleMode.valueOf(saleMode),
    tableNumber = tableNumber,
    ownerEmployeeId = ownerEmployeeId,
    status = OrderStatus.valueOf(status),
    version = version,
    lines = lines.map { it.toDomain() },
    claimedLevels = claimedLevels.split(',').filter { it.isNotBlank() }.map { it.toInt() }.toSet(),
    ticketDiscount = ticketDiscountBp?.let { TicketDiscount(BasisPoints(it)) },
)

fun OrderLineEntity.toDomain() = OrderLine(
    id = id, productId = productId, label = label, unitPriceTtc = Cents(unitPriceTtc),
    quantityMilli = quantityMilli, vatRate = BasisPoints(vatRateBp), level = ServiceLevel(serviceLevel),
    destination = destination, authorEmployeeId = authorEmployeeId, createdAt = createdAt,
    lineDiscount = when (lineDiscountKind) {
        "PERCENT" -> LineDiscount.Percent(BasisPoints(lineDiscountValue!!.toInt()))
        "AMOUNT" -> LineDiscount.Amount(Cents(lineDiscountValue!!))
        else -> null
    },
    dispatchId = dispatchId,
    cancellation = cancelReason?.let { Cancellation(it, cancelledBy!!, cancelledAt!!) },
    parentLineId = parentLineId,
)

fun Order.toEntity(openedAt: Long) = OrderEntity(
    id = id, saleMode = saleMode.name, tableNumber = tableNumber, ownerEmployeeId = ownerEmployeeId,
    status = status.name, version = version, claimedLevels = claimedLevels.sorted().joinToString(","),
    ticketDiscountBp = ticketDiscount?.rate?.value, openedAt = openedAt,
)

fun OrderLine.toEntity(orderId: String, position: Int) = OrderLineEntity(
    id = id, orderId = orderId, position = position, productId = productId, label = label,
    unitPriceTtc = unitPriceTtc.value, quantityMilli = quantityMilli, vatRateBp = vatRate.value,
    serviceLevel = level.value, destination = destination, authorEmployeeId = authorEmployeeId,
    createdAt = createdAt,
    lineDiscountKind = when (lineDiscount) { is LineDiscount.Percent -> "PERCENT"; is LineDiscount.Amount -> "AMOUNT"; null -> null },
    lineDiscountValue = when (val d = lineDiscount) { is LineDiscount.Percent -> d.rate.value.toLong(); is LineDiscount.Amount -> d.amount.value; null -> null },
    dispatchId = dispatchId,
    cancelReason = cancellation?.reason, cancelledBy = cancellation?.byEmployeeId, cancelledAt = cancellation?.at,
    parentLineId = parentLineId,
)

fun JournalEventEntity.toDomain() = JournalEvent(
    seq = seq,
    draft = JournalDraft(id, establishment_id, terminal_id, actor_id, occurred_at, type, entity_type, entity_id, before_json, after_json, reason),
    prevHash = prev_hash, hash = hash,
)

fun JournalEvent.toEntity() = JournalEventEntity(
    seq = seq, id = draft.id, establishment_id = draft.establishmentId, terminal_id = draft.terminalId,
    actor_id = draft.actorId, occurred_at = draft.occurredAt, type = draft.type, entity_type = draft.entityType,
    entity_id = draft.entityId, before_json = draft.beforeJson, after_json = draft.afterJson, reason = draft.reason,
    prev_hash = prevHash, hash = hash,
)

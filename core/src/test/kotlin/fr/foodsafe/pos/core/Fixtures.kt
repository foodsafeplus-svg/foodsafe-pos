package fr.foodsafe.pos.core

import fr.foodsafe.pos.core.kitchen.IdGenerator
import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.Order
import fr.foodsafe.pos.core.order.OrderLine
import fr.foodsafe.pos.core.order.SaleMode
import fr.foodsafe.pos.core.order.ServiceLevel
import fr.foodsafe.pos.core.tax.FrenchVat

class SeqIds(private val prefix: String = "id") : IdGenerator {
    private var n = 0
    override fun next() = "$prefix-${++n}"
}

fun line(
    id: String,
    label: String,
    priceCents: Long,
    rate: BasisPoints = FrenchVat.INTERMEDIATE,
    qty: Long = 1,
    level: Int = 0,
    destination: String = "CUISINE",
    author: String = "marie",
) = OrderLine(
    id = id, productId = "p-$label", label = label, unitPriceTtc = Cents(priceCents),
    quantityMilli = qty * 1000, vatRate = rate, level = ServiceLevel(level),
    destination = destination, authorEmployeeId = author, createdAt = 0,
)

fun order(vararg lines: OrderLine, table: String = "43") =
    Order(id = "o1", saleMode = SaleMode.DINE_IN, tableNumber = table, ownerEmployeeId = "marie", lines = lines.toList())

fun Order.add(vararg l: OrderLine) = copy(lines = lines + l).bumped()

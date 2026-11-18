package fr.foodsafe.pos.app.data

import fr.foodsafe.pos.core.fiscal.ClosureInfo
import fr.foodsafe.pos.core.fiscal.FiscalDocType
import fr.foodsafe.pos.core.fiscal.FiscalDocument
import fr.foodsafe.pos.core.fiscal.FiscalLine
import fr.foodsafe.pos.core.fiscal.FiscalPayment
import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.payment.PaymentMethod
import fr.foodsafe.pos.core.tax.VatLine
import org.json.JSONArray
import org.json.JSONObject

private fun <T> JSONArray.map(f: (JSONObject) -> T) = (0 until length()).map { f(getJSONObject(it)) }
private fun JSONObject.optLongOrNull(k: String) = if (isNull(k)) null else getLong(k)

fun FiscalDocument.toEntity() = FiscalDocumentEntity(
    id = id, establishmentId = establishmentId, terminalId = terminalId, type = type.name, number = number,
    issuedAt = issuedAt, actorId = actorId, orderId = orderId, tableLabel = tableLabel, relatedDocumentId = relatedDocumentId,
    linesJson = JSONArray(lines.map {
        JSONObject().put("label", it.label).put("qty", it.quantityMilli).put("unit", it.unitPriceTtc.value)
            .put("gross", it.gross.value).put("discount", it.discount.value).put("net", it.net.value).put("vat", it.vatRate.value)
    }).toString(),
    taxesJson = JSONArray(taxes.map { JSONObject().put("rate", it.rate.value).put("ttc", it.ttc.value).put("ht", it.ht.value).put("vat", it.vat.value) }).toString(),
    total = total.value,
    paymentsJson = JSONArray(payments.map { JSONObject().put("method", it.method.name).put("amount", it.amount.value) }).toString(),
    perpetualTotal = perpetualTotal.value, perpetualTotalAbs = perpetualTotalAbs.value,
    closureJson = closure?.let {
        JSONObject().put("start", it.periodStart).put("end", it.periodEnd).put("count", it.ticketCount)
            .put("first", it.firstTicketNumber ?: JSONObject.NULL).put("last", it.lastTicketNumber ?: JSONObject.NULL)
            .put("discounts", it.discounts.value).toString()
    },
    prevHash = prevHash, hash = hash, signature = signature, keyId = keyId,
)

fun FiscalDocumentEntity.toDomain() = FiscalDocument(
    id = id, establishmentId = establishmentId, terminalId = terminalId, type = FiscalDocType.valueOf(type), number = number,
    issuedAt = issuedAt, actorId = actorId, orderId = orderId, tableLabel = tableLabel, relatedDocumentId = relatedDocumentId,
    lines = JSONArray(linesJson).map {
        FiscalLine(it.getString("label"), it.getLong("qty"), Cents(it.getLong("unit")), Cents(it.getLong("gross")),
            Cents(it.getLong("discount")), Cents(it.getLong("net")), BasisPoints(it.getInt("vat")))
    },
    taxes = JSONArray(taxesJson).map { VatLine(BasisPoints(it.getInt("rate")), Cents(it.getLong("ttc")), Cents(it.getLong("ht")), Cents(it.getLong("vat"))) },
    total = Cents(total),
    payments = JSONArray(paymentsJson).map { FiscalPayment(PaymentMethod.valueOf(it.getString("method")), Cents(it.getLong("amount"))) },
    perpetualTotal = Cents(perpetualTotal), perpetualTotalAbs = Cents(perpetualTotalAbs),
    closure = closureJson?.let { JSONObject(it) }?.let {
        ClosureInfo(it.getLong("start"), it.getLong("end"), it.getInt("count"), it.optLongOrNull("first"), it.optLongOrNull("last"), Cents(it.getLong("discounts")))
    },
    prevHash = prevHash, hash = hash, signature = signature, keyId = keyId,
)

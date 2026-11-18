package fr.foodsafe.pos.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "employees")
data class EmployeeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val role: String,
    val pinHash: String,
    val active: Boolean,
)

/** Documents fiscaux : append-only (triggers), numérotation continue et chaînage par (terminal, type). */
@Entity(
    tableName = "fiscal_documents",
    indices = [Index(value = ["terminalId", "type", "number"], unique = true), Index("orderId")],
)
data class FiscalDocumentEntity(
    @PrimaryKey val id: String,
    val establishmentId: String,
    val terminalId: String,
    val type: String,
    val number: Long,
    val issuedAt: Long,
    val actorId: String,
    val orderId: String?,
    val tableLabel: String?,
    val relatedDocumentId: String?,
    val linesJson: String,
    val taxesJson: String,
    val total: Long,
    val paymentsJson: String,
    val perpetualTotal: Long,
    val perpetualTotalAbs: Long,
    val closureJson: String?,
    val prevHash: String,
    val hash: String,
    val signature: String,
    val keyId: String,
)

/** Clés publiques de signature (une par terminal et par génération de clé), pour la vérification. */
@Entity(tableName = "fiscal_keys")
data class FiscalKeyEntity(
    @PrimaryKey val keyId: String,
    val publicKeyB64: String,
    val createdAt: Long,
)

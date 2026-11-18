package fr.foodsafe.pos.app.data

import fr.foodsafe.pos.core.auth.Decision
import fr.foodsafe.pos.core.auth.Employee
import fr.foodsafe.pos.core.auth.LoginResult
import fr.foodsafe.pos.core.auth.PermissionPolicy
import fr.foodsafe.pos.core.auth.PinAuthenticator
import fr.foodsafe.pos.core.auth.PinHasher
import fr.foodsafe.pos.core.auth.Role
import fr.foodsafe.pos.core.auth.SensitiveAction
import fr.foodsafe.pos.core.discount.TicketDiscount
import fr.foodsafe.pos.core.fiscal.ClosureReport
import fr.foodsafe.pos.core.fiscal.Establishment
import fr.foodsafe.pos.core.fiscal.FiscalDocType
import fr.foodsafe.pos.core.fiscal.FiscalDocument
import fr.foodsafe.pos.core.fiscal.FiscalLedger
import fr.foodsafe.pos.core.fiscal.FiscalStore
import fr.foodsafe.pos.core.fiscal.FiscalVerification
import fr.foodsafe.pos.core.fiscal.FiscalVerifier
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
import fr.foodsafe.pos.core.util.B64
import org.json.JSONObject
import java.util.UUID

data class OrderSnapshot(val order: Order, val payments: List<Payment>, val ticket: FiscalDocument? = null) {
    val priced get() = OrderPricer.price(order)
    val remaining: Cents get() = PaymentRules.remaining(priced.totalTtc, payments)
}

/** L'action exige la validation d'un responsable (PIN). L'UI redemande avec [approverId]. */
class ApprovalRequired(val action: SensitiveAction, reason: String) : Exception(reason)
class ActionDenied(reason: String) : Exception(reason)

/**
 * Point d'entrée unique des écritures. Chaque opération s'exécute dans UNE transaction SQLite :
 * contrôle des droits → idempotence → état commande → bons cuisine → paiement → document fiscal → journal.
 * Si une étape échoue, rien n'est écrit. Les droits sont vérifiés ici, pas seulement dans l'interface.
 */
class PosRepository(
    private val db: PosDatabase,
    val establishment: Establishment,
    private val terminalId: String,
    private val keys: FiscalKeyProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() },
    private val policy: PermissionPolicy = PermissionPolicy.DEFAULT,
) {
    private val dao = db.dao()
    private val authenticator = PinAuthenticator()

    private val journal = object : JournalStore {
        override fun last() = dao.lastJournal()?.toDomain()
        override fun insert(event: JournalEvent) = dao.insertJournal(event.toEntity())
        override fun all() = dao.allJournal().map { it.toDomain() }
    }

    private val fiscalStore = object : FiscalStore {
        override fun last(type: FiscalDocType) = dao.lastFiscal(terminalId, type.name)?.toDomain()
        override fun insert(doc: FiscalDocument) = dao.insertFiscal(doc.toEntity())
        override fun byId(id: String) = dao.fiscal(id)?.toDomain()
        override fun all(type: FiscalDocType) = dao.fiscalOfType(terminalId, type.name).map { it.toDomain() }
    }

    private val ledger = FiscalLedger(fiscalStore, keys.signer, establishment, terminalId)

    /** Catalogue éditable (produits, claviers) : réservé aux responsables, chaque modification est journalisée. */
    val catalog = CatalogRepository(
        db, ids::next, clock,
        log = { a, t, e, i, b, af -> log(a, t, e, i, b, af) },
        requireRight = { actor -> authorize(actor, SensitiveAction.EDIT_CATALOG, 0, null) },
    )

    /**
     * À appeler une fois au démarrage, hors du thread UI : enregistre la clé publique du terminal
     * et crée l'équipe de démonstration si la base est vide.
     */
    fun bootstrap() = db.runInTransaction {
        dao.insertKey(FiscalKeyEntity(keys.signer.keyId, B64.encode(keys.publicKey.encoded), clock()))
        seedDemoEmployees()
        catalog.seedIfEmpty()
    }

    val tickets get() = dao.observeTickets()
    val journalFlow get() = dao.observeJournal()
    val fiscalFlow get() = dao.observeFiscal()
    val openOrdersFlow get() = dao.observeOpenOrders()
    val openLinesFlow get() = dao.observeOpenLines()

    // ---------- Employés et connexion ----------

    /** SIMULÉ : équipe de démonstration créée au premier lancement (gestion des employés : back-office, phase 2). */
    fun seedDemoEmployees() = db.runInTransaction {
        if (dao.employees().isEmpty()) dao.insertEmployees(
            listOf(
                EmployeeEntity("marie", "Marie", Role.SERVER.name, PinHasher.hash("1111"), true),
                EmployeeEntity("paul", "Paul", Role.MANAGER.name, PinHasher.hash("9999"), true),
            ),
        )
    }

    fun employee(id: String): Employee? = dao.employee(id)?.toDomain()

    fun login(pin: String): LoginResult {
        val result = authenticator.login(pin, dao.employees().map { it.toDomain() }, clock())
        db.runInTransaction {
            when (result) {
                is LoginResult.Success -> log(result.employee.id, "LOGIN", "session", "-", null, null)
                LoginResult.WrongPin -> log("inconnu", "LOGIN_FAILED", "session", "-", null, null)
                is LoginResult.Locked -> log("inconnu", "TERMINAL_LOCKED", "session", "-", null, null)
            }
        }
        return result
    }

    fun logout(employeeId: String, reason: String) = db.runInTransaction {
        log(employeeId, "LOGOUT", "session", "-", null, null, reason)
    }

    /** Vérifie le PIN d'un responsable pour valider [action]. */
    fun approverFor(pin: String, action: SensitiveAction): Employee? =
        dao.employees().map { it.toDomain() }.firstOrNull { PinHasher.verify(pin, it.pinHash) && policy.canApprove(it, action) }

    private fun authorize(actorId: String, action: SensitiveAction, value: Long, approverId: String?): String? {
        val actor = employee(actorId) ?: throw ActionDenied("employé inconnu")
        return when (val d = policy.check(actor.role, action, value)) {
            Decision.Allowed -> null
            is Decision.Denied -> throw ActionDenied(d.reason)
            is Decision.NeedsManager -> {
                val approver = approverId?.let(::employee) ?: throw ApprovalRequired(action, d.reason)
                if (!policy.canApprove(approver, action)) throw ActionDenied("${approver.name} ne peut pas valider cette action")
                if (approver.id == actor.id && policy.policy(actor.role, action) == fr.foodsafe.pos.core.auth.Policy.AlwaysValidate) {
                    throw ActionDenied("validation par une autre personne requise")
                }
                "validé par ${approver.name}"
            }
        }
    }

    // ---------- Commandes ----------

    fun snapshot(orderId: String): OrderSnapshot? = dao.order(orderId)?.let { o ->
        OrderSnapshot(
            o.toDomain(dao.lines(orderId)),
            dao.payments(orderId).map { it.toDomain() },
            fiscalStore.all(FiscalDocType.TICKET).lastOrNull { it.orderId == orderId },
        )
    }

    fun openOrder(ownerId: String, tableNumber: String, mode: SaleMode = SaleMode.DINE_IN): OrderSnapshot =
        db.runInTransaction<OrderSnapshot> {
            require(tableNumber.isNotBlank()) { "numéro de table requis" }
            if (tableNumber in dao.allOpenOrderTables()) error("la table $tableNumber est déjà ouverte")
            val order = Order(ids.next(), mode, tableNumber, ownerId)
            dao.upsertOrder(order.toEntity(clock()))
            log(ownerId, "ORDER_OPENED", "order", order.id, null, order.summary())
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

    /** Mise en attente (déconnexion, changement d'utilisateur) : la note reste à son propriétaire. */
    fun hold(orderId: String, actorId: String, key: String) =
        mutate(orderId, actorId, key, "ORDER_ON_HOLD") { o ->
            if (o.status == OrderStatus.OPEN && o.lines.isNotEmpty()) DispatchResult(o.copy(status = OrderStatus.ON_HOLD).bumped(), emptyList())
            else DispatchResult(o, emptyList())
        }

    fun cancelLine(orderId: String, actorId: String, lineId: String, reason: String, key: String, approverId: String? = null): OrderSnapshot {
        require(reason.isNotBlank()) { "motif d'annulation obligatoire" }
        val sent = snapshot(orderId)?.order?.lines?.firstOrNull { it.id == lineId }?.isSent == true
        val approval = if (sent) authorize(actorId, SensitiveAction.CANCEL_SENT_LINE, 0, approverId) else null
        return mutate(orderId, actorId, key, "LINE_CANCELLED", listOfNotNull(reason, approval).joinToString(" · ")) { order ->
            val line = order.lines.first { it.id == lineId }
            KitchenDispatcher.cancelLine(order, lineId, line.quantityMilli, reason, actorId, clock(), ids)
        }
    }

    fun setTicketDiscount(orderId: String, actorId: String, discount: TicketDiscount?, key: String, approverId: String? = null): OrderSnapshot {
        val approval = authorize(actorId, SensitiveAction.TICKET_DISCOUNT, discount?.rate?.value?.toLong() ?: 0, approverId)
        return mutate(orderId, actorId, key, "TICKET_DISCOUNT_SET", approval) {
            DispatchResult(it.copy(ticketDiscount = discount).bumped(), emptyList())
        }
    }

    /**
     * Encaissement. Sur place : les lignes non envoyées partent en cuisine au règlement.
     * Solde nul → commande PAID et ticket fiscal émis dans la même transaction.
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
            log(cashierId, "PAYMENT_RECORDED", "order", orderId, null, JSONObject()
                .put("payment", payment.id).put("method", method.name).put("amount", payment.amount.value)
                .put("tendered", payment.tendered.value).put("change", payment.change.value).toString())
            val after = snapshot(orderId)!!
            if (after.remaining.isZero) {
                val paid = after.order.copy(status = OrderStatus.PAID).bumped()
                dao.upsertOrder(paid.toEntity(dao.order(orderId)!!.openedAt))
                log(cashierId, "ORDER_PAID", "order", orderId, after.order.summary(), paid.summary())
                val ticket = ledger.issueTicket(
                    ids.next(), orderId, paid.tableNumber?.let { "TABLE $it" }, OrderPricer.price(paid), after.payments, cashierId, clock(),
                )
                log(cashierId, "FISCAL_TICKET_ISSUED", "fiscal", ticket.id, null, ticket.numberLabel)
            }
            snapshot(orderId)!!
        }

    // ---------- Documents fiscaux et clôtures ----------

    /** « Imprimer la note » : note provisoire, ne clôture pas la vente. */
    fun printProvisionalNote(orderId: String, actorId: String): FiscalDocument = db.runInTransaction<FiscalDocument> {
        val s = snapshot(orderId)!!
        if (s.order.status == OrderStatus.PAID) error("la note est soldée : utiliser le ticket")
        ledger.issueProvisionalNote(ids.next(), orderId, s.order.tableNumber?.let { "TABLE $it" }, s.priced, s.payments, actorId, clock())
            .also { log(actorId, "PROVISIONAL_NOTE_ISSUED", "fiscal", it.id, null, it.numberLabel) }
    }

    fun duplicate(ticketId: String, actorId: String, approverId: String? = null): FiscalDocument {
        val approval = authorize(actorId, SensitiveAction.REPRINT_DUPLICATE, 0, approverId)
        return db.runInTransaction<FiscalDocument> {
            ledger.issueDuplicate(ids.next(), ticketId, actorId, clock())
                .also { log(actorId, "DUPLICATE_ISSUED", "fiscal", it.id, null, it.numberLabel, approval) }
        }
    }

    /** Ouverture du tiroir sans vente : droit vérifié et inscrite au journal AVANT l'impulsion. */
    fun openDrawer(actorId: String, approverId: String? = null) {
        val approval = authorize(actorId, SensitiveAction.OPEN_DRAWER, 0, approverId)
        db.runInTransaction { log(actorId, "DRAWER_OPENED", "drawer", terminalId, null, null, approval) }
    }

    /** Comptage des espèces (compteur) : non fiscal, mais tracé (total + détail). */
    fun logCashCount(actorId: String, count: fr.foodsafe.pos.core.cash.CashCount) = db.runInTransaction {
        val detail = count.lines.joinToString(";") { "${it.denomination.value}x${it.quantity}" }
        log(actorId, "CASH_COUNTED", "drawer", terminalId, null, "total=${count.total.value};$detail")
    }

    fun reportX(actorId: String): ClosureReport = db.runInTransaction<ClosureReport> {
        ledger.reportX(clock()).also { log(actorId, "REPORT_X", "closure", "-", null, "total=${it.total.value}") }
    }

    fun closeZ(actorId: String, approverId: String? = null): FiscalDocument {
        val approval = authorize(actorId, SensitiveAction.Z_CLOSURE, 0, approverId)
        return db.runInTransaction<FiscalDocument> {
            ledger.closeZ(ids.next(), actorId, clock())
                .also { log(actorId, "Z_CLOSURE", "fiscal", it.id, null, it.numberLabel, approval) }
        }
    }

    fun fiscalDocument(id: String) = fiscalStore.byId(id)

    data class IntegrityReport(val journal: ChainVerification, val fiscal: FiscalVerification)

    fun verifyAll(): IntegrityReport {
        val publicKeys = dao.keys().associate { it.keyId to decodePublicKey(it.publicKeyB64) }
        return IntegrityReport(JournalVerifier.verify(terminalId, journal.all()), FiscalVerifier.verify(terminalId, fiscalStore, publicKeys))
    }

    fun verifyJournal(): ChainVerification = JournalVerifier.verify(terminalId, journal.all())

    // ---------- Interne ----------

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
        log(actorId, type, "order", before.id, before.summary(), result.order.summary(), reason)
    }

    private fun log(actorId: String, type: String, entityType: String, entityId: String, before: String?, after: String?, reason: String? = null) {
        journal.append(JournalDraft(ids.next(), establishment.id, terminalId, actorId, clock(), type, entityType, entityId, before, after, reason))
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

private fun EmployeeEntity.toDomain() = Employee(id, name, Role.valueOf(role), pinHash, active)
private fun PaymentEntity.toDomain() = Payment(id, orderId, PaymentMethod.valueOf(method), Cents(amount), Cents(tendered), cashierEmployeeId, terminalId, at)
private fun Payment.toEntity() = PaymentEntity(id, orderId, method.name, amount.value, tendered.value, cashierEmployeeId, terminalId, at)

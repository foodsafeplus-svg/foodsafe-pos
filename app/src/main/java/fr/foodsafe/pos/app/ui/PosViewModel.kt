package fr.foodsafe.pos.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.foodsafe.pos.app.PosApplication
import fr.foodsafe.pos.app.data.ActionDenied
import fr.foodsafe.pos.app.data.ApprovalRequired
import fr.foodsafe.pos.app.data.OrderSnapshot
import fr.foodsafe.pos.app.data.PosRepository
import fr.foodsafe.pos.app.data.Product
import fr.foodsafe.pos.app.data.toDomain
import fr.foodsafe.pos.core.auth.Employee
import fr.foodsafe.pos.core.auth.LoginResult
import fr.foodsafe.pos.core.auth.Role
import fr.foodsafe.pos.core.auth.SensitiveAction
import fr.foodsafe.pos.core.auth.SessionPolicy
import fr.foodsafe.pos.core.discount.TicketDiscount
import fr.foodsafe.pos.core.fiscal.FiscalDocument
import fr.foodsafe.pos.core.fiscal.ReceiptRenderer
import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.OrderPricer
import fr.foodsafe.pos.core.order.OrderStatus
import fr.foodsafe.pos.core.order.ServiceLevel
import fr.foodsafe.pos.core.payment.PaymentMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class Screen { TABLES, ORDER, DOCUMENTS, CLOSURE, KITCHEN, JOURNAL }

data class TableSummary(val orderId: String, val table: String, val ownerId: String, val status: OrderStatus, val total: Cents)

data class PendingApproval(val action: SensitiveAction, val reason: String, val retry: (String) -> Unit)

data class ReceiptPreview(val title: String, val lines: List<String>, val document: FiscalDocument? = null, val gift: Boolean = false)

data class UiState(
    val user: Employee? = null,
    val screen: Screen = Screen.TABLES,
    val snapshot: OrderSnapshot? = null,
    val selectedLevel: ServiceLevel? = null,
    val message: String? = null,
    val loginError: String? = null,
    val approval: PendingApproval? = null,
    val receipt: ReceiptPreview? = null,
    val integrity: PosRepository.IntegrityReport? = null,
)

class PosViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as PosApplication).repository
    private val est = repo.establishment
    val establishmentName: String get() = est.name

    /** Déconnexion après 2 min d'inactivité (paramétrable par établissement en phase 2). */
    val sessionPolicy = SessionPolicy(inactivityTimeoutMs = 120_000)
    private var lastActivity = System.currentTimeMillis()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val kitchenTickets = repo.tickets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val journal = repo.journalFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val documents = repo.fiscalFlow.map { l -> l.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tables = combine(repo.openOrdersFlow, repo.openLinesFlow) { orders, lines ->
        val byOrder = lines.groupBy { it.orderId }
        orders.map { o ->
            val order = o.toDomain(byOrder[o.id].orEmpty().sortedBy { it.position })
            TableSummary(o.id, o.tableNumber ?: "-", o.ownerEmployeeId, OrderStatus.valueOf(o.status), OrderPricer.price(order).totalTtc)
        }.sortedBy { it.table.toIntOrNull() ?: Int.MAX_VALUE }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Initialisation de la base hors du thread UI ; la connexion attend qu'elle soit terminée. */
    private val ready = viewModelScope.async(Dispatchers.IO) { repo.bootstrap() }

    init {
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val user = _state.value.user
                if (user != null && sessionPolicy.isExpired(lastActivity, System.currentTimeMillis())) logout("inactivité")
            }
        }
    }

    fun touch() { lastActivity = System.currentTimeMillis() }

    private fun userId() = _state.value.user!!.id
    private fun orderId() = _state.value.snapshot!!.order.id

    /**
     * Exécute une action hors du thread UI. Chaque action reçoit une clé d'idempotence générée au tap,
     * conservée si un responsable doit valider (la seconde tentative ne crée pas de doublon).
     */
    private fun <T> act(
        message: String? = null,
        key: String = UUID.randomUUID().toString(),
        approverId: String? = null,
        onSuccess: (T) -> Unit = {},
        block: (key: String, approverId: String?) -> T,
    ) {
        touch()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block(key, approverId) } }
            r.onSuccess { onSuccess(it); if (message != null) _state.value = _state.value.copy(message = message) }
            r.onFailure { e ->
                _state.value = when (e) {
                    is ApprovalRequired -> _state.value.copy(approval = PendingApproval(e.action, e.message ?: "") { approver ->
                        act(message, key, approver, onSuccess, block)
                    })
                    is ActionDenied -> _state.value.copy(message = "Interdit : ${e.message}")
                    else -> _state.value.copy(message = "Refusé : ${e.message}")
                }
            }
        }
    }

    // ---------- Session ----------

    fun login(pin: String) = viewModelScope.launch {
        touch()
        ready.await()
        val r = withContext(Dispatchers.IO) { repo.login(pin) }
        _state.value = when (r) {
            // Toujours l'accueil (plan des tables), jamais la note de l'utilisateur précédent.
            is LoginResult.Success -> UiState(user = r.employee, screen = Screen.TABLES, message = "Bonjour ${r.employee.name}")
            LoginResult.WrongPin -> _state.value.copy(loginError = "PIN incorrect")
            is LoginResult.Locked -> _state.value.copy(loginError = "Trop d'essais : terminal verrouillé 30 s")
        }
    }

    fun logout(reason: String = "déconnexion") {
        val s = _state.value
        val user = s.user ?: return
        viewModelScope.launch(Dispatchers.IO) {
            s.snapshot?.order?.let { if (it.status == OrderStatus.OPEN) runCatching { repo.hold(it.id, user.id, UUID.randomUUID().toString()) } }
            repo.logout(user.id, reason)
        }
        _state.value = UiState(message = if (reason == "inactivité") "Déconnecté après inactivité" else null)
    }

    fun approve(pin: String) {
        val p = _state.value.approval ?: return
        viewModelScope.launch {
            val approver = withContext(Dispatchers.IO) { repo.approverFor(pin, p.action) }
            if (approver == null) {
                _state.value = _state.value.copy(message = "PIN responsable invalide")
            } else {
                _state.value = _state.value.copy(approval = null)
                p.retry(approver.id)
            }
        }
    }

    fun dismissApproval() { _state.value = _state.value.copy(approval = null) }

    // ---------- Navigation ----------

    fun show(screen: Screen) { touch(); _state.value = _state.value.copy(screen = screen) }

    fun openTable(orderId: String) = act<OrderSnapshot>(onSuccess = { setOrder(it) }) { _, _ -> repo.snapshot(orderId)!! }

    fun newTable(number: String) = act<OrderSnapshot>("Table $number ouverte", onSuccess = { setOrder(it) }) { _, _ -> repo.openOrder(userId(), number) }

    private fun setOrder(s: OrderSnapshot) { _state.value = _state.value.copy(snapshot = s, screen = Screen.ORDER, selectedLevel = null) }
    private fun update(s: OrderSnapshot) { _state.value = _state.value.copy(snapshot = s) }

    fun backToTables() {
        val s = _state.value.snapshot
        if (s != null && s.order.status == OrderStatus.OPEN && s.order.lines.isNotEmpty()) {
            act<OrderSnapshot> { k, _ -> repo.hold(s.order.id, userId(), k) }
        }
        _state.value = _state.value.copy(screen = Screen.TABLES, snapshot = null)
    }

    // ---------- Commande ----------

    fun selectLevel(level: ServiceLevel?) { touch(); _state.value = _state.value.copy(selectedLevel = level) }
    fun add(p: Product) = act(onSuccess = ::update) { k, _ -> repo.addProduct(orderId(), userId(), p, _state.value.selectedLevel, k) }
    fun send() = act("Commande envoyée en cuisine", onSuccess = ::update) { k, _ -> repo.sendToKitchen(orderId(), userId(), k) }

    /** Après réclamation : note en attente et retour au plan des tables. */
    fun claim() = act<OrderSnapshot>("Réclamation envoyée", onSuccess = { _state.value = _state.value.copy(snapshot = null, screen = Screen.TABLES) }) { k, _ ->
        repo.claimNext(orderId(), userId(), k)
    }

    fun cancel(lineId: String, reason: String) = act("Ligne annulée", onSuccess = ::update) { k, a -> repo.cancelLine(orderId(), userId(), lineId, reason, k, a) }

    fun ticketDiscount(percent: Int) = act(if (percent == 0) "Remise retirée" else "Remise $percent % appliquée", onSuccess = ::update) { k, a ->
        repo.setTicketDiscount(orderId(), userId(), if (percent == 0) null else TicketDiscount(BasisPoints.percent(percent)), k, a)
    }

    fun pay(method: PaymentMethod, amount: Cents) = act<OrderSnapshot>(onSuccess = { s ->
        update(s)
        s.ticket?.let { showDocument(it) }
    }) { k, _ -> repo.pay(orderId(), userId(), method, amount, k) }

    fun printNote() = act<FiscalDocument>(onSuccess = { showDocument(it) }) { _, _ -> repo.printProvisionalNote(orderId(), userId()) }

    // ---------- Documents ----------

    fun showDocument(d: FiscalDocument) {
        val remaining = _state.value.snapshot?.takeIf { it.order.id == d.orderId }?.remaining
        _state.value = _state.value.copy(receipt = ReceiptPreview(d.numberLabel, ReceiptRenderer.render(d, est, remaining = remaining), d))
    }

    fun showGift(d: FiscalDocument) {
        _state.value = _state.value.copy(receipt = ReceiptPreview("Ticket cadeau", ReceiptRenderer.renderGift(d, est), d, gift = true))
    }

    fun duplicate(d: FiscalDocument) = act<FiscalDocument>("Duplicata émis", onSuccess = { showDocument(it) }) { _, a -> repo.duplicate(d.id, userId(), a) }

    fun dismissReceipt() { _state.value = _state.value.copy(receipt = null) }

    fun reportX() = act<List<String>>(onSuccess = { _state.value = _state.value.copy(receipt = ReceiptPreview("Rapport X", it)) }) { _, _ ->
        ReceiptRenderer.renderX(repo.reportX(userId()), est)
    }

    fun closeZ() = act<FiscalDocument>("Clôture Z enregistrée", onSuccess = { showDocument(it) }) { _, a -> repo.closeZ(userId(), a) }

    fun verifyAll() = act<PosRepository.IntegrityReport>(onSuccess = { _state.value = _state.value.copy(integrity = it) }) { _, _ -> repo.verifyAll() }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }

    val isManager get() = _state.value.user?.role != Role.SERVER
}

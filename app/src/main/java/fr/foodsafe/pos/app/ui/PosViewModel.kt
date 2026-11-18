package fr.foodsafe.pos.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.foodsafe.pos.app.PosApplication
import fr.foodsafe.pos.app.data.OrderSnapshot
import fr.foodsafe.pos.app.data.Product
import fr.foodsafe.pos.core.discount.TicketDiscount
import fr.foodsafe.pos.core.journal.ChainVerification
import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.ServiceLevel
import fr.foodsafe.pos.core.payment.PaymentMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class UiState(
    val snapshot: OrderSnapshot? = null,
    /** null = niveau par défaut du produit. */
    val selectedLevel: ServiceLevel? = null,
    val message: String? = null,
    val verification: ChainVerification? = null,
)

class PosViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as PosApplication).repository
    // SIMULÉ : utilisateur connecté fixe (connexion PIN en phase 1).
    val employeeId = "marie"

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    val tickets = repo.tickets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val journal = repo.journalFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { run { repo.currentOrNew(employeeId) } }

    private val orderId get() = _state.value.snapshot!!.order.id

    /** Chaque action reçoit une clé d'idempotence générée au tap. */
    private fun run(message: String? = null, block: (key: String) -> OrderSnapshot) {
        val key = UUID.randomUUID().toString()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block(key) } }
            result.onSuccess { _state.value = _state.value.copy(snapshot = it, message = message) }
                .onFailure { _state.value = _state.value.copy(message = "Refusé : ${it.message}") }
        }
    }

    fun selectLevel(level: ServiceLevel?) { _state.value = _state.value.copy(selectedLevel = level) }
    fun add(p: Product) = run { repo.addProduct(orderId, employeeId, p, _state.value.selectedLevel, it) }
    fun send() = run("Commande envoyée en cuisine") { repo.sendToKitchen(orderId, employeeId, it) }
    fun claim() = run("Réclamation envoyée, note mise en attente") { repo.claimNext(orderId, employeeId, it) }
    fun cancel(lineId: String, reason: String) = run("Ligne annulée") { repo.cancelLine(orderId, employeeId, lineId, reason, it) }
    fun ticketDiscount(percent: Int) = run(if (percent == 0) "Remise retirée" else "Remise $percent % appliquée") {
        repo.setTicketDiscount(orderId, employeeId, if (percent == 0) null else TicketDiscount(BasisPoints.percent(percent)), it)
    }
    fun pay(method: PaymentMethod, amount: Cents) = run { repo.pay(orderId, employeeId, method, amount, it) }
    fun newTable(number: String) = run("Table $number ouverte") { repo.openOrder(employeeId, number) }
    fun dismissMessage() { _state.value = _state.value.copy(message = null) }

    fun verifyChain() = viewModelScope.launch {
        val v = withContext(Dispatchers.IO) { repo.verifyJournal() }
        _state.value = _state.value.copy(verification = v)
    }
}

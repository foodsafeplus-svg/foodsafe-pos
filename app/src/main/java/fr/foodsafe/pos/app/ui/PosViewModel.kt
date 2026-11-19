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
import fr.foodsafe.pos.app.data.ModifierItem
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

enum class Screen { TABLES, ORDER, DOCUMENTS, CLOSURE, KITCHEN, JOURNAL, HOME, CASH_COUNT, CALCULATOR, CATALOG, KEYBOARDS, PRODUCTS, IMAGES, WIZARD, MODIFIERS, FORMULAS }

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
    private val printer = (app as PosApplication).printer

    /** Impression hors du thread UI ; une erreur s'affiche dans le bandeau sans bloquer la vente. */
    private fun printLines(lines: List<String>, openDrawer: Boolean = false) {
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { runCatching { printer.print(lines, openDrawer) }.getOrElse { "Impression impossible : ${it.message}" } }
            if (err != null) _state.value = _state.value.copy(message = err)
        }
    }
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

    // ---------- Catalogue et claviers ----------

    val catalog = repo.catalog.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val prefs = app.getSharedPreferences("terminal", android.content.Context.MODE_PRIVATE)
    private val _keyboardId = MutableStateFlow(prefs.getString("keyboardId", null))
    /** Clavier utilisé par ce terminal (null = le premier). */
    val keyboardId: StateFlow<String?> = _keyboardId.asStateFlow()
    private val _page = MutableStateFlow<String?>(null)
    /** Page affichée en vente (null = première page visible). */
    val page: StateFlow<String?> = _page.asStateFlow()
    private val pageStack = ArrayDeque<String>()
    val canGoBackPage get() = pageStack.isNotEmpty()

    fun useKeyboard(id: String) {
        prefs.edit().putString("keyboardId", id).apply()
        _keyboardId.value = id; _page.value = null; pageStack.clear()
    }
    fun selectTab(pageId: String) { touch(); pageStack.clear(); _page.value = pageId }
    fun openPage(pageId: String) { touch(); _page.value?.let(pageStack::addLast); _page.value = pageId }
    fun pageBack() { touch(); _page.value = pageStack.removeLastOrNull() }

    private fun catalogEdit(message: String? = null, block: (String) -> Unit) = act<Unit>(message) { _, _ -> block(userId()) }
    fun saveProduct(p: Product, message: String = "Produit enregistré") = catalogEdit(message) { repo.catalog.saveProduct(it, p) }
    fun newProductId() = repo.catalog.newProductId()
    fun addKeyboard(name: String) = catalogEdit("Clavier créé") { repo.catalog.addKeyboard(it, name) }
    fun renameKeyboard(id: String, name: String) = catalogEdit { repo.catalog.renameKeyboard(it, id, name) }
    fun addPage(keyboardId: String, name: String, visible: Boolean, onDone: (String) -> Unit = {}) =
        act<String>("Page créée", onSuccess = onDone) { _, _ -> repo.catalog.addPage(userId(), keyboardId, name, visible) }
    fun updatePage(pageId: String, name: String? = null, visible: Boolean? = null) = catalogEdit { repo.catalog.updatePage(it, pageId, name, visible) }
    fun movePage(pageId: String, delta: Int) = catalogEdit { repo.catalog.movePage(it, pageId, delta) }
    fun deletePage(pageId: String) = catalogEdit("Page supprimée") { repo.catalog.deletePage(it, pageId) }
    fun setProductCell(pageId: String, slot: Int, productId: String, color: Int?) = catalogEdit { repo.catalog.setProductCell(it, pageId, slot, productId, color) }
    fun setPageLinkCell(pageId: String, slot: Int, target: String, color: Int?) = catalogEdit { repo.catalog.setPageLinkCell(it, pageId, slot, target, color) }
    fun moveCell(fromPage: String, fromSlot: Int, toPage: String, toSlot: Int) =
        catalogEdit("Touche déplacée") { repo.catalog.moveCell(it, fromPage, fromSlot, toPage, toSlot) }
    /** La modification du catalogue est réservée au responsable (règle EDIT_CATALOG). */
    val canEditCatalog get() = _state.value.user?.role != fr.foodsafe.pos.core.auth.Role.SERVER
    fun notify(text: String) { _state.value = _state.value.copy(message = text) }
    fun setCellTariff(pageId: String, slot: Int, tariff: Int?) = catalogEdit("Tarification fixée") { repo.catalog.setCellTariff(it, pageId, slot, tariff) }
    fun clearCell(pageId: String, slot: Int) = catalogEdit { repo.catalog.clearCell(it, pageId, slot) }
    fun createAndPlace(p: Product, pageId: String, slot: Int, onDone: () -> Unit) =
        act<Product>("Produit « ${p.label} » créé et placé", onSuccess = { onDone() }) { _, _ -> repo.catalog.createAndPlace(userId(), p, pageId, slot) }
    fun saveGroup(id: String?, name: String, mandatory: Boolean, multiple: Boolean, onDone: (String) -> Unit = {}) =
        act<String>("Groupe enregistré", onSuccess = onDone) { _, _ -> repo.catalog.saveGroup(userId(), id, name, mandatory, multiple) }
    fun deleteGroup(id: String) = catalogEdit("Groupe supprimé") { repo.catalog.deleteGroup(it, id) }
    fun saveModifier(item: ModifierItem) = catalogEdit("Élément enregistré") { repo.catalog.saveItem(it, item) }
    fun deleteModifier(id: String) = catalogEdit("Élément supprimé") { repo.catalog.deleteItem(it, id) }
    fun setProductGroups(productId: String, groupIds: List<String>) = catalogEdit { repo.catalog.setProductGroups(it, productId, groupIds) }
    fun saveFormula(f: fr.foodsafe.pos.app.data.Formula, onDone: (String) -> Unit = {}) =
        act<String>("Formule enregistrée", onSuccess = onDone) { _, _ -> repo.catalog.saveFormula(userId(), f) }
    fun deleteFormula(id: String) = catalogEdit("Formule supprimée") { repo.catalog.deleteFormula(it, id) }
    fun setFormulaCell(pageId: String, slot: Int, formulaId: String, color: Int?) = catalogEdit { repo.catalog.setFormulaCell(it, pageId, slot, formulaId, color) }
    fun deleteImage(id: String) = catalogEdit("Image supprimée") { repo.catalog.deleteImage(it, id) }

    /** Importe une image choisie sur la tablette : réduite à 256 px max, enregistrée en JPEG dans le stockage privé. */
    fun importImage(uri: android.net.Uri) = act<String>("Image importée") { _, _ ->
        val app = getApplication<Application>()
        val cr = app.contentResolver
        val name = runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.substringBeforeLast('.') ?: "Image"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("image illisible")
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("format d'image non reconnu")
        val scale = 256f / maxOf(decoded.width, decoded.height)
        val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
        repo.catalog.addImage(userId(), name.take(30), out.toByteArray())
    }

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
    fun add(p: Product, modifiers: List<ModifierItem> = emptyList()) =
        act(onSuccess = ::update) { k, _ -> repo.addProduct(orderId(), userId(), p, _state.value.selectedLevel, k, modifiers) }
    fun addFormula(f: fr.foodsafe.pos.app.data.Formula, picks: List<Product>) =
        act(onSuccess = ::update) { k, _ -> repo.addFormula(orderId(), userId(), f, picks, k) }
    fun removePromo(lineId: String, reason: String) =
        act("Promotion retirée", onSuccess = ::update) { k, a -> repo.removePromo(orderId(), userId(), lineId, reason, k, a) }
    fun addModifiers(lineId: String, modifiers: List<ModifierItem>) =
        act(onSuccess = ::update) { k, _ -> repo.addModifiers(orderId(), userId(), lineId, modifiers, k) }
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
        if (method.isCash && s.ticket == null) printLines(emptyList(), openDrawer = true)
        s.ticket?.let { showDocument(it, print = true, openDrawer = s.ticket.payments.any { p -> p.method.isCash }) }
    }) { k, _ -> repo.pay(orderId(), userId(), method, amount, k) }

    fun printNote() = act<FiscalDocument>(onSuccess = { showDocument(it, print = true) }) { _, _ -> repo.printProvisionalNote(orderId(), userId()) }

    // ---------- Documents ----------

    /**
     * Affiche un document. print = true seulement au moment où il est émis (ticket, note, duplicata, Z) :
     * le revoir depuis une liste ne le réimprime pas, une réimpression passe par un DUPLICATA numéroté.
     */
    fun showDocument(d: FiscalDocument, print: Boolean = false, openDrawer: Boolean = false) {
        val remaining = _state.value.snapshot?.takeIf { it.order.id == d.orderId }?.remaining
        val lines = ReceiptRenderer.render(d, est, remaining = remaining)
        _state.value = _state.value.copy(receipt = ReceiptPreview(d.numberLabel, lines, d))
        if (print) printLines(lines, openDrawer)
    }

    fun showGift(d: FiscalDocument) {
        val lines = ReceiptRenderer.renderGift(d, est)
        _state.value = _state.value.copy(receipt = ReceiptPreview("Ticket cadeau", lines, d, gift = true))
        printLines(lines)
    }

    fun duplicate(d: FiscalDocument) = act<FiscalDocument>("Duplicata émis", onSuccess = { showDocument(it, print = true) }) { _, a -> repo.duplicate(d.id, userId(), a) }

    fun dismissReceipt() { _state.value = _state.value.copy(receipt = null) }

    // ---------- Menu Accueil ----------

    fun openDrawer() = act<Unit>("Tiroir ouvert", onSuccess = { printLines(emptyList(), openDrawer = true) }) { _, a -> repo.openDrawer(userId(), a) }

    fun printCashCount(count: fr.foodsafe.pos.core.cash.CashCount) = act<List<String>>("Comptage imprimé", onSuccess = { printLines(it) }) { _, _ ->
        repo.logCashCount(userId(), count)
        val now = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date())
        count.render("COMPTAGE DES ESPÈCES", listOf(est.name.uppercase(), now, "Utilisateur : ${_state.value.user?.name}", "Terminal T01"))
    }

    fun reportX() = act<List<String>>(onSuccess = { _state.value = _state.value.copy(receipt = ReceiptPreview("Rapport X", it)); printLines(it) }) { _, _ ->
        ReceiptRenderer.renderX(repo.reportX(userId()), est)
    }

    fun closeZ() = act<FiscalDocument>("Clôture Z enregistrée", onSuccess = { showDocument(it, print = true) }) { _, a -> repo.closeZ(userId(), a) }

    fun verifyAll() = act<PosRepository.IntegrityReport>(onSuccess = { _state.value = _state.value.copy(integrity = it) }) { _, _ -> repo.verifyAll() }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }

    val isManager get() = _state.value.user?.role != Role.SERVER
}

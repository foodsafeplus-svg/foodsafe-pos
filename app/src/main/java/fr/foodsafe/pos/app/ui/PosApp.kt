package fr.foodsafe.pos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.foodsafe.pos.app.data.DemoCatalog
import fr.foodsafe.pos.app.data.JournalEventEntity
import fr.foodsafe.pos.app.data.KitchenTicketEntity
import fr.foodsafe.pos.app.data.OrderSnapshot
import fr.foodsafe.pos.app.data.PosRepository
import fr.foodsafe.pos.core.fiscal.FiscalDocType
import fr.foodsafe.pos.core.fiscal.FiscalDocument
import fr.foodsafe.pos.core.fiscal.FiscalVerification
import fr.foodsafe.pos.core.journal.ChainVerification
import fr.foodsafe.pos.core.kitchen.KitchenDispatcher
import fr.foodsafe.pos.core.kitchen.KitchenTicketRenderer
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.OrderLine
import fr.foodsafe.pos.core.order.OrderStatus
import fr.foodsafe.pos.core.order.ServiceLevel
import fr.foodsafe.pos.core.payment.PaymentMethod
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Brand = Color(0xFF0E7C66)
private val Muted = Color(0xFF6B7280)
private val Warn = Color(0xFFB45309)

@Composable
fun PosApp(vm: PosViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }

    MaterialTheme(colorScheme = lightColorScheme(primary = Brand)) {
        Scaffold { pad ->
            Box(
                Modifier.padding(pad).fillMaxSize().pointerInput(Unit) {
                    // Toute interaction repousse la déconnexion pour inactivité.
                    awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); vm.touch() } }
                },
            ) {
                if (state.user == null) LoginScreen(state.loginError, vm::login) else MainScreen(state, vm)
                // Les notifications s'affichent en bas à gauche : jamais par-dessus les boutons d'action de la note.
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomStart).fillMaxWidth(0.55f))
            }
        }
        state.approval?.let { ApprovalDialog(it, vm::approve, vm::dismissApproval) }
        state.receipt?.let { r -> ReceiptDialog(r, vm::dismissReceipt, onGift = r.document?.takeIf { it.type == FiscalDocType.TICKET && !r.gift }?.let { d -> { vm.showGift(d) } }) }
    }
}

// ---------------------------------------------------------------- Connexion

@Composable
private fun LoginScreen(error: String?, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Brand), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("FOODSAFE+", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Saisissez votre code PIN", color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text("●".repeat(pin.length).ifEmpty { " " }, color = Color.White, fontSize = 28.sp, modifier = Modifier.height(40.dp))
        error?.let { Text(it, color = Color(0xFFFFE08A), fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(12.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k ->
                        Surface(
                            Modifier.size(84.dp, 64.dp).clickable {
                                when (k) {
                                    "⌫" -> pin = pin.dropLast(1)
                                    "OK" -> { onPin(pin); pin = "" }
                                    else -> if (pin.length < 8) pin += k
                                }
                            },
                            color = Color.White, shape = MaterialTheme.shapes.medium,
                        ) { Box(contentAlignment = Alignment.Center) { Text(k, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Brand) } }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Démo : Marie (serveuse) 1111 · Paul (responsable) 9999", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- Structure

@Composable
private fun MainScreen(state: UiState, vm: PosViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Brand).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("FOODSAFE+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.width(16.dp))
            Text(
                state.snapshot?.takeIf { state.screen == Screen.ORDER }?.let { "Table ${it.order.tableNumber} · ${statusLabel(it.order.status)} · propriétaire ${it.order.ownerEmployeeId}" } ?: "",
                color = Color.White, modifier = Modifier.weight(1f),
            )
            Text("${state.user!!.name} (${roleLabel(state.user)}) · T01", color = Color.White)
            TextButton(onClick = { vm.logout() }) { Text("Déconnexion", color = Color.White) }
        }
        val tabs = listOf(Screen.TABLES to "Tables", Screen.DOCUMENTS to "Tickets", Screen.CLOSURE to "Clôtures", Screen.KITCHEN to "Bons cuisine", Screen.JOURNAL to "Journal")
        val selected = tabs.indexOfFirst { it.first == state.screen }.let { if (it < 0) 0 else it }
        TabRow(selectedTabIndex = selected) {
            tabs.forEachIndexed { i, (screen, label) -> Tab(selected = i == selected && state.screen != Screen.ORDER, onClick = { if (screen == Screen.TABLES) vm.backToTables() else vm.show(screen) }, text = { Text(label) }) }
        }
        when (state.screen) {
            Screen.TABLES -> TablesScreen(vm)
            Screen.ORDER -> state.snapshot?.let { OrderScreen(it, state.selectedLevel, vm) }
            Screen.DOCUMENTS -> DocumentsScreen(vm)
            Screen.CLOSURE -> ClosureScreen(vm)
            Screen.KITCHEN -> KitchenScreen(vm)
            Screen.JOURNAL -> JournalScreen(vm, state.integrity)
        }
    }
}

private fun statusLabel(s: OrderStatus) = when (s) {
    OrderStatus.OPEN -> "ouverte"; OrderStatus.ON_HOLD -> "en attente"; OrderStatus.PAID -> "soldée"; OrderStatus.CANCELLED -> "annulée"
}

private fun roleLabel(e: fr.foodsafe.pos.core.auth.Employee) = when (e.role) {
    fr.foodsafe.pos.core.auth.Role.SERVER -> "serveur"; fr.foodsafe.pos.core.auth.Role.MANAGER -> "responsable"; fr.foodsafe.pos.core.auth.Role.ADMIN -> "admin"
}

// ---------------------------------------------------------------- Tables (accueil)

@Composable
private fun TablesScreen(vm: PosViewModel) {
    val tables by vm.tables.collectAsStateWithLifecycle()
    var askNumber by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tables ouvertes : ${tables.size}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { askNumber = true }) { Text("Nouvelle table") }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tables, key = { it.orderId }) { t ->
                Card(
                    Modifier.height(100.dp).clickable { vm.openTable(t.orderId) },
                    colors = CardDefaults.cardColors(containerColor = if (t.status == OrderStatus.ON_HOLD) Color(0xFFFFF4E0) else Color(0xFFE6F4F0)),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Table ${t.table}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(t.ownerId, color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Row { Text(t.total.format(), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(statusLabel(t.status), fontSize = 12.sp, color = if (t.status == OrderStatus.ON_HOLD) Warn else Brand) }
                    }
                }
            }
        }
        if (tables.isEmpty()) Text("Aucune table ouverte. « Nouvelle table » pour commencer.", color = Muted)
    }
    if (askNumber) NumberDialog("Numéro de la table", onDismiss = { askNumber = false }) { askNumber = false; vm.newTable(it) }
}

// ---------------------------------------------------------------- Commande

@Composable
private fun OrderScreen(s: OrderSnapshot, selectedLevel: ServiceLevel?, vm: PosViewModel) {
    var cancelTarget by remember { mutableStateOf<OrderLine?>(null) }
    var showDiscount by remember { mutableStateOf(false) }
    var showPay by remember { mutableStateOf(false) }
    val paid = s.order.status == OrderStatus.PAID
    val priced = s.priced
    val nextClaim = KitchenDispatcher.nextClaimableLevel(s.order)

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1.3f).fillMaxHeight().padding(12.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::backToTables) { Text("← Tables") }
                FilterChip(selected = selectedLevel == null, onClick = { vm.selectLevel(null) }, label = { Text("Niveau produit") })
                (0..3).forEach { l -> ServiceLevel(l).let { lvl -> FilterChip(selected = selectedLevel == lvl, onClick = { vm.selectLevel(lvl) }, label = { Text(lvl.label) }) } }
            }
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(GridCells.Adaptive(140.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DemoCatalog.products) { p ->
                    Card(Modifier.height(84.dp).clickable(enabled = !paid) { vm.add(p) }) {
                        Column(Modifier.padding(10.dp)) {
                            Text(p.label, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Spacer(Modifier.weight(1f))
                            Text("${p.price.format()} · ${p.defaultLevel.label}", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF5F7F6)).padding(12.dp)) {
            LazyColumn(Modifier.weight(1f)) {
                priced.lines.groupBy { it.line.level.value }.toSortedMap().forEach { (level, lines) ->
                    item {
                        val claimed = level in s.order.claimedLevels
                        Text(ServiceLevel(level).label + if (level > 0 && claimed) " · réclamé" else if (level > 1) " · à suivre" else "",
                            fontWeight = FontWeight.Bold, color = Brand, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(lines, key = { it.line.id }) { pl ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((if (pl.line.isSent) "✓ " else "• ") + "${KitchenTicketRenderer.formatQty(pl.line.quantityMilli)} ${pl.line.label}",
                                Modifier.weight(1f), color = if (pl.line.isSent) Muted else Color.Unspecified)
                            if (pl.gross != pl.net) Text(pl.gross.format(false), textDecoration = TextDecoration.LineThrough, color = Muted, fontSize = 12.sp)
                            Text(" " + pl.net.format(), fontWeight = FontWeight.Medium)
                            if (!paid) TextButton(onClick = { cancelTarget = pl.line }) { Text("✕") }
                        }
                    }
                }
                val cancelled = s.order.lines.filter { !it.isActive }
                if (cancelled.isNotEmpty()) item {
                    Text("Annulées", color = Muted, modifier = Modifier.padding(top = 8.dp))
                    cancelled.forEach { Text("${it.label} · motif : ${it.cancellation?.reason}", color = Muted, textDecoration = TextDecoration.LineThrough, fontSize = 12.sp) }
                }
            }
            HorizontalDivider()
            if (!priced.totalDiscount.isZero) Text("Remises : -${priced.totalDiscount.format()}", color = Muted)
            priced.taxes.byRate.forEach { Text("TVA ${it.rate.format()} : ${it.vat.format()} (HT ${it.ht.format()})", fontSize = 12.sp, color = Muted) }
            Text("Total ${priced.totalTtc.format()}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (s.payments.isNotEmpty() && !paid) Text("Reste à payer ${s.remaining.format()}", color = Brand, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (paid) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.ticket?.let { t -> OutlinedButton(onClick = { vm.showDocument(t) }, Modifier.weight(1f)) { Text("Voir le ticket") } }
                    Button(onClick = vm::backToTables, Modifier.weight(1f)) { Text("Retour aux tables") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::send, Modifier.weight(1f)) { Text("Envoyer commande") }
                    if (nextClaim != null) OutlinedButton(onClick = vm::claim, Modifier.weight(1f)) { Text("Réclamer ${nextClaim.label}") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDiscount = true }, Modifier.weight(1f)) { Text("Remise note %") }
                    OutlinedButton(onClick = vm::printNote, Modifier.weight(1f), enabled = priced.lines.isNotEmpty()) { Text("Imprimer la note") }
                }
                Button(onClick = { showPay = true }, Modifier.fillMaxWidth(), enabled = priced.lines.isNotEmpty()) { Text("ENCAISSER") }
            }
        }
    }
    cancelTarget?.let { line -> CancelDialog(line, onDismiss = { cancelTarget = null }) { reason -> vm.cancel(line.id, reason); cancelTarget = null } }
    if (showDiscount) DiscountDialog(onDismiss = { showDiscount = false }) { vm.ticketDiscount(it); showDiscount = false }
    if (showPay && !paid) PayDialog(s, onDismiss = { showPay = false }) { m, a -> vm.pay(m, a); if (a >= s.remaining) showPay = false }
}

// ---------------------------------------------------------------- Tickets et clôtures

@Composable
private fun DocumentsScreen(vm: PosViewModel) {
    val docs by vm.documents.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item { Text("Documents émis par ce terminal (aucun ne peut être modifié ni supprimé)", color = Muted, fontSize = 12.sp) }
        items(docs.filter { it.type != FiscalDocType.Z }, key = { it.id }) { d ->
            Row(Modifier.fillMaxWidth().clickable { vm.showDocument(d) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(d.numberLabel, fontFamily = FontFamily.Monospace, modifier = Modifier.width(160.dp))
                Text(d.type.label, modifier = Modifier.width(140.dp), color = if (d.type == FiscalDocType.TICKET) Color.Unspecified else Muted)
                Text(d.tableLabel ?: "", modifier = Modifier.width(100.dp))
                Text(fmt.format(Date(d.issuedAt)), color = Muted, modifier = Modifier.weight(1f))
                Text(d.total.format(), fontWeight = FontWeight.SemiBold)
                if (d.type == FiscalDocType.TICKET) TextButton(onClick = { vm.duplicate(d) }) { Text("Duplicata") }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ClosureScreen(vm: PosViewModel) {
    val docs by vm.documents.collectAsStateWithLifecycle()
    val zs = docs.filter { it.type == FiscalDocType.Z }
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::reportX) { Text("Rapport X (consultation)") }
            Button(onClick = vm::closeZ) { Text("Clôture Z") }
        }
        Text("Le Z couvre tous les tickets depuis le Z précédent. Un Z enregistré ne peut jamais être modifié.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn {
            items(zs, key = { it.id }) { z ->
                Row(Modifier.fillMaxWidth().clickable { vm.showDocument(z) }.padding(vertical = 8.dp)) {
                    Text("Z n° ${z.number}", fontWeight = FontWeight.Bold, modifier = Modifier.width(100.dp))
                    Text(fmt.format(Date(z.issuedAt)), modifier = Modifier.width(170.dp))
                    Text((z.closure?.ticketCount ?: 0).let { if (it > 1) "$it tickets" else "$it ticket" }, modifier = Modifier.weight(1f))
                    Text(z.total.format(), fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider()
            }
        }
    }
}

// ---------------------------------------------------------------- Dialogues

@Composable
private fun ReceiptDialog(r: ReceiptPreview, onDismiss: () -> Unit, onGift: (() -> Unit)?) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(r.title) },
        text = {
            Column {
                Text("SIMULÉ : aperçu papier 80 mm (imprimante non connectée).", color = Muted, fontSize = 11.sp)
                Surface(color = Color.White, shadowElevation = 2.dp, modifier = Modifier.padding(top = 6.dp)) {
                    Text(r.lines.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.Black,
                        modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState()))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = { onGift?.let { TextButton(onClick = it) { Text("Ticket cadeau") } } },
    )
}

@Composable
private fun ApprovalDialog(p: PendingApproval, onPin: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Validation responsable") },
        text = {
            Column {
                Text(p.reason)
                OutlinedTextField(pin, { v -> pin = v.filter { it.isDigit() }.take(8) }, label = { Text("PIN du responsable") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onPin(pin) }, enabled = pin.length >= 4) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun NumberDialog(title: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(v, { s -> v = s.filter { it.isLetterOrDigit() }.take(6) }, singleLine = true, label = { Text("Numéro") }) },
        confirmButton = { Button(onClick = { onOk(v) }, enabled = v.isNotBlank()) { Text("Ouvrir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun CancelDialog(line: OrderLine, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Annuler ${line.label}") },
        text = {
            Column {
                if (line.isSent) Text("Déjà envoyé : un bon d'annulation partira en ${line.destination.lowercase()} (sans le motif). Validation responsable requise pour un serveur.")
                listOf("Erreur de saisie", "Client a changé d'avis", "Produit indisponible").forEach { TextButton(onClick = { reason = it }) { Text(it) } }
                OutlinedTextField(reason, { reason = it }, label = { Text("Motif (obligatoire)") })
            }
        },
        confirmButton = { Button(onClick = { onConfirm(reason) }, enabled = reason.isNotBlank()) { Text("Confirmer l'annulation") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Retour") } },
    )
}

@Composable
private fun DiscountDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remise sur la note (en %)") },
        text = {
            Column {
                Text("Appliquée ligne par ligne, chaque produit garde son taux de TVA. Aucune remise en euros sur la note. Au-delà de 10 %, un serveur doit faire valider par un responsable.")
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 20).forEach { OutlinedButton(onClick = { onPick(it) }) { Text("$it %") } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(0) }) { Text("Retirer la remise") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun PayDialog(s: OrderSnapshot, onDismiss: () -> Unit, onPay: (PaymentMethod, Cents) -> Unit) {
    val rest = s.remaining
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Encaisser · reste ${rest.format()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Carte : saisir le montant validé sur le TPE autonome (TPE intégré : phase ultérieure).", fontSize = 12.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPay(PaymentMethod.CARD, rest) }) { Text("Carte ${rest.format()}") }
                    if (rest.value >= 2000) OutlinedButton(onClick = { onPay(PaymentMethod.CARD, Cents(rest.value / 2)) }) { Text("Carte moitié") }
                }
                Text("Espèces (rendu calculé)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPay(PaymentMethod.CASH, rest) }) { Text("Exact") }
                    listOf(1000L, 2000L, 5000L).filter { it >= rest.value }.take(2).forEach { v ->
                        OutlinedButton(onClick = { onPay(PaymentMethod.CASH, Cents(v)) }) { Text("${v / 100} € (rendu ${(Cents(v) - rest).format()})") }
                    }
                }
                s.payments.forEach { Text("${it.method.label} ${it.amount.format()}" + if (!it.change.isZero) " · rendu ${it.change.format()}" else "", fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

// ---------------------------------------------------------------- Cuisine et journal

@Composable
private fun KitchenScreen(vm: PosViewModel) {
    val tickets by vm.kitchenTickets.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("SIMULÉ : aperçu des bons tels qu'ils seraient imprimés (aucune imprimante connectée).", color = Muted, fontSize = 12.sp)
        LazyVerticalGrid(GridCells.Adaptive(260.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(tickets, key = { it.rowId }) { t -> KitchenTicketCard(t) }
        }
    }
}

@Composable
private fun KitchenTicketCard(t: KitchenTicketEntity) {
    Surface(color = if (t.inverse) Color.Black else Color.White, shadowElevation = 2.dp) {
        Column(Modifier.padding(10.dp)) {
            Text("→ ${t.destination}", color = if (t.inverse) Color.White else Brand, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(t.renderedText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (t.inverse) Color.White else Color.Black)
        }
    }
}

@Composable
private fun JournalScreen(vm: PosViewModel, integrity: PosRepository.IntegrityReport?) {
    val events by vm.journal.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::verifyAll) { Text("Vérifier l'intégrité") }
            Spacer(Modifier.width(12.dp))
            Column {
                when (val j = integrity?.journal) {
                    is ChainVerification.Valid -> Text("Journal intègre ✓ (${j.count} événements)", color = Brand, fontWeight = FontWeight.Bold)
                    is ChainVerification.Broken -> Text("Journal ROMPU au n° ${j.seq} : ${j.problem}", color = Color.Red, fontWeight = FontWeight.Bold)
                    null -> Text("Journal et documents en ajout seul : modification et suppression bloquées par la base.", color = Muted)
                }
                when (val f = integrity?.fiscal) {
                    is FiscalVerification.Valid -> Text("Documents fiscaux intègres et signatures valides ✓ (${f.count})", color = Brand, fontWeight = FontWeight.Bold)
                    is FiscalVerification.Broken -> Text("Document ${f.type.label} n° ${f.number} : ${f.problem}", color = Color.Red, fontWeight = FontWeight.Bold)
                    null -> {}
                }
            }
        }
        LazyColumn(Modifier.padding(top = 8.dp)) {
            items(events, key = { it.seq }) { e -> JournalRow(e, fmt) }
        }
    }
}

@Composable
private fun JournalRow(e: JournalEventEntity, fmt: SimpleDateFormat) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("#${e.seq} · ${fmt.format(Date(e.occurred_at))} · ${e.type} · ${e.actor_id}" + (e.reason?.let { " · $it" } ?: ""), fontWeight = FontWeight.Medium)
        Text("hash ${e.hash.take(16)}… ← ${e.prev_hash.take(16)}…", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted)
    }
    HorizontalDivider()
}

package fr.foodsafe.pos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
private val Sent = Color(0xFF6B7280)

@Composable
fun PosApp(vm: PosViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tickets by vm.tickets.collectAsStateWithLifecycle()
    val journal by vm.journal.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Brand)) {
        // Les notifications s'affichent sous la grille produits : jamais par-dessus les boutons d'action de la note.
        Scaffold { pad ->
          Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Header(state.snapshot, vm.employeeId)
                TabRow(selectedTabIndex = tab) {
                    listOf("Commande", "Bons cuisine (${tickets.size})", "Journal (${journal.size})").forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                    }
                }
                when (tab) {
                    0 -> state.snapshot?.let { OrderScreen(it, state.selectedLevel, vm) }
                    1 -> TicketsScreen(tickets)
                    else -> JournalScreen(journal, state.verification, vm::verifyChain)
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomStart).fillMaxWidth(0.55f))
          }
        }
    }
}

@Composable
private fun Header(s: OrderSnapshot?, employee: String) {
    Row(
        Modifier.fillMaxWidth().background(Brand).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("FOODSAFE+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.width(16.dp))
        Text(
            s?.let { "Table ${it.order.tableNumber} · ${statusLabel(it.order.status)}" } ?: "",
            color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f),
        )
        Text("Serveur : $employee · Terminal T01", color = Color.White.copy(alpha = 0.85f))
    }
}

private fun statusLabel(s: OrderStatus) = when (s) {
    OrderStatus.OPEN -> "ouverte"
    OrderStatus.ON_HOLD -> "en attente"
    OrderStatus.PAID -> "soldée"
    OrderStatus.CANCELLED -> "annulée"
}

@Composable
private fun OrderScreen(s: OrderSnapshot, selectedLevel: ServiceLevel?, vm: PosViewModel) {
    var cancelTarget by remember { mutableStateOf<OrderLine?>(null) }
    var showDiscount by remember { mutableStateOf(false) }
    var showPay by remember { mutableStateOf(false) }
    val paid = s.order.status == OrderStatus.PAID
    val priced = s.priced
    val nextClaim = KitchenDispatcher.nextClaimableLevel(s.order)

    Row(Modifier.fillMaxSize()) {
        // Colonne produits
        Column(Modifier.weight(1.3f).fillMaxHeight().padding(12.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedLevel == null, onClick = { vm.selectLevel(null) }, label = { Text("Niveau produit") })
                (0..3).forEach { l ->
                    val lvl = ServiceLevel(l)
                    FilterChip(selected = selectedLevel == lvl, onClick = { vm.selectLevel(lvl) }, label = { Text(lvl.label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(GridCells.Adaptive(140.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DemoCatalog.products) { p ->
                    Card(Modifier.height(88.dp).clickable(enabled = !paid) { vm.add(p) }) {
                        Column(Modifier.padding(10.dp)) {
                            Text(p.label, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Spacer(Modifier.weight(1f))
                            Text("${p.price.format()} · ${p.defaultLevel.label}", color = Sent, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        // Colonne note
        Column(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF5F7F6)).padding(12.dp)) {
            LazyColumn(Modifier.weight(1f)) {
                val byLevel = priced.lines.groupBy { it.line.level.value }.toSortedMap()
                byLevel.forEach { (level, lines) ->
                    item {
                        val claimed = level in s.order.claimedLevels
                        Text(
                            ServiceLevel(level).label + if (level > 0 && claimed) " · réclamé" else if (level > 1) " · à suivre" else "",
                            fontWeight = FontWeight.Bold, color = Brand, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(lines, key = { it.line.id }) { pl ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (pl.line.isSent) "✓ " else "• ") + "${KitchenTicketRenderer.formatQty(pl.line.quantityMilli)} ${pl.line.label}",
                                Modifier.weight(1f), color = if (pl.line.isSent) Sent else Color.Unspecified,
                            )
                            if (pl.gross != pl.net) Text(pl.gross.format(false), textDecoration = TextDecoration.LineThrough, color = Sent, fontSize = 12.sp)
                            Text(" " + pl.net.format(), fontWeight = FontWeight.Medium)
                            if (!paid) TextButton(onClick = { cancelTarget = pl.line }) { Text("✕") }
                        }
                    }
                }
                val cancelled = s.order.lines.filter { !it.isActive }
                if (cancelled.isNotEmpty()) item {
                    Text("Annulées", color = Sent, modifier = Modifier.padding(top = 8.dp))
                    cancelled.forEach { Text("${it.label} · motif : ${it.cancellation?.reason}", color = Sent, textDecoration = TextDecoration.LineThrough, fontSize = 12.sp) }
                }
            }
            HorizontalDivider()
            if (!priced.totalDiscount.isZero) Text("Remises : -${priced.totalDiscount.format()}", color = Sent)
            priced.taxes.byRate.forEach { Text("TVA ${it.rate.format()} : ${it.vat.format()} (HT ${it.ht.format()})", fontSize = 12.sp, color = Sent) }
            Text("Total ${priced.totalTtc.format()}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (s.payments.isNotEmpty()) Text("Reste à payer ${s.remaining.format()}", color = Brand, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (paid) {
                Button(onClick = { vm.newTable(((s.order.tableNumber?.toIntOrNull() ?: 40) + 1).toString()) }, Modifier.fillMaxWidth()) { Text("Nouvelle table") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::send, Modifier.weight(1f)) { Text("Envoyer commande") }
                    if (nextClaim != null) OutlinedButton(onClick = vm::claim, Modifier.weight(1f)) { Text("Réclamer ${nextClaim.label}") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDiscount = true }, Modifier.weight(1f)) { Text("Remise note %") }
                    Button(onClick = { showPay = true }, Modifier.weight(1f), enabled = priced.lines.isNotEmpty()) { Text("ENCAISSER") }
                }
            }
        }
    }

    cancelTarget?.let { line -> CancelDialog(line, onDismiss = { cancelTarget = null }) { reason -> vm.cancel(line.id, reason); cancelTarget = null } }
    if (showDiscount) DiscountDialog(onDismiss = { showDiscount = false }) { vm.ticketDiscount(it); showDiscount = false }
    if (showPay) PayDialog(s, onDismiss = { showPay = false }) { m, a -> vm.pay(m, a); if (a >= s.remaining) showPay = false }
}

@Composable
private fun CancelDialog(line: OrderLine, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Annuler ${line.label}") },
        text = {
            Column {
                if (line.isSent) Text("Déjà envoyé : un bon d'annulation partira en ${line.destination.lowercase()}. Le motif n'y sera pas imprimé.")
                Spacer(Modifier.height(8.dp))
                listOf("Erreur de saisie", "Client a changé d'avis", "Produit indisponible").forEach {
                    TextButton(onClick = { reason = it }) { Text(it) }
                }
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
                Text("Appliquée ligne par ligne, chaque produit garde son taux de TVA. Aucune remise en euros sur la note.")
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
                Text("Carte : saisie du montant validé sur le TPE autonome (TPE intégré non disponible dans cet incrément).", fontSize = 12.sp, color = Sent)
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
                if (s.payments.isNotEmpty()) s.payments.forEach { Text("${it.method.label} ${it.amount.format()}" + if (!it.change.isZero) " · rendu ${it.change.format()}" else "", fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun TicketsScreen(tickets: List<KitchenTicketEntity>) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("SIMULÉ : aperçu des bons tels qu'ils seraient imprimés (aucune imprimante connectée dans cet incrément).", color = Sent, fontSize = 12.sp)
        LazyVerticalGrid(GridCells.Adaptive(260.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(tickets, key = { it.rowId }) { t ->
                Surface(color = if (t.inverse) Color.Black else Color.White, shadowElevation = 2.dp) {
                    Column(Modifier.padding(10.dp)) {
                        Text("→ ${t.destination}", color = if (t.inverse) Color.White else Brand, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(t.renderedText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (t.inverse) Color.White else Color.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalScreen(events: List<JournalEventEntity>, verification: ChainVerification?, onVerify: () -> Unit) {
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onVerify) { Text("Vérifier la chaîne") }
            Spacer(Modifier.width(12.dp))
            when (verification) {
                is ChainVerification.Valid -> Text("Chaîne intègre ✓ (${verification.count} maillons)", color = Brand, fontWeight = FontWeight.Bold)
                is ChainVerification.Broken -> Text("Chaîne ROMPUE au maillon ${verification.seq} : ${verification.problem}", color = Color.Red, fontWeight = FontWeight.Bold)
                null -> Text("Journal append-only : modification et suppression bloquées par la base.", color = Sent)
            }
        }
        LazyColumn(Modifier.padding(top = 8.dp)) {
            items(events, key = { it.seq }) { e ->
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column {
                        Text("#${e.seq} · ${fmt.format(Date(e.occurred_at))} · ${e.type} · ${e.actor_id}" + (e.reason?.let { " · motif : $it" } ?: ""), fontWeight = FontWeight.Medium)
                        Text("hash ${e.hash.take(16)}… ← ${e.prev_hash.take(16)}…", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Sent)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

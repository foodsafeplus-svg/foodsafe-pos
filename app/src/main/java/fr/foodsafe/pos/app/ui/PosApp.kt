package fr.foodsafe.pos.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
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
import fr.foodsafe.pos.core.fiscal.FiscalVerification
import fr.foodsafe.pos.core.journal.ChainVerification
import fr.foodsafe.pos.core.kitchen.KitchenDispatcher
import fr.foodsafe.pos.core.kitchen.KitchenTicketRenderer
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.OrderLine
import fr.foodsafe.pos.core.order.OrderStatus
import fr.foodsafe.pos.core.order.ServiceLevel
import fr.foodsafe.pos.core.payment.PaymentMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- Charte FOODSAFE+
// Esprit « caisse de comptoir » : panneaux sombres, grosses touches bleu clair, tuiles produits colorées,
// moyens de paiement identifiés par couleur. Couleurs et logo propres à FOODSAFE+.

private val FsBlue = Color(0xFF1E4FC8)      // bleu de marque
private val FsSky = Color(0xFF2F9BEF)       // le « + »
private val Navy = Color(0xFF1C2230)        // panneaux de fonctions
private val NavySoft = Color(0xFF2B3346)
private val KeyBlue = Color(0xFFB4CAE8)     // touches du pavé
private val TicketBg = Color(0xFFEDF1F7)    // fond de la note
private val TileGreen = Color(0xFF9EDC8C)
private val TileLavender = Color(0xFFB7C3EA)
private val PayCash = Color(0xFF55C246)
private val PayCard = Color(0xFF2357D9)
private val PayVoucher = Color(0xFFF0922B)
private val Accent = Color(0xFFD9468F)      // icônes de fonctions
private val Brand = FsBlue
private val Muted = Color(0xFF6B7280)
private val Warn = Color(0xFFB45309)

@Composable
fun PosApp(vm: PosViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }

    MaterialTheme(colorScheme = lightColorScheme(primary = FsBlue, secondary = FsSky)) {
        Scaffold { pad ->
            Box(
                Modifier.padding(pad).fillMaxSize().pointerInput(Unit) {
                    // Toute interaction repousse la déconnexion pour inactivité.
                    awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); vm.touch() } }
                },
            ) {
                if (state.user == null) LoginScreen(vm.establishmentName, state.loginError, vm::login) else MainScreen(state, vm)
                // Notifications dans le bandeau du haut, entre les onglets et le nom de l'utilisateur (zone sans bouton) :
                // jamais sur la note, le pavé, les produits ni les moyens de paiement.
                SnackbarHost(snackbar, Modifier.align(Alignment.TopEnd).padding(end = 330.dp).fillMaxWidth(0.3f))
            }
        }
        state.approval?.let { ApprovalDialog(it, vm::approve, vm::dismissApproval) }
        state.receipt?.let { r -> ReceiptDialog(r, vm::dismissReceipt, onGift = r.document?.takeIf { it.type == FiscalDocType.TICKET && !r.gift }?.let { d -> { vm.showGift(d) } }) }
    }
}

@Composable
private fun Wordmark(size: TextUnit, color: Color = Color(0xFF151515)) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append("FOODSAFE") }
            withStyle(SpanStyle(color = FsSky)) { append("+") }
        },
        fontSize = size, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
    )
}

/** Horloge mise à jour hors du thread UI (n'empêche pas les tests Compose d'atteindre l'état « idle »). */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { while (true) { delay(1_000); now = System.currentTimeMillis() } } }
    return now
}

// ---------------------------------------------------------------- Connexion (écran d'accueil)

@Composable
private fun LoginScreen(establishment: String, error: String?, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    val now = rememberNow()
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    val hour = remember { SimpleDateFormat("HH:mm", Locale.FRANCE) }
    val day = remember { SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE) }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        // Vagues décoratives bleu ciel / bleu FOODSAFE+ en bas de l'écran.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            fun wave(top: Float, c1: Float, c2: Float, color: Color) {
                val p = Path().apply {
                    moveTo(0f, h * top)
                    cubicTo(w * 0.3f, h * c1, w * 0.6f, h * c2, w, h * (top - 0.18f))
                    lineTo(w, h); lineTo(0f, h); close()
                }
                drawPath(p, color)
            }
            wave(0.55f, 0.75f, 0.45f, Color(0xFFCDEBF7))
            wave(0.62f, 0.85f, 0.55f, Color(0xFF8FD0EE))
            wave(0.74f, 0.95f, 0.62f, FsBlue)
        }
        Text(establishment.uppercase(), Modifier.align(Alignment.TopCenter).padding(top = 20.dp), fontSize = 26.sp, color = Color(0xFF222222))

        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(bottom = 120.dp)) {
                Wordmark(64.sp)
                Text("ENCAISSEZ ET PILOTEZ VOTRE RESTAURANT SEREINEMENT", fontSize = 15.sp, color = Color(0xFF333333), letterSpacing = 1.sp)
            }
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 8.dp) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Saisissez votre code PIN", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Box(Modifier.padding(vertical = 8.dp).width(250.dp).height(44.dp).clip(RoundedCornerShape(8.dp)).background(TicketBg), contentAlignment = Alignment.Center) {
                        Text("●".repeat(pin.length), fontSize = 24.sp, color = Navy, letterSpacing = 6.sp)
                    }
                    Text(error ?: " ", color = Color(0xFFC62828), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    val keys = listOf("7", "8", "9", "4", "5", "6", "1", "2", "3", "⌫", "0", "OK")
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        keys.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { k ->
                                    PadKey(
                                        k, Modifier.size(78.dp, 58.dp),
                                        color = when (k) { "OK" -> PayCash; "⌫" -> NavySoft; else -> KeyBlue },
                                        textColor = if (k == "OK" || k == "⌫") Color.White else Navy,
                                    ) {
                                        when (k) {
                                            "⌫" -> pin = pin.dropLast(1)
                                            "OK" -> { onPin(pin); pin = "" }
                                            else -> if (pin.length < 8) pin += k
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text("Démo : Marie (serveuse) 1111 · Paul (responsable) 9999", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 20.dp)) {
            Text(hour.format(Date(now)), fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(day.format(Date(now)), fontSize = 15.sp, color = Color.White)
        }
        Surface(Modifier.align(Alignment.BottomEnd).padding(20.dp), shape = RoundedCornerShape(6.dp), color = Color.White) {
            Text("Version $version", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontWeight = FontWeight.SemiBold, color = Navy)
        }
    }
}

@Composable
private fun PadKey(label: String, modifier: Modifier, color: Color = KeyBlue, textColor: Color = Navy, fontSize: TextUnit = 24.sp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(if (enabled) color else color.copy(alpha = 0.4f)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = textColor, textAlign = TextAlign.Center) }
}

// ---------------------------------------------------------------- Structure

@Composable
private fun MainScreen(state: UiState, vm: PosViewModel) {
    Column(Modifier.fillMaxSize().background(Navy)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(20.sp, Color.White)
            Spacer(Modifier.width(16.dp))
            val tabs = listOf(Screen.TABLES to "Tables", Screen.DOCUMENTS to "Tickets", Screen.CLOSURE to "Clôtures", Screen.KITCHEN to "Bons cuisine", Screen.JOURNAL to "Journal")
            tabs.forEach { (screen, label) ->
                val selected = state.screen == screen || (screen == Screen.TABLES && state.screen == Screen.ORDER)
                Box(
                    Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) FsBlue else NavySoft)
                        .clickable { if (screen == Screen.TABLES) vm.backToTables() else vm.show(screen) }.padding(horizontal = 14.dp, vertical = 9.dp),
                ) { Text(label.uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
            Spacer(Modifier.weight(1f))
            Text(vm.establishmentName, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            Spacer(Modifier.width(16.dp))
            Text("${state.user!!.name} · ${roleLabel(state.user)} · T01", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFB23B3B)).clickable { vm.logout() }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text("Déconnexion", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Box(Modifier.fillMaxSize().background(Color.White)) {
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
    Column(Modifier.fillMaxSize().background(TicketBg).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TABLES OUVERTES : ${tables.size}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Navy, modifier = Modifier.weight(1f))
            PadKey("Nouvelle table", Modifier.size(200.dp, 56.dp), color = PayCash, textColor = Color.White, fontSize = 18.sp) { askNumber = true }
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(GridCells.Adaptive(170.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tables, key = { it.orderId }) { t ->
                val hold = t.status == OrderStatus.ON_HOLD
                Column(
                    Modifier.height(110.dp).clip(RoundedCornerShape(10.dp)).background(if (hold) Color(0xFFF7C66B) else TileGreen)
                        .clickable { vm.openTable(t.orderId) }.padding(12.dp),
                ) {
                    Row {
                        Text("TABLE ${t.table}", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Navy, modifier = Modifier.weight(1f))
                        Text(t.total.format(), fontWeight = FontWeight.Bold, color = Navy)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(t.ownerId, color = Navy.copy(alpha = 0.7f), fontSize = 13.sp)
                    Text(statusLabel(t.status).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (hold) Warn else Color(0xFF2E6B22))
                }
            }
        }
        if (tables.isEmpty()) Text("Aucune table ouverte. Touchez « Nouvelle table » pour commencer.", color = Muted)
    }
    if (askNumber) NumberDialog("Numéro de la table", onDismiss = { askNumber = false }) { askNumber = false; vm.newTable(it) }
}

// ---------------------------------------------------------------- Commande : note + pavé à gauche, actions au centre, produits à droite

@Composable
private fun OrderScreen(s: OrderSnapshot, selectedLevel: ServiceLevel?, vm: PosViewModel) {
    var cancelTarget by remember { mutableStateOf<OrderLine?>(null) }
    var showDiscount by remember { mutableStateOf(false) }
    var paying by remember(s.order.id) { mutableStateOf(false) }
    var selectedLineId by remember(s.order.id) { mutableStateOf<String?>(null) }
    val paid = s.order.status == OrderStatus.PAID
    // Ligne visée par « Supprimer ligne » : celle touchée, sinon la dernière ligne active.
    val targetLine = s.order.lines.firstOrNull { it.id == selectedLineId && it.isActive } ?: s.order.activeLines.lastOrNull()

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(0.40f).fillMaxHeight().background(TicketBg)) {
            TicketPanel(s, Modifier.weight(1f), selectedLineId = targetLine?.id, onSelect = { selectedLineId = it })
            if (paid) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.ticket?.let { t -> PadKey("Voir le ticket", Modifier.weight(1f).height(56.dp), color = NavySoft, textColor = Color.White, fontSize = 16.sp) { vm.showDocument(t) } }
                    PadKey("Retour aux tables", Modifier.weight(1f).height(56.dp), color = FsBlue, textColor = Color.White, fontSize = 16.sp) { vm.backToTables() }
                }
            } else if (!paying) {
                SaleKeypad(s, Modifier.height(300.dp), onPay = { paying = true }, vm = vm)
            }
        }
        if (paying && !paid) {
            PaymentPanel(s, Modifier.weight(0.60f), onBack = { paying = false }, onDiscount = { showDiscount = true }, vm = vm)
        } else {
            ActionColumn(s, Modifier.width(118.dp), onCancelLine = { targetLine?.let { cancelTarget = it } }, onDiscount = { showDiscount = true }, vm = vm)
            ProductPanel(paid, selectedLevel, Modifier.weight(0.60f), vm)
        }
    }
    cancelTarget?.let { line -> CancelDialog(line, onDismiss = { cancelTarget = null }) { reason -> vm.cancel(line.id, reason); cancelTarget = null } }
    if (showDiscount) DiscountDialog(onDismiss = { showDiscount = false }) { vm.ticketDiscount(it); showDiscount = false }
}

@Composable
private fun TicketPanel(s: OrderSnapshot, modifier: Modifier, selectedLineId: String?, onSelect: (String) -> Unit) {
    val priced = s.priced
    val paid = s.order.status == OrderStatus.PAID
    val isNew = s.order.activeLines.isEmpty()
    val hour = remember { SimpleDateFormat("HH:mm", Locale.FRANCE) }
    val full = remember { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE) }
    val now = rememberNow()
    Column(modifier.fillMaxWidth().padding(6.dp)) {
        // En-tête : état (blanc = nouvelle, rouge = en cours, vert = soldée), mode de vente, montant et nombre d'articles
        Row(Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(6.dp)).background(Navy)) {
            val stateColor = when { paid -> PayCash; isNew -> Color.White; else -> Color(0xFFD62828) }
            val stateText = if (stateColor == Color.White) Navy else Color.White
            Column(Modifier.width(104.dp).fillMaxHeight().background(stateColor), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("TABLE ${s.order.tableNumber}", color = stateText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text(if (isNew && !paid) "NOUVELLE" else statusLabel(s.order.status).uppercase(), color = stateText, fontSize = 11.sp)
            }
            Column(Modifier.width(84.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("SUR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("PLACE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
                if (isNew) {
                    Text("BIENVENUE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(full.format(Date(now)), color = Color.White, fontSize = 13.sp)
                } else {
                    Text("À PAYER : ${priced.totalTtc.format()}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("NB ART : ${s.order.activeLines.size} · ${s.order.ownerEmployeeId}", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }
        }
        // Lignes : serveur et heure, quantité, libellé, PU, total, état (E = envoyée en cuisine)
        LazyColumn(Modifier.weight(1f).padding(top = 4.dp).background(Color.White)) {
            priced.lines.groupBy { it.line.level.value }.toSortedMap().forEach { (level, lines) ->
                item {
                    val claimed = level in s.order.claimedLevels
                    Text(ServiceLevel(level).label + if (level > 0 && claimed) " · réclamé" else if (level > 1) " · à suivre" else "",
                        fontWeight = FontWeight.Bold, color = FsBlue, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
                }
                items(lines, key = { it.line.id }) { pl ->
                    val l = pl.line
                    Row(
                        Modifier.fillMaxWidth().background(if (l.id == selectedLineId && !paid) Color(0xFFDDE3EC) else Color.White)
                            .clickable(enabled = !paid) { onSelect(l.id) }.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.width(44.dp)) {
                            Text(l.authorEmployeeId.uppercase().take(6), fontSize = 8.sp, color = Muted, maxLines = 1)
                            Text(hour.format(Date(l.createdAt)), fontSize = 8.sp, color = Muted)
                        }
                        Text(KitchenTicketRenderer.formatQty(l.quantityMilli), Modifier.width(22.dp), fontWeight = FontWeight.Bold)
                        Text(l.label, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1)
                        Text("PU ${l.unitPriceTtc.format(false)}", Modifier.width(62.dp), fontSize = 11.sp, color = Muted)
                        if (pl.gross != pl.net) Text(pl.gross.format(false) + " ", textDecoration = TextDecoration.LineThrough, color = Muted, fontSize = 11.sp)
                        Text(pl.net.format(false), Modifier.width(50.dp), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                        Text(if (l.isSent) "E" else "", Modifier.width(16.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E9E4F), textAlign = TextAlign.End)
                    }
                    HorizontalDivider(color = TicketBg)
                }
            }
            val cancelled = s.order.lines.filter { !it.isActive }
            if (cancelled.isNotEmpty()) item {
                Text("Annulées", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
                cancelled.forEach { Text("${it.label} · motif : ${it.cancellation?.reason}", color = Muted, textDecoration = TextDecoration.LineThrough, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp)) }
            }
        }
        // Pied : poste et serveur à gauche, totaux à droite
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("T01", fontSize = 11.sp, color = Muted)
                Text(s.order.ownerEmployeeId.uppercase(), fontSize = 11.sp, color = Muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("SOUS-TOTAL : ${(priced.totalTtc + priced.totalDiscount).format(false)}   DONT REMISE : ${priced.totalDiscount.format(false)}", fontSize = 10.sp, color = Muted)
                Text("NET À PAYER : ${priced.totalTtc.format()}", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Navy)
                Text(priced.taxes.byRate.joinToString("   ") { "TVA ${it.rate.format()} : ${it.vat.format(false)}" }, fontSize = 10.sp, color = Muted)
                if (s.payments.isNotEmpty() && !paid) Text("Reste à payer ${s.remaining.format()}", color = FsBlue, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Sous la note : colonne noire (navigation) et pavé avec raccourcis de paiement.
 * Montant tapé puis ESP / CB = règlement immédiat de ce montant (vide = reste dû) ; 10 € / 20 € = billet remis en espèces.
 */
@Composable
private fun SaleKeypad(s: OrderSnapshot, modifier: Modifier, onPay: () -> Unit, vm: PosViewModel) {
    val rest = s.remaining
    val canPay = s.priced.lines.isNotEmpty() && rest.value > 0
    var entry by remember(rest) { mutableStateOf("") }
    val amount = parseAmount(entry) ?: rest
    Row(modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.width(104.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Navy).padding(vertical = 4.dp)) {
            FunctionButton("≡", Color(0xFFF5B800), "Tables", onClick = vm::backToTables)
            Spacer(Modifier.weight(1f))
            FunctionButton("⏸", Color(0xFF8A94A8), "Mettre en attente", onClick = vm::backToTables)
            FunctionButton("←", Color(0xFFB23B3B), "Quitter", onClick = { vm.logout() })
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.weight(0.8f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.weight(3f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(entry.ifEmpty { " " }, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                }
                PadKey("⌫", Modifier.weight(1f).fillMaxHeight(), fontSize = 18.sp) { entry = entry.dropLast(1) }
            }
            listOf(listOf("7", "8", "9"), listOf("4", "5", "6"), listOf("1", "2", "3"), listOf("C", "0", ",")).forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { k -> PadKey(k, Modifier.weight(1f).fillMaxHeight(), fontSize = 20.sp) { entry = if (k == "C") "" else typeAmount(entry, k) } }
                }
            }
            Row(Modifier.weight(1.4f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    PadKey("10 €", Modifier.weight(1f).fillMaxWidth(), color = PayCash, textColor = Color.White, fontSize = 14.sp, enabled = canPay) { vm.pay(PaymentMethod.CASH, Cents(1000)) }
                    PadKey("20 €", Modifier.weight(1f).fillMaxWidth(), color = PayCash, textColor = Color.White, fontSize = 14.sp, enabled = canPay) { vm.pay(PaymentMethod.CASH, Cents(2000)) }
                }
                PadKey("ESP", Modifier.weight(1f).fillMaxHeight(), color = PayCash, textColor = Color.White, fontSize = 18.sp, enabled = canPay) { vm.pay(PaymentMethod.CASH, amount); entry = "" }
                PadKey("CB", Modifier.weight(1f).fillMaxHeight(), color = PayCard, textColor = Color.White, fontSize = 18.sp, enabled = canPay && amount <= rest) { vm.pay(PaymentMethod.CARD, amount); entry = "" }
                PadKey("ENCAISSER", Modifier.weight(1.3f).fillMaxHeight(), color = Color(0xFF3E9E33), textColor = Color.White, fontSize = 14.sp, enabled = canPay, onClick = onPay)
            }
        }
    }
}

private fun parseAmount(entry: String): Cents? =
    entry.takeIf { it.isNotBlank() }?.replace(',', '.')?.toBigDecimalOrNull()?.movePointRight(2)?.toLong()?.takeIf { it > 0 }?.let(::Cents)

private fun typeAmount(entry: String, k: String): String = when {
    k == "," && entry.contains(',') -> entry
    k == "," -> entry.ifEmpty { "0" } + ","
    entry.contains(',') && entry.substringAfter(',').length >= 2 -> entry
    entry.length >= 7 -> entry
    else -> entry + k
}

/** Colonne noire d'actions sur la note. Seules les actions réellement disponibles y figurent. */
@Composable
private fun ActionColumn(s: OrderSnapshot, modifier: Modifier, onCancelLine: () -> Unit, onDiscount: () -> Unit, vm: PosViewModel) {
    val paid = s.order.status == OrderStatus.PAID
    val hasLines = s.order.activeLines.isNotEmpty()
    val nextClaim = KitchenDispatcher.nextClaimableLevel(s.order)
    Column(modifier.fillMaxHeight().background(Navy).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FunctionButton("➜", Color(0xFF2E9E4F), "Envoyer commande", enabled = !paid, onClick = vm::send)
        if (nextClaim != null && !paid) FunctionButton("N", Color(0xFF7B4FD6), "Réclamer ${nextClaim.label}", onClick = vm::claim)
        FunctionButton("−", Accent, "Supprimer ligne", enabled = !paid && hasLines, onClick = onCancelLine)
        FunctionButton("%", Color(0xFF1FB5A8), "Remise note %", enabled = !paid, onClick = onDiscount)
        FunctionButton("≡", Color(0xFFF08A30), "Imprimer la note", enabled = !paid && hasLines, onClick = vm::printNote)
    }
}

@Composable
private fun FunctionButton(icon: String, iconColor: Color, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 7.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(if (enabled) iconColor else iconColor.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
            Text(icon, color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Text(label.uppercase(), color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f), fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Couleurs de tuiles par famille : deux teintes alternées par rangée (comme petite / grande taille). */
private val FamilyTiles = mapOf(
    "Entrées" to (Color(0xFF9EDC8C) to Color(0xFFC8CCD2)),
    "Pizzas" to (Color(0xFF9EDC8C) to Color(0xFFB7C3EA)),
    "Pâtes" to (Color(0xFFD9A066) to Color(0xFFE8C88E)),
    "Desserts" to (Color(0xFFFFB6C6) to Color(0xFFF7D2DB)),
    "Glaces" to (Color(0xFFC98A6A) to Color(0xFFFFC6D3)),
    "Softs" to (Color(0xFF7FD8E8) to Color(0xFFB9E6EE)),
    "Alcools" to (Color(0xFFF2A07E) to Color(0xFFB8C9A0)),
    "Cafés" to (Color(0xFFE8C832) to Color(0xFFF1DC7A)),
)

@Composable
private fun ProductPanel(paid: Boolean, selectedLevel: ServiceLevel?, modifier: Modifier, vm: PosViewModel) {
    var category by remember { mutableStateOf(DemoCatalog.categories.first()) }
    Column(modifier.fillMaxHeight().background(Color.White).padding(6.dp)) {
        // Familles : deux rangées de quatre onglets, gris et noir en alternance, l'onglet actif en bleu
        DemoCatalog.categories.chunked(4).forEachIndexed { r, row ->
            Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { i, c ->
                    val base = if ((r + i) % 2 == 0) Color(0xFF6E737C) else Color(0xFF111418)
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(4.dp)).background(if (c == category) FsBlue else base).clickable { category = c },
                        contentAlignment = Alignment.Center,
                    ) { Text(c.uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        // Niveau d'envoi des prochains articles
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            LevelChip("Niveau produit", selectedLevel == null) { vm.selectLevel(null) }
            (0..3).forEach { l -> ServiceLevel(l).let { lvl -> LevelChip(lvl.label, selectedLevel == lvl) { vm.selectLevel(lvl) } } }
        }
        val products = DemoCatalog.products.filter { it.category == category }
        val (c1, c2) = FamilyTiles[category] ?: (TileGreen to TileLavender)
        // Grille fixe de 5 colonnes ; les cases vides restent visibles pour garder des repères stables.
        val cells = products + List((5 - products.size % 5) % 5 + if (products.size < 10) 5 else 0) { null }
        LazyVerticalGrid(GridCells.Fixed(5), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(cells.size) { idx ->
                val p = cells[idx]
                if (p == null) {
                    Box(Modifier.height(82.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFF0F1F3)))
                } else {
                    val tile = if ((idx / 5) % 2 == 0) c1 else c2
                    Box(
                        Modifier.height(82.dp).clip(RoundedCornerShape(4.dp)).background(if (paid) tile.copy(alpha = 0.4f) else tile)
                            .clickable(enabled = !paid) { vm.add(p) }.padding(5.dp),
                    ) {
                        Text(p.price.format(false), Modifier.align(Alignment.TopEnd), fontSize = 12.sp, color = Navy)
                        Text(p.label, Modifier.align(Alignment.BottomCenter), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Navy, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) FsBlue else TicketBg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = if (selected) Color.White else Navy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

// ---------------------------------------------------------------- Encaissement (remplace actions et produits, la note reste visible)

@Composable
private fun PaymentPanel(s: OrderSnapshot, modifier: Modifier, onBack: () -> Unit, onDiscount: () -> Unit, vm: PosViewModel) {
    val rest = s.remaining
    var entry by remember(rest) { mutableStateOf("") }
    val amount: Cents? = if (entry.isBlank()) rest else parseAmount(entry)
    val totalReceived = Cents(s.payments.sumOf { it.tendered.value })

    Column(modifier.fillMaxHeight().background(Color(0xFFDCE4F0)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            PadKey("← Retour", Modifier.size(120.dp, 48.dp), color = NavySoft, textColor = Color.White, fontSize = 16.sp, onClick = onBack)
            Text("ENCAISSEMENT TABLE ${s.order.tableNumber}", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
            Spacer(Modifier.width(120.dp))
        }
        Row(Modifier.fillMaxWidth().height(150.dp).padding(vertical = 6.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Navy).padding(12.dp)) {
                Text("RESTE À PAYER", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text(rest.format(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 34.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.weight(1f))
                Text("TOTAL REÇU : ${totalReceived.format()}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1.2f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp)) {
                Row { listOf("TYPE" to 1.4f, "MONTANT" to 1f, "REÇU" to 1f, "RENDU" to 1f).forEach { (h, w) -> Text(h, Modifier.weight(w), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Muted) } }
                HorizontalDivider()
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    s.payments.forEach { p ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(p.method.label, Modifier.weight(1.4f), fontSize = 13.sp)
                            Text(p.amount.format(false), Modifier.weight(1f), fontSize = 13.sp)
                            Text(p.tendered.format(false), Modifier.weight(1f), fontSize = 13.sp)
                            Text(p.change.format(false), Modifier.weight(1f), fontSize = 13.sp, fontWeight = if (p.change.isZero) FontWeight.Normal else FontWeight.Bold)
                        }
                    }
                    if (s.payments.isEmpty()) Text("Aucun règlement", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(118.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Navy).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FunctionButton("%", Color(0xFF1FB5A8), "Remise note %", onClick = onDiscount)
                FunctionButton("≡", Color(0xFFF08A30), "Imprimer la note", onClick = vm::printNote)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(3f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterEnd) {
                        Text(if (entry.isBlank()) rest.format(false) else entry, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (entry.isBlank()) Muted else Navy)
                    }
                    PadKey("⌫", Modifier.weight(1f).fillMaxHeight()) { entry = entry.dropLast(1) }
                }
                PadRow(listOf("10", "20", "50", "100")) { entry = it }
                PadRow(listOf("7", "8", "9", "C")) { entry = if (it == "C") "" else typeAmount(entry, it) }
                PadRow(listOf("4", "5", "6", ",")) { entry = typeAmount(entry, it) }
                PadRow(listOf("1", "2", "3", "0")) { entry = typeAmount(entry, it) }
            }
            Spacer(Modifier.width(8.dp))
            // Six moyens de paiement, chacun sa couleur
            Column(Modifier.width(176.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val ok = amount != null
                val notAbove = ok && amount!! <= rest
                PayButton("ESPÈCES", PayCash, ok) { vm.pay(PaymentMethod.CASH, amount!!); entry = "" }
                PayButton("CARTE", PayCard, notAbove) { vm.pay(PaymentMethod.CARD, amount!!); entry = "" }
                PayButton("CHÈQUE", Color(0xFFE5C100), notAbove) { vm.pay(PaymentMethod.CHEQUE, amount!!); entry = "" }
                PayButton("TITRE-RESTO", PayVoucher, notAbove) { vm.pay(PaymentMethod.MEAL_VOUCHER, amount!!); entry = "" }
                PayButton("CHÈQUE-VACANCES", Color(0xFFE0603A), notAbove) { vm.pay(PaymentMethod.HOLIDAY_VOUCHER, amount!!); entry = "" }
                Text("Carte : montant validé sur le TPE autonome.", fontSize = 10.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PadRow(keys: List<String>, onKey: (String) -> Unit) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.forEach { k -> PadKey(k, Modifier.weight(1f).fillMaxHeight(), fontSize = 24.sp) { onKey(k) } }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PayButton(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    PadKey(label, Modifier.fillMaxWidth().weight(1f), color = color, textColor = Color.White, fontSize = 16.sp, enabled = enabled, onClick = onClick)
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

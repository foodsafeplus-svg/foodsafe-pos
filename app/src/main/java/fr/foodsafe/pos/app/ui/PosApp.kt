package fr.foodsafe.pos.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import fr.foodsafe.pos.app.data.KeyboardCell
import fr.foodsafe.pos.app.data.JournalEventEntity
import fr.foodsafe.pos.app.data.KitchenTicketEntity
import fr.foodsafe.pos.app.data.OrderSnapshot
import fr.foodsafe.pos.app.data.PosRepository
import fr.foodsafe.pos.app.data.Product
import fr.foodsafe.pos.app.data.tariffs
import fr.foodsafe.pos.core.order.PromoEngine
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

internal val FsBlue = Color(0xFF1E4FC8)      // bleu de marque
internal val FsSky = Color(0xFF2F9BEF)       // le « + »
internal val Navy = Color(0xFF1C2230)        // panneaux de fonctions
internal val NavySoft = Color(0xFF2B3346)
internal val KeyBlue = Color(0xFFB4CAE8)     // touches du pavé
internal val TicketBg = Color(0xFFEDF1F7)    // fond de la note
internal val TileGreen = Color(0xFF9EDC8C)
internal val TileLavender = Color(0xFFB7C3EA)
internal val PayCash = Color(0xFF55C246)
internal val PayCard = Color(0xFF2357D9)
internal val PayVoucher = Color(0xFFF0922B)
internal val Accent = Color(0xFFD9468F)      // icônes de fonctions
internal val Brand = FsBlue
internal val Muted = Color(0xFF6B7280)
internal val Warn = Color(0xFFB45309)

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
                SnackbarHost(snackbar, Modifier.align(Alignment.TopEnd).padding(end = 300.dp).fillMaxWidth(0.2f))
            }
        }
        state.approval?.let { ApprovalDialog(it, vm::approve, vm::dismissApproval) }
        state.receipt?.let { r -> ReceiptDialog(r, vm::dismissReceipt, onGift = r.document?.takeIf { it.type == FiscalDocType.TICKET && !r.gift }?.let { d -> { vm.showGift(d) } }) }
    }
}

@Composable
internal fun Wordmark(size: TextUnit, color: Color = Color(0xFF151515)) {
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
        Row(Modifier.align(Alignment.BottomEnd).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                Modifier.clickable { ctx.startActivity(android.content.Intent(ctx, fr.foodsafe.pos.app.hardware.HardwareTestActivity::class.java)) },
                shape = RoundedCornerShape(6.dp), color = Color.White,
            ) {
                Text("Test matériel", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontWeight = FontWeight.SemiBold, color = Navy)
            }
            Surface(shape = RoundedCornerShape(6.dp), color = Color.White) {
                Text("Version $version", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontWeight = FontWeight.SemiBold, color = Navy)
            }
        }
    }
}

@Composable
internal fun PadKey(label: String, modifier: Modifier, color: Color = KeyBlue, textColor: Color = Navy, fontSize: TextUnit = 24.sp, enabled: Boolean = true, onClick: () -> Unit) {
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
            val tabs = listOf(Screen.HOME to "Accueil", Screen.TABLES to "Tables", Screen.DOCUMENTS to "Tickets", Screen.CLOSURE to "Clôtures", Screen.KITCHEN to "Bons cuisine", Screen.JOURNAL to "Journal")
            tabs.forEach { (screen, label) ->
                val selected = state.screen == screen || (screen == Screen.TABLES && state.screen == Screen.ORDER) ||
                    (screen == Screen.HOME && state.screen in setOf(Screen.CASH_COUNT, Screen.CALCULATOR, Screen.CATALOG, Screen.KEYBOARDS, Screen.PRODUCTS, Screen.IMAGES, Screen.WIZARD))
                Box(
                    Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) FsBlue else NavySoft)
                        .clickable { if (screen == Screen.TABLES) vm.backToTables() else vm.show(screen) }.padding(horizontal = 11.dp, vertical = 9.dp),
                ) { Text(label.uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
            Spacer(Modifier.weight(1f))
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
                Screen.HOME -> HomeMenuScreen(vm)
                Screen.CASH_COUNT -> CashCountScreen(vm)
                Screen.CALCULATOR -> CalculatorScreen(vm)
                Screen.CATALOG -> CatalogHubScreen(vm)
                Screen.KEYBOARDS -> KeyboardsScreen(vm)
                Screen.PRODUCTS -> ProductsScreen(vm)
                Screen.IMAGES -> ImagesScreen(vm)
                Screen.WIZARD -> ProductWizardScreen(vm)
                Screen.MODIFIERS -> ModifiersScreen(vm)
                Screen.FORMULAS -> FormulasScreen(vm)
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
    var commentLine by remember { mutableStateOf<OrderLine?>(null) }
    var promoLine by remember { mutableStateOf<OrderLine?>(null) }
    val catalog by vm.catalog.collectAsStateWithLifecycle()
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
            // « Commentaire » : sur la ligne produit visée (ou la ligne parente d'un commentaire), tant qu'elle n'est pas envoyée
            val commentTarget = targetLine?.let { t -> if (t.isModifier) s.order.lines.firstOrNull { it.id == t.parentLineId } else t }?.takeIf { !it.isSent && it.isActive }
            ActionColumn(s, Modifier.width(118.dp), onCancelLine = {
                // un article de formule s'annule avec sa formule
                targetLine?.let { t -> cancelTarget = if (t.isComponent) s.order.lines.firstOrNull { it.id == t.parentLineId } ?: t else t }
            }, onDiscount = { showDiscount = true },
                onComment = commentTarget?.let { t -> { commentLine = t } },
                onRemovePromo = targetLine?.takeIf { it.promoGroup != null && it.promoGroup != PromoEngine.REFUSED }?.let { t -> { promoLine = t } }, vm = vm)
            ProductPanel(paid, selectedLevel, Modifier.weight(0.60f), vm)
        }
    }
    cancelTarget?.let { line -> CancelDialog(line, onDismiss = { cancelTarget = null }) { reason -> vm.cancel(line.id, reason); cancelTarget = null } }
    commentLine?.let { line ->
        // groupes du produit, sinon tous les groupes (commentaire libre choisi dans la liste)
        val groups = catalog?.groupsFor(line.productId).orEmpty().ifEmpty { catalog?.groups.orEmpty() }
        ModifierDialog(line.label, groups.map { it.copy(mandatory = false) }, onDismiss = { commentLine = null }) { vm.addModifiers(line.id, it); commentLine = null }
    }
    promoLine?.let { line ->
        PromoRemoveDialog(line, onDismiss = { promoLine = null }) { reason -> vm.removePromo(line.id, reason); promoLine = null }
    }
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
                    Text("NB ART : ${s.order.activeLines.count { !it.isModifier && !it.isFormulaHeader }} · ${s.order.ownerEmployeeId}", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
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
                    // en-tête de formule : montant = somme de ses composants (ventilés) ; composants affichés sans prix
                    val formulaTotal = if (l.isFormulaHeader) Cents(priced.lines.filter { it.line.parentLineId == l.id }.sumOf { it.net.value }) else null
                    val hidePrice = l.isComponent || (l.isModifier && pl.gross.isZero)
                    Row(
                        Modifier.fillMaxWidth().background(if (l.id == selectedLineId && !paid) Color(0xFFDDE3EC) else Color.White)
                            .clickable(enabled = !paid) { onSelect(l.id) }.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.width(44.dp)) {
                            Text(l.authorEmployeeId.uppercase().take(6), fontSize = 8.sp, color = Muted, maxLines = 1)
                            Text(hour.format(Date(l.createdAt)), fontSize = 8.sp, color = Muted)
                        }
                        if (l.isModifier || l.isComponent) {
                            Text("    > ${l.label}" + if (l.isComponent && !l.level.isDirect) "  (${l.level.label})" else "", Modifier.weight(1f).padding(start = 22.dp),
                                fontSize = 13.sp, color = if (l.isComponent) Navy else FsBlue, maxLines = 1)
                            Text(if (hidePrice) "" else "PU ${l.unitPriceTtc.format(false)}", Modifier.width(62.dp), fontSize = 11.sp, color = Muted)
                        } else {
                            Text(KitchenTicketRenderer.formatQty(l.quantityMilli), Modifier.width(22.dp), fontWeight = FontWeight.Bold)
                            Text(l.label, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, fontWeight = if (l.isFormulaHeader) FontWeight.Bold else FontWeight.Normal)
                            if (l.promoGroup != null && l.promoGroup != PromoEngine.REFUSED) Text("PROMO ", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E9E4F))
                            Text("PU ${(formulaTotal ?: l.unitPriceTtc).format(false)}", Modifier.width(62.dp), fontSize = 11.sp, color = Muted)
                        }
                        if (pl.gross != pl.net && !hidePrice) Text(pl.gross.format(false) + " ", textDecoration = TextDecoration.LineThrough, color = Muted, fontSize = 11.sp)
                        Text(if (hidePrice) "" else (formulaTotal ?: pl.net).format(false), Modifier.width(50.dp), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
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
private fun ActionColumn(s: OrderSnapshot, modifier: Modifier, onCancelLine: () -> Unit, onDiscount: () -> Unit, onComment: (() -> Unit)?, onRemovePromo: (() -> Unit)? = null, vm: PosViewModel) {
    val paid = s.order.status == OrderStatus.PAID
    val hasLines = s.order.activeLines.isNotEmpty()
    val nextClaim = KitchenDispatcher.nextClaimableLevel(s.order)
    Column(modifier.fillMaxHeight().background(Navy).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FunctionButton("➜", Color(0xFF2E9E4F), "Envoyer commande", enabled = !paid, onClick = vm::send)
        if (nextClaim != null && !paid) FunctionButton("N", Color(0xFF7B4FD6), "Réclamer ${nextClaim.label}", onClick = vm::claim)
        FunctionButton("−", Accent, "Supprimer ligne", enabled = !paid && hasLines, onClick = onCancelLine)
        FunctionButton("✎", Color(0xFF2F55C8), "Commentaire", enabled = !paid && onComment != null, onClick = { onComment?.invoke() })
        if (onRemovePromo != null && !paid) FunctionButton("P", Color(0xFF2E9E4F), "Retirer promo", onClick = onRemovePromo)
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
internal val FamilyTiles = mapOf(
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
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val keyboardId by vm.keyboardId.collectAsStateWithLifecycle()
    val pageId by vm.page.collectAsStateWithLifecycle()
    val keyboard = catalog?.keyboard(keyboardId)
    val tabs = keyboard?.visiblePages.orEmpty()
    val page = keyboard?.pages?.firstOrNull { it.id == pageId } ?: tabs.firstOrNull()
    var asking by remember { mutableStateOf<Product?>(null) }
    var askingFormula by remember { mutableStateOf<fr.foodsafe.pos.app.data.Formula?>(null) }
    // appui long : menu de la touche, fiche produit, déplacement (page, case)
    var menuFor by remember { mutableStateOf<Pair<String, KeyboardCell>?>(null) }
    var editing by remember { mutableStateOf<Product?>(null) }
    // prix libre : produit en attente de son prix
    var pricing by remember { mutableStateOf<Pair<Product, List<fr.foodsafe.pos.app.data.ModifierItem>>?>(null) }
    pricing?.let { (p, mods) ->
        PriceDialog("Prix de « ${p.label} »", onDismiss = { pricing = null }) { v ->
            v.replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 2 }?.movePointRight(2)?.toLong()?.let { c ->
                vm.add(p.copy(price = Cents(c)), mods); pricing = null
            } ?: vm.notify("Prix invalide")
        }
    }
    var moving by remember { mutableStateOf<Pair<String, KeyboardCell>?>(null) }
    menuFor?.let { (pg, cell) ->
        KeyMenuDialog(cell, onDismiss = { menuFor = null },
            onEdit = (cell as? KeyboardCell.ProductCell)?.let { pc -> { editing = pc.product; menuFor = null } },
            onMove = { moving = pg to cell; menuFor = null },
            onFree = { vm.clearCell(pg, cell.slot); menuFor = null },
            onTariff = { t -> vm.setCellTariff(pg, cell.slot, t); menuFor = null })
    }
    editing?.let { p ->
        ProductDialog(p, catalog?.categories.orEmpty(), isNew = false,
            onSave = { vm.saveProduct(it); editing = null }, onDismiss = { editing = null }, images = catalog?.images.orEmpty(),
            groups = catalog?.groups.orEmpty(), initialGroups = catalog?.productGroups?.get(p.id).orEmpty(),
            onSaveGroups = { vm.setProductGroups(p.id, it) })
    }
    askingFormula?.let { f ->
        FormulaDialog(f, catalog?.products.orEmpty(), onDismiss = { askingFormula = null }) { vm.addFormula(f, it); askingFormula = null }
    }
    asking?.let { p ->
        ModifierDialog(p.label, catalog?.groupsFor(p.id).orEmpty(), onDismiss = { asking = null }) {
            if (p.openPrice) pricing = p to it else vm.add(p, it); asking = null
        }
    }
    Column(modifier.fillMaxHeight().background(Color.White).padding(6.dp)) {
        // Pages visibles : rangées de quatre onglets, gris et noir en alternance, l'onglet actif en bleu
        tabs.chunked(4).forEachIndexed { r, row ->
            Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { i, pg ->
                    val base = if ((r + i) % 2 == 0) Color(0xFF6E737C) else Color(0xFF111418)
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(4.dp)).background(if (pg.id == page?.id) FsBlue else base).clickable { vm.selectTab(pg.id) },
                        contentAlignment = Alignment.Center,
                    ) { Text(pg.name.uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        // Niveau d'envoi des prochains articles (et retour depuis une page cachée)
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != null && !page.visible) {
                PadKey("← ${page.name}", Modifier.height(32.dp).width(170.dp), color = NavySoft, textColor = Color.White, fontSize = 13.sp) { vm.pageBack() }
            }
            LevelChip("Niveau produit", selectedLevel == null) { vm.selectLevel(null) }
            (0..3).forEach { l -> ServiceLevel(l).let { lvl -> LevelChip(lvl.label, selectedLevel == lvl) { vm.selectLevel(lvl) } } }
        }
        moving?.let { (fromPage, cell) ->
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFFFE9A8)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Déplacement de « ${cell.title()} » : touchez la case d'arrivée (une touche occupée est échangée). Changez d'onglet pour une autre page.",
                    Modifier.weight(1f), fontSize = 13.sp, color = Navy)
                PadKey("Annuler", Modifier.size(110.dp, 38.dp), color = NavySoft, textColor = Color.White, fontSize = 14.sp) { moving = null }
            }
        }
        val cells = page?.let { keyboard?.cellsOf(it.id) }.orEmpty()
        val (c1, c2) = FamilyTiles[page?.name] ?: (TileGreen to TileLavender)
        val mv = moving
        KeyboardGrid(cells, c1, c2, dimmed = paid, images = catalog?.images.orEmpty().mapNotNull { i -> i.file?.let { i.id to it } }.toMap(),
            showEmpty = mv != null,
            onEmpty = { slot -> if (mv != null && page != null) { vm.moveCell(mv.first, mv.second.slot, page.id, slot); moving = null } },
            onLongCell = { cell ->
                if (mv == null && page != null) {
                    if (vm.canEditCatalog) menuFor = page.id to cell else vm.notify("Modifier ou déplacer une touche : réservé au responsable")
                }
            },
            highlightSlot = mv?.takeIf { it.first == page?.id }?.second?.slot,
        ) { cell ->
            if (mv != null) {
                if (page != null) { vm.moveCell(mv.first, mv.second.slot, page.id, cell.slot); moving = null }
            } else if (!paid) when (cell) {
                is KeyboardCell.ProductCell -> {
                    val sold = cell.product.copy(price = cell.price)
                    when {
                        !catalog?.groupsFor(sold.id).isNullOrEmpty() -> asking = sold
                        sold.openPrice -> pricing = sold to emptyList()
                        else -> vm.add(sold)
                    }
                }
                is KeyboardCell.PageLink -> vm.openPage(cell.page.id)
                is KeyboardCell.FormulaCell -> {
                    val byId = catalog?.products.orEmpty().associateBy { it.id }
                    if (cell.formula.isFixed) vm.addFormula(cell.formula, cell.formula.steps.mapNotNull { byId[it.productIds.first()] })
                    else askingFormula = cell.formula
                }
            }
        }
    }
}

/** Grille de 5 colonnes : cases vides visibles pour garder des repères stables, lignes de teintes alternées. */
@Composable
internal fun KeyboardGrid(
    cells: Map<Int, KeyboardCell>, c1: Color, c2: Color, dimmed: Boolean = false, showEmpty: Boolean = false,
    images: Map<String, java.io.File> = emptyMap(),
    onEmpty: (Int) -> Unit = {},
    /** Appui long sur une touche (modifier, déplacer). */
    onLongCell: ((KeyboardCell) -> Unit)? = null,
    /** Touche en cours de déplacement, encadrée. */
    highlightSlot: Int? = null,
    onCell: (KeyboardCell) -> Unit,
) {
    val last = cells.keys.maxOrNull() ?: -1
    val count = maxOf(10, ((last + 1 + 4) / 5) * 5 + if (showEmpty) 5 else 0)
    LazyVerticalGrid(GridCells.Fixed(5), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(count) { idx ->
            val cell = cells[idx]
            if (cell == null) {
                Box(
                    Modifier.height(82.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFF0F1F3)).clickable(enabled = showEmpty) { onEmpty(idx) },
                    contentAlignment = Alignment.Center,
                ) { if (showEmpty) Text("+", fontSize = 26.sp, color = Muted) }
            } else {
                val base = cell.color?.let { Color(it) } ?: when {
                    cell is KeyboardCell.PageLink -> Color(0xFF3D4A63)
                    cell is KeyboardCell.FormulaCell -> Color(0xFF2E7D4F)
                    (idx / 5) % 2 == 0 -> c1
                    else -> c2
                }
                val fg = if (cell !is KeyboardCell.ProductCell && cell.color == null) Color.White else Navy
                Box(
                    Modifier.height(82.dp).clip(RoundedCornerShape(4.dp)).background(if (dimmed) base.copy(alpha = 0.4f) else base)
                        .then(if (idx == highlightSlot) Modifier.border(3.dp, Color(0xFFD62828), RoundedCornerShape(4.dp)) else Modifier)
                        .combinedClickable(onClick = { onCell(cell) }, onLongClick = onLongCell?.let { f -> { f(cell) } }).padding(5.dp),
                ) {
                    when (cell) {
                        is KeyboardCell.ProductCell -> {
                            cell.product.imageId?.let(images::get)?.let { f -> ImageThumb(f, Modifier.align(Alignment.TopCenter).size(40.dp)) }
                            Text(cell.price.format(false), Modifier.align(Alignment.TopEnd), fontSize = 12.sp, color = fg)
                            cell.tariff?.let { t -> Text("T$t", Modifier.align(Alignment.TopStart), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg) }
                            Text(cell.product.label, Modifier.align(Alignment.BottomCenter), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = fg, textAlign = TextAlign.Center, maxLines = 2)
                        }
                        is KeyboardCell.FormulaCell -> {
                            Text(cell.formula.price.format(false), Modifier.align(Alignment.TopEnd), fontSize = 12.sp, color = fg)
                            Text("FORMULE", Modifier.align(Alignment.TopStart), fontSize = 9.sp, color = fg, fontWeight = FontWeight.Bold)
                            Text(cell.formula.name, Modifier.align(Alignment.BottomCenter), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = fg, textAlign = TextAlign.Center, maxLines = 2)
                        }
                        is KeyboardCell.PageLink -> {
                            Text("→", Modifier.align(Alignment.TopEnd), fontSize = 16.sp, color = fg, fontWeight = FontWeight.Bold)
                            Text(cell.page.name, Modifier.align(Alignment.BottomCenter), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = fg, textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

internal fun KeyboardCell.title() = when (this) {
    is KeyboardCell.ProductCell -> product.label
    is KeyboardCell.PageLink -> page.name
    is KeyboardCell.FormulaCell -> formula.name
}

@Composable
private fun KeyMenuDialog(cell: KeyboardCell, onDismiss: () -> Unit, onEdit: (() -> Unit)?, onMove: () -> Unit, onFree: () -> Unit, onTariff: ((Int?) -> Unit)? = null) {
    var askTariff by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(cell.title()) },
        text = {
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onEdit != null) PadKey("Modifier le produit", Modifier.fillMaxWidth().height(56.dp), color = FsBlue, textColor = Color.White, fontSize = 17.sp, onClick = onEdit)
                PadKey("Déplacer la touche", Modifier.fillMaxWidth().height(56.dp), color = NavySoft, textColor = Color.White, fontSize = 17.sp, onClick = onMove)
                PadKey("Libérer l'emplacement", Modifier.fillMaxWidth().height(56.dp), color = Color(0xFF2E9E4F), textColor = Color.White, fontSize = 17.sp, onClick = onFree)
                if (onTariff != null && cell is KeyboardCell.ProductCell) {
                    PadKey("Fixer tarification", Modifier.fillMaxWidth().height(56.dp), color = Color(0xFFC62828), textColor = Color.White, fontSize = 17.sp) { askTariff = !askTariff }
                    if (askTariff) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        cell.product.tariffs.forEach { (t, p) ->
                            Chip((if (t == 0) "Normal" else "Tarif $t") + " " + p.format(false), (cell.tariff ?: 0) == t) { onTariff(t.takeIf { it > 0 }) }
                        }
                    }
                    if (askTariff && cell.product.tariffs.size == 1) Text("Ce produit n'a qu'un tarif : renseignez Prix 1 ou Prix 2 dans sa fiche.", fontSize = 12.sp, color = Muted)
                }
                Text("Libérer retire la touche de cette page ; le produit reste au catalogue.", fontSize = 12.sp, color = Muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
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
                Text("Aperçu du document (80 mm). Il est imprimé à son émission si une imprimante est reliée.", color = Muted, fontSize = 11.sp)
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
private fun PriceDialog(title: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(v, { s -> v = s.filter { it.isDigit() || it == ',' || it == '.' }.take(8) }, singleLine = true, label = { Text("Prix TTC (€)") }) },
        confirmButton = { Button(onClick = { onOk(v) }, enabled = v.isNotBlank()) { Text("Valider") } },
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

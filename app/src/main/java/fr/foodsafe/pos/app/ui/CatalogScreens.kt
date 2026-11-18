package fr.foodsafe.pos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.foodsafe.pos.app.data.KeyboardCell
import fr.foodsafe.pos.app.data.Product
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.ServiceLevel
import fr.foodsafe.pos.core.tax.FrenchVat

internal val VatChoices = listOf(FrenchVat.SUPER_REDUCED, FrenchVat.REDUCED, FrenchVat.INTERMEDIATE, FrenchVat.NORMAL)
internal val CellColors = listOf(
    0xFF9EDC8C, 0xFFB7C3EA, 0xFFFFB6C6, 0xFF7FD8E8, 0xFFE8C832, 0xFFF2A07E, 0xFFD9A066, 0xFFC8CCD2, 0xFF3D4A63,
).map { Color(it) }

internal fun parsePrice(s: String): Cents? =
    s.trim().replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.scale() <= 2 }?.movePointRight(2)?.toLong()?.let(::Cents)

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PadKey("← Retour", Modifier.size(120.dp, 44.dp), color = NavySoft, textColor = Color.White, fontSize = 16.sp, onClick = onBack)
        Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) FsBlue else Color(0xFFE3E8F0)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(label, color = if (selected) Color.White else Navy, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

// ---------------------------------------------------------------- Menu « Produits et claviers »

@Composable
internal fun CatalogHubScreen(vm: PosViewModel) {
    data class T(val label: String, val glyph: String, val color: Color, val target: Screen?)
    val tiles = listOf(
        T("Assistant création produit", "+", Color(0xFFD62850), null),
        T("Images", "▣", Color(0xFF3F8FE0), null),
        T("Claviers", "#", Color(0xFF7A2E2E), Screen.KEYBOARDS),
        T("Commentaires et suppléments", "✎", Color(0xFF2F55C8), null),
        T("Formules", "F", Color(0xFF43A047), null),
        T("Tous les produits", "≡", Color(0xFF5B3CC4), Screen.PRODUCTS),
    )
    Column(Modifier.fillMaxSize().background(TicketBg).padding(16.dp)) {
        ScreenHeader("PRODUITS ET CLAVIERS", onBack = { vm.show(Screen.HOME) })
        LazyVerticalGrid(GridCells.Fixed(6), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(tiles) { t ->
                val enabled = t.target != null
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { t.target?.let(vm::show) }.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(if (enabled) t.color else t.color.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                        Text(t.glyph, fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White)
                    }
                    Text(t.label.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (enabled) Navy else Muted, modifier = Modifier.padding(top = 6.dp))
                    if (!enabled) Text("bientôt", fontSize = 11.sp, color = Muted)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Claviers

@Composable
internal fun KeyboardsScreen(vm: PosViewModel) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val activeId by vm.keyboardId.collectAsStateWithLifecycle()
    val keyboards = catalog?.keyboards.orEmpty()
    var kbId by remember { mutableStateOf<String?>(null) }
    val kb = keyboards.firstOrNull { it.id == kbId } ?: catalog?.keyboard(activeId) ?: return
    var pageId by remember { mutableStateOf<String?>(null) }
    val page = kb.pages.firstOrNull { it.id == pageId } ?: kb.pages.firstOrNull()
    var editCell by remember { mutableStateOf<Int?>(null) }
    var askName by remember { mutableStateOf<Pair<String, (String, Boolean) -> Unit>?>(null) }
    val isActive = (catalog?.keyboard(activeId)?.id) == kb.id

    Row(Modifier.fillMaxSize().background(TicketBg).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ScreenHeader("CLAVIERS", onBack = { vm.show(Screen.CATALOG) })
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val i = keyboards.indexOfFirst { it.id == kb.id }
                PadKey("‹", Modifier.size(48.dp), enabled = i > 0) { kbId = keyboards[i - 1].id; pageId = null }
                Text(kb.name.uppercase(), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Navy)
                PadKey("›", Modifier.size(48.dp), enabled = i < keyboards.lastIndex) { kbId = keyboards[i + 1].id; pageId = null }
            }
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PadKey(if (isActive) "✓ Clavier de ce terminal" else "Utiliser sur ce terminal", Modifier.weight(1.4f).height(44.dp),
                    color = if (isActive) PayCash else FsBlue, textColor = Color.White, fontSize = 13.sp, enabled = !isActive) { vm.useKeyboard(kb.id) }
                PadKey("Renommer", Modifier.weight(1f).height(44.dp), color = NavySoft, textColor = Color.White, fontSize = 13.sp) {
                    askName = kb.name to { n, _ -> vm.renameKeyboard(kb.id, n) }
                }
                PadKey("+ Clavier", Modifier.weight(1f).height(44.dp), color = NavySoft, textColor = Color.White, fontSize = 13.sp) {
                    askName = "Clavier ${keyboards.size + 1}" to { n, _ -> vm.addKeyboard(n) }
                }
            }
            LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Color.White)) {
                items(kb.pages, key = { it.id }) { p ->
                    val sel = p.id == page?.id
                    Row(
                        Modifier.fillMaxWidth().background(if (sel) FsBlue else Color.Transparent).clickable { pageId = p.id }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p.name.uppercase(), Modifier.weight(1f), color = if (sel) Color.White else Navy, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Box(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(if (p.visible) PayCash else Color(0xFF9AA1AD)).clickable { vm.updatePage(p.id, visible = !p.visible) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) { Text(if (p.visible) "VISIBLE" else "CACHÉE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(6.dp))
                        Text("✎", Modifier.clickable { askName = p.name to { n, _ -> vm.updatePage(p.id, name = n) } }.padding(6.dp), fontSize = 20.sp, color = if (sel) Color.White else Navy)
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PadKey("▲", Modifier.weight(1f).height(48.dp), color = NavySoft, textColor = Color.White, enabled = page != null && kb.pages.first().id != page.id) { page?.let { vm.movePage(it.id, -1) } }
                PadKey("▼", Modifier.weight(1f).height(48.dp), color = NavySoft, textColor = Color.White, enabled = page != null && kb.pages.last().id != page.id) { page?.let { vm.movePage(it.id, +1) } }
                PadKey("+ Nouvelle page", Modifier.weight(2f).height(48.dp), color = PayCash, textColor = Color.White, fontSize = 15.sp) {
                    askName = "" to { n, visible -> vm.addPage(kb.id, n, visible) }
                }
                PadKey("Suppr.", Modifier.weight(1f).height(48.dp), color = Color(0xFF8E2A2A), textColor = Color.White, fontSize = 14.sp, enabled = page != null && kb.pages.size > 1) {
                    page?.let { vm.deletePage(it.id); pageId = null }
                }
            }
        }
        Column(Modifier.weight(1.5f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp)) {
            Text(
                (page?.name ?: "").uppercase() + if (page?.visible == false) " (page cachée)" else "",
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Navy).padding(10.dp), color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Text("Touchez une case pour y placer un produit ou un renvoi vers une page.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 6.dp))
            if (page != null) {
                val (c1, c2) = FamilyTiles[page.name] ?: (TileGreen to TileLavender)
                KeyboardGrid(kb.cellsOf(page.id), c1, c2, showEmpty = true, onEmpty = { editCell = it }) { editCell = it.slot }
            }
        }
    }

    val slot = editCell
    if (slot != null && page != null && catalog != null) {
        CellDialog(
            existing = kb.cellsOf(page.id)[slot], products = catalog!!.products.filter { it.active },
            pages = kb.pages.filter { it.id != page.id }.sortedBy { it.visible }.map { it.id to (it.name + if (!it.visible) " (cachée)" else "") },
            onProduct = { id, color -> vm.setProductCell(page.id, slot, id, color); editCell = null },
            onPage = { id, color -> vm.setPageLinkCell(page.id, slot, id, color); editCell = null },
            onClear = { vm.clearCell(page.id, slot); editCell = null },
            onDismiss = { editCell = null },
        )
    }
    askName?.let { (initial, onOk) ->
        NameDialog(initial, askVisible = initial.isEmpty(), onOk = { n, v -> onOk(n, v); askName = null }, onDismiss = { askName = null })
    }
}

@Composable
private fun NameDialog(initial: String, askVisible: Boolean, onOk: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (askVisible) "Nouvelle page" else "Nom") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, label = { Text("Nom") }, singleLine = true)
                if (askVisible) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Visible (onglet)", visible) { visible = true }
                    Chip("Cachée (sous-page)", !visible) { visible = false }
                }
            }
        },
        confirmButton = { Button(onClick = { onOk(name.trim(), visible) }, enabled = name.isNotBlank()) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CellDialog(
    existing: KeyboardCell?, products: List<Product>, pages: List<Pair<String, String>>,
    onProduct: (String, Int?) -> Unit, onPage: (String, Int?) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit,
) {
    var linkMode by remember { mutableStateOf(existing is KeyboardCell.PageLink) }
    var color by remember { mutableStateOf(existing?.color) }
    val categories = products.map { it.category }.distinct()
    var category by remember { mutableStateOf((existing as? KeyboardCell.ProductCell)?.product?.category ?: categories.firstOrNull()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contenu de la case") },
        text = {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Produit", !linkMode) { linkMode = false }
                    Chip("Aller à une page", linkMode) { linkMode = true }
                }
                Text("Couleur", fontSize = 13.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color.White).border(2.dp, if (color == null) FsBlue else KeyBlue, CircleShape).clickable { color = null }, contentAlignment = Alignment.Center) {
                        Text("A", fontSize = 12.sp, color = Navy)
                    }
                    CellColors.forEach { c ->
                        Box(Modifier.size(32.dp).clip(CircleShape).background(c).border(2.dp, if (color == c.toArgb()) FsBlue else Color.Transparent, CircleShape).clickable { color = c.toArgb() })
                    }
                }
                if (!linkMode) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.forEach { c -> Chip(c, c == category) { category = c } }
                    }
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(products.filter { it.category == category }) { p ->
                            Row(Modifier.fillMaxWidth().clickable { onProduct(p.id, color) }.padding(vertical = 10.dp, horizontal = 6.dp)) {
                                Text(p.label, Modifier.weight(1f), fontSize = 16.sp, color = Navy)
                                Text(p.price.format(), fontSize = 16.sp, color = Navy, fontWeight = FontWeight.SemiBold)
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    if (pages.isEmpty()) Text("Créez d'abord une autre page (de préférence cachée).", color = Muted)
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(pages) { (id, name) ->
                            Text("→ $name", Modifier.fillMaxWidth().clickable { onPage(id, color) }.padding(vertical = 10.dp, horizontal = 6.dp), fontSize = 16.sp, color = Navy)
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = { if (existing != null) TextButton(onClick = onClear) { Text("Vider la case", color = Color(0xFFC62828)) } },
    )
}

// ---------------------------------------------------------------- Tous les produits

@Composable
internal fun ProductsScreen(vm: PosViewModel) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Product?>(null) }
    val products = catalog?.products.orEmpty()
    Column(Modifier.fillMaxSize().background(TicketBg).padding(12.dp)) {
        ScreenHeader("TOUS LES PRODUITS (${products.size})", onBack = { vm.show(Screen.CATALOG) }) {
            PadKey("+ Nouveau produit", Modifier.size(200.dp, 44.dp), color = PayCash, textColor = Color.White, fontSize = 15.sp) {
                editing = Product(vm.newProductId(), "", Cents.ZERO, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel.DIRECT, catalog?.categories?.firstOrNull() ?: "Divers")
            }
        }
        Row(Modifier.fillMaxWidth().background(Navy).padding(10.dp)) {
            listOf("PRODUIT" to 2.2f, "CATÉGORIE" to 1.4f, "TVA" to 0.7f, "ENVOI" to 0.9f, "PRIX TTC" to 1f).forEach { (h, w) ->
                Text(h, Modifier.weight(w), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = if (h == "PRIX TTC") TextAlign.End else TextAlign.Start)
            }
        }
        LazyColumn(Modifier.weight(1f).background(Color.White)) {
            items(products, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable { editing = p }.padding(horizontal = 10.dp, vertical = 10.dp)) {
                    val c = if (p.active) Navy else Muted
                    Text(p.label + if (!p.active) " (inactif)" else "", Modifier.weight(2.2f), color = c, fontSize = 15.sp)
                    Text(p.category + (p.subCategory?.let { " › $it" } ?: ""), Modifier.weight(1.4f), color = c, fontSize = 14.sp)
                    Text(p.vat.format(), Modifier.weight(0.7f), color = c, fontSize = 14.sp)
                    Text("${p.destination.lowercase()} ${p.defaultLevel.label}", Modifier.weight(0.9f), color = c, fontSize = 13.sp)
                    Text(p.price.format(), Modifier.weight(1f), color = c, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                }
                HorizontalDivider()
            }
        }
    }
    editing?.let { p ->
        ProductDialog(p, catalog?.categories.orEmpty(), isNew = products.none { it.id == p.id },
            onSave = { vm.saveProduct(it); editing = null }, onDismiss = { editing = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProductDialog(initial: Product, categories: List<String>, isNew: Boolean, onSave: (Product) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(initial.label) }
    var price by remember { mutableStateOf(if (isNew) "" else initial.price.format(false).replace(" ", "")) }
    var vat by remember { mutableStateOf(initial.vat) }
    var category by remember { mutableStateOf(initial.category) }
    var sub by remember { mutableStateOf(initial.subCategory ?: "") }
    var level by remember { mutableStateOf(initial.defaultLevel) }
    var dest by remember { mutableStateOf(initial.destination) }
    var active by remember { mutableStateOf(initial.active) }
    val cents = parsePrice(price)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Nouveau produit" else "Modifier le produit") },
        text = {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label, { label = it.take(40) }, Modifier.weight(2f), label = { Text("Nom") }, singleLine = true)
                    OutlinedTextField(price, { v -> price = v.filter { it.isDigit() || it == ',' || it == '.' }.take(8) }, Modifier.weight(1f),
                        label = { Text("Prix TTC (€)") }, singleLine = true, isError = price.isNotEmpty() && cents == null)
                }
                Text("TVA", fontSize = 13.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { VatChoices.forEach { r -> Chip(r.format(), r == vat) { vat = r } } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(category, { category = it.take(30) }, Modifier.weight(1f), label = { Text("Catégorie") }, singleLine = true)
                    OutlinedTextField(sub, { sub = it.take(30) }, Modifier.weight(1f), label = { Text("Sous-catégorie (facultatif)") }, singleLine = true)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { c -> Chip(c, c == category) { category = c } }
                }
                Text("Envoi en préparation", fontSize = 13.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("CUISINE", "BAR").forEach { d -> Chip(d.lowercase().replaceFirstChar { it.uppercase() }, d == dest) { dest = d } }
                    Spacer(Modifier.width(12.dp))
                    (0..3).map(::ServiceLevel).forEach { l -> Chip(l.label, l == level) { level = l } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("Actif", active) { active = true }
                    Chip("Inactif (retiré de la vente)", !active) { active = false }
                }
                if (!isNew && cents != null && (cents != initial.price || vat != initial.vat)) {
                    Text("Le nouveau prix ou la nouvelle TVA vaudront pour les ventes suivantes. Les tickets déjà émis ne changent pas.", fontSize = 12.sp, color = Warn)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(initial.copy(label = label.trim(), price = cents!!, vat = vat, category = category.trim().ifBlank { "Divers" }, subCategory = sub.trim().ifBlank { null }, defaultLevel = level, destination = dest, active = active)) },
                enabled = label.isNotBlank() && cents != null,
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}


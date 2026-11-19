package fr.foodsafe.pos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.foodsafe.pos.app.data.Formula
import fr.foodsafe.pos.app.data.FormulaKind
import fr.foodsafe.pos.app.data.FormulaStep
import fr.foodsafe.pos.app.data.Product
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.OrderLine
import fr.foodsafe.pos.core.order.ServiceLevel

/** Vente d'une formule : un produit par étape. Une étape à un seul produit est déjà choisie. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormulaDialog(f: Formula, products: List<Product>, onDismiss: () -> Unit, onOk: (List<Product>) -> Unit) {
    val byId = products.filter { it.active }.associateBy { it.id }
    val options = f.steps.map { s -> s.productIds.mapNotNull(byId::get) }
    var picks by remember { mutableStateOf(options.map { it.singleOrNull() }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${f.name} · ${f.price.format()}") },
        text = {
            Column(Modifier.width(640.dp).heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                f.steps.forEachIndexed { i, s ->
                    Text(s.name + (s.level?.let { " · envoi ${it.label}" } ?: ""), fontWeight = FontWeight.Bold, color = if (picks[i] == null) Warn else Navy, fontSize = 15.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        options[i].forEach { p -> Chip(p.label, picks[i]?.id == p.id) { picks = picks.toMutableList().also { it[i] = p } } }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onOk(picks.filterNotNull()) }, enabled = picks.all { it != null }) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
internal fun PromoRemoveDialog(line: OrderLine, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Retirer la promotion") },
        text = {
            Column {
                Text("Les articles de cette promotion (dont ${line.label}) reviennent au prix normal et ne seront plus regroupés sur cette note. Validation responsable requise pour un serveur.")
                listOf("Client ne souhaite pas la formule", "Erreur de saisie").forEach { TextButton(onClick = { reason = it }) { Text(it) } }
                OutlinedTextField(reason, { reason = it }, label = { Text("Motif (obligatoire)") })
            }
        },
        confirmButton = { Button(onClick = { onConfirm(reason) }, enabled = reason.isNotBlank()) { Text("Retirer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Retour") } },
    )
}

// ---------------------------------------------------------------- Écran de gestion

@Composable
internal fun FormulasScreen(vm: PosViewModel) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val formulas = catalog?.formulas.orEmpty()
    val products = catalog?.products.orEmpty().filter { it.active }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val selected = if (creating) null else formulas.firstOrNull { it.id == selectedId } ?: formulas.firstOrNull()
    var confirmDelete by remember { mutableStateOf<Formula?>(null) }
    Column(Modifier.fillMaxSize().background(TicketBg).padding(12.dp)) {
        ScreenHeader("FORMULES", onBack = { vm.show(Screen.CATALOG) }) {
            PadKey("+ Nouvelle formule", Modifier.size(200.dp, 44.dp), color = PayCash, textColor = Color.White, fontSize = 15.sp) { creating = true }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.width(300.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color.White)) {
                items(formulas, key = { it.id }) { f ->
                    Column(Modifier.fillMaxWidth().background(if (f.id == selected?.id) Color(0xFFDDE3EC) else Color.White).clickable { creating = false; selectedId = f.id }.padding(12.dp)) {
                        Text(f.name, fontWeight = FontWeight.Bold, color = Navy, fontSize = 16.sp)
                        Text("${f.kind.label} · ${f.price.format()} · ${f.steps.size} étape(s)", fontSize = 12.sp, color = Muted)
                    }
                    HorizontalDivider(color = TicketBg)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color.White).padding(12.dp)) {
                val initial = selected ?: Formula("", "", FormulaKind.MENU, Cents.ZERO, listOf(FormulaStep("", "Étape 1", null, emptyList())))
                if (selected == null && !creating) {
                    Text("Aucune formule. Créez-en une avec « + Nouvelle formule ».", color = Muted)
                } else FormulaEditor(initial, products, onSave = { f -> vm.saveFormula(f) { creating = false; selectedId = it } },
                    onDelete = selected?.let { s -> { confirmDelete = s } })
            }
        }
    }
    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Supprimer la formule « ${f.name} » ?") },
            text = { Text("Elle disparaît du clavier et n'est plus proposée. Les notes et tickets déjà saisis ne changent pas.") },
            confirmButton = { Button(onClick = { vm.deleteFormula(f.id); confirmDelete = null; selectedId = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annuler") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormulaEditor(initial: Formula, products: List<Product>, onSave: (Formula) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var kind by remember(initial) { mutableStateOf(initial.kind) }
    var price by remember(initial) { mutableStateOf(if (initial.id.isEmpty()) "" else initial.price.format(false).replace(" ", "")) }
    var steps by remember(initial) { mutableStateOf(initial.steps) }
    var picking by remember(initial) { mutableStateOf<Int?>(null) }
    val cents = parsePrice(price)
    val byId = products.associateBy { it.id }
    // Valeur au détail la plus basse et la plus haute de la combinaison, pour situer le prix de la formule
    val mins = steps.map { s -> s.productIds.mapNotNull { byId[it]?.price?.value }.minOrNull() ?: 0 }
    val maxs = steps.map { s -> s.productIds.mapNotNull { byId[it]?.price?.value }.maxOrNull() ?: 0 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it.take(30) }, Modifier.weight(2f), label = { Text("Nom de la formule") }, singleLine = true)
            OutlinedTextField(price, { v -> price = v.filter { it.isDigit() || it == ',' || it == '.' }.take(7) }, Modifier.weight(1f),
                label = { Text("Prix TTC (€)") }, singleLine = true, isError = price.isNotEmpty() && cents == null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FormulaKind.entries.forEach { k -> Chip(k.label, k == kind) { kind = k } } }
        Text(when (kind) {
            FormulaKind.MENU -> "Le serveur choisit un produit par étape. Chaque étape part à son niveau (DIRECT pour une formule directe, N1, N2… pour une formule de service)."
            FormulaKind.PACKAGE -> "Articles fixes sous un seul bouton : un produit par ligne, ajoutés d'un seul toucher."
            FormulaKind.PROMO -> "Pas de bouton : dès que la combinaison (un produit par étape) est sur la note, le prix de la promo s'applique. Jamais si elle revient plus cher qu'au détail."
        }, fontSize = 13.sp, color = Muted)
        steps.forEachIndexed { i, s ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(TicketBg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s.name, { v -> steps = steps.toMutableList().also { it[i] = s.copy(name = v.take(20)) } }, Modifier.width(220.dp), label = { Text("Étape ${i + 1}") }, singleLine = true)
                    if (kind == FormulaKind.MENU) {
                        Chip("Niveau produit", s.level == null) { steps = steps.toMutableList().also { it[i] = s.copy(level = null) } }
                        (0..3).map(::ServiceLevel).forEach { l -> Chip(l.label, s.level == l) { steps = steps.toMutableList().also { it[i] = s.copy(level = l) } } }
                    }
                    Spacer(Modifier.weight(1f))
                    if (steps.size > 1) Text("Retirer", Modifier.clickable { steps = steps.filterIndexed { j, _ -> j != i } }.padding(6.dp), color = Color(0xFFC62828), fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (s.productIds.isEmpty()) "Aucun produit" else s.productIds.mapNotNull { byId[it]?.label }.joinToString(", "),
                        Modifier.weight(1f), fontSize = 13.sp, color = if (s.productIds.isEmpty()) Warn else Navy, maxLines = 2)
                    Chip(if (kind == FormulaKind.PACKAGE) "Choisir l'article" else "Choisir les produits", false) { picking = i }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("+ Ajouter une étape", false) { steps = steps + FormulaStep("", "Étape ${steps.size + 1}", null, emptyList()) }
            Spacer(Modifier.weight(1f))
            if (steps.all { it.productIds.isNotEmpty() }) Text("Au détail : ${Cents(mins.sum()).format()} à ${Cents(maxs.sum()).format()}", fontSize = 13.sp, color = Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val valid = name.isNotBlank() && cents != null && steps.all { it.productIds.isNotEmpty() } &&
                (kind != FormulaKind.PACKAGE || steps.all { it.productIds.size == 1 })
            PadKey("Enregistrer", Modifier.size(180.dp, 48.dp), color = PayCash, textColor = Color.White, fontSize = 16.sp, enabled = valid) {
                onSave(initial.copy(name = name.trim(), kind = kind, price = cents!!, steps = steps))
            }
            if (onDelete != null) PadKey("Supprimer", Modifier.size(150.dp, 48.dp), color = NavySoft, textColor = Color.White, fontSize = 16.sp, onClick = onDelete)
        }
    }
    picking?.let { i ->
        ProductPicker(products, steps[i].productIds, single = kind == FormulaKind.PACKAGE, onDismiss = { picking = null }) { ids ->
            steps = steps.toMutableList().also { it[i] = it[i].copy(productIds = ids) }; picking = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductPicker(products: List<Product>, initial: List<String>, single: Boolean, onDismiss: () -> Unit, onOk: (List<String>) -> Unit) {
    var chosen by remember { mutableStateOf(initial) }
    val categories = products.map { it.category }.distinct()
    var category by remember { mutableStateOf(products.firstOrNull { it.id in initial }?.category ?: categories.firstOrNull()) }
    val inCat = products.filter { it.category == category }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (single) "Article du package" else "Produits proposés à cette étape") },
        text = {
            Column(Modifier.width(600.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { c -> Chip(c, c == category) { category = c } }
                }
                if (!single) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Toute la catégorie", inCat.isNotEmpty() && inCat.all { it.id in chosen }) {
                        chosen = if (inCat.all { it.id in chosen }) chosen - inCat.map { it.id }.toSet() else (chosen + inCat.map { it.id }).distinct()
                    }
                }
                FlowRow(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    inCat.forEach { p ->
                        Chip("${p.label} ${p.price.format(false)}", p.id in chosen) {
                            chosen = when { single -> listOf(p.id); p.id in chosen -> chosen - p.id; else -> chosen + p.id }
                        }
                    }
                }
                Text("${chosen.size} produit(s) choisi(s)", fontSize = 13.sp, color = Muted)
            }
        },
        confirmButton = { Button(onClick = { onOk(chosen) }, enabled = chosen.isNotEmpty()) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

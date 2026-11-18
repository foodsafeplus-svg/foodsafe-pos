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
import fr.foodsafe.pos.app.data.ModifierGroup
import fr.foodsafe.pos.app.data.ModifierItem
import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents

internal fun ModifierItem.chipLabel() = if (isFree) label else "$label +${price.format(false)}"

/**
 * Choix des commentaires et suppléments à la vente. Un groupe obligatoire doit avoir un choix ;
 * un groupe à choix unique remplace la sélection à chaque toucher.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModifierDialog(title: String, groups: List<ModifierGroup>, onDismiss: () -> Unit, onOk: (List<ModifierItem>) -> Unit) {
    var chosen by remember { mutableStateOf(listOf<ModifierItem>()) }
    val missing = groups.filter { g -> g.mandatory && chosen.none { it.groupId == g.id } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.width(620.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                groups.forEach { g ->
                    Text(g.name + (if (g.mandatory) " · obligatoire" else "") + (if (g.multiple) " · plusieurs choix" else " · un choix"),
                        fontWeight = FontWeight.Bold, color = if (g in missing) Warn else Navy, fontSize = 15.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        g.items.forEach { m ->
                            Chip(m.chipLabel(), m in chosen) {
                                chosen = when {
                                    m in chosen -> chosen - m
                                    g.multiple -> chosen + m
                                    else -> chosen.filter { it.groupId != g.id } + m
                                }
                            }
                        }
                    }
                }
                val extra = Cents(chosen.sumOf { it.price.value })
                if (!extra.isZero) Text("Suppléments : +${extra.format()}", color = Navy, fontWeight = FontWeight.SemiBold)
            }
        },
        // l'ordre des groupes, puis l'ordre des éléments, donne l'ordre des sous-lignes
        confirmButton = {
            Button(onClick = { onOk(groups.flatMap { g -> g.items.filter { it in chosen } }) }, enabled = missing.isEmpty()) { Text("Valider") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

// ---------------------------------------------------------------- Écran de gestion

@Composable
internal fun ModifiersScreen(vm: PosViewModel) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val groups = catalog?.groups.orEmpty()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = groups.firstOrNull { it.id == selectedId } ?: groups.firstOrNull()
    var groupDialog by remember { mutableStateOf<ModifierGroup?>(null) }
    var newGroup by remember { mutableStateOf(false) }
    var itemDialog by remember { mutableStateOf<ModifierItem?>(null) }
    var confirmDelete by remember { mutableStateOf<ModifierGroup?>(null) }
    Column(Modifier.fillMaxSize().background(TicketBg).padding(12.dp)) {
        ScreenHeader("COMMENTAIRES ET SUPPLÉMENTS", onBack = { vm.show(Screen.CATALOG) }) {
            PadKey("+ Nouveau groupe", Modifier.size(200.dp, 44.dp), color = PayCash, textColor = Color.White, fontSize = 15.sp) { newGroup = true }
        }
        Text("Un groupe réunit des commentaires gratuits et des suppléments payants. Rattachez-le à un produit (fiche produit) : il est proposé à chaque vente de ce produit.",
            fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 8.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.width(320.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color.White)) {
                items(groups, key = { it.id }) { g ->
                    val linked = catalog?.productGroups.orEmpty().count { (_, l) -> g.id in l }
                    Column(Modifier.fillMaxWidth().background(if (g.id == selected?.id) Color(0xFFDDE3EC) else Color.White).clickable { selectedId = g.id }.padding(12.dp)) {
                        Text(g.name, fontWeight = FontWeight.Bold, color = Navy, fontSize = 16.sp)
                        Text(listOfNotNull(if (g.mandatory) "obligatoire" else null, if (g.multiple) "plusieurs choix" else "un choix",
                            "${g.items.size} élément(s)", "$linked produit(s)").joinToString(" · "), fontSize = 12.sp, color = Muted)
                    }
                    HorizontalDivider(color = TicketBg)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color.White).padding(12.dp)) {
                if (selected == null) { Text("Aucun groupe. Créez-en un avec « + Nouveau groupe ».", color = Muted); return@Column }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected.name.uppercase(), fontWeight = FontWeight.Black, color = Navy, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Chip("Modifier", false) { groupDialog = selected }
                    Chip("Supprimer", false) { confirmDelete = selected }
                    PadKey("+ Nouvel élément", Modifier.size(190.dp, 40.dp), color = PayCash, textColor = Color.White, fontSize = 14.sp) {
                        itemDialog = ModifierItem("", selected.id, "", Cents.ZERO, null)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp).background(Navy).padding(8.dp)) {
                    listOf("ÉLÉMENT" to 2f, "PRIX" to 1f, "TVA" to 1.2f).forEach { (h, w) -> Text(h, Modifier.weight(w), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(selected.items, key = { it.id }) { m ->
                        Row(Modifier.fillMaxWidth().clickable { itemDialog = m }.padding(horizontal = 8.dp, vertical = 10.dp)) {
                            Text(m.label, Modifier.weight(2f), color = Navy, fontSize = 15.sp)
                            Text(if (m.isFree) "gratuit" else "+" + m.price.format(), Modifier.weight(1f), color = Navy, fontSize = 14.sp)
                            Text(m.vat?.format() ?: "suit le produit", Modifier.weight(1.2f), color = if (m.vat == null) Muted else Navy, fontSize = 14.sp)
                        }
                        HorizontalDivider(color = TicketBg)
                    }
                }
            }
        }
    }
    if (newGroup || groupDialog != null) {
        val g = groupDialog
        GroupDialog(g, onDismiss = { newGroup = false; groupDialog = null }) { name, mandatory, multiple ->
            vm.saveGroup(g?.id, name, mandatory, multiple) { selectedId = it }
            newGroup = false; groupDialog = null
        }
    }
    itemDialog?.let { m ->
        ModifierItemDialog(m, onDismiss = { itemDialog = null }, onDelete = if (m.id.isEmpty()) null else ({ vm.deleteModifier(m.id); itemDialog = null })) {
            vm.saveModifier(it); itemDialog = null
        }
    }
    confirmDelete?.let { g ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Supprimer le groupe « ${g.name} » ?") },
            text = { Text("Il ne sera plus proposé à la vente. Les notes déjà saisies gardent leurs commentaires.") },
            confirmButton = { Button(onClick = { vm.deleteGroup(g.id); confirmDelete = null; selectedId = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun GroupDialog(initial: ModifierGroup?, onDismiss: () -> Unit, onOk: (String, Boolean, Boolean) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var mandatory by remember { mutableStateOf(initial?.mandatory ?: false) }
    var multiple by remember { mutableStateOf(initial?.multiple ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nouveau groupe" else "Modifier le groupe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, label = { Text("Nom") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Facultatif", !mandatory) { mandatory = false }
                    Chip("Obligatoire", mandatory) { mandatory = true }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Un seul choix", !multiple) { multiple = false }
                    Chip("Plusieurs choix", multiple) { multiple = true }
                }
                if (mandatory) Text("La commande ne pourra pas partir en cuisine tant qu'aucun choix n'est fait pour ce groupe.", fontSize = 12.sp, color = Muted)
            }
        },
        confirmButton = { Button(onClick = { onOk(name, mandatory, multiple) }, enabled = name.isNotBlank()) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun ModifierItemDialog(initial: ModifierItem, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onOk: (ModifierItem) -> Unit) {
    var label by remember { mutableStateOf(initial.label) }
    var price by remember { mutableStateOf(if (initial.isFree) "" else initial.price.format(false).replace(" ", "")) }
    var vat by remember { mutableStateOf<BasisPoints?>(initial.vat) }
    val cents = if (price.isBlank()) Cents.ZERO else parsePrice(price)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isEmpty()) "Nouvel élément" else "Modifier l'élément") },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label, { label = it.take(30) }, Modifier.weight(2f), label = { Text("Libellé") }, singleLine = true)
                    OutlinedTextField(price, { v -> price = v.filter { it.isDigit() || it == ',' || it == '.' }.take(7) }, Modifier.weight(1f),
                        label = { Text("Prix (vide = gratuit)") }, singleLine = true, isError = cents == null)
                }
                Text("TVA", fontSize = 13.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Suit le produit", vat == null) { vat = null }
                    VatChoices.forEach { r -> Chip(r.format(), r == vat) { vat = r } }
                }
                Text("« Suit le produit » : le supplément prend la TVA du produit auquel il s'ajoute (ex. un diluant sur un alcool à 20 %).", fontSize = 12.sp, color = Muted)
                if (onDelete != null) Text("Supprimer cet élément", Modifier.clickable(onClick = onDelete).padding(top = 4.dp), color = Color(0xFFC62828), fontSize = 14.sp)
            }
        },
        confirmButton = {
            Button(onClick = { onOk(initial.copy(label = label.trim(), price = cents!!, vat = vat)) }, enabled = label.isNotBlank() && cents != null) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

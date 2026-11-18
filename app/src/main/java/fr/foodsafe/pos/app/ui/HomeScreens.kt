package fr.foodsafe.pos.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.foodsafe.pos.app.hardware.HardwareTestActivity
import fr.foodsafe.pos.core.cash.CashCount

import java.math.BigDecimal
import java.math.RoundingMode

// ---------------------------------------------------------------- Menu Accueil

private data class HomeTile(val label: String, val glyph: String, val color: Color, val enabled: Boolean = true, val note: String = "bientôt", val onClick: () -> Unit = {})

/** Menu Accueil en tuiles (cahier des fonctions § 2). Les tuiles grisées arrivent dans les étapes suivantes. */
@Composable
internal fun HomeMenuScreen(vm: PosViewModel) {
    val ctx = LocalContext.current
    val tiles = listOf(
        HomeTile("Caisse", "€", Color(0xFFD9468F)) { vm.backToTables() },
        HomeTile("Calculatrice", "±", Color(0xFFC2185B)) { vm.show(Screen.CALCULATOR) },
        HomeTile("Clients", "C", Color(0xFF29B6F6), enabled = false),
        HomeTile("Compteur d'espèces", "∑", Color(0xFF3F7FE0)) { vm.show(Screen.CASH_COUNT) },
        HomeTile("Écrans de préparation", "≡", Color(0xFF26A0DA)) { vm.show(Screen.KITCHEN) },
        HomeTile("Ouvrir le tiroir", "⇲", Color(0xFF26A69A)) { vm.openDrawer() },
        HomeTile("Produits et claviers", "P", Color(0xFFF2B01E), enabled = vm.isManager, note = "responsable") { vm.show(Screen.CATALOG) },
        HomeTile("Statistiques", "S", Color(0xFF43A047), enabled = false),
        HomeTile("Stock", "K", Color(0xFFE8C51A), enabled = false),
        HomeTile("Tous les rapports Z", "Z", Color(0xFF5C6BC0)) { vm.show(Screen.CLOSURE) },
        HomeTile("TVA", "%", Color(0xFF7E57C2), enabled = false),
        HomeTile("Utilisateurs", "U", Color(0xFFD84315), enabled = false),
        HomeTile("Z établissement en cours", "Z", Color(0xFFE53970)) { vm.show(Screen.CLOSURE) },
        HomeTile("Test matériel", "T", Color(0xFF607D8B)) { ctx.startActivity(Intent(ctx, HardwareTestActivity::class.java)) },
        HomeTile("Quitter", "×", Color(0xFFE57373)) { vm.logout() },
    )
    Column(Modifier.fillMaxSize().background(TicketBg).padding(16.dp)) {
        Text("ACCUEIL", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(GridCells.Fixed(6), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(tiles) { t ->
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = t.enabled, onClick = t.onClick).padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(if (t.enabled) t.color else t.color.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center,
                    ) { Text(t.glyph, fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White) }
                    Text(t.label.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        color = if (t.enabled) Navy else Muted, modifier = Modifier.padding(top = 6.dp))
                    if (!t.enabled) Text(t.note, fontSize = 11.sp, color = Muted)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Compteur d'espèces

/** Compteur d'espèces (cahier § 3) : quantité au pavé, puis la pièce ou le billet. */
@Composable
internal fun CashCountScreen(vm: PosViewModel) {
    var count by remember { mutableStateOf(CashCount()) }
    var selected by remember { mutableIntStateOf(-1) }
    var entry by remember { mutableStateOf("") }

    Row(Modifier.fillMaxSize().background(TicketBg).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Tableau des lignes + total
        Column(Modifier.weight(1.1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color.White)) {
            Row(Modifier.fillMaxWidth().background(Navy).padding(10.dp)) {
                listOf("QTÉ" to 0.7f, "VALEUR" to 1f, "TOTAL" to 1f).forEach { (h, w) ->
                    Text(h, Modifier.weight(w), color = Color.White, fontWeight = FontWeight.Bold, textAlign = if (h == "QTÉ") TextAlign.Start else TextAlign.End)
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(count.lines) { i, l ->
                    Row(
                        Modifier.fillMaxWidth().background(if (i == selected) FsBlue else Color.Transparent).clickable { selected = i }.padding(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                        val c = if (i == selected) Color.White else Navy
                        Text("${l.quantity}", Modifier.weight(0.7f), color = c, fontSize = 18.sp)
                        Text(l.denomination.format(), Modifier.weight(1f), color = c, fontSize = 18.sp, textAlign = TextAlign.End)
                        Text(l.total.format(), Modifier.weight(1f), color = c, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                    }
                }
            }
            Text(
                "TOTAL : ${count.total.format()}", Modifier.fillMaxWidth().background(Color(0xFFE8F5E9)).padding(14.dp),
                color = Color(0xFF2E7D32), fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.End,
            )
        }
        // Commandes de ligne
        Column(Modifier.width(84.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val has = selected in count.lines.indices
            PadKey("▲", Modifier.fillMaxWidth().weight(1f), color = NavySoft, textColor = Color.White, enabled = selected > 0) { selected-- }
            PadKey("+", Modifier.fillMaxWidth().weight(1f), color = PayCash, textColor = Color.White, fontSize = 32.sp, enabled = has) { count = count.step(selected, +1) }
            PadKey("−", Modifier.fillMaxWidth().weight(1f), color = Color(0xFFD62828), textColor = Color.White, fontSize = 32.sp, enabled = has) {
                val before = count.lines.size; count = count.step(selected, -1); if (count.lines.size < before) selected = -1
            }
            PadKey("▼", Modifier.fillMaxWidth().weight(1f), color = NavySoft, textColor = Color.White, enabled = selected < count.lines.size - 1) { selected++ }
            PadKey("Suppr.", Modifier.fillMaxWidth().weight(1f), color = Color(0xFF8E2A2A), textColor = Color.White, fontSize = 16.sp, enabled = has) {
                count = count.remove(selected); selected = -1
            }
        }
        // Pavé de quantité
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, KeyBlue, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterEnd) {
                Text(if (entry.isEmpty()) "Quantité" else "$entry ×", fontSize = 28.sp, color = if (entry.isEmpty()) Muted else Navy, fontWeight = FontWeight.Bold)
            }
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("C", "0", "00")).forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        PadKey(k, Modifier.weight(1f).fillMaxHeight().testTag("qte-$k"), color = if (k == "C") Color(0xFFF3D3D3) else KeyBlue, textColor = if (k == "C") Color(0xFFC62828) else Navy) {
                            entry = when (k) { "C" -> ""; else -> (entry + k).trimStart('0').take(5) }
                        }
                    }
                }
            }
        }
        // Valeurs + actions
        Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadKey("Imprimer", Modifier.weight(1f).height(56.dp), color = FsBlue, textColor = Color.White, fontSize = 18.sp, enabled = count.lines.isNotEmpty()) { vm.printCashCount(count) }
                PadKey("Tout effacer", Modifier.weight(1f).height(56.dp), color = NavySoft, textColor = Color.White, fontSize = 18.sp, enabled = count.lines.isNotEmpty()) {
                    count = CashCount(); selected = -1; entry = ""
                }
            }
            CashCount.DENOMINATIONS.chunked(3).forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { d ->
                        val coin = CashCount.isCoin(d)
                        PadKey(
                            d.format().replace(",00", ""), Modifier.weight(1f).fillMaxHeight(),
                            color = if (coin) Color(0xFFE9DFC7) else Color(0xFFD5E8D4), textColor = Navy, fontSize = 20.sp,
                        ) {
                            val q = entry.toLongOrNull()?.takeIf { it > 0 } ?: 1
                            count = count.add(d, q)
                            selected = count.lines.indexOfFirst { it.denomination == d }
                            entry = ""
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Calculatrice

/** Calculatrice simple, sans lien avec les ventes (décimales exactes, pas de flottants). */
@Composable
internal fun CalculatorScreen(vm: PosViewModel) {
    var display by remember { mutableStateOf("0") }
    var acc by remember { mutableStateOf<BigDecimal?>(null) }
    var op by remember { mutableStateOf<String?>(null) }
    var fresh by remember { mutableStateOf(true) }

    fun current() = display.replace(',', '.').toBigDecimalOrNull() ?: BigDecimal.ZERO
    fun show(v: BigDecimal) { display = v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',') }
    fun apply(): Boolean {
        val a = acc ?: return true
        val b = current()
        val r = when (op) {
            "+" -> a + b; "−" -> a - b; "×" -> a * b
            "÷" -> if (b.signum() == 0) { display = "Erreur"; acc = null; op = null; fresh = true; return false } else a.divide(b, 8, RoundingMode.HALF_UP)
            else -> b
        }
        show(r); acc = r; return true
    }

    Column(Modifier.fillMaxSize().background(TicketBg).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.width(520.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(8.dp)).background(Navy).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
                Text(display, fontSize = 40.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            listOf(listOf("7", "8", "9", "÷"), listOf("4", "5", "6", "×"), listOf("1", "2", "3", "−"), listOf("C", "0", ",", "+"), listOf("Retour", "=")).forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        val isOp = k in setOf("÷", "×", "−", "+", "=")
                        PadKey(
                            k, Modifier.weight(if (k == "Retour" || k == "=") 2f else 1f).fillMaxHeight(),
                            color = when { k == "=" -> PayCash; isOp -> FsBlue; k == "C" -> Color(0xFFF3D3D3); k == "Retour" -> NavySoft; else -> KeyBlue },
                            textColor = when { isOp || k == "Retour" -> Color.White; k == "C" -> Color(0xFFC62828); else -> Navy },
                            fontSize = if (k == "Retour") 18.sp else 28.sp,
                        ) {
                            when (k) {
                                "Retour" -> vm.show(Screen.HOME)
                                "C" -> { display = "0"; acc = null; op = null; fresh = true }
                                "=" -> { if (apply()) { op = null; acc = null }; fresh = true }
                                "÷", "×", "−", "+" -> {
                                    if (acc == null) acc = current() else if (!fresh && !apply()) return@PadKey
                                    op = k; fresh = true
                                }
                                "," -> { if (fresh) { display = "0,"; fresh = false } else if (!display.contains(',')) display += "," }
                                else -> { display = if (fresh || display == "0") k else (display + k).take(14); fresh = false }
                            }
                        }
                    }
                }
            }
        }
    }
}


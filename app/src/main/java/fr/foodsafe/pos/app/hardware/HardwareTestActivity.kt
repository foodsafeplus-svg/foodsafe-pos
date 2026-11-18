package fr.foodsafe.pos.app.hardware

import android.app.PendingIntent
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Écran de test du matériel intégré (imprimante, afficheur / écran client), pour identifier comment le
 * terminal les expose : USB interne, port série, imprimante réseau, ou second écran Android.
 * N'imprime que des tickets marqués « TEST », jamais de document fiscal.
 */
class HardwareTestActivity : ComponentActivity() {
    private val log = mutableStateListOf<String>()
    private var presentation: Presentation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    override fun onDestroy() {
        presentation?.dismiss()
        super.onDestroy()
    }

    private fun note(s: String) {
        log.add(0, SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date()) + "  " + s)
    }

    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        var refresh by remember { mutableStateOf(0) }
        var ip by remember { mutableStateOf("192.168.1.") }
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        @Suppress("UNUSED_EXPRESSION") refresh
        val usbDevices = usb.deviceList.values.toList()
        val ports = serialCandidates()
        val displays = dm.displays.toList()

        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1.3f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Test du matériel", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { refresh++ }) { Text("Actualiser") }
                    OutlinedButton(onClick = { finish() }) { Text("Retour") }
                }

                Section("1. Périphériques USB (${usbDevices.size})")
                if (usbDevices.isEmpty()) Text("Aucun périphérique USB détecté.")
                usbDevices.forEach { d ->
                    Text(describe(d), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { printUsb(usb, d, EscPos.testTicket("USB ${d.vendorId}:${d.productId}")) } }) { Text("Imprimer un test") }
                        OutlinedButton(onClick = { scope.launch { printUsb(usb, d, EscPos.displayText("FOODSAFE+", "Test afficheur")) } }) { Text("Tester afficheur") }
                    }
                }

                Section("2. Ports série (${ports.size})")
                if (ports.isEmpty()) Text("Aucun port série accessible.")
                ports.forEach { f ->
                    Text("${f.path}  ${if (f.canWrite()) "accessible" else "non accessible (droits)"}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(9600, 19200, 38400, 115200).forEach { baud ->
                            OutlinedButton(onClick = { scope.launch { printSerial(f, baud, EscPos.testTicket("${f.name} $baud")) } }) { Text("$baud") }
                        }
                        OutlinedButton(onClick = { scope.launch { printSerial(f, 9600, EscPos.displayText("FOODSAFE+", "Test afficheur")) } }) { Text("Afficheur") }
                    }
                }

                Section("3. Imprimante réseau (port 9100)")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(ip, { ip = it }, label = { Text("Adresse IP") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { scope.launch { printNetwork(ip.trim(), EscPos.testTicket("Réseau $ip")) } }) { Text("Imprimer un test") }
                }

                Section("4. Écrans (${displays.size})")
                displays.forEach { d ->
                    Text("#${d.displayId} ${d.name} ${d.mode.physicalWidth}×${d.mode.physicalHeight}" +
                        if (d.displayId == Display.DEFAULT_DISPLAY) " (écran principal)" else "", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (d.displayId != Display.DEFAULT_DISPLAY) {
                        Button(onClick = { showOn(d) }) { Text("Afficher un test sur cet écran") }
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Résultats", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Prenez cette zone en photo pour le support.", fontSize = 12.sp)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp)) }
            }
        }
    }

    @Composable
    private fun Section(title: String) {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
    }

    private fun describe(d: UsbDevice): String = buildString {
        append("${d.deviceName}  vendor=${d.vendorId} (0x%04x) product=${d.productId} (0x%04x)".format(d.vendorId, d.productId))
        runCatching { d.manufacturerName }.getOrNull()?.let { append("\n  $it") }
        runCatching { d.productName }.getOrNull()?.let { append(" $it") }
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            append("\n  interface $i classe ${itf.interfaceClass}" + if (itf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) " (IMPRIMANTE)" else "")
        }
    }

    private suspend fun printUsb(usb: UsbManager, d: UsbDevice, data: ByteArray) {
        if (!usb.hasPermission(d)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent("fr.foodsafe.pos.USB_PERMISSION").setPackage(packageName), flags)
            usb.requestPermission(d, pi)
            note("USB ${d.vendorId}:${d.productId} : autorisez l'accès dans la fenêtre Android, puis appuyez à nouveau.")
            return
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val itf = (0 until d.interfaceCount).map { d.getInterface(it) }
                    .sortedByDescending { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
                    .firstOrNull { i -> (0 until i.endpointCount).any { i.getEndpoint(it).let { e -> e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_OUT } } }
                    ?: error("aucune sortie de données (bulk OUT) sur ce périphérique")
                val ep = (0 until itf.endpointCount).map { itf.getEndpoint(it) }
                    .first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
                val conn = usb.openDevice(d) ?: error("ouverture refusée")
                try {
                    check(conn.claimInterface(itf, true)) { "interface occupée" }
                    var off = 0
                    while (off < data.size) {
                        val n = conn.bulkTransfer(ep, data.copyOfRange(off, minOf(off + 4096, data.size)), minOf(4096, data.size - off), 3000)
                        check(n > 0) { "envoi refusé (code $n)" }
                        off += n
                    }
                    "OK, ${data.size} octets envoyés (interface classe ${itf.interfaceClass})"
                } finally { conn.close() }
            }
        }
        note("USB ${d.vendorId}:${d.productId} : " + result.fold({ it }, { "ÉCHEC ${it.message}" }))
    }

    private suspend fun printSerial(f: File, baud: Int, data: ByteArray) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                // Réglage de la vitesse via stty (toybox) ; peut être refusé selon le terminal.
                val stty = runCatching {
                    val p = Runtime.getRuntime().exec(arrayOf("stty", "-F", f.path, "$baud", "raw", "-echo"))
                    p.waitFor(); if (p.exitValue() == 0) "vitesse $baud réglée" else "stty refusé"
                }.getOrElse { "stty indisponible" }
                FileOutputStream(f).use { it.write(data); it.flush() }
                "OK, ${data.size} octets écrits ($stty)"
            }
        }
        note("${f.path} @$baud : " + result.fold({ it }, { "ÉCHEC ${it.message}" }))
    }

    private suspend fun printNetwork(ip: String, data: ByteArray) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { s -> s.connect(InetSocketAddress(ip, 9100), 3000); s.getOutputStream().apply { write(data); flush() } }
                "OK, ${data.size} octets envoyés"
            }
        }
        note("Réseau $ip:9100 : " + result.fold({ it }, { "ÉCHEC ${it.message}" }))
    }

    private fun showOn(d: Display) {
        runCatching {
            presentation?.dismiss()
            presentation = object : Presentation(this, d) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(TextView(context).apply {
                        text = "FOODSAFE+\nÉcran client OK\nTotal : 12,50 €"
                        textSize = 36f; gravity = Gravity.CENTER
                        setTextColor(AColor.WHITE); setBackgroundColor(0xFF1E4FC8.toInt())
                    })
                }
            }.also { it.show() }
            "OK, affiché"
        }.fold({ note("Écran #${d.displayId} : $it") }, { note("Écran #${d.displayId} : ÉCHEC ${it.message}") })
    }

    private fun serialCandidates(): List<File> {
        val dev = File("/dev")
        val names = dev.list()?.filter { n -> listOf("ttyS", "ttyUSB", "ttyACM", "ttyGS", "printer", "ttyFIQ").any { n.startsWith(it) } } ?: emptyList()
        val lp = File("/dev/usb").list()?.filter { it.startsWith("lp") }?.map { File("/dev/usb/$it") } ?: emptyList()
        return (names.sorted().map { File(dev, it) } + lp)
    }
}

/** Commandes ESC/POS minimales pour les tests (ASCII seul : la page de code dépend de l'imprimante). */
object EscPos {
    private const val ESC = 0x1B
    private const val GS = 0x1D

    fun testTicket(via: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun b(vararg x: Int) = x.forEach { out.write(it) }
        fun t(s: String) = out.write(ascii(s).toByteArray(Charsets.US_ASCII))
        b(ESC, '@'.code)                // initialisation
        b(ESC, 'a'.code, 1)             // centré
        b(GS, '!'.code, 0x11); t("FOODSAFE+\n"); b(GS, '!'.code, 0)
        t("TEST IMPRIMANTE\n")
        t("Ce ticket n'a aucune valeur\n")
        b(ESC, 'a'.code, 0)
        t("--------------------------------\n")
        t("Connexion : $via\n")
        t(SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date()) + "\n")
        t("123456789012345678901234567890123456789012345678\n")
        t("\n\n\n\n")
        b(GS, 'V'.code, 66, 0)          // coupe partielle si disponible
        return out.toByteArray()
    }

    /** Afficheur client type VFD / ESC/POS : effacer puis deux lignes de 20 caractères. */
    fun displayText(l1: String, l2: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(ESC); out.write('@'.code); out.write(0x0C)
        out.write(ascii(l1).padEnd(20).take(20).toByteArray(Charsets.US_ASCII))
        out.write(ascii(l2).padEnd(20).take(20).toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun ascii(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("[^\\x00-\\x7F]"), "")
}

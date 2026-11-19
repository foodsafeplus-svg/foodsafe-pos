package fr.foodsafe.pos.app.hardware

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.ByteArrayOutputStream

/** Imprimante de tickets. Renvoie null si l'impression a réussi, sinon un message d'erreur en français. */
interface TicketPrinter {
    fun print(lines: List<String>, openDrawer: Boolean = false): String?
}

/** Aucune imprimante (tests, ou terminal sans imprimante). */
object NoPrinter : TicketPrinter {
    override fun print(lines: List<String>, openDrawer: Boolean): String? = null
}

/** Page de code de l'imprimante (commande ESC t n) et encodage des caractères correspondant. */
enum class CodePage(val label: String, val escT: Int) {
    CP858("PC858 (€)", 19),
    CP850("PC850", 2),
    WPC1252("Windows-1252", 16),
    ASCII("Sans accents", 0);

    fun encode(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (c in s) {
            val b = when (this) {
                CP858, CP850 -> CP850_MAP[c] ?: if (c == '€') (if (this == CP858) 0xD5 else -1) else -1
                WPC1252 -> WIN1252_MAP[c] ?: -1
                ASCII -> -1
            }
            when {
                c.code < 0x80 -> out.write(c.code)
                b >= 0 -> out.write(b)
                c == '€' -> out.write("EUR".toByteArray())
                else -> out.write(stripAccent(c).code.takeIf { it < 0x80 } ?: '?'.code)
            }
        }
        return out.toByteArray()
    }

    companion object {
        private val CP850_MAP = mapOf(
            'Ç' to 0x80, 'ü' to 0x81, 'é' to 0x82, 'â' to 0x83, 'ä' to 0x84, 'à' to 0x85, 'ç' to 0x87, 'ê' to 0x88,
            'ë' to 0x89, 'è' to 0x8A, 'ï' to 0x8B, 'î' to 0x8C, 'Ä' to 0x8E, 'É' to 0x90, 'ô' to 0x93, 'ö' to 0x94,
            'û' to 0x96, 'ù' to 0x97, 'Ö' to 0x99, 'Ü' to 0x9A, 'Â' to 0xB6, 'À' to 0xB7, 'Ê' to 0xD2, 'Ë' to 0xD3,
            'È' to 0xD4, 'Î' to 0xD7, 'Ï' to 0xD8, 'Ô' to 0xE2, 'Ù' to 0xEB, 'Û' to 0xEA, '°' to 0xF8, '·' to 0xFA,
            'œ' to -1, 'Œ' to -1,
        ).filterValues { it >= 0 }
        private val WIN1252_MAP: Map<Char, Int> = buildMap {
            put('€', 0x80); put('œ', 0x9C); put('Œ', 0x8C)
            "ÀÂÄÇÈÉÊËÎÏÔÖÙÛÜàâäçèéêëîïôöùûü°·".forEach { put(it, it.code) } // = Latin-1 pour ces caractères
        }
        fun stripAccent(c: Char): Char =
            java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).firstOrNull() ?: '?'
    }
}

/** Commandes ESC/POS pour un document texte en largeur fixe. */
object EscPosDocument {
    fun build(lines: List<String>, codePage: CodePage, openDrawer: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        fun b(vararg x: Int) = x.forEach { out.write(it) }
        b(0x1B, '@'.code)                                   // initialisation
        if (openDrawer) b(0x1B, 'p'.code, 0, 25, 250)       // impulsion tiroir-caisse (broche 2)
        if (lines.isEmpty()) return out.toByteArray()       // tiroir seul : ni avance ni coupe
        if (codePage != CodePage.ASCII) b(0x1B, 't'.code, codePage.escT)
        lines.forEach { out.write(codePage.encode(it)); b(0x0A) }
        repeat(4) { b(0x0A) }
        b(0x1D, 'V'.code, 66, 0)                            // avance puis coupe partielle
        return out.toByteArray()
    }
}

/**
 * Imprimante ESC/POS reliée en USB (imprimante intégrée des caisses Android sans SDK fabricant).
 * Choisit le périphérique de classe « imprimante », sinon le dernier qui a imprimé (VID/PID mémorisés).
 */
class UsbEscPosPrinter(private val context: Context) : TicketPrinter {
    private val prefs = context.getSharedPreferences("imprimante", Context.MODE_PRIVATE)
    private val usb get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    var codePage: CodePage
        get() = runCatching { CodePage.valueOf(prefs.getString("codepage", null)!!) }.getOrDefault(CodePage.CP858)
        set(v) { prefs.edit().putString("codepage", v.name).apply() }

    fun remember(d: UsbDevice) {
        prefs.edit().putInt("vid", d.vendorId).putInt("pid", d.productId).apply()
    }

    private fun find(): UsbDevice? {
        val devices = usb.deviceList.values
        val vid = prefs.getInt("vid", -1); val pid = prefs.getInt("pid", -1)
        return devices.firstOrNull { it.vendorId == vid && it.productId == pid }
            ?: devices.firstOrNull { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER } }
    }

    override fun print(lines: List<String>, openDrawer: Boolean): String? =
        printBytes(EscPosDocument.build(lines, codePage, openDrawer))

    fun printBytes(data: ByteArray): String? {
        val d = find() ?: return "Aucune imprimante USB détectée"
        if (!usb.hasPermission(d)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(d, PendingIntent.getBroadcast(context, 0, Intent("fr.foodsafe.pos.USB_PERMISSION").setPackage(context.packageName), flags))
            return "Autorisez l'accès à l'imprimante, puis relancez l'impression"
        }
        return send(usb, d, data)
    }

    /** Ticket de test : la même phrase dans chaque page de code, pour choisir celle qui affiche bien les accents. */
    fun accentTest(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, '@'.code.toByte()))
        out.write(CodePage.ASCII.encode("TEST DES ACCENTS - aucune valeur\n\n"))
        CodePage.entries.filter { it != CodePage.ASCII }.forEach { cp ->
            out.write(byteArrayOf(0x1B, 't'.code.toByte(), cp.escT.toByte()))
            out.write(CodePage.ASCII.encode("${cp.label} :\n"))
            out.write(cp.encode("  é è ê à â ç ù ô î É È À Ç 12,50 €\n\n"))
        }
        out.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A, 0x1D, 'V'.code.toByte(), 66, 0))
        return out.toByteArray()
    }

    companion object {
        /** Envoie des octets bruts sur la sortie « bulk » du périphérique. Null si tout est parti. */
        fun send(usb: UsbManager, d: UsbDevice, data: ByteArray): String? = runCatching {
            val itf = (0 until d.interfaceCount).map { d.getInterface(it) }
                .sortedByDescending { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
                .firstOrNull { i -> (0 until i.endpointCount).any { i.getEndpoint(it).let { e -> e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_OUT } } }
                ?: error("aucune sortie de données sur ce périphérique")
            val ep = (0 until itf.endpointCount).map { itf.getEndpoint(it) }
                .first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
            val conn = usb.openDevice(d) ?: error("ouverture refusée")
            try {
                check(conn.claimInterface(itf, true)) { "imprimante occupée" }
                var off = 0
                while (off < data.size) {
                    val len = minOf(4096, data.size - off)
                    val n = conn.bulkTransfer(ep, data.copyOfRange(off, off + len), len, 5000)
                    check(n > 0) { "envoi refusé (code $n)" }
                    off += n
                }
            } finally { conn.close() }
            null
        }.getOrElse { "Impression impossible : ${it.message}" }
    }
}

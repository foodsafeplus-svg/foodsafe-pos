package fr.foodsafe.pos.core.util

/**
 * Base64 standard (RFC 4648, avec remplissage). java.util.Base64 n'existe qu'à partir d'Android 8 (API 26)
 * et n'est pas couvert par le desugaring : le cœur, qui doit tourner dès Android 7, embarque son propre codec.
 */
object B64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val REVERSE = IntArray(128) { -1 }.also { r -> ALPHABET.forEachIndexed { i, c -> r[c.code] = i } }

    fun encode(data: ByteArray): String = buildString((data.size + 2) / 3 * 4) {
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else 0
            append(ALPHABET[b0 shr 2])
            append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            append(if (i + 1 < data.size) ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            append(if (i + 2 < data.size) ALPHABET[b2 and 63] else '=')
            i += 3
        }
    }

    fun decode(s: String): ByteArray {
        require(s.length % 4 == 0) { "longueur base64 invalide" }
        val pad = s.takeLast(2).count { it == '=' }
        val out = ByteArray(s.length / 4 * 3 - pad)
        var o = 0
        for (i in s.indices step 4) {
            val v = IntArray(4) { k ->
                val c = s[i + k]
                if (c == '=') 0 else REVERSE.getOrElse(c.code) { -1 }.also { require(it >= 0) { "caractère base64 invalide" } }
            }
            val n = (v[0] shl 18) or (v[1] shl 12) or (v[2] shl 6) or v[3]
            if (o < out.size) out[o++] = (n shr 16).toByte()
            if (o < out.size) out[o++] = (n shr 8).toByte()
            if (o < out.size) out[o++] = n.toByte()
        }
        return out
    }
}

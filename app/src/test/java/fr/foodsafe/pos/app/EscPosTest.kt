package fr.foodsafe.pos.app

import fr.foodsafe.pos.app.hardware.CodePage
import fr.foodsafe.pos.app.hardware.EscPosDocument
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EscPosTest {
    @Test
    fun accentsEtEuroEnPc858() {
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0x87.toByte(), 0xD5.toByte()), CodePage.CP858.encode("éç€"))
        assertEquals("EUR", String(CodePage.CP850.encode("€")))
        assertEquals("ec", String(CodePage.ASCII.encode("éç")))
    }

    @Test
    fun tiroirSeulSansCoupe() {
        val b = EscPosDocument.build(emptyList(), CodePage.CP858, openDrawer = true)
        assertArrayEquals(byteArrayOf(0x1B, '@'.code.toByte(), 0x1B, 'p'.code.toByte(), 0, 25, 250.toByte()), b)
    }

    @Test
    fun ticketSeTermineParLaCoupe() {
        val b = EscPosDocument.build(listOf("A"), CodePage.CP858, openDrawer = false)
        assertArrayEquals(byteArrayOf(0x1D, 'V'.code.toByte(), 66, 0), b.takeLast(4).toByteArray())
    }
}

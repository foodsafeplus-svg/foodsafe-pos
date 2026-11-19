package fr.foodsafe.pos.app

import fr.foodsafe.pos.app.data.FileKeyProvider
import fr.foodsafe.pos.core.journal.SignatureVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FileKeyProviderTest {
    @Test
    fun laCleLogicielleEstConserveeEntreDeuxLancements() {
        val dir = Files.createTempDirectory("keys").toFile()
        val first = FileKeyProvider("T01", dir)
        val second = FileKeyProvider("T01", dir)
        assertEquals(first.signer.keyId, second.signer.keyId)
        assertTrue(first.signer.keyId.startsWith("T01-SW-"))
        val data = "ticket".toByteArray()
        assertTrue(SignatureVerifier.verify(second.publicKey, data, first.signer.sign(data)))
    }
}

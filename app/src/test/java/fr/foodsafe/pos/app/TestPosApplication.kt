package fr.foodsafe.pos.app

import fr.foodsafe.pos.app.data.FiscalKeyProvider
import fr.foodsafe.pos.app.data.SoftwareKeyProvider

/** Robolectric ne fournit pas d'Android Keystore : clé logicielle pour les tests uniquement. */
class TestPosApplication : PosApplication() {
    override fun keyProvider(): FiscalKeyProvider = SoftwareKeyProvider("T01")
}

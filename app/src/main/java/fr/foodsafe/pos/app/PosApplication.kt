package fr.foodsafe.pos.app

import android.app.Application
import fr.foodsafe.pos.app.data.AndroidKeystoreKeyProvider
import fr.foodsafe.pos.app.data.FiscalKeyProvider
import fr.foodsafe.pos.app.data.PosDatabase
import fr.foodsafe.pos.app.data.PosRepository
import fr.foodsafe.pos.core.fiscal.Establishment

open class PosApplication : Application() {
    // SIMULÉ : établissement et terminal fixes tant que l'appairage au back-office n'existe pas.
    val establishment = Establishment(
        id = "DEMO-ETAB",
        name = "Restaurant de démonstration",
        addressLines = listOf("1 place de la République", "75003 Paris"),
        siret = "000 000 000 00000",
        vatNumber = "FR00000000000",
    )

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
    }

    val repository by lazy {
        PosRepository(PosDatabase.build(this), establishment, terminalId = "T01", keys = keyProvider())
    }

    /** Clé de signature fiscale : Android Keystore sur l'appareil (surchargée uniquement dans les tests). */
    protected open fun keyProvider(): FiscalKeyProvider = AndroidKeystoreKeyProvider("T01")
}

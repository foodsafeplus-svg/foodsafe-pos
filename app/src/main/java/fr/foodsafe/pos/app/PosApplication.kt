package fr.foodsafe.pos.app

import android.app.Application
import fr.foodsafe.pos.app.data.fiscalKeyProvider
import fr.foodsafe.pos.app.hardware.TicketPrinter
import fr.foodsafe.pos.app.hardware.UsbEscPosPrinter
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
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName?.removeSuffix("-debug") }.getOrNull()
            ?.let { fr.foodsafe.pos.core.fiscal.ReceiptRenderer.SOFTWARE = "FOODSAFE+ POS $it" }
    }

    /** Imprimante de tickets intégrée (ESC/POS en USB). */
    val printer: TicketPrinter by lazy { createPrinter() }
    protected open fun createPrinter(): TicketPrinter = UsbEscPosPrinter(this)

    val repository by lazy {
        PosRepository(PosDatabase.build(this), establishment, terminalId = "T01", keys = keyProvider(), imagesDir = java.io.File(filesDir, "images"))
    }

    /**
     * Clé de signature fiscale : Android Keystore sur l'appareil, ou clé logicielle si le Keystore du terminal
     * est défaillant (surchargée uniquement dans les tests).
     */
    protected open fun keyProvider(): FiscalKeyProvider = fiscalKeyProvider("T01", filesDir)
}

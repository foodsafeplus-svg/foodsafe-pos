package fr.foodsafe.pos.app

import android.app.Application
import fr.foodsafe.pos.app.data.PosDatabase
import fr.foodsafe.pos.app.data.PosRepository

class PosApplication : Application() {
    // SIMULÉ : établissement et terminal fixes tant que l'appairage n'existe pas.
    val repository by lazy { PosRepository(PosDatabase.build(this), establishmentId = "DEMO-ETAB", terminalId = "T01") }
}

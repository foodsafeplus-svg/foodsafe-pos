package fr.foodsafe.pos.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import fr.foodsafe.pos.app.ui.PosApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashReport.pending(this)
        if (report != null) {
            CrashReport.show(this, report) { CrashReport.clear(this); recreate() }
            return
        }
        try {
            // Ouvre la base et la clé fiscale tout de suite : une erreur ici s'affiche au lieu de fermer l'appli.
            (application as PosApplication).repository
            setContent { PosApp() }
        } catch (e: Throwable) {
            CrashReport.show(this, CrashReport.describe(this, e)) { recreate() }
        }
    }
}

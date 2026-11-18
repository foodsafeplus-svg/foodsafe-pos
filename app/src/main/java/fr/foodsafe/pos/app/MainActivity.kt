package fr.foodsafe.pos.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import fr.foodsafe.pos.app.hardware.CustomerDisplay
import fr.foodsafe.pos.app.hardware.CustomerView
import fr.foodsafe.pos.app.ui.PosApp
import fr.foodsafe.pos.app.ui.PosViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val display by lazy { CustomerDisplay(this) }

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
            val vm: PosViewModel by viewModels()
            setContent { PosApp(vm) }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    vm.state.map { CustomerView.of(it.snapshot, vm.establishmentName) }.distinctUntilChanged().collect {
                        runCatching { display.show(it) }
                    }
                }
            }
        } catch (e: Throwable) {
            CrashReport.show(this, CrashReport.describe(this, e)) { recreate() }
        }
    }

    override fun onDestroy() {
        display.close()
        super.onDestroy()
    }
}

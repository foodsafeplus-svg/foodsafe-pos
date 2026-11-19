package fr.foodsafe.pos.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
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
            hideSystemBars()
            setContent { ScaledToScreen { PosApp(vm) } }
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Caisse en plein écran : barres d'état et de navigation masquées (rappel d'un glissement depuis le bord). */
    private fun hideSystemBars() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/**
 * Adapte l'interface à la taille de l'écran : elle est dessinée pour une surface de référence de 1280 × 800 dp
 * et mise à l'échelle pour tenir dans l'écran réel, quelle que soit sa résolution ou sa densité.
 * La place en plus (écran plus large ou plus haut) est occupée par les zones extensibles.
 */
@androidx.compose.runtime.Composable
fun ScaledToScreen(content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val scale = if (w <= 0f || h <= 0f) androidx.compose.ui.platform.LocalDensity.current.density
        else minOf(w / REFERENCE_WIDTH_DP, h / REFERENCE_HEIGHT_DP)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(scale, fontScale = 1f),
        ) { content() }
    }
}

const val REFERENCE_WIDTH_DP = 1280f
const val REFERENCE_HEIGHT_DP = 800f

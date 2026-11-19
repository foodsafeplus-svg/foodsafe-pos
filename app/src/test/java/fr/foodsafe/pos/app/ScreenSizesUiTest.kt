package fr.foodsafe.pos.app

import android.graphics.Bitmap
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * L'interface tient sur des écrans de tailles et densités différentes (mise à l'échelle depuis 1280 × 800 dp).
 * Captures : écran de la caisse d'Alex (1340 × 800 px, ~213 dpi), petit écran 7" (1024 × 600) et grand écran Full HD.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestPosApplication::class)
class ScreenSizesUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun capture(name: String) {
        val dir = System.getProperty("screenshotDir")?.let(::File) ?: return
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitText(t: String) {
        repeat(150) { rule.waitForIdle(); if (rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()) return; Thread.sleep(50) }
        throw AssertionError("texte introuvable : $t")
    }

    private fun parcours(name: String, table: String) {
        waitText("Saisissez votre code PIN")
        capture("$name-connexion")
        "9999".forEach { rule.onNodeWithText(it.toString()).performClick() }
        rule.onNodeWithText("OK").performClick()
        waitText("Nouvelle table")
        rule.onNodeWithText("Nouvelle table").performClick()
        rule.onNode(hasSetTextAction()).performTextInput(table)
        rule.onNodeWithText("Ouvrir").performClick()
        waitText("Salade César")
        rule.onNodeWithText("Salade César").performClick()
        waitText("NET À PAYER : 9,50 €")
        capture("$name-vente")
        // fiche produit par appui long : tout l'onglet Général doit tenir à l'écran
        rule.onNodeWithText("Burrata").performTouchInput { longClick() }
        rule.onNodeWithText("Modifier le produit").performClick()
        waitText("TYPE DE BIEN")
        rule.waitForIdle()
        val dv = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val bmp = Bitmap.createBitmap(dv.width, dv.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { dv.draw(android.graphics.Canvas(bmp)) }
        System.getProperty("screenshotDir")?.let { File(it, "$name-fiche.png").outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) } }
        rule.onNodeWithText("TYPE DE BIEN").assertIsDisplayed()
    }

    @Config(qualifiers = "w1006dp-h601dp-land-tvdpi") @Test fun ecranCaisseAlex() = parcours("15-ecran-1340x800", "71")
    @Config(qualifiers = "w1024dp-h600dp-land-mdpi") @Test fun petitEcran() = parcours("16-ecran-1024x600", "72")
    @Config(qualifiers = "w1280dp-h720dp-land-xhdpi") @Test fun grandEcran() = parcours("17-ecran-2560x1440", "73")
}

package fr.foodsafe.pos.app

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.printToLog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Parcours UI réel (Compose + Room + core) exécuté sous Robolectric, avec captures d'écran. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class OrderScreenUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun capture(name: String) {
        val dir = System.getProperty("screenshotDir")?.let(::File) ?: return
        dir.mkdirs()
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    init { org.robolectric.shadows.ShadowLog.stream = System.out }

    @Test fun prendreCommandeEnvoyerRemiseEncaisser() {
        waitText("Table", substring = true)
        rule.onNodeWithText("Salade César").performClick()
        waitText("Total 9,50 €")
        rule.onNodeWithText("Pizza Margherita").performClick()
        waitText("Total 21,50 €")
        rule.onNodeWithText("Bière pression").performClick()
        waitText("Total 27,50 €")

        rule.onNodeWithText("Envoyer commande").performClick()
        waitText("Bons cuisine (2)")
        rule.onNodeWithText("Réclamer N1").assertIsDisplayed()
        capture("01-commande-envoyee")

        rule.onNodeWithText("Remise note %").performClick()
        rule.onNodeWithText("10 %").performClick()
        waitText("Total 24,75 €")
        capture("02-remise-10-pourcent")

        rule.onNodeWithText("Bons cuisine (2)").performClick()
        waitText("→ CUISINE")
        capture("03-bons-cuisine")

        rule.onNodeWithText("Commande").performClick()
        rule.onNodeWithText("ENCAISSER").performClick()
        waitText("Carte 24,75 €")
        rule.onNodeWithText("Carte 24,75 €").performClick()
        waitText("Nouvelle table")

        rule.onNodeWithText("Journal", substring = true).performClick()
        rule.onNodeWithText("Vérifier la chaîne").performClick()
        waitText("Chaîne intègre", substring = true)
        capture("04-journal-verifie")
    }

    /** Attente en temps réel : les écritures Room s'exécutent sur Dispatchers.IO. */
    private fun waitText(text: String, substring: Boolean = false) {
        repeat(100) {
            rule.waitForIdle()
            if (rule.onAllNodesWithTextExists(text, substring)) return
            Thread.sleep(50)
        }
        rule.onRoot().printToLog("ARBRE")
        throw AssertionError("texte introuvable : $text")
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextExists(
        text: String, substring: Boolean = false,
    ) = onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
}

package fr.foodsafe.pos.app

import android.graphics.Bitmap
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Parcours UI réel (Compose + Room + cœur) sous Robolectric, avec captures d'écran dans docs/captures.
 * Seule différence avec l'appareil : la clé de signature (Robolectric n'a pas d'Android Keystore).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi", application = TestPosApplication::class)
class OrderScreenUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    init { org.robolectric.shadows.ShadowLog.stream = System.out }

    private fun capture(name: String) {
        val dir = System.getProperty("screenshotDir")?.let(::File) ?: return
        dir.mkdirs()
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun pin(code: String) {
        code.forEach { rule.onNodeWithText(it.toString()).performClick() }
        rule.onNodeWithText("OK").performClick()
    }

    @Test fun connexionCommandeRemiseEncaissementTicketZ() {
        waitText("Saisissez votre code PIN")
        capture("01-connexion-pin")
        pin("1234")
        waitText("PIN incorrect")
        pin("1111")
        waitText("Nouvelle table")

        rule.onNodeWithText("Nouvelle table").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("43")
        rule.onNodeWithText("Ouvrir").performClick()
        waitText("Niveau produit")

        rule.onNodeWithText("Salade César").performClick(); waitText("NET À PAYER : 9,50 €")
        rule.onNodeWithText("PIZZAS").performClick()
        rule.onNodeWithText("Pizza Margherita").performClick(); waitText("NET À PAYER : 21,50 €")
        rule.onNodeWithText("BOISSONS").performClick()
        rule.onNodeWithText("Bière pression").performClick(); waitText("NET À PAYER : 27,50 €")
        rule.onNodeWithText("Envoyer commande").performClick()
        waitText("Réclamer N1")
        rule.onNodeWithText("PIZZAS").performClick()
        capture("02-commande-envoyee")

        // Remise 20 % par une serveuse : validation responsable demandée
        rule.onNodeWithText("Remise note %").performClick()
        rule.onNodeWithText("20 %").performClick()
        waitText("Validation responsable")
        rule.onNode(hasSetTextAction()).performTextInput("9999")
        rule.onNodeWithText("Valider").performClick()
        waitText("NET À PAYER : 22,00 €")

        rule.onNodeWithText("Imprimer la note").performClick()
        waitText("*** TICKET PROVISOIRE ***", substring = true)
        rule.onNodeWithText("Fermer").performClick()

        // Écran d'encaissement : 10 € en espèces au pavé, puis le reste par carte
        rule.onNodeWithText("ENCAISSER").performClick()
        waitText("RESTE À PAYER")
        rule.onNodeWithText("10").performClick()
        rule.onNodeWithText("ESPÈCES").performClick()
        waitText("12,00 €")
        capture("02b-encaissement")
        rule.onNodeWithText("CARTE").performClick()
        waitText("T01-T-000001")
        rule.onNodeWithText("Fermer").performClick()
        waitText("Retour aux tables")
        capture("03-note-soldee-remise-20")

        // Déconnexion puis connexion responsable : retour à l'accueil, jamais à la note précédente
        rule.onNodeWithText("Déconnexion").performClick()
        waitText("Saisissez votre code PIN")
        pin("9999")
        waitText("Nouvelle table")
        rule.onNodeWithText("CLÔTURES").performClick()
        rule.onNodeWithText("Clôture Z").performClick()
        waitText("CLÔTURE Z n° 1", substring = true)
        rule.onNodeWithText("Fermer").performClick()
        waitText("1 ticket")
        capture("04-clotures")

        rule.onNodeWithText("JOURNAL").performClick()
        rule.onNodeWithText("Vérifier l'intégrité").performClick()
        waitText("Documents fiscaux intègres", substring = true)
        capture("05-integrite-verifiee")
    }

    /** Attente en temps réel : les écritures Room s'exécutent sur Dispatchers.IO. */
    private fun waitText(text: String, substring: Boolean = false) {
        repeat(150) {
            rule.waitForIdle()
            if (rule.exists(text, substring)) return
            Thread.sleep(50)
        }
        rule.onRoot().printToLog("ARBRE")
        throw AssertionError("texte introuvable : $text")
    }

    private fun AndroidComposeTestRule<*, *>.exists(text: String, substring: Boolean) =
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
}

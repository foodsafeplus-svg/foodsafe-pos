package fr.foodsafe.pos.app

import android.graphics.Bitmap
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
        rule.onNodeWithText("ALCOOLS").performClick()
        rule.onNodeWithText("Bière pression").performClick(); waitText("NET À PAYER : 27,50 €")
        rule.onNodeWithText("ENVOYER COMMANDE").performClick()
        waitText("RÉCLAMER N1")
        rule.onNodeWithText("PIZZAS").performClick()
        capture("02-commande-envoyee")

        // Remise 20 % par une serveuse : validation responsable demandée
        rule.onNodeWithText("REMISE NOTE %").performClick()
        rule.onNodeWithText("20 %").performClick()
        waitText("Validation responsable")
        rule.onNode(hasSetTextAction()).performTextInput("9999")
        rule.onNodeWithText("Valider").performClick()
        waitText("NET À PAYER : 22,00 €")

        rule.onNodeWithText("IMPRIMER LA NOTE").performClick()
        waitText("*** TICKET PROVISOIRE ***", substring = true)
        rule.onNodeWithText("Fermer").performClick()

        // Raccourci de l'écran de vente : billet de 10 € en espèces, puis le reste par carte à l'écran d'encaissement
        rule.onNodeWithText("10 €").performClick()
        waitText("Reste à payer 12,00 €")
        rule.onNodeWithText("ENCAISSER").performClick()
        waitText("RESTE À PAYER")
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
    @Test fun menuAccueilCompteurEspecesCalculatrice() {
        waitText("Saisissez votre code PIN")
        pin("1111")
        waitText("Nouvelle table")
        rule.onNodeWithText("ACCUEIL").performClick()
        waitText("COMPTEUR D'ESPÈCES")
        capture("06-menu-accueil")

        rule.onNodeWithText("COMPTEUR D'ESPÈCES").performClick()
        waitText("Quantité")
        // 2 × 2 €, 5 × 5 €, 25 × 0,20 €, 34 × 0,50 €, 5 × 10 € = 101,00 €
        listOf("2" to "2 €", "5" to "5 €", "25" to "0,20 €", "34" to "0,50 €", "5" to "10 €").forEach { (q, d) ->
            q.forEach { rule.onNodeWithTag("qte-$it").performClick() }
            rule.onNodeWithText(d).performClick()
        }
        waitText("TOTAL : 101,00 €")
        rule.onNodeWithText("10 €").performClick() // sans quantité : +1
        waitText("TOTAL : 111,00 €")
        rule.onNodeWithText("−").performClick()
        waitText("TOTAL : 101,00 €")
        capture("07-compteur-especes")
        rule.onNodeWithText("Imprimer").performClick()
        waitText("Comptage imprimé")

        rule.onNodeWithText("ACCUEIL").performClick()
        rule.onNodeWithText("CALCULATRICE").performClick()
        waitText("Retour")
        listOf("1", "2", "+", "3", "0", "=").forEach { rule.onNodeWithText(it).performClick() }
        waitText("42")
        rule.onNodeWithText("Retour").performClick()
        waitText("COMPTEUR D'ESPÈCES")
    }

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

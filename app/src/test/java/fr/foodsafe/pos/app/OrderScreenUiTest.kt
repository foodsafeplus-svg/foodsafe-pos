package fr.foodsafe.pos.app

import android.graphics.Bitmap
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
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
        waitText("Salade César") // le clavier se charge depuis la base

        rule.onNodeWithText("Salade César").performClick(); waitText("NET À PAYER : 9,50 €")
        rule.onNodeWithText("PIZZAS").performClick()
        rule.onNodeWithText("Pizza Margherita").performClick()
        waitText("Suppléments pizza", substring = true) // groupe facultatif : on valide sans choix
        rule.onNodeWithText("Valider").performClick(); waitText("NET À PAYER : 21,50 €")
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

    @Test fun produitsEtClaviersPageCachee() {
        waitText("Saisissez votre code PIN")
        pin("9999")
        waitText("Nouvelle table")
        rule.onNodeWithText("ACCUEIL").performClick()
        rule.onNodeWithText("PRODUITS ET CLAVIERS").performClick()
        rule.onNodeWithText("TOUS LES PRODUITS").performClick()
        waitText("TOUS LES PRODUITS (", substring = true)
        waitText("Salade César") // catalogue chargé
        val avant = productCount()
        rule.onNodeWithText("+ Nouveau produit").performClick()
        waitText("NOUVEAU PRODUIT")
        rule.onNodeWithTag("fiche-nom").performTextInput("Sirop menthe")
        rule.onNodeWithTag("fiche-prix").performTextInput("2,50")
        capture("18-fiche-produit")
        rule.onNodeWithText("Enregistrer").performClick()
        waitText("TOUS LES PRODUITS (${avant + 1})")
        waitText("Sirop menthe")
        capture("08-tous-les-produits")

        // Page cachée « Sirops » + renvoi depuis la page Softs
        rule.onNodeWithText("← Retour").performClick()
        rule.onNodeWithText("CLAVIERS").performClick()
        waitText("CLAVIER 1")
        rule.onNodeWithText("+ Nouvelle page").performClick()
        rule.onNode(hasSetTextAction() and hasText("Nom")).performTextInput("Sirops")
        rule.onNodeWithText("Cachée (sous-page)").performClick()
        rule.onNodeWithText("Valider").performClick()
        waitText("SIROPS")
        rule.onNodeWithText("SIROPS").performClick()
        rule.onAllNodesWithText("+")[0].performClick()
        waitText("Contenu de la case")
        // le nouveau produit est dans la première catégorie (ordre alphabétique), affichée par défaut
        rule.onNodeWithText("Sirop menthe").performClick()
        waitText("2,50")
        rule.onNodeWithText("SOFTS").performClick()
        rule.onAllNodesWithText("+")[0].performClick()
        rule.onNodeWithText("Aller à une page").performClick()
        waitText("→ Sirops (cachée)")
        rule.onNodeWithText("→ Sirops (cachée)").performClick()
        waitText("Sirops")
        capture("09-claviers")

        // En vente : Softs → Sirops (page cachée) → Sirop menthe
        rule.onNodeWithText("TABLES").performClick()
        rule.onNodeWithText("Nouvelle table").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("7")
        rule.onNodeWithText("Ouvrir").performClick()
        waitText("Niveau produit")
        rule.onNodeWithText("SOFTS").performClick()
        rule.onNodeWithText("Sirops").performClick()
        waitText("← Sirops")
        rule.onNodeWithText("Sirop menthe").performClick()
        waitText("NET À PAYER : 2,50 €")
        rule.onNodeWithText("← Sirops").performClick()
        waitText("Soda 33 cl")
    }

    @Test fun assistantCreationProduitPuisVente() {
        waitText("Saisissez votre code PIN")
        pin("9999")
        waitText("Nouvelle table")
        rule.onNodeWithText("ACCUEIL").performClick()
        rule.onNodeWithText("PRODUITS ET CLAVIERS").performClick()
        rule.onNodeWithText("ASSISTANT CRÉATION PRODUIT").performClick()
        waitText("1. Nom")
        rule.onNode(hasSetTextAction()).performTextInput("Café gourmand")
        rule.onNodeWithText("Suivant").performClick()
        listOf("7", ",", "9", "0").forEach { rule.onNodeWithTag("prix-$it").performClick() }
        waitText("7,90 €")
        rule.onNodeWithText("Suivant").performClick()
        waitText("Taux de TVA")
        rule.onNodeWithText("Suivant").performClick()
        rule.onNodeWithText("Desserts").performClick()
        rule.onNodeWithText("Suivant").performClick()
        waitText("Image du bouton (facultatif)")
        rule.onNodeWithText("Suivant").performClick()
        waitText("Page où placer le bouton")
        rule.onNodeWithText("Suivant").performClick() // page « Desserts » proposée d'après la catégorie
        waitText("Touchez une case libre", substring = true)
        capture("10-assistant-placement")
        rule.onAllNodesWithText("+")[0].performClick()
        waitText("TOUS LES PRODUITS")

        rule.onNodeWithText("TABLES").performClick()
        rule.onNodeWithText("Nouvelle table").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("5")
        rule.onNodeWithText("Ouvrir").performClick()
        waitText("Niveau produit")
        rule.onNodeWithText("DESSERTS").performClick()
        rule.onNodeWithText("Café gourmand").performClick()
        waitText("NET À PAYER : 7,90 €")
    }

    @Test fun commentairesEtSupplementsEnVente() {
        waitText("Saisissez votre code PIN")
        pin("1111")
        waitText("Nouvelle table")
        rule.onNodeWithText("Nouvelle table").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("8")
        rule.onNodeWithText("Ouvrir").performClick()
        waitText("Niveau produit")
        waitText("Salade César")
        rule.onNodeWithText("PIZZAS").performClick()
        rule.onNodeWithText("Pizza Reine").performClick()
        waitText("Suppléments pizza", substring = true)
        rule.onNodeWithText("Œuf +1,00").performClick()
        rule.onNodeWithText("Sans olives").performClick()
        waitText("Suppléments : +1,00 €")
        rule.onNodeWithText("Valider").performClick()
        waitText("NET À PAYER : 14,50 €")
        waitText("    > Sans olives")
        // commentaire ajouté après coup sur la ligne (groupes du produit)
        rule.onAllNodesWithText("Pizza Reine")[0].performClick() // la ligne de la note
        rule.onNodeWithText("COMMENTAIRE").performClick()
        waitText("Suppléments pizza · plusieurs choix")
        rule.onNodeWithText("Bien cuite").performClick()
        rule.onNodeWithText("Valider").performClick()
        waitText("    > Bien cuite")
        rule.onNodeWithText("ENVOYER COMMANDE").performClick()
        waitText("Commande envoyée en cuisine")
        capture("12-note-avec-supplements")
    }

    @Test fun ecranCommentairesEtSupplements() {
        waitText("Saisissez votre code PIN")
        pin("9999")
        waitText("Nouvelle table")
        rule.onNodeWithText("ACCUEIL").performClick()
        rule.onNodeWithText("PRODUITS ET CLAVIERS").performClick()
        rule.onNodeWithText("COMMENTAIRES ET SUPPLÉMENTS").performClick()
        waitText("CUISSON")
        rule.onNodeWithText("Diluants").performClick()
        waitText("DILUANTS")
        rule.onNodeWithText("+ Nouvel élément").performClick()
        rule.onNode(hasSetTextAction() and hasText("Libellé")).performTextInput("Schweppes")
        rule.onNode(hasSetTextAction() and hasText("Prix (vide = gratuit)")).performTextInput("1,20")
        rule.onNodeWithText("Enregistrer").performClick()
        waitText("Schweppes")
        waitText("+1,20 €")
        capture("13-commentaires-supplements")
    }

    @Test fun appuiLongModifierEtDeplacerUneTouche() {
        waitText("Saisissez votre code PIN")
        pin("1111")
        waitText("Nouvelle table")
        rule.onNodeWithText("Nouvelle table").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("61")
        rule.onNodeWithText("Ouvrir").performClick()
        waitText("Salade César")
        rule.onNodeWithText("Burrata").performTouchInput { longClick() }
        waitText("réservé au responsable", substring = true)
        rule.onNodeWithText("Déconnexion").performClick()

        waitText("Saisissez votre code PIN")
        pin("9999")
        waitText("Nouvelle table")
        rule.onNodeWithText("Nouvelle table").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("62")
        rule.onNodeWithText("Ouvrir").performClick()
        waitText("Salade César")
        // modifier le prix depuis l'appui long
        rule.onNodeWithText("Burrata").performTouchInput { longClick() }
        rule.onNodeWithText("Modifier le produit").performClick()
        waitText("PRIX DE VENTE TTC NORMAL")
        rule.onNodeWithTag("fiche-prix").performTextReplacement("11,50")
        rule.onNodeWithText("Enregistrer").performClick()
        waitText("11,50")
        rule.onNodeWithText("Burrata").performClick()
        waitText("NET À PAYER : 11,50 €")
        // déplacer la Salade César sur la première case libre
        rule.onNodeWithText("Salade César").performTouchInput { longClick() }
        rule.onNodeWithText("Déplacer la touche").performClick()
        waitText("touchez la case d'arrivée", substring = true)
        capture("14-deplacer-touche")
        rule.onAllNodesWithText("+")[0].performClick()
        waitText("Touche déplacée")
        rule.onNodeWithText("Salade César").performClick()
        waitText("NET À PAYER : 21,00 €")
    }

    private fun productCount(): Int = rule.onAllNodes(hasText("TOUS LES PRODUITS (", substring = true)).fetchSemanticsNodes()
        .first().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text.substringAfter('(').substringBefore(')').toInt()

    private fun waitText(text: String, substring: Boolean = false) {
        repeat(150) {
            rule.waitForIdle()
            if (rule.exists(text, substring)) return
            Thread.sleep(50)
        }
        val roots = rule.onAllNodes(androidx.compose.ui.test.isRoot())
        repeat(roots.fetchSemanticsNodes().size) { roots[it].printToLog("ARBRE") }
        throw AssertionError("texte introuvable : $text")
    }

    private fun AndroidComposeTestRule<*, *>.exists(text: String, substring: Boolean) =
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
}

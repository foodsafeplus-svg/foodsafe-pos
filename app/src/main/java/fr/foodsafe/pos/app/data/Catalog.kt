package fr.foodsafe.pos.app.data

import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.ServiceLevel
import fr.foodsafe.pos.core.tax.FrenchVat

data class Product(
    val id: String,
    val label: String,
    val price: Cents,
    val vat: BasisPoints,
    val destination: String,
    val defaultLevel: ServiceLevel,
    /** Catégorie (rangement et statistiques) ; la page du clavier est indépendante. */
    val category: String = "Divers",
    val subCategory: String? = null,
    val imageId: String? = null,
    val color: Int? = null,
    val active: Boolean = true,
)

/** Catalogue de démonstration : copié en base au premier lancement, puis modifiable dans « Produits et claviers ». */
object DemoCatalog {
    /** Deux rangées de quatre onglets, comme sur les caisses de comptoir. */
    val categories = listOf("Entrées", "Pizzas", "Pâtes", "Desserts", "Glaces", "Softs", "Alcools", "Cafés")

    private fun p(id: String, label: String, cents: Long, vat: fr.foodsafe.pos.core.money.BasisPoints, dest: String, level: ServiceLevel, cat: String) =
        Product(id, label, Cents(cents), vat, dest, level, cat)

    val products = listOf(
        p("salade", "Salade César", 950, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(1), "Entrées"),
        p("burrata", "Burrata", 1100, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(1), "Entrées"),
        p("bruschetta", "Bruschetta", 750, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(1), "Entrées"),
        p("margherita", "Pizza Margherita", 1200, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pizzas"),
        p("reine", "Pizza Reine", 1350, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pizzas"),
        p("4fromages", "Pizza 4 fromages", 1450, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pizzas"),
        p("calzone", "Calzone", 1300, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pizzas"),
        p("chorizo", "Pizza Chorizo", 1350, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pizzas"),
        p("vegetarienne", "Pizza Végétarienne", 1300, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pizzas"),
        p("carbonara", "Pâtes carbonara", 1250, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pâtes"),
        p("bolognaise", "Pâtes bolognaise", 1200, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pâtes"),
        p("tiramisu", "Tiramisu", 650, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(3), "Desserts"),
        p("pannacotta", "Panna cotta", 600, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(3), "Desserts"),
        p("glace", "Glace 2 boules", 500, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(3), "Glaces"),
        p("glace3", "Glace 3 boules", 650, FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(3), "Glaces"),
        p("eau", "Eau minérale", 400, FrenchVat.INTERMEDIATE, "BAR", ServiceLevel.DIRECT, "Softs"),
        p("soda", "Soda 33 cl", 350, FrenchVat.INTERMEDIATE, "BAR", ServiceLevel.DIRECT, "Softs"),
        p("jus", "Jus d'orange", 400, FrenchVat.INTERMEDIATE, "BAR", ServiceLevel.DIRECT, "Softs"),
        p("biere", "Bière pression", 600, FrenchVat.NORMAL, "BAR", ServiceLevel.DIRECT, "Alcools"),
        p("vin", "Verre de vin", 550, FrenchVat.NORMAL, "BAR", ServiceLevel.DIRECT, "Alcools"),
        p("cafe", "Café", 200, FrenchVat.INTERMEDIATE, "BAR", ServiceLevel(3), "Cafés"),
        p("the", "Thé", 300, FrenchVat.INTERMEDIATE, "BAR", ServiceLevel(3), "Cafés"),
    )
}

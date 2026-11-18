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
    /** Famille affichée en onglet sur l'écran de prise de commande. */
    val category: String = "Divers",
)

/** SIMULÉ : catalogue de démonstration codé en dur (le catalogue éditable arrive en phase 1). */
object DemoCatalog {
    val categories = listOf("Entrées", "Pizzas", "Desserts", "Boissons")

    val products = listOf(
        Product("salade", "Salade César", Cents(950), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(1), "Entrées"),
        Product("burrata", "Burrata", Cents(1100), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(1), "Entrées"),
        Product("bruschetta", "Bruschetta", Cents(750), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(1), "Entrées"),
        Product("margherita", "Pizza Margherita", Cents(1200), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pizzas"),
        Product("reine", "Pizza Reine", Cents(1350), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pizzas"),
        Product("4fromages", "Pizza 4 fromages", Cents(1450), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pizzas"),
        Product("calzone", "Calzone", Cents(1300), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pizzas"),
        Product("chorizo", "Pizza Chorizo", Cents(1350), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pizzas"),
        Product("vegetarienne", "Pizza Végétarienne", Cents(1300), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2), "Pizzas"),
        Product("tiramisu", "Tiramisu", Cents(650), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(3), "Desserts"),
        Product("pannacotta", "Panna cotta", Cents(600), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(3), "Desserts"),
        Product("glace", "Glace 2 boules", Cents(500), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(3), "Desserts"),
        Product("biere", "Bière pression", Cents(600), FrenchVat.NORMAL, "BAR", ServiceLevel.DIRECT, "Boissons"),
        Product("vin", "Verre de vin", Cents(550), FrenchVat.NORMAL, "BAR", ServiceLevel.DIRECT, "Boissons"),
        Product("eau", "Eau minérale", Cents(400), FrenchVat.INTERMEDIATE, "BAR", ServiceLevel.DIRECT, "Boissons"),
        Product("soda", "Soda 33 cl", Cents(350), FrenchVat.INTERMEDIATE, "BAR", ServiceLevel.DIRECT, "Boissons"),
        Product("cafe", "Café", Cents(200), FrenchVat.INTERMEDIATE, "BAR", ServiceLevel(3), "Boissons"),
    )
}

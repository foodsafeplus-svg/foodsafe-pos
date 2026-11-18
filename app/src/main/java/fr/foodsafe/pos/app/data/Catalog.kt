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
)

/** SIMULÉ : catalogue de démonstration codé en dur (le catalogue éditable arrive en phase 1). */
object DemoCatalog {
    val products = listOf(
        Product("salade", "Salade César", Cents(950), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(1)),
        Product("burrata", "Burrata", Cents(1100), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(1)),
        Product("margherita", "Pizza Margherita", Cents(1200), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2)),
        Product("reine", "Pizza Reine", Cents(1350), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(2)),
        Product("tiramisu", "Tiramisu", Cents(650), FrenchVat.INTERMEDIATE, "CUISINE", ServiceLevel(3)),
        Product("biere", "Bière pression", Cents(600), FrenchVat.NORMAL, "BAR", ServiceLevel.DIRECT),
        Product("vin", "Verre de vin", Cents(550), FrenchVat.NORMAL, "BAR", ServiceLevel.DIRECT),
        Product("eau", "Eau minérale", Cents(400), FrenchVat.INTERMEDIATE, "BAR", ServiceLevel.DIRECT),
        Product("cafe", "Café", Cents(200), FrenchVat.INTERMEDIATE, "BAR", ServiceLevel(3)),
    )
}

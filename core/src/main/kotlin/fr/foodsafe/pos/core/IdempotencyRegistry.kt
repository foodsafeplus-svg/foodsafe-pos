package fr.foodsafe.pos.core

/**
 * Garantit qu'une commande métier portant la même clé (générée au tap) n'est exécutée qu'une fois :
 * une répétition (double tap, renvoi après coupure réseau) renvoie le résultat initial.
 * En production : table SQL avec clé UNIQUE, dans la même transaction que l'opération.
 */
class IdempotencyRegistry<R> {
    private val results = HashMap<String, R>()
    @Synchronized
    fun execute(key: String, block: () -> R): R = results[key] ?: block().also { results[key] = it }
}

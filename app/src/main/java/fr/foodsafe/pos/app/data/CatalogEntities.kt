package fr.foodsafe.pos.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Produit vendable. Le prix et la TVA ne valent que pour les ventes suivantes : les tickets émis ne changent jamais. */
@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val label: String,
    val priceCents: Long,
    val vatBp: Int,
    val destination: String,
    val defaultLevel: Int,
    val category: String,
    val subCategory: String?,
    val imageId: String?,
    val color: Int?,
    val active: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "keyboards")
data class KeyboardEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Int,
)

/** Page d'un clavier : visible = onglet en haut de la caisse ; cachée = accessible par une case « aller à la page ». */
@Entity(tableName = "keyboard_pages", indices = [Index("keyboardId")])
data class KeyboardPageEntity(
    @PrimaryKey val id: String,
    val keyboardId: String,
    val name: String,
    val position: Int,
    val visible: Boolean,
)

/** Case d'une page (grille de 5 colonnes, numérotée ligne par ligne). kind : PRODUCT, PAGE (renvoi), FORMULA. */
@Entity(tableName = "keyboard_cells", primaryKeys = ["pageId", "slot"])
data class KeyboardCellEntity(
    val pageId: String,
    val slot: Int,
    val kind: String,
    val targetId: String,
    val color: Int?,
)

/** Image importée (fichier dans le stockage privé de l'application). */
@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey val id: String,
    val name: String,
    val fileName: String,
    val createdAt: Long,
)

/**
 * Groupe de commentaires et suppléments (ex. « Cuisson », « Diluants »). Obligatoire : la commande ne part pas
 * en cuisine sans un choix. Plusieurs : on peut cocher plusieurs éléments. Supprimé : masqué, gardé pour l'historique.
 */
@Entity(tableName = "modifier_groups")
data class ModifierGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mandatory: Boolean,
    val multiple: Boolean,
    val position: Int,
    val deleted: Boolean,
)

/** Élément d'un groupe : gratuit (commentaire) ou payant (supplément). vatBp null = la TVA suit celle du produit. */
@Entity(tableName = "modifier_items", indices = [Index("groupId")])
data class ModifierItemEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val label: String,
    val priceCents: Long,
    val vatBp: Int?,
    val position: Int,
)

/** Groupes proposés à la vente d'un produit, dans l'ordre. */
@Entity(tableName = "product_modifier_groups", primaryKeys = ["productId", "groupId"])
data class ProductModifierGroupEntity(
    val productId: String,
    val groupId: String,
    val position: Int,
)

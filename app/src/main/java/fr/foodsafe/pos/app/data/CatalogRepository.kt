package fr.foodsafe.pos.app.data

import fr.foodsafe.pos.core.money.BasisPoints
import fr.foodsafe.pos.core.money.Cents
import fr.foodsafe.pos.core.order.ServiceLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.json.JSONObject

/** Case d'un clavier telle que l'affiche la caisse. */
sealed interface KeyboardCell {
    val slot: Int
    val color: Int?
    data class ProductCell(override val slot: Int, val product: Product, override val color: Int?) : KeyboardCell
    data class PageLink(override val slot: Int, val page: KeyboardPage, override val color: Int?) : KeyboardCell
}

data class KeyboardPage(val id: String, val keyboardId: String, val name: String, val position: Int, val visible: Boolean)

data class Keyboard(val id: String, val name: String, val pages: List<KeyboardPage>, val cells: Map<String, Map<Int, KeyboardCell>>) {
    val visiblePages get() = pages.filter { it.visible }
    fun cellsOf(pageId: String): Map<Int, KeyboardCell> = cells[pageId].orEmpty()
}

data class CatalogImage(val id: String, val name: String, val file: java.io.File?)

data class CatalogState(val products: List<Product>, val keyboards: List<Keyboard>, val images: List<CatalogImage> = emptyList()) {
    fun image(id: String?): CatalogImage? = id?.let { i -> images.firstOrNull { it.id == i } }
    val categories: List<String> get() = products.map { it.category }.distinct()
    fun keyboard(id: String?): Keyboard? = keyboards.firstOrNull { it.id == id } ?: keyboards.firstOrNull()
}

/**
 * Catalogue éditable : produits, claviers, pages et cases. Toute modification passe par ici : droit vérifié
 * (responsable), écriture en transaction, événement au journal (avant / après pour les produits).
 */
class CatalogRepository internal constructor(
    private val db: PosDatabase,
    private val ids: () -> String,
    private val clock: () -> Long,
    private val log: (actorId: String, type: String, entityType: String, entityId: String, before: String?, after: String?) -> Unit,
    private val requireRight: (actorId: String) -> Unit,
    /** Dossier des images importées (stockage privé de l'application). */
    private val imagesDir: java.io.File? = null,
) {
    private val dao = db.catalogDao()

    val state: Flow<CatalogState> = combine(
        combine(dao.observeProducts(), dao.observeKeyboards(), dao.observePages(), dao.observeCells()) { p, k, pg, c -> build(p, k, pg, c) },
        dao.observeImages(),
    ) { st, imgs -> st.copy(images = imgs.map { CatalogImage(it.id, it.name, imagesDir?.let { d -> java.io.File(d, it.fileName) }) }) }

    private fun build(p: List<ProductEntity>, k: List<KeyboardEntity>, pg: List<KeyboardPageEntity>, c: List<KeyboardCellEntity>): CatalogState {
        val products = p.map { it.toDomain() }
        val byId = products.associateBy { it.id }
        val pages = pg.map { KeyboardPage(it.id, it.keyboardId, it.name, it.position, it.visible) }
        val pageById = pages.associateBy { it.id }
        val cells = c.mapNotNull { e ->
            when (e.kind) {
                "PRODUCT" -> byId[e.targetId]?.takeIf { it.active }?.let { KeyboardCell.ProductCell(e.slot, it, e.color) }
                "PAGE" -> pageById[e.targetId]?.let { KeyboardCell.PageLink(e.slot, it, e.color) }
                else -> null
            }?.let { e.pageId to it }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.associateBy { it.slot } }
        val keyboards = k.map { kb -> Keyboard(kb.id, kb.name, pages.filter { it.keyboardId == kb.id }.sortedBy { it.position }, cells) }
        return CatalogState(products, keyboards)
    }

    /** Premier lancement : le catalogue de démonstration devient un clavier « Clavier 1 », une page visible par catégorie. */
    fun seedIfEmpty() = db.runInTransaction {
        if (dao.productCount() > 0 || dao.keyboards().isNotEmpty()) return@runInTransaction
        val now = clock()
        DemoCatalog.products.forEach { dao.upsertProduct(it.toEntity(now)) }
        val kb = KeyboardEntity("kb-1", "Clavier 1", 0)
        dao.upsertKeyboard(kb)
        DemoCatalog.categories.forEachIndexed { i, cat ->
            val page = KeyboardPageEntity("page-${i + 1}", kb.id, cat, i, true)
            dao.upsertPage(page)
            dao.upsertCells(DemoCatalog.products.filter { it.category == cat }.mapIndexed { slot, p -> KeyboardCellEntity(page.id, slot, "PRODUCT", p.id, null) })
        }
    }

    // ---------- Produits ----------

    fun saveProduct(actorId: String, p: Product): Product {
        requireRight(actorId)
        require(p.label.isNotBlank()) { "nom du produit requis" }
        require(!p.price.isNegative) { "prix négatif" }
        return db.runInTransaction<Product> {
            val before = dao.product(p.id)
            dao.upsertProduct(p.toEntity(clock()))
            log(actorId, if (before == null) "PRODUCT_CREATED" else "PRODUCT_UPDATED", "product", p.id, before?.summary(), p.toEntity(0).summary())
            p
        }
    }

    fun newProductId() = "p-" + ids()

    /** Assistant : crée le produit et le place dans la case choisie, en une seule transaction. */
    fun createAndPlace(actorId: String, p: Product, pageId: String, slot: Int): Product {
        requireRight(actorId)
        require(p.label.isNotBlank()) { "nom du produit requis" }
        require(dao.page(pageId) != null) { "page inconnue" }
        return db.runInTransaction<Product> {
            dao.upsertProduct(p.toEntity(clock()))
            log(actorId, "PRODUCT_CREATED", "product", p.id, null, p.toEntity(0).summary())
            dao.upsertCell(KeyboardCellEntity(pageId, slot, "PRODUCT", p.id, p.color))
            log(actorId, "KEYBOARD_UPDATED", "keyboard", pageId, null, null)
            p
        }
    }

    // ---------- Images ----------

    /** Enregistre une image déjà réduite (JPEG) dans le stockage privé. */
    fun addImage(actorId: String, name: String, jpeg: ByteArray): String {
        requireRight(actorId)
        val dir = imagesDir ?: error("stockage des images indisponible")
        dir.mkdirs()
        val id = "img-" + ids()
        val file = java.io.File(dir, "$id.jpg")
        file.writeBytes(jpeg)
        return db.runInTransaction<String> {
            dao.upsertImage(ImageEntity(id, name.ifBlank { "Image" }, file.name, clock()))
            log(actorId, "IMAGE_ADDED", "image", id, null, name)
            id
        }
    }

    /** Supprime une image ; les produits qui l'utilisaient n'ont plus d'image. */
    fun deleteImage(actorId: String, id: String) {
        requireRight(actorId)
        db.runInTransaction {
            dao.products().filter { it.imageId == id }.forEach { dao.upsertProduct(it.copy(imageId = null, updatedAt = clock())) }
            dao.imageById(id)?.let { img -> dao.deleteImage(img); imagesDir?.let { java.io.File(it, img.fileName).delete() } }
            log(actorId, "IMAGE_DELETED", "image", id, null, null)
        }
    }

    // ---------- Claviers et pages ----------

    fun addKeyboard(actorId: String, name: String): String = edit(actorId, "KEYBOARD_CREATED", name) {
        val id = "kb-" + ids()
        dao.upsertKeyboard(KeyboardEntity(id, name.ifBlank { "Clavier" }, dao.keyboards().size))
        dao.upsertPage(KeyboardPageEntity("page-" + ids(), id, "Page 1", 0, true))
        id
    }

    fun renameKeyboard(actorId: String, id: String, name: String) = edit(actorId, "KEYBOARD_UPDATED", id) {
        dao.keyboards().first { it.id == id }.let { dao.upsertKeyboard(it.copy(name = name.ifBlank { it.name })) }
    }

    fun addPage(actorId: String, keyboardId: String, name: String, visible: Boolean): String = edit(actorId, "KEYBOARD_UPDATED", keyboardId) {
        val id = "page-" + ids()
        dao.upsertPage(KeyboardPageEntity(id, keyboardId, name.ifBlank { "Nouvelle page" }, dao.pages(keyboardId).size, visible))
        id
    }

    fun updatePage(actorId: String, pageId: String, name: String? = null, visible: Boolean? = null) = edit(actorId, "KEYBOARD_UPDATED", pageId) {
        val p = dao.page(pageId) ?: error("page inconnue")
        dao.upsertPage(p.copy(name = name?.ifBlank { p.name } ?: p.name, visible = visible ?: p.visible))
    }

    /** Déplace une page d'un cran (−1 = vers le haut). */
    fun movePage(actorId: String, pageId: String, delta: Int) = edit(actorId, "KEYBOARD_UPDATED", pageId) {
        val p = dao.page(pageId) ?: error("page inconnue")
        val list = dao.pages(p.keyboardId).toMutableList()
        val i = list.indexOfFirst { it.id == pageId }
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i != j) { val x = list.removeAt(i); list.add(j, x) }
        dao.upsertPages(list.mapIndexed { pos, e -> e.copy(position = pos) })
    }

    fun deletePage(actorId: String, pageId: String) = edit(actorId, "KEYBOARD_UPDATED", pageId) {
        val p = dao.page(pageId) ?: error("page inconnue")
        require(dao.pages(p.keyboardId).size > 1) { "un clavier garde au moins une page" }
        dao.deleteCellsOf(pageId); dao.deleteLinksTo(pageId); dao.deletePage(pageId)
        dao.upsertPages(dao.pages(p.keyboardId).mapIndexed { pos, e -> e.copy(position = pos) })
    }

    fun setProductCell(actorId: String, pageId: String, slot: Int, productId: String, color: Int?) = edit(actorId, "KEYBOARD_UPDATED", pageId) {
        require(dao.product(productId) != null) { "produit inconnu" }
        dao.upsertCell(KeyboardCellEntity(pageId, slot, "PRODUCT", productId, color))
    }

    fun setPageLinkCell(actorId: String, pageId: String, slot: Int, targetPageId: String, color: Int?) = edit(actorId, "KEYBOARD_UPDATED", pageId) {
        require(targetPageId != pageId) { "une page ne peut pas renvoyer vers elle-même" }
        require(dao.page(targetPageId) != null) { "page inconnue" }
        dao.upsertCell(KeyboardCellEntity(pageId, slot, "PAGE", targetPageId, color))
    }

    fun clearCell(actorId: String, pageId: String, slot: Int) = edit(actorId, "KEYBOARD_UPDATED", pageId) { dao.deleteCell(pageId, slot) }

    /** Première case libre d'une page (pour l'assistant de création). */
    fun firstFreeSlot(pageId: String, cells: Map<Int, KeyboardCell>): Int = generateSequence(0) { it + 1 }.first { it !in cells }

    private fun <T> edit(actorId: String, type: String, entityId: String, block: () -> T): T {
        requireRight(actorId)
        return db.runInTransaction<T> { block().also { log(actorId, type, "keyboard", entityId, null, null) } }
    }
}

internal fun ProductEntity.toDomain() = Product(
    id, label, Cents(priceCents), BasisPoints(vatBp), destination, ServiceLevel(defaultLevel), category, subCategory, imageId, color, active,
)

internal fun Product.toEntity(now: Long) = ProductEntity(
    id, label, price.value, vat.value, destination, defaultLevel.value, category, subCategory, imageId, color, active, now,
)

private fun ProductEntity.summary() = JSONObject()
    .put("label", label).put("priceCents", priceCents).put("vatBp", vatBp).put("category", category)
    .put("subCategory", subCategory ?: "").put("level", defaultLevel).put("destination", destination).put("active", active)
    .toString()

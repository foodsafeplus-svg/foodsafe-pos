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
    data class ProductCell(override val slot: Int, val product: Product, override val color: Int?, val tariff: Int? = null) : KeyboardCell {
        /** Prix vendu par cette touche (tarif bloqué ou normal). */
        val price get() = product.priceFor(tariff)
    }
    data class PageLink(override val slot: Int, val page: KeyboardPage, override val color: Int?) : KeyboardCell
    data class FormulaCell(override val slot: Int, val formula: Formula, override val color: Int?) : KeyboardCell
}

enum class FormulaKind(val label: String) { MENU("Formule"), PACKAGE("Package"), PROMO("Promo automatique") }

/** Étape de formule : produits possibles et niveau d'envoi (null = niveau du produit). */
data class FormulaStep(val id: String, val name: String, val level: ServiceLevel?, val productIds: List<String>)

data class Formula(val id: String, val name: String, val kind: FormulaKind, val price: Cents, val steps: List<FormulaStep>) {
    /** Toutes les étapes n'ont qu'un produit possible : ajout direct, sans question. */
    val isFixed get() = steps.all { it.productIds.size == 1 }
}

data class KeyboardPage(val id: String, val keyboardId: String, val name: String, val position: Int, val visible: Boolean)

data class Keyboard(val id: String, val name: String, val pages: List<KeyboardPage>, val cells: Map<String, Map<Int, KeyboardCell>>) {
    val visiblePages get() = pages.filter { it.visible }
    fun cellsOf(pageId: String): Map<Int, KeyboardCell> = cells[pageId].orEmpty()
}

data class CatalogImage(val id: String, val name: String, val file: java.io.File?)

/** Élément de commentaire ou de supplément. [vat] null : la TVA suit celle du produit auquel il est rattaché. */
data class ModifierItem(val id: String, val groupId: String, val label: String, val price: Cents, val vat: BasisPoints?) {
    val isFree get() = price.isZero
}

data class ModifierGroup(val id: String, val name: String, val mandatory: Boolean, val multiple: Boolean, val items: List<ModifierItem>)

data class CatalogState(
    val products: List<Product>, val keyboards: List<Keyboard>, val images: List<CatalogImage> = emptyList(),
    val groups: List<ModifierGroup> = emptyList(),
    val formulas: List<Formula> = emptyList(),
    /** Produit → groupes proposés à la vente, dans l'ordre. */
    val productGroups: Map<String, List<String>> = emptyMap(),
) {
    fun groupsFor(productId: String): List<ModifierGroup> =
        productGroups[productId].orEmpty().mapNotNull { id -> groups.firstOrNull { it.id == id } }
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

    private val modifiers: Flow<Pair<List<ModifierGroup>, Map<String, List<String>>>> =
        combine(dao.observeGroups(), dao.observeItems(), dao.observeProductGroups()) { g, i, links ->
            val items = i.map { ModifierItem(it.id, it.groupId, it.label, Cents(it.priceCents), it.vatBp?.let(::BasisPoints)) }.groupBy { it.groupId }
            g.map { ModifierGroup(it.id, it.name, it.mandatory, it.multiple, items[it.id].orEmpty()) } to
                links.groupBy({ it.productId }, { it.groupId })
        }

    private val formulas: Flow<List<Formula>> = combine(dao.observeFormulas(), dao.observeFormulaSteps()) { f, s ->
        val steps = s.groupBy { it.formulaId }
        f.map { it.toDomain(steps[it.id].orEmpty()) }
    }

    private data class Raw(val p: List<ProductEntity>, val k: List<KeyboardEntity>, val pg: List<KeyboardPageEntity>, val c: List<KeyboardCellEntity>)

    val state: Flow<CatalogState> = combine(
        combine(dao.observeProducts(), dao.observeKeyboards(), dao.observePages(), dao.observeCells(), ::Raw),
        dao.observeImages(),
        modifiers,
        formulas,
    ) { r, imgs, (groups, links), f ->
        build(r.p, r.k, r.pg, r.c, f).copy(
            images = imgs.map { CatalogImage(it.id, it.name, imagesDir?.let { d -> java.io.File(d, it.fileName) }) },
            groups = groups, productGroups = links, formulas = f,
        )
    }

    private fun build(p: List<ProductEntity>, k: List<KeyboardEntity>, pg: List<KeyboardPageEntity>, c: List<KeyboardCellEntity>, f: List<Formula>): CatalogState {
        val formulaById = f.associateBy { it.id }
        val products = p.map { it.toDomain() }
        val byId = products.associateBy { it.id }
        val pages = pg.map { KeyboardPage(it.id, it.keyboardId, it.name, it.position, it.visible) }
        val pageById = pages.associateBy { it.id }
        val cells = c.mapNotNull { e ->
            when (e.kind) {
                "PRODUCT" -> byId[e.targetId]?.takeIf { it.active }?.let { KeyboardCell.ProductCell(e.slot, it, e.color, e.tariff) }
                "PAGE" -> pageById[e.targetId]?.let { KeyboardCell.PageLink(e.slot, it, e.color) }
                "FORMULA" -> formulaById[e.targetId]?.takeIf { it.kind != FormulaKind.PROMO }?.let { KeyboardCell.FormulaCell(e.slot, it, e.color) }
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

    /**
     * Exemples de commentaires et suppléments, posés une seule fois (aussi sur une caisse déjà en service).
     * Un groupe supprimé reste en base (masqué) : l'exemple ne revient donc jamais.
     */
    fun seedModifiersIfEmpty() = db.runInTransaction {
        if (dao.allGroups().isNotEmpty()) return@runInTransaction
        fun group(id: String, name: String, mandatory: Boolean, multiple: Boolean, pos: Int, vararg items: Pair<String, Long>) {
            dao.upsertGroup(ModifierGroupEntity(id, name, mandatory, multiple, pos, false))
            items.forEachIndexed { i, (label, cents) -> dao.upsertItem(ModifierItemEntity("$id-${i + 1}", id, label, cents, null, i)) }
        }
        group("grp-cuisson", "Cuisson", mandatory = true, multiple = false, 0, "Bleu" to 0, "Saignant" to 0, "À point" to 0, "Bien cuit" to 0)
        group("grp-pizza", "Suppléments pizza", mandatory = false, multiple = true, 1, "Œuf" to 100, "Jambon" to 200, "Mozzarella" to 150, "Sans olives" to 0, "Bien cuite" to 0)
        group("grp-diluants", "Diluants", mandatory = false, multiple = false, 2, "Coca" to 100, "Perrier" to 100, "Jus d'orange" to 100, "Sec" to 0)
        val pizzas = dao.products().filter { it.category == "Pizzas" }.map { it.id }
        dao.upsertProductGroups(pizzas.map { ProductModifierGroupEntity(it, "grp-pizza", 0) })
    }

    /** Exemples de formules, posés une seule fois, avec une page « Formules » sur le premier clavier. */
    fun seedFormulasIfEmpty() = db.runInTransaction {
        if (dao.allFormulas().isNotEmpty()) return@runInTransaction
        val products = dao.products().filter { it.active }
        fun cat(c: String) = products.filter { it.category == c }.joinToString(",") { it.id }
        fun formula(id: String, name: String, kind: FormulaKind, cents: Long, pos: Int, vararg steps: Triple<String, Int?, String>) {
            dao.upsertFormula(FormulaEntity(id, name, kind.name, cents, pos, false))
            dao.upsertFormulaSteps(steps.mapIndexed { i, (n, lvl, ids) -> FormulaStepEntity("$id-s${i + 1}", id, n, lvl, ids, i) }.filter { it.productIds.isNotEmpty() })
        }
        formula("f-midi", "Formule midi", FormulaKind.MENU, 1990, 0,
            Triple("Pizza", null, cat("Pizzas")), Triple("Boisson", 0, cat("Softs")), Triple("Dessert", null, cat("Desserts")))
        formula("f-apero", "Apéro", FormulaKind.PACKAGE, 1100, 1,
            Triple("Bière", null, products.firstOrNull { it.id == "biere" }?.id ?: ""), Triple("Bruschetta", null, products.firstOrNull { it.id == "bruschetta" }?.id ?: ""))
        formula("f-promo-soda", "Pizza + soda", FormulaKind.PROMO, 1500, 2,
            Triple("Pizza", null, cat("Pizzas")), Triple("Soda", null, products.firstOrNull { it.id == "soda" }?.id ?: ""))
        val kb = dao.keyboards().firstOrNull() ?: return@runInTransaction
        val pageId = "page-formules"
        dao.upsertPage(KeyboardPageEntity(pageId, kb.id, "Formules", dao.pages(kb.id).size, true))
        dao.upsertCells(listOf("f-midi", "f-apero").filter { id -> dao.formulaSteps(id).isNotEmpty() }
            .mapIndexed { i, id -> KeyboardCellEntity(pageId, i, "FORMULA", id, null) })
    }

    // ---------- Formules ----------

    fun saveFormula(actorId: String, f: Formula): String {
        requireRight(actorId)
        require(f.name.isNotBlank()) { "nom de la formule requis" }
        require(!f.price.isNegative) { "prix négatif" }
        require(f.steps.isNotEmpty() && f.steps.all { it.productIds.isNotEmpty() }) { "chaque étape doit proposer au moins un produit" }
        if (f.kind == FormulaKind.PACKAGE) require(f.steps.all { it.productIds.size == 1 }) { "un package contient des articles fixes (un produit par ligne)" }
        return db.runInTransaction<String> {
            val before = dao.formula(f.id)
            val id = before?.id ?: ("f-" + ids())
            dao.upsertFormula(FormulaEntity(id, f.name.trim(), f.kind.name, f.price.value, before?.position ?: dao.allFormulas().size, false))
            val beforeSteps = dao.formulaSteps(id)
            dao.clearFormulaSteps(id)
            dao.upsertFormulaSteps(f.steps.mapIndexed { i, s -> FormulaStepEntity("$id-s${i + 1}", id, s.name.trim().ifBlank { "Étape ${i + 1}" }, s.level?.value, s.productIds.joinToString(","), i) })
            if (f.kind == FormulaKind.PROMO) dao.deleteFormulaCells(id)
            log(actorId, if (before == null) "FORMULA_CREATED" else "FORMULA_UPDATED", "formula", id,
                before?.let { formulaSummary(it, beforeSteps) }, formulaSummary(dao.formula(id)!!, dao.formulaSteps(id)))
            id
        }
    }

    fun deleteFormula(actorId: String, id: String) {
        requireRight(actorId)
        db.runInTransaction {
            val f = dao.formula(id) ?: error("formule inconnue")
            dao.upsertFormula(f.copy(deleted = true)); dao.deleteFormulaCells(id)
            log(actorId, "FORMULA_DELETED", "formula", id, f.name, null)
        }
    }

    fun setFormulaCell(actorId: String, pageId: String, slot: Int, formulaId: String, color: Int?) = edit(actorId, "KEYBOARD_UPDATED", pageId) {
        val f = dao.formula(formulaId) ?: error("formule inconnue")
        require(f.kind != FormulaKind.PROMO.name) { "une promo s'applique seule : elle ne se place pas sur le clavier" }
        dao.upsertCell(KeyboardCellEntity(pageId, slot, "FORMULA", formulaId, color))
    }

    /** Promotions actives, au format du moteur de promo. */
    fun promoRules(): List<fr.foodsafe.pos.core.order.PromoRule> =
        dao.allFormulas().filter { !it.deleted && it.kind == FormulaKind.PROMO.name }.map { f ->
            fr.foodsafe.pos.core.order.PromoRule(f.id, f.name, Cents(f.priceCents), dao.formulaSteps(f.id).map { s -> s.productIds.split(',').filter(String::isNotBlank).toSet() })
        }

    fun formulaName(id: String?): String? = id?.let { dao.formula(it)?.name }

    // ---------- Commentaires et suppléments ----------

    fun saveGroup(actorId: String, id: String?, name: String, mandatory: Boolean, multiple: Boolean): String {
        requireRight(actorId)
        require(name.isNotBlank()) { "nom du groupe requis" }
        return db.runInTransaction<String> {
            val before = id?.let(dao::group)
            val gid = before?.id ?: ("grp-" + ids())
            val e = ModifierGroupEntity(gid, name.trim(), mandatory, multiple, before?.position ?: dao.allGroups().size, false)
            dao.upsertGroup(e)
            log(actorId, if (before == null) "MODIFIER_GROUP_CREATED" else "MODIFIER_GROUP_UPDATED", "modifier_group", gid,
                before?.let { JSONObject().put("name", it.name).put("mandatory", it.mandatory).put("multiple", it.multiple).toString() },
                JSONObject().put("name", e.name).put("mandatory", mandatory).put("multiple", multiple).toString())
            gid
        }
    }

    /** Masque le groupe et le détache des produits ; les notes déjà saisies gardent leurs lignes. */
    fun deleteGroup(actorId: String, id: String) {
        requireRight(actorId)
        db.runInTransaction {
            val g = dao.group(id) ?: error("groupe inconnu")
            dao.upsertGroup(g.copy(deleted = true)); dao.unlinkGroup(id)
            log(actorId, "MODIFIER_GROUP_DELETED", "modifier_group", id, g.name, null)
        }
    }

    fun saveItem(actorId: String, item: ModifierItem): String {
        requireRight(actorId)
        require(item.label.isNotBlank()) { "libellé requis" }
        require(!item.price.isNegative) { "prix négatif" }
        return db.runInTransaction<String> {
            val before = dao.item(item.id)
            val id = before?.id ?: ("mod-" + ids())
            val e = ModifierItemEntity(id, item.groupId, item.label.trim(), item.price.value, item.vat?.value, before?.position ?: dao.items(item.groupId).size)
            dao.upsertItem(e)
            log(actorId, if (before == null) "MODIFIER_CREATED" else "MODIFIER_UPDATED", "modifier", id, before?.summary(), e.summary())
            id
        }
    }

    fun deleteItem(actorId: String, id: String) {
        requireRight(actorId)
        db.runInTransaction {
            val before = dao.item(id) ?: error("élément inconnu")
            dao.deleteItem(id)
            log(actorId, "MODIFIER_DELETED", "modifier", id, before.summary(), null)
        }
    }

    fun setProductGroups(actorId: String, productId: String, groupIds: List<String>) {
        requireRight(actorId)
        db.runInTransaction {
            val before = dao.productGroups(productId).map { it.groupId }
            if (before == groupIds) return@runInTransaction
            dao.clearProductGroups(productId)
            dao.upsertProductGroups(groupIds.distinct().mapIndexed { i, g -> ProductModifierGroupEntity(productId, g, i) })
            log(actorId, "PRODUCT_UPDATED", "product", productId, "groupes=" + before.joinToString(","), "groupes=" + groupIds.joinToString(","))
        }
    }

    /** Groupes obligatoires d'un produit sans aucun choix parmi [chosenItemIds]. */
    fun missingMandatory(productId: String, chosenItemIds: Collection<String>): List<String> =
        dao.productGroups(productId).mapNotNull { link -> dao.group(link.groupId)?.takeIf { it.mandatory && !it.deleted } }
            .filter { g -> dao.items(g.id).none { it.id in chosenItemIds } }
            .map { it.name }

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

    /** Déplace une touche ; si la case d'arrivée est occupée, les deux touches s'échangent. */
    fun moveCell(actorId: String, fromPage: String, fromSlot: Int, toPage: String, toSlot: Int) = edit(actorId, "KEYBOARD_UPDATED", toPage) {
        if (fromPage == toPage && fromSlot == toSlot) return@edit
        val moving = dao.cell(fromPage, fromSlot) ?: error("touche introuvable")
        require(dao.page(toPage) != null) { "page inconnue" }
        if (moving.kind == "PAGE") require(moving.targetId != toPage) { "une page ne peut pas renvoyer vers elle-même" }
        val other = dao.cell(toPage, toSlot)
        if (other?.kind == "PAGE") require(other.targetId != fromPage) { "une page ne peut pas renvoyer vers elle-même" }
        dao.deleteCell(fromPage, fromSlot); dao.deleteCell(toPage, toSlot)
        dao.upsertCell(moving.copy(pageId = toPage, slot = toSlot))
        other?.let { dao.upsertCell(it.copy(pageId = fromPage, slot = fromSlot)) }
    }

    /** « Fixer tarification » : la touche vend toujours au tarif choisi (null = tarif normal). */
    fun setCellTariff(actorId: String, pageId: String, slot: Int, tariff: Int?) = edit(actorId, "KEYBOARD_UPDATED", pageId) {
        val c = dao.cell(pageId, slot) ?: error("touche introuvable")
        require(c.kind == "PRODUCT") { "seule une touche produit a un tarif" }
        dao.upsertCell(c.copy(tariff = tariff?.takeIf { it > 0 }))
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
    internalCode, description, price1Cents?.let(::Cents), price2Cents?.let(::Cents), formulaPriceCents?.let(::Cents),
    Cents(ecoDeeeCents), Cents(ecoDeaCents), openPrice, runCatching { GoodsType.valueOf(goodsType) }.getOrDefault(GoodsType.PRODUCT), outOfTurnover,
)

internal fun Product.toEntity(now: Long) = ProductEntity(
    id, label, price.value, vat.value, destination, defaultLevel.value, category, subCategory, imageId, color, active, now,
    internalCode, description, price1?.value, price2?.value, formulaPrice?.value, ecoDeee.value, ecoDea.value, openPrice, goodsType.name, outOfTurnover,
)

private fun ProductEntity.summary() = JSONObject()
    .put("label", label).put("priceCents", priceCents).put("vatBp", vatBp).put("category", category)
    .put("subCategory", subCategory ?: "").put("level", defaultLevel).put("destination", destination).put("active", active)
    .put("code", internalCode ?: "").put("price1", price1Cents ?: JSONObject.NULL).put("price2", price2Cents ?: JSONObject.NULL)
    .put("formulaPrice", formulaPriceCents ?: JSONObject.NULL).put("ecoDeee", ecoDeeeCents).put("ecoDea", ecoDeaCents)
    .put("openPrice", openPrice).put("goodsType", goodsType).put("outOfTurnover", outOfTurnover)
    .toString()

private fun ModifierItemEntity.summary() = JSONObject()
    .put("groupId", groupId).put("label", label).put("priceCents", priceCents).put("vatBp", vatBp ?: JSONObject.NULL).toString()

internal fun FormulaEntity.toDomain(steps: List<FormulaStepEntity>) = Formula(
    id, name, FormulaKind.valueOf(kind), Cents(priceCents),
    steps.sortedBy { it.position }.map { FormulaStep(it.id, it.name, it.level?.let(::ServiceLevel), it.productIds.split(',').filter(String::isNotBlank)) },
)

private fun formulaSummary(f: FormulaEntity, steps: List<FormulaStepEntity>) = JSONObject()
    .put("name", f.name).put("kind", f.kind).put("priceCents", f.priceCents)
    .put("steps", org.json.JSONArray(steps.map { JSONObject().put("name", it.name).put("level", it.level ?: JSONObject.NULL).put("products", it.productIds) }))
    .toString()

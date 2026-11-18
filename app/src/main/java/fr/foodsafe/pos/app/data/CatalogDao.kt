package fr.foodsafe.pos.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM products ORDER BY category, label") fun observeProducts(): Flow<List<ProductEntity>>
    @Query("SELECT * FROM products") fun products(): List<ProductEntity>
    @Query("SELECT * FROM products WHERE id = :id") fun product(id: String): ProductEntity?
    @Query("SELECT COUNT(*) FROM products") fun productCount(): Int
    @Upsert fun upsertProduct(p: ProductEntity)

    @Query("SELECT * FROM keyboards ORDER BY position") fun observeKeyboards(): Flow<List<KeyboardEntity>>
    @Query("SELECT * FROM keyboards ORDER BY position") fun keyboards(): List<KeyboardEntity>
    @Upsert fun upsertKeyboard(k: KeyboardEntity)

    @Query("SELECT * FROM keyboard_pages ORDER BY keyboardId, position") fun observePages(): Flow<List<KeyboardPageEntity>>
    @Query("SELECT * FROM keyboard_pages WHERE keyboardId = :keyboardId ORDER BY position") fun pages(keyboardId: String): List<KeyboardPageEntity>
    @Query("SELECT * FROM keyboard_pages WHERE id = :id") fun page(id: String): KeyboardPageEntity?
    @Upsert fun upsertPage(p: KeyboardPageEntity)
    @Upsert fun upsertPages(p: List<KeyboardPageEntity>)
    @Query("DELETE FROM keyboard_pages WHERE id = :id") fun deletePage(id: String)

    @Query("SELECT * FROM keyboard_cells") fun observeCells(): Flow<List<KeyboardCellEntity>>
    @Upsert fun upsertCell(c: KeyboardCellEntity)
    @Upsert fun upsertCells(c: List<KeyboardCellEntity>)
    @Query("DELETE FROM keyboard_cells WHERE pageId = :pageId AND slot = :slot") fun deleteCell(pageId: String, slot: Int)
    @Query("DELETE FROM keyboard_cells WHERE pageId = :pageId") fun deleteCellsOf(pageId: String)
    @Query("DELETE FROM keyboard_cells WHERE kind = 'PAGE' AND targetId = :pageId") fun deleteLinksTo(pageId: String)

    @Query("SELECT * FROM images ORDER BY createdAt DESC") fun observeImages(): Flow<List<ImageEntity>>
    @Upsert fun upsertImage(i: ImageEntity)
    @Delete fun deleteImage(i: ImageEntity)
}

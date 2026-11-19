package fr.foodsafe.pos.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import fr.foodsafe.pos.app.data.PosDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Une caisse en version 0.1 (base v1) passe en 0.2 (base v2) sans perte de journal ni de paiement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PosDatabase::class.java)

    @Test fun migration1vers2ConserveLesDonnees() {
        helper.createDatabase("migr.db", 1).apply {
            execSQL("INSERT INTO payments VALUES ('p1','o1','CARD',1200,1200,'marie','T01',1)")
            execSQL("INSERT INTO journal_events VALUES (1,'e1','E','T01','marie',1,'X','order','o1',NULL,NULL,NULL,'a','b')")
            close()
        }
        val db = helper.runMigrationsAndValidate("migr.db", 2, true)
        db.query("SELECT COUNT(*) FROM payments").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM journal_events").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM fiscal_documents").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test fun migration2vers3AjouteLeCatalogueSansToucherAuxDonnees() {
        helper.createDatabase("migr3.db", 2).apply {
            execSQL("INSERT INTO payments VALUES ('p1','o1','CARD',1200,1200,'marie','T01',1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migr3.db", 3, true)
        db.query("SELECT COUNT(*) FROM payments").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM products").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM keyboard_cells").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test fun migration3vers4AjouteCommentairesEtSupplements() {
        helper.createDatabase("migr4.db", 3).apply {
            execSQL("INSERT INTO order_lines (id, orderId, position, productId, label, unitPriceTtc, quantityMilli, vatRateBp, serviceLevel, destination, authorEmployeeId, createdAt) " +
                "VALUES ('l1','o1',0,'reine','Pizza Reine',1350,1000,1000,2,'CUISINE','marie',1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migr4.db", 4, true)
        db.query("SELECT label, parentLineId FROM order_lines").use { it.moveToFirst(); assertEquals("Pizza Reine", it.getString(0)); assert(it.isNull(1)) }
        db.query("SELECT COUNT(*) FROM modifier_groups").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test fun migration4vers5AjouteLesFormules() {
        helper.createDatabase("migr5.db", 4).apply {
            execSQL("INSERT INTO order_lines (id, orderId, position, productId, label, unitPriceTtc, quantityMilli, vatRateBp, serviceLevel, destination, authorEmployeeId, createdAt, parentLineId) " +
                "VALUES ('l2','o1',1,'grp-pizza-1','Œuf',100,1000,1000,2,'CUISINE','marie',1,'l1')")
            close()
        }
        val db = helper.runMigrationsAndValidate("migr5.db", 5, true)
        db.query("SELECT role, promoGroup, parentLineId FROM order_lines").use {
            it.moveToFirst(); assert(it.isNull(0) && it.isNull(1)); assertEquals("l1", it.getString(2))
        }
        db.query("SELECT COUNT(*) FROM formulas").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
}

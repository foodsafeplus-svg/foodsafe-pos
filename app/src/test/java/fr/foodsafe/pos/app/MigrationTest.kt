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
}

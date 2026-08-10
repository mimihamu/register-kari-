package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V084LegacyDatabaseMigrationSafetyTest {
    @Test
    fun sqliteOpenHelperUpgradeRoutesThroughNonDestructiveMigrator() {
        val database = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        val migration = File("src/main/java/jp/co/tenposinfo/register/LegacyDatabaseMigrationV084.kt").readText()

        assertTrue(database.contains("LegacyDatabaseMigrationV084.migrate(db, oldVersion)"))
        assertFalse(database.contains("dropAllTables"))
        assertFalse(database.contains("DROP TABLE IF EXISTS sales"))
        assertFalse(database.contains("DROP TABLE IF EXISTS sale_items"))
        assertFalse(database.contains("DROP TABLE IF EXISTS held_tickets"))
        assertFalse(database.contains("DROP TABLE IF EXISTS held_ticket_items"))
        assertFalse(database.contains("DROP TABLE IF EXISTS products"))

        assertTrue(migration.contains("ensureLegacyBaseTables(db)"))
        assertTrue(migration.contains("tableExists(db, \"products\")"))
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS products"))
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS cart_items"))
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS held_tickets"))
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS held_ticket_items"))
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS sales"))
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS sale_items"))
    }

    @Test
    fun existingColumnsAndCartRowsArePreservedByGuardedMigration() {
        val migration = File("src/main/java/jp/co/tenposinfo/register/LegacyDatabaseMigrationV084.kt").readText()

        assertTrue(migration.contains("addColumnIfMissing"))
        assertTrue(migration.contains("hasColumn(db, \"cart_items\", \"line_no\")"))
        assertTrue(migration.contains("hasColumn(db, table, column)"))
        assertTrue(migration.contains("PRAGMA table_info($table)"))
        assertTrue(migration.contains("ALTER TABLE cart_items RENAME TO $CART_MIGRATION_TEMP"))
        assertTrue(migration.contains("SELECT rowid, product_id, product_name, unit_price, tax_category"))
        assertTrue(migration.contains("FROM $CART_MIGRATION_TEMP"))
        assertTrue(migration.contains("DROP TABLE $CART_MIGRATION_TEMP"))

        // DROPを許すのは、コピー成功後の一時移行テーブルだけ。
        val dropLines = migration.lineSequence().filter { it.contains("DROP TABLE") }.toList()
        assertTrue(dropLines.isNotEmpty())
        assertTrue(dropLines.all { it.contains("CART_MIGRATION_TEMP") })
        listOf(
            "sales",
            "sale_items",
            "sale_payments",
            "held_tickets",
            "held_ticket_items",
            "products",
            "print_jobs",
        ).forEach { businessTable ->
            assertFalse(migration.contains("DROP TABLE $businessTable"))
            assertFalse(migration.contains("DROP TABLE IF EXISTS $businessTable"))
        }
    }

    @Test
    fun missingProductTableGetsSeedsWithoutOverwritingExistingMaster() {
        val migration = File("src/main/java/jp/co/tenposinfo/register/LegacyDatabaseMigrationV084.kt").readText()

        assertTrue(migration.contains("val productsMissing = !tableExists(db, \"products\")"))
        assertTrue(migration.contains("if (productsMissing) insertSeedProducts(db)"))
        assertFalse(migration.contains("DELETE FROM products"))
        assertFalse(migration.contains("UPDATE products"))
    }

    @Test
    fun releaseIdentityIsOwnedByCurrentReleaseAndCumulativeCiRemains() {
        val root = File("..")
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(build.contains("applicationId = \"jp.co.tenposinfo.register\""))
        assertTrue(build.contains("compileSdk = 36"))
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":customer-display:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
        assertTrue(File(root, "docs/V0.84_LEGACY_DATABASE_MIGRATION_SAFETY.md").isFile)
        assertTrue(File(root, "docs/V0.84_RELEASE_NOTES.md").isFile)
    }
}

from pathlib import Path

ROOT = Path(".")


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


path = "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsStore.kt"
text = read(path)
text = replace_once(text, '    CASH_MOVEMENT("入出金"),\n    X_INSPECTION("X点検"),', '    CASH_MOVEMENT("入出金"),\n    BUSINESS_START("営業開始"),\n    X_INSPECTION("X点検"),', "permission enum")
text = replace_once(text, "    init {\n        ensureSchema()\n        seedDefaults()\n    }", "    init {\n        ensureSchema()\n        migrateBusinessStartPermissionV135()\n        seedDefaults()\n    }", "admin init")
migration = '''    /**
     * v1.35 BIZDAY-001: 営業開始権限をZ精算から分離する一度限りの互換移行。
     * 旧版でZ精算権限を持っていた既存担当者には営業開始権限を一度だけコピーする。
     * マーカー保存後は再実行しないため、管理者が営業開始権限だけ外しても再付与しない。
     */
    private fun migrateBusinessStartPermissionV135() {
        val migrationKey = "BUSINESS_START_FROM_Z_V135"
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS register_permission_migrations_v135 (
                migration_key TEXT PRIMARY KEY,
                applied_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        val applied = db.rawQuery(
            "SELECT COUNT(*) FROM register_permission_migrations_v135 WHERE migration_key = ?",
            arrayOf(migrationKey),
        ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) > 0L }
        if (applied) return

        db.runInTransaction {
            execSQL(
                """
                INSERT OR IGNORE INTO operator_permissions(operator_id, permission_key)
                SELECT operator_id, '${RegisterPermission.BUSINESS_START.name}'
                FROM operator_permissions
                WHERE permission_key IN (
                    '${RegisterPermission.Z_SETTLEMENT.name}',
                    '${RegisterPermission.SETTLEMENT.name}'
                )
                """.trimIndent(),
            )
            insertOrThrow(
                "register_permission_migrations_v135",
                null,
                ContentValues().apply {
                    put("migration_key", migrationKey)
                    put("applied_at", System.currentTimeMillis())
                },
            )
        }
    }

'''
text = replace_once(text, "    private fun seedDefaults() {", migration + "    private fun seedDefaults() {", "migration insertion")
write(path, text)

path = "app/src/main/java/jp/co/tenposinfo/register/SettlementPreflightV026.kt"
text = read(path)
text = replace_once(text, "/** 旧SETTLEMENT権限を、X点検とZ精算へ安全に展開する互換レイヤー。 */", "/** 旧SETTLEMENT権限を、営業開始・X点検・Z精算へ安全に展開する互換レイヤー。 */", "compat comment")
text = replace_once(text, "        return stored + RegisterPermission.X_INSPECTION + RegisterPermission.Z_SETTLEMENT", "        return stored + RegisterPermission.BUSINESS_START + RegisterPermission.X_INSPECTION + RegisterPermission.Z_SETTLEMENT", "compat expansion")
write(path, text)

path = "app/src/main/java/jp/co/tenposinfo/register/OperationsNavigationPoliciesV030.kt"
text = read(path)
text = replace_once(text, " * 旧SETTLEMENTは保存データ読込時にRegisterPermissionCompatibilityV026で展開されるため、\n * 新規UI・認可判定ではZ_SETTLEMENTだけを参照する。", " * v1.35では営業開始をZ精算から分離し、新規UI・認可判定はBUSINESS_STARTだけを参照する。\n * 旧SETTLEMENTは互換レイヤーでBUSINESS_START/X/Zへ展開される。", "nav comment")
text = replace_once(text, "        RegisterPermission.Z_SETTLEMENT in permissions", "        RegisterPermission.BUSINESS_START in permissions", "nav permission")
text = replace_once(text, "        RegisterPermission.CASH_MOVEMENT,\n        RegisterPermission.X_INSPECTION,", "        RegisterPermission.CASH_MOVEMENT,\n        RegisterPermission.BUSINESS_START,\n        RegisterPermission.X_INSPECTION,", "management permission")
write(path, text)

path = "app/src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt"
text = read(path)
text = replace_once(text, "    DAILY_SALES(RegisterPermission.VIEW_SALES, false),\n    X_INSPECTION(RegisterPermission.X_INSPECTION, false),", "    DAILY_SALES(RegisterPermission.VIEW_SALES, false),\n    BUSINESS_START(RegisterPermission.BUSINESS_START, false),\n    X_INSPECTION(RegisterPermission.X_INSPECTION, false),", "action permission")
text = replace_once(text, "            val operator = requireOperator(OperationsAction.Z_SETTLEMENT)\n            store.startBusinessDay", "            val operator = requireOperator(OperationsAction.BUSINESS_START)\n            store.startBusinessDay", "start auth")
write(path, text)

path = "app/src/main/java/jp/co/tenposinfo/register/BusinessStartActivityV030.kt"
text = read(path)
text = replace_once(text, " * 管理メニューを経由せず、画面表示時と保存直前の両方でZ_SETTLEMENTを検証する。", " * 管理メニューを経由せず、画面表示時と保存直前の両方でBUSINESS_STARTを検証する。", "business start comment")
write(path, text)

path = "app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt"
text = read(path)
text = replace_once(text, 'eventType = "Z_SETTLEMENT_BACKUP_FAILURE_ACKNOWLEDGED"', 'eventType = "Z_SETTLEMENT_BACKUP_FAILURE_ACK"', "audit event")
write(path, text)

path = "app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt"
text = read(path)
text = replace_once(text, "    val itemCount: Int,\n    val totalAmount: Long,", "    val itemCount: Int,\n    val guestCount: Int = 0,\n    val totalAmount: Long,", "held model")
text = replace_once(text, "    override fun onOpen(db: SQLiteDatabase) {\n        super.onOpen(db)\n        CartCorrectionSchemaV135.ensure(db)\n    }", "    override fun onOpen(db: SQLiteDatabase) {\n        super.onOpen(db)\n        CartCorrectionSchemaV135.ensure(db)\n        SaleGuestCountRuntimeV135.ensureSchema(db)\n    }", "held schema ensure")
text = replace_once(text, '''            SELECT t.id, t.name, t.operator_name, t.created_at,
                   COALESCE(SUM(i.quantity), 0) AS item_count
            FROM held_tickets t
            LEFT JOIN held_ticket_items i ON i.ticket_id = t.id
            GROUP BY t.id, t.name, t.operator_name, t.created_at''', '''            SELECT t.id, t.name, t.operator_name, t.created_at,
                   COALESCE(SUM(i.quantity), 0) AS item_count,
                   COALESCE(MAX(g.guest_count), 0) AS guest_count
            FROM held_tickets t
            LEFT JOIN held_ticket_items i ON i.ticket_id = t.id
            LEFT JOIN held_ticket_guest_count_v135 g ON g.ticket_id = t.id
            GROUP BY t.id, t.name, t.operator_name, t.created_at''', "held query")
text = replace_once(text, "                    itemCount = cursor.getInt(4),\n                    totalAmount = TaxEngine.calculate(items).grossAmount,", "                    itemCount = cursor.getInt(4),\n                    guestCount = cursor.getInt(5),\n                    totalAmount = TaxEngine.calculate(items).grossAmount,", "held mapping")
write(path, text)

path = "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt"
text = read(path)
text = replace_once(text, '                                  "${formatDate(ticket.createdAt)} / ${ticket.operatorName} / ${ticket.itemCount}点",', '                                  "${formatDate(ticket.createdAt)} / ${ticket.operatorName}${if (ticket.guestCount > 0) " / ${ticket.guestCount}名" else ""} / ${ticket.itemCount}点",', "compact held guest display")
text = replace_once(text, '                                  "${formatDate(ticket.createdAt)} / 担当 ${ticket.operatorName} / ${ticket.itemCount}点",', '                                  "${formatDate(ticket.createdAt)} / 担当 ${ticket.operatorName}${if (ticket.guestCount > 0) " / ${ticket.guestCount}名" else ""} / ${ticket.itemCount}点",', "wide held guest display")
write(path, text)

path = "app/src/test/java/jp/co/tenposinfo/register/V030BusinessStartNavigationTest.kt"
text = read(path)
text = replace_once(text, "    fun caseA_salesAndZSettlementShowsBusinessStartNavigation() {\n        val permissions = setOf(RegisterPermission.SALES, RegisterPermission.Z_SETTLEMENT)", "    fun caseA_salesAndBusinessStartShowsBusinessStartNavigation() {\n        val permissions = setOf(RegisterPermission.SALES, RegisterPermission.BUSINESS_START)", "V030 case A")
text = replace_once(text, "        assertTrue(RegisterPermission.X_INSPECTION in expanded)\n        assertTrue(RegisterPermission.Z_SETTLEMENT in expanded)\n        assertTrue(BusinessStartNavigationPolicyV030.canOpenBusinessStart(expanded))", "        assertTrue(RegisterPermission.BUSINESS_START in expanded)\n        assertTrue(RegisterPermission.X_INSPECTION in expanded)\n        assertTrue(RegisterPermission.Z_SETTLEMENT in expanded)\n        assertTrue(BusinessStartNavigationPolicyV030.canOpenBusinessStart(expanded))", "V030 legacy assertions")
text = replace_once(text, "        assertTrue(ManagementNavigationPolicyV030.canOpenManagement(setOf(RegisterPermission.X_INSPECTION)))", "        assertTrue(ManagementNavigationPolicyV030.canOpenManagement(setOf(RegisterPermission.BUSINESS_START)))\n        assertTrue(ManagementNavigationPolicyV030.canOpenManagement(setOf(RegisterPermission.X_INSPECTION)))", "V030 management")
insert = '''    @Test
    fun zSettlementAloneNoLongerGrantsBusinessStart() {
        val permissions = setOf(RegisterPermission.SALES, RegisterPermission.Z_SETTLEMENT)

        assertFalse(BusinessStartNavigationPolicyV030.canOpenBusinessStart(permissions))
        assertTrue(OperationsAccessPolicyV030.canEnter(permissions))
    }

'''
text = replace_once(text, "    @Test\n    fun caseB_xInspectionOnlyDoesNotShowBusinessStartNavigation()", insert + "    @Test\n    fun caseB_xInspectionOnlyDoesNotShowBusinessStartNavigation()", "V030 independent regression")
text = replace_once(text, '        assertFalse(source("SecureOperationsCoordinator.kt").contains("OperationsAction.SETTLEMENT"))', '        assertFalse(source("SecureOperationsCoordinator.kt").contains("OperationsAction.SETTLEMENT"))\n        assertTrue(source("SecureOperationsCoordinator.kt").contains("requireOperator(OperationsAction.BUSINESS_START)"))', "V030 write auth assertion")
write(path, text)

path = "app/src/test/java/jp/co/tenposinfo/register/V026SettlementPreflightPermissionTest.kt"
text = read(path)
text = replace_once(text, "        assertTrue(RegisterPermission.X_INSPECTION in expanded)\n        assertTrue(RegisterPermission.Z_SETTLEMENT in expanded)", "        assertTrue(RegisterPermission.BUSINESS_START in expanded)\n        assertTrue(RegisterPermission.X_INSPECTION in expanded)\n        assertTrue(RegisterPermission.Z_SETTLEMENT in expanded)", "V026 legacy assertion")
write(path, text)

path = "app/src/test/java/jp/co/tenposinfo/register/V135BusinessDayResidualGapTest.kt"
if (ROOT / path).exists():
    raise SystemExit(f"{path} already exists")
write(path, '''package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V135BusinessDayResidualGapTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private fun source(name: String): String = File(
        root,
        "app/src/main/java/jp/co/tenposinfo/register/$name",
    ).readText()

    @Test
    fun businessStartPermissionIsIndependentFromZSettlement() {
        assertTrue(BusinessStartNavigationPolicyV030.canOpenBusinessStart(setOf(RegisterPermission.BUSINESS_START)))
        assertFalse(BusinessStartNavigationPolicyV030.canOpenBusinessStart(setOf(RegisterPermission.Z_SETTLEMENT)))
        val secure = source("SecureOperationsCoordinator.kt")
        assertTrue(secure.contains("BUSINESS_START(RegisterPermission.BUSINESS_START"))
        assertTrue(secure.contains("requireOperator(OperationsAction.BUSINESS_START)"))
    }

    @Test
    fun legacySettlementCompatibilityIncludesBusinessStartButExplicitZDoesNot() {
        val legacy = RegisterPermissionCompatibilityV026.expand(setOf(RegisterPermission.SETTLEMENT))
        val explicitZ = RegisterPermissionCompatibilityV026.expand(setOf(RegisterPermission.Z_SETTLEMENT))
        assertTrue(RegisterPermission.BUSINESS_START in legacy)
        assertFalse(RegisterPermission.BUSINESS_START in explicitZ)
        val settings = source("AdminSettingsStore.kt")
        assertTrue(settings.contains("BUSINESS_START_FROM_Z_V135"))
        assertTrue(settings.contains("register_permission_migrations_v135"))
    }

    @Test
    fun lifecyclePreventsDoubleOpenAndOnlyZCloses() {
        assertFalse(BusinessSessionLifecyclePolicy.mayStart(BusinessSessionStatus.OPEN))
        assertTrue(BusinessSessionLifecyclePolicy.mayStart(BusinessSessionStatus.CLOSED))
        assertTrue(BusinessSessionLifecyclePolicy.mayStart(null))
        assertEquals(BusinessSessionStatus.OPEN, BusinessSessionLifecyclePolicy.resultStatus(SettlementReportType.X_INSPECTION, BusinessSessionStatus.OPEN))
        assertEquals(BusinessSessionStatus.CLOSED, BusinessSessionLifecyclePolicy.resultStatus(SettlementReportType.Z_SETTLEMENT, BusinessSessionStatus.OPEN))
        assertTrue(runCatching { BusinessSessionLifecyclePolicy.resultStatus(SettlementReportType.Z_SETTLEMENT, BusinessSessionStatus.CLOSED) }.isFailure)
        assertTrue(source("BusinessSessionV024.kt").contains("idx_business_sessions_single_active"))
    }

    @Test
    fun canonicalBackupFailureAcknowledgementEventIsUsedForNewWrites() {
        val store = source("OperationsStore.kt")
        assertTrue(store.contains("Z_SETTLEMENT_BACKUP_FAILURE_ACK\\\""))
        assertFalse(store.contains("Z_SETTLEMENT_BACKUP_FAILURE_ACKNOWLEDGED"))
    }

    @Test
    fun heldTicketListLoadsAndDisplaysPersistedGuestCount() {
        val database = source("RegisterDatabase.kt")
        val main = source("MainActivity.kt")
        assertTrue(database.contains("val guestCount: Int = 0"))
        assertTrue(database.contains("held_ticket_guest_count_v135 g"))
        assertTrue(database.contains("guestCount = cursor.getInt(5)"))
        assertTrue(main.contains("ticket.guestCount"))
    }
}
''')

Path("docs").mkdir(exist_ok=True)
write("docs/v1.35-bizday-residual-gap.md", '''# v1.35 BIZDAY / REP residual gap

- `BUSINESS_START` is independent from `Z_SETTLEMENT` in UI and write-side authorization.
- Existing operators who previously had Z/legacy settlement permission receive `BUSINESS_START` once via `BUSINESS_START_FROM_Z_V135`. The migration marker prevents later manual permission removal from being undone.
- Legacy `SETTLEMENT` compatibility expands to `BUSINESS_START`, `X_INSPECTION`, and `Z_SETTLEMENT`.
- X keeps an OPEN business session; only successful Z changes it to CLOSED. Existing single-OPEN and settlement idempotency protections remain in force.
- New REP-003 backup-failure acknowledgement audit writes use `Z_SETTLEMENT_BACKUP_FAILURE_ACK`; persisted append-only audit history is not rewritten.
- Held-ticket list now reads and displays persisted guest count when present.
- Held-ticket provisional-slip printing remains a separate residual item and is not marked complete by this change.
''')

print("core patch applied")

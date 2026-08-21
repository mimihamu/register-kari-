package jp.co.tenposinfo.register

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V135Tax001TaxCategoryHistoryTest {
    private val dayD = LocalDate.of(2026, 8, 19)
    private val dayD1 = dayD.plusDays(1)

    @Test
    fun resolverUsesBusinessDateAndSwitchesOnlyWhenBusinessDateAdvances() {
        val old = revision(
            rate = 10,
            from = LocalDate.of(2020, 1, 1),
            to = dayD,
        )
        val next = revision(
            rate = 12,
            from = dayD1,
            to = null,
        )
        val rows = listOf(old, next)

        assertEquals(10, TaxCategoryHistoryPolicyV135.resolve(rows, "STANDARD", dayD)?.ratePercent)
        assertEquals(12, TaxCategoryHistoryPolicyV135.resolve(rows, "STANDARD", dayD1)?.ratePercent)

        // The policy has no clock argument: even after wall-clock midnight an OPEN businessDate=D resolves D.
        assertEquals(10, TaxCategoryHistoryPolicyV135.resolve(rows, "STANDARD", dayD)?.ratePercent)
    }

    @Test
    fun disabledRevisionRemainsHistoryButIsNotSelectableForNewSales() {
        val disabled = revision(rate = 10, from = dayD, to = null, enabled = false)
        assertNull(TaxCategoryHistoryPolicyV135.resolve(listOf(disabled), "STANDARD", dayD))
        assertFalse(disabled.enabled)
    }

    @Test
    fun overlapIsRejectedInsteadOfChoosingAnAmbiguousTaxRevision() {
        val existing = revision(rate = 10, from = dayD, to = dayD.plusDays(10))
        val candidate = revision(rate = 12, from = dayD.plusDays(5), to = null)

        val error = runCatching {
            TaxCategoryHistoryPolicyV135.requireNoOverlap(listOf(existing), candidate)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun nonTaxableMustBeZeroPercentAndTaxableMustBePositive() {
        val valid = revision(rate = 0, from = dayD, to = null, mode = DynamicTaxMode.NON_TAXABLE)
        assertEquals(0, TaxCategoryHistoryPolicyV135.validate(valid).ratePercent)

        val invalidNonTax = runCatching {
            TaxCategoryHistoryPolicyV135.validate(
                revision(rate = 10, from = dayD, to = null, mode = DynamicTaxMode.NON_TAXABLE),
            )
        }.exceptionOrNull()
        assertTrue(invalidNonTax is IllegalArgumentException)

        val invalidTaxable = runCatching {
            TaxCategoryHistoryPolicyV135.validate(
                revision(rate = 0, from = dayD, to = null, mode = DynamicTaxMode.INCLUDED),
            )
        }.exceptionOrNull()
        assertTrue(invalidTaxable is IllegalArgumentException)
    }

    @Test
    fun schemaStagesFutureSameKeyAndPromotesOnOpenBusinessDate() {
        val source = source("src/main/java/jp/co/tenposinfo/register/TaxCategoryHistoryV135.kt")
        listOf(
            "tax_category_history_v135",
            "effective_from_business_date",
            "effective_to_business_date",
            "rounding TEXT NOT NULL",
            "trg_tax001_stage_future_revision_v135",
            "NEW.valid_from > (SELECT s.business_date",
            "SELECT RAISE(IGNORE)",
            "trg_tax001_business_open_insert_v135",
            "trg_tax001_business_open_update_v135",
            "promoteForOpenBusinessDate",
        ).forEach { marker -> assertTrue("missing TAX-001 business-date marker: $marker", source.contains(marker)) }
    }

    @Test
    fun schemaRequiresStartDateAndBlocksPhysicalTaxMasterDelete() {
        val source = source("src/main/java/jp/co/tenposinfo/register/TaxCategoryHistoryV135.kt")
        assertTrue(source.contains("trg_tax001_require_start_insert_v135"))
        assertTrue(source.contains("適用開始営業日は必須です"))
        assertTrue(source.contains("trg_tax001_block_delete_v135"))
        assertTrue(source.contains("税区分は削除できません。有効/無効で履歴管理してください"))
        assertFalse(source.contains("DELETE FROM $TABLE_MARKER"))
    }

    @Test
    fun existingSalesAndHeldTicketsKeepLineTaxSnapshots() {
        val database = source("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt")
        assertTrue(database.contains("LineTaxSnapshotStore.SCOPE_CART"))
        assertTrue(database.contains("LineTaxSnapshotStore.SCOPE_HELD"))
        assertTrue(database.contains("LineTaxSnapshotStore.SCOPE_SALE"))
        assertTrue(database.contains("LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_SALE, saleId, items)"))
    }

    @Test
    fun bootstrapInstallsTaxHistoryBeforeSettlementRuntimes() {
        val bootstrap = source("src/main/java/jp/co/tenposinfo/register/MixedTaxCartBootstrapProviderV135.kt")
        val history = bootstrap.indexOf("TaxCategoryHistoryRuntimeV135.initialize(appContext)")
        val settlement = bootstrap.indexOf("SettlementReportingRuntimeV135.initialize(appContext)")
        assertTrue(history >= 0)
        assertTrue(settlement > history)
    }

    @Test
    fun auditDocumentKeepsTax006UiAndRealDeviceWorkExplicitlyOpen() {
        val audit = source("../docs/v1.35-tax-001-tax-category-history-audit.md")
        assertTrue(audit.contains("TAX-006"))
        assertTrue(audit.contains("実機未確認"))
        assertTrue(audit.contains("暦日0時"))
        assertTrue(audit.contains("営業日"))
    }

    private fun revision(
        rate: Int,
        from: LocalDate,
        to: LocalDate?,
        mode: DynamicTaxMode = DynamicTaxMode.INCLUDED,
        enabled: Boolean = true,
    ) = TaxCategoryRevisionV135(
        taxKey = "STANDARD",
        label = "標準税率",
        ratePercent = rate,
        mode = mode,
        rounding = TaxRoundingV135.FLOOR,
        reduced = false,
        enabled = enabled,
        symbol = "内",
        effectiveFromBusinessDate = from,
        effectiveToBusinessDate = to,
    )

    private fun source(path: String): String =
        String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)

    companion object {
        private const val TABLE_MARKER = "tax_category_history_v135"
    }
}

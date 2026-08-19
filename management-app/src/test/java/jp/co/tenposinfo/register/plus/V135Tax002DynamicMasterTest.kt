package jp.co.tenposinfo.register.plus

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate

class V135Tax002DynamicMasterTest {
    @Test
    fun initialMasterContainsFiveRequiredCategoriesWithoutPreventingFutureRates() {
        val rules = ManagementTaxCategoryPolicyV135.initialCategories
        assertEquals(5, rules.size)
        assertEquals(
            setOf("NON_TAXABLE", "INCLUDED_10", "EXCLUDED_10", "INCLUDED_8", "EXCLUDED_8"),
            rules.mapTo(linkedSetOf()) { it.taxKey },
        )

        val future = ManagementTaxCategoryPolicyV135.validate(
            ManagementTaxCategoryV135(
                taxKey = "future_12_ex",
                label = "12%外税",
                ratePercent = 12,
                mode = ManagementTaxModeV135.EXCLUDED,
                reduced = false,
                symbol = "外12",
                effectiveFromBusinessDate = "2027-04-01",
            ),
        )
        assertEquals("FUTURE_12_EX", future.taxKey)
        assertEquals(12, future.ratePercent)
        assertTrue(future.taxable)
        assertFalse(future.taxIncluded)
    }

    @Test
    fun nonTaxableAndTaxableValidationRemainDistinct() {
        val nonTaxable = ManagementTaxCategoryPolicyV135.validate(
            ManagementTaxCategoryV135(
                taxKey = "FREE",
                label = "非課税",
                ratePercent = 0,
                mode = ManagementTaxModeV135.NON_TAXABLE,
                reduced = false,
                symbol = "非",
                effectiveFromBusinessDate = "2026-08-19",
            ),
        )
        assertFalse(nonTaxable.taxable)

        val invalid = runCatching {
            ManagementTaxCategoryPolicyV135.validate(nonTaxable.copy(ratePercent = 12))
        }
        assertTrue(invalid.isFailure)
    }

    @Test
    fun businessDateChoosesRevisionRatherThanCalendarMidnight() {
        val old = ManagementTaxCategoryV135(
            id = 1,
            taxKey = "FUTURE_RATE",
            label = "12%外税",
            ratePercent = 12,
            mode = ManagementTaxModeV135.EXCLUDED,
            reduced = false,
            symbol = "外12",
            effectiveFromBusinessDate = "2026-08-01",
            effectiveToBusinessDate = "2026-08-31",
        )
        val next = old.copy(
            id = 2,
            label = "13%外税",
            ratePercent = 13,
            symbol = "外13",
            effectiveFromBusinessDate = "2026-09-01",
            effectiveToBusinessDate = "",
        )

        assertEquals(
            12,
            ManagementTaxCategoryPolicyV135.effectiveRules(listOf(old, next), LocalDate.parse("2026-08-31"))
                .single().ratePercent,
        )
        assertEquals(
            13,
            ManagementTaxCategoryPolicyV135.effectiveRules(listOf(old, next), LocalDate.parse("2026-09-01"))
                .single().ratePercent,
        )
    }

    @Test
    fun snapshotUsesDynamicCategoryArrayAndKeepsThirdRate() {
        val custom = ManagementTaxCategoryV135(
            taxKey = "INCLUDED_12",
            label = "12%内税",
            ratePercent = 12,
            mode = ManagementTaxModeV135.INCLUDED,
            reduced = false,
            symbol = "内12",
            effectiveFromBusinessDate = "2026-08-19",
        )
        val json = ManagementTaxCategoryPolicyV135.snapshotJson(
            ManagementTaxCategoryPolicyV135.initialCategories + custom,
            LocalDate.parse("2026-08-19"),
        )
        val root = JSONObject(json)
        val categories = root.getJSONArray("categories")
        assertEquals(6, categories.length())
        assertTrue((0 until categories.length()).any { index ->
            categories.getJSONObject(index).optInt("ratePercent") == 12
        })
        assertFalse(json.contains("tax8Amount"))
        assertFalse(json.contains("tax10Amount"))
    }

    @Test
    fun plusSalesReportAggregatesThirdRateWithoutFixedEightTenColumns() {
        val report = SalesReportCalculator.calculate(
            listOf(
                SalesJournalReportEntry(
                    duplicateImportKey = "k1",
                    eventType = SalesReportCalculator.EVENT_SALE,
                    storeId = "STORE",
                    terminalId = "POS1",
                    businessDate = "2026-08-19",
                    aggregateId = "1",
                    occurredAt = 1L,
                    payloadSchema = "register.sale.v2",
                    payloadJson = """{"totalAmount":1120,"taxTotals":[{"ratePercent":12,"taxAmount":120}]}""",
                    totalAmount = 1_120L,
                    sourceName = "sale.json",
                ),
            ),
        )

        assertTrue(report.taxBreakdownComplete)
        assertEquals(listOf(SalesAmountBreakdown("12%", 120L)), report.taxBreakdown)
    }

    @Test
    fun plusUiSchemaAndPosEngineStayConnectedToDynamicMaster() {
        val sourceRoot = "src/main/java/jp/co/tenposinfo/register/plus"
        val master = source("$sourceRoot/TaxCategoryMasterV135.kt")
        val activity = source("$sourceRoot/TaxCategoryMasterActivityV135.kt")
        val shell = source("$sourceRoot/ManagementFolderSyncScreen.kt")
        val reporting = source("$sourceRoot/SalesReporting.kt")
        val manifest = source("src/main/AndroidManifest.xml")
        val posDomain = source("../app/src/main/java/jp/co/tenposinfo/register/Domain.kt")
        val posDynamic = source("../app/src/main/java/jp/co/tenposinfo/register/DynamicCatalogRuntime.kt")

        for (token in listOf(
            "tax_category_master_v135",
            "UNIQUE(tax_key, effective_from_business_date)",
            "TAX_CATEGORY_HISTORY_MUST_NOT_BE_DELETED",
            "register.tax-categories.v1",
            "ratePercent",
            "effectiveFromBusinessDate",
            "enabled",
        )) assertTrue(master.contains(token))
        assertFalse(master.contains("fun delete"))

        assertTrue(activity.contains("税区分マスター"))
        assertTrue(activity.contains("新規追加"))
        assertTrue(activity.contains("改定"))
        assertTrue(shell.contains("TaxCategoryMasterActivityV135"))
        assertTrue(shell.contains("税区分マスター"))
        assertTrue(manifest.contains(".TaxCategoryMasterActivityV135"))
        assertTrue(reporting.contains("val totals = payload.optJSONArray(\"taxTotals\")"))
        assertTrue(reporting.contains("firstText(row, \"ratePercent\", \"taxRate\", \"rate\")"))
        assertTrue(posDomain.contains("groupBy { it.product.taxRatePercent }"))
        assertTrue(posDynamic.contains("record.ratePercent in 0..100"))
        assertTrue(posDynamic.contains("TaxCategory.entries.forEachIndexed"))
    }

    private fun source(path: String): String =
        String(Files.readAllBytes(Paths.get(path)), Charsets.UTF_8)
}
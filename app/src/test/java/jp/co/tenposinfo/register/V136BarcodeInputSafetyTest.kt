package jp.co.tenposinfo.register

import java.io.File
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class V136BarcodeInputSafetyTest {
    private fun product(id: String, barcode: String = "") = Product(
        id = id,
        name = id,
        unitPrice = 100,
        taxCategory = TaxCategory.INCLUDED_10,
        displayOrder = 1,
        barcode = barcode,
    )

    @Test
    fun hidDecoderAcceptsShortCodesAndEnterSuffix() {
        val decoder = HidBarcodeDecoderV136()
        assertNull(decoder.accept('A', false, 10L))
        assertEquals("A", decoder.accept(null, true, 20L))
    }

    @Test
    fun hidDecoderResetsSlowPartialInput() {
        val decoder = HidBarcodeDecoderV136(maxInterKeyMillis = 100L)
        decoder.accept('A', false, 10L)
        decoder.accept('B', false, 500L)
        assertEquals("B", decoder.accept(null, true, 510L))
    }

    @Test
    fun scannerIndexSupportsCodeBarcodeAndFailsClosedOnAmbiguity() {
        val byId = product("P0001", "4900000000010")
        val byBarcode = product("P0002", "ABC")
        val index = BarcodeProductIndexV136(listOf(byId, byBarcode))
        assertSame(byId, index.findExact("p0001"))
        assertSame(byBarcode, index.findExact("ABC"))

        val ambiguous = BarcodeProductIndexV136(listOf(product("ABC"), byBarcode))
        assertNull(ambiguous.findExact("ABC"))
    }

    @Test
    fun indexedLookupProcessesTenThousandProductsWellInsideFormalBudget() {
        val products = (0 until 10_000).map { i ->
            product("P%05d".format(i), "49%011d".format(i))
        }
        val index = BarcodeProductIndexV136(products)
        repeat(1_000) { assertTrue(index.findExact("P09999") != null) }
        val elapsed = measureNanoTime {
            repeat(10_000) { assertTrue(index.findExact("4900000009999") != null) }
        }
        assertTrue("10k indexed lookups exceeded 100ms: ${elapsed / 1_000_000.0}ms", elapsed < 100_000_000L)
    }

    @Test
    fun temporaryProductUsesFormalNameAndRetainsScannedCode() {
        val p = TemporaryBarcodeProductPolicyV136.create(
            code = "4901234567890",
            unitPrice = 800,
            taxCategory = TaxCategory.INCLUDED_8,
        )
        assertEquals("未登録商品", p.name)
        assertEquals("4901234567890", p.id)
        assertEquals("4901234567890", p.barcode)
        assertEquals(800L, p.unitPrice)
        assertEquals(TaxCategory.INCLUDED_8, p.taxCategory)
    }

    @Test
    fun sourceUsesScannerGatewayCentralRouterAndPermissionGatedUnregisteredChoices() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val main = File(root, "MainActivity.kt").readText()
        val scanner = File(root, "BarcodeInputV136.kt").readText()
        val dialog = File(root, "UnregisteredBarcodeDialogV136.kt").readText()
        val catalog = File(root, "CatalogHubActivityV030.kt").readText()
        val settings = File(root, "CatalogSettingsActivity.kt").readText()

        assertTrue(main.contains("ScannerGatewayV136"))
        assertTrue(main.contains("InputRouterV136.setBarcodeListener"))
        assertTrue(!main.contains("BarcodeScannerRuntimeV135.handle(event)"))
        assertTrue(scanner.contains("fun start()"))
        assertTrue(scanner.contains("fun stop()"))
        assertTrue(scanner.contains("fun barcodeScanned(event: BarcodeScannedV136)"))
        assertTrue(dialog.contains("canOpenProductSettings"))
        assertTrue(dialog.contains("Text(\"仮商品\")"))
        assertTrue(dialog.contains("Text(\"商品登録\")"))
        assertTrue(dialog.contains("Text(\"キャンセル\")"))
        assertTrue(catalog.contains("EXTRA_PREFILL_BARCODE"))
        assertTrue(settings.contains("initialBarcode"))
    }
}

package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136Receipt80mmTaxColumnFallbackTest {
    @Test
    fun standard80mmWidthUsesDedicatedTaxColumn() {
        assertTrue(ReceiptTaxSymbolV136.canUseDedicatedColumn(ReceiptPaper.MM80.charsPerLine))
    }

    @Test
    fun narrowLogicalWidthFallsBackToInlineTaxSymbol() {
        assertFalse(ReceiptTaxSymbolV136.canUseDedicatedColumn(20))
        assertTrue(ReceiptTaxSymbolV136.canUseDedicatedColumn(21))
    }
}

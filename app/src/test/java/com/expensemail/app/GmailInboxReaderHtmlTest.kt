package com.expensemail.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GmailInboxReaderHtmlTest {
    @Test
    fun separatesAdjacentReceiptTableCellsBeforeParsing() {
        val html = """<table>
            <tr><td>Status</td><td>:</td><td>Successful</td></tr>
            <tr><td>Transaction Date</td><td>:</td><td>07 Oct 2026 13:46:10</td></tr>
            <tr><td>Transaction Type</td><td>:</td><td>QRIS Payment</td></tr>
            <tr><td>Payment to</td><td>:</td><td>OWL KOTA KASABLANKA</td></tr>
            <tr><td>Total Payment</td><td>:</td><td>IDR 3,999,000.00</td></tr>
        </table>""".trimIndent()
        val readable = addHtmlTableBoundaries(html).replace(Regex("<[^>]*>"), " ")
        val parsed = BankAlertParser.parse(readable, "BCA <bca@bca.co.id>")
        assertEquals("OWL KOTA KASABLANKA", parsed?.merchant)
        assertEquals(3_999_000L, parsed?.amount)
        assertEquals("7 Oct 2026", parsed?.date)
    }
}

package com.expensemail.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankAlertParserTest {
    @Test
    fun parsesBcaQrisReceiptUsingTotalPayment() {
        val result = BankAlertParser.parse(
            """myBCA
                |Status : Successful
                |Transaction Date : 07 Oct 2026 13:16:02
                |Transaction Type : QRIS Payment
                |Payment to : NANNYS PAVILLON CASA
                |Total Payment : IDR 219,100.00""".trimMargin(),
            "BCA <bca@bca.co.id>",
        )

        assertEquals("NANNYS PAVILLON CASA", result?.merchant)
        assertEquals(219100L, result?.amount)
        assertEquals("7 Oct 2026", result?.date)
        assertEquals("BCA", result?.bank)
    }

    @Test
    fun parsesBcaFlazzTopUp() {
        val result = BankAlertParser.parse(
            """Status : Successful
                |Transaction Date : 09 Oct 2026 17:51:58
                |Transaction Type : Flazz Top Up
                |Flazz Card Number : 1234567890123456
                |Source of Fund : 1111****22
                |Top Up Amount : IDR 50,000.00""".trimMargin(),
            "bca@bca.co.id",
        )
        assertEquals("Flazz top-up", result?.merchant)
        assertEquals(50000L, result?.amount)
        assertEquals("9 Oct 2026", result?.date)
    }

    @Test
    fun parsesBcaVirtualAccountTopUpAsTransfer() {
        val result = BankAlertParser.parse(
            """Status : Successful
                |Transaction Date : 02 Jan 2026 16:01:36
                |Transfer Type : Transfer to BCA Virtual Account
                |BCA Virtual Account No. : 123456789
                |Company/Product Name : PT VISIONET INTERNASIONAL / OVO NABUNG
                |Pay Amount : IDR 25,000.00
                |Total Payment : IDR 25,000.00""".trimMargin(),
            "bca@bca.co.id",
        )
        assertEquals("OVO NABUNG", result?.merchant)
        assertEquals(25000L, result?.amount)
        assertEquals("Transfer", result?.recordType)
    }

    @Test
    fun parsesBcaVirtualAccountPurchaseAsExpense() {
        val result = BankAlertParser.parse(
            """Status : Successful
                |Transaction Date : 02 Jan 2026 16:01:36
                |Transfer Type : Transfer to BCA Virtual Account
                |BCA Virtual Account No. : 123456789
                |Company/Product Name : PT TOKOPEDIA / TOKOPEDIA
                |billdesc : IDR 80,000.00
                |Bill Total : IDR 80,000.00
                |Total Payment : IDR 80,000.00""".trimMargin(),
            "bca@bca.co.id",
        )
        assertEquals("TOKOPEDIA", result?.merchant)
        assertEquals(80000L, result?.amount)
        assertEquals("Expense", result?.recordType)
    }

    @Test
    fun parsesBcaTransferAndCashWithdrawalAsTransfers() {
        val transfer = BankAlertParser.parse(
            """Status : Successful
                |Transaction Date : 14 Jan 2026 12:04:23
                |Transfer Type : Transfer to BCA Account
                |Beneficiary Account : 123456789
                |Beneficiary Name : TEST RECIPIENT
                |Transfer Amount : IDR 21,700.00""".trimMargin(),
            "bca@bca.co.id",
        )
        assertEquals("Transfer to TEST RECIPIENT", transfer?.merchant)
        assertEquals(21700L, transfer?.amount)
        assertEquals("Transfer", transfer?.recordType)

        val withdrawal = BankAlertParser.parse(
            """Status : Successful
                |Transaction Date : 10 Mar 2026 17:33:14
                |Transfer Type : Cardless - Withdraw Cash
                |Source of Fund : 2302xxxx07
                |Amount : IDR 50,000.00""".trimMargin(),
            "bca@bca.co.id",
        )
        assertEquals("Cash withdrawal", withdrawal?.merchant)
        assertEquals(50000L, withdrawal?.amount)
        assertEquals("Transfer", withdrawal?.recordType)
    }

    @Test
    fun parsesBcaLifestylePurchaseButRejectsFailedReceipt() {
        val purchase = BankAlertParser.parse(
            """Status : Successful
                |Transaction Date : 19 May 2026 12:06:44
                |Transaction Type : Lifestyle
                |Partner Name : tiket.com
                |Amount : IDR 4,243,700.00
                |Total Payment : IDR 4,243,700.00""".trimMargin(),
            "bca@bca.co.id",
        )
        assertEquals("tiket.com", purchase?.merchant)
        assertEquals(4243700L, purchase?.amount)
        assertEquals("Expense", purchase?.recordType)

        assertNull(BankAlertParser.parse(
            """Status : Failed
                |Transaction Date : 18 Jul 2026 19:33:39
                |Transaction Type : QRIS Payment
                |Payment to : TEST MERCHANT
                |Total Payment : IDR 50,000.00""".trimMargin(),
            "bca@bca.co.id",
        ))
    }

    @Test
    fun parsesUobCompletedTransfersButNotProcessingNotice() {
        val sent = BankAlertParser.parse(
            """Jumlah : Rp 80,050,000.00
                |Untuk : TEST RECIPIENT
                |Dikirimkan pada : 02-Jan-2026
                |Bank penerima : BCA""".trimMargin(),
            "noreply_UOB@uob.co.id",
        )
        assertEquals("Transfer to TEST RECIPIENT", sent?.merchant)
        assertEquals(80050000L, sent?.amount)
        assertEquals("Transfer", sent?.recordType)

        val received = BankAlertParser.parse(
            """Jumlah : Rp 500,000.00
                |Diterima pada : 03-Feb-2026 20:53
                |Bank pengirim : BANK CENTRAL ASIA""".trimMargin(),
            "noreply_UOB@uob.co.id",
        )
        assertEquals("Transfer from BANK CENTRAL ASIA", received?.merchant)
        assertEquals(500000L, received?.amount)
        assertEquals("Transfer", received?.recordType)

        assertNull(BankAlertParser.parse(
            """Jumlah : Rp 80,050,000.00
                |Untuk : TEST RECIPIENT
                |Permintaan pada : 02-Jan-2026
                |Kami akan mengirim email kembali saat transaksi berhasil.""".trimMargin(),
            "noreply_UOB@uob.co.id",
        ))
    }

    @Test
    fun parsesMandiriQrTransfer() {
        val result = BankAlertParser.parse(
            """Transfer Berhasil
                |Penerima TEST RECIPIENT Bank Mandiri - ****9643
                |Tanggal 31 Mei 2026
                |Jam 14:05:48 WIB
                |Nominal Rp 707.000,00""".trimMargin(),
            "noreply.livin@bankmandiri.co.id",
        )
        assertEquals("Transfer to TEST RECIPIENT", result?.merchant)
        assertEquals(707000L, result?.amount)
        assertEquals("Transfer", result?.recordType)
    }

    @Test
    fun parsesJagoFeeAndDebitCardAlertsUsingReceivedDate() {
        val receivedAt = 1_791_570_000_000L
        val fee = BankAlertParser.parseJagoAlertWithReceivedDate(
            "Kamu telah dikenakan biaya sebesar Rp5.000 karena saldo di Kantong terhubung tidak mencukupi.",
            "Jago <noreply@jago.com>", receivedAt,
        )
        assertEquals("Jago insufficient-balance fee", fee?.merchant)
        assertEquals(5000L, fee?.amount)
        val debit = BankAlertParser.parseJagoAlertWithReceivedDate(
            "Kamu telah melakukan transaksi sebesar Rp138.833 menggunakan kartu debit Jago.",
            "noreply@jago.com", receivedAt,
        )
        assertEquals("Jago debit card", debit?.merchant)
        assertEquals(138833L, debit?.amount)
        assertNull(BankAlertParser.parseJagoAlertWithReceivedDate(
            "Kamu telah melakukan transaksi sebesar Rp138.833 menggunakan kartu debit Jago.",
            "other@jago.com", receivedAt,
        ))
    }

    @Test
    fun distinguishesFailedAndUnrecognizedTransactionsFromNotices() {
        assertEquals("Bank marked this transaction as failed", BankAlertParser.skipReason(
            "Status : Failed\nTransaction Date : 18 Jul 2026\nTotal Payment : IDR 50,000.00",
            "bca@bca.co.id",
        ))
        assertEquals("Possible transaction needs review", BankAlertParser.skipReason(
            "Status : Successful\nTransaction Date : 18 Jul 2026\nAmount : IDR 50,000.00",
            "bca@bca.co.id",
        ))
        assertEquals("Transfer is still processing", BankAlertParser.skipReason(
            "Permintaan pada : 02-Jan-2026. Kami akan mengirim email kembali saat transaksi berhasil.",
            "noreply_UOB@uob.co.id",
        ))
        assertEquals("No completed transaction found", BankAlertParser.skipReason(
            "Pengkinian Data Nasabah 2026", "noreply@jago.com",
        ))
    }

    @Test
    fun parsesLivinPaymentIncludingTransactionFeeInTotal() {
        val result = BankAlertParser.parse(
            """Pembayaran Berhasil
                |Penerima Tokopedia 887088123456
                |Tanggal 4 Okt 2026
                |Jam 16:36:11 WIB
                |Nominal Transaksi IDR 83.016,00
                |Biaya Transaksi IDR 1.000,00
                |Total Transaksi IDR 84.016,00""".trimMargin(),
            "noreply.livin@bankmandiri.co.id",
        )

        assertEquals("Tokopedia", result?.merchant)
        assertEquals(84016L, result?.amount)
        assertEquals("4 Oct 2026", result?.date)
        assertEquals("Shopping", result?.category)
    }

    @Test
    fun parsesLivinTopUpReceipt() {
        val result = BankAlertParser.parse(
            """Top-up e-money Berhasil
                |Penyedia Jasa : GoPay
                |Tanggal : 4 Okt 2026
                |Nominal Top-up : IDR 50.000,00""".trimMargin(),
            "noreply.livin@bankmandiri.co.id",
        )

        assertEquals("GoPay", result?.merchant)
        assertEquals(50000L, result?.amount)
    }

    @Test
    fun parsesJagoTransferAsTransfer() {
        val result = BankAlertParser.parse(
            """Transfer uang berhasil
                |Ringkasan transaksi
                |Dari MA • 99887766
                |Ke TEST RECIPIENT Mandiri • 1234
                |Jumlah Rp470.000
                |Tanggal transaksi 30 September 2026 21:20 WIB""".trimMargin(),
            "noreply@jago.com",
        )

        assertEquals("Transfer to TEST RECIPIENT Mandiri", result?.merchant)
        assertEquals(470000L, result?.amount)
        assertEquals("30 Sep 2026", result?.date)
        assertEquals("Transfer", result?.recordType)
    }

    @Test
    fun parsesUobBillPaymentWithHyphenatedDate() {
        val result = BankAlertParser.parse(
            """Anda telah melakukan pembayaran tagihan/pembelian
                |Jam,Tanggal : 05:45AM 14-Feb-2025
                |Nama Perusahaan : TMRW CREDIT CARD
                |Jumlah : IDR 200,000.00
                |Status Transaksi : Berhasil""".trimMargin(),
            "noreply_UOB@uob.co.id",
        )

        assertEquals("TMRW CREDIT CARD", result?.merchant)
        assertEquals(200000L, result?.amount)
        assertEquals("14 Feb 2025", result?.date)
    }

    @Test
    fun rejectsPromotionsAndCssCurrencyNoise() {
        assertNull(
            BankAlertParser.parse(
                "Diskon spesial! Cashback Rp50.000 untuk transaksi berikutnya.",
                "info@jago.com",
            ),
        )
        assertNull(
            BankAlertParser.parse(
                """myBCA
                    |<style>body { max-width: 600px; }</style>
                    |Promo pembayaran dengan diskon Rp50.000.""".trimMargin(),
                "bca@bca.co.id",
            ),
        )
    }
}

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
    fun parsesBcaMobileDataPurchaseAndCategorizesItAsUtilities() {
        val result = BankAlertParser.parse(
            """Status : Successful
                |Transaction Date : 09 Oct 2026 17:51:58
                |Transaction Type : Mobile Data - PAKET DATA AXIS
                |Source of Fund : 2302****07
                |Amount : IDR 17,000.00
                |Admin Fee : IDR 1,500.00
                |Total Payment : IDR 18,500.00
                |Details: BRONET 2GB""".trimMargin(),
            "bca@bca.co.id",
        )

        assertEquals("Mobile Data - PAKET DATA AXIS", result?.merchant)
        assertEquals(18500L, result?.amount)
        assertEquals("9 Oct 2026", result?.date)
        assertEquals("Bills & utilities", result?.category)
        assertEquals("Expense", result?.recordType)
    }

    @Test
    fun categorizesObservedOctoberBcaQrisMerchants() {
        val cases = listOf(
            "SOZO SKIN CLINIC PONDOK B" to "Health & personal care",
            "UDDERS ICE CREAM" to "Food & drink",
            "MAGURO ORANGE GROVES PIK" to "Food & drink",
            "PAP Photobooth" to "Entertainment",
            "COSMONAUTS SPACEWEAR-HO" to "Shopping",
            "Cummo Car Wash 7802" to "Transport",
            "TUKU PAHLAWAN REVOLUSI" to "Food & drink",
            "CAFE COCO MADE" to "Food & drink",
            "SEBA IZAKAYA" to "Food & drink",
            "IDM INDOMARET D" to "Groceries",
            "OWL KOTA KASABLANKA" to "Food & drink",
            "NANNYS PAVILLON CASA" to "Food & drink",
            "ESB RESTAURANT TECHNOLOGY" to "Food & drink",
            "Klinik Ban Bright Olimart" to "Transport",
            "SPBU 31.13402,KALI MLNG" to "Transport",
            "Sour Sally" to "Food & drink",
            "JICAF 2026" to "Entertainment",
            "LOKET" to "Entertainment",
        )

        cases.forEach { (merchant, category) ->
            val result = BankAlertParser.parse(
                """Status : Successful
                    |Transaction Date : 11 Oct 2026 09:37:08
                    |Transaction Type : QRIS Payment
                    |Payment to : $merchant
                    |Merchant Location : Jakarta, ID
                    |Total Payment : IDR 63,120.00""".trimMargin(),
                "bca@bca.co.id",
            )

            assertEquals("merchant=$merchant", merchant, result?.merchant)
            assertEquals("merchant=$merchant", category, result?.category)
            assertEquals("merchant=$merchant", "Expense", result?.recordType)
        }
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
        assertEquals("No completed transaction found", BankAlertParser.skipReason(
            "Tanggal Waktu Transaksi : 08:42PM 15-Jan-2025\nStatus Transaksi : Pembayaran QR | Berhasil",
            "noreply_UOB@uob.co.id",
        ))
        assertEquals("Possible transaction needs review", BankAlertParser.skipReason(
            "Tanggal Waktu Transaksi : 08:42PM 15-Jan-2025\nJumlah : IDR 3,999,000.00\nStatus Transaksi : Pembayaran QR | Berhasil",
            "noreply_UOB@uob.co.id",
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

        assertEquals("Transfer to TEST RECIPIENT", result?.merchant)
        assertEquals(470000L, result?.amount)
        assertEquals("30 Sep 2026", result?.date)
        assertEquals("Transfer", result?.recordType)
    }

    @Test
    fun labelsJagoRdnPocketWithdrawalWithoutInventingAReceiverName() {
        val result = BankAlertParser.parse(
            """Penarikan dana dari Kantong RDN berhasil dilakukan dengan rincian berikut:
                |Ringkasan transaksi
                |Dari: 118957371558
                |Ke: 104796057039
                |Jumlah: Rp190.331
                |Tanggal Transaksi: 12 October 2026 08:17 WIB""".trimMargin(),
            "noreply@jago.com",
        )

        assertEquals("RDN pocket withdrawal", result?.merchant)
        assertEquals(190331L, result?.amount)
        assertEquals("12 Oct 2026", result?.date)
        assertEquals("Transfer", result?.recordType)
        assertEquals("Transfer", result?.category)
    }

    @Test
    fun masksDestinationAccountWhenJagoDoesNotProvideARecipientName() {
        val result = BankAlertParser.parse(
            """Transfer uang berhasil
                |Ringkasan transaksi
                |Dari: 118957371558
                |Ke: 104796057039
                |Jumlah: Rp190.331
                |Tanggal Transaksi: 12 October 2026 08:17 WIB""".trimMargin(),
            "noreply@jago.com",
        )

        assertEquals("Transfer to account", result?.merchant)
    }

    @Test
    fun masksGroupedDestinationAccountAfterJagoRecipientName() {
        val result = BankAlertParser.parse(
            """Transfer uang berhasil
                |Dari MA • 99887766
                |Ke TEST RECIPIENT 1047 9605 7039
                |Jumlah Rp470.000
                |Tanggal transaksi 30 September 2026 21:20 WIB""".trimMargin(),
            "noreply@jago.com",
        )

        assertEquals("Transfer to TEST RECIPIENT", result?.merchant)
    }

    @Test
    fun keepsJagoRecipientNameAndRemovesBankAccountSuffix() {
        val result = BankAlertParser.parse(
            """Terima kasih sudah bertransaksi dengan Jago! Kamu baru saja melakukan transfer uang.
                |Dari MA • 104796057039
                |Ke ZHAFRAN RIFANDI Mandiri • 1660002202042
                |Jumlah Rp190.000
                |Tanggal transaksi 12 October 2026 08:33 WIB""".trimMargin(),
            "noreply@jago.com",
        )

        assertEquals("Transfer to ZHAFRAN RIFANDI", result?.merchant)
    }

    @Test
    fun rejectsFailedJagoTransfersEvenWhenReceiptHasAllTransferFields() {
        val result = BankAlertParser.parse(
            """Transfer gagal
                |Dari MA • 99887766
                |Ke TEST RECIPIENT
                |Jumlah Rp470.000
                |Tanggal transaksi 30 September 2026 21:20 WIB""".trimMargin(),
            "noreply@jago.com",
        )

        assertNull(result)
    }

    @Test
    fun rejectsPendingJagoTransfersAndAcceptsExplicitSuccessfulStatus() {
        val fields = """Dari MA • 99887766
            |Ke TEST RECIPIENT
            |Jumlah Rp470.000
            |Tanggal transaksi 30 September 2026 21:20 WIB""".trimMargin()

        assertNull(BankAlertParser.parse("Transfer pending\n$fields", "noreply@jago.com"))
        assertEquals(
            "Transfer to TEST RECIPIENT",
            BankAlertParser.parse("$fields\nStatus Transaksi: Berhasil", "noreply@jago.com")?.merchant,
        )
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
    fun parsesUobQrReceiptAndUsesMerchantName() {
        val result = BankAlertParser.parse(
            """Status Transaksi : Pembayaran QR | Berhasil
                |Nama Bank Pemilik Aplikasi | Sumber Dana : Bank UOB | 7353809141
                |Nama Bank Pemilik Merchant | Merchant PAN : Dana | 9360091501693705929
                |Nama Merchant : saweria
                |Customer PAN | Dana | 936000092007373538091412
                |Terminal ID | Lokasi Terminal | saweria Kota Jakarta
                |Tanggal Waktu Transaksi : 08:42PM 15-Jan-2025
                |Nomor Resi : 501520745956
                |Tipe Transaksi : Pembayaran QR
                |Jumlah : IDR 3,999,000.00""".trimMargin(),
            "noreply_UOB@uob.co.id",
        )

        assertEquals("saweria", result?.merchant)
        assertEquals(3999000L, result?.amount)
        assertEquals("15 Jan 2025", result?.date)
        assertEquals("Other", result?.category)
        assertEquals("Expense", result?.recordType)
    }

    @Test
    fun parsesBniTransferEmailAsTransferAndMasksDestinationAccount() {
        val result = BankAlertParser.parse(
            """Transfer berhasil!
                |Penerima
                |Bpk ADE ROMANSAH
                |BNI • 159416532
                |Sumber dana
                |ZHAFRAN RIFANDI
                |Tanggal & waktu transaksi Tanggal 21-Feb-2026 Waktu 17:42:02 WIB
                |Nominal transaksi Nominal Rp1.300.000
                |Biaya admin Rp0
                |Total Rp1.300.000
                |Reference ID 20260221054159000518""".trimMargin(),
            "wondr@bni.co.id",
        )

        assertEquals("Transfer to Bpk ADE ROMANSAH", result?.merchant)
        assertEquals(1300000L, result?.amount)
        assertEquals("21 Feb 2026", result?.date)
        assertEquals("BNI", result?.bank)
        assertEquals("Transfer", result?.recordType)
    }

    @Test
    fun parsesBniWalletTopUpTotalAsExpense() {
        val result = BankAlertParser.parse(
            """Top-up berhasil!
                |Penerima OVO-ZHAFRAN RIFANDI
                |OVO • 62*******7296
                |Sumber dana ZHAFRAN RIFANDI
                |Tanggal & waktu top-up Tanggal 02 Juni 2026 Waktu 10:50:27 WIB
                |Detail top-up
                |Nominal Rp150.000
                |Biaya transaksi Rp0
                |Total Rp150.000
                |Ref ID 20260602105023098629""".trimMargin(),
            "wondr@bni.co.id",
        )

        assertEquals("OVO-ZHAFRAN RIFANDI", result?.merchant)
        assertEquals(150000L, result?.amount)
        assertEquals("2 Jun 2026", result?.date)
        assertEquals("Expense", result?.recordType)
    }

    @Test
    fun doesNotTreatBniCashWithdrawalCodeEmailAsCompletedTransaction() {
        val result = BankAlertParser.parse(
            """Kode tarik tunai siap digunakan!
                |Tanggal & waktu transaksi 30 Mei 2026 17:24:34 WIB
                |Nominal Rp150.000
                |Penyedia layanan ATM BNI""".trimMargin(),
            "wondr@bni.co.id",
        )

        assertNull(result)
    }

    @Test
    fun parsesOcbcFundsTransferAndUsesTheTransferDateAndRecipient() {
        val result = BankAlertParser.parse(
            """Successful Funds Transfer To Other Bank
                |We are pleased to inform you that the following funds transfer request has been successful.
                |FROM
                |ZHAFRAN RIFANDI
                |AMOUNT IDR 17,425,000
                |TO
                |ZHAFRAN RIFANDI
                |BANK BCA
                |1234567890
                |TRANSFER DATE:
                |03 Sep 2026 14:26:40 WIB
                |INSTRUCTION DATE:
                |03 Sep 2026 14:26:38 WIB""".trimMargin(),
            "notifikasi@ocbc.id",
        )

        assertEquals("Transfer to ZHAFRAN RIFANDI", result?.merchant)
        assertEquals(17425000L, result?.amount)
        assertEquals("3 Sep 2026", result?.date)
        assertEquals("OCBC", result?.bank)
        assertEquals("Transfer", result?.recordType)
    }

    @Test
    fun parsesOcbcQrPaymentUsingMerchantAndDayFirstNumericDate() {
        val result = BankAlertParser.parse(
            """QR Payment Successful
                |We inform you that the following QR Payment request has been successful.
                |Merchant PAN
                |936000123456789
                |Tako
                |TANGERANG, 12870
                |Terminal No. A01
                |Amount Pay IDR 3,999,000.00
                |Payment Date:
                |03/08/2026""".trimMargin(),
            "notifikasi@ocbc.id",
        )

        assertEquals("Tako", result?.merchant)
        assertEquals(3999000L, result?.amount)
        assertEquals("3 Aug 2026", result?.date)
        assertEquals("Expense", result?.recordType)

        val impossibleDate = BankAlertParser.parse(
            """QR Payment Successful
                |The following QR Payment request has been successful.
                |Merchant PAN 936000123456789
                |Tako
                |Amount Pay IDR 3,999,000.00
                |Payment Date: 31/02/2026""".trimMargin(),
            "notifikasi@ocbc.id",
        )
        assertNull(impossibleDate)
    }

    @Test
    fun prefersExplicitOcbcQrMerchantOverBankAndSourceMetadata() {
        val result = BankAlertParser.parse(
            """QR Payment Successful
                |The following QR Payment request has been successful.
                |Merchant PAN
                |936000123456789
                |Nama Bank Pemilik Aplikasi | Sumber Dana : Bank OCBC | 1234567890
                |Nama Bank Pemilik Merchant | BCA | 9876543210
                |Nama Merchant : saweria
                |Amount Pay IDR 3,999,000.00
                |Payment Date: 03/08/2026""".trimMargin(),
            "notifikasi@ocbc.id",
        )

        assertEquals("saweria", result?.merchant)
        assertEquals(3999000L, result?.amount)
        assertEquals("Expense", result?.recordType)
    }

    @Test
    fun parsesOcbcBillPaymentAsExpenseAndIgnoresDeviceLoginNotices() {
        val payment = BankAlertParser.parse(
            """Successful Bill Payment
                |The following request for bill payment has been successful.
                |TO OVO
                |PAYMENT AMOUNT IDR 15,000
                |PAYMENT DATE:
                |28 Apr 2026 19:18:47 WIB""".trimMargin(),
            "notifikasi@ocbc.id",
        )
        val login = BankAlertParser.parse("Device Successfully Logged In to OCBC mobile", "notifikasi@ocbc.id")

        assertEquals("OVO", payment?.merchant)
        assertEquals(15000L, payment?.amount)
        assertEquals("28 Apr 2026", payment?.date)
        assertEquals("Expense", payment?.recordType)
        assertNull(login)
        assertNull(BankAlertParser.parse("Successful Funds Transfer to merchant", "untrusted@ocbc.id"))
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

    @Test
    fun rejectsFlattenedCssAndBankFooterButKeepsLegitimateCssMerchant() {
        val malformed = listOf(
            "1500888",
            "BCA AccountSource of Fund: 2302xxxx07Source of Fund",
            "background-color: #ffffff; text-align: center",
        )
        malformed.forEach { merchant ->
            assertNull(
                BankAlertParser.parse(
                    """Status : Successful
                        |Transaction Date : 11 Oct 2026 09:37:08
                        |Transaction Type : QRIS Payment
                        |Payment to : $merchant
                        |Total Payment : IDR 63,120.00""".trimMargin(),
                    "bca@bca.co.id",
                ),
            )
        }

        val legitimate = BankAlertParser.parse(
            """Status : Successful
                |Transaction Date : 11 Oct 2026 09:37:08
                |Transaction Type : QRIS Payment
                |Payment to : CSS Holdings
                |Total Payment : IDR 63,120.00""".trimMargin(),
            "bca@bca.co.id",
        )
        assertEquals("CSS Holdings", legitimate?.merchant)
    }
}

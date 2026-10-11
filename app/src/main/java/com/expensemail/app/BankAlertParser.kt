package com.expensemail.app

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/** Parses only known, structured transaction receipts. Marketing and account notices are ignored. */
internal object BankAlertParser {
    private enum class Bank(val label: String) {
        BCA("BCA"), MANDIRI("Livin’ by Mandiri"), JAGO("Bank Jago"), UOB("UOB")
    }

    private val senderBanks = mapOf(
        "bca@bca.co.id" to Bank.BCA,
        "noreply.livin@bankmandiri.co.id" to Bank.MANDIRI,
        "noreply@jago.com" to Bank.JAGO,
        "noreply_uob@uob.co.id" to Bank.UOB,
    )

    fun parse(raw: String, suppliedSender: String = ""): Expense? {
        val text = raw.replace('\u00A0', ' ').replace('\u200B', ' ').trim()
        if (text.length < 8) return null

        val senderInBody = Regex("(?im)^\\s*From:\\s*(?:.*?<)?([\\w.+-]+@[\\w.-]+)")
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
        val sender = suppliedSender.ifBlank { senderInBody }
        val senderAddress = Regex("(?i)[\\w.+-]+@[\\w.-]+")
            .find(sender)?.value?.lowercase(Locale.ROOT) ?: return null
        val bank = senderBanks[senderAddress] ?: return null

        val lower = text.lowercase(Locale.ROOT)
        val bcaTransactionType = if (bank == Bank.BCA) labeledValue(text, listOf("Transaction Type")) else null
        val bcaTransferType = if (bank == Bank.BCA) labeledValue(text, listOf("Transfer Type")) else null
        val bcaFlazzTopUp = bcaTransactionType.equals("Flazz Top Up", ignoreCase = true)
        val bcaLifestyle = bcaTransactionType.equals("Lifestyle", ignoreCase = true)
        val bcaVirtualAccount = bcaTransferType.equals("Transfer to BCA Virtual Account", ignoreCase = true)
        val bcaCashWithdrawal = bcaTransferType.equals("Cardless - Withdraw Cash", ignoreCase = true)
        val bcaBankTransfer = bcaTransferType?.startsWith("Transfer to ", ignoreCase = true) == true && !bcaVirtualAccount
        val bcaVirtualAccountProduct = if (bcaVirtualAccount) labeledValue(text, listOf("Company/Product Name")) else null
        val recordType = when (bank) {
            Bank.BCA -> {
                if (!hasField(text, "Transaction Date") ||
                    !labeledValue(text, listOf("Status")).equals("Successful", ignoreCase = true)) return null
                when {
                    bcaFlazzTopUp && hasField(text, "Flazz Card Number") && hasField(text, "Top Up Amount") -> "Expense"
                    bcaVirtualAccount && !bcaVirtualAccountProduct.isNullOrBlank() && hasField(text, "Total Payment") -> {
                        if (Regex("(?i)OVO NABUNG|GO-PAY TOPUP|BIBIT\\.ID|TOP[ -]?UP")
                                .containsMatchIn(bcaVirtualAccountProduct)) "Transfer" else "Expense"
                    }
                    bcaLifestyle && hasField(text, "Partner Name") && hasField(text, "Total Payment") -> "Expense"
                    bcaCashWithdrawal && hasField(text, "Amount") -> "Transfer"
                    bcaBankTransfer && hasField(text, "Beneficiary Name") &&
                        (hasField(text, "Transfer Amount") || hasField(text, "Amount")) -> "Transfer"
                    hasField(text, "Total Payment") && hasField(text, "Payment to") -> "Expense"
                    else -> return null
                }
            }
            Bank.MANDIRI -> when {
                hasField(text, "Penerima") && hasField(text, "Tanggal") &&
                    (hasField(text, "Total Transaksi") || hasField(text, "Nominal Transaksi")) -> "Expense"
                hasField(text, "Nominal Top-up") && hasField(text, "Tanggal") &&
                    hasField(text, "Penyedia Jasa") -> "Expense"
                lower.contains("transfer berhasil") && hasField(text, "Penerima") &&
                    hasField(text, "Tanggal") && hasField(text, "Nominal") -> "Transfer"
                else -> return null
            }
            Bank.JAGO -> when {
                hasField(text, "Dari") && hasField(text, "Ke") && hasField(text, "Jumlah") &&
                    hasField(text, "Tanggal transaksi") -> "Transfer"
                else -> return null
            }
            Bank.UOB -> when {
                hasField(text, "Jumlah") && hasField(text, "Dikirimkan pada") && hasField(text, "Untuk") -> "Transfer"
                hasField(text, "Jumlah") && hasField(text, "Diterima pada") && hasField(text, "Bank pengirim") -> "Transfer"
                hasField(text, "Jumlah") && hasField(text, "Jam,Tanggal") &&
                    labeledValue(text, listOf("Status Transaksi")).equals("Berhasil", ignoreCase = true) -> "Expense"
                else -> return null
            }
        }

        val amountLabels = when (bank) {
            Bank.BCA -> when {
                bcaFlazzTopUp -> listOf("Top Up Amount")
                bcaCashWithdrawal || bcaBankTransfer -> listOf("Transfer Amount", "Amount")
                else -> listOf("Total Payment")
            }
            Bank.MANDIRI -> listOf("Total Transaksi", "Nominal Transaksi", "Nominal Top-up", "Nominal")
            Bank.JAGO, Bank.UOB -> listOf("Jumlah")
        }
        val amountText = amountLabels.firstNotNullOfOrNull { labeledAmount(text, it) } ?: return null
        val amount = parseIdrAmount(amountText) ?: return null
        val date = transactionDate(text) ?: return null

        val merchant = when (bank) {
            Bank.BCA -> when {
                bcaFlazzTopUp -> "Flazz top-up"
                bcaVirtualAccount -> bcaVirtualAccountProduct?.substringAfterLast('/')?.trim()
                bcaLifestyle -> labeledValue(text, listOf("Partner Name"))
                bcaCashWithdrawal -> "Cash withdrawal"
                bcaBankTransfer -> labeledValue(text, listOf("Beneficiary Name"))?.let { "Transfer to $it" }
                else -> labeledValue(text, listOf("Payment to"))
            }
            Bank.MANDIRI -> when {
                recordType == "Transfer" -> labeledValue(text, listOf("Penerima"))
                    ?.replace(Regex("(?i)\\s+Bank\\s+.*$"), "")?.let { "Transfer to $it" }
                lower.contains("top-up") -> labeledValue(text, listOf("Penyedia Jasa")) ?: labeledValue(text, listOf("Penerima"))
                else -> labeledValue(text, listOf("Penerima"))
            }
            Bank.JAGO -> if (recordType == "Transfer") {
                labeledValue(text, listOf("Ke"))?.let { "Transfer to $it" }
            } else null
            Bank.UOB -> when {
                hasField(text, "Dikirimkan pada") -> labeledValue(text, listOf("Untuk"))?.let { "Transfer to $it" }
                hasField(text, "Diterima pada") -> labeledValue(text, listOf("Bank pengirim"))?.let { "Transfer from $it" }
                else -> labeledValue(text, listOf("Nama Perusahaan"))
            }
        }?.let(::cleanMerchant)?.takeIf { it.isNotBlank() } ?: if (recordType == "Transfer") "Bank transfer" else return null

        val category = if (recordType == "Transfer") "Transfer" else categoryFor(merchant)
        return Expense(merchant = merchant, amount = amount, date = date, category = category, bank = bank.label, recordType = recordType)
    }

    /** Jago card and fee alerts have an amount but no transaction date in the email body. */
    fun parseJagoAlertWithReceivedDate(raw: String, suppliedSender: String, receivedAtMillis: Long?): Expense? {
        val senderAddress = Regex("(?i)[\\w.+-]+@[\\w.-]+")
            .find(suppliedSender)?.value?.lowercase(Locale.ROOT)
        if (receivedAtMillis == null || senderAddress != "noreply@jago.com") return null
        val fee = Regex("(?i)\\bkamu telah dikenakan biaya sebesar\\s*Rp\\s*([0-9.,]+)").find(raw)
        val card = Regex("(?i)\\bkamu telah melakukan transaksi sebesar\\s*Rp\\s*([0-9.,]+)\\s+menggunakan kartu debit Jago").find(raw)
        val match = fee ?: card ?: return null
        val amount = parseIdrAmount(match.groupValues[1]) ?: return null
        val date = SimpleDateFormat("d MMM yyyy", Locale("en", "ID")).format(Date(receivedAtMillis))
        return Expense(
            merchant = if (fee != null) "Jago insufficient-balance fee" else "Jago debit card",
            amount = amount,
            date = date,
            category = "Other",
            bank = Bank.JAGO.label,
            recordType = "Expense",
        )
    }

    fun skipReason(raw: String, suppliedSender: String): String {
        val senderAddress = Regex("(?i)[\\w.+-]+@[\\w.-]+")
            .find(suppliedSender)?.value?.lowercase(Locale.ROOT)
        if (senderAddress == "bca@bca.co.id" &&
            labeledValue(raw, listOf("Status")).equals("Failed", ignoreCase = true)) {
            return "Bank marked this transaction as failed"
        }
        if (senderAddress == "noreply_uob@uob.co.id" &&
            (raw.contains("sedang diproses", ignoreCase = true) ||
                (hasField(raw, "Permintaan pada") && raw.contains("saat transaksi berhasil", ignoreCase = true)))) {
            return "Transfer is still processing"
        }
        val possibleTransaction = when (senderAddress) {
            "bca@bca.co.id" -> labeledValue(raw, listOf("Status")).equals("Successful", ignoreCase = true) &&
                hasField(raw, "Transaction Date") && Regex("(?i)\\bIDR\\s*[0-9]").containsMatchIn(raw)
            "noreply_uob@uob.co.id" -> hasField(raw, "Jumlah") &&
                (hasField(raw, "Dikirimkan pada") || hasField(raw, "Diterima pada"))
            "noreply.livin@bankmandiri.co.id" -> raw.contains("berhasil", ignoreCase = true) &&
                (hasField(raw, "Nominal") || hasField(raw, "Total Transaksi"))
            "noreply@jago.com" -> raw.contains("Rp", ignoreCase = true) &&
                Regex("(?i)transaksi|dikenakan biaya|dikembalikan|pengiriman uangmu").containsMatchIn(raw)
            else -> false
        }
        return if (possibleTransaction) "Possible transaction needs review" else "No completed transaction found"
    }

    private fun hasField(text: String, label: String): Boolean =
        Regex("(?i)\\b${Regex.escape(label)}\\s*:?").containsMatchIn(text)

    private fun labeledAmount(text: String, label: String): String? = Regex(
        "(?is)\\b${Regex.escape(label)}\\s*:?\\s*(?:IDR|RP\\.?|RUPIAH)?\\s*([0-9][0-9.,]*)"
    ).find(text)?.groupValues?.getOrNull(1)

    /** Reads a field up to the next known table label, including flattened HTML table text. */
    private fun labeledValue(text: String, labels: List<String>): String? {
        val fieldLabels = "Status|Transaction Date|Transaction Type|Transfer Type|Payment to|Partner Name|Penerima|Merchant(?: Name)?|Merchant Location|Acquirer|Merchant PAN|Terminal ID|Flazz Card Number|Source of Fund|Source Currency|Customer PAN|BCA Virtual Account No\\.?|Company/Product Name|Pay Amount|billdesc|Bill Total|Top Up Amount|Total Payment|Transfer Amount|Beneficiary Account(?: No\\.?)?|Beneficiary Name|Beneficiary Bank|Transfer Currency|Transfer Method|Amount|Fee|Remarks|Description|Total Transaksi|Nominal Transaksi|Nominal Top-up|Nominal|Biaya Transaksi|Biaya|Jam|Tanggal(?: transaksi)?|Jumlah|Dikirimkan pada|Diterima pada|Bank pengirim|Untuk|Dari|Ke|Nama Perusahaan|Nomor/ID Pelanggan|Nomor Referensi[^:]*|Reference No\\.?|Rekening Pendebetan|Status Transaksi"
        for (label in labels) {
            val match = Regex(
                "(?is)\\b${Regex.escape(label)}\\s*:?\\s*(.+?)(?=\\s+(?:$fieldLabels)\\s*:?|[\\r\\n]|$)"
            ).find(text)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrBlank()) return match
        }
        return null
    }

    private fun cleanMerchant(raw: String): String = raw
        .replace(Regex("\\s*[•|]\\s*\\d{4,}.*$"), "")
        .replace(Regex("\\s+\\d{8,}.*$"), "")
        .replace(Regex("\\s{2,}"), " ")
        .trim(' ', ':', '-', '·', '|')
        .take(60)

    private fun parseIdrAmount(raw: String): Long? {
        val value = raw.trim().filter { it.isDigit() || it == '.' || it == ',' }
        if (value.isEmpty()) return null
        val lastSeparator = maxOf(value.lastIndexOf('.'), value.lastIndexOf(','))
        val integerPart = when {
            value.contains('.') && value.contains(',') && value.length - lastSeparator - 1 in 1..2 -> value.substring(0, lastSeparator)
            value.contains('.') && value.contains(',') -> value
            lastSeparator >= 0 && value.length - lastSeparator - 1 == 3 -> value
            lastSeparator >= 0 && value.length - lastSeparator - 1 in 1..2 -> value.substring(0, lastSeparator)
            else -> value
        }
        return integerPart.filter(Char::isDigit).toLongOrNull()?.takeIf { it > 0 }
    }

    private fun transactionDate(text: String): String? {
        val dateField = Regex(
            "(?is)(?:Transaction Date|Tanggal(?:\\s+transaksi)?|Jam\\s*,?\\s*Tanggal|Dikirimkan pada|Diterima pada)\\s*:?\\s*(.{1,100}?)(?=\\s+(?:Transaction Type|Transfer Type|Payment to|Partner Name|Penerima|Merchant Location|Acquirer|Merchant PAN|Terminal ID|Source of Fund|Customer PAN|Total Payment|Transfer Amount|Total Transaksi|Nominal Transaksi|Nominal Top-up|Nominal|Biaya|Jumlah|Untuk|Bank pengirim|Dari|Ke|Nama Perusahaan|Nomor/ID Pelanggan|Nomor Referensi[^:]*|Status Transaksi)\\s*:?|[\\r\\n]|$)"
        ).find(text)?.groupValues?.getOrNull(1) ?: return null
        val dateMatch = Regex("(?i)\\b(\\d{1,2})[-\\s]+([A-Za-zÀ-ÿ]+)[-\\s]+(\\d{4})\\b").find(dateField) ?: return null
        val day = dateMatch.groupValues[1]
        val monthKey = dateMatch.groupValues[2].lowercase(Locale.ROOT).take(3)
        val year = dateMatch.groupValues[3]
        val month = monthNames[monthKey] ?: return null
        return runCatching {
            val parser = SimpleDateFormat("d MMM yyyy", Locale.US).apply { isLenient = false }
            val parsed: Date = parser.parse("$day $month $year") ?: return null
            SimpleDateFormat("d MMM yyyy", Locale("en", "ID")).format(parsed)
        }.getOrNull()
    }

    private val monthNames = mapOf(
        "jan" to "Jan", "feb" to "Feb", "mar" to "Mar", "apr" to "Apr",
        "mei" to "May", "may" to "May", "jun" to "Jun", "jul" to "Jul",
        "agu" to "Aug", "ags" to "Aug", "aug" to "Aug", "sep" to "Sep",
        "okt" to "Oct", "oct" to "Oct", "nov" to "Nov", "des" to "Dec", "dec" to "Dec",
    )

    private fun categoryFor(merchant: String): String {
        val value = merchant.lowercase(Locale.ROOT)
        return when {
            listOf("gojek", "grab", "transjakarta", "mrt", "taxi", "transport").any(value::contains) -> "Transport"
            listOf("alfamart", "indomaret", "supermarket", "grocery").any(value::contains) -> "Groceries"
            listOf("kopi", "cafe", "restaurant", "food", "makan").any(value::contains) -> "Food & drink"
            listOf("tokopedia", "shopee", "shopping", "marketplace").any(value::contains) -> "Shopping"
            listOf("netflix", "spotify", "subscription").any(value::contains) -> "Subscriptions"
            else -> "Other"
        }
    }
}

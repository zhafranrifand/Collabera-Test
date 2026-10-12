package com.expensemail.app

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/** Parses only known, structured transaction receipts. Marketing and account notices are ignored. */
internal object BankAlertParser {
    private enum class Bank(val label: String) {
        BCA("BCA"), MANDIRI("Livin’ by Mandiri"), JAGO("Bank Jago"), UOB("UOB"), BNI("BNI"), OCBC("OCBC")
    }

    private val senderBanks = mapOf(
        "bca@bca.co.id" to Bank.BCA,
        "noreply.livin@bankmandiri.co.id" to Bank.MANDIRI,
        "noreply@jago.com" to Bank.JAGO,
        "noreply_uob@uob.co.id" to Bank.UOB,
        "wondr@bni.co.id" to Bank.BNI,
        "notifikasi@ocbc.id" to Bank.OCBC,
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
        val bcaMobileData = bcaTransactionType?.startsWith("Mobile Data", ignoreCase = true) == true
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
                    bcaMobileData && hasField(text, "Amount") && hasField(text, "Total Payment") -> "Expense"
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
                    hasField(text, "Tanggal transaksi") && isSuccessfulJagoTransfer(text) -> "Transfer"
                else -> return null
            }
            Bank.UOB -> when {
                hasField(text, "Jumlah") && hasField(text, "Dikirimkan pada") && hasField(text, "Untuk") -> "Transfer"
                hasField(text, "Jumlah") && hasField(text, "Diterima pada") && hasField(text, "Bank pengirim") -> "Transfer"
                hasField(text, "Jumlah") && (hasField(text, "Tanggal Waktu Transaksi") || hasField(text, "Jam,Tanggal")) &&
                    isSuccessfulUobQr(labeledValue(text, listOf("Status Transaksi"))) -> "Expense"
                else -> return null
            }
            Bank.BNI -> when {
                lower.contains("transfer berhasil") && hasField(text, "Penerima") &&
                    hasField(text, "Tanggal") && hasField(text, "Nominal") -> "Transfer"
                lower.contains("top-up berhasil") && hasField(text, "Penerima") &&
                    hasField(text, "Tanggal") && hasField(text, "Nominal") && hasField(text, "Total") -> "Expense"
                else -> return null
            }
            Bank.OCBC -> when {
                lower.contains("funds transfer request has been successful") &&
                    hasField(text, "TRANSFER DATE") && hasField(text, "AMOUNT") && ocbcLabeledValue(text, "TO") != null -> "Transfer"
                lower.contains("qr payment request has been successful") &&
                    hasField(text, "Payment Date") && hasField(text, "Amount Pay") && ocbcQrMerchant(text) != null -> "Expense"
                lower.contains("request for bill payment has been successful") &&
                    hasField(text, "PAYMENT DATE") && hasField(text, "PAYMENT AMOUNT") && ocbcLabeledValue(text, "TO") != null -> "Expense"
                else -> return null
            }
        }

        val amountLabels = when (bank) {
            Bank.BCA -> when {
                bcaFlazzTopUp -> listOf("Top Up Amount")
                bcaMobileData -> listOf("Total Payment")
                bcaCashWithdrawal || bcaBankTransfer -> listOf("Transfer Amount", "Amount")
                else -> listOf("Total Payment")
            }
            Bank.MANDIRI -> listOf("Total Transaksi", "Nominal Transaksi", "Nominal Top-up", "Nominal")
            Bank.JAGO, Bank.UOB -> listOf("Jumlah")
            Bank.BNI -> if (recordType == "Expense") listOf("Total", "Nominal") else listOf("Nominal")
            Bank.OCBC -> when {
                text.contains("QR Payment", ignoreCase = true) -> listOf("Amount Pay")
                text.contains("bill payment", ignoreCase = true) -> listOf("PAYMENT AMOUNT")
                else -> listOf("AMOUNT")
            }
        }
        val amountText = amountLabels.firstNotNullOfOrNull { labeledAmount(text, it) } ?: return null
        val amount = parseIdrAmount(amountText) ?: return null
        val date = transactionDate(text) ?: return null

        val merchant = when (bank) {
            Bank.BCA -> when {
                bcaFlazzTopUp -> "Flazz top-up"
                bcaMobileData -> bcaTransactionType
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
                val recipient = labeledValue(text, listOf("Ke"))
                val isRdnWithdrawal = Regex("(?i)penarikan\\s+dana\\s+dari\\s+kantong\\s+RDN").containsMatchIn(text)
                when {
                    recipient.isNullOrBlank() && isRdnWithdrawal -> "RDN pocket withdrawal"
                    recipient.isNullOrBlank() -> "Bank transfer"
                    recipient.trim().matches(Regex("[0-9*Xx -]+")) ->
                        if (isRdnWithdrawal) "RDN pocket withdrawal" else "Transfer to account"
                    else -> "Transfer to ${maskRecipientAccountSuffix(recipient)}"
                }
            } else null
            Bank.UOB -> when {
                hasField(text, "Dikirimkan pada") -> labeledValue(text, listOf("Untuk"))?.let { "Transfer to $it" }
                hasField(text, "Diterima pada") -> labeledValue(text, listOf("Bank pengirim"))?.let { "Transfer from $it" }
                else -> labeledValue(text, listOf("Nama Merchant", "Nama Perusahaan"))
            }
            Bank.BNI -> {
                val recipient = labeledValue(text, listOf("Penerima"))
                if (recordType == "Transfer") recipient?.let { "Transfer to ${maskRecipientAccountSuffix(it)}" }
                else recipient?.lineSequence()?.firstOrNull()?.trim()
            }
            Bank.OCBC -> when {
                text.contains("QR Payment", ignoreCase = true) -> ocbcQrMerchant(text)
                else -> ocbcLabeledValue(text, "TO")?.let { recipient ->
                    val cleaned = maskRecipientAccountSuffix(recipient)
                    if (recordType == "Transfer") "Transfer to $cleaned" else cleaned
                }
            }
        }?.let(::cleanMerchant)?.takeIf { it.isNotBlank() } ?: if (recordType == "Transfer") "Bank transfer" else return null
        // A malformed merchant is a parse failure, not an expense we should silently
        // count under Other. Gmail's flattened HTML can surface footer phone numbers,
        // CSS fragments, or adjacent bank-table labels in place of the merchant.
        if (recordType == "Expense" && !isCredibleMerchant(merchant)) return null

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
            "noreply_uob@uob.co.id" ->
                (hasField(raw, "Jumlah") &&
                    (hasField(raw, "Dikirimkan pada") || hasField(raw, "Diterima pada"))) ||
                    (hasField(raw, "Jumlah") &&
                        (hasField(raw, "Tanggal Waktu Transaksi") || hasField(raw, "Jam,Tanggal")) &&
                        isSuccessfulUobQr(labeledValue(raw, listOf("Status Transaksi"))))
            "noreply.livin@bankmandiri.co.id" -> raw.contains("berhasil", ignoreCase = true) &&
                (hasField(raw, "Nominal") || hasField(raw, "Total Transaksi"))
            "noreply@jago.com" -> raw.contains("Rp", ignoreCase = true) &&
                Regex("(?i)transaksi|dikenakan biaya|dikembalikan|pengiriman uangmu").containsMatchIn(raw)
            "wondr@bni.co.id" -> raw.contains("Rp", ignoreCase = true) &&
                Regex("(?i)transfer|top-up|transaksi|tarik tunai|pembayaran").containsMatchIn(raw)
            "notifikasi@ocbc.id" -> raw.contains("IDR", ignoreCase = true) &&
                Regex("(?i)transfer|payment|pembayaran|transaction|QR").containsMatchIn(raw)
            else -> false
        }
        return if (possibleTransaction) "Possible transaction needs review" else "No completed transaction found"
    }

    private fun hasField(text: String, label: String): Boolean =
        Regex("(?i)\\b${Regex.escape(label)}\\s*:?").containsMatchIn(text)

    private fun isSuccessfulUobQr(status: String?): Boolean =
        status?.trim()?.matches(Regex("(?i)(?:Pembayaran\\s+QR\\s*[|/]\\s*)?Berhasil")) == true

    private fun isSuccessfulJagoTransfer(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val explicitStatus = labeledValue(text, listOf("Status Transaksi", "Status"))
        if (!explicitStatus.isNullOrBlank()) {
            return explicitStatus.matches(Regex("(?i)\\s*(?:berhasil|successful|success)\\s*"))
        }
        if (Regex("(?i)\\b(gagal|dibatalkan|ditolak|tidak berhasil|belum berhasil|failed|cancelled|canceled|declined|pending)\\b|sedang\\s+diproses")
                .containsMatchIn(lower)) return false
        // Some genuine Jago receipts confirm the completed transfer through their
        // structured transaction summary without a separate "berhasil" status line.
        return true
    }

    /** Keep a recipient name when present, but never expose a full destination account number. */
    private fun maskRecipientAccountSuffix(value: String): String {
        val normalized = value.trim().replace(Regex("\\s+"), " ")
        val withoutBankAccount = normalized.replace(
            Regex("(?i)\\s*(?:-\\s*)?(?:Bank\\s+)?(?:Mandiri|BCA|BNI|BRI|BTPN|Jago|UOB)\\s*[•|]\\s*[0-9*Xx -]+$"),
            "",
        ).trim()
        val suffix = Regex("(?:[\\s•*-]*\\d{2,4}){2,}$").find(withoutBankAccount) ?: return withoutBankAccount
        val accountDigits = suffix.value.filter(Char::isDigit)
        if (accountDigits.length < 8) return withoutBankAccount
        val name = withoutBankAccount.substring(0, suffix.range.first).trim().trimEnd('•', '-', '*')
        return name.ifBlank { "account" }
    }

    /** OCBC's HTML receipts render TO as a standalone label followed by its value. */
    private fun ocbcLabeledValue(text: String, label: String): String? {
        val lines = text.lines().map { it.trim() }.filter(String::isNotBlank)
        val index = lines.indexOfFirst {
            it.equals(label, ignoreCase = true) || it.startsWith("$label ", ignoreCase = true)
        }
        if (index < 0) return null
        val current = lines[index]
        val inline = current.substring(label.length).trim().trimStart(':')
        val value = inline.ifBlank { lines.drop(index + 1).firstOrNull().orEmpty() }
        return value.takeWhile { it != '\n' }.trim().takeIf(String::isNotBlank)
    }

    /** OCBC QR receipts place the merchant on the first named row following Merchant PAN. */
    private fun ocbcQrMerchant(text: String): String? {
        val lines = text.lines().map { it.trim() }.filter(String::isNotBlank)
        val explicitLabel = lines.indexOfFirst {
            it.startsWith("Nama Merchant", ignoreCase = true) || it.startsWith("Merchant Name", ignoreCase = true)
        }
        if (explicitLabel >= 0) {
            val line = lines[explicitLabel]
            val label = if (line.startsWith("Nama Merchant", ignoreCase = true)) "Nama Merchant" else "Merchant Name"
            val explicitValue = line.substring(label.length).trim().trimStart(':').ifBlank {
                lines.drop(explicitLabel + 1).firstOrNull().orEmpty()
            }
            if (explicitValue.isNotBlank()) return explicitValue.trim()
        }
        val index = lines.indexOfFirst { it.startsWith("Merchant PAN", ignoreCase = true) }
        if (index < 0) return null
        val candidate = lines.drop(index + 1).firstOrNull { line ->
            line.any(Char::isLetter) &&
                !Regex("(?i)^(?:Nama Bank|Sumber Dana|Acquirer|Terminal No\\.|Merchant PAN|Reference No\\.?|Reff No\\.?|Amount Pay|Tip IDR|Payment Date)").containsMatchIn(line) &&
                !Regex("(?i)^(?:Bank )?(?:BCA|BNI|BRI|Mandiri|Jago|UOB|OCBC)\\b").containsMatchIn(line)
        } ?: return null
        return candidate.replace(Regex("(?i)\\s+[A-Z][A-Z ]+,\\s*\\d.*$"), "")
            .trim().takeIf(String::isNotBlank)
    }

    private fun labeledAmount(text: String, label: String): String? = Regex(
        "(?is)\\b${Regex.escape(label)}\\s*:?\\s*(?:IDR|RP\\.?|RUPIAH)?\\s*([0-9][0-9.,]*)"
    ).find(text)?.groupValues?.getOrNull(1)

    /** Reads a field up to the next known table label, including flattened HTML table text. */
    private fun labeledValue(text: String, labels: List<String>): String? {
        val fieldLabels = "Status|Transaction Date|Transaction Type|Transfer Type|Payment to|Partner Name|Penerima|Merchant(?: Name)?|Nama Merchant|Merchant Location|Acquirer|Merchant PAN|Terminal ID|Flazz Card Number|Source of Fund|Source Currency|Customer PAN|BCA Virtual Account No\\.?|Company/Product Name|Pay Amount|billdesc|Bill Total|Top Up Amount|Total Payment|Transfer Amount|Beneficiary Account(?: No\\.?)?|Beneficiary Name|Beneficiary Bank|Transfer Currency|Transfer Method|Amount|Fee|Remarks|Description|Total Transaksi|Nominal Transaksi|Nominal Top-up|Nominal|Biaya Transaksi|Biaya|Jam|Tanggal Waktu Transaksi|Tanggal(?: transaksi)?|Jumlah|Dikirimkan pada|Diterima pada|Bank pengirim|Untuk|Dari|Ke|Nama Perusahaan|Nomor/ID Pelanggan|Nomor Referensi[^:]*|Reference No\\.?|Rekening Pendebetan|Status Transaksi"
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

    internal fun isCredibleMerchant(value: String): Boolean {
        val merchant = value.trim()
        if (merchant.length !in 2..60 || merchant.none(Char::isLetter)) return false
        val flattenedEmailArtifacts = listOf(
            "!important", "max-width", "font-size", "font-family", "background-color",
            "accountsource", "source of fund", "customer pan", "terminal id",
            "transaction date", "total payment", "merchant location", "reference no",
            "transaction successful", "please save this email", "do not recognize this transaction",
        )
        if (flattenedEmailArtifacts.any { merchant.contains(it, ignoreCase = true) }) return false
        // Gmail's HTML-to-text conversion may expose CSS declarations as merchant text.
        // Check common CSS property names only when they look like declarations; a real
        // merchant name containing the letters "css" remains valid.
        val cssProperty = "(?:align-content|align-items|align-self|background(?:-[\\w-]+)?|border(?:-[\\w-]+)?|box-sizing|clear|color|display|float|font(?:-[\\w-]+)?|height|letter-spacing|line-height|margin(?:-[\\w-]+)?|max-(?:height|width)|min-(?:height|width)|opacity|overflow(?:-[\\w-]+)?|padding(?:-[\\w-]+)?|position|text-align|text-decoration|text-transform|vertical-align|white-space|width)"
        if (Regex("(?i)(?:^|[;{}])\\s*$cssProperty\\s*:").containsMatchIn(merchant)) return false
        // A common flattened BCA footer can be joined to adjacent text by Gmail's HTML parser.
        if (Regex("(?i)\\b(?:halo\\s+)?bca\\b.{0,24}\\b1500888\\b").containsMatchIn(merchant)) return false
        return true
    }

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
            "(?is)(?:TRANSFER DATE|PAYMENT DATE|Transaction Date|Tanggal Waktu Transaksi|Tanggal(?:\\s+transaksi)?|Jam\\s*,?\\s*Tanggal|Dikirimkan pada|Diterima pada)\\s*:?\\s*(.{1,100}?)(?=\\s+(?:Transaction Type|Transfer Type|Payment to|Partner Name|Penerima|Merchant Location|Acquirer|Merchant PAN|Terminal ID|Source of Fund|Customer PAN|Total Payment|Transfer Amount|Total Transaksi|Nominal Transaksi|Nominal Top-up|Nominal|Biaya|Jumlah|Untuk|Bank pengirim|Dari|Ke|Nama Merchant|Nama Perusahaan|Nomor/ID Pelanggan|Nomor Referensi[^:]*|Status Transaksi)\\s*:?|[\\r\\n]|$)"
        ).find(text)?.groupValues?.getOrNull(1) ?: return null
        val numericDate = Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b").find(dateField)
        if (numericDate != null) {
            // OCBC's dated receipts are day/month/year (confirmed against the email date).
            return runCatching {
                val parser = SimpleDateFormat("d/M/yyyy", Locale.US).apply { isLenient = false }
                val parsed = parser.parse("${numericDate.groupValues[1]}/${numericDate.groupValues[2]}/${numericDate.groupValues[3]}")
                    ?: return null
                SimpleDateFormat("d MMM yyyy", Locale.US).format(parsed)
            }.getOrNull()
        }
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
        val value = normalizeMerchant(merchant)
        return when {
            // "Klinik Ban" is a tyre shop name; keep the automotive qualifier together
            // so the generic "klinik" health alias does not misclassify it.
            matchesMerchant(value, "klinik ban", "ban bright", "olimart", "tire shop", "tyre shop", "ban motor", "ban mobil") -> "Transport"
            matchesMerchant(value, "clinic", "klinik", "hospital", "apotek", "pharmacy", "dentist", "fit hub", "gym", "salon", "barber shop", "skin care", "health") -> "Health & personal care"
            matchesMerchant(value, "mobile data", "paket data", "pulsa", "telkomsel", "axis", "indosat", "internet", "wifi", "pln", "listrik", "electricity", "token listrik") -> "Bills & utilities"
            matchesMerchant(value, "gojek", "grab", "transjakarta", "mrt", "taxi", "transport", "bluebird", "blue bird", "spbu", "shell", "pertamina", "valet parking", "car wash", "carwash", "fast nitrogen", "kci c access", "flazz top up") -> "Transport"
            matchesMerchant(value, "alfamart", "indomaret", "idm", "circle k", "supermarket", "grocery", "groceries") -> "Groceries"
            matchesMerchant(value, "kopi", "cafe", "restaurant", "restoran", "food", "makan", "warung", "nasi", "soto", "bakso", "mie ayam", "udders", "maguro", "tuku", "izakaya", "esb restaurant", "sour sally", "nannys pavillon", "owl kota kasablanka", "marugame udon", "ramen seirock", "shihlin", "es doger") -> "Food & drink"
            matchesMerchant(value, "tokopedia", "shopee", "shopping", "marketplace", "spacewear", "kinokuniya") -> "Shopping"
            matchesMerchant(value, "xxi", "photobooth", "jicaf", "loket", "cinema", "theater", "theatre", "ticket", "event", "radio") -> "Entertainment"
            matchesMerchant(value, "netflix", "spotify", "subscription") -> "Subscriptions"
            else -> "Other"
        }
    }

    private fun normalizeMerchant(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun matchesMerchant(normalizedMerchant: String, vararg aliases: String): Boolean = aliases.any { alias ->
        val normalizedAlias = normalizeMerchant(alias)
        " $normalizedMerchant ".contains(" $normalizedAlias ")
    }
}

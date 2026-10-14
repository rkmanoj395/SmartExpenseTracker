package com.example.smartexpensetracker.ai

import com.example.smartexpensetracker.data.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class ParsedTransactionResult(
    val isTransaction: Boolean,
    val amount: Double,
    val type: TransactionType,
    val merchantOrPayee: String,
    val category: String,
    val isCategoryFetchable: Boolean,
    val reportedBankBalance: Double? = null,
    val reportedCreditCardDue: Double? = null
)

object GeminiTransactionAnalyzer {

    val STANDARD_CATEGORIES = listOf(
        "Food & Dining",
        "Groceries",
        "Shopping",
        "Transport & Fuel",
        "Bills & Utilities",
        "Health & Medical",
        "Entertainment",
        "Travel",
        "Rent & Housing",
        "Investments",
        "Salary & Income",
        "Credit Card Payment",
        "Uncategorized"
    )

    // Pre-filter so non-financial chat messages are never sent to AI
    private val FINANCIAL_KEYWORDS = listOf(
        "rs", "inr", "₹", "debited", "credited", "spent", "paid", "sent",
        "received", "upi", "a/c", "acct", "card", "avl bal", "available bal",
        "total due", "min due", "statement", "txn", "trf", "autodebit"
    )

    fun looksLikeFinancialAlert(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val hasKeyword = FINANCIAL_KEYWORDS.any { lower.contains(it) }
        val hasNumber = Regex("\\d+").containsMatchIn(lower)
        // Ignore OTP-only messages
        val isOtpOnly = (lower.contains("otp") || lower.contains("one time password") || lower.contains("verification code")) &&
                !lower.contains("debited") && !lower.contains("spent") && !lower.contains("credited")
        return hasKeyword && hasNumber && !isOtpOnly
    }

    /**
     * Scrubs sensitive account/card numbers and phone numbers before sending to Gemini API.
     */
    fun scrubSensitiveNumbers(rawText: String): String {
        return rawText
            // Mask 9-16 digit account/card/reference numbers not preceded by currency symbols
            .replace(Regex("(?<!Rs\\.?\\s?)(?<!INR\\s?)(?<!₹\\s?)\\b\\d{9,16}\\b"), "[REDACTED_ID]")
            // Mask XX1234 or *1234 patterns
            .replace(Regex("[Xx\\*]{2,}\\d{3,4}"), "[ACCOUNT_MASK]")
    }

    suspend fun analyzeNotification(
        rawText: String,
        packageName: String,
        apiKey: String
    ): ParsedTransactionResult? = withContext(Dispatchers.IO) {
        if (!looksLikeFinancialAlert(rawText)) return@withContext null

        val scrubbed = scrubSensitiveNumbers(rawText)

        if (apiKey.isNotBlank()) {
            try {
                val aiResult = callGeminiApi(scrubbed, packageName, apiKey)
                if (aiResult != null) return@withContext aiResult
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback to local regex + merchant dictionary if offline or no API key configured
        return@withContext parseLocally(rawText)
    }

    private fun callGeminiApi(
        scrubbedText: String,
        packageName: String,
        apiKey: String
    ): ParsedTransactionResult? {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 10000
            readTimeout = 10000
            doOutput = true
        }

        val prompt = """
            You are an Indian financial transaction parser. Analyze this notification from package "$packageName":
            Notification text: "$scrubbedText"

            Return strictly a JSON object with these exact keys:
            - "isTransaction": boolean (true if this represents a real money debit, credit, credit card spend, or credit card payment; false if promotional spam or failed txn)
            - "amount": number (transaction amount in INR, 0 if none)
            - "type": string, one of ["DEBIT", "CREDIT", "CC_SPEND", "CC_PAYMENT"]
            - "merchantOrPayee": string (clean merchant or recipient name, or "Unknown")
            - "category": string, one of ${STANDARD_CATEGORIES.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}
            - "isCategoryFetchable": boolean (true ONLY if the merchant clearly implies the category, such as Swiggy -> Food & Dining, Uber -> Transport & Fuel, BigBasket -> Groceries, Bescom/Airtel -> Bills & Utilities. Set to FALSE and category to "Uncategorized" if the payee is a person's name, an ambiguous VPA/UPI ID, or a generic bank transfer where the spending category cannot be known without asking the user.)
            - "reportedBankBalance": number or null (if the alert mentions Available Balance / Avl Bal in bank account, extract the number; otherwise null)
            - "reportedCreditCardDue": number or null (if the alert explicitly mentions Total Credit Card Due / Statement Due, extract the number; otherwise null)
        """.trimIndent()

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.1)
            })
        }

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(requestJson.toString())
            writer.flush()
        }

        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            return null
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(responseText)
        val contentText = root.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")

        val parsed = JSONObject(contentText)
        val isTxn = parsed.optBoolean("isTransaction", false)
        if (!isTxn) return null

        val amount = parsed.optDouble("amount", 0.0)
        if (amount <= 0.0) return null

        val typeStr = parsed.optString("type", "DEBIT")
        val txnType = runCatching { TransactionType.valueOf(typeStr) }.getOrDefault(TransactionType.DEBIT)
        val merchant = parsed.optString("merchantOrPayee", "Unknown").ifBlank { "Unknown" }
        val isFetchable = parsed.optBoolean("isCategoryFetchable", false)
        val rawCategory = parsed.optString("category", "Uncategorized")
        val finalCategory = if (isFetchable && rawCategory != "Uncategorized") rawCategory else "Uncategorized"

        val bankBal = if (parsed.isNull("reportedBankBalance")) null else parsed.optDouble("reportedBankBalance")
        val ccDue = if (parsed.isNull("reportedCreditCardDue")) null else parsed.optDouble("reportedCreditCardDue")

        return ParsedTransactionResult(
            isTransaction = true,
            amount = amount,
            type = txnType,
            merchantOrPayee = merchant,
            category = finalCategory,
            isCategoryFetchable = isFetchable && finalCategory != "Uncategorized",
            reportedBankBalance = bankBal?.takeIf { !it.isNaN() },
            reportedCreditCardDue = ccDue?.takeIf { !it.isNaN() }
        )
    }

    /**
     * Local fallback parser for offline usage or when Gemini API key is not yet set.
     */
    fun parseLocally(rawText: String): ParsedTransactionResult? {
        val lower = rawText.lowercase(Locale.ROOT)
        if (lower.contains("failed") || lower.contains("declined") || lower.contains("request")) {
            return null
        }

        val amountRegex = Regex("(?:rs\\.?|inr|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
        val match = amountRegex.find(rawText) ?: return null
        val amount = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        if (amount <= 0.0) return null

        // Check for available balance in SMS
        val balRegex = Regex("(?:avl\\s*bal|available\\s*bal(?:ance)?|bal)\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
        val reportedBal = balRegex.find(rawText)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

        val isCreditCard = lower.contains("credit card") || lower.contains("cc ") ||
                (lower.contains("card") && !lower.contains("debit card") && (lower.contains("spent") || lower.contains("limit")))

        val type = when {
            isCreditCard && (lower.contains("payment") || lower.contains("received") || lower.contains("credited")) -> TransactionType.CC_PAYMENT
            isCreditCard -> TransactionType.CC_SPEND
            lower.contains("credited") || lower.contains("received") || lower.contains("refund") -> TransactionType.CREDIT
            else -> TransactionType.DEBIT
        }

        // Extract merchant after "to", "at", "towards", "info:"
        val merchantRegex = Regex("(?:\\bto\\b|\\bat\\b|\\btowards\\b|\\bfor\\b|vpa\\s+)\\s*([A-Za-z0-9@._\\-\\s]{2,30}?)(?:\\s+on\\b|\\s+ref\\b|\\s+upi\\b|\\.|,|$)", RegexOption.IGNORE_CASE)
        val merchant = merchantRegex.find(rawText)?.groupValues?.get(1)?.trim() ?: "Unknown Payee"

        val knownMerchants = mapOf(
            "swiggy" to "Food & Dining",
            "zomato" to "Food & Dining",
            "starbucks" to "Food & Dining",
            "dominos" to "Food & Dining",
            "mcdonald" to "Food & Dining",
            "bigbasket" to "Groceries",
            "blinkit" to "Groceries",
            "zepto" to "Groceries",
            "instamart" to "Groceries",
            "dmart" to "Groceries",
            "amazon" to "Shopping",
            "flipkart" to "Shopping",
            "myntra" to "Shopping",
            "ajio" to "Shopping",
            "uber" to "Transport & Fuel",
            "ola" to "Transport & Fuel",
            "rapido" to "Transport & Fuel",
            "irctc" to "Travel",
            "makemytrip" to "Travel",
            "indigo" to "Travel",
            "shell" to "Transport & Fuel",
            "hpcl" to "Transport & Fuel",
            "bpcl" to "Transport & Fuel",
            "iocl" to "Transport & Fuel",
            "airtel" to "Bills & Utilities",
            "jio" to "Bills & Utilities",
            "bescom" to "Bills & Utilities",
            "tneb" to "Bills & Utilities",
            "tangedco" to "Bills & Utilities",
            "netflix" to "Entertainment",
            "spotify" to "Entertainment",
            "bookmyshow" to "Entertainment",
            "apollo" to "Health & Medical",
            "pharmeasy" to "Health & Medical",
            "1mg" to "Health & Medical"
        )

        val matchedCategory = knownMerchants.entries.firstOrNull { (k, _) ->
            lower.contains(k) || merchant.lowercase(Locale.ROOT).contains(k)
        }?.value

        val isFetchable = matchedCategory != null
        return ParsedTransactionResult(
            isTransaction = true,
            amount = amount,
            type = type,
            merchantOrPayee = merchant,
            category = matchedCategory ?: "Uncategorized",
            isCategoryFetchable = isFetchable,
            reportedBankBalance = reportedBal,
            reportedCreditCardDue = null
        )
    }
}

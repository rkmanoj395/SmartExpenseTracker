package com.example.smartexpensetracker.data

import android.content.Context
import com.example.smartexpensetracker.ai.GeminiTransactionAnalyzer
import com.example.smartexpensetracker.widget.FinanceSummaryWidget
import java.security.MessageDigest
import kotlin.math.max

object TransactionRepository {

    suspend fun processIncomingAlert(
        context: Context,
        rawText: String,
        sourcePackage: String,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        val apiKey = AppPreferences.getGeminiApiKey(context)
        val parsed = GeminiTransactionAnalyzer.analyzeNotification(
            rawText = rawText,
            packageName = sourcePackage,
            apiKey = apiKey
        ) ?: return

        // Create a 2-minute time bucket + amount + type signature so duplicate SMS + UPI app alerts
        // for the exact same spend within 2 minutes are deduplicated automatically
        val twoMinBucket = timestampMillis / (120_000L)
        val dedupSignature = sha256("${parsed.amount}_${parsed.type}_$twoMinBucket")

        val db = AppDatabase.getInstance(context)
        val dao = db.transactionDao()

        val entity = TransactionEntity(
            amount = parsed.amount,
            type = parsed.type,
            merchantOrPayee = parsed.merchantOrPayee,
            category = parsed.category,
            isCategoryFetchable = parsed.isCategoryFetchable,
            sourcePackage = sourcePackage,
            rawNotificationText = rawText.take(500),
            timestamp = timestampMillis,
            dedupSignature = dedupSignature
        )

        val insertedRowId = dao.insertTransaction(entity)
        if (insertedRowId == -1L) {
            // Already recorded via another app/SMS notification
            return
        }

        // Update Cumulative Bank Balance & Credit Card Due
        val currentSummary = dao.getAccountSummarySnapshot() ?: AccountSummaryEntity()
        var newBankBalance = currentSummary.cumulativeBankBalance
        var newCreditCardDue = currentSummary.creditCardDue

        when (parsed.type) {
            TransactionType.DEBIT -> {
                newBankBalance = parsed.reportedBankBalance ?: (newBankBalance - parsed.amount)
            }
            TransactionType.CREDIT -> {
                newBankBalance = parsed.reportedBankBalance ?: (newBankBalance + parsed.amount)
            }
            TransactionType.CC_SPEND -> {
                newCreditCardDue = parsed.reportedCreditCardDue ?: (newCreditCardDue + parsed.amount)
            }
            TransactionType.CC_PAYMENT -> {
                newCreditCardDue = parsed.reportedCreditCardDue ?: max(0.0, newCreditCardDue - parsed.amount)
                newBankBalance = parsed.reportedBankBalance ?: (newBankBalance - parsed.amount)
            }
        }

        dao.saveAccountSummary(
            AccountSummaryEntity(
                id = 1,
                cumulativeBankBalance = newBankBalance,
                creditCardDue = newCreditCardDue,
                lastUpdated = System.currentTimeMillis()
            )
        )

        // Immediately refresh Homescreen Widget
        FinanceSummaryWidget.updateAllWidgets(context)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

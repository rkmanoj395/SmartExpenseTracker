package com.example.smartexpensetracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TransactionType {
    DEBIT,          // Bank or UPI debit
    CREDIT,         // Bank or UPI credit / refund / salary
    CC_SPEND,       // Credit Card purchase
    CC_PAYMENT      // Credit Card bill payment
}

@Entity(
    tableName = "transactions",
    indices = [Index(value = ["dedupSignature"], unique = true)]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val type: TransactionType,
    val merchantOrPayee: String,
    val category: String,              // e.g., "Groceries", "Dining", "Uncategorized"
    val isCategoryFetchable: Boolean,  // false when AI cannot determine category automatically
    val sourcePackage: String,         // e.g., "com.google.android.apps.nbu.paisa.user" or "SMS"
    val rawNotificationText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val dedupSignature: String         // Prevents duplicate capture across SMS + UPI app notification
)

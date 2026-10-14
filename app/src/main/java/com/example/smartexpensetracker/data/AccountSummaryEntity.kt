package com.example.smartexpensetracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "account_summary")
data class AccountSummaryEntity(
    @PrimaryKey val id: Int = 1,
    val cumulativeBankBalance: Double = 0.0,
    val creditCardDue: Double = 0.0,
    val lastUpdated: Long = System.currentTimeMillis()
)

package com.example.smartexpensetracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE isCategoryFetchable = 0 OR category = 'Uncategorized' ORDER BY timestamp DESC")
    fun observeUncategorizedTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE isCategoryFetchable = 0 OR category = 'Uncategorized' ORDER BY timestamp DESC")
    suspend fun getUncategorizedTransactionsSnapshot(): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE isCategoryFetchable = 0 OR category = 'Uncategorized'")
    suspend fun getUncategorizedCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Query("UPDATE transactions SET category = :newCategory, isCategoryFetchable = 1 WHERE id = :transactionId")
    suspend fun updateCategoryManually(transactionId: Long, newCategory: String)

    @Query("SELECT * FROM account_summary WHERE id = 1")
    fun observeAccountSummary(): Flow<AccountSummaryEntity?>

    @Query("SELECT * FROM account_summary WHERE id = 1")
    suspend fun getAccountSummarySnapshot(): AccountSummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAccountSummary(summary: AccountSummaryEntity)
}

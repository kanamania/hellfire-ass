package com.hellfire.ass.finance

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Monthly spending limit for one category (v11). Used by the Budget widget; never changes the ledger. */
@Entity(tableName = "category_budget", primaryKeys = ["category", "month"])
data class CategoryBudget(val category: String, /** yyyy-MM, East Africa Time. */ val month: String, val amount: Long)

/** Last balance stated in a provider SMS (v11). Shown on the Wallets widget as "as of" information only. */
@Entity(tableName = "provider_balance")
data class ProviderBalance(@PrimaryKey val provider: String, val amount: Long, val observedAt: Long, val sourceId: String)

@Dao
interface WidgetDataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun budget(value: CategoryBudget)
    @Query("DELETE FROM category_budget WHERE category = :category AND month = :month") suspend fun clearBudget(category: String, month: String)
    @Query("SELECT * FROM category_budget WHERE month = :month ORDER BY category") fun budgets(month: String): Flow<List<CategoryBudget>>
    @Query("SELECT * FROM category_budget WHERE month = :month ORDER BY category") suspend fun budgetsFor(month: String): List<CategoryBudget>
    @Query("SELECT * FROM provider_balance ORDER BY provider") suspend fun balances(): List<ProviderBalance>
    @Query("SELECT * FROM provider_balance WHERE provider = :provider") suspend fun balance(provider: String): ProviderBalance?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBalance(value: ProviderBalance)
    /** Keeps only the newest statement per provider; older or replayed SMS never overwrite a newer balance. */
    @Transaction suspend fun observeBalance(value: ProviderBalance) {
        val current = balance(value.provider)
        if (current == null || value.observedAt >= current.observedAt) putBalance(value)
    }
}

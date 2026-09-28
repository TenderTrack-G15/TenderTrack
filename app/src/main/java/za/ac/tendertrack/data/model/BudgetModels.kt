package za.ac.tendertrack.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One department's budget for one financial year (table department_budgets,
 * FR11). Shown on the Budget Allocation screen, opened from the dashboard's
 * "Budget allocated" tile.
 */
@Serializable
data class DepartmentBudget(
    val department: String,
    @SerialName("financial_year") val financialYear: String,
    val allocated: Double,
    val committed: Double = 0.0,
    val disbursed: Double = 0.0
) {
    /** Budget not yet committed to an awarded contract. */
    val uncommitted: Double get() = (allocated - committed).coerceAtLeast(0.0)

    /** Share of this department's allocation that has been committed. */
    val committedFraction: Float get() = if (allocated <= 0) 0f else (committed / allocated).toFloat()
}

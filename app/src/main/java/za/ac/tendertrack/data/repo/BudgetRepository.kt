package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.DepartmentBudget
import za.ac.tendertrack.data.sample.SampleData

/** Department budgets (FR11). Read-only in the app. */
interface BudgetRepository {
    /** Every department's budget, newest financial year first. */
    suspend fun departmentBudgets(): List<DepartmentBudget>
}

class SupabaseBudgetRepository(private val client: SupabaseClient) : BudgetRepository {

    override suspend fun departmentBudgets(): List<DepartmentBudget> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.BUDGETS)
            .select { order("financial_year", Order.DESCENDING) }
            .decodeList<DepartmentBudget>()
    }
}

/** Offline stand-in with the same figures as supabase/seed.sql. */
class SampleBudgetRepository : BudgetRepository {

    override suspend fun departmentBudgets(): List<DepartmentBudget> {
        val year = SampleData.fundSummary.financialYear
        return listOf(
            DepartmentBudget(
                SampleData.currentUser.department ?: "Gauteng Dept of e-Government", year,
                SampleData.fundSummary.allocated, SampleData.fundSummary.committed, SampleData.fundSummary.disbursed
            ),
            DepartmentBudget("KZN Department of Health", year, 120_000_000.0, 31_200_000.0, 0.0),
            DepartmentBudget("Western Cape Provincial Treasury", year, 64_000_000.0, 6_900_000.0, 0.0),
            DepartmentBudget("National Treasury", year, 42_000_000.0, 4_750_000.0, 0.0),
            DepartmentBudget("Eastern Cape CoGTA", year, 18_000_000.0, 2_480_000.0, 2_480_000.0)
        )
    }
}

package za.ac.tendertrack.data.repo

import za.ac.tendertrack.core.Format
import za.ac.tendertrack.data.model.*

/**
 * Report generation — FR17.
 *
 * Builds the report from the same repositories the screens use, so an export can
 * never disagree with what is on screen. The file is produced as CSV text, which
 * Excel opens directly; the PDF option is handed to the platform print service.
 */
class ReportRepository(
    private val tenderRepository: TenderRepository,
    private val supplierRepository: SupplierRepository,
    private val paymentRepository: PaymentRepository,
    private val flagRepository: FlagRepository
) {

    suspend fun generate(
        type: ReportType,
        fromIso: String,
        toIso: String,
        department: String?,
        format: ReportFormat
    ): GeneratedReport {
        val rows: List<List<String>> = when (type) {
            ReportType.LIFECYCLE_SUMMARY -> lifecycleRows(department)
            ReportType.AWARD_REGISTER -> awardRows(department, fromIso, toIso)
            ReportType.PAYMENTS -> paymentRows(fromIso, toIso)
            ReportType.SUPPLIER_REGISTER -> supplierRows()
            ReportType.COMPLIANCE_FLAGS -> flagRows(fromIso, toIso)
            ReportType.AUDIT_TRAIL -> auditRows()
        }
        val stamp = "Generated ${Format.date(Format.nowIso())} · ${type.label} · " +
            "${Format.date(fromIso)} to ${Format.date(toIso)}"
        val body = buildString {
            appendLine(escapeRow(listOf(stamp)))
            rows.forEach { appendLine(escapeRow(it)) }
        }
        val slug = type.name.lowercase().replace('_', '-')
        return GeneratedReport(
            fileName = "tendertrack-$slug-${Format.isoDate(Format.nowIso())}.${format.extension}",
            rowCount = (rows.size - 1).coerceAtLeast(0),
            content = body
        )
    }

    private suspend fun lifecycleRows(department: String?): List<List<String>> {
        val tenders = tenderRepository.list().filterDept(department)
        return buildList {
            add(listOf("Reference", "Title", "Department", "Status", "Estimated budget", "Closing date"))
            tenders.forEach {
                add(listOf(
                    it.referenceNumber, it.title, it.department, it.status.displayName,
                    Format.money(it.estimatedBudget), Format.date(it.closingDate)
                ))
            }
        }
    }

    private suspend fun awardRows(department: String?, fromIso: String, toIso: String): List<List<String>> {
        val tenders = tenderRepository.list().filterDept(department)
            .filter { it.awardedAt != null && it.awardedAt in fromIso..toIso }
        return buildList {
            add(listOf("Reference", "Title", "Awarded supplier", "Awarded value", "Awarded on", "Paid to date"))
            tenders.forEach {
                add(listOf(
                    it.referenceNumber, it.title, it.awardedSupplierName ?: "",
                    Format.money(it.awardedValue ?: 0.0), Format.date(it.awardedAt),
                    Format.money(it.paidToDate)
                ))
            }
        }
    }

    private suspend fun paymentRows(fromIso: String, toIso: String): List<List<String>> {
        val payments = paymentRepository.all().filter { it.paidOn >= Format.isoDate(fromIso) && it.paidOn <= Format.isoDate(toIso) }
        return buildList {
            add(listOf("Tender", "Milestone", "Amount", "Paid on", "Invoice", "Recorded by"))
            payments.forEach {
                add(listOf(
                    it.tenderReference, it.milestone, Format.money(it.amount),
                    Format.date(it.paidOn), it.invoiceNumber, it.recordedBy
                ))
            }
        }
    }

    private suspend fun supplierRows(): List<List<String>> {
        val suppliers = supplierRepository.list()
        return buildList {
            add(listOf("Company", "Registration no.", "CSD number", "B-BBEE level", "Status", "Submitted"))
            suppliers.forEach {
                add(listOf(
                    it.companyName, it.registrationNumber, it.csdNumber,
                    it.bbbeeLevel?.toString() ?: "—", it.status.displayName, Format.date(it.submittedAt)
                ))
            }
        }
    }

    private suspend fun flagRows(fromIso: String, toIso: String): List<List<String>> {
        val flags = flagRepository.list().filter { it.raisedAt in fromIso..toIso }
        return buildList {
            add(listOf("Reference", "Tender", "Flag", "Rule", "Severity", "Status", "Raised"))
            flags.forEach {
                add(listOf(
                    it.reference, it.tenderReference, it.title, it.ruleTriggered,
                    it.severity.displayName, it.status.displayName, Format.date(it.raisedAt)
                ))
            }
        }
    }

    private suspend fun auditRows(): List<List<String>> {
        val tenders = tenderRepository.list()
        val entries = tenders.flatMap { tenderRepository.auditTrail(it.id) }.sortedBy { it.createdAt }
        return buildList {
            add(listOf("When", "Entity", "Action", "Detail", "Actor"))
            entries.forEach {
                add(listOf(
                    Format.dateTime(it.createdAt), it.entityType, it.action, it.detail, it.actor
                ))
            }
        }
    }

    private fun List<Tender>.filterDept(department: String?): List<Tender> =
        if (department.isNullOrBlank()) this else filter { it.department == department }

    private fun escapeRow(cells: List<String>): String =
        cells.joinToString(",") { cell ->
            if (cell.contains(',') || cell.contains('"')) "\"${cell.replace("\"", "\"\"")}\"" else cell
        }
}

package za.ac.tendertrack.data.sample

import za.ac.tendertrack.data.model.*

/**
 * Bundled sample data.
 *
 * Used only when no Supabase project is configured, so the app can be opened and
 * marked without a backend. It is deliberately held in memory and mutated by the
 * repositories, which means actions such as awarding a tender or recording a
 * payment behave correctly during a demo.
 */
object SampleData {

    val currentUser = Profile(
        id = "u-po-001",
        email = "t.mokoena@gauteng.gov.za",
        fullName = "T. Mokoena",
        role = UserRole.PROCUREMENT_OFFICER,
        department = "Gauteng Dept of e-Government"
    )

    val departments = listOf(
        "Gauteng Dept of e-Government",
        "Western Cape Provincial Treasury",
        "KZN Department of Health",
        "Eastern Cape CoGTA",
        "National Treasury"
    )

    val categories = listOf(
        "IT Infrastructure",
        "IT Services",
        "Software Licensing",
        "Cybersecurity",
        "Cloud Hosting",
        "End-User Devices"
    )

    val milestones = listOf(
        "Deposit / mobilisation",
        "Delivery milestone 1",
        "Delivery milestone 2",
        "Acceptance testing",
        "Final payment / retention"
    )

    val tenders = mutableListOf(
        Tender(
            id = "t-001",
            referenceNumber = "GP/IT/2290",
            title = "Network Hardware Supply",
            description = "Supply, delivery and installation of core switching and routing hardware across 14 sites.",
            department = "Gauteng Dept of e-Government",
            category = "IT Infrastructure",
            estimatedBudget = 18_400_000.0,
            closingDate = "2026-06-30T11:00:00Z",
            contractPeriodMonths = 24,
            status = TenderStatus.AWARDED,
            awardedSupplierId = "s-001",
            awardedSupplierName = "Infratech Solutions (Pty) Ltd",
            awardedValue = 17_950_000.0,
            awardedAt = "2026-08-12T14:00:00Z",
            paidToDate = 3_231_000.0,
            openFlagCount = 1,
            createdAt = "2026-06-02T09:00:00Z"
        ),
        Tender(
            id = "t-002",
            referenceNumber = "WC/SF/1042",
            title = "SaaS CRM Integration",
            description = "Integration of a citizen relationship management platform with existing provincial systems.",
            department = "Western Cape Provincial Treasury",
            category = "IT Services",
            estimatedBudget = 6_900_000.0,
            closingDate = "2026-10-01T12:00:00Z",
            contractPeriodMonths = 18,
            status = TenderStatus.PUBLISHED,
            createdAt = "2026-07-14T09:00:00Z"
        ),
        Tender(
            id = "t-003",
            referenceNumber = "KZN/HW/9923",
            title = "End-User Device Leases",
            description = "36-month lease of 4 200 notebooks and docking stations for district health offices.",
            department = "KZN Department of Health",
            category = "End-User Devices",
            estimatedBudget = 31_200_000.0,
            closingDate = "2026-10-04T12:00:00Z",
            contractPeriodMonths = 36,
            status = TenderStatus.PUBLISHED,
            openFlagCount = 1,
            createdAt = "2026-07-28T09:00:00Z"
        ),
        Tender(
            id = "t-004",
            referenceNumber = "NAT/SEC/492",
            title = "Cybersecurity Audit Services",
            description = "Independent penetration testing and control assurance across national departments.",
            department = "National Treasury",
            category = "Cybersecurity",
            estimatedBudget = 4_750_000.0,
            closingDate = "2026-08-02T11:00:00Z",
            contractPeriodMonths = 12,
            status = TenderStatus.UNDER_EVALUATION,
            openFlagCount = 1,
            createdAt = "2026-06-20T09:00:00Z"
        ),
        Tender(
            id = "t-005",
            referenceNumber = "EC/WEB/004",
            title = "Municipal Portal Development",
            description = "Design and build of a municipal services portal with payment integration.",
            department = "Eastern Cape CoGTA",
            category = "IT Services",
            estimatedBudget = 2_600_000.0,
            closingDate = "2026-01-20T11:00:00Z",
            contractPeriodMonths = 12,
            status = TenderStatus.COMPLETED,
            awardedSupplierId = "s-002",
            awardedSupplierName = "Vuka IT Consulting",
            awardedValue = 2_480_000.0,
            awardedAt = "2026-02-18T10:00:00Z",
            paidToDate = 2_480_000.0,
            createdAt = "2025-12-05T09:00:00Z"
        ),
        Tender(
            id = "t-006",
            referenceNumber = "GP/CLD/1187",
            title = "Cloud Backup Hosting",
            description = "Offsite backup and disaster recovery hosting for provincial data centres.",
            department = "Gauteng Dept of e-Government",
            category = "Cloud Hosting",
            estimatedBudget = 9_800_000.0,
            closingDate = "2026-05-15T11:00:00Z",
            contractPeriodMonths = 24,
            status = TenderStatus.IN_PROGRESS,
            awardedSupplierId = "s-003",
            awardedSupplierName = "Siyakhula Technologies",
            awardedValue = 9_450_000.0,
            awardedAt = "2026-06-30T10:00:00Z",
            paidToDate = 4_100_000.0,
            createdAt = "2026-04-01T09:00:00Z"
        ),
        Tender(
            id = "t-007",
            referenceNumber = "GP/IT/2291",
            title = "Data Centre UPS Replacement",
            description = "Replacement of uninterruptible power supply units at the primary data centre.",
            department = "Gauteng Dept of e-Government",
            category = "IT Infrastructure",
            estimatedBudget = 5_400_000.0,
            closingDate = "2026-11-12T11:00:00Z",
            contractPeriodMonths = 6,
            status = TenderStatus.REGISTERED,
            createdAt = "2026-09-01T09:00:00Z"
        )
    )

    val suppliers = mutableListOf(
        Supplier(
            id = "s-001",
            companyName = "Infratech Solutions (Pty) Ltd",
            registrationNumber = "2019/451236/07",
            csdNumber = "MAAA0451236",
            taxClearanceExpiry = "2027-06-30",
            bbbeeLevel = 2,
            businessType = "Pty Ltd",
            contactEmail = "ops@infratech.co.za",
            physicalAddress = "14 Anderson Street, Marshalltown, Johannesburg, 2001",
            documentsReceived = 4,
            status = SupplierVerificationStatus.VERIFIED,
            submittedAt = "2026-08-31T08:30:00Z"
        ),
        Supplier(
            id = "s-002",
            companyName = "Vuka IT Consulting",
            registrationNumber = "2017/338914/07",
            csdNumber = "MAAA0338914",
            taxClearanceExpiry = null,
            bbbeeLevel = 1,
            businessType = "Pty Ltd",
            contactEmail = "admin@vukait.co.za",
            physicalAddress = "8 Loop Street, Cape Town, 8001",
            documentsReceived = 3,
            status = SupplierVerificationStatus.AWAITING_VERIFICATION,
            submittedAt = "2026-09-02T10:15:00Z"
        ),
        Supplier(
            id = "s-003",
            companyName = "Siyakhula Technologies",
            registrationNumber = "2021/512077/07",
            csdNumber = "MAAA0512077",
            taxClearanceExpiry = "2026-06-30",
            bbbeeLevel = 4,
            businessType = "Pty Ltd",
            contactEmail = "ops@siyakhula.co.za",
            physicalAddress = "22 Umgeni Road, Durban, 4001",
            documentsReceived = 4,
            status = SupplierVerificationStatus.AWAITING_VERIFICATION,
            submittedAt = "2026-09-04T07:45:00Z"
        ),
        Supplier(
            id = "s-004",
            companyName = "Lethabo Digital CC",
            registrationNumber = "2015/118844/23",
            csdNumber = "MAAA0118844",
            taxClearanceExpiry = "2027-02-28",
            bbbeeLevel = 1,
            businessType = "Close Corporation",
            contactEmail = "hello@lethabodigital.co.za",
            physicalAddress = "5 Church Street, Polokwane, 0700",
            documentsReceived = 4,
            status = SupplierVerificationStatus.AWAITING_VERIFICATION,
            submittedAt = "2026-09-05T11:20:00Z"
        ),
        Supplier(
            id = "s-005",
            companyName = "Northern Cape Networks",
            registrationNumber = "2013/771122/07",
            csdNumber = "MAAA0771122",
            taxClearanceExpiry = "2026-03-31",
            bbbeeLevel = 6,
            businessType = "Pty Ltd",
            contactEmail = "info@ncnetworks.co.za",
            physicalAddress = "3 Du Toitspan Road, Kimberley, 8301",
            documentsReceived = 4,
            status = SupplierVerificationStatus.NOT_APPROVED,
            submittedAt = "2026-08-11T09:00:00Z",
            decisionReason = "Tax Clearance Certificate expired on 31 March 2026."
        )
    )

    val payments = mutableListOf(
        Payment("p-001", "t-001", "GP/IT/2290", "Deposit / mobilisation", 1_795_000.0, "2026-08-20", "INV-2026-0388", "T. Mokoena"),
        Payment("p-002", "t-001", "GP/IT/2290", "Delivery milestone 1", 1_436_000.0, "2026-09-01", "INV-2026-0441", "T. Mokoena"),
        Payment("p-003", "t-005", "EC/WEB/004", "Final payment / retention", 620_000.0, "2026-08-30", "INV-2026-0402", "P. Naidoo"),
        Payment("p-004", "t-006", "GP/CLD/1187", "Deposit / mobilisation", 2_400_000.0, "2026-07-10", "INV-2026-0301", "P. Naidoo"),
        Payment("p-005", "t-006", "GP/CLD/1187", "Delivery milestone 1", 1_700_000.0, "2026-08-14", "INV-2026-0356", "P. Naidoo")
    )

    val flags = mutableListOf(
        ComplianceFlag(
            id = "f-001",
            reference = "FLG-2026-0093",
            tenderId = "t-001",
            tenderReference = "GP/IT/2290",
            tenderTitle = "Network Hardware Supply",
            title = "Award value variance",
            description = "The awarded value of R 17 950 000 differs from the published estimate of R 18 400 000. Combined with a single-supplier shortlist, the rule threshold was met.",
            ruleTriggered = "Award variance above threshold",
            severity = FlagSeverity.HIGH,
            status = FlagStatus.OPEN,
            raisedAt = "2026-08-12T14:02:00Z",
            assignedTo = "N. Dlamini (Auditor)",
            notes = listOf(
                FlagNote("n-001", "System rule R-04", "Flag raised automatically.", "2026-08-12T14:02:00Z"),
                FlagNote("n-002", "N. Dlamini", "Assigned for review. Evidence requested from the department.", "2026-08-15T11:45:00Z")
            )
        ),
        ComplianceFlag(
            id = "f-002",
            reference = "FLG-2026-0088",
            tenderId = "t-004",
            tenderReference = "NAT/SEC/492",
            tenderTitle = "Cybersecurity Audit Services",
            title = "Single bid received",
            description = "Only one responsive bid was received on a tender with an estimated value above R 1 million.",
            ruleTriggered = "Fewer than 3 bids above R 1 m",
            severity = FlagSeverity.MEDIUM,
            status = FlagStatus.OPEN,
            raisedAt = "2026-08-02T09:30:00Z",
            assignedTo = null
        ),
        ComplianceFlag(
            id = "f-003",
            reference = "FLG-2026-0081",
            tenderId = "t-003",
            tenderReference = "KZN/HW/9923",
            tenderTitle = "End-User Device Leases",
            title = "Supplier tax clearance lapsed",
            description = "The awarded supplier's tax compliance status expired during the contract period.",
            ruleTriggered = "Tax clearance expired mid-contract",
            severity = FlagSeverity.MEDIUM,
            status = FlagStatus.UNDER_INVESTIGATION,
            raisedAt = "2026-07-21T08:10:00Z",
            assignedTo = "T. Mokoena (Procurement Officer)"
        )
    )

    val notifications = mutableListOf(
        AppNotification("n-01", NotificationKind.FLAG, "Compliance flag raised on GP/IT/2290",
            "Awarded value differs from the published estimate by more than the permitted threshold.",
            "2026-08-12T14:02:00Z", read = false),
        AppNotification("n-02", NotificationKind.DEADLINE, "WC/SF/1042 closes in 3 days",
            "Closing 01 Oct 2026 at 12:00. An evaluation panel has not yet been assigned.",
            "2026-09-04T08:00:00Z", read = false),
        AppNotification("n-03", NotificationKind.AWARD_CODE, "Award code unclaimed after 7 days",
            "The code issued for NAT/SEC/492 has not been used by the awarded supplier.",
            "2026-09-03T09:15:00Z", read = false),
        AppNotification("n-04", NotificationKind.REGISTRATION, "3 supplier registrations awaiting verification",
            "The oldest was submitted on 02 Sep 2026.",
            "2026-09-02T07:00:00Z", read = true),
        AppNotification("n-05", NotificationKind.PAYMENT, "Payment recorded on EC/WEB/004",
            "R 620 000 final milestone. The contract is now fully disbursed.",
            "2026-08-30T16:40:00Z", read = true)
    )

    val auditTrail = mutableListOf(
        AuditEntry("a-001", "tender", "t-001", "Status changed", "Registered", "T. Mokoena", "2026-06-02T09:00:00Z"),
        AuditEntry("a-002", "tender", "t-001", "Status changed", "Open for bids", "T. Mokoena", "2026-06-02T10:30:00Z"),
        AuditEntry("a-003", "tender", "t-001", "Status changed", "Under evaluation", "Evaluation Committee", "2026-06-30T12:00:00Z"),
        AuditEntry("a-004", "tender", "t-001", "Tender awarded", "Infratech Solutions (Pty) Ltd · R 17 950 000", "T. Mokoena", "2026-08-12T14:00:00Z")
    )

    val fundSummary = FundSummary(
        financialYear = "2026/27",
        allocated = 248_000_000.0,
        committed = 214_700_000.0,
        disbursed = 162_400_000.0
    )

    /** Next reference number in the signed-in officer's department series. */
    fun nextReference(department: String): String {
        val prefix = when {
            department.contains("Gauteng") -> "GP/IT"
            department.contains("Western Cape") -> "WC/IT"
            department.contains("KZN") -> "KZN/IT"
            department.contains("Eastern Cape") -> "EC/IT"
            else -> "NAT/IT"
        }
        val highest = tenders.mapNotNull { it.referenceNumber.substringAfterLast("/").toIntOrNull() }.maxOrNull() ?: 2290
        return "$prefix/${highest + 1}"
    }
}

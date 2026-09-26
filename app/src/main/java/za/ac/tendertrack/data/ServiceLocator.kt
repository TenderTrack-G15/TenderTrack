package za.ac.tendertrack.data

import za.ac.tendertrack.data.repo.*

/**
 * Wires the repositories up once and hands the same instances to every
 * ViewModel.
 *
 * When a Supabase project is configured the real implementations are used;
 * otherwise the sample implementations are, so the app runs either way. Keeping
 * the choice in one place means no screen knows or cares which it got.
 */
object ServiceLocator {

    val usingSampleData: Boolean = SupabaseModule.client == null

    val authRepository: AuthRepository by lazy {
        SupabaseModule.client?.let { SupabaseAuthRepository(it) } ?: SampleAuthRepository()
    }

    val supplierRepository: SupplierRepository by lazy {
        SupabaseModule.client?.let { SupabaseSupplierRepository(it) } ?: SampleSupplierRepository()
    }

    val flagRepository: FlagRepository by lazy {
        SupabaseModule.client?.let { SupabaseFlagRepository(it) } ?: SampleFlagRepository()
    }

    val notificationRepository: NotificationRepository by lazy {
        SupabaseModule.client?.let { SupabaseNotificationRepository(it) } ?: SampleNotificationRepository()
    }

    val paymentRepository: PaymentRepository by lazy {
        SupabaseModule.client?.let { SupabasePaymentRepository(it) } ?: SamplePaymentRepository()
    }

    val tenderRepository: TenderRepository by lazy {
        SupabaseModule.client?.let {
            SupabaseTenderRepository(it, supplierRepository, flagRepository, notificationRepository)
        } ?: SampleTenderRepository()
    }

    /** Public / citizen screens. Runs as the anonymous role on Supabase. */
    val publicRepository: PublicRepository by lazy {
        SupabaseModule.client?.let { SupabasePublicRepository(it) } ?: SamplePublicRepository()
    }

    /** The supplier module: tender board, company profile, documents, banking. */
    val supplierPortalRepository: SupplierPortalRepository by lazy {
        SupabaseModule.client?.let { SupabaseSupplierPortalRepository(it) } ?: SampleSupplierPortalRepository()
    }

    /** Notices written by the administrator in the admin portal, shown as banners. */
    val announcementRepository: AnnouncementRepository by lazy {
        SupabaseModule.client?.let { SupabaseAnnouncementRepository(it) } ?: SampleAnnouncementRepository()
    }

    val reportRepository: ReportRepository by lazy {
        ReportRepository(tenderRepository, supplierRepository, paymentRepository, flagRepository)
    }

    /** Auditor screens: flags, findings and the read-only overview. */
    val auditorRepository: AuditorRepository by lazy {
        SupabaseModule.client?.let { SupabaseAuditorRepository(it) } ?: SampleAuditorRepository()
    }
}

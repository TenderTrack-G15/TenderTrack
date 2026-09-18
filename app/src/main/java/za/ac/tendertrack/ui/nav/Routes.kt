package za.ac.tendertrack.ui.nav

/**
 * Every destination in the app, in one place, so a typo in a route string
 * cannot silently break navigation.
 */
object Routes {
    const val SIGN_IN = "sign_in"
    const val DASHBOARD = "dashboard"
    const val TENDERS = "tenders"
    const val TENDER_DETAIL = "tender/{tenderId}"
    const val TENDER_FORM = "tender_form?tenderId={tenderId}"
    const val LIFECYCLE = "lifecycle/{tenderId}"
    const val AWARD = "award/{tenderId}"
    const val SUPPLIERS = "suppliers"
    const val SUPPLIER_REVIEW = "supplier/{supplierId}"
    const val RECORD_PAYMENT = "payment?tenderId={tenderId}"
    const val FUND_UTILISATION = "funds"
    const val FLAGS = "flags"
    const val FLAG_DETAIL = "flag/{flagId}"
    const val REPORTS = "reports"
    const val NOTIFICATIONS = "notifications"

    fun tenderDetail(id: String) = "tender/$id"
    fun tenderForm(id: String? = null) = "tender_form?tenderId=${id ?: ""}"
    fun lifecycle(id: String) = "lifecycle/$id"
    fun award(id: String) = "award/$id"
    fun supplierReview(id: String) = "supplier/$id"
    fun recordPayment(tenderId: String? = null) = "payment?tenderId=${tenderId ?: ""}"
    fun flagDetail(id: String) = "flag/$id"

    /** Destinations reachable from the drawer, which therefore show a menu button. */
    val drawerDestinations = setOf(
        DASHBOARD, TENDERS, SUPPLIERS, RECORD_PAYMENT,
        FUND_UTILISATION, FLAGS, REPORTS, NOTIFICATIONS
    )
}

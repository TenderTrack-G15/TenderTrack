package za.ac.tendertrack.ui.screens.supplier

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.SupplierPortalRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/** The home screen's data: the tender board and the supplier's own registration. */
data class SupplierHome(val board: List<TenderBoardItem>, val profile: SupplierProfile?) {
    val open: List<TenderBoardItem> get() = board.filter { it.openForBids }
    val closingSoon: Int get() = open.count { (Format.daysUntil(it.closingDate) ?: 99) in 0..7 }
    val documentsOutstanding: Int
        get() = profile?.let { (it.documentsRequired - it.documentsReceived).coerceAtLeast(0) } ?: 0
}

data class SupplierHomeUiState(
    val home: UiState<SupplierHome> = UiState.Loading,
    val query: String = "",
    val onlyOpen: Boolean = true
) {
    val visible: List<TenderBoardItem>
        get() {
            val all = (home as? UiState.Success)?.data?.board ?: return emptyList()
            return all.filter { t ->
                val matchesQuery = query.isBlank() ||
                    t.referenceNumber.contains(query, true) || t.title.contains(query, true) ||
                    t.department.contains(query, true) || t.category.contains(query, true)
                matchesQuery && (!onlyOpen || t.openForBids)
            }
        }
}

class SupplierHomeViewModel(
    private val repository: SupplierPortalRepository = ServiceLocator.supplierPortalRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SupplierHomeUiState())
    val state: StateFlow<SupplierHomeUiState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(home = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(SupplierHome(repository.board(), runCatching { repository.myProfile() }.getOrNull()))
            } catch (e: Exception) {
                UiState.Error(e.supplierMessage())
            }
            _state.update { it.copy(home = result) }
        }
    }

    fun onQuery(v: String) = _state.update { it.copy(query = v) }
    fun onOnlyOpen(v: Boolean) = _state.update { it.copy(onlyOpen = v) }
}

/**
 * Supplier home: the tenders available to bid on, with a search, and the state
 * of the supplier's own registration.
 */
@Composable
fun SupplierHomeScreen(
    supplierName: String,
    onOpenTender: (String) -> Unit,
    onOpenCompany: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: SupplierHomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    // Reloads whenever the screen comes back into view, e.g. after editing the company.
    LaunchedEffect(Unit) { viewModel.load() }

    AppScaffold(
        title = "TenderTrack",
        actions = listOf(
            TopBarAction(Icons.Default.Business, "My company") { onOpenCompany() },
            TopBarAction(Icons.AutoMirrored.Filled.Logout, "Sign out") { onSignOut() }
        )
    ) {
        when (val result = state.home) {
            is UiState.Loading -> LoadingState(message = "Loading tenders…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val home = result.data
                ScreenHeading(
                    eyebrow = "Supplier",
                    title = home.profile?.companyName ?: "Welcome",
                    subtitle = "Signed in as $supplierName",
                    trailing = {
                        home.profile?.let { StatusBadge(it.status.supplierLabel(), it.status.supplierTone()) }
                    }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                    StatTile("Open for bids", "${home.open.size}", Modifier.weight(1f), "Accepting bids now")
                    StatTile(
                        "Closing this week", "${home.closingSoon}", Modifier.weight(1f), "Within 7 days",
                        alert = home.closingSoon > 0
                    )
                }
                Spacer(Modifier.height(Dimens.GridGap))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                    StatTile(
                        "My registration", home.profile?.status?.supplierLabel() ?: "None",
                        Modifier.weight(1f), home.profile?.reference ?: "Not registered",
                        onClick = onOpenCompany
                    )
                    StatTile(
                        "Documents outstanding", "${home.documentsOutstanding}",
                        Modifier.weight(1f), "Of ${home.profile?.documentsRequired ?: 6} required",
                        alert = home.documentsOutstanding > 0,
                        onClick = onOpenCompany
                    )
                }

                home.profile?.takeIf { it.status == SupplierVerificationStatus.NOT_APPROVED }?.let {
                    NoteBanner(
                        title = "Registration not approved",
                        text = it.decisionReason ?: "Open My company to correct your details and resubmit.",
                        tone = NoteTone.Danger,
                        icon = Icons.Default.ErrorOutline
                    )
                }

                SectionHeader("Available tenders")
                SearchField(
                    value = state.query,
                    onValueChange = viewModel::onQuery,
                    placeholder = "Search reference, title, department or category"
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
                ) {
                    // onClick by name: it is not FilterChip's last parameter.
                    FilterChip(text = "Open for bids", selected = state.onlyOpen, onClick = { viewModel.onOnlyOpen(true) })
                    FilterChip(text = "All tenders", selected = !state.onlyOpen, onClick = { viewModel.onOnlyOpen(false) })
                }

                val visible = state.visible
                Text("${visible.size} tenders", style = AppType.Meta)
                if (visible.isEmpty()) {
                    EmptyState(
                        title = "No tenders match",
                        message = "Try a different search, or show all tenders.",
                        icon = Icons.Default.Search
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        visible.forEach { tender -> TenderCard(tender) { onOpenTender(tender.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun TenderCard(tender: TenderBoardItem, onClick: () -> Unit) {
    val daysLeft = Format.daysUntil(tender.closingDate)
    AppCard(onClick = onClick) {
        CardHeader(
            title = tender.referenceNumber,
            subtitle = tender.title,
            trailing = { StatusBadge(tender.status.bidderLabel(), tender.status.bidderTone()) }
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Department", tender.department)
        KeyValueRow("Category", tender.category.ifBlank { "—" })
        KeyValueRow("Published", tender.publishedAt?.let { Format.date(it) } ?: "—")
        KeyValueRow(
            "Closing",
            "${Format.date(tender.closingDate)}${if (tender.openForBids && daysLeft != null) " · $daysLeft days left" else ""}",
            valueColor = if (tender.openForBids && (daysLeft ?: 99) <= 7) AppColor.DangerInk else AppColor.Ink,
            showDivider = false
        )
    }
}

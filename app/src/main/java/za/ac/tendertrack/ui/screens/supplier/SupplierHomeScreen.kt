package za.ac.tendertrack.ui.screens.supplier

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import za.ac.tendertrack.data.repo.AwardRepository
import za.ac.tendertrack.data.repo.RegistrationRepository
import za.ac.tendertrack.data.repo.SupplierPortalRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/** The home screen's data: the tender board, the supplier's registration and its awards. */
data class SupplierHome(
    val board: List<TenderBoardItem>,
    val profile: SupplierProfile?,
    val awards: List<SupplierAward> = emptyList(),
    /** Null when it could not be checked; the database still enforces it. */
    val registration: TenderTrackRegistration? = null
) {
    /** Registered on the eTender portal but not yet for TenderTrack. */
    val mustRegister: Boolean get() = registration != null && !registration.appRegistered

    /** Awards whose emailed code has not been entered yet. */
    val awaitingCode: List<SupplierAward> get() = awards.filter { it.canEnterCode }

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
    private val repository: SupplierPortalRepository = ServiceLocator.supplierPortalRepository,
    private val awardRepository: AwardRepository = AwardRepository.instance,
    private val registrationRepository: RegistrationRepository = RegistrationRepository.instance
) : ViewModel() {

    private val _state = MutableStateFlow(SupplierHomeUiState())
    val state: StateFlow<SupplierHomeUiState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(home = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                val registration = runCatching { registrationRepository.status() }.getOrNull()
                UiState.Success(
                    SupplierHome(
                        board = repository.board(),
                        profile = runCatching { repository.myProfile() }.getOrNull(),
                        // Awards must not stop the tender board from loading.
                        awards = if (registration?.appRegistered == false) emptyList()
                        else runCatching { awardRepository.awards() }.getOrDefault(emptyList()),
                        registration = registration
                    )
                )
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
 * Supplier home: the tenders available to bid on, with a search, the state
 * of the supplier's own registration, and its awards.
 */
@Composable
fun SupplierHomeScreen(
    supplierName: String,
    onOpenTender: (String) -> Unit,
    onOpenCompany: () -> Unit,
    onOpenAwards: () -> Unit,
    onClaim: (String) -> Unit,
    onRegister: () -> Unit,
    onSignOut: () -> Unit,
    /** Opens the supplier's navigation panel; the menu button shows when it is given. */
    onMenu: (() -> Unit)? = null,
    viewModel: SupplierHomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    // Reloads whenever the screen comes back into view, e.g. after editing the company.
    LaunchedEffect(Unit) { viewModel.load() }

    val loaded = (state.home as? UiState.Success)?.data
    val waiting = loaded?.awaitingCode?.size ?: 0
    val signOut = TopBarAction(Icons.AutoMirrored.Filled.Logout, "Sign out") { onSignOut() }

    AppScaffold(
        title = "TenderTrack",
        onMenu = onMenu,
        // Until the company has registered for TenderTrack, only signing out is offered.
        // My company and the rest are in the navigation panel.
        actions = if (loaded?.mustRegister == true) listOf(signOut) else listOf(
            TopBarAction(Icons.Default.EmojiEvents, "Awards", badgeCount = waiting) { onOpenAwards() },
            signOut
        )
    ) {
        when (val result = state.home) {
            is UiState.Loading -> LoadingState(message = "Loading tenders…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> if (result.data.mustRegister) {
                RegisterFirst(result.data, supplierName, onRegister, onCheckAgain = viewModel::load)
            } else {
                val home = result.data
                ScreenHeading(
                    eyebrow = "Supplier",
                    title = home.profile?.companyName ?: "Welcome",
                    subtitle = "Signed in as $supplierName",
                    trailing = {
                        home.profile?.let { StatusBadge(it.status.supplierLabel(), it.status.supplierTone()) }
                    }
                )

                // The tiles sit in their own column so the gaps between rows match the gap between tiles.
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                        StatTile("Open for bids", "${home.open.size}", Modifier.weight(1f), "Accepting bids now")
                        StatTile(
                            "Closing this week", "${home.closingSoon}", Modifier.weight(1f), "Within 7 days",
                            alert = home.closingSoon > 0
                        )
                    }
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
                    // One tile for both award figures: they open the same page.
                    AwardsTile(
                        awaitingCode = home.awaitingCode.size,
                        activeContracts = home.awards.count { it.isClaimed && it.status != TenderStatus.COMPLETED },
                        onClick = onOpenAwards
                    )
                }

                home.awaitingCode.firstOrNull()?.let { award ->
                    NoteBanner(
                        title = "You have been awarded ${award.referenceNumber}",
                        text = "Enter the 10-digit award code emailed to ${award.sentTo.ifBlank { "your company" }} " +
                            "to claim the contract. Until then you cannot start work on it.",
                        tone = NoteTone.Success,
                        icon = Icons.Default.EmojiEvents
                    )
                    PrimaryButton(
                        text = "Claim award",
                        icon = Icons.Default.Key,
                        // Straight to the code screen when only one award is waiting.
                        onClick = { if (home.awaitingCode.size == 1) onClaim(award.tenderId) else onOpenAwards() }
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

/**
 * Both award figures in one tile, since both open Awards: codes still to be
 * entered (red while any are waiting) and contracts with deliverables to update.
 */
@Composable
private fun AwardsTile(awaitingCode: Int, activeContracts: Int, onClick: () -> Unit) {
    val alert = awaitingCode > 0
    AppCard(
        background = if (alert) AppColor.DangerSurface else AppColor.Surface,
        border = if (alert) AppColor.DangerBorder else AppColor.Line,
        onClick = onClick
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AwardFigure(
                label = "Awaiting your code",
                value = awaitingCode,
                footnote = "Claim under Awards",
                alert = alert,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .width(1.dp)
                    .height(56.dp)
                    .background(if (alert) AppColor.DangerBorder else AppColor.Line)
            )
            AwardFigure(
                label = "Active contracts",
                value = activeContracts,
                footnote = "Deliverables to update",
                alert = false,
                modifier = Modifier.weight(1f)
            )
            AppIcon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                tint = if (alert) AppColor.DangerInk else AppColor.MutedLight,
                contentDescription = "Open Awards"
            )
        }
    }
}

@Composable
private fun AwardFigure(label: String, value: Int, footnote: String, alert: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = AppType.StatLabel, maxLines = 2)
        Spacer(Modifier.height(2.dp))
        Text(
            "$value",
            style = AppType.StatValue.copy(color = if (alert) AppColor.DangerInk else AppColor.Ink),
            maxLines = 1
        )
        Spacer(Modifier.height(3.dp))
        Text(
            footnote,
            style = AppType.StatFoot.copy(color = if (alert) AppColor.DangerInk else AppColor.MutedLight),
            maxLines = 2
        )
    }
}

/**
 * Shown instead of the home screen when the company registered on the eTender
 * portal but has not registered for TenderTrack yet.
 */
@Composable
private fun RegisterFirst(home: SupplierHome, supplierName: String, onRegister: () -> Unit, onCheckAgain: () -> Unit) {
    ScreenHeading(
        eyebrow = "Supplier",
        title = home.registration?.companyName?.ifBlank { null } ?: home.profile?.companyName ?: "Welcome",
        subtitle = "Signed in as $supplierName"
    )
    if (home.registration?.portalRegistered == false) {
        // The company's own step on the portal comes first; no one else has to do anything.
        val missing = home.registration?.portalMissing.orEmpty()
        NoteBanner(
            title = "Finish your eTender registration first",
            text = "Your company's registration on the eTender portal is not complete" +
                (if (missing.isNotEmpty()) ". Still needed: ${missing.joinToString("; ")}" else "") +
                ". Sign in on the eTender portal with this email and password to add it, then tap Check again.",
            tone = NoteTone.Warning,
            icon = Icons.Default.Shield
        )
        SecondaryButton(text = "Check again", icon = Icons.Default.Refresh, onClick = onCheckAgain)
        return
    }
    NoteBanner(
        title = "Finish registering for TenderTrack",
        text = "Your company is registered on the eTender portal. To use TenderTrack as well, confirm it here " +
            "with your CSD and registration numbers and a 6-digit code emailed to the company. Until then you " +
            "cannot claim awards or update deliverables in TenderTrack.",
        tone = NoteTone.Warning,
        icon = Icons.Default.Shield
    )
    PrimaryButton(text = "Register for TenderTrack", icon = Icons.Default.HowToReg, onClick = onRegister)
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

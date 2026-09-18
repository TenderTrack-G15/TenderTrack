package za.ac.tendertrack.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.*
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.GeneratedReport
import za.ac.tendertrack.data.model.ReportFormat
import za.ac.tendertrack.data.model.ReportType
import za.ac.tendertrack.data.repo.ReportRepository
import za.ac.tendertrack.data.sample.SampleData
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import java.io.File

data class ReportsUiState(
    val type: ReportType = ReportType.LIFECYCLE_SUMMARY,
    val from: String = "01/04/2026",
    val to: String = "31/03/2027",
    val department: String? = null,
    val format: ReportFormat = ReportFormat.CSV,
    val errors: Map<String, String> = emptyMap(),
    val action: ActionState = ActionState.Idle,
    val generated: List<GeneratedReport> = emptyList()
)

class ReportsViewModel(
    private val repository: ReportRepository = ServiceLocator.reportRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ReportsUiState())
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()

    fun onType(value: ReportType) = _state.update { it.copy(type = value) }
    fun onFrom(value: String) = _state.update { it.copy(from = value, errors = it.errors - "from") }
    fun onTo(value: String) = _state.update { it.copy(to = value, errors = it.errors - "to") }
    fun onDepartment(value: String?) = _state.update { it.copy(department = value) }
    fun onFormat(value: ReportFormat) = _state.update { it.copy(format = value) }

    /** Generates the report and hands the file back so it can be shared or saved. */
    fun generate(writeFile: (GeneratedReport) -> Unit) {
        val current = _state.value
        val errors = buildMap {
            Validate.date(current.from, "From date")?.let { put("from", it) }
            Validate.date(current.to, "To date")?.let { put("to", it) }
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }

        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                val report = repository.generate(
                    type = current.type,
                    fromIso = Validate.toIso(current.from),
                    toIso = Validate.toIso(current.to, "23:59"),
                    department = current.department,
                    format = current.format
                )
                writeFile(report)
                _state.update {
                    it.copy(
                        action = ActionState.Succeeded("${report.rowCount} rows exported."),
                        generated = listOf(report) + it.generated
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.friendlyMessage())) }
            }
        }
    }

    fun clearAction() = _state.update { it.copy(action = ActionState.Idle) }
}

/** Reports — FR17. Generates an export from the same data the screens show. */
@Composable
fun ReportsScreen(
    onMenu: () -> Unit,
    viewModel: ReportsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.action) {
        when (val a = state.action) {
            is ActionState.Succeeded -> { snackbar.showSnackbar(a.message); viewModel.clearAction() }
            is ActionState.Failed -> { snackbar.showSnackbar(a.message); viewModel.clearAction() }
            else -> Unit
        }
    }

    AppScaffold(title = "Reports", onMenu = onMenu, snackbarHostState = snackbar) {
        ScreenHeading(
            title = "Generate a report",
            subtitle = "Exports are built from live data and stamped with the run date.",
            small = true
        )

        AppDropdownField(
            label = "Report type",
            selected = state.type,
            options = ReportType.entries.toList(),
            optionLabel = { it.label },
            onSelect = viewModel::onType
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMd)) {
            AppTextField(
                label = "From",
                value = state.from,
                onValueChange = viewModel::onFrom,
                placeholder = "dd/mm/yyyy",
                leadingIcon = Icons.Default.CalendarToday,
                keyboardType = KeyboardType.Number,
                error = state.errors["from"],
                modifier = Modifier.weight(1f)
            )
            AppTextField(
                label = "To",
                value = state.to,
                onValueChange = viewModel::onTo,
                placeholder = "dd/mm/yyyy",
                leadingIcon = Icons.Default.CalendarToday,
                keyboardType = KeyboardType.Number,
                error = state.errors["to"],
                modifier = Modifier.weight(1f)
            )
        }

        AppDropdownField(
            label = "Department",
            selected = state.department,
            options = listOf("All departments") + SampleData.departments,
            optionLabel = { it },
            onSelect = { viewModel.onDepartment(if (it == "All departments") null else it) },
            placeholder = "All departments"
        )

        Column {
            Text("Format", style = AppType.Label)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
            ) {
                ReportFormat.entries.forEach { format ->
                    FilterChip(
                        text = format.label,
                        selected = state.format == format,
                        onClick = { viewModel.onFormat(format) }
                    )
                }
            }
        }

        PrimaryButton(
            text = "Generate report",
            icon = Icons.Default.Download,
            loading = state.action is ActionState.Running,
            onClick = {
                viewModel.generate { report -> shareReport(context, report, state.format) }
            }
        )

        if (state.generated.isNotEmpty()) {
            SectionHeader("Generated this session")
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                state.generated.forEach { report ->
                    AppCard(onClick = { shareReport(context, report, state.format) }) {
                        CardHeader(
                            title = report.fileName,
                            subtitle = "${report.rowCount} rows",
                            trailing = { AppIcon(Icons.Default.Share) }
                        )
                    }
                }
            }
        }

        NoteBanner(
            "Every export records who generated it and when, and appears in the audit trail."
        )
    }
}

/**
 * Writes the report into the app's cache and opens the system share sheet, so
 * the officer can email it or save it to their device.
 */
private fun shareReport(context: Context, report: GeneratedReport, format: ReportFormat) {
    try {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, report.fileName)
        file.writeText(report.content)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, report.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share report"))
    } catch (e: Exception) {
        // The report is still listed on screen; sharing simply failed.
    }
}

package za.ac.tendertrack.ui.screens.admin

import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.model.AdminAccount
import za.ac.tendertrack.data.model.UserRole
import za.ac.tendertrack.ui.components.BadgeTone
import za.ac.tendertrack.ui.components.TextAction
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import java.io.File

/*
 * Pieces shared by the Administrator screens. Everything visual still comes
 * from the existing components and the AppColor / AppType / Dimens tokens, so
 * these screens match the officer and public screens exactly.
 */

// ---------------------------------------------------------------------------
// Roles and permissions (Deliverable 3: "displays the permissions that role
// grants and denies, alongside a summary of all seven roles")
// ---------------------------------------------------------------------------

/** What each role may and may not do. Describes the rules the database enforces. */
data class RolePermissions(val summary: String, val grants: List<String>, val denies: List<String>)

fun UserRole.permissions(): RolePermissions = when (this) {
    UserRole.PUBLIC -> RolePermissions(
        "Uses the app without an account.",
        listOf("View published tenders, spending and delivery progress", "Flag a tender for review"),
        listOf("See unpublished tenders or estimates before award", "See who reported what")
    )
    UserRole.SUPPLIER -> RolePermissions(
        "A registered company that bids for tenders.",
        listOf("Browse published tenders", "See its own awarded contract", "Upload evidence for its own deliverables"),
        listOf("See estimates while bidding is open", "See other suppliers' records", "Any officer action")
    )
    UserRole.PROCUREMENT_OFFICER -> RolePermissions(
        "Runs the tender lifecycle for their department.",
        listOf("Register, publish and advance tenders", "Award tenders", "Record payments",
            "Verify suppliers", "Review compliance flags", "Generate reports"),
        listOf("Change roles or manage accounts")
    )
    UserRole.EVALUATION_COMMITTEE -> RolePermissions(
        "Assesses bids for their department.",
        listOf("Read tenders, including the estimate"),
        listOf("Award tenders", "Record payments", "Manage accounts")
    )
    UserRole.FINANCE_OFFICER -> RolePermissions(
        "Releases payments for their department.",
        listOf("Read tenders and payments", "Record payments against verified milestones"),
        listOf("Award tenders or change their status", "Manage accounts")
    )
    UserRole.AUDITOR -> RolePermissions(
        "Independent oversight across all departments.",
        listOf("Read all tenders, payments and flags", "Read the audit trail", "Raise and update compliance flags"),
        listOf("Change tenders or payments", "Delete any record", "Manage accounts")
    )
    UserRole.ADMINISTRATOR -> RolePermissions(
        "Manages the platform, not procurement.",
        listOf("Invite staff and assign roles", "Suspend and reinstate accounts",
            "Read the audit log", "Export tender and payment data (FR17)"),
        listOf("Tender actions — registering, awarding or paying (Deliverable 3)",
            "Change their own role or suspend themselves")
    )
}

fun AdminAccount.statusTone(): BadgeTone = if (suspended) BadgeTone.Danger else BadgeTone.Success
fun AdminAccount.statusLabel(): String = if (suspended) "Suspended" else "Active"

fun roleTone(role: UserRole): BadgeTone = when (role) {
    UserRole.ADMINISTRATOR -> BadgeTone.Warning
    UserRole.AUDITOR -> BadgeTone.Info
    UserRole.SUPPLIER, UserRole.PUBLIC -> BadgeTone.Neutral
    else -> BadgeTone.Success
}

/** Friendlier names for the audit trail's entity types. */
fun entityLabel(entityType: String): String = when (entityType) {
    "tender" -> "Tender"
    "account" -> "Account"
    "invitation" -> "Invitation"
    "supplier" -> "Supplier"
    "flag" -> "Flag"
    "payment" -> "Payment"
    else -> entityType.replaceFirstChar { it.uppercase() }
}

// ---------------------------------------------------------------------------
// Feedback
// ---------------------------------------------------------------------------

/** A server error can carry the URL and headers on later lines; show only the first. */
fun Throwable.adminMessage(): String =
    friendlyMessage().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: "Something went wrong. Please try again."

/** Confirmation before a sensitive change, styled with the app's own tokens. */
@Composable
fun AdminConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColor.Surface,
        title = { Text(title, style = AppType.H2) },
        text = { Text(message, style = AppType.Body.copy(color = AppColor.InkSoft)) },
        confirmButton = {
            TextAction(text = confirmText, color = if (danger) AppColor.DangerInk else AppColor.Ink, onClick = onConfirm)
        },
        dismissButton = { TextAction(text = "Cancel", color = AppColor.Muted, onClick = onDismiss) }
    )
}

// ---------------------------------------------------------------------------
// CSV export (FR17) — same FileProvider set-up as the officer Reports screen
// ---------------------------------------------------------------------------

/** One CSV field, quoted when it contains a comma, quote or line break. */
fun csv(value: Any?): String {
    val text = value?.toString() ?: ""
    return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + text.replace("\"", "\"\"") + "\""
    } else text
}

fun csvRow(vararg values: Any?): String = values.joinToString(",") { csv(it) }

/**
 * Writes the CSV into the app's cache (the "reports" folder the manifest's
 * FileProvider already allows) and opens the share sheet. Returns false if
 * sharing failed, so the screen can say so.
 */
fun shareCsv(context: Context, fileName: String, content: String): Boolean = try {
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    val file = File(dir, fileName)
    file.writeText(content)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export $fileName"))
    true
} catch (e: Exception) {
    false
}

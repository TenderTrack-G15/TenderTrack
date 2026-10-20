package za.ac.tendertrack.ui.screens.phases

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.model.ContractPhase
import za.ac.tendertrack.data.model.PhaseState
import za.ac.tendertrack.data.model.TenderAlert
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/*
 * Pieces shared by the supplier's, the auditor's and the officer's phase
 * screens, built only from the existing components and the AppColor / AppType
 * / Dimens tokens, so they match the rest of the app.
 */

fun PhaseState.tone(): BadgeTone = when (this) {
    PhaseState.TO_DO -> BadgeTone.Neutral
    PhaseState.RETURNED -> BadgeTone.Danger
    PhaseState.AWAITING_SITE_CHECK -> BadgeTone.Warning
    PhaseState.AWAITING_OFFICER -> BadgeTone.Warning
    PhaseState.VERIFIED -> BadgeTone.Info
    PhaseState.PAYMENT_APPROVED -> BadgeTone.Success
}

/** The badge colour for an alert from the database ("danger", "warning", …). */
fun TenderAlert.badgeTone(): BadgeTone = when (tone) {
    "danger" -> BadgeTone.Danger
    "warning" -> BadgeTone.Warning
    "info" -> BadgeTone.Info
    "success" -> BadgeTone.Success
    else -> BadgeTone.Neutral
}

/** "R 1 795 000 · 10% of the contract" */
fun ContractPhase.valueLine(): String =
    Format.money(phaseValue) + (phaseShare?.let { " · ${Format.percent((it / 100).toFloat())} of the contract" } ?: "")

/**
 * A database error in one line a person can act on. Server errors carry the
 * URL and headers on later lines; only the first is shown.
 */
fun Throwable.phaseMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("mfa_required", true) || raw.contains("Two-factor sign-in required", true) ->
            "Two-factor sign-in is needed. Sign out, then sign in again with the code from your authenticator app."
        raw.contains("Could not find the function", true) || raw.contains("PGRST202") ->
            "The database has not been updated for photo proof yet. Run supabase/phase_proof.sql in Supabase."
        else -> friendlyMessage().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: "Something went wrong. Please try again."
    }
}

/**
 * The contract's five phases, one under the other: a tick when verified, a
 * padlock while an earlier phase is still open, otherwise the phase number.
 */
@Composable
fun PhaseList(
    phases: List<ContractPhase>,
    forOfficer: Boolean,
    selectedId: String? = null,
    onOpen: ((ContractPhase) -> Unit)? = null
) {
    AppCard {
        phases.sortedBy { it.phaseNo }.forEachIndexed { index, phase ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (onOpen != null) Modifier.clickable { onOpen(phase) } else Modifier)
                    .background(if (phase.id == selectedId) AppColor.SurfaceMuted else AppColor.Surface)
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PhaseMarker(phase)
                Column(Modifier.weight(1f)) {
                    Text(phase.phaseName, style = AppType.KvValue)
                    Text(
                        "${phase.valueLine()} · due ${Format.date(phase.targetDate)}",
                        style = AppType.Tiny.copy(color = AppColor.Muted)
                    )
                }
                if (phase.locked) {
                    StatusBadge("Locked", BadgeTone.Neutral, showDot = false)
                } else {
                    StatusBadge(
                        if (forOfficer) phase.state.officerLabel else phase.state.supplierLabel,
                        phase.state.tone(),
                        showDot = false
                    )
                }
            }
            if (index < phases.size - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(AppColor.SurfaceMuted))
            }
        }
    }
}

@Composable
private fun PhaseMarker(phase: ContractPhase) {
    val done = phase.state.isDone
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                when {
                    done -> AppColor.SuccessBg
                    phase.locked -> AppColor.SurfaceMuted
                    else -> AppColor.Ink
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            done -> AppIcon(Icons.Default.Check, tint = AppColor.SuccessInk, size = 16.dp)
            phase.locked -> AppIcon(Icons.Default.Lock, tint = AppColor.MutedLight, size = 14.dp)
            else -> Text("${phase.phaseNo}", style = AppType.Badge.copy(color = AppColor.OnInk))
        }
    }
}

/** The supplier's photo and the auditor's photo next to each other. */
@Composable
fun PhotoPair(phase: ContractPhase, showSitePhoto: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap), modifier = Modifier.fillMaxWidth()) {
        StoredPhotoFrame(
            label = "Supplier's photo",
            photoId = phase.supplierPhotoId,
            modifier = Modifier.weight(1f),
            emptyText = "Not taken yet"
        )
        if (showSitePhoto) {
            StoredPhotoFrame(
                label = "Auditor's site photo",
                photoId = phase.sitePhotoId,
                modifier = Modifier.weight(1f),
                emptyText = "Waiting for the site visit"
            )
        }
    }
}

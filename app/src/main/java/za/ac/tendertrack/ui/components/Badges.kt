package za.ac.tendertrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/** The five badge treatments used across the app. */
enum class BadgeTone { Success, Warning, Danger, Info, Neutral }

fun BadgeTone.background(): Color = when (this) {
    BadgeTone.Success -> AppColor.SuccessBg
    BadgeTone.Warning -> AppColor.WarnBg
    BadgeTone.Danger -> AppColor.DangerBg
    BadgeTone.Info -> AppColor.InfoBg
    BadgeTone.Neutral -> AppColor.NeutralBg
}

fun BadgeTone.foreground(): Color = when (this) {
    BadgeTone.Success -> AppColor.SuccessInk
    BadgeTone.Warning -> AppColor.WarnInk
    BadgeTone.Danger -> AppColor.DangerInk
    BadgeTone.Info -> AppColor.InfoInk
    BadgeTone.Neutral -> AppColor.NeutralInk
}

/** Rounded status pill. Used for tender status, severity and verification state. */
@Composable
fun StatusBadge(
    text: String,
    tone: BadgeTone,
    modifier: Modifier = Modifier,
    showDot: Boolean = true
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.RadiusPill))
            .background(tone.background())
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (showDot) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tone.foreground())
            )
        }
        Text(text, style = AppType.Badge.copy(color = tone.foreground()), maxLines = 1)
    }
}

/** Small count pill used on drawer rows. */
@Composable
fun CountPill(count: Int, tone: BadgeTone) {
    if (count <= 0) return
    Box(
        Modifier
            .clip(RoundedCornerShape(Dimens.RadiusPill))
            .background(tone.background())
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text("$count", style = AppType.Badge.copy(color = tone.foreground()))
    }
}

/** Circular initials avatar used in the drawer footer and user lists. */
@Composable
fun Avatar(initials: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 34.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(AppColor.Ink),
        contentAlignment = Alignment.Center
    ) {
        Text(initials.take(2).uppercase(), style = AppType.Badge.copy(color = AppColor.OnInk))
    }
}

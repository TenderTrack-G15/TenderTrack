package za.ac.tendertrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

enum class NoteTone { Neutral, Info, Warning, Danger, Success }

/**
 * The tinted explanatory strip used to state a rule or a consequence, e.g. that
 * an over-payment will be blocked. Keeps such messages visually distinct from
 * body copy on every screen.
 */
@Composable
fun NoteBanner(
    text: String,
    tone: NoteTone = NoteTone.Neutral,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Info,
    title: String? = null
) {
    val bg: Color
    val border: Color
    val ink: Color
    when (tone) {
        NoteTone.Neutral -> { bg = AppColor.NeutralBg; border = AppColor.Line; ink = AppColor.NeutralInk }
        NoteTone.Info -> { bg = AppColor.InfoSurface; border = AppColor.InfoBorder; ink = AppColor.InfoInk }
        NoteTone.Warning -> { bg = AppColor.WarnSurface; border = AppColor.WarnBorder; ink = AppColor.WarnInk }
        NoteTone.Danger -> { bg = AppColor.DangerSurface; border = AppColor.DangerBorder; ink = AppColor.DangerInk }
        NoteTone.Success -> { bg = Color(0xFFF0FDF4); border = Color(0xFFBBF7D0); ink = AppColor.SuccessInk }
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.RadiusField))
            .background(bg)
            .border(Dimens.BorderWidth, border, RoundedCornerShape(Dimens.RadiusField))
            .padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AppIcon(icon, tint = ink, size = 18.dp)
        Column {
            if (title != null) {
                Text(title, style = AppType.H2.copy(color = ink, fontSize = 15.sp))
                Spacer(Modifier.height(4.dp))
            }
            Text(text, style = AppType.Meta.copy(color = ink))
        }
    }
}

/** Full-screen spinner shown while a screen's first load is in flight. */
@Composable
fun LoadingState(modifier: Modifier = Modifier, message: String = "Loading…") {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd)
    ) {
        CircularProgressIndicator(color = AppColor.Ink, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
        Text(message, style = AppType.Meta)
    }
}

/** Shown when a load fails, with the reason and a way to try again. */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = Dimens.SpaceLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd)
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(Dimens.RadiusPill))
                .background(AppColor.DangerBg),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(Icons.Default.Warning, tint = AppColor.DangerBar, size = 28.dp)
        }
        Text("Something went wrong", style = AppType.H2)
        Text(message, style = AppType.Meta, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(Dimens.SpaceXs))
            SecondaryButton(
                text = "Try again",
                icon = Icons.Default.Refresh,
                modifier = Modifier.widthIn(max = 200.dp),
                onClick = onRetry
            )
        }
    }
}

/** Shown when a list legitimately has nothing in it. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = Dimens.SpaceLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(Dimens.RadiusPill))
                .background(AppColor.SurfaceMuted),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(icon, tint = AppColor.Muted, size = 26.dp)
        }
        Spacer(Modifier.height(Dimens.SpaceXs))
        Text(title, style = AppType.H2)
        Text(message, style = AppType.Meta, textAlign = TextAlign.Center)
    }
}

/** Section label above a group of tiles or cards. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = AppType.H2)
        trailing?.invoke()
    }
}

/** Uppercase group marker, e.g. "TENDER LIFECYCLE". */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = AppType.Eyebrow, modifier = modifier)
}

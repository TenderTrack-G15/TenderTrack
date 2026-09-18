package za.ac.tendertrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/**
 * Filled dark button — the single primary action on a screen.
 * Handles its own disabled and busy states so no screen re-implements them.
 */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    val active = enabled && !loading
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.ButtonHeight)
            .clip(RoundedCornerShape(Dimens.RadiusField))
            .background(if (active) AppColor.Ink else AppColor.LineStrong)
            .clickable(enabled = active) { onClick() }
            .padding(horizontal = Dimens.SpaceLg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = AppColor.OnInk,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(Dimens.SpaceSm))
        } else if (icon != null) {
            AppIcon(icon, tint = AppColor.OnInk)
            Spacer(Modifier.width(Dimens.SpaceSm))
        }
        Text(
            text = if (loading) "Please wait…" else text,
            style = AppType.Button.copy(color = AppColor.OnInk)
        )
    }
}

/** Outlined white button — secondary actions, and every action inside a card. */
@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    small: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val contentColor = when {
        !enabled -> AppColor.MutedLight
        danger -> AppColor.DangerInk
        else -> AppColor.Ink
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (small) Dimens.ButtonHeightSmall else Dimens.ButtonHeight)
            .clip(RoundedCornerShape(Dimens.RadiusField))
            .background(AppColor.Surface)
            .border(
                Dimens.BorderWidth,
                if (danger) AppColor.DangerBorder else AppColor.LineStrong,
                RoundedCornerShape(Dimens.RadiusField)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = Dimens.SpaceMd),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            AppIcon(icon, tint = contentColor, size = if (small) Dimens.IconSm else Dimens.IconMd)
            Spacer(Modifier.width(Dimens.SpaceSm))
        }
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = (if (small) AppType.ButtonSmall else AppType.Button).copy(color = contentColor)
        )
    }
}

/** Underlined text action, e.g. "Resend code". */
@Composable
fun TextAction(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppColor.Ink,
    onClick: () -> Unit
) {
    Text(
        text = text,
        style = AppType.Body.copy(
            color = color,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        ),
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Dimens.SpaceMd)
    )
}

/** Two buttons side by side, each taking half the width. */
@Composable
fun ButtonRow(
    left: @Composable RowScope.() -> Unit,
    right: @Composable RowScope.() -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMd)) {
        left()
        right()
    }
}

package za.ac.tendertrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/**
 * The white rounded container everything sits in. One definition means every
 * card in the app has the same corner radius, border and inner padding.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    background: Color = AppColor.Surface,
    border: Color = AppColor.Line,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Intrinsic height so the accent stripe can match the card's height.
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .background(background)
            .border(Dimens.BorderWidth, border, RoundedCornerShape(Dimens.RadiusCard))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(Dimens.CardPadding),
            content = content
        )
    }
}

/** Card header: a title and supporting line on the left, a badge on the right. */
@Composable
fun CardHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.CardTitle)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = AppType.Meta)
            }
        }
        trailing?.invoke()
    }
}

/**
 * Key/value row with a hairline under it — the detail pattern used on almost
 * every screen. [valueColor] lets a screen mark a value as good or overdue
 * without changing the layout.
 */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    valueColor: Color = AppColor.Ink,
    showDivider: Boolean = true
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
            verticalAlignment = Alignment.Top
        ) {
            Text(key, style = AppType.KvKey, modifier = Modifier.weight(1f))
            Text(
                value,
                style = AppType.KvValue.copy(color = valueColor),
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AppColor.SurfaceMuted)
            )
        }
    }
}

/** A stat tile in the two-column dashboard grid. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    footnote: String? = null,
    alert: Boolean = false,
    money: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    AppCard(
        modifier = modifier,
        background = if (alert) AppColor.DangerSurface else AppColor.Surface,
        border = if (alert) AppColor.DangerBorder else AppColor.Line,
        onClick = onClick
    ) {
        Text(
            label,
            style = AppType.StatLabel,
            minLines = 2,
            maxLines = 2
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = (if (money) AppType.StatValueMoney else AppType.StatValue)
                .copy(color = if (alert) AppColor.DangerInk else AppColor.Ink),
            maxLines = 1
        )
        if (footnote != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                footnote,
                style = AppType.StatFoot.copy(
                    color = if (alert) AppColor.DangerInk else AppColor.MutedLight
                ),
                maxLines = 2
            )
        }
    }
}

typealias StatGridTile = @Composable (Modifier) -> Unit

/**
 * The two-column tile grid. Takes a flat list and lays it out in pairs, which
 * avoids nesting a LazyVerticalGrid inside a scrolling column.
 */
@Composable
fun StatGrid(tiles: List<StatGridTile>) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                pair.forEach { tile ->
                    Box(modifier = Modifier.weight(1f)) {
                        tile(Modifier)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Horizontal progress bar used for fund utilisation and contract spend. */
@Composable
fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = AppColor.SuccessBar
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.ProgressHeight)
            .clip(RoundedCornerShape(Dimens.RadiusPill))
            .background(AppColor.Line)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(Dimens.RadiusPill))
                .background(color)
        )
    }
}

/** Progress bar with a heading, a percentage and an explanatory line. */
@Composable
fun UtilisationCard(
    title: String,
    caption: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    barColor: Color = AppColor.SuccessBar
) {
    AppCard(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(title, style = AppType.H2)
            Text(
                "${(fraction * 100).toInt()}%",
                style = AppType.KvValue.copy(color = AppColor.Muted)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(caption, style = AppType.Hint)
        Spacer(Modifier.height(11.dp))
        ProgressBar(fraction, color = barColor)
    }
}

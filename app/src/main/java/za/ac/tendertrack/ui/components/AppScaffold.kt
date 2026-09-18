package za.ac.tendertrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/** An icon button in the app bar. */
data class TopBarAction(
    val icon: ImageVector,
    val contentDescription: String,
    val badgeCount: Int = 0,
    val onClick: () -> Unit
)

/**
 * The frame every screen sits in: fixed-height app bar, centred title, an
 * optional back arrow or menu button on the left, and actions on the right.
 *
 * Because all screens use this, the title position, bar height and gutters are
 * identical throughout the app.
 */
@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    actions: List<TopBarAction> = emptyList(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        modifier = modifier,
        containerColor = AppColor.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { AppTopBar(title, onBack, onMenu, actions) }
    ) { padding ->
        val base = Modifier
            .fillMaxSize()
            .padding(padding)
        Column(
            modifier = if (scrollable) base.verticalScroll(rememberScrollState()) else base
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Dimens.ScreenPadding,
                        end = Dimens.ScreenPadding,
                        top = Dimens.SpaceXl,
                        bottom = Dimens.SpaceXxl
                    ),
                verticalArrangement = Arrangement.spacedBy(Dimens.SectionGap),
                content = content
            )
        }
    }
}

@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    actions: List<TopBarAction> = emptyList()
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(AppColor.Surface)
            .height(Dimens.TopBarHeight)
    ) {
        Text(
            title,
            style = AppType.TopBarTitle,
            modifier = Modifier.align(Alignment.Center)
        )
        if (onMenu != null) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = Dimens.SpaceLg)
                    .clickable { onMenu() }
                    .padding(Dimens.SpaceXs)
            ) {
                AppIcon(Icons.Default.Menu, tint = AppColor.Ink, size = 22.dp, contentDescription = "Open navigation")
            }
        } else if (onBack != null) {
            Row(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = Dimens.SpaceMd)
                    .clickable { onBack() }
                    .padding(Dimens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    tint = AppColor.Ink,
                    size = 22.dp,
                    contentDescription = "Back"
                )
                Spacer(Modifier.width(6.dp))
                Text("Back", style = AppType.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
            }
        }
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = Dimens.SpaceLg),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceLg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { action ->
                Box(Modifier.clickable { action.onClick() }.padding(Dimens.SpaceXs)) {
                    AppIcon(
                        action.icon,
                        tint = AppColor.Ink,
                        size = 22.dp,
                        contentDescription = action.contentDescription
                    )
                    if (action.badgeCount > 0) {
                        Box(Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp)) {
                            CountPill(action.badgeCount, BadgeTone.Danger)
                        }
                    }
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(AppColor.Line)
        )
    }
}

/** Screen title block: big heading with an optional supporting line under it. */
@Composable
fun ScreenHeading(
    title: String,
    subtitle: String? = null,
    eyebrow: String? = null,
    small: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        if (eyebrow != null) {
            Eyebrow(eyebrow)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = if (small) AppType.H1Small else AppType.H1,
                modifier = Modifier.weight(1f, fill = false)
            )
            trailing?.invoke()
        }
        if (subtitle != null) {
            Spacer(Modifier.height(5.dp))
            Text(subtitle, style = AppType.Meta)
        }
    }
}

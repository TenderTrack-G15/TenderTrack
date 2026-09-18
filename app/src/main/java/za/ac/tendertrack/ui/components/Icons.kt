package za.ac.tendertrack.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.Dimens

/**
 * Every icon in the app goes through here, so icon size and tint are consistent
 * rather than being set ad hoc at each call site.
 */
@Composable
fun AppIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = AppColor.Muted,
    size: Dp = Dimens.IconMd,
    contentDescription: String? = null
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

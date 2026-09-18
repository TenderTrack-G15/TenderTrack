package za.ac.tendertrack.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle

private val TenderTrackColors = lightColorScheme(
    primary = AppColor.Ink,
    onPrimary = AppColor.OnInk,
    background = AppColor.Background,
    onBackground = AppColor.Ink,
    surface = AppColor.Surface,
    onSurface = AppColor.Ink,
    surfaceVariant = AppColor.SurfaceMuted,
    onSurfaceVariant = AppColor.Muted,
    outline = AppColor.Line,
    error = AppColor.DangerBar,
    onError = AppColor.OnInk
)

/**
 * Material is used only for structure (Scaffold, drawer, snackbar). All visual
 * styling comes from [AppColor], [AppType] and [Dimens], which is why the app
 * looks the same as the wireframes rather than like stock Material.
 */
@Composable
fun TenderTrackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TenderTrackColors,
        typography = MaterialTheme.typography.copy(
            bodyLarge = AppType.Body,
            bodyMedium = AppType.Meta,
            titleMedium = AppType.H2,
            labelLarge = TextStyle(fontFamily = Inter)
        ),
        content = content
    )
}

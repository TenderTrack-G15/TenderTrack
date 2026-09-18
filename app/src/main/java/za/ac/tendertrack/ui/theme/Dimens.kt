package za.ac.tendertrack.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing, sizing and radius tokens.
 *
 * Screens use these rather than literal dp values, which is what keeps padding,
 * gaps and control heights identical from one screen to the next.
 */
object Dimens {

    // Spacing scale ----------------------------------------------------------
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 20.dp
    val SpaceXxl = 28.dp

    /** Left and right gutter on every screen. */
    val ScreenPadding = 20.dp

    /** Vertical gap between the major blocks of a screen. */
    val SectionGap = 18.dp

    /** Gap between sibling cards in a list. */
    val ListGap = 12.dp

    /** Gap between the two columns of the stat grid. */
    val GridGap = 12.dp

    // Control sizing ---------------------------------------------------------
    val FieldHeight = 52.dp
    val ButtonHeight = 52.dp
    val ButtonHeightSmall = 42.dp
    val SearchHeight = 48.dp
    val TopBarHeight = 60.dp
    val IconSm = 16.dp
    val IconMd = 20.dp
    val IconLg = 24.dp

    // Radii ------------------------------------------------------------------
    val RadiusField = 10.dp
    val RadiusCard = 12.dp
    val RadiusPill = 999.dp

    // Misc -------------------------------------------------------------------
    val BorderWidth = 1.dp
    val CardPadding = 16.dp
    val ProgressHeight = 9.dp
    val StepPip = 22.dp
}

package za.ac.tendertrack.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for colour in the app.
 *
 * Every value here is lifted directly from the approved wireframes, so a screen
 * never invents its own colour. If a colour is not in this file it does not
 * belong on screen.
 */
object AppColor {

    // Surfaces ---------------------------------------------------------------
    val Background = Color(0xFFF8FAFC)   // page behind the cards
    val Surface = Color(0xFFFFFFFF)      // cards, app bar, drawer
    val SurfaceMuted = Color(0xFFF1F5F9) // selected drawer row, inset panels

    // Lines ------------------------------------------------------------------
    val Line = Color(0xFFE2E8F0)         // card and input borders
    val LineStrong = Color(0xFFCBD5E1)   // secondary button borders, dividers

    // Text -------------------------------------------------------------------
    val Ink = Color(0xFF0F172A)          // headings, primary buttons, body emphasis
    val InkSoft = Color(0xFF334155)      // field labels
    val Muted = Color(0xFF64748B)        // supporting copy
    val MutedLight = Color(0xFF94A3B8)   // placeholders, footnotes
    val OnInk = Color(0xFFFFFFFF)        // text on the primary button

    // Status: success / verified / completed ----------------------------------
    val SuccessBg = Color(0xFFDCFCE7)
    val SuccessInk = Color(0xFF15803D)
    val SuccessBar = Color(0xFF16A34A)

    // Status: warning / awaiting / closing soon --------------------------------
    val WarnBg = Color(0xFFFEF3C7)
    val WarnInk = Color(0xFFB45309)
    val WarnBar = Color(0xFFD97706)
    val WarnSurface = Color(0xFFFFFBEB)
    val WarnBorder = Color(0xFFFDE68A)

    // Status: danger / flagged / not approved ----------------------------------
    val DangerBg = Color(0xFFFEE2E2)
    val DangerInk = Color(0xFFB91C1C)
    val DangerBar = Color(0xFFDC2626)
    val DangerSurface = Color(0xFFFEF2F2)
    val DangerBorder = Color(0xFFFECACA)

    // Status: informational / in progress --------------------------------------
    val InfoBg = Color(0xFFE0E7FF)
    val InfoInk = Color(0xFF4338CA)
    val InfoSurface = Color(0xFFEEF2FF)
    val InfoBorder = Color(0xFFC7D2FE)

    // Status: neutral ----------------------------------------------------------
    val NeutralBg = Color(0xFFF1F5F9)
    val NeutralInk = Color(0xFF475569)
}

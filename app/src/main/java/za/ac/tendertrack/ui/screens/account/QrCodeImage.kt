package za.ac.tendertrack.ui.screens.account

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The QR code Supabase sends when an authenticator is set up, drawn in the
 * app so it can be scanned with a phone. Useful when TenderTrack runs on an
 * emulator, or on a different phone from the one with the authenticator app:
 * scanning avoids typing the 32-character key.
 *
 * Supabase sends the QR code as an SVG picture made of one small square per
 * dark or light block. Compose cannot show SVG by itself, so the squares are
 * read from the SVG and drawn directly. If the picture cannot be read, the
 * screen simply shows the set-up key without a QR code.
 */
object QrSvg {

    data class Cell(val x: Float, val y: Float, val width: Float, val height: Float)

    /** The dark squares and the size of the whole picture, in the SVG's own units. */
    data class Picture(val size: Float, val dark: List<Cell>)

    private val rectTag = Regex("<rect\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val svgTag = Regex("<svg\\b([^>]*)>", RegexOption.IGNORE_CASE)

    private fun attribute(attributes: String, name: String): String? =
        Regex("(?:^|\\s)$name\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(attributes)?.groupValues?.get(1)

    private fun number(value: String?): Float? = value?.trim()?.removeSuffix("px")?.toFloatOrNull()

    private fun isDark(attributes: String): Boolean {
        val paint = listOfNotNull(attribute(attributes, "style"), attribute(attributes, "fill"))
            .joinToString(";").lowercase().replace(" ", "")
        if (paint.isEmpty()) return "bg" !in (attribute(attributes, "class") ?: "")   // unstyled squares are black
        return "black" in paint || "#000" in paint || "rgb(0,0,0)" in paint
    }

    fun parse(svg: String): Picture? {
        if (svg.isBlank()) return null
        val dark = mutableListOf<Cell>()
        var right = 0f
        var bottom = 0f
        for (match in rectTag.findAll(svg)) {
            val attributes = match.groupValues[1]
            val x = number(attribute(attributes, "x")) ?: 0f
            val y = number(attribute(attributes, "y")) ?: 0f
            val w = number(attribute(attributes, "width")) ?: continue
            val h = number(attribute(attributes, "height")) ?: continue
            right = maxOf(right, x + w)
            bottom = maxOf(bottom, y + h)
            if (isDark(attributes)) dark += Cell(x, y, w, h)
        }
        val svgAttributes = svgTag.find(svg)?.groupValues?.get(1).orEmpty()
        val declared = maxOf(number(attribute(svgAttributes, "width")) ?: 0f, number(attribute(svgAttributes, "height")) ?: 0f)
        val size = maxOf(declared, right, bottom)
        // A QR code has at least 21 x 21 blocks; anything much smaller is not one.
        if (size <= 0f || dark.size < 50) return null
        return Picture(size, dark)
    }
}

/** Draws a QR code read with [QrSvg.parse]. */
@Composable
fun QrCodeImage(picture: QrSvg.Picture, modifier: Modifier = Modifier, side: Dp = 240.dp) {
    Canvas(
        modifier
            .size(side)
            .background(Color.White)
            .padding(8.dp)
    ) {
        val scale = size.width / picture.size
        picture.dark.forEach { cell ->
            // Supabase draws its QR code mirrored across the diagonal (rows and columns
            // swapped). Most authenticator apps read it anyway; swapping them back here
            // gives the standard picture, which every scanner reads.
            // Each block is drawn a hair larger, so no light lines show between neighbours.
            drawRect(
                color = Color.Black,
                topLeft = Offset(cell.y * scale, cell.x * scale),
                size = Size(cell.height * scale + 0.6f, cell.width * scale + 0.6f)
            )
        }
    }
}

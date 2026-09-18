package za.ac.tendertrack.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import za.ac.tendertrack.R

/** Inter, bundled in res/font so the app renders identically on every device. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold)
)

/**
 * The type scale. Screens reference these named styles instead of setting
 * fontSize inline, so headings and body copy stay the same size everywhere.
 */
object AppType {

    /** Screen title, e.g. "Dashboard". */
    val H1 = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp, lineHeight = 31.sp, letterSpacing = (-0.6).sp,
        color = AppColor.Ink
    )

    /** Slightly smaller screen title, used where a reference number sits above it. */
    val H1Small = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp,
        color = AppColor.Ink
    )

    /** Card and section heading. */
    val H2 = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp,
        color = AppColor.Ink
    )

    /** Centred title in the app bar. */
    val TopBarTitle = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = 17.sp, lineHeight = 22.sp, color = AppColor.Ink
    )

    /** Name line inside a list card. */
    val CardTitle = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = 15.sp, lineHeight = 20.sp, color = AppColor.Ink
    )

    /** Default body copy. */
    val Body = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp, color = AppColor.Ink
    )

    /** Supporting copy under a heading or card title. */
    val Meta = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 19.sp, color = AppColor.Muted
    )

    /** Field label above an input. */
    val Label = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 17.sp, color = AppColor.InkSoft
    )

    /** Helper text under an input. */
    val Hint = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 17.sp, color = AppColor.Muted
    )

    /** Text typed into an input. */
    val FieldValue = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp, color = AppColor.Ink
    )

    /** Primary button label. */
    val Button = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = 15.sp, lineHeight = 20.sp
    )

    val ButtonSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 18.sp
    )

    /** Large number on a stat tile. */
    val StatValue = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-1).sp,
        color = AppColor.Ink
    )

    /** Money value on a stat tile — smaller so "R 248.0 m" fits on one line. */
    val StatValueMoney = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.ExtraBold,
        fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = (-0.6).sp,
        color = AppColor.Ink
    )

    val StatLabel = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, color = AppColor.Muted
    )

    val StatFoot = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 15.sp, color = AppColor.MutedLight
    )

    /** Uppercase section marker, e.g. "TENDER LIFECYCLE". */
    val Eyebrow = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.9.sp,
        color = AppColor.Muted
    )

    /** Status pill text. */
    val Badge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.1.sp
    )

    /** Smallest supporting text — timestamps, references, disclaimers. */
    val Tiny = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp, lineHeight = 17.sp, color = AppColor.MutedLight
    )

    /** Key in a key/value row. */
    val KvKey = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp, color = AppColor.Muted
    )

    /** Value in a key/value row. */
    val KvValue = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 18.sp, color = AppColor.Ink
    )
}

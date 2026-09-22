package za.ac.tendertrack.core

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * All money, date and percentage formatting lives here so a rand amount reads
 * the same on every screen.
 */
object Format {
    private val months = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    /** Full amount with thin spaces: R 17 950 000. */
    fun money(amount: Double): String {
        val rounded = amount.roundToLong()
        val grouped = abs(rounded).toString()
            .reversed()
            .chunked(3)
            .joinToString(" ")
            .reversed()
        return if (rounded < 0) "-R $grouped" else "R $grouped"
    }

    /** Compact amount for stat tiles: R 1.24 bn, R 812.5 m, R 45.0 k. */
    fun moneyCompact(amount: Double): String = when {
        abs(amount) >= 1_000_000_000 -> "R ${trim(amount / 1_000_000_000, 2)} bn"
        abs(amount) >= 1_000_000 -> "R ${trim(amount / 1_000_000, 1)} m"
        abs(amount) >= 1_000 -> "R ${trim(amount / 1_000, 1)} k"
        else -> money(amount)
    }

    private fun trim(value: Double, decimals: Int): String {
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        val rounded = (value * factor).roundToLong() / factor
        return if (decimals == 0) rounded.toLong().toString()
        else {
            val whole = rounded.toLong()
            val frac = ((abs(rounded) - abs(whole)) * factor).roundToLong()
            "$whole.${frac.toString().padStart(decimals, '0')}"
        }
    }

    fun percent(fraction: Float, decimals: Int = 0): String =
        if (decimals == 0) "${(fraction * 100).roundToLong()}%"
        else "${trim((fraction * 100).toDouble(), decimals)}%"

    /** 18 Sep 2026 */
    fun date(iso: String?): String {
        val dt = parse(iso) ?: return "—"
        return "${dt.dayOfMonth.toString().padStart(2, '0')} ${months[dt.monthNumber - 1]} ${dt.year}"
    }

    /** 18 Sep 2026 · 11:00 */
    fun dateTime(iso: String?): String {
        val dt = parse(iso) ?: return "—"
        val time = "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
        return "${date(iso)} · $time"
    }

    /** ISO date used when writing to the database. */
    fun isoDate(iso: String?): String {
        val dt = parse(iso) ?: return ""
        return "${dt.year}-${dt.monthNumber.toString().padStart(2, '0')}-${dt.dayOfMonth.toString().padStart(2, '0')}"
    }

    /** Whole days from now until [iso]. Negative once the date has passed. */
    fun daysUntil(iso: String?): Int? {
        val instant = parseInstant(iso) ?: return null
        val diff = instant - Clock.System.now()
        return diff.inWholeDays.toInt()
    }

    fun daysSince(iso: String?): Int? {
        val instant = parseInstant(iso) ?: return null
        val diff = Clock.System.now() - instant
        return diff.inWholeDays.toInt()
    }

    /** "14 days left" / "Closed 3 days ago" — used on tender cards. */
    fun closingLabel(iso: String?): String {
        val days = daysUntil(iso) ?: return "—"
        return when {
            days > 1 -> "$days days"
            days == 1 -> "1 day"
            days == 0 -> "Closes today"
            else -> "Closed"
        }
    }

    private fun parseInstant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(if (iso.length == 10) "${iso}T00:00:00Z" else iso)
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(iso: String?) =
        parseInstant(iso)?.toLocalDateTime(TimeZone.currentSystemDefault())

    fun nowIso(): String = Clock.System.now().toString()
}

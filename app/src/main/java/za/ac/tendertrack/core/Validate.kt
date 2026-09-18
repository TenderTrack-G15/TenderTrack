package za.ac.tendertrack.core

/**
 * Form validation rules, kept out of the screens so the same rule cannot be
 * written two different ways on two different forms.
 */
object Validate {

    fun required(value: String, field: String): String? =
        if (value.isBlank()) "$field is required" else null

    fun email(value: String): String? = when {
        value.isBlank() -> "Email address is required"
        !value.contains("@") || !value.substringAfter("@").contains(".") ->
            "Enter a valid email address"
        else -> null
    }

    fun password(value: String): String? = when {
        value.isBlank() -> "Password is required"
        value.length < 8 -> "Password must be at least 8 characters"
        else -> null
    }

    /** Accepts "18 400 000", "18400000.50" and "R 18 400 000". */
    fun parseAmount(value: String): Double? =
        value.replace("R", "", ignoreCase = true)
            .replace(" ", "")
            .replace(",", "")
            .trim()
            .toDoubleOrNull()

    fun amount(value: String, field: String, allowZero: Boolean = false): String? {
        if (value.isBlank()) return "$field is required"
        val parsed = parseAmount(value) ?: return "$field must be a number"
        if (parsed < 0) return "$field cannot be negative"
        if (!allowZero && parsed == 0.0) return "$field must be more than zero"
        return null
    }

    /** Expects dd/mm/yyyy, which is how the date fields are labelled. */
    fun date(value: String, field: String): String? {
        if (value.isBlank()) return "$field is required"
        val parts = value.split("/")
        if (parts.size != 3) return "$field must be in dd/mm/yyyy format"
        val day = parts[0].toIntOrNull()
        val month = parts[1].toIntOrNull()
        val year = parts[2].toIntOrNull()
        if (day == null || month == null || year == null) return "$field must be in dd/mm/yyyy format"
        if (day !in 1..31 || month !in 1..12 || year !in 2000..2100) return "$field is not a valid date"
        return null
    }

    fun time(value: String, field: String): String? {
        if (value.isBlank()) return "$field is required"
        val parts = value.split(":")
        if (parts.size != 2) return "$field must be in HH:mm format"
        val hour = parts[0].toIntOrNull()
        val minute = parts[1].toIntOrNull()
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59)
            return "$field is not a valid time"
        return null
    }

    /** Converts dd/mm/yyyy (+ optional HH:mm) into the ISO string the API wants. */
    fun toIso(date: String, time: String = "00:00"): String {
        val parts = date.split("/")
        if (parts.size != 3) return ""
        val day = parts[0].padStart(2, '0')
        val month = parts[1].padStart(2, '0')
        val year = parts[2]
        val t = if (time.isBlank()) "00:00" else time
        val timeParts = t.split(":")
        val hh = timeParts.getOrElse(0) { "00" }.padStart(2, '0')
        val mm = timeParts.getOrElse(1) { "00" }.padStart(2, '0')
        return "$year-$month-${day}T$hh:$mm:00Z"
    }

    /** Converts dd/mm/yyyy into a bare ISO date, for date (not timestamp) columns. */
    fun isoDateOnly(date: String): String {
        val parts = date.split("/")
        if (parts.size != 3) return ""
        return "${parts[2]}-${parts[1].padStart(2, '0')}-${parts[0].padStart(2, '0')}"
    }

    fun integer(value: String, field: String): String? {
        if (value.isBlank()) return "$field is required"
        val parsed = value.trim().toIntOrNull() ?: return "$field must be a whole number"
        if (parsed <= 0) return "$field must be more than zero"
        return null
    }
}

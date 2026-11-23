package app.steady.ui

import java.time.Duration
import java.time.LocalTime

/** Small, locale-light formatters matching the prototype copy ("4–7 pm", "Mon–Fri", "in 12 min"). */
object Format {
    private val dayShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun dayName(isoDay: Int): String = dayShort[(isoDay - 1).coerceIn(0, 6)]

    /** 16:00 -> "4 pm", 16:30 -> "4:30 pm", 00:00 -> "12 am". */
    fun time(t: LocalTime, withSuffix: Boolean = true): String {
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        val base = if (t.minute == 0) "$h12" else "$h12:${t.minute.toString().padStart(2, '0')}"
        return if (withSuffix) "$base ${suffix(t)}" else base
    }

    private fun suffix(t: LocalTime) = if (t.hour < 12) "am" else "pm"

    /** "4–7 pm" when both share am/pm, else "11 am–1 pm". */
    fun timeRange(start: LocalTime, end: LocalTime): String =
        if (suffix(start) == suffix(end) && end.isAfter(start)) "${time(start, false)}–${time(end)}"
        else "${time(start)}–${time(end)}"

    fun timeRange(start: String, end: String): String = timeRange(LocalTime.parse(start), LocalTime.parse(end))

    /** {1..7} -> "daily", {1..5} -> "Mon–Fri", {6,7} -> "weekends", contiguous -> "Tue–Thu", else "Mon, Wed". */
    fun days(days: Set<Int>): String {
        val sorted = days.filter { it in 1..7 }.sorted()
        return when {
            sorted.isEmpty() -> "no days"
            sorted.size == 7 -> "daily"
            sorted == listOf(6, 7) -> "weekends"
            sorted.size >= 3 && sorted.zipWithNext().all { (a, b) -> b == a + 1 } ->
                "${dayName(sorted.first())}–${dayName(sorted.last())}"
            else -> sorted.joinToString(", ") { dayName(it) }
        }
    }

    /** Countdown text: "now", "in 12 min", "in 1 h 5 min", "in 3 h". */
    fun countdown(d: Duration): String {
        val totalMin = (d.seconds + 59) / 60 // round up so "in 0 min" never shows
        if (d.isNegative || d.isZero) return "now"
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "in $m min"
            m == 0L -> "in $h h"
            else -> "in $h h $m min"
        }
    }
}

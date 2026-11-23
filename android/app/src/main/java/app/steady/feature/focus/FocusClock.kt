package app.steady.feature.focus

import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/** Pure countdown math and formatting for the focus ring. */
object FocusClock {
    /** Whole seconds until [endsAt], rounded up so the ring shows 00:00 only when time is really up. */
    fun remainingSeconds(now: Instant, endsAt: Instant): Long {
        val ms = Duration.between(now, endsAt).toMillis()
        return if (ms <= 0) 0 else (ms + 999) / 1000
    }

    /** Fraction of the ring still filled, 1 = just started, 0 = done. */
    fun fraction(remainingSeconds: Long, totalSeconds: Long): Float =
        if (totalSeconds <= 0) 0f else (remainingSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)

    /** "15:30", "05:03", "00:00"; with hours "2:44:00". */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val mmss = "${pad(m)}:${pad(sec)}"
        return if (h > 0) "$h:$mmss" else mmss
    }

    /** Block length for "left of …": "25 min", "3 h", "2 h 30 min" (rounded to the nearest minute). */
    fun durationLabel(seconds: Long): String {
        val totalMin = ((seconds.coerceAtLeast(0) + 30) / 60)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "$totalMin min"
            m == 0L -> "$h h"
            else -> "$h h $m min"
        }
    }

    /** Minutes left for screen readers, rounded up ("1 min" until the very end). */
    fun spokenRemaining(seconds: Long): String = durationLabel(((seconds.coerceAtLeast(0) + 59) / 60) * 60)

    /** Wall-clock time as in the prototype pill: "6:00 pm", "12:30 am". */
    fun wallTime(t: LocalTime): String {
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        return "$h12:${pad(t.minute.toLong())} ${if (t.hour < 12) "am" else "pm"}"
    }

    private fun pad(v: Long) = v.toString().padStart(2, '0')
}

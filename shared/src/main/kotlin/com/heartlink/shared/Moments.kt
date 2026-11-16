package com.heartlink.shared

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Times when heartbeats and messages arrive silently. Minutes are minutes after midnight in the
 * person's own time zone; the window may cross midnight (23:00 → 07:00).
 */
data class QuietHours(
    val enabled: Boolean = true,
    val startMinute: Int = 23 * 60,
    val endMinute: Int = 7 * 60,
    /** Let things through while the person is actively using their phone, even in quiet hours. */
    val allowWhenOnline: Boolean = true,
) {
    fun contains(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }
}

/**
 * Whether something arriving now should be silent. Quiet if the watch says they're asleep or
 * the clock is inside their quiet hours, unless they're online and allow that.
 */
fun isQuiet(quiet: QuietHours?, timeZone: String?, now: Long, asleep: Boolean, online: Boolean): Boolean {
    if (quiet == null || !quiet.enabled) return false
    if (quiet.allowWhenOnline && online) return false
    if (asleep) return true
    return quiet.contains(minuteOfDay(timeZone, now))
}

private fun minuteOfDay(timeZone: String?, at: Long): Int {
    val zone = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
    return time.hour * 60 + time.minute
}

/** "23:00" / "11:00 PM", following this device's 12/24-hour style. */
fun minuteOfDayText(minute: Int): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(LocalTime.of(minute / 60 % 24, minute % 60))

/** A short status like "Home safe 🏠", shown on the partner's card for a few hours. */
data class CheckIn(val text: String, val timestamp: Long) {
    fun isVisible(now: Long): Boolean = now - timestamp in 0..CheckIns.SHOW_FOR_MS
}

object CheckIns {
    const val HOME = "Home safe 🏠"
    const val MAX_LENGTH = 40
    val SHOW_FOR_MS: Long = TimeUnit.HOURS.toMillis(3)

    /** Don't announce arriving home more than once in this window (GPS jitter at the boundary). */
    val HOME_COOLDOWN_MS: Long = TimeUnit.MINUTES.toMillis(30)
}

/** "Together again in 23 days 💞", or null once the date has passed. */
fun countdownText(date: LocalDate, today: LocalDate): String? {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days < 0 -> null
        days == 0L -> "Together today! 💞"
        days == 1L -> "Together tomorrow 💞"
        else -> "Together again in $days days 💞"
    }
}

object Moments {
    /** Only announce a wake/sleep change this fresh, so restarts don't replay old ones. */
    val FRESH_MS: Long = TimeUnit.MINUTES.toMillis(30)
}

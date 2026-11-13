package com.heartlink.shared

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** What a person's devices report about them (stored at /couples/{id}/presence/{role}). */
data class Presence(
    /** True while their phone holds a live connection; Firebase flips it off when the connection drops. */
    val connected: Boolean = false,
    /** Last time we heard from them at all (server time). */
    val lastSeen: Long? = null,
    /** Last screen-on / unlock / app use. */
    val lastActive: Long? = null,
    /** Set by their watch from Wear OS sleep detection. */
    val asleep: Boolean = false,
    val asleepAt: Long? = null,
    /** IANA time zone, e.g. "Europe/London". */
    val timeZone: String? = null,
    val phoneBattery: BatteryState? = null,
    val watchBattery: BatteryState? = null,
    /** Last automatic or manual check-in, e.g. arriving home. */
    val checkIn: CheckIn? = null,
    val quietHours: QuietHours? = null,
)

enum class PartnerStatus(val label: String) {
    Online("online"),
    Inactive("inactive"),
    Away("away"),
    Asleep("asleep"),
    NoConnection("no connection"),
    Unknown(""),
}

object StatusRules {
    /** No phone/screen activity for this long → inactive. */
    val INACTIVE_AFTER_MS: Long = TimeUnit.MINUTES.toMillis(10)

    /** No phone/screen activity for this long → away. */
    val AWAY_AFTER_MS: Long = TimeUnit.HOURS.toMillis(1)

    /** A watch upload this recent proves the person is reachable even if their phone isn't. */
    val RECENT_BEAT_MS: Long = TimeUnit.MINUTES.toMillis(3)

    /** Ignore a sleep flag older than this (a watch that died while they slept). */
    val ASLEEP_STALE_MS: Long = TimeUnit.HOURS.toMillis(14)
}

/**
 * no connection – can't reach any of their devices (phone disconnected, no recent watch reading)
 * asleep        – their watch says they're asleep
 * away          – reachable, but no sign of use for over an hour
 * inactive      – reachable, but no sign of use for 10 minutes
 * online        – reachable and recently active
 */
fun partnerStatus(presence: Presence?, lastBeat: Reading?, now: Long): PartnerStatus {
    val recentBeat = lastBeat != null && lastBeat.source != Sources.TEST &&
        now - lastBeat.timestamp < StatusRules.RECENT_BEAT_MS
    if (presence == null) return if (recentBeat) PartnerStatus.Online else PartnerStatus.Unknown
    if (!presence.connected && !recentBeat) return PartnerStatus.NoConnection
    val asleepAt = presence.asleepAt
    if (presence.asleep && asleepAt != null && now - asleepAt < StatusRules.ASLEEP_STALE_MS) return PartnerStatus.Asleep
    // A watch reading only shows they're wearing it, not that they're using anything.
    val lastActive = presence.lastActive ?: 0L
    val idle = now - lastActive
    return when {
        idle > StatusRules.AWAY_AFTER_MS -> PartnerStatus.Away
        idle > StatusRules.INACTIVE_AFTER_MS -> PartnerStatus.Inactive
        else -> PartnerStatus.Online
    }
}

/**
 * When we last heard from them: the later of their phone's last contact and their last real
 * heartbeat. For "no connection", this is roughly when the connection dropped.
 */
fun lastHeardFrom(presence: Presence?, lastBeat: Reading?): Long? {
    val beatAt = lastBeat?.takeIf { it.source != Sources.TEST }?.timestamp
    return listOfNotNull(presence?.lastSeen, beatAt).maxOrNull()
}

/** "no connection · 2h 14m", or just the plain label for every other status. */
fun statusLabel(status: PartnerStatus, presence: Presence?, lastBeat: Reading?, now: Long): String {
    if (status != PartnerStatus.NoConnection) return status.label
    val since = lastHeardFrom(presence, lastBeat) ?: return status.label
    return "${status.label} · ${durationText(now - since)}"
}

/** Compact elapsed time: "<1m", "45m", "2h 14m", "3d 5h". */
fun durationText(ms: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms.coerceAtLeast(0))
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "<1m"
        hours < 1 -> "${minutes}m"
        days < 1 -> if (minutes % 60 == 0L) "${hours}h" else "${hours}h ${minutes % 60}m"
        else -> if (hours % 24 == 0L) "${days}d" else "${days}d ${hours % 24}h"
    }
}

/** "21:14", in the partner's time zone, using this device's 12/24 h style. Null if unknown. */
fun localTimeText(timeZone: String?, now: Long): String? {
    val zone = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
    return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(now).atZone(zone))
}

/** "+5h", "−3h 30m" relative to this device, or null when it's the same. */
fun timeDifferenceText(timeZone: String?, now: Long, localZone: ZoneId = ZoneId.systemDefault()): String? {
    val zone = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
    val instant = Instant.ofEpochMilli(now)
    val diffSeconds = zone.rules.getOffset(instant).totalSeconds - localZone.rules.getOffset(instant).totalSeconds
    if (diffSeconds == 0) return null
    val sign = if (diffSeconds > 0) "+" else "−"
    val hours = abs(diffSeconds) / 3600
    val minutes = (abs(diffSeconds) % 3600) / 60
    return if (minutes == 0) "$sign${hours}h" else "$sign${hours}h ${minutes}m"
}

/** A short message from the partner, waiting in this person's inbox. */
data class Message(val id: String, val text: String, val fromName: String?, val timestamp: Long)

/** A request to feel someone's heartbeat: [bpm] is the sender's last known heart rate. */
data class Buzz(val bpm: Int, val timestamp: Long, val fromName: String?)

object Messages {
    const val MAX_LENGTH = 200

    val quickReplies = listOf("Thinking of you 💗", "Miss you", "Goodnight 🌙", "Good morning ☀️", "Call me?")

    fun clean(input: String): String = input.trim().take(MAX_LENGTH)
}

object Vibration {
    private const val LUB_MS = 70L
    private const val GAP_MS = 110L
    private const val DUB_MS = 50L
    const val DEFAULT_BEATS = 8

    /**
     * Waveform for [beats] "lub-dub" heartbeats at [bpm], for VibrationEffect.createWaveform.
     * Timings alternate off/on, starting with "off".
     */
    fun heartbeatWaveform(bpm: Int, beats: Int = DEFAULT_BEATS): Pair<LongArray, IntArray> {
        val period = beatPeriodMs(bpm).toLong()
        val rest = (period - LUB_MS - GAP_MS - DUB_MS).coerceAtLeast(60L)
        val timings = ArrayList<Long>()
        val amplitudes = ArrayList<Int>()
        repeat(beats) { i ->
            timings += if (i == 0) 0L else rest; amplitudes += 0
            timings += LUB_MS; amplitudes += 255
            timings += GAP_MS; amplitudes += 0
            timings += DUB_MS; amplitudes += 160
        }
        return timings.toLongArray() to amplitudes.toIntArray()
    }
}

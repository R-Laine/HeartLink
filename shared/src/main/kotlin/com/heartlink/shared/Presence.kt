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
    /** Last direct use of their phone or watch: unlocked phone, the watch app, a charging cable (see [ActivityModel]). */
    val lastActive: Long? = null,
    /** Last time their phone saw them on the move (walking, cycling, in a vehicle). */
    val movingAt: Long? = null,
    /** Last time they were on a call (reported every 5 minutes during one). Not counted as use. */
    val callAt: Long? = null,
    /** Set by their watch from Wear OS sleep detection. */
    val asleep: Boolean = false,
    val asleepAt: Long? = null,
    /** When their current stretch of phone use started, if it started while the watch said asleep. */
    val wokeAt: Long? = null,
    /** The sleep verdict their own phone published from every source it has (see [SleepModel]). */
    val sleep: SleepDecision? = null,
    /** IANA time zone, e.g. "Europe/London". */
    val timeZone: String? = null,
    val phoneBattery: BatteryState? = null,
    val watchBattery: BatteryState? = null,
    /** Last automatic or manual check-in, e.g. arriving home. */
    val checkIn: CheckIn? = null,
    val quietHours: QuietHours? = null,
    /** Whether their phone can reach their watch, as their phone last reported it. */
    val watch: WatchLink? = null,
)

/** A phone's connection to its watch. [since] is when it last changed (server time). */
data class WatchLink(val connected: Boolean, val since: Long?)

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

    /** On the move this recently → inactive rather than away. Longer than [MOVING_REPORT_MS], so it doesn't flicker. */
    val MOVING_RECENT_MS: Long = TimeUnit.MINUTES.toMillis(15)

    /** A watch upload this recent proves the person is reachable even if their phone isn't. */
    val RECENT_BEAT_MS: Long = TimeUnit.MINUTES.toMillis(3)

    /** Ignore a sleep flag older than this (a watch that died while they slept). */
    val ASLEEP_STALE_MS: Long = TimeUnit.HOURS.toMillis(14)

    /** Using the phone this long in total, while the watch says asleep, means they're up, not just stirring. */
    val WAKE_CONFIRM_MS: Long = TimeUnit.MINUTES.toMillis(15)

    /**
     * Uses of the phone or watch less than this apart belong to one spell of being awake, so a
     * shower or making breakfast doesn't end it. After this long with nothing used, "woke up" goes
     * back to asleep.
     */
    val WAKE_GAP_MS: Long = TimeUnit.MINUTES.toMillis(30)

    /** A spell of being awake that has lasted this long (with repeated use) means they're up. */
    val WAKE_SPAN_MS: Long = TimeUnit.MINUTES.toMillis(45)

    /** How much use one "in use" signal stands for: the phone reports use about once a minute. */
    val USE_TICK_MS: Long = TimeUnit.MINUTES.toMillis(1)

    /**
     * After this much sleep, picking up the phone is much more likely to be getting up than
     * stirring, so less use confirms it ([RESTED_WAKE_CONFIRM_MS], [RESTED_WAKE_SPAN_MS]).
     */
    val FULL_SLEEP_MS: Long = TimeUnit.HOURS.toMillis(6)
    val RESTED_WAKE_CONFIRM_MS: Long = TimeUnit.MINUTES.toMillis(5)
    val RESTED_WAKE_SPAN_MS: Long = TimeUnit.MINUTES.toMillis(20)

    // The four WAKE_* values above are starting points: each person's phone tunes its own copy
    // from how its guesses compared with the night as recorded (see SleepTuning, SleepLearning).
}

/** Where someone is in their sleep, combining every source in [SleepModel]. */
enum class SleepState(val key: String) {
    Awake("awake"),

    /** The watch says asleep and nothing has shown otherwise (or they went back to sleep). */
    Asleep("asleep"),

    /** The watch still says asleep, but they're using their phone right now: up in the night, or just woken. */
    WokeUp("wokeUp"),
    ;

    companion object {
        fun fromKey(key: String?): SleepState? = entries.firstOrNull { it.key == key }
    }
}

/** Where someone is in their sleep. Decided by [SleepModel]; see [Presence.sleepDecision]. */
fun Presence.sleepState(now: Long): SleepState = sleepDecision(now).state

/**
 * Asleep right now, for quiet hours: not while they're up using their phone. Someone who stirred
 * but put their phone down again counts as asleep after [StatusRules.INACTIVE_AFTER_MS], even
 * though their partner still sees "woke up" for a while.
 */
fun Presence.isAsleep(now: Long): Boolean = sleepDecision(now).isAsleep(now)

/** See [Presence.isAsleep]. */
fun SleepDecision.isAsleep(now: Long): Boolean {
    val current = at(now)
    return when (current.state) {
        SleepState.Asleep -> true
        SleepState.WokeUp -> now - (current.lastUse ?: 0L) >= StatusRules.INACTIVE_AFTER_MS
        SleepState.Awake -> false
    }
}

/**
 * no connection – can't reach any of their devices (phone disconnected, no recent watch reading)
 * asleep        – their watch says they're asleep (see [sleepState])
 * inactive      – stirring: their watch says asleep and they've used a device in the last
 *                 30 minutes, but not enough to count as up ([SleepState.WokeUp])
 * online / inactive / away – awake: decided by [ActivityModel]
 */
fun partnerStatus(presence: Presence?, lastBeat: Reading?, now: Long): PartnerStatus {
    val recentBeat = lastBeat != null && lastBeat.source != Sources.TEST &&
        now - lastBeat.timestamp < StatusRules.RECENT_BEAT_MS
    if (presence == null) return if (recentBeat) PartnerStatus.Online else PartnerStatus.Unknown
    if (!presence.connected && !recentBeat) return PartnerStatus.NoConnection
    when (presence.sleepState(now)) {
        SleepState.Asleep -> return PartnerStatus.Asleep
        // Stirring in the night isn't really being up, so it doesn't show as online.
        SleepState.WokeUp -> return PartnerStatus.Inactive
        SleepState.Awake -> Unit
    }
    return when (presence.activityDecision(now).level) {
        ActivityLevel.Online -> PartnerStatus.Online
        ActivityLevel.Inactive -> PartnerStatus.Inactive
        ActivityLevel.Away -> PartnerStatus.Away
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
    val BEAT_CHOICES = listOf(4, 8, 12, 16)

    /**
     * Waveform for [beats] "lub-dub" heartbeats at [bpm], for VibrationEffect.createWaveform.
     * Timings alternate off/on, starting with "off". [strength] scales the amplitudes (0–1);
     * devices without amplitude control ignore it.
     */
    fun heartbeatWaveform(bpm: Int, beats: Int = DEFAULT_BEATS, strength: Float = 1f): Pair<LongArray, IntArray> {
        fun amp(full: Int) = (full * strength.coerceIn(0f, 1f)).toInt().coerceIn(1, 255)
        val period = beatPeriodMs(bpm).toLong()
        val rest = (period - LUB_MS - GAP_MS - DUB_MS).coerceAtLeast(60L)
        val timings = ArrayList<Long>()
        val amplitudes = ArrayList<Int>()
        repeat(beats) { i ->
            timings += if (i == 0) 0L else rest; amplitudes += 0
            timings += LUB_MS; amplitudes += amp(255)
            timings += GAP_MS; amplitudes += 0
            timings += DUB_MS; amplitudes += amp(160)
        }
        return timings.toLongArray() to amplitudes.toIntArray()
    }
}

/** How strongly received heartbeats vibrate. Stored per device. */
enum class HapticStrength(val label: String, val scale: Float) {
    Gentle("Gentle", 0.4f),
    Normal("Normal", 0.7f),
    Strong("Strong", 1f),
    ;

    companion object {
        val DEFAULT = Normal
        fun fromName(name: String?): HapticStrength = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * How to play one received heartbeat. The phone sends this to its watch as the BUZZ payload,
 * "bpm|beats|strength", so the phone's haptic settings apply to the watch too. A bare "bpm"
 * (from older phones) plays with the defaults.
 */
data class BuzzPlayback(
    val bpm: Int,
    val beats: Int = Vibration.DEFAULT_BEATS,
    val strength: HapticStrength = HapticStrength.DEFAULT,
) {
    fun encode(): String = "$bpm|$beats|${strength.name}"

    fun waveform(): Pair<LongArray, IntArray> = Vibration.heartbeatWaveform(bpm, beats, strength.scale)

    companion object {
        fun decode(text: String): BuzzPlayback? {
            val parts = text.split('|')
            val bpm = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val beats = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 30) ?: Vibration.DEFAULT_BEATS
            return BuzzPlayback(bpm, beats, HapticStrength.fromName(parts.getOrNull(2)))
        }
    }
}

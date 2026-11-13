package com.heartlink.shared

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import java.util.concurrent.TimeUnit

/** A device's battery as last reported. [updatedAt] is server time, null for a fresh local reading. */
data class BatteryState(val level: Int, val charging: Boolean, val updatedAt: Long? = null) {
    val isLow: Boolean get() = !charging && level <= Battery.LOW_LEVEL
}

/** Which of a person's devices a battery reading is for. */
object Devices {
    const val PHONE = "phone"
    const val WATCH = "watch"
}

object Battery {
    const val LOW_LEVEL = 15

    /** Re-report at least this often even when nothing changed, so readings don't look stale. */
    private val HEARTBEAT_MS = TimeUnit.MINUTES.toMillis(30)

    /** Readings older than this get an age next to them. */
    private val STALE_MS = TimeUnit.HOURS.toMillis(1)

    private const val PREFS = "heartlink_battery"
    private const val TAG = "Battery"

    /** Current level and charging state of this device. */
    fun read(ctx: Context): BatteryState? {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        return fromIntent(intent)
    }

    fun fromIntent(intent: Intent): BatteryState? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryState(level * 100 / scale, charging)
    }

    /**
     * Whether a new reading is worth uploading: charging started/stopped, the level moved by
     * 2 points or more, it's low, or the last report is getting old.
     */
    fun shouldReport(previous: BatteryState?, previousAt: Long, current: BatteryState, now: Long): Boolean {
        if (previous == null) return true
        if (previous.charging != current.charging) return true
        if (kotlin.math.abs(previous.level - current.level) >= 2) return true
        if (current.isLow && previous.level != current.level) return true
        return now - previousAt >= HEARTBEAT_MS
    }

    /** Reads this device's battery and uploads it for [device] if it changed meaningfully. */
    suspend fun reportIfChanged(ctx: Context, session: Session, device: String, current: BatteryState? = read(ctx)) {
        if (current == null) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = if (prefs.contains("level")) {
            BatteryState(prefs.getInt("level", 0), prefs.getBoolean("charging", false))
        } else {
            null
        }
        val now = System.currentTimeMillis()
        if (!shouldReport(previous, prefs.getLong("at", 0L), current, now)) return
        try {
            HeartRepository.reportBattery(session, device, current)
            prefs.edit()
                .putInt("level", current.level)
                .putBoolean("charging", current.charging)
                .putLong("at", now)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't report battery", e)
        }
    }

    /** Forgets what was last reported (after unlinking), so the next link reports straight away. */
    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** "📱 64%", "⌚ 40%⚡", "📱 3% (2 hr ago)". */
    fun label(state: BatteryState, icon: String, now: Long): String {
        val base = "$icon ${state.level}%" + if (state.charging) "⚡" else ""
        val age = state.updatedAt?.let { now - it } ?: return base
        return if (age > STALE_MS) "$base (${ageText(age)})" else base
    }
}

/** Partner's phone and watch battery on one line, or null if neither has reported. */
fun batteryLine(presence: Presence?, now: Long): String? {
    if (presence == null) return null
    return listOfNotNull(
        presence.phoneBattery?.let { Battery.label(it, "📱", now) },
        presence.watchBattery?.let { Battery.label(it, "⌚", now) },
    ).joinToString(" · ").ifEmpty { null }
}

fun anyBatteryLow(presence: Presence?): Boolean =
    presence?.phoneBattery?.isLow == true || presence?.watchBattery?.isLow == true

package com.heartlink.phone.service

import android.content.Context
import com.heartlink.shared.HapticStrength
import com.heartlink.shared.QuietHours
import com.heartlink.shared.Vibration

/** Per-device preferences. Kept apart from the session so they survive unlinking. */
object Settings {
    private const val PREFS = "heartlink_settings"
    private const val KEY_BACKGROUND = "run_in_background"
    private const val KEY_RICH_NOTIFICATION = "rich_notification"
    private const val KEY_LAST_BUZZ = "last_handled_buzz"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Keep the background connection running (status, messages, heartbeats, live watch readings). */
    fun runInBackground(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BACKGROUND, true)
    fun setRunInBackground(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_BACKGROUND, value).apply()

    /** Show the partner's heartbeat, status and local time in the always-on notification. */
    fun richNotification(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_RICH_NOTIFICATION, true)
    fun setRichNotification(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_RICH_NOTIFICATION, value).apply()

    /** Notify when the partner wakes up or falls asleep. */
    fun wakeSleepNotifications(ctx: Context): Boolean = prefs(ctx).getBoolean("wake_sleep", true)
    fun setWakeSleepNotifications(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("wake_sleep", value).apply()

    /** When heartbeats and messages arrive silently. On by default, 23:00–07:00. */
    fun quietHours(ctx: Context): QuietHours {
        val p = prefs(ctx)
        val d = QuietHours()
        return QuietHours(
            enabled = p.getBoolean("quiet_enabled", d.enabled),
            startMinute = p.getInt("quiet_start", d.startMinute),
            endMinute = p.getInt("quiet_end", d.endMinute),
            allowWhenOnline = p.getBoolean("quiet_allow_online", d.allowWhenOnline),
        )
    }

    fun setQuietHours(ctx: Context, quiet: QuietHours) {
        prefs(ctx).edit()
            .putBoolean("quiet_enabled", quiet.enabled)
            .putInt("quiet_start", quiet.startMinute)
            .putInt("quiet_end", quiet.endMinute)
            .putBoolean("quiet_allow_online", quiet.allowWhenOnline)
            .apply()
    }

    /** asleepAt of the last wake/sleep change we announced, so each is announced once. */
    fun lastMomentAt(ctx: Context): Long = prefs(ctx).getLong("last_moment", 0L)
    fun setLastMomentAt(ctx: Context, value: Long) = prefs(ctx).edit().putLong("last_moment", value).apply()

    /** Home location for arrival detection. Stays on this phone; only "got home" is shared. */
    fun home(ctx: Context): Pair<Double, Double>? {
        val p = prefs(ctx)
        if (!p.contains("home_lat")) return null
        return Double.fromBits(p.getLong("home_lat", 0L)) to Double.fromBits(p.getLong("home_lng", 0L))
    }

    fun setHome(ctx: Context, home: Pair<Double, Double>?) {
        val edit = prefs(ctx).edit()
        if (home == null) {
            edit.remove("home_lat").remove("home_lng")
        } else {
            edit.putLong("home_lat", home.first.toRawBits()).putLong("home_lng", home.second.toRawBits())
        }
        edit.apply()
    }

    fun lastHomeArrival(ctx: Context): Long = prefs(ctx).getLong("last_home_arrival", 0L)
    fun setLastHomeArrival(ctx: Context, value: Long) = prefs(ctx).edit().putLong("last_home_arrival", value).apply()

    /** How received heartbeats vibrate, on this phone and on its watch. */
    fun hapticStrength(ctx: Context): HapticStrength = HapticStrength.fromName(prefs(ctx).getString("haptic_strength", null))
    fun setHapticStrength(ctx: Context, value: HapticStrength) = prefs(ctx).edit().putString("haptic_strength", value.name).apply()

    fun hapticBeats(ctx: Context): Int = prefs(ctx).getInt("haptic_beats", Vibration.DEFAULT_BEATS)
    fun setHapticBeats(ctx: Context, value: Int) = prefs(ctx).edit().putInt("haptic_beats", value).apply()

    /** Play heartbeats that arrived silently during quiet hours once quiet hours are over. */
    fun replayAfterQuiet(ctx: Context): Boolean = prefs(ctx).getBoolean("replay_after_quiet", true)
    fun setReplayAfterQuiet(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("replay_after_quiet", value).apply()

    /** Heartbeats held back during quiet hours: how many, the latest bpm and sender, and when the first arrived. */
    data class PendingReplay(val count: Int, val bpm: Int, val fromName: String?, val firstAt: Long)

    fun pendingReplay(ctx: Context): PendingReplay? {
        val p = prefs(ctx)
        val count = p.getInt("replay_count", 0)
        if (count <= 0) return null
        return PendingReplay(count, p.getInt("replay_bpm", 70), p.getString("replay_from", null), p.getLong("replay_first", 0L))
    }

    fun addPendingReplay(ctx: Context, bpm: Int, fromName: String?, at: Long) {
        val existing = pendingReplay(ctx)
        prefs(ctx).edit()
            .putInt("replay_count", (existing?.count ?: 0) + 1)
            .putInt("replay_bpm", bpm)
            .putString("replay_from", fromName)
            .putLong("replay_first", existing?.firstAt ?: at)
            .apply()
    }

    fun clearPendingReplay(ctx: Context) {
        prefs(ctx).edit().remove("replay_count").remove("replay_bpm").remove("replay_from").remove("replay_first").apply()
    }

    /** Timestamp of the last buzz we vibrated for, so each one plays once. */
    fun lastHandledBuzz(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_BUZZ, 0L)
    fun setLastHandledBuzz(ctx: Context, value: Long) = prefs(ctx).edit().putLong(KEY_LAST_BUZZ, value).apply()
}

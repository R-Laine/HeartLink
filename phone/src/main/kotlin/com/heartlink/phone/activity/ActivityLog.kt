package com.heartlink.phone.activity

import android.content.Context
import com.heartlink.shared.ActivityKind
import com.heartlink.shared.ActivityModel
import com.heartlink.shared.ActivitySignal
import com.heartlink.shared.StatusRules
import com.heartlink.shared.UseRun

/**
 * This person's recent signs of activity, for [ActivityModel] and (their use) for the sleep model.
 * Kept on disk so a restarted service still knows about this morning. Main thread only.
 */
class ActivityLog(context: Context) {
    // In the session's prefs file, so unlinking clears it too.
    private val prefs = context.getSharedPreferences("heartlink", Context.MODE_PRIVATE)
    private var signals: List<ActivitySignal> = load()

    fun signals(): List<ActivitySignal> = signals

    /** Use of a phone or watch, as runs, for the sleep model. */
    fun engagedRuns(): List<UseRun> = ActivityModel.engagedRuns(signals)

    /**
     * Records [kind] at [at] (server time): extends the latest signal of that kind if it's still
     * going (reports about a minute apart), otherwise starts a new one. Returns true if that's new.
     */
    fun add(kind: ActivityKind, at: Long): Boolean {
        val lastIndex = signals.indexOfLast { it.kind == kind }
        val last = signals.getOrNull(lastIndex)
        if (last != null && at in last.start..last.end) return false
        val updated = signals.toMutableList()
        if (last != null && at > last.end && at - last.end <= CONTINUES_MS) {
            updated[lastIndex] = last.copy(end = at)
        } else {
            updated += ActivitySignal(kind, at)
        }
        val cutoff = at - StatusRules.ASLEEP_STALE_MS
        signals = updated.filter { it.end >= cutoff }.sortedBy { it.start }.takeLast(MAX_SIGNALS)
        save()
        return true
    }

    private fun save() {
        prefs.edit().putString(KEY, signals.joinToString(",") { "${it.kind.key}:${it.start}:${it.end}" }).apply()
    }

    private fun load(): List<ActivitySignal> {
        val stored = prefs.getString(KEY, null)
        if (stored == null) {
            // Use recorded before activity kinds existed.
            return prefs.getString(OLD_KEY, null).orEmpty().split(',').mapNotNull { entry ->
                val (start, end) = entry.split('-').takeIf { it.size == 2 } ?: return@mapNotNull null
                ActivitySignal(ActivityKind.Phone, start.toLongOrNull() ?: return@mapNotNull null, end.toLongOrNull() ?: return@mapNotNull null)
            }
        }
        return stored.split(',').mapNotNull { entry ->
            val parts = entry.split(':').takeIf { it.size == 3 } ?: return@mapNotNull null
            val kind = ActivityKind.entries.firstOrNull { it.key == parts[0] } ?: return@mapNotNull null
            ActivitySignal(kind, parts[1].toLongOrNull() ?: return@mapNotNull null, parts[2].toLongOrNull() ?: return@mapNotNull null)
        }
    }

    private companion object {
        const val KEY = "activity_signals"
        const val OLD_KEY = "use_runs"
        const val MAX_SIGNALS = 300

        /** Reports this close together are one continuous signal. */
        val CONTINUES_MS = 2 * StatusRules.USE_TICK_MS
    }
}

package com.heartlink.app

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Identity
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * Delivers sleep and wake changes reliably. Health Services only reports a change once, so a
 * report that failed (no connection, or the watch closed the app mid-write) used to be lost until
 * the next change. Now the latest change waits in [PREFS] until the database confirms it, and
 * WorkManager keeps retrying it, keeping the app running until each attempt finishes.
 */
object SleepReport {
    private const val PREFS = "heartlink_watch_state"

    /** The last state the database confirmed. */
    private const val KEY_SENT = "asleep"
    private const val KEY_PENDING = "pending_asleep"
    private const val KEY_PENDING_AT = "pending_at"
    private const val WORK_NAME = "sleep-report"
    private const val KEY_LAST_BPM = "last_bpm"
    private const val KEY_LAST_BPM_AT = "last_bpm_at"

    /** Remembers the latest heart rate measured, and when, for the phone to relay if it asks. */
    fun recordReading(ctx: Context, bpm: Int, at: Long = System.currentTimeMillis()) {
        val p = prefs(ctx)
        // Batches can arrive out of order; keep the newest.
        if (p.getLong(KEY_LAST_BPM_AT, 0L) > at) return
        p.edit().putInt(KEY_LAST_BPM, bpm).putLong(KEY_LAST_BPM_AT, at).apply()
    }

    fun lastReading(ctx: Context): Pair<Int, Long>? {
        val p = prefs(ctx)
        if (!p.contains(KEY_LAST_BPM)) return null
        return p.getInt(KEY_LAST_BPM, 0) to p.getLong(KEY_LAST_BPM_AT, 0L)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A change from Health Services, [at] being when it happened. */
    fun record(ctx: Context, asleep: Boolean, at: Long) {
        val p = prefs(ctx)
        val latest = if (p.contains(KEY_PENDING)) p.getBoolean(KEY_PENDING, false) else p.takeIf { it.contains(KEY_SENT) }?.getBoolean(KEY_SENT, false)
        if (latest == asleep) return
        p.edit().putBoolean(KEY_PENDING, asleep).putLong(KEY_PENDING_AT, at).commit()
        schedule(ctx, ExistingWorkPolicy.REPLACE)
    }

    /** Called whenever the app runs anyway (heart-rate updates, opening it): nudges a waiting report. */
    fun retryIfPending(ctx: Context) {
        if (prefs(ctx).contains(KEY_PENDING)) schedule(ctx, ExistingWorkPolicy.KEEP)
    }

    /** The change still waiting to be sent, and when it happened, if any. */
    fun pending(ctx: Context): Pair<Boolean, Long>? {
        val p = prefs(ctx)
        if (!p.contains(KEY_PENDING)) return null
        return p.getBoolean(KEY_PENDING, false) to p.getLong(KEY_PENDING_AT, 0L)
    }

    /** The phone uploaded the change from [at] on the watch's behalf. */
    fun confirmRelayed(ctx: Context, at: Long) {
        val p = prefs(ctx)
        if (!p.contains(KEY_PENDING) || p.getLong(KEY_PENDING_AT, 0L) != at) return
        p.edit().putBoolean(KEY_SENT, p.getBoolean(KEY_PENDING, false)).remove(KEY_PENDING).remove(KEY_PENDING_AT).commit()
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }

    /** Forget everything, e.g. after unlinking. */
    fun reset(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PENDING).remove(KEY_PENDING_AT).remove(KEY_SENT)
            .remove(KEY_LAST_BPM).remove(KEY_LAST_BPM_AT).commit()
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }

    private fun schedule(ctx: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<SleepReportWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    internal suspend fun send(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.contains(KEY_PENDING)) return true
        val session = Identity.get(ctx) ?: return true
        val asleep = p.getBoolean(KEY_PENDING, false)
        val at = p.getLong(KEY_PENDING_AT, System.currentTimeMillis())
        return try {
            withTimeout(TimeUnit.SECONDS.toMillis(45)) { HeartRepository.setAsleep(session, asleep, at) }
            // Only clear it if no newer change arrived while this one was being sent.
            if (p.getBoolean(KEY_PENDING, !asleep) == asleep && p.getLong(KEY_PENDING_AT, 0) == at) {
                p.edit().putBoolean(KEY_SENT, asleep).remove(KEY_PENDING).remove(KEY_PENDING_AT).commit()
            }
            true
        } catch (e: Exception) {
            Log.w("SleepReport", "Couldn't report sleep; will retry", e)
            false
        }
    }
}

class SleepReportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = if (SleepReport.send(applicationContext)) Result.success() else Result.retry()
}

package com.heartlink.phone.sleep

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.SleepClassifyEvent
import com.google.android.gms.location.SleepSegmentEvent
import com.google.android.gms.location.SleepSegmentRequest
import com.heartlink.shared.FinishedSleep
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.SleepConfidence
import com.heartlink.shared.SleepReason
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Android's own sleep detection on this phone (Google Play services Sleep API), from the phone's
 * motion and light. Every 10 minutes it guesses how likely the person is asleep, and after they
 * wake it reports the finished sleep. It is one of the sources SleepModel weighs.
 * Needs the physical activity permission.
 */
object SleepApi {
    private const val TAG = "SleepApi"

    // In the session's prefs file, so unlinking clears it too.
    private const val PREFS = "heartlink"
    private const val KEY_READINGS = "sleep_api_readings"
    private const val KEY_SEGMENT = "sleep_api_segment"

    /** About 15 hours of readings, one every 10 minutes: a whole night, for checking it afterwards. */
    private const val MAX_READINGS = 90

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when new Sleep API data arrives while the app is running. */
    val changed: SharedFlow<Unit> = _changed

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Starts (or renews) updates. Safe to call often; registrations don't survive a reboot. */
    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        if (!hasPermission(ctx)) return
        runCatching {
            ActivityRecognition.getClient(ctx)
                .requestSleepSegmentUpdates(pendingIntent(ctx), SleepSegmentRequest.getDefaultSleepSegmentRequest())
                .addOnFailureListener { Log.w(TAG, "Couldn't start sleep updates", it) }
        }.onFailure { Log.w(TAG, "Sleep API unavailable", it) }
    }

    @SuppressLint("MissingPermission")
    fun unregister(ctx: Context) {
        if (!hasPermission(ctx)) return
        runCatching { ActivityRecognition.getClient(ctx).removeSleepSegmentUpdates(pendingIntent(ctx)) }
    }

    /** Recent readings, in server time, oldest first. */
    fun readings(ctx: Context): List<SleepConfidence> =
        prefs(ctx).getString(KEY_READINGS, null).orEmpty().split(',').mapNotNull { entry ->
            val (at, confidence) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            SleepConfidence(at.toLongOrNull() ?: return@mapNotNull null, confidence.toIntOrNull() ?: return@mapNotNull null)
        }

    /** The last sleep the Sleep API reported as finished, in server time. */
    fun lastSleep(ctx: Context): FinishedSleep? {
        val (start, end) = prefs(ctx).getString(KEY_SEGMENT, null)?.split(':')?.takeIf { it.size == 2 } ?: return null
        return FinishedSleep(start.toLongOrNull() ?: return null, end.toLongOrNull() ?: return null, SleepReason.SleepApi)
    }

    internal fun receive(ctx: Context, intent: Intent) {
        val p = prefs(ctx)
        if (SleepClassifyEvent.hasEvents(intent)) {
            val fresh = SleepClassifyEvent.extractEvents(intent).map {
                SleepConfidence(HeartRepository.toServerTime(it.timestampMillis), it.confidence)
            }
            val kept = (readings(ctx) + fresh).distinctBy { it.at }.sortedBy { it.at }.takeLast(MAX_READINGS)
            p.edit().putString(KEY_READINGS, kept.joinToString(",") { "${it.at}:${it.confidence}" }).apply()
        }
        if (SleepSegmentEvent.hasEvents(intent)) {
            SleepSegmentEvent.extractEvents(intent)
                .filter { it.status == SleepSegmentEvent.STATUS_SUCCESSFUL }
                .maxByOrNull { it.endTimeMillis }
                ?.let { segment ->
                    val start = HeartRepository.toServerTime(segment.startTimeMillis)
                    val end = HeartRepository.toServerTime(segment.endTimeMillis)
                    if (end > (lastSleep(ctx)?.end ?: 0L)) p.edit().putString(KEY_SEGMENT, "$start:$end").apply()
                }
        }
        _changed.tryEmit(Unit)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pendingIntent(ctx: Context): PendingIntent {
        // Mutable: Play services adds the events to it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 0, Intent(ctx, SleepApiReceiver::class.java), flags)
    }
}

class SleepApiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { SleepApi.receive(context, intent) }.onFailure { Log.w("SleepApiReceiver", "Bad sleep update", it) }
    }
}

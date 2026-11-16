package com.heartlink.phone.activity

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.heartlink.phone.sleep.SleepApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.TimeUnit

/**
 * Whether this person is on the move with their phone (walking, running, cycling, in a vehicle),
 * from Android's activity recognition. Uses the same physical activity permission as the Sleep API
 * (Settings, Sleep detection). Low power: the phone's motion hardware does the work.
 */
object Motion {
    private const val TAG = "Motion"
    private const val PREFS = "heartlink"
    private const val KEY_MOVING = "motion_moving"
    private const val KEY_CHANGED = "motion_changed"

    /** A "started moving" with no "stopped" after this long is not trusted. */
    private val MAX_TRIP_MS = TimeUnit.HOURS.toMillis(3)

    private val TYPES = listOf(
        DetectedActivity.WALKING,
        DetectedActivity.RUNNING,
        DetectedActivity.ON_BICYCLE,
        DetectedActivity.IN_VEHICLE,
    )

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when moving starts or stops while the app is running. */
    val changed: SharedFlow<Unit> = _changed

    /** On the move right now (device clock). */
    fun isMoving(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(ctx)
        return p.getBoolean(KEY_MOVING, false) && now - p.getLong(KEY_CHANGED, 0L) < MAX_TRIP_MS
    }

    /** When moving last started or stopped (device clock), or null. */
    fun changedAt(ctx: Context): Long? = prefs(ctx).getLong(KEY_CHANGED, 0L).takeIf { it != 0L }

    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        if (!SleepApi.hasPermission(ctx)) return
        val transitions = TYPES.flatMap { type ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { change ->
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(change).build()
            }
        }
        runCatching {
            ActivityRecognition.getClient(ctx)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(ctx))
                .addOnFailureListener { Log.w(TAG, "Couldn't start motion updates", it) }
        }.onFailure { Log.w(TAG, "Activity recognition unavailable", it) }
    }

    @SuppressLint("MissingPermission")
    fun unregister(ctx: Context) {
        if (!SleepApi.hasPermission(ctx)) return
        runCatching { ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(pendingIntent(ctx)) }
        prefs(ctx).edit().remove(KEY_MOVING).remove(KEY_CHANGED).apply()
    }

    internal fun receive(ctx: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val event = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.maxByOrNull { it.elapsedRealTimeNanos } ?: return
        // Event times are since boot; turn them into clock time.
        val ageMs = (SystemClock.elapsedRealtimeNanos() - event.elapsedRealTimeNanos) / 1_000_000
        val at = System.currentTimeMillis() - ageMs.coerceAtLeast(0L)
        val moving = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        prefs(ctx).edit().putBoolean(KEY_MOVING, moving).putLong(KEY_CHANGED, at).apply()
        _changed.tryEmit(Unit)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pendingIntent(ctx: Context): PendingIntent {
        // Mutable: Play services adds the events to it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 1, Intent(ctx, MotionReceiver::class.java), flags)
    }
}

class MotionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { Motion.receive(context, intent) }.onFailure { Log.w("MotionReceiver", "Bad motion update", it) }
    }
}

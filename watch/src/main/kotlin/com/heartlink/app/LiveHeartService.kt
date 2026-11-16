package com.heartlink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Measures heart rate live for a short while because the partner is looking right now.
 * Each new request (relayed by the phone every ~30 s while the partner's screen is open)
 * extends the window; it stops [WINDOW_MS] after the last one.
 */
class LiveHeartService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stopJob: Job? = null
    private var measuring = false

    private val callback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit

        override fun onDataReceived(data: DataPointContainer) {
            val value = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value ?: return
            if (value <= 0.0) return
            val session = Identity.get(this@LiveHeartService) ?: return
            SleepReport.recordReading(this@LiveHeartService, value.roundToInt())
            scope.launch { HeartRepository.upload(session, value.roundToInt()) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started with startForegroundService(), so startForeground() must come first: a service
        // that stops without it crashes the app. [request] checks the link and permission before
        // starting, so stopping below only happens if they changed in between.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't go to the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!canMeasure(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!measuring) {
            try {
                HealthServices.getClient(this).measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
                measuring = true
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start live heart rate", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(WINDOW_MS)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (measuring) {
            HealthServices.getClient(this).measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Live heartbeat", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("Sharing your heartbeat live")
            .setOngoing(true)
            .setLocalOnly(true)
            .build()
    }

    companion object {
        private const val TAG = "LiveHeartService"
        private const val CHANNEL = "live"
        private const val NOTIFICATION_ID = 10
        private const val WINDOW_MS = 90_000L

        /**
         * Linked, and allowed to read heart rate. Wear OS 6 grants READ_HEART_RATE instead of
         * BODY_SENSORS: checking only BODY_SENSORS meant live readings never started there.
         */
        private fun canMeasure(ctx: Context) = Identity.get(ctx) != null && BackgroundSetup.hasHeartRate(ctx)

        fun request(ctx: Context) {
            // Checked before asking Android, not in the service: once started this way, the
            // service can't stop without going to the foreground first, or the app crashes.
            if (!canMeasure(ctx)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LiveHeartService::class.java))
            } catch (e: Exception) {
                // Android may refuse to start a foreground service from the background; the
                // passive listener keeps sending (less frequent) readings regardless.
                Log.w(TAG, "Couldn't start live measuring", e)
            }
        }
    }
}

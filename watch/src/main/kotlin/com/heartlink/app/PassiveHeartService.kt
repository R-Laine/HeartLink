package com.heartlink.app

import android.os.SystemClock
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import com.heartlink.shared.Battery
import com.heartlink.shared.Devices
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Identity
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Background updates from Health Services: heart-rate batches (uploads the newest) and
 * activity-state changes (reports when the wearer falls asleep or wakes up).
 */
class PassiveHeartService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val session = Identity.get(this) ?: return
        val latest = dataPoints.getData(DataType.HEART_RATE_BPM).maxByOrNull { it.timeDurationFromBoot } ?: return
        val bpm = latest.value.roundToInt()
        if (bpm <= 0) return
        // When it was measured, not when the batch arrived: batches can be minutes late.
        val bootInstant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val measuredAt = latest.getTimeInstant(bootInstant).toEpochMilli()
        val app = applicationContext
        SleepReport.recordReading(app, bpm, measuredAt)
        // A sleep change that couldn't be sent yet: try again now the app is running.
        SleepReport.retryIfPending(app)

        // Not tied to this service: Health Services can unbind it as soon as this returns, which
        // cancelled the upload (and the battery report after it) half-way.
        AppScope.launch {
            HeartRepository.uploadMeasured(session, bpm, measuredAt)
            // Piggyback on the passive updates (every few minutes) to keep the battery current.
            Battery.reportIfChanged(app, session, Devices.WATCH)
        }
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        if (Identity.get(this) == null) return
        val asleep = info.userActivityState == UserActivityState.USER_ACTIVITY_ASLEEP
        SleepReport.record(this, asleep, info.stateChangeTime.toEpochMilli())
    }
}

package com.heartlink.app

import android.content.Context
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import com.heartlink.shared.Battery
import com.heartlink.shared.Devices
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Background updates from Health Services: heart-rate batches (uploads the newest) and
 * activity-state changes (reports when the wearer falls asleep or wakes up).
 */
class PassiveHeartService : PassiveListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val session = Identity.get(this) ?: return
        val latest = dataPoints.getData(DataType.HEART_RATE_BPM)
            .maxByOrNull { it.timeDurationFromBoot }
            ?.value ?: return
        if (latest <= 0.0) return

        scope.launch {
            HeartRepository.upload(session, latest.roundToInt(), force = true)
            // Piggyback on the passive updates (every few minutes) to keep the battery current.
            Battery.reportIfChanged(this@PassiveHeartService, session, Devices.WATCH)
        }
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        val session = Identity.get(this) ?: return
        val asleep = info.userActivityState == UserActivityState.USER_ACTIVITY_ASLEEP
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_ASLEEP) && prefs.getBoolean(KEY_ASLEEP, false) == asleep) return
        scope.launch {
            runCatching { HeartRepository.setAsleep(session, asleep) }
                .onSuccess { prefs.edit().putBoolean(KEY_ASLEEP, asleep).apply() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val PREFS = "heartlink_watch_state"
        const val KEY_ASLEEP = "asleep"
    }
}

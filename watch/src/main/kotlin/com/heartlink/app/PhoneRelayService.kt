package com.heartlink.app

import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.heartlink.shared.Battery
import com.heartlink.shared.BuzzPlayback
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Identity
import com.heartlink.shared.WatchReport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import com.heartlink.shared.WearPaths

/**
 * Messages from the phone: play the partner's heartbeat, start live readings, or check in
 * because the phone hasn't seen fresh data from this watch. Runs on a background thread.
 */
class PhoneRelayService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.BUZZ -> BuzzPlayback.decode(String(event.data))?.let(::vibrate)
            WearPaths.LIVE_REQUEST -> LiveHeartService.request(this)
            WearPaths.SYNC_REQUEST -> sync(event.sourceNodeId)
            WearPaths.SLEEP_RELAYED -> String(event.data).toLongOrNull()?.let { SleepReport.confirmRelayed(this, it) }
            // The phone was unlinked and already removed this watch from the couple.
            WearPaths.UNLINK -> if (Identity.get(this) != null) LinkReset.forget(this)
        }
    }

    /**
     * Wakes everything back up (background monitoring can be dropped, sends can be stuck) and
     * tells the phone what this watch knows, so the phone can upload it itself.
     */
    private fun sync(phone: String) {
        if (Identity.get(this) == null) return
        BackgroundSetup.register(this)
        SleepReport.retryIfPending(this)
        val last = SleepReport.lastReading(this)
        // No recent reading: measure for a moment (if Android allows it from the background).
        if (last == null || System.currentTimeMillis() - last.second > FRESH_READING_MS) LiveHeartService.request(this)
        val battery = Battery.read(this)
        val pending = SleepReport.pending(this)
        val report = WatchReport(
            bpm = last?.first,
            bpmAt = last?.second,
            battery = battery?.level,
            charging = battery?.charging == true,
            asleep = pending?.first,
            asleepAt = pending?.second,
            uid = HeartRepository.currentUid,
        )
        runCatching {
            runBlocking {
                withTimeout(15_000) {
                    Wearable.getMessageClient(this@PhoneRelayService).sendMessage(phone, WearPaths.SYNC_REPLY, report.encode()).await()
                }
            }
        }.onFailure { Log.w("PhoneRelayService", "Couldn't reply to phone", it) }
    }

    private fun vibrate(playback: BuzzPlayback) {
        val (timings, amplitudes) = playback.waveform()
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(android.os.Vibrator::class.java)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    private companion object {
        /** A reading older than this is worth measuring a new one for. */
        const val FRESH_READING_MS = 10 * 60_000L
    }
}

package com.heartlink.app

import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.heartlink.shared.Vibration
import com.heartlink.shared.WearPaths

/** Requests the phone forwards from the partner: play their heartbeat, or start live readings. */
class PhoneRelayService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.BUZZ -> String(event.data).toIntOrNull()?.let(::vibrate)
            WearPaths.LIVE_REQUEST -> LiveHeartService.request(this)
        }
    }

    private fun vibrate(bpm: Int) {
        val (timings, amplitudes) = Vibration.heartbeatWaveform(bpm)
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(android.os.Vibrator::class.java)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }
}

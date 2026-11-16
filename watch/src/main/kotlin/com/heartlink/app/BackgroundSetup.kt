package com.heartlink.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import com.heartlink.shared.Battery
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Background heart-rate and sleep updates from Health Services (see [PassiveHeartService]). */
object BackgroundSetup {
    private fun has(ctx: Context, permission: String) =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Wear OS 6 (Android 16) replaces BODY_SENSORS with a Health Connect heart-rate permission. */
    val heartRatePermission: String =
        if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

    fun hasHeartRate(ctx: Context) = has(ctx, heartRatePermission) || has(ctx, Manifest.permission.BODY_SENSORS)

    /** Registers (or refreshes) the passive listener. Safe to call repeatedly. */
    fun register(ctx: Context) {
        if (Identity.get(ctx) == null || !hasHeartRate(ctx)) return
        val config = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .setShouldUserActivityInfoBeRequested(has(ctx, Manifest.permission.ACTIVITY_RECOGNITION))
            .build()
        runCatching {
            HealthServices.getClient(ctx).passiveMonitoringClient
                .setPassiveListenerServiceAsync(PassiveHeartService::class.java, config)
        }.onFailure { Log.w("BackgroundSetup", "Couldn't start background heart rate", it) }
    }
}

/**
 * Work that should finish even if the component that started it goes away, such as an upload
 * started by [PassiveHeartService], which Health Services can unbind as soon as it returns.
 */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Whether this watch should pair itself through the phone automatically. */
object Pairing {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("heartlink_pairing", Context.MODE_PRIVATE)

    /** True after the person unlinked this watch themselves, so it doesn't pair straight back. */
    fun isPaused(ctx: Context): Boolean = prefs(ctx).getBoolean("paused", false)

    fun setPaused(ctx: Context, paused: Boolean) {
        prefs(ctx).edit().putBoolean("paused", paused).apply()
    }
}

/** Forgets this watch's link and stops everything that shares data. */
object LinkReset {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires after [forget], so an open screen can go back to setup. */
    val events: SharedFlow<Unit> = _events

    fun forget(ctx: Context) {
        runCatching { HealthServices.getClient(ctx).passiveMonitoringClient.clearPassiveListenerServiceAsync() }
        ctx.stopService(Intent(ctx, LiveHeartService::class.java))
        Battery.reset(ctx)
        SleepReport.reset(ctx)
        Identity.clear(ctx)
        HeartRepository.resetAccessDenied()
        _events.tryEmit(Unit)
    }
}

/**
 * Health Services forgets passive registrations when the watch restarts (and can after an app
 * update), which stopped heartbeats and sleep reports until the app was opened again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> BackgroundSetup.register(context)
        }
    }
}

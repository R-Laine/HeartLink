package com.heartlink.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.lifecycle.lifecycleScope
import com.heartlink.shared.Battery
import com.heartlink.shared.Devices
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Identity
import com.heartlink.shared.Session
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val session = mutableStateOf<Session?>(null)
    private val myBpm = mutableStateOf<Int?>(null)
    private val crash = mutableStateOf<String?>(null)
    private val setupNote = mutableStateOf<String?>(null)
    private var measuring = false

    private val measureCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit

        override fun onDataReceived(data: DataPointContainer) {
            val value = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value ?: return
            if (value <= 0.0) return
            val bpm = value.roundToInt()
            myBpm.value = bpm
            val current = session.value ?: return
            lifecycleScope.launch { HeartRepository.upload(current, bpm) }
        }
    }

    private val sensorPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) afterSensorPermission() else requestActivityRecognition()
        }

    private val backgroundPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestActivityRecognition() }

    private val activityPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setupBackground()
            requestNotifications()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session.value = Identity.get(this)
        lifecycleScope.launch { runCatching { HeartRepository.ensureSignedIn() } }
        // A link the database no longer accepts (unlinked from the phone, or made in an older
        // Firebase project) would otherwise leave the watch stuck: forget it and pair again.
        session.value?.let { current ->
            lifecycleScope.launch { HeartRepository.isStillLinked(current) }
        }
        lifecycleScope.launch {
            HeartRepository.accessDenied.collect { denied ->
                val current = session.value
                if (!denied || current == null) return@collect
                // One refused read isn't proof the link is gone: only forget it if the database
                // confirms this watch isn't in the couple.
                when (HeartRepository.isStillLinked(current)) {
                    false -> forgetLink(refused = true)
                    true -> {
                        android.util.Log.w("MainActivity", "Read refused but still linked: ${HeartRepository.lastDenied}")
                        HeartRepository.resetAccessDenied()
                    }
                    null -> Unit
                }
            }
        }
        crash.value = CrashLog.read(this)
        setContent {
            val report = crash.value
            if (report != null) {
                CrashReport(report, onDismiss = {
                    CrashLog.clear(this)
                    crash.value = null
                })
            } else {
                HeartLinkApp(
                    session = session.value,
                    myBpm = myBpm.value,
                    setupNote = setupNote.value,
                    onLinked = ::link,
                    onUnlink = ::unlink,
                )
            }
        }
        if (session.value != null) requestSensors()
    }

    override fun onResume() {
        super.onResume()
        startLive()
        // Using the watch counts as being active.
        session.value?.let { current ->
            lifecycleScope.launch { runCatching { HeartRepository.markActive(current) } }
            lifecycleScope.launch { Battery.reportIfChanged(this@MainActivity, current, Devices.WATCH) }
        }
    }

    override fun onPause() {
        super.onPause()
        stopLive()
    }

    private fun link(newSession: Session) {
        HeartRepository.resetAccessDenied()
        setupNote.value = null
        Identity.set(this, newSession)
        session.value = newSession
        requestSensors()
    }

    private fun unlink() {
        val old = session.value
        if (old != null) {
            lifecycleScope.launch {
                runCatching { HeartRepository.leave(old) }
                    .onFailure { android.util.Log.w("MainActivity", "Couldn't remove device", it) }
            }
        }
        forgetLink()
    }

    /**
     * Clears the link on this watch only; setup then pairs it again through the phone. When the
     * database [refused] the link, setup explains why and waits before asking the phone again,
     * so a link that keeps failing can't flicker between screens.
     */
    private fun forgetLink(refused: Boolean = false) {
        android.util.Log.w("MainActivity", "Link no longer valid; pairing again")
        setupNote.value = if (refused) {
            listOfNotNull(
                "The database refused this watch's link. Trying again shortly.",
                HeartRepository.currentUid?.let { "Watch ID ${it.take(8)}" },
                HeartRepository.lastDenied,
            ).joinToString("\n")
        } else {
            null
        }
        stopLive()
        runCatching { HealthServices.getClient(this).passiveMonitoringClient.clearPassiveListenerServiceAsync() }
        Battery.reset(this)
        Identity.clear(this)
        HeartRepository.resetAccessDenied()
        session.value = null
        myBpm.value = null
    }

    private fun has(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Wear OS 6 (Android 16) replaces BODY_SENSORS with a Health Connect heart-rate permission. */
    private val heartRatePermission =
        if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

    private fun hasHeartRate() = has(heartRatePermission) || has(Manifest.permission.BODY_SENSORS)

    private fun requestSensors() {
        if (hasHeartRate()) afterSensorPermission()
        else sensorPermission.launch(heartRatePermission)
    }

    private fun afterSensorPermission() {
        startLive()
        setupBackground()
        val backgroundSensors = if (Build.VERSION.SDK_INT >= 36) {
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        } else {
            Manifest.permission.BODY_SENSORS_BACKGROUND
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !has(backgroundSensors)) {
            backgroundPermission.launch(backgroundSensors)
        } else {
            requestActivityRecognition()
        }
    }

    /** Needed for sleep detection, so the partner can see when this person is asleep. */
    private fun requestActivityRecognition() {
        if (!has(Manifest.permission.ACTIVITY_RECOGNITION)) {
            activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            requestNotifications()
        }
    }

    /** For the notification shown while measuring live, and heartbeats that arrive silently. */
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startLive() {
        if (measuring || session.value == null || !hasHeartRate()) return
        runCatching {
            HealthServices.getClient(this).measureClient
                .registerMeasureCallback(DataType.HEART_RATE_BPM, measureCallback)
            measuring = true
        }.onFailure { android.util.Log.w("MainActivity", "Couldn't start live heart rate", it) }
    }

    private fun stopLive() {
        if (!measuring) return
        runCatching {
            HealthServices.getClient(this).measureClient
                .unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, measureCallback)
        }
        measuring = false
    }

    private fun setupBackground() {
        val config = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .setShouldUserActivityInfoBeRequested(has(Manifest.permission.ACTIVITY_RECOGNITION))
            .build()
        runCatching {
            HealthServices.getClient(this).passiveMonitoringClient
                .setPassiveListenerServiceAsync(PassiveHeartService::class.java, config)
        }.onFailure { android.util.Log.w("MainActivity", "Couldn't start background heart rate", it) }
    }
}

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
            if (granted) afterSensorPermission()
        }

    private val backgroundPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestActivityRecognition() }

    private val activityPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) setupBackground() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session.value = Identity.get(this)
        lifecycleScope.launch { runCatching { HeartRepository.ensureSignedIn() } }
        setContent {
            HeartLinkApp(
                session = session.value,
                myBpm = myBpm.value,
                onLinked = ::link,
                onUnlink = ::unlink,
            )
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
        stopLive()
        HealthServices.getClient(this).passiveMonitoringClient.clearPassiveListenerServiceAsync()
        Battery.reset(this)
        Identity.clear(this)
        session.value = null
        myBpm.value = null
    }

    private fun has(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun requestSensors() {
        if (has(Manifest.permission.BODY_SENSORS)) afterSensorPermission()
        else sensorPermission.launch(Manifest.permission.BODY_SENSORS)
    }

    private fun afterSensorPermission() {
        startLive()
        setupBackground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !has(Manifest.permission.BODY_SENSORS_BACKGROUND)
        ) {
            backgroundPermission.launch(Manifest.permission.BODY_SENSORS_BACKGROUND)
        } else {
            requestActivityRecognition()
        }
    }

    /** Needed for sleep detection, so the partner can see when this person is asleep. */
    private fun requestActivityRecognition() {
        if (!has(Manifest.permission.ACTIVITY_RECOGNITION)) {
            activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private fun startLive() {
        if (measuring || session.value == null || !has(Manifest.permission.BODY_SENSORS)) return
        HealthServices.getClient(this).measureClient
            .registerMeasureCallback(DataType.HEART_RATE_BPM, measureCallback)
        measuring = true
    }

    private fun stopLive() {
        if (!measuring) return
        HealthServices.getClient(this).measureClient
            .unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, measureCallback)
        measuring = false
    }

    private fun setupBackground() {
        val config = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .setShouldUserActivityInfoBeRequested(has(Manifest.permission.ACTIVITY_RECOGNITION))
            .build()
        HealthServices.getClient(this).passiveMonitoringClient
            .setPassiveListenerServiceAsync(PassiveHeartService::class.java, config)
    }
}
